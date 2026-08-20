/*
 * Host unit tests for the DNS-over-TCP framing decision extracted into
 * app/src/main/jni/netguard/dns_frame.{h,c}.
 *
 * This is a plain C test program with a tiny assert-based harness (no test
 * framework dependency), so it can build and run with the system compiler
 * on any host -- see .github/workflows/test.yml. It intentionally does not
 * link netguard.h, JNI, or parse_dns_response: dns_frame_decide() and
 * dns_frame_apply_rewrite() are pure, so a DNS-parse outcome is simulated
 * inline (a "post_parse_dlen" value) instead of calling the real parser.
 *
 * Background: this framing logic shipped a real blocking bypass once
 * already, fixed in commit 9c49cc09 ("Fix DNS filtering regressions from
 * the tc-dns extraction") -- an earlier refactor skipped parse_dns_response
 * entirely unless a recv() held exactly one complete frame, so coalesced or
 * split reads went unfiltered. These tests pin the correct behavior down so
 * that regression cannot silently return.
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dns_frame.h"

static int failures = 0;

#define CHECK(cond, msg)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            fprintf(stderr, "FAIL: %s (%s:%d)\n", (msg), __FILE__, __LINE__); \
            failures++;                                                     \
        }                                                                   \
    } while (0)

/* Writes a big-endian 16-bit length prefix into buffer[0..1]. */
static void set_prefix(uint8_t *buffer, size_t frame_len) {
    buffer[0] = (uint8_t) (frame_len >> 8);
    buffer[1] = (uint8_t) frame_len;
}

static size_t read_prefix(const uint8_t *buffer) {
    return ((size_t) buffer[0] << 8) | buffer[1];
}

/* A test double standing in for parse_dns_response(): optionally shrinks
 * dlen (simulating a policy hit that blanks/truncates the DNS message),
 * or leaves it unchanged. The extracted helpers never call this -- they
 * only ever see its *result*, which is exactly the point of extracting
 * them as pure functions. */
static size_t stub_parse_unchanged(size_t dlen) {
    return dlen;
}

static size_t stub_parse_shrink_to(size_t new_len) {
    return new_len;
}

/* 1. Isolated complete frame: parser shortens it -> shorten + prefix rewritten. */
static void test_isolated_frame_shortened(void) {
    size_t frame_len = 50;
    ssize_t bytes = (ssize_t) (frame_len + 2);
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xAA, frame_len);

    struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
    CHECK(d.should_parse, "isolated frame: should_parse");
    CHECK(d.isolated, "isolated frame: isolated");
    CHECK(d.dlen == frame_len, "isolated frame: dlen == frame_len");

    size_t post_parse_dlen = stub_parse_shrink_to(20);
    dns_frame_apply_rewrite(buffer, &d, post_parse_dlen, &bytes);

    CHECK(bytes == 22, "isolated frame shortened: bytes rewritten to 22");
    CHECK(read_prefix(buffer) == 20, "isolated frame shortened: prefix rewritten to 20");
}

/* 2. Isolated complete frame: parser does not shorten -> no rewrite. */
static void test_isolated_frame_unchanged(void) {
    size_t frame_len = 50;
    ssize_t bytes = (ssize_t) (frame_len + 2);
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xBB, frame_len);

    struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
    CHECK(d.should_parse, "isolated frame unchanged: should_parse");
    CHECK(d.isolated, "isolated frame unchanged: isolated");
    CHECK(d.dlen == frame_len, "isolated frame unchanged: dlen == frame_len");

    size_t post_parse_dlen = stub_parse_unchanged(d.dlen);
    dns_frame_apply_rewrite(buffer, &d, post_parse_dlen, &bytes);

    CHECK(bytes == (ssize_t) (frame_len + 2), "isolated frame unchanged: bytes untouched");
    CHECK(read_prefix(buffer) == frame_len, "isolated frame unchanged: prefix untouched");
}

/* 3. Coalesced read: frame + extra bytes belonging to (the start of) the
 * next frame. Only the first frame's payload is parsed; the read is never
 * shortened and the prefix is never rewritten, because that would discard
 * the extra bytes still owed to the stream. */
static void test_coalesced_read(void) {
    size_t frame_len = 50;
    size_t extra = 30;
    ssize_t bytes = (ssize_t) (frame_len + 2 + extra);
    uint8_t buffer[2 + 50 + 30];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xCC, frame_len);
    memset(buffer + 2 + frame_len, 0xDD, extra);

    uint8_t snapshot[sizeof(buffer)];
    memcpy(snapshot, buffer, sizeof(buffer));

    struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
    CHECK(d.should_parse, "coalesced read: should_parse (regression 9c49cc09)");
    CHECK(!d.isolated, "coalesced read: not isolated");
    CHECK(d.dlen == frame_len, "coalesced read: dlen == first frame's payload length only");

    /* Even if the (simulated) parser wants to shrink the first frame, a
     * coalesced read must never be shortened -- that would eat into the
     * next frame's bytes. */
    size_t post_parse_dlen = stub_parse_shrink_to(10);
    dns_frame_apply_rewrite(buffer, &d, post_parse_dlen, &bytes);

    CHECK(bytes == (ssize_t) (frame_len + 2 + extra), "coalesced read: bytes forwarded unchanged");
    CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
          "coalesced read: buffer contents forwarded unchanged (prefix + extra bytes)");
}

