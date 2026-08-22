/*
 * Host unit tests for the IPv6 extension header walk extracted into
 * app/src/main/jni/netguard/ip6_ext.{h,c}.
 *
 * Plain C test program with a tiny assert-based harness (no test framework
 * dependency), so it builds and runs with the system compiler on any host
 * -- see .github/workflows/test.yml. Mirrors app/src/test/native/dns_frame_test.c.
 *
 * Background: the original inline walk in handle_ip() (ip.c) had several
 * compounding defects -- wrong Hdr Ext Len arithmetic (treated as octets
 * instead of 8-octet units), no bounds checking against the packet length,
 * a loop-termination bug that meant the walk essentially never reached an
 * upper-layer protocol at all (see the "negative control" comment below),
 * and a handful of header types (Fragment, ESP, AH, Mobility) that either
 * cannot be walked the same way or cannot be walked at all. These tests
 * pin down the fixed behaviour so none of that can silently regress.
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ip6_ext.h"

static int failures = 0;

#define CHECK(cond, msg)                                                    \
    do {                                                                    \
        if (!(cond)) {                                                      \
            fprintf(stderr, "FAIL: %s (%s:%d)\n", (msg), __FILE__, __LINE__); \
            failures++;                                                     \
        }                                                                   \
    } while (0)

#define IPPROTO_TCP_ 6
#define IPPROTO_UDP_ 17

/* Fixed 40-byte IPv6 header (RFC 8200 section 3): only byte 6 (Next Header)
 * matters to ip6_skip_ext_headers(), the rest is zeroed. */
static void set_ip6_next(uint8_t *pkt, uint8_t next) {
    memset(pkt, 0, IP6_EXT_FIXED_HDR_LEN);
    pkt[6] = next;
}

/* Writes a minimal walkable extension header (Hop-by-Hop/Routing/Destination
 * Options/AH all share this 2-byte-prefix shape) at `off`: byte 0 is its
 * Next Header, byte 1 is Hdr Ext Len. The rest of the header (its actual
 * options/data, sized by the caller's chosen advance) is left zeroed. */
static void set_ext_header(uint8_t *pkt, size_t off, uint8_t next, uint8_t hdr_len) {
    pkt[off] = next;
    pkt[off + 1] = hdr_len;
}

/* 1. Plain IPv6 packet, no extension headers -- the common case. */
static void test_no_extension_headers(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 20];
    set_ip6_next(pkt, IPPROTO_TCP_);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "no extension headers: succeeds");
    CHECK(protocol == IPPROTO_TCP_, "no extension headers: protocol is TCP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "no extension headers: payload right after fixed header");
}

/* 2. Single Hop-by-Hop header (Hdr Ext Len 0 -> 8 bytes), then TCP. */
static void test_single_hopbyhop_then_tcp(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 8 + 20];
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_TCP_, 0);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "single hop-by-hop: succeeds");
    CHECK(protocol == IPPROTO_TCP_, "single hop-by-hop: protocol is TCP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN + 8, "single hop-by-hop: payload after the 8-byte header");
}

/* 3. Chain of three headers: Hop-by-Hop -> Routing -> Destination Options -> TCP. */
static void test_chain_of_three_headers(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 8 + 8 + 8 + 20];
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);
    size_t off = IP6_EXT_FIXED_HDR_LEN;
    set_ext_header(pkt, off, 43 /* Routing */, 0);
    off += 8;
    set_ext_header(pkt, off, 60 /* Destination Options */, 0);
    off += 8;
    set_ext_header(pkt, off, IPPROTO_TCP_, 0);
    off += 8;

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "chain of three: succeeds");
    CHECK(protocol == IPPROTO_TCP_, "chain of three: protocol is TCP");
    CHECK(payload_off == off, "chain of three: payload after all three headers");
}

/* 3b. A walkable header carrying options (Hdr Ext Len > 0). This is the case
 * that discriminates RFC 8200's 8 * (Hdr Ext Len + 1) from the original
 * 8 + Hdr Ext Len: at Hdr Ext Len 0 the two agree, so a chain built only from
 * minimum-size headers passes under either formula and proves nothing about
 * the arithmetic this fix is mainly about. */
static void test_hopbyhop_with_options_then_tcp(void) {
    /* Hdr Ext Len 1 -> 8 * (1 + 1) = 16 bytes; the buggy form gives 9. */
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 16 + 20];
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);
    memset(pkt + IP6_EXT_FIXED_HDR_LEN, 0, 16);
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_TCP_, 1);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "hop-by-hop with options: succeeds");
    CHECK(protocol == IPPROTO_TCP_, "hop-by-hop with options: protocol is TCP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN + 16,
          "hop-by-hop with options: advance is 8 * (len + 1), not 8 + len");
}

/* 3c. The same discrimination on a longer header of a different type, so the
 * arithmetic is pinned rather than fitted to one case. */
static void test_routing_with_options_then_udp(void) {
    /* Hdr Ext Len 3 -> 8 * (3 + 1) = 32 bytes; the buggy form gives 11. */
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 32 + 8];
    set_ip6_next(pkt, 43 /* Routing */);
    memset(pkt + IP6_EXT_FIXED_HDR_LEN, 0, 32);
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_UDP_, 3);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "routing with options: succeeds");
    CHECK(protocol == IPPROTO_UDP_, "routing with options: protocol is UDP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN + 32,
          "routing with options: advance is 8 * (len + 1), not 8 + len");
}

