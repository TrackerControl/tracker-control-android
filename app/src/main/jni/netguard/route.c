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

#include "netguard.h"

#include <stdlib.h>
#include <stdatomic.h>

// Sorted array of the UIDs whose routing *differs* from the global default,
// plus that default. Written from the Java thread during a reload and read by
// the tunnel thread on the packet path, so both sides take route_lock. The
// packet path is otherwise single-threaded (one tunnelThread runs jni_run),
// which is why nothing else here needs a lock.
//
// Only the exceptions are pushed, never the whole tunnelled set. In the default
// mode every applied app is tunnelled, so a "tunnelled UIDs" array held every
// installed app and was indistinguishable from a heavily-overridden one — which
// made route_uid_relevant() below always true and cost every user, WireGuard or
// not, a per-packet lock and session-table walk.
static pthread_mutex_t route_lock = PTHREAD_MUTEX_INITIALIZER;
static jint *route_uids = NULL;
static int route_uid_count = 0;
static int route_default_tunnel = 1;

// Fast-path mirrors of the facts the packet path needs before it knows whether
// resolving a UID is worth anything. All are read per packet, so they are
// atomics rather than lock-protected: with no per-app override configured —
// the shipped default — every UID gets the same answer, and the packet path
// must not pay a mutex or a session-table walk to rediscover that.
static _Atomic int route_has_overrides = 0;
static _Atomic int route_default_tunnel_fast = 1;

// Whether direct apps' DNS is redirected to the system resolver. Its own flag
// rather than a reuse of args->fwd53: that one is also set by an unrelated
// port-53 forward (Secure DNS runs one whenever WireGuard carries no DNS line
// of its own), and borrowing it silently switched off the rule that every
// resolver query takes the tunnel.
static _Atomic int route_dns_direct_fast = 0;

static int compare_uid(const void *a, const void *b) {
    jint ua = *(const jint *) a;
    jint ub = *(const jint *) b;
    return (ua > ub) - (ua < ub);
}

void set_route_uids(const jint *uids, int count, int default_tunnel, int dns_direct) {
    jint *copy = NULL;
    if (count > 0) {
        copy = ng_malloc(sizeof(jint) * (size_t) count, "route uids");
        if (copy == NULL) {
            log_android(ANDROID_LOG_ERROR, "route uids alloc failed, keeping previous routing");
            return;
        }
        memcpy(copy, uids, sizeof(jint) * (size_t) count);
        qsort(copy, (size_t) count, sizeof(jint), compare_uid);
    }

    if (pthread_mutex_lock(&route_lock)) {
        log_android(ANDROID_LOG_ERROR, "route lock failed, keeping previous routing");
        if (copy != NULL)
            ng_free(copy, __FILE__, __LINE__);
        return;
    }

    jint *previous = route_uids;
    route_uids = copy;
    route_uid_count = count;
    route_default_tunnel = default_tunnel;

    atomic_store_explicit(&route_has_overrides, count > 0 ? 1 : 0, memory_order_release);
    atomic_store_explicit(&route_default_tunnel_fast, default_tunnel, memory_order_release);
    atomic_store_explicit(&route_dns_direct_fast, dns_direct, memory_order_release);

    if (pthread_mutex_unlock(&route_lock))
        log_android(ANDROID_LOG_ERROR, "route unlock failed");

    // Verdicts cached against the previous rules must not survive them.
    route_flow_invalidate();

    if (previous != NULL)
        ng_free(previous, __FILE__, __LINE__);
}

void clear_route_uids() {
    set_route_uids(NULL, 0, 1, 0);
}

int is_tunnel_uid(jint uid) {
    if (pthread_mutex_lock(&route_lock)) {
        // Fall back to the safest answer: keep the app in the tunnel.
        log_android(ANDROID_LOG_ERROR, "route lock failed, tunnelling uid %d", uid);
        return 1;
    }

    int tunnel;
    // Only UIDs the user gave an explicit, differing answer for are listed.
    // Everything else — an unresolved UID, system traffic, an app installed
    // since the last reload — follows the global default, which is what makes
    // the default mode identical to the behaviour before per-app routing
    // existed.
    if (uid < 0 || route_uids == NULL)
        tunnel = route_default_tunnel;
    else if (bsearch(&uid, route_uids, (size_t) route_uid_count, sizeof(jint), compare_uid)
             != NULL)
        tunnel = !route_default_tunnel;
    else
        tunnel = route_default_tunnel;

    if (pthread_mutex_unlock(&route_lock))
        log_android(ANDROID_LOG_ERROR, "route unlock failed");

    return tunnel;
}

