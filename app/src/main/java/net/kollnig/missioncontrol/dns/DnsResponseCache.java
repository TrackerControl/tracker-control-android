/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Copyright © 2026 TrackerControl
 */

package net.kollnig.missioncontrol.dns;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small, TTL-respecting DNS response cache for the Secure DNS (DoH) proxy.
 *
 * <p>Every DoH query is a full HTTPS round-trip: it wakes the radio, does a TLS
 * handshake or reuses a warm socket, and waits on the network. Background apps
 * re-resolve the same handful of hostnames over and over — including while the
 * screen is off — so serving repeat queries from a local cache removes a large
 * share of those wakeups. This is the single biggest idle-cost reduction for the
 * DoH path (see issue #652).
 *
 * <p>The cache is deliberately conservative:
 * <ul>
 *   <li>Only NOERROR responses with at least one answer record are stored.</li>
 *   <li>The entry lifetime is the <em>minimum</em> TTL across all answer records,
 *       clamped to [{@link #MIN_TTL_SECONDS}, {@link #MAX_TTL_SECONDS}]. A record
 *       with TTL 0 is never cached.</li>
 *   <li>Only single-question queries are cached (the overwhelmingly common case).</li>
 *   <li>On a hit the stored bytes are copied, the transaction ID is rewritten to
 *       match the caller, and every answer TTL is rewritten to the remaining
 *       lifetime so downstream resolvers see a sensible countdown.</li>
 * </ul>
 *
 * <p>This class is self-contained and clock-injectable so it can be unit tested
 * without a device.
 */
final class DnsResponseCache {

    /** Never cache for less than this — avoids thrashing on very short TTLs. */
    static final long MIN_TTL_SECONDS = 30;
    /** Never cache for longer than this regardless of the record TTL. */
    static final long MAX_TTL_SECONDS = 6 * 60 * 60;
    /** Upper bound on distinct cached questions. */
    static final int MAX_ENTRIES = 2048;

    private static final int HEADER_LEN = 12;

    interface Clock {
        long nowMs();
    }

    private static final class CacheEntry {
        final byte[] response;
        final long expiryMs;
        final int[] ttlOffsets;

        CacheEntry(byte[] response, long expiryMs, int[] ttlOffsets) {
            this.response = response;
            this.expiryMs = expiryMs;
            this.ttlOffsets = ttlOffsets;
        }
    }

    private final Clock clock;

    private final Map<String, CacheEntry> map =
            new LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    DnsResponseCache() {
        this(System::currentTimeMillis);
    }

    DnsResponseCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * Look up a cached response for the given query.
     *
     * @return a fresh response byte[] (id + TTLs rewritten) or {@code null} on miss/expiry.
     */
    @Nullable
    synchronized byte[] get(byte[] query) {
        String key = questionKey(query);
        if (key == null)
            return null;

        CacheEntry entry = map.get(key);
        if (entry == null)
            return null;

        long now = clock.nowMs();
        long remainingMs = entry.expiryMs - now;
        if (remainingMs <= 0) {
            map.remove(key);
            return null;
        }

        byte[] out = Arrays.copyOf(entry.response, entry.response.length);
        // Rewrite transaction ID to match the caller's query.
        out[0] = query[0];
        out[1] = query[1];
        // Rewrite each answer TTL to the remaining lifetime.
        long remainingSecs = remainingMs / 1000L;
        int remaining = (int) Math.max(0, Math.min(remainingSecs, 0xFFFFFFFFL));
        for (int off : entry.ttlOffsets) {
            out[off] = (byte) (remaining >>> 24);
            out[off + 1] = (byte) (remaining >>> 16);
            out[off + 2] = (byte) (remaining >>> 8);
            out[off + 3] = (byte) remaining;
        }
        return out;
    }

    /**
     * Store a response for the given query, if it is cacheable.
     */
    synchronized void put(byte[] query, byte[] response) {
        String key = questionKey(query);
        if (key == null)
            return;

        Parsed parsed = parseAnswers(response);
        if (parsed == null || parsed.minTtl <= 0)
            return;

        long ttl = Math.max(MIN_TTL_SECONDS, Math.min(parsed.minTtl, MAX_TTL_SECONDS));
        long expiry = clock.nowMs() + ttl * 1000L;
        map.put(key, new CacheEntry(Arrays.copyOf(response, response.length), expiry, parsed.ttlOffsets));
    }

    synchronized void clear() {
        map.clear();
    }

    synchronized int size() {
        return map.size();
    }

    // --- DNS wire-format parsing helpers ---

    /**
     * Build a cache key from the (single) question of a query: normalised qname +
     * qtype + qclass. Returns null if the message is malformed or does not have
     * exactly one question.
     */
    @Nullable
    private static String questionKey(byte[] msg) {
        if (msg == null || msg.length < HEADER_LEN)
            return null;
        int qdcount = ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        if (qdcount != 1)
            return null;

        StringBuilder name = new StringBuilder();
        int pos = HEADER_LEN;
        while (true) {
            if (pos >= msg.length)
                return null;
            int len = msg[pos] & 0xFF;
            if (len == 0) {
                pos++;
                break;
            }
            if ((len & 0xC0) != 0) // compression not allowed in the question
                return null;
            pos++;
            if (pos + len > msg.length)
                return null;
            for (int i = 0; i < len; i++) {
                char c = (char) (msg[pos + i] & 0xFF);
                if (c >= 'A' && c <= 'Z')
                    c = (char) (c + 32); // lowercase for case-insensitive matching
                name.append(c);
            }
            name.append('.');
            pos += len;
        }
        if (pos + 4 > msg.length)
            return null;
        int qtype = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
        int qclass = ((msg[pos + 2] & 0xFF) << 8) | (msg[pos + 3] & 0xFF);
        return name + "|" + qtype + "|" + qclass;
    }

    private static final class Parsed {
        final long minTtl;
        final int[] ttlOffsets;

        Parsed(long minTtl, int[] ttlOffsets) {
            this.minTtl = minTtl;
            this.ttlOffsets = ttlOffsets;
        }
    }

    /**
     * Validate that a response is a cacheable NOERROR answer and collect the
     * minimum answer TTL together with the byte offsets of every answer TTL field.
     * Returns null if the response is malformed, is not a positive answer, or has
     * no answer records.
     */
    @Nullable
    private static Parsed parseAnswers(byte[] msg) {
        if (msg == null || msg.length < HEADER_LEN)
            return null;

        boolean isResponse = (msg[2] & 0x80) != 0;
        int rcode = msg[3] & 0x0F;
        if (!isResponse || rcode != 0) // only cache NOERROR responses
            return null;

        int qdcount = ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        int ancount = ((msg[6] & 0xFF) << 8) | (msg[7] & 0xFF);
        if (ancount == 0)
            return null;

        int pos = HEADER_LEN;
        // Skip the question section.
        for (int q = 0; q < qdcount; q++) {
            pos = skipName(msg, pos);
            if (pos < 0 || pos + 4 > msg.length)
                return null;
            pos += 4; // qtype + qclass
        }

        long minTtl = Long.MAX_VALUE;
        int[] offsets = new int[ancount];
        for (int a = 0; a < ancount; a++) {
            pos = skipName(msg, pos);
            if (pos < 0 || pos + 10 > msg.length)
                return null;
            // type(2) class(2) ttl(4) rdlength(2)
            int ttlOffset = pos + 4;
            long ttl = ((long) (msg[ttlOffset] & 0xFF) << 24)
                    | ((msg[ttlOffset + 1] & 0xFF) << 16)
                    | ((msg[ttlOffset + 2] & 0xFF) << 8)
                    | (msg[ttlOffset + 3] & 0xFF);
            int rdlength = ((msg[pos + 8] & 0xFF) << 8) | (msg[pos + 9] & 0xFF);
            offsets[a] = ttlOffset;
            if (ttl < minTtl)
                minTtl = ttl;
            pos += 10 + rdlength;
            if (pos > msg.length)
                return null;
        }

        if (minTtl == Long.MAX_VALUE)
            return null;
        return new Parsed(minTtl, offsets);
    }

    /**
     * Advance past a (possibly compressed) domain name. Returns the offset just
     * after the name, or -1 on malformed input.
     */
    private static int skipName(byte[] msg, int pos) {
        while (true) {
            if (pos < 0 || pos >= msg.length)
                return -1;
            int len = msg[pos] & 0xFF;
            if (len == 0)
                return pos + 1;
            if ((len & 0xC0) == 0xC0) // compression pointer: 2 bytes, name ends here
                return pos + 2;
            pos += 1 + len;
        }
    }
}
