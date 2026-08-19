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

// Sorted array of UIDs Java wants routed through the remote tunnel, plus the
// default applied to every UID not in it. Written from the Java thread during
// a reload and read by the tunnel thread on the packet path, so both sides
// take route_lock. The packet path is otherwise single-threaded (one
// tunnelThread runs jni_run), which is why nothing else here needs a lock.
static pthread_mutex_t route_lock = PTHREAD_MUTEX_INITIALIZER;
static jint *route_uids = NULL;
static int route_uid_count = 0;
static int route_default_tunnel = 1;

// Fast-path mirrors of the two facts the packet path needs before it knows
// whether resolving a UID is worth anything. Both are read per packet, so they
// are atomics rather than lock-protected: with no per-app override configured
// — the shipped default — every UID gets the same answer, and the packet path
// must not pay a mutex or a session-table walk to rediscover that.
static _Atomic int route_has_overrides = 0;
static _Atomic int route_default_tunnel_fast = 1;

static int compare_uid(const void *a, const void *b) {
    jint ua = *(const jint *) a;
    jint ub = *(const jint *) b;
    return (ua > ub) - (ua < ub);
}

void set_route_uids(const jint *uids, int count, int default_tunnel) {
    jint *copy = NULL;
    if (count > 0) {
        copy = ng_malloc(sizeof(jint) * (size_t) count, "route uids");
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

    if (pthread_mutex_unlock(&route_lock))
        log_android(ANDROID_LOG_ERROR, "route unlock failed");

    if (previous != NULL)
        ng_free(previous, __FILE__, __LINE__);
}

void clear_route_uids() {
    set_route_uids(NULL, 0, 1);
}

int is_tunnel_uid(jint uid) {
    if (pthread_mutex_lock(&route_lock)) {
        // Fall back to the safest answer: keep the app in the tunnel.
        log_android(ANDROID_LOG_ERROR, "route lock failed, tunnelling uid %d", uid);
        return 1;
    }

    int tunnel;
    // An unresolved UID has no per-app answer, and neither does system traffic
    // that belongs to no installed app. Both follow the global default, which
    // makes the default mode byte-identical to the behaviour before per-app
    // routing existed.
    if (uid < 0 || route_uids == NULL)
        tunnel = route_default_tunnel;
    else
        tunnel = bsearch(&uid, route_uids, (size_t) route_uid_count, sizeof(jint), compare_uid)
                 != NULL;

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