/**
 * Whether resolving this packet's UID can change the routing answer.
 *
 * With no per-app override configured, every UID resolves to the same global
 * default, so the packet path can skip both the UID lookup and the lock. This
 * is the shipped default, and keeping it free is what holds the per-packet cost
 * at what it was before per-app routing existed.
 */
int route_uid_relevant() {
    return atomic_load_explicit(&route_has_overrides, memory_order_acquire);
}

/** The answer every UID gets when no override is configured. */
int route_default_is_tunnel() {
    return atomic_load_explicit(&route_default_tunnel_fast, memory_order_acquire);
}

/** Whether direct apps' DNS is redirected, in which case DNS follows the UID. */
int route_dns_direct() {
    return atomic_load_explicit(&route_dns_direct_fast, memory_order_acquire);
}

/**
 * Whether this packet belongs in the tunnel. Pure, so the rule can be read
 * straight through — the caller still decides what to do when the tunnel is
 * down, because only the write attempt can say whether it is.
 *
 * @param local_dest loopback / link-local / multicast destination
 * @param is_dns     port 53, over UDP or TCP
 * @param tunnel_uid whether this packet's UID is routed through the tunnel
 * @param dns_direct whether direct apps' DNS is redirected to the system
 *                   resolver; while false, DNS always takes the tunnel
 */
int route_wants_tunnel(int local_dest, int is_dns, int tunnel_uid, int dns_direct) {
    // Destinations WireGuard cannot meaningfully forward never take the tunnel,
    // whichever app sent them.
    if (local_dest && !is_dns)
        return 0;

    // Unless a direct app's DNS is being redirected, every resolver query takes
    // the tunnel: sending it out directly would expose the user's physical
    // network to the resolver.
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
// giving up. Written and read only by the tunnel thread, which is why nothing
// here takes a lock; a reload bumps route_flow_gen instead of clearing the
// table, so entries decided under superseded rules simply stop matching.

#define ROUTE_FLOW_SIZE 1024        // power of two; ~40 KB resident
#define ROUTE_FLOW_MAX_AGE 300      // seconds idle before an entry is reusable

struct route_flow_entry {
    uint32_t gen;                   // 0 = free
    uint8_t version;
    uint8_t protocol;
    uint8_t tunnel;
    uint16_t sport;
    uint16_t dport;
    uint8_t saddr[16];
    uint8_t daddr[16];
    time_t time;
};

static struct route_flow_entry route_flows[ROUTE_FLOW_SIZE];
static _Atomic uint32_t route_flow_gen = 1;

void route_flow_invalidate() {
    // Wrapping past 0 would resurrect free slots, so skip it.
    uint32_t next = atomic_fetch_add_explicit(&route_flow_gen, 1, memory_order_release) + 1;
    if (next == 0)
        atomic_store_explicit(&route_flow_gen, 1, memory_order_release);
}

static size_t route_flow_slot(int version, int protocol,
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
    return (size_t) (h & (ROUTE_FLOW_SIZE - 1));
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
                      int *tunnel) {
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    struct route_flow_entry *e = &route_flows[route_flow_slot(
            version, protocol, saddr, sport, daddr, dport)];

    time_t now = time(NULL);
    if (!route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now))
        return 0;

    e->time = now;
    *tunnel = e->tunnel;
    return 1;
}

/**
 * Remember this flow's verdict. One slot per hash, overwritten on collision —
 * it is a cache, and a miss only costs the fallback path that ran before it
 * existed. Never called for a packet whose UID was unknown: pinning a guessed
 * default for the life of a flow is the failure this exists to prevent.
 */
void route_flow_store(int version, int protocol,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport,
                      int tunnel) {
    size_t alen = (version == 4 ? 4u : 16u);
    struct route_flow_entry *e = &route_flows[route_flow_slot(
            version, protocol, saddr, sport, daddr, dport)];

    e->gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    e->version = (uint8_t) version;
    e->protocol = (uint8_t) protocol;
    e->tunnel = (uint8_t) (tunnel ? 1 : 0);
    e->sport = sport;
    e->dport = dport;
    memset(e->saddr, 0, sizeof(e->saddr));
    memset(e->daddr, 0, sizeof(e->daddr));
    memcpy(e->saddr, saddr, alen);
    memcpy(e->daddr, daddr, alen);
    e->time = time(NULL);
}
