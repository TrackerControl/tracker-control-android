/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Copyright © 2026
 */

// Routing policy for both egress paths. The decision itself lives in
// wgbridge-rs/src/policy.rs, where `cargo test` covers it in CI; this file
// reaches it, caches the handful of facts the packet path needs so the common
// case never crosses the boundary at all, and remembers a flow's verdict.
// The pure packet decision is mirrored below so the packet path does not cross
// the Rust FFI boundary on every packet; the equivalent Rust function remains
// the tested definition of that decision.

#include "netguard.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <stdatomic.h>

#define POLICY_ABI_VERSION 1

static pthread_once_t policy_once = PTHREAD_ONCE_INIT;
static int policy_ok = 0;

static int (*p_abi_version)(void) = NULL;
static void (*p_set_route_uids)(const jint *uids, int count, int default_tunnel) = NULL;
static void (*p_clear_route_uids)(void) = NULL;
static int (*p_is_tunnel_uid)(jint uid) = NULL;
static int (*p_wants_tunnel)(int local_dest, int is_dns, int tunnel_uid, int dns_direct) = NULL;

// Facts the packet path reads per packet. Mirrored here rather than queried
// across the boundary: with no per-app override configured — the shipped
// default — every UID gets the same answer, and rediscovering that per packet
// is what made the fork expensive enough to show up as degraded DNS.
static _Atomic int policy_has_overrides = 0;
static _Atomic int policy_default_tunnel = 1;
static _Atomic int policy_dns_direct = 0;

static void policy_load() {
    // Java loads libnetguard only, so the bridge is resolved here rather than
    // linked: a DT_NEEDED would stop libnetguard loading at all whenever the
    // Rust library is missing, and would couple the CMake output to a cargo
    // one that is deliberately built late (see app/gradle/wgbridge.gradle).
    // Java's own System.loadLibrary("wgbridge") returns this same soinfo when
    // the tunnel starts, so there is exactly one policy table. Never dlclose.
    void *handle = dlopen("libwgbridge.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        log_android(ANDROID_LOG_ERROR, "policy: cannot load libwgbridge: %s", dlerror());
        return;
    }

    p_abi_version = dlsym(handle, "tc_policy_abi_version");
    p_set_route_uids = dlsym(handle, "tc_policy_set_route_uids");
    p_clear_route_uids = dlsym(handle, "tc_policy_clear_route_uids");
    p_is_tunnel_uid = dlsym(handle, "tc_policy_is_tunnel_uid");
    p_wants_tunnel = dlsym(handle, "tc_policy_wants_tunnel");

    if (p_abi_version == NULL || p_set_route_uids == NULL || p_clear_route_uids == NULL ||
        p_is_tunnel_uid == NULL || p_wants_tunnel == NULL) {
        log_android(ANDROID_LOG_ERROR, "policy: missing symbol: %s", dlerror());
        return;
    }

    int abi = p_abi_version();
    if (abi != POLICY_ABI_VERSION) {
        log_android(ANDROID_LOG_ERROR, "policy: ABI %d, expected %d", abi, POLICY_ABI_VERSION);
        return;
    }

    policy_ok = 1;
    log_android(ANDROID_LOG_WARN, "policy: libwgbridge %p ABI %d", handle, abi);
}

void policy_ensure() {
    pthread_once(&policy_once, policy_load);
}

void set_route_uids(const jint *uids, int count, int default_tunnel, int dns_direct) {
    policy_ensure();

    if (count < 0 || uids == NULL)
        count = 0;

    if (policy_ok)
        p_set_route_uids(uids, count, default_tunnel);

    // A missing/incompatible bridge cannot honour a selected-app policy. Keep
    // the conservative state instead: all eligible traffic takes the tunnel,
    // and direct-app DNS is not allowed to opt out of that protection. This is
    // deliberately independent of the requested default; otherwise a failed
    // load in selected mode would silently route the selected app directly.
    int effective_default_tunnel = policy_ok ? (default_tunnel != 0) : 1;
    int effective_dns_direct = policy_ok ? (dns_direct != 0) : 0;
    atomic_store_explicit(&policy_default_tunnel, effective_default_tunnel,
                          memory_order_release);
    atomic_store_explicit(&policy_dns_direct, effective_dns_direct,
                          memory_order_release);
    // Without the bridge there is no table to consult, so pin every UID to the
    // safe global default. A per-app choice is ignored rather than leaking out
    // of the tunnel.
    atomic_store_explicit(&policy_has_overrides,
                          (policy_ok && count > 0) ? 1 : 0, memory_order_release);

    // Verdicts cached against the previous rules must not survive them.
    route_flow_invalidate();
}