/* 4. AH uses 4-octet units, not 8-octet units: (Hdr Ext Len + 2) * 4. */
static void test_ah_then_udp(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 12 + 8];
    set_ip6_next(pkt, 51 /* AH */);
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_UDP_, 1); // (1+2)*4 = 12 bytes

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 1, "AH then UDP: succeeds");
    CHECK(protocol == IPPROTO_UDP_, "AH then UDP: protocol is UDP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN + 12, "AH then UDP: payload after AH's 4-octet-unit length");
}

/* 5. Declared header length runs past the end of the buffer: stop cleanly,
 * do not read out of bounds, and report the header we couldn't get past. */
static void test_declared_length_past_end(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 8]; // only room for the header itself
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_TCP_, 250); // 8*(250+1) way past the buffer

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "declared length past end: stops (not a false success)");
    CHECK(protocol == 0, "declared length past end: reports the Hop-by-Hop header it stopped on");
    CHECK(payload_off <= sizeof(pkt), "declared length past end: payload_off never exceeds the buffer");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "declared length past end: payload_off at the unwalked header");
}

/* 6. Truncated packet: a header start (1 byte) but no room for its own
 * 2-byte prefix, let alone a body. Must not read past the buffer. */
static void test_truncated_header_start(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 1];
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);
    pkt[IP6_EXT_FIXED_HDR_LEN] = IPPROTO_TCP_; // only 1 byte available, no Hdr Ext Len byte

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "truncated header start: stops");
    CHECK(protocol == 0, "truncated header start: reports the Hop-by-Hop header it stopped on");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "truncated header start: payload_off at the unreadable header");
}

/* 7. Fragment header: ip6e_len is a reserved field here, not a length --
 * must not be walked, regardless of whatever garbage sits in that byte. */
static void test_fragment_stops(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 8 + 20];
    set_ip6_next(pkt, 44 /* Fragment */);
    // Whatever "Hdr Ext Len" would say for Fragment must be ignored.
    set_ext_header(pkt, IP6_EXT_FIXED_HDR_LEN, IPPROTO_TCP_, 0);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "fragment: stops, not walked");
    CHECK(protocol == 44, "fragment: reports Fragment");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "fragment: payload_off right after the fixed header");
}

/* 8. ESP: payload is encrypted, nothing after it is parseable. */
static void test_esp_stops(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 20];
    set_ip6_next(pkt, 50 /* ESP */);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "ESP: stops, not walked");
    CHECK(protocol == 50, "ESP: reports ESP");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "ESP: payload_off right after the fixed header");
}

/* 9. No Next Header (59): a clean, legitimate end of the chain. */
static void test_no_next_header_stops(void) {
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 20];
    set_ip6_next(pkt, 59 /* No Next Header */);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "no next header: stops cleanly");
    CHECK(protocol == 59, "no next header: reports 59");
    CHECK(payload_off == IP6_EXT_FIXED_HDR_LEN, "no next header: payload_off right after the fixed header");
}

/* 10. A chain long enough to hit MAX_IP6_EXT_HEADERS (8): a real TCP header
 * sits right where the 9th extension header would be, so if the cap did
 * not fire this would incorrectly report success with protocol == TCP. */
static void test_cap_stops_long_chain(void) {
    enum { walked = 8 }; // must match MAX_IP6_EXT_HEADERS in ip6_ext.c
    uint8_t pkt[IP6_EXT_FIXED_HDR_LEN + 8 * (walked + 1) + 20];
    set_ip6_next(pkt, 0 /* Hop-by-Hop */);

    size_t off = IP6_EXT_FIXED_HDR_LEN;
    for (int i = 0; i < walked; i++) {
        set_ext_header(pkt, off, 0 /* another Hop-by-Hop */, 0);
        off += 8;
    }
    // The 9th header the walk must never reach: if it were read, it would
    // hand the walk straight to TCP.
    set_ext_header(pkt, off, IPPROTO_TCP_, 0);

    uint8_t protocol;
    size_t payload_off;
    int ok = ip6_skip_ext_headers(pkt, sizeof(pkt), &protocol, &payload_off);

    CHECK(ok == 0, "long chain: cap stops the walk before reaching TCP");
    CHECK(protocol == 0, "long chain: reports Hop-by-Hop, not TCP");
    CHECK(payload_off == off, "long chain: payload_off right before the unreached 9th header");
}

int main(void) {
    test_no_extension_headers();
    test_single_hopbyhop_then_tcp();
    test_chain_of_three_headers();
    test_hopbyhop_with_options_then_tcp();
    test_routing_with_options_then_udp();
    test_ah_then_udp();
    test_declared_length_past_end();
    test_truncated_header_start();
    test_fragment_stops();
    test_esp_stops();
    test_no_next_header_stops();
    test_cap_stops_long_chain();

    if (failures == 0) {
        printf("ip6_ext_test: all tests passed\n");
        return 0;
    }

    fprintf(stderr, "ip6_ext_test: %d assertion(s) failed\n", failures);
    return 1;
}
