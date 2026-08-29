/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import net.kollnig.missioncontrol.data.Tracker;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomically publishes the hostname and tracker verdict for an IP address.
 *
 * <p>The generation is advanced together with invalidation. Writers that read
 * the database outside this cache can publish only when their generation is
 * still current. The lock makes that check and publication atomic with
 * invalidation, so a stale database result cannot be reintroduced after a
 * cache clear.</p>
 */
final class TrackerCache {
    static final class Entry {
        private final String hostname;
        private final Tracker tracker;
        private final String minimalCategory;
        private final long expires;

        Entry(String hostname, Tracker tracker, String minimalCategory, long expires) {
            this.hostname = Objects.requireNonNull(hostname);
            this.tracker = Objects.requireNonNull(tracker);
            this.minimalCategory = minimalCategory;
            this.expires = expires;
        }

        String getHostname() {
            return hostname;
        }

        Tracker getTracker() {
            return tracker;
        }

        /**
         * The DuckDuckGo category behind the blocking verdict for this IP, or
         * null when no DDG tracker was seen. Resolved while both the qname and
         * the aname are still in hand, so a DDG match on an uncloaked CNAME
         * target is not lost the way a verdict-time hostname lookup would lose
         * it.
         */
        String getMinimalCategory() {
            return minimalCategory;
        }

        boolean isExpired(long now) {
            return now > expires;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();
    private final AtomicLong generation = new AtomicLong();

    Entry get(String address, long now) {
        Entry entry = entries.get(address);
        if (entry != null && entry.isExpired(now)) {
            entries.remove(address, entry);
            return null;
        }
        return entry;
    }

    long generation() {
        return generation.get();
    }

    boolean putIfGeneration(String address, Entry entry, long expectedGeneration) {
        Objects.requireNonNull(entry);
        synchronized (mutationLock) {
            if (generation.get() != expectedGeneration)
                return false;

            entries.put(address, entry);
            return true;
        }
    }

    void invalidate(String address) {
        synchronized (mutationLock) {
            entries.remove(address);
            generation.incrementAndGet();
        }
    }

    void clear() {
        synchronized (mutationLock) {
            entries.clear();
            generation.incrementAndGet();
        }
    }
}