void clear_route_uids() {
    policy_ensure();

    if (policy_ok)
        p_clear_route_uids();

    atomic_store_explicit(&policy_has_overrides, 0, memory_order_release);
    atomic_store_explicit(&policy_default_tunnel, 1, memory_order_release);
    atomic_store_explicit(&policy_dns_direct, 0, memory_order_release);

    route_flow_invalidate();
}

int is_tunnel_uid(jint uid) {
    if (policy_ok)
        return p_is_tunnel_uid(uid);

    return route_default_is_tunnel();
}

/**
 * Whether resolving this packet's UID can change the routing answer.
 *
 * With no per-app override configured, every UID resolves to the same global
 * default, so the packet path can skip the UID lookup and the boundary
 * crossing. This is the shipped default, and keeping it free is what holds the
 * per-packet cost at what it was before per-app routing existed.
 */
int route_uid_relevant() {
    return atomic_load_explicit(&policy_has_overrides, memory_order_acquire);
}

/** The answer every UID gets when no override is configured. */
int route_default_is_tunnel() {
    return atomic_load_explicit(&policy_default_tunnel, memory_order_acquire);
}

/** Whether direct apps' DNS is redirected, in which case DNS follows the UID. */
int route_dns_direct() {
    return atomic_load_explicit(&policy_dns_direct, memory_order_acquire);
}

int route_wants_tunnel(int local_dest, int is_dns, int tunnel_uid, int dns_direct) {
    // This is the literal transcription of policy::wants_tunnel. Keep the
    // pure branch local: crossing the Rust boundary for every packet adds
    // overhead without consulting any mutable policy state. The Rust function
    // remains exported and exhaustively tested as the policy definition.
    if (local_dest && !is_dns)
        return 0;
    if (is_dns && !dns_direct)
        return 1;
    return tunnel_uid;
}

// --- Per-flow verdict cache -------------------------------------------------
//
// A tunnelled packet is handed to WireGuard and returns before handle_tcp /
// handle_udp run, so no ng_session is ever created for it. Meanwhile the UID
// lookup upstream is skipped for non-SYN TCP and existing UDP, so packet 2+ of
// a tunnelled flow arrives with uid == -1 and nothing left to resolve it from.
// Falling back to the global default there is wrong in exactly the mode the
// feature exists for: in "selected" mode the default is direct, so the rest of
// a tunnelled TCP flow was routed direct and handle_tcp, finding no session for
// a non-SYN segment, answered it with an RST.
//
// So remember the verdict per flow, keyed on the 5-tuple, and consult it before
// giving up. This is a cache rather than policy, which is why it stays on this
// side of the boundary. Written and read only by the tunnel thread, which is
// why nothing here takes a lock; a reload bumps route_flow_gen instead of
// clearing the table, so entries decided under superseded rules stop matching.

// 4-way set-associative: same 1024-entry / ~56 KB budget as the original
// direct-mapped table, but two concurrently active flows that hash to the
// same set no longer evict each other every packet. A direct-mapped table
// made that eviction ping-pong guaranteed for any colliding pair of busy
// flows, and every miss it caused fell through to a per-packet Binder/procfs
// UID lookup — the exact cost this cache exists to avoid.
#define ROUTE_FLOW_WAYS 4
#define ROUTE_FLOW_SETS 256         // power of two; SETS * WAYS = 1024 entries
#define ROUTE_FLOW_MAX_AGE 300      // seconds idle before an entry is reusable

struct route_flow_entry {
    uint32_t gen;                   // 0 = never written
    uint8_t version;
    uint8_t protocol;
    uint8_t tunnel;
    uint8_t uid_known;
    uint16_t sport;
    uint16_t dport;
    uint8_t saddr[16];
    uint8_t daddr[16];
    time_t time;
};

static struct route_flow_entry route_flows[ROUTE_FLOW_SETS][ROUTE_FLOW_WAYS];
static _Atomic uint32_t route_flow_gen = 1;

