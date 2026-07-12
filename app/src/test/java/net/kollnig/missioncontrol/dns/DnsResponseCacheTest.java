/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Copyright © 2026 TrackerControl
 */

package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

/**
 * Unit tests for the DoH response cache — the primary idle-battery mitigation for
 * Secure DNS (issue #652). Pure JVM, no device needed.
 */
public class DnsResponseCacheTest {

    /** Mutable clock so tests can advance time deterministically. */
    private static final class FakeClock implements DnsResponseCache.Clock {
        long now = 1_000_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    // --- DNS wire-format builders ---

    private static byte[] encodeName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes();
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] buildQuery(int id, String name, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id >>> 8);
        out.write(id & 0xFF);
        out.write(0x01);
        out.write(0x00); // standard query, RD
        out.write(0x00);
        out.write(0x01); // qdcount = 1
        out.write(0x00);
        out.write(0x00); // ancount
        out.write(0x00);
        out.write(0x00); // nscount
        out.write(0x00);
        out.write(0x00); // arcount
        byte[] qname = encodeName(name);
        out.write(qname, 0, qname.length);
        out.write(qtype >>> 8);
        out.write(qtype & 0xFF);
        out.write(0x00);
        out.write(0x01); // class IN
        return out.toByteArray();
    }

    /** One A-record answer, using a name pointer back to the question. */
    private static byte[] buildResponse(int id, String name, int qtype, long ttl, int rcode, int ancount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id >>> 8);
        out.write(id & 0xFF);
        out.write(0x81);
        out.write(0x80 | (rcode & 0x0F)); // QR=1, RD, RA, rcode
        out.write(0x00);
        out.write(0x01); // qdcount = 1
        out.write(ancount >>> 8);
        out.write(ancount & 0xFF);
        out.write(0x00);
        out.write(0x00); // nscount
        out.write(0x00);
        out.write(0x00); // arcount
        byte[] qname = encodeName(name);
        out.write(qname, 0, qname.length);
        out.write(qtype >>> 8);
        out.write(qtype & 0xFF);
        out.write(0x00);
        out.write(0x01); // class IN
        for (int i = 0; i < ancount; i++) {
            out.write(0xC0);
            out.write(0x0C); // name pointer -> offset 12
            out.write(qtype >>> 8);
            out.write(qtype & 0xFF);
            out.write(0x00);
            out.write(0x01); // class IN
            out.write((int) ((ttl >>> 24) & 0xFF));
            out.write((int) ((ttl >>> 16) & 0xFF));
            out.write((int) ((ttl >>> 8) & 0xFF));
            out.write((int) (ttl & 0xFF));
            out.write(0x00);
            out.write(0x04); // rdlength = 4
            out.write(93);
            out.write(184);
            out.write(216);
            out.write(34 + i); // A record
        }
        return out.toByteArray();
    }

    private static long readTtl(byte[] msg, int offset) {
        return ((long) (msg[offset] & 0xFF) << 24)
                | ((msg[offset + 1] & 0xFF) << 16)
                | ((msg[offset + 2] & 0xFF) << 8)
                | (msg[offset + 3] & 0xFF);
    }

    // TTL field of the first answer: 12 header + question(example.com=13 + 4) + name ptr(2) + type(2) + class(2)
    private static final int FIRST_ANSWER_TTL_OFFSET = 12 + 13 + 4 + 2 + 2 + 2;

    @Test
    public void missReturnsNull() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        assertNull(cache.get(buildQuery(0x1234, "example.com", 1)));
    }

    @Test
    public void hitReturnsResponseWithCallersTransactionId() {
        FakeClock clock = new FakeClock();
        DnsResponseCache cache = new DnsResponseCache(clock);

        byte[] response = buildResponse(0x1234, "example.com", 1, 300, 0, 1);
        cache.put(buildQuery(0x1234, "example.com", 1), response);

        // A different query id for the same question must hit and get its own id back.
        byte[] hit = cache.get(buildQuery(0xABCD, "example.com", 1));
        assertNotNull(hit);
        assertEquals(0xAB, hit[0] & 0xFF);
        assertEquals(0xCD, hit[1] & 0xFF);
        assertEquals(1, cache.size());
    }

    @Test
    public void hitRewritesTtlToRemainingLifetime() {
        FakeClock clock = new FakeClock();
        DnsResponseCache cache = new DnsResponseCache(clock);

        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 300, 0, 1));

        clock.now += 100_000L; // 100 s later
        byte[] hit = cache.get(buildQuery(2, "example.com", 1));
        assertNotNull(hit);
        assertEquals(200, readTtl(hit, FIRST_ANSWER_TTL_OFFSET));
    }

    @Test
    public void expiredEntryIsEvicted() {
        FakeClock clock = new FakeClock();
        DnsResponseCache cache = new DnsResponseCache(clock);

        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 60, 0, 1));

        clock.now += 61_000L;
        assertNull(cache.get(buildQuery(2, "example.com", 1)));
        assertEquals(0, cache.size());
    }

    @Test
    public void shortTtlIsClampedToFloor() {
        FakeClock clock = new FakeClock();
        DnsResponseCache cache = new DnsResponseCache(clock);

        // TTL of 5 s should be honoured as MIN_TTL_SECONDS (still cacheable).
        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 5, 0, 1));
        clock.now += 6_000L; // past the record TTL but within the floor
        assertNotNull(cache.get(buildQuery(2, "example.com", 1)));
    }

    @Test
    public void zeroTtlIsNotCached() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 0, 0, 1));
        assertNull(cache.get(buildQuery(2, "example.com", 1)));
    }

    @Test
    public void nxdomainIsNotCached() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        // rcode 3 = NXDOMAIN, with an answer count of 0.
        cache.put(buildQuery(1, "nope.example.com", 1),
                buildResponse(1, "nope.example.com", 1, 300, 3, 0));
        assertNull(cache.get(buildQuery(2, "nope.example.com", 1)));
    }

    @Test
    public void answerlessNoerrorIsNotCached() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 300, 0, 0));
        assertNull(cache.get(buildQuery(2, "example.com", 1)));
    }

    @Test
    public void differentQtypeIsSeparateEntry() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 300, 0, 1));
        // AAAA (type 28) for the same name must not hit the A entry.
        assertNull(cache.get(buildQuery(2, "example.com", 28)));
    }

    @Test
    public void nameMatchingIsCaseInsensitive() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        cache.put(buildQuery(1, "Example.COM", 1),
                buildResponse(1, "Example.COM", 1, 300, 0, 1));
        assertNotNull(cache.get(buildQuery(2, "example.com", 1)));
    }

    @Test
    public void multiRecordAnswerUsesMinimumTtl() {
        FakeClock clock = new FakeClock();
        DnsResponseCache cache = new DnsResponseCache(clock);

        // Build a two-answer response manually with TTLs 300 and 100.
        byte[] resp = buildTwoAnswerResponse(1, "example.com", 300, 100);
        cache.put(buildQuery(1, "example.com", 1), resp);

        clock.now += 101_000L; // past the smaller TTL (100 s)
        assertNull(cache.get(buildQuery(2, "example.com", 1)));
    }

    @Test
    public void clearEmptiesCache() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        cache.put(buildQuery(1, "example.com", 1),
                buildResponse(1, "example.com", 1, 300, 0, 1));
        assertTrue(cache.size() > 0);
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void cachedResponseBytesAreCopied() {
        DnsResponseCache cache = new DnsResponseCache(new FakeClock());
        byte[] response = buildResponse(1, "example.com", 1, 300, 0, 1);
        cache.put(buildQuery(1, "example.com", 1), response);

        byte[] first = cache.get(buildQuery(0xAAAA, "example.com", 1));
        byte[] second = cache.get(buildQuery(0xBBBB, "example.com", 1));
        assertNotNull(first);
        assertNotNull(second);
        // Mutating the returned buffer must not corrupt the stored entry.
        first[0] = 0x00;
        assertEquals(0xBB, second[0] & 0xFF);
        assertArrayEquals(new byte[]{(byte) 0xBB, (byte) 0xBB},
                new byte[]{second[0], second[1]});
    }

    private static byte[] buildTwoAnswerResponse(int id, String name, long ttl1, long ttl2) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id >>> 8);
        out.write(id & 0xFF);
        out.write(0x81);
        out.write(0x80);
        out.write(0x00);
        out.write(0x01); // qdcount
        out.write(0x00);
        out.write(0x02); // ancount = 2
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        byte[] qname = encodeName(name);
        out.write(qname, 0, qname.length);
        out.write(0x00);
        out.write(0x01); // type A
        out.write(0x00);
        out.write(0x01); // class IN
        long[] ttls = {ttl1, ttl2};
        for (int i = 0; i < 2; i++) {
            out.write(0xC0);
            out.write(0x0C);
            out.write(0x00);
            out.write(0x01);
            out.write(0x00);
            out.write(0x01);
            out.write((int) ((ttls[i] >>> 24) & 0xFF));
            out.write((int) ((ttls[i] >>> 16) & 0xFF));
            out.write((int) ((ttls[i] >>> 8) & 0xFF));
            out.write((int) (ttls[i] & 0xFF));
            out.write(0x00);
            out.write(0x04);
            out.write(93);
            out.write(184);
            out.write(216);
            out.write(34 + i);
        }
        return out.toByteArray();
    }
}
