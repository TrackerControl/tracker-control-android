/*
    This file is part of TrackerControl.

    TrackerControl is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    TrackerControl is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
*/

// Packet-loop instrumentation for issue #653: measure how often the packet loop
// is woken and how much CPU it burns, so that routing system apps through the
// tun can be compared against excluding them without guessing from throughput.

#include "netguard.h"

void loop_stats_reset(struct loop_stats *stats) {
    // Only the counters are reset; ordering does not matter because the loop
    // thread has not started (or has already stopped) when this runs.
    atomic_store_explicit(&stats->started_ms, (uint64_t) get_ms(), memory_order_relaxed);
    atomic_store_explicit(&stats->iterations, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->polls, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->wakeups, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->timeouts, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->recheck_polls, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->events_tun, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->events_sock, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->events_pipe, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->tun_packets, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->tun_bytes, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->cpu_us, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->scan_us, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->dispatch_us, 0, memory_order_relaxed);
    atomic_store_explicit(&stats->uid_overflow, 0, memory_order_relaxed);

    for (int i = 0; i < LOOP_UID_SLOTS; i++) {
        stats->uids[i].uid = LOOP_UID_FREE;
        stats->uids[i].sessions = 0;
        stats->uids[i].events = 0;
    }
}

void loop_stats_add(atomic_uint_least64_t *counter, uint64_t delta) {
    atomic_fetch_add_explicit(counter, delta, memory_order_relaxed);
}

uint64_t loop_stats_get(const atomic_uint_least64_t *counter) {
    return atomic_load_explicit(counter, memory_order_relaxed);
}

long long get_us() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000LL;
}

uint64_t get_thread_cpu_us() {
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts))
        return 0;
    return (uint64_t) ts.tv_sec * 1000000ULL + (uint64_t) ts.tv_nsec / 1000ULL;
}

// Linear probing over a fixed table. The table is small and the number of UIDs
// generating traffic on a device is smaller still, so a scan is cheaper than a
// hash; once full, further UIDs are counted as overflow rather than evicting
// existing entries (evictions would silently distort the comparison).
static struct loop_uid_stat *loop_stats_uid_slot(struct loop_stats *stats, jint uid) {
    struct loop_uid_stat *free_slot = NULL;
    for (int i = 0; i < LOOP_UID_SLOTS; i++) {
        if (stats->uids[i].uid == uid)
            return &stats->uids[i];
        if (free_slot == NULL && stats->uids[i].uid == LOOP_UID_FREE)
            free_slot = &stats->uids[i];
    }

    if (free_slot == NULL) {
        loop_stats_add(&stats->uid_overflow, 1);
        return NULL;
    }

    free_slot->uid = uid;
    return free_slot;
}

void loop_stats_uid_session(struct loop_stats *stats, jint uid) {
    struct loop_uid_stat *slot = loop_stats_uid_slot(stats, uid);
    if (slot != NULL)
        slot->sessions++;
}

void loop_stats_uid_event(struct loop_stats *stats, jint uid) {
    struct loop_uid_stat *slot = loop_stats_uid_slot(stats, uid);
    if (slot != NULL)
        slot->events++;
}

// Emitted when the packet loop stops so a measurement run leaves a record in
// logcat even if nothing polled jni_get_loop_stats() beforehand. Java produces
// the readable, per-app report; this is the fallback totals line.
//
// Must be called from the packet-loop thread: it takes the final
// CLOCK_THREAD_CPUTIME_ID sample, which is only meaningful on that thread.
void log_loop_stats(struct context *ctx) {
    struct loop_stats *stats = &ctx->stats;
    uint64_t iterations = loop_stats_get(&stats->iterations);
    if (iterations == 0)
        return;

    atomic_store_explicit(&stats->cpu_us, get_thread_cpu_us(), memory_order_relaxed);

    uint64_t started_ms = loop_stats_get(&stats->started_ms);
    uint64_t elapsed_ms = (uint64_t) get_ms() - started_ms;

    log_android(ANDROID_LOG_WARN,
                "Packet loop stats elapsed %llu ms iterations %llu polls %llu "
                "wakeups %llu timeouts %llu recheck %llu "
                "events tun %llu sock %llu pipe %llu "
                "tun packets %llu bytes %llu "
                "cpu %llu us scan %llu us dispatch %llu us uid overflow %llu",
                // uint64_t is "unsigned long" on LP64 and "unsigned long long" on
                // ILP32, so cast rather than relying on %llu matching either one.
                (unsigned long long) elapsed_ms,
                (unsigned long long) iterations,
                (unsigned long long) loop_stats_get(&stats->polls),
                (unsigned long long) loop_stats_get(&stats->wakeups),
                (unsigned long long) loop_stats_get(&stats->timeouts),
                (unsigned long long) loop_stats_get(&stats->recheck_polls),
                (unsigned long long) loop_stats_get(&stats->events_tun),
                (unsigned long long) loop_stats_get(&stats->events_sock),
                (unsigned long long) loop_stats_get(&stats->events_pipe),
                (unsigned long long) loop_stats_get(&stats->tun_packets),
                (unsigned long long) loop_stats_get(&stats->tun_bytes),
                (unsigned long long) loop_stats_get(&stats->cpu_us),
                (unsigned long long) loop_stats_get(&stats->scan_us),
                (unsigned long long) loop_stats_get(&stats->dispatch_us),
                (unsigned long long) loop_stats_get(&stats->uid_overflow));
}