void route_flow_invalidate() {
    // Wrapping to 0 would make every never-written slot look current, so skip it.
    uint32_t next = atomic_fetch_add_explicit(&route_flow_gen, 1, memory_order_release) + 1;
    if (next == 0)
        atomic_store_explicit(&route_flow_gen, 1, memory_order_release);
}

static size_t route_flow_set(int version, int protocol,
                             const void *saddr, uint16_t sport,
                             const void *daddr, uint16_t dport) {
    size_t alen = (version == 4 ? 4u : 16u);
    // FNV-1a
    uint32_t h = 2166136261u;
    const uint8_t *s = saddr;
    const uint8_t *d = daddr;
    for (size_t i = 0; i < alen; i++) {
        h = (h ^ s[i]) * 16777619u;
        h = (h ^ d[i]) * 16777619u;
    }
    h = (h ^ (uint8_t) version) * 16777619u;
    h = (h ^ (uint8_t) protocol) * 16777619u;
    h = (h ^ (uint8_t) (sport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (sport >> 8)) * 16777619u;
    h = (h ^ (uint8_t) (dport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (dport >> 8)) * 16777619u;
    return (size_t) (h & (ROUTE_FLOW_SETS - 1));
}

static int route_flow_matches(const struct route_flow_entry *e, uint32_t gen,
                              int version, int protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport, time_t now) {
    size_t alen = (version == 4 ? 4u : 16u);
    return e->gen == gen &&
           e->version == (uint8_t) version && e->protocol == (uint8_t) protocol &&
           e->sport == sport && e->dport == dport &&
           memcmp(e->saddr, saddr, alen) == 0 && memcmp(e->daddr, daddr, alen) == 0 &&
           now - e->time <= ROUTE_FLOW_MAX_AGE;
}

/**
 * The remembered verdict for this flow, if any.
 *
 * @return 1 when *tunnel was filled in, 0 on a miss.
 */
int route_flow_lookup(int version, int protocol,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport,
                      int *tunnel, int *uid_known) {
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    struct route_flow_entry *set = route_flows[route_flow_set(
            version, protocol, saddr, sport, daddr, dport)];

    time_t now = time(NULL);
    for (int way = 0; way < ROUTE_FLOW_WAYS; way++) {
        struct route_flow_entry *e = &set[way];
        if (!route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now))
            continue;
        e->time = now;
        *tunnel = e->tunnel;
        *uid_known = e->uid_known;
        return 1;
    }
    return 0;
}

/**
 * Remember this flow's verdict, in the first empty/stale/current-gen-oldest
 * way of its set. It is a cache, and a miss only costs the fallback path that
 * ran before it existed. uid_known distinguishes a stable per-app decision
 * from the fail-closed tunnel route used while Android cannot resolve an owner.
 */
void route_flow_store(int version, int protocol,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport,
                      int tunnel, int uid_known) {
    size_t alen = (version == 4 ? 4u : 16u);
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    struct route_flow_entry *set = route_flows[route_flow_set(
            version, protocol, saddr, sport, daddr, dport)];

    // Pick a way to write, cheapest-safe choice first: reuse this flow's own
    // entry if it is already cached, else an empty/superseded/expired way,
    // else evict the oldest entry in the set (a working set wider than 4
    // concurrently-hot flows per set degrades to more fallback lookups, not
    // to a wrong answer).
    time_t now = time(NULL);
    struct route_flow_entry *victim = NULL;
    struct route_flow_entry *oldest = &set[0];
    for (int way = 0; way < ROUTE_FLOW_WAYS; way++) {
        struct route_flow_entry *e = &set[way];
        if (route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now)) {
            victim = e;
            break;
        }
        if (victim == NULL && (e->gen != gen || now - e->time > ROUTE_FLOW_MAX_AGE))
            victim = e;
        if (e->time < oldest->time)
            oldest = e;
    }
    if (victim == NULL)
        victim = oldest;

    struct route_flow_entry *e = victim;
    e->gen = gen;
    e->version = (uint8_t) version;
    e->protocol = (uint8_t) protocol;
    e->tunnel = (uint8_t) (tunnel ? 1 : 0);
    e->uid_known = (uint8_t) (uid_known ? 1 : 0);
    e->sport = sport;
    e->dport = dport;
    memset(e->saddr, 0, sizeof(e->saddr));
    memset(e->daddr, 0, sizeof(e->daddr));
    memcpy(e->saddr, saddr, alen);
    memcpy(e->daddr, daddr, alen);
    e->time = now;
}