/* 4. Split/partial frame: recv() got fewer bytes than the prefix declares.
 * Only the available bytes are parsed; never shortened/rewritten. */
static void test_split_frame(void) {
    size_t frame_len = 100;
    size_t avail = 50;
    ssize_t bytes = (ssize_t) (2 + avail);
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xEE, avail);

    uint8_t snapshot[sizeof(buffer)];
    memcpy(snapshot, buffer, sizeof(buffer));

    struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
    CHECK(d.should_parse, "split frame: should_parse (regression 9c49cc09)");
    CHECK(!d.isolated, "split frame: not isolated");
    CHECK(d.dlen == avail, "split frame: dlen capped to bytes actually available");

    size_t post_parse_dlen = stub_parse_unchanged(d.dlen);
    dns_frame_apply_rewrite(buffer, &d, post_parse_dlen, &bytes);

    CHECK(bytes == (ssize_t) (2 + avail), "split frame: bytes untouched");
    CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0, "split frame: buffer untouched");
}

/* 5. frame_len == 0: skipped entirely, no parse, no rewrite. */
static void test_zero_frame_len_skipped(void) {
    size_t frame_len = 0;
    ssize_t bytes = 10;

    struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
    CHECK(!d.should_parse, "frame_len == 0: skipped (should_parse == 0)");
    CHECK(!d.isolated, "frame_len == 0: not isolated");
    CHECK(d.dlen == 0, "frame_len == 0: dlen == 0");
}

/* 6. bytes == 3 minimal edge: exactly one payload byte available. */
static void test_bytes_equal_three_minimal(void) {
    /* Isolated: a 1-byte DNS message, nothing else in the read. */
    {
        size_t frame_len = 1;
        ssize_t bytes = 3;
        struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
        CHECK(d.should_parse, "bytes==3 isolated: should_parse");
        CHECK(d.isolated, "bytes==3 isolated: isolated");
        CHECK(d.dlen == 1, "bytes==3 isolated: dlen == 1");
    }
    /* Split: prefix declares more than the single available byte. */
    {
        size_t frame_len = 5;
        ssize_t bytes = 3;
        struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
        CHECK(d.should_parse, "bytes==3 split: should_parse");
        CHECK(!d.isolated, "bytes==3 split: not isolated");
        CHECK(d.dlen == 1, "bytes==3 split: dlen capped to the single available byte");
    }
}

/* 7. frame_len at the maximum a 2-byte prefix can represent (0xFFFF):
 * boundary check for the >>8 / mask arithmetic in the write-back path, and
 * for a split read against that maximum. */
static void test_frame_len_u16_boundary(void) {
    size_t frame_len = 0xFFFF; /* 65535: max value a 2-byte prefix can hold */

    /* Isolated at the boundary, with a shrink on rewrite. */
    {
        ssize_t bytes = (ssize_t) (frame_len + 2);
        uint8_t *buffer = malloc((size_t) bytes);
        CHECK(buffer != NULL, "u16 boundary isolated: allocation");
        if (buffer != NULL) {
            set_prefix(buffer, frame_len);

            struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
            CHECK(d.should_parse, "u16 boundary isolated: should_parse");
            CHECK(d.isolated, "u16 boundary isolated: isolated");
            CHECK(d.dlen == frame_len, "u16 boundary isolated: dlen == frame_len");

            size_t post_parse_dlen = stub_parse_shrink_to(65435); /* still fits in 16 bits */
            dns_frame_apply_rewrite(buffer, &d, post_parse_dlen, &bytes);

            CHECK(bytes == (ssize_t) (65435 + 2), "u16 boundary isolated: bytes rewritten");
            CHECK(read_prefix(buffer) == 65435, "u16 boundary isolated: prefix rewritten correctly");
            free(buffer);
        }
    }

    /* Split: prefix claims the maximum 16-bit length, but recv() only got
     * a small buffer's worth (realistic: the TCP read buffer is sized to
     * the connection's MSS, far smaller than 65535). */
    {
        size_t avail = 200;
        ssize_t bytes = (ssize_t) (2 + avail);
        uint8_t buffer[2 + 200];
        set_prefix(buffer, frame_len);
        memset(buffer + 2, 0x11, avail);

        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct dns_frame_decision d = dns_frame_decide((size_t) bytes, frame_len);
        CHECK(d.should_parse, "u16 boundary split: should_parse");
        CHECK(!d.isolated, "u16 boundary split: not isolated");
        CHECK(d.dlen == avail, "u16 boundary split: dlen capped to available bytes");

        dns_frame_apply_rewrite(buffer, &d, stub_parse_unchanged(d.dlen), &bytes);
        CHECK(bytes == (ssize_t) (2 + avail), "u16 boundary split: bytes untouched");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0, "u16 boundary split: buffer untouched");
    }
}

int main(void) {
    test_isolated_frame_shortened();
    test_isolated_frame_unchanged();
    test_coalesced_read();
    test_split_frame();
    test_zero_frame_len_skipped();
    test_bytes_equal_three_minimal();
    test_frame_len_u16_boundary();

    if (failures == 0) {
        printf("dns_frame_test: all tests passed\n");
        return 0;
    }

    fprintf(stderr, "dns_frame_test: %d assertion(s) failed\n", failures);
    return 1;
}
