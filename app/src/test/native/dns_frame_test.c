/*
 * Host unit tests for the DNS-over-TCP stream cursor implemented in
 * app/src/main/jni/netguard/dns_frame.{h,c}.
 *
 * This is a plain C test program with a tiny assert-based harness (no test
 * framework dependency), so it can build and run with the system compiler
 * on any host -- see .github/workflows/test.yml. It intentionally does not
 * link netguard.h, JNI, or parse_dns_response: dns_frame_process_stream()
 * takes the DNS parser as a callback, so a parse outcome is simulated with
 * stub callbacks (one recording every (offset, dlen) invocation, optionally
 * shrinking a chosen frame) instead of calling the real parser.
 *
 * Background: this framing logic shipped a real blocking bypass once
 * already, fixed in commit 9c49cc09 ("Fix DNS filtering regressions from
 * the tc-dns extraction") -- an earlier refactor skipped parse_dns_response
 * entirely unless a recv() held exactly one complete frame, so coalesced or
 * split reads went unfiltered. The cursor now parses *every* frame it can
 * see, so these tests pin down which frames get parsed, which may be
 * shortened (only those lying wholly inside the current read, since nothing
 * in it has been forwarded yet), and -- the subtler failure mode -- that the
 * carry-over state and the returned byte count agree, so the stream cannot
 * silently desynchronise one recv() later.
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

#define MAX_CALLS 8

/* A test double standing in for parse_dns_response(): records where in the
 * buffer it was invoked and with what length, and optionally reports a
 * shrunk length for one chosen invocation (simulating a policy hit that
 * blanks/truncates the DNS message). */
struct parse_recorder {
    const uint8_t *base;
    size_t calls;
    size_t off[MAX_CALLS];
    size_t len[MAX_CALLS];
    size_t shrink_call; /* 1-based invocation to shrink; 0 = never shrink */
    size_t shrink_to;
    size_t shrink_many_count;
    size_t shrink_many_to[MAX_CALLS];
    int shrink_on_marker;
    uint8_t shrink_marker;
    size_t shrink_marker_to;
};

static void recorder_init(struct parse_recorder *r, const uint8_t *base) {
    memset(r, 0, sizeof(*r));
    r->base = base;
}

static size_t stub_parse(void *ctx, uint8_t *data, size_t dlen) {
    struct parse_recorder *r = (struct parse_recorder *) ctx;
    if (r->calls < MAX_CALLS) {
        r->off[r->calls] = (size_t) (data - r->base);
        r->len[r->calls] = dlen;
    }
    r->calls++;
    if (r->shrink_call != 0 && r->calls == r->shrink_call)
        return r->shrink_to;
    if (r->shrink_many_count > 0 && r->calls <= r->shrink_many_count)
        return r->shrink_many_to[r->calls - 1];
    return dlen;
}

/* Records like stub_parse(), but shrinks every payload whose first byte is
 * the configured marker. This models a rule-based parser outcome without
 * depending on callback invocation order. */
static size_t stub_parse_by_marker(void *ctx, uint8_t *data, size_t dlen) {
    struct parse_recorder *r = (struct parse_recorder *) ctx;
    (void) stub_parse(ctx, data, dlen);
    if (r->shrink_on_marker && dlen > 0 && data[0] == r->shrink_marker)
        return r->shrink_marker_to;
    return dlen;
}

static int state_is_clean(const struct dns_stream_state *state) {
    return state->frame_remaining == 0 && state->have_prefix_hi == 0;
}

static size_t append_frame(uint8_t *buffer, size_t offset, size_t frame_len,
                           uint8_t fill) {
    set_prefix(buffer + offset, frame_len);
    memset(buffer + offset + 2, fill, frame_len);
    return offset + 2 + frame_len;
}

static size_t build_expected_frames(const uint8_t *source,
                                    const size_t *frame_lens,
                                    const size_t *new_lens,
                                    size_t frame_count, uint8_t *expected) {
    size_t source_offset = 0;
    size_t expected_offset = 0;

    for (size_t i = 0; i < frame_count; i++) {
        set_prefix(expected + expected_offset, new_lens[i]);
        memcpy(expected + expected_offset + 2, source + source_offset + 2,
               new_lens[i]);
        source_offset += 2 + frame_lens[i];
        expected_offset += 2 + new_lens[i];
    }

    return expected_offset;
}

static void run_coalesced_shrink_case(const char *label,
                                      const size_t *new_lens) {
    static const size_t frame_lens[] = {4, 5, 6, 7};
    static const uint8_t fills[] = {0x41, 0x52, 0x63, 0x74};
    uint8_t original[4 + 5 + 6 + 7 + 2 * 4 + 1];
    uint8_t copy[sizeof(original)];
    uint8_t expected[sizeof(original)];
    size_t bytes = 0;
    size_t removed = 0;

    for (size_t i = 0; i < 4; i++)
        bytes = append_frame(original, bytes, frame_lens[i], fills[i]);
    original[bytes++] = 0xE5; /* trailing byte after the last complete frame */
    memcpy(copy, original, bytes);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, copy);
    r.shrink_many_count = 4;
    for (size_t i = 0; i < 4; i++)
        r.shrink_many_to[i] = new_lens[i];

    size_t out = dns_frame_process_stream(copy, bytes, &state, stub_parse, &r);
    size_t expected_bytes = build_expected_frames(original, frame_lens,
                                                  new_lens, 4, expected);
    expected[expected_bytes++] = original[bytes - 1];
    for (size_t i = 0; i < 4; i++)
        removed += frame_lens[i] - new_lens[i];

    CHECK(r.calls == 4, label);
    CHECK(out == bytes - removed && out == expected_bytes, label);
    CHECK(memcmp(copy, expected, out) == 0, label);
    CHECK(copy[out - 1] == 0xE5, label);
    CHECK(state.frame_remaining == 0 && state.have_prefix_hi != 0 &&
              state.prefix_hi == 0xE5,
          label);

    size_t expected_offset = 0;
    for (size_t i = 0; i < 4; i++) {
        CHECK(r.off[i] == expected_offset + 2 && r.len[i] == frame_lens[i],
              label);
        expected_offset += 2 + new_lens[i];
    }
}

/* A four-frame coalesced read verifies every callback pointer against the
 * buffer as it exists at that invocation. The four independent rewrite cases
 * also cover first, middle, last, and several frames shrinking together. */
static void test_coalesced_multiple_frames(void) {
    static const size_t frame_lens[] = {4, 5, 6, 7};
    static const uint8_t fills[] = {0x41, 0x52, 0x63, 0x74};
    uint8_t original[4 + 5 + 6 + 7 + 2 * 4];
    uint8_t copy[sizeof(original)];
    size_t bytes = 0;

    for (size_t i = 0; i < 4; i++)
        bytes = append_frame(original, bytes, frame_lens[i], fills[i]);
    memcpy(copy, original, bytes);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, copy);
    size_t out = dns_frame_process_stream(copy, bytes, &state, stub_parse, &r);

    CHECK(r.calls == 4, "coalesced four frames: every frame parsed");
    size_t expected_offset = 0;
    for (size_t i = 0; i < 4; i++) {
        CHECK(r.off[i] == expected_offset + 2 && r.len[i] == frame_lens[i],
              "coalesced four frames: callback offset and length");
        expected_offset += 2 + frame_lens[i];
    }
    CHECK(out == bytes, "coalesced four frames: unchanged count");
    CHECK(memcmp(copy, original, bytes) == 0,
          "coalesced four frames: unchanged bytes");
    CHECK(state_is_clean(&state), "coalesced four frames: clean state");

    {
        static const size_t new_lens[] = {1, 5, 6, 7};
        run_coalesced_shrink_case("coalesced shrink: first frame", new_lens);
    }
    {
        static const size_t new_lens[] = {4, 2, 6, 7};
        run_coalesced_shrink_case("coalesced shrink: middle frame", new_lens);
    }
    {
        static const size_t new_lens[] = {4, 5, 6, 1};
        run_coalesced_shrink_case("coalesced shrink: last frame", new_lens);
    }
    {
        static const size_t new_lens[] = {1, 2, 3, 4};
        run_coalesced_shrink_case("coalesced shrink: several frames", new_lens);
    }
}

/* 1. A single complete frame in the read, shortened by the parser: the
 * payload shrinks, the 2-byte prefix is rewritten and the forwarded count
 * drops. Nothing in this buffer has reached the tun yet, so this is
 * sequence-safe. */
static void test_single_frame_shortened(void) {
    size_t frame_len = 50;
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xAA, frame_len);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);
    r.shrink_call = 1;
    r.shrink_to = 20;

    size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

    CHECK(r.calls == 1, "single frame shortened: parsed once");
    CHECK(r.off[0] == 2 && r.len[0] == frame_len,
          "single frame shortened: parser saw the whole payload");
    CHECK(out == 22, "single frame shortened: forwards 22 bytes");
    CHECK(read_prefix(buffer) == 20, "single frame shortened: prefix rewritten to 20");
    CHECK(state_is_clean(&state), "single frame shortened: no carry-over state");
}

/* 2. A single complete frame the parser leaves alone: forwarded verbatim.
 * Also pins the defensive clamp -- a callback that reports *more* than it was
 * given must be treated as "unchanged", never as a grow. */
static void test_single_frame_unchanged(void) {
    size_t frame_len = 50;
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xBB, frame_len);

    uint8_t snapshot[sizeof(buffer)];
    memcpy(snapshot, buffer, sizeof(buffer));

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);

    size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

    CHECK(r.calls == 1, "single frame unchanged: parsed once");
    CHECK(out == sizeof(buffer), "single frame unchanged: bytes untouched");
    CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
          "single frame unchanged: buffer untouched");
    CHECK(state_is_clean(&state), "single frame unchanged: no carry-over state");

    /* Defensive clamp: a bogus grow is ignored. */
    struct dns_stream_state state2 = {0, 0, 0};
    struct parse_recorder r2;
    recorder_init(&r2, buffer);
    r2.shrink_call = 1;
    r2.shrink_to = frame_len + 100;

    size_t out2 = dns_frame_process_stream(buffer, sizeof(buffer), &state2, stub_parse, &r2);
    CHECK(out2 == sizeof(buffer), "grow clamped: bytes untouched");
    CHECK(read_prefix(buffer) == frame_len, "grow clamped: prefix untouched");
    CHECK(state_is_clean(&state2), "grow clamped: no carry-over state");
}

/* 3. Coalesced read: two complete frames in one recv(). Both are parsed
 * (regression 9c49cc09 -- the second frame used to be invisible), and
 * shrinking the first one memmoves the second down over the gap and rewrites
 * the first frame's prefix. */
static void test_coalesced_read(void) {
    size_t first = 50;
    size_t second = 30;
    uint8_t buffer[2 + 50 + 2 + 30];
    set_prefix(buffer, first);
    memset(buffer + 2, 0xCC, first);
    set_prefix(buffer + 2 + first, second);
    memset(buffer + 2 + first + 2, 0xDD, second);

    /* First: both frames parsed, nothing shortened. */
    {
        uint8_t copy[sizeof(buffer)];
        memcpy(copy, buffer, sizeof(buffer));

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, copy);

        size_t out = dns_frame_process_stream(copy, sizeof(copy), &state, stub_parse, &r);

        CHECK(r.calls == 2, "coalesced read: both frames parsed (regression 9c49cc09)");
        CHECK(r.off[0] == 2 && r.len[0] == first, "coalesced read: first frame payload");
        CHECK(r.off[1] == 2 + 50 + 2 && r.len[1] == second,
              "coalesced read: second frame payload");
        CHECK(out == sizeof(copy), "coalesced read: bytes untouched");
        CHECK(memcmp(copy, buffer, sizeof(buffer)) == 0, "coalesced read: buffer untouched");
        CHECK(state_is_clean(&state), "coalesced read: no carry-over state");
    }

    /* Then: the first frame is shortened to 10 -- the second frame slides
     * down and is still parsed, at its new offset. */
    {
        uint8_t copy[sizeof(buffer)];
        memcpy(copy, buffer, sizeof(buffer));

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, copy);
        r.shrink_call = 1;
        r.shrink_to = 10;

        size_t out = dns_frame_process_stream(copy, sizeof(copy), &state, stub_parse, &r);

        CHECK(r.calls == 2, "coalesced shrink: both frames parsed");
        CHECK(r.off[1] == 2 + 10 + 2 && r.len[1] == second,
              "coalesced shrink: second frame parsed at its moved-down offset");
        CHECK(out == 2 + 10 + 2 + second, "coalesced shrink: 40 bytes of gap removed");
        CHECK(read_prefix(copy) == 10, "coalesced shrink: first prefix rewritten to 10");
        CHECK(read_prefix(copy + 12) == second, "coalesced shrink: second prefix intact");
        CHECK(copy[14] == 0xDD && copy[14 + second - 1] == 0xDD,
              "coalesced shrink: second payload moved down intact");
        CHECK(state_is_clean(&state), "coalesced shrink: no carry-over state");
    }
}

/* Shrinking a frame to zero keeps its two-byte prefix in place and moves the
 * following frame into the resulting gap. */
static void test_shrink_to_zero_alignment(void) {
    uint8_t original[2 + 3 + 2 + 2];
    size_t bytes = 0;
    bytes = append_frame(original, bytes, 3, 0x81);
    bytes = append_frame(original, bytes, 2, 0x92);

    uint8_t buffer[sizeof(original)];
    memcpy(buffer, original, bytes);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);
    r.shrink_call = 1;
    r.shrink_to = 0;

    size_t out = dns_frame_process_stream(buffer, bytes, &state, stub_parse, &r);

    CHECK(r.calls == 2, "shrink to zero: both frames parsed");
    CHECK(r.off[0] == 2 && r.len[0] == 3,
          "shrink to zero: first callback offset and length");
    CHECK(r.off[1] == 4 && r.len[1] == 2,
          "shrink to zero: following frame aligned after bare prefix");
    CHECK(out == 2 + 2 + 2, "shrink to zero: exact returned count");
    CHECK(buffer[0] == 0x00 && buffer[1] == 0x00,
          "shrink to zero: prefix is 00 00 in place");
    CHECK(read_prefix(buffer + 2) == 2 && buffer[4] == 0x92 && buffer[5] == 0x92,
          "shrink to zero: following frame moved intact");
    CHECK(memcmp(buffer + out, original + out, bytes - out) == 0,
          "shrink to zero: bytes after output unchanged");
    CHECK(state_is_clean(&state), "shrink to zero: clean state");
}

/* A payload can span three reads: the visible fragment is parsed once, the
 * middle continuation is only skipped, and the final continuation exposes
 * subsequent complete frames at the correct offset. */
static void test_payload_spanning_three_reads(void) {
    struct dns_stream_state state = {0, 0, 0};

    {
        uint8_t buffer[2 + 2];
        set_prefix(buffer, 9);
        memset(buffer + 2, 0xA1, 2);
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 0;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 1 && r.off[0] == 2 && r.len[0] == 2,
              "three-read payload: first visible fragment parsed");
        CHECK(out == sizeof(buffer), "three-read payload: first count unchanged");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "three-read payload: first fragment untouched");
        CHECK(state.frame_remaining == 7,
              "three-read payload: exact first overflow remembered");
    }

    {
        uint8_t buffer[3];
        memset(buffer, 0xA1, sizeof(buffer));
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 0, "three-read payload: middle continuation not parsed");
        CHECK(out == sizeof(buffer) && state.frame_remaining == 4,
              "three-read payload: exact middle overflow remembered");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "three-read payload: middle continuation untouched");
    }

    {
        uint8_t buffer[4 + 2 + 2 + 2 + 1];
        memset(buffer, 0xA1, 4);
        size_t offset = 4;
        offset = append_frame(buffer, offset, 2, 0xB2);
        (void) append_frame(buffer, offset, 1, 0xC3);
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 2, "three-read payload: following frames parsed");
        CHECK(r.off[0] == 6 && r.len[0] == 2 && r.off[1] == 10 && r.len[1] == 1,
              "three-read payload: following callback offsets and lengths");
        CHECK(out == sizeof(buffer), "three-read payload: final count unchanged");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "three-read payload: final continuation untouched");
        CHECK(state_is_clean(&state), "three-read payload: clean state");
    }
}

/* A high prefix byte is forwarded first. The next read completes the prefix
 * and exposes a partial, parse-only payload; later reads drain that payload
 * before parsing a new frame. */
static void test_split_prefix_payload_handoff(void) {
    struct dns_stream_state state = {0, 0, 0};

    {
        uint8_t buffer[1] = {0x00};
        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 0 && out == 1,
              "split-prefix handoff: high byte forwarded alone");
        CHECK(state.have_prefix_hi != 0 && state.prefix_hi == 0x00 &&
                  state.frame_remaining == 0,
              "split-prefix handoff: high byte stashed");
    }

    {
        uint8_t buffer[2] = {0x05, 0xD1};
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 0;
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 1 && r.off[0] == 1 && r.len[0] == 1,
              "split-prefix handoff: visible payload parsed");
        CHECK(out == sizeof(buffer),
              "split-prefix handoff: parse-only length unchanged");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "split-prefix handoff: parse-only bytes untouched");
        CHECK(state.have_prefix_hi == 0 && state.frame_remaining == 4,
              "split-prefix handoff: visible payload overflow remembered");
    }

    {
        uint8_t buffer[2] = {0xD1, 0xD1};
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 0 && out == sizeof(buffer) && state.frame_remaining == 2,
              "split-prefix handoff: middle payload skipped exactly");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "split-prefix handoff: middle payload untouched");
    }

    {
        uint8_t buffer[2 + 2 + 2];
        memset(buffer, 0xD1, 2);
        (void) append_frame(buffer, 2, 2, 0xE2);

        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);
        CHECK(r.calls == 1 && r.off[0] == 4 && r.len[0] == 2,
              "split-prefix handoff: following frame parsed after drain");
        CHECK(out == sizeof(buffer), "split-prefix handoff: final count unchanged");
        CHECK(state_is_clean(&state), "split-prefix handoff: clean state");
    }
}

/* Legal zero-length frames between real frames must forward their prefixes
 * but never invoke the DNS parser. */
static void test_zero_frames_between_real_frames(void) {
    uint8_t buffer[2 + 2 + 2 + 2 + 3 + 2 + 2 + 1];
    size_t offset = 0;
    offset = append_frame(buffer, offset, 2, 0x11);
    set_prefix(buffer + offset, 0);
    offset += 2;
    offset = append_frame(buffer, offset, 3, 0x22);
    set_prefix(buffer + offset, 0);
    offset += 2;
    (void) append_frame(buffer, offset, 1, 0x33);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);
    size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                           stub_parse, &r);

    CHECK(r.calls == 3, "zero frames between: zero-length frames not parsed");
    CHECK(r.off[0] == 2 && r.len[0] == 2 && r.off[1] == 8 && r.len[1] == 3 &&
              r.off[2] == 15 && r.len[2] == 1,
          "zero frames between: real-frame offsets and lengths");
    CHECK(out == sizeof(buffer), "zero frames between: all bytes forwarded");
    CHECK(state_is_clean(&state), "zero frames between: clean state");
}

/* 4. Split frame: recv() got fewer bytes than the prefix declares. The
 * visible part is still parsed (regression 9c49cc09), never shortened, and
 * the overflow is remembered so the next read skips exactly those bytes
 * instead of misreading them as a fresh length prefix. */
static void test_split_frame(void) {
    size_t frame_len = 100;
    size_t avail = 50;
    uint8_t buffer[2 + 50];
    set_prefix(buffer, frame_len);
    memset(buffer + 2, 0xEE, avail);

    uint8_t snapshot[sizeof(buffer)];
    memcpy(snapshot, buffer, sizeof(buffer));

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);
    r.shrink_call = 1; /* a shrink on a partial frame must be ignored */
    r.shrink_to = 5;

    size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

    CHECK(r.calls == 1, "split frame: visible part parsed (regression 9c49cc09)");
    CHECK(r.off[0] == 2 && r.len[0] == avail, "split frame: parse capped to available bytes");
    CHECK(out == sizeof(buffer), "split frame: bytes untouched");
    CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0, "split frame: buffer untouched");
    CHECK(state.frame_remaining == frame_len - avail, "split frame: overflow remembered");
    CHECK(state.have_prefix_hi == 0, "split frame: no stashed prefix byte");

    /* The continuation read: the remaining 50 payload bytes, followed by a
     * complete 10-byte frame. The skip must be exact. */
    uint8_t next[50 + 2 + 10];
    memset(next, 0xEE, 50);
    set_prefix(next + 50, 10);
    memset(next + 52, 0x22, 10);

    struct parse_recorder r2;
    recorder_init(&r2, next);

    size_t out2 = dns_frame_process_stream(next, sizeof(next), &state, stub_parse, &r2);

    CHECK(r2.calls == 1, "split continuation: only the new frame is parsed");
    CHECK(r2.off[0] == 52 && r2.len[0] == 10,
          "split continuation: exactly 50 carry-over bytes skipped");
    CHECK(out2 == sizeof(next), "split continuation: all bytes forwarded");
    CHECK(state_is_clean(&state), "split continuation: state drained");
}

/* 5. frame_len == 0 is a legal no-op frame: its two bytes forward as-is and
 * the rest of the read is still processed (it no longer aborts the read). */
static void test_zero_frame_len(void) {
    uint8_t buffer[2 + 2 + 8];
    set_prefix(buffer, 0);
    set_prefix(buffer + 2, 8);
    memset(buffer + 4, 0x33, 8);

    struct dns_stream_state state = {0, 0, 0};
    struct parse_recorder r;
    recorder_init(&r, buffer);
    r.shrink_call = 1;
    r.shrink_to = 3;

    size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

    CHECK(r.calls == 1, "zero frame_len: the following frame is still parsed");
    CHECK(r.off[0] == 4 && r.len[0] == 8, "zero frame_len: parsed at the right offset");
    CHECK(out == 2 + 2 + 3, "zero frame_len: following frame still shrinkable");
    CHECK(read_prefix(buffer) == 0, "zero frame_len: no-op frame forwarded as-is");
    CHECK(read_prefix(buffer + 2) == 3, "zero frame_len: following prefix rewritten");
    CHECK(state_is_clean(&state), "zero frame_len: no carry-over state");
}

/* 6. Minimal edges: a 3-byte read holding one payload byte, complete or
 * split, and a 1-byte read that is only the high half of a length prefix. */
static void test_minimal_reads(void) {
    /* Complete: a 1-byte DNS message, shrunk away entirely -> a 00 00 frame. */
    {
        uint8_t buffer[3];
        set_prefix(buffer, 1);
        buffer[2] = 0x44;

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 0;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 1 && r.off[0] == 2 && r.len[0] == 1, "bytes==3 complete: parsed");
        CHECK(out == 2, "bytes==3 complete: shrunk to a bare prefix");
        CHECK(read_prefix(buffer) == 0, "bytes==3 complete: prefix rewritten to 0");
        CHECK(state_is_clean(&state), "bytes==3 complete: no carry-over state");
    }

    /* Split: the prefix declares more than the single available byte. */
    {
        uint8_t buffer[3];
        set_prefix(buffer, 5);
        buffer[2] = 0x55;

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 1 && r.off[0] == 2 && r.len[0] == 1,
              "bytes==3 split: parse capped to the single available byte");
        CHECK(out == sizeof(buffer), "bytes==3 split: bytes untouched");
        CHECK(state.frame_remaining == 4, "bytes==3 split: 4 payload bytes still owed");
    }

    /* A lone byte: the high half of a length prefix. It cannot be withheld,
     * so it is forwarded and stashed. */
    {
        uint8_t buffer[1] = {0x01};

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 0, "lone prefix byte: nothing to parse yet");
        CHECK(out == 1, "lone prefix byte: forwarded as-is");
        CHECK(state.have_prefix_hi != 0 && state.prefix_hi == 0x01,
              "lone prefix byte: stashed for the next read");
        CHECK(state.frame_remaining == 0, "lone prefix byte: no payload owed");
    }

    /* Exactly two bytes can hold a nonzero prefix but no payload. The parser
     * may or may not be called with dlen == 0; only the externally visible
     * state and untouched buffer are contractual here. */
    {
        uint8_t buffer[2] = {0x12, 0x34};
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);

        CHECK(out == sizeof(buffer), "bytes==2 no payload: count unchanged");
        CHECK(state.frame_remaining == 0x1234 && state.have_prefix_hi == 0,
              "bytes==2 no payload: full frame length owed");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "bytes==2 no payload: buffer untouched");
    }

    /* A one-byte continuation is similarly just consumed from the owed
     * payload; no parser-call shape is part of this edge contract. */
    {
        uint8_t buffer[1] = {0x77};
        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct dns_stream_state state = {5, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);
        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state,
                                               stub_parse, &r);

        CHECK(out == sizeof(buffer) && state.frame_remaining == 4,
              "bytes==1 continuation: one owed byte consumed");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0,
              "bytes==1 continuation: buffer untouched");
    }
}

/* 7. frame_len at the maximum a 2-byte prefix can represent (0xFFFF):
 * boundary check for the >>8 / mask arithmetic in the write-back path, and
 * for a split read against that maximum (realistic: the TCP read buffer is
 * sized to the connection's MSS, far smaller than 65535). */
static void test_frame_len_u16_boundary(void) {
    size_t frame_len = 0xFFFF;

    /* Complete at the boundary, with a shrink. */
    {
        size_t bytes = frame_len + 2;
        uint8_t *buffer = malloc(bytes);
        CHECK(buffer != NULL, "u16 boundary complete: allocation");
        if (buffer != NULL) {
            set_prefix(buffer, frame_len);
            memset(buffer + 2, 0x66, frame_len);

            struct dns_stream_state state = {0, 0, 0};
            struct parse_recorder r;
            recorder_init(&r, buffer);
            r.shrink_call = 1;
            r.shrink_to = 65435;

            size_t out = dns_frame_process_stream(buffer, bytes, &state, stub_parse, &r);

            CHECK(r.calls == 1 && r.len[0] == frame_len, "u16 boundary complete: parsed");
            CHECK(out == 65435 + 2, "u16 boundary complete: forwarded count rewritten");
            CHECK(read_prefix(buffer) == 65435, "u16 boundary complete: prefix rewritten");
            CHECK(state_is_clean(&state), "u16 boundary complete: no carry-over state");
            free(buffer);
        }
    }

    /* Split against the boundary. */
    {
        size_t avail = 200;
        uint8_t buffer[2 + 200];
        set_prefix(buffer, frame_len);
        memset(buffer + 2, 0x11, avail);

        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct dns_stream_state state = {0, 0, 0};
        struct parse_recorder r;
        recorder_init(&r, buffer);

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 1 && r.off[0] == 2 && r.len[0] == avail,
              "u16 boundary split: parse capped to available bytes");
        CHECK(out == sizeof(buffer), "u16 boundary split: bytes untouched");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0, "u16 boundary split: buffer untouched");
        CHECK(state.frame_remaining == frame_len - avail,
              "u16 boundary split: overflow remembered");
    }
}

/* 8. The desync test: four consecutive recv()s on one connection, with a
 * length rewrite in the first, a frame split across reads, a length prefix
 * split across reads, and a parse-only frame whose shrink must be ignored.
 * A state/return mismatch anywhere here shows up as a wrong parse offset or
 * a wrong forwarded count in a *later* call -- which no single-buffer test
 * can catch. */
static void test_multi_call_no_desync(void) {
    struct dns_stream_state state = {0, 0, 0};

    /* Read 1: frame A (40 bytes, complete, shrunk to 10) followed by the
     * prefix of frame B (60 bytes) with only 20 of its payload present. */
    {
        uint8_t buffer[2 + 40 + 2 + 20];
        set_prefix(buffer, 40);
        memset(buffer + 2, 0xA1, 40);
        set_prefix(buffer + 42, 60);
        memset(buffer + 44, 0xB1, 20);

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 10;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 2, "read 1: frame A and the visible part of frame B parsed");
        CHECK(r.off[0] == 2 && r.len[0] == 40, "read 1: frame A payload");
        CHECK(out == 2 + 10 + 2 + 20, "read 1: forwards 34 bytes after the rewrite");
        CHECK(read_prefix(buffer) == 10, "read 1: frame A prefix rewritten");
        CHECK(read_prefix(buffer + 12) == 60, "read 1: frame B prefix moved down intact");
        CHECK(r.off[1] == 14 && r.len[1] == 20,
              "read 1: frame B's visible payload parsed at its moved-down offset");
        CHECK(buffer[14] == 0xB1, "read 1: frame B payload moved down intact");
        CHECK(state.frame_remaining == 40, "read 1: 40 bytes of frame B still owed");
        CHECK(state.have_prefix_hi == 0, "read 1: no stashed prefix byte");
    }

    /* Read 2: the rest of frame B (40 bytes) plus the high half of frame C's
     * length prefix. Nothing may be parsed or rewritten here. */
    {
        uint8_t buffer[40 + 1];
        memset(buffer, 0xB1, 40);
        buffer[40] = 0x00; /* high byte of frame C's length */

        uint8_t snapshot[sizeof(buffer)];
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 1;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 0, "read 2: carry-over payload is never re-parsed");
        CHECK(out == sizeof(buffer), "read 2: all bytes forwarded");
        CHECK(memcmp(buffer, snapshot, sizeof(buffer)) == 0, "read 2: buffer untouched");
        CHECK(state.frame_remaining == 0, "read 2: frame B fully drained");
        CHECK(state.have_prefix_hi != 0 && state.prefix_hi == 0x00,
              "read 2: frame C's prefix high byte stashed");
    }

    /* Read 3: frame C's prefix low byte (12), its 12 payload bytes, then a
     * complete frame D. Frame C's prefix is already on the wire, so a shrink
     * of C must be ignored; D must still be found at the right offset. */
    {
        uint8_t buffer[1 + 12 + 2 + 5];
        buffer[0] = 0x0C; /* low byte: frame C is 12 bytes */
        memset(buffer + 1, 0xC1, 12);
        set_prefix(buffer + 13, 5);
        memset(buffer + 15, 0xD1, 5);

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1; /* shrink frame C -- must be ignored */
        r.shrink_to = 3;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 2, "read 3: frames C and D both parsed");
        CHECK(r.off[0] == 1 && r.len[0] == 12, "read 3: frame C payload");
        CHECK(out == sizeof(buffer),
              "read 3: split-prefix frame is parse-only, never shortened");
        CHECK(read_prefix(buffer + 13) == 5, "read 3: frame D prefix untouched");
        CHECK(r.off[1] == 15 && r.len[1] == 5, "read 3: frame D parsed at the right offset");
        CHECK(state_is_clean(&state), "read 3: state drained");
    }

    /* Read 4: a plain complete frame -- proof the cursor is still aligned
     * with the stream after all of the above. */
    {
        uint8_t buffer[2 + 9];
        set_prefix(buffer, 9);
        memset(buffer + 2, 0xE1, 9);

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_call = 1;
        r.shrink_to = 4;

        size_t out = dns_frame_process_stream(buffer, sizeof(buffer), &state, stub_parse, &r);

        CHECK(r.calls == 1 && r.off[0] == 2 && r.len[0] == 9, "read 4: frame parsed");
        CHECK(out == 2 + 4, "read 4: rewrite applies again on a clean boundary");
        CHECK(read_prefix(buffer) == 4, "read 4: prefix rewritten");
        CHECK(state_is_clean(&state), "read 4: no carry-over state");
    }
}

#define CHUNKING_STREAM_BYTES 30

static size_t build_chunking_reference(uint8_t *buffer) {
    size_t offset = 0;
    offset = append_frame(buffer, offset, 7, 0x11);
    offset = append_frame(buffer, offset, 8, 0x22);
    buffer[11] = 0xA5; /* marker in the designated frame's payload */
    offset = append_frame(buffer, offset, 9, 0x33);
    return offset;
}

static void run_chunk_partition(const uint8_t *stream, size_t stream_bytes,
                                const size_t *chunk_ends, size_t chunk_count) {
    static const size_t frame_lens[] = {7, 8, 9};
    static const size_t designated_start = 9;
    static const size_t designated_end = 19;
    uint8_t actual[CHUNKING_STREAM_BYTES];
    size_t actual_bytes = 0;
    size_t returned_bytes = 0;
    size_t chunk_start = 0;
    struct dns_stream_state state = {0, 0, 0};

    for (size_t i = 0; i < chunk_count; i++) {
        size_t chunk_end = chunk_ends[i];
        size_t chunk_bytes = chunk_end - chunk_start;
        uint8_t buffer[CHUNKING_STREAM_BYTES];
        uint8_t snapshot[sizeof(buffer)];
        memset(buffer, 0xF0, sizeof(buffer));
        memcpy(buffer, stream + chunk_start, chunk_bytes);
        memcpy(snapshot, buffer, sizeof(buffer));

        struct parse_recorder r;
        recorder_init(&r, buffer);
        r.shrink_on_marker = 1;
        r.shrink_marker = 0xA5;
        r.shrink_marker_to = 3;

        size_t out = dns_frame_process_stream(buffer, chunk_bytes, &state,
                                               stub_parse_by_marker, &r);
        CHECK(out <= chunk_bytes, "chunking property: output never exceeds input");
        if (out <= chunk_bytes) {
            CHECK(memcmp(buffer + out, snapshot + out, chunk_bytes - out) == 0,
                  "chunking property: no bytes changed beyond returned count");
        }
        CHECK(memcmp(buffer + chunk_bytes, snapshot + chunk_bytes,
                     sizeof(buffer) - chunk_bytes) == 0,
              "chunking property: no bytes changed outside input");

        size_t accepted = out;
        if (accepted > chunk_bytes)
            accepted = chunk_bytes;
        CHECK(actual_bytes + accepted <= sizeof(actual),
              "chunking property: output collector capacity");
        if (actual_bytes + accepted <= sizeof(actual)) {
            memcpy(actual + actual_bytes, buffer, accepted);
            actual_bytes += accepted;
        }
        returned_bytes += accepted;
        CHECK(actual_bytes == returned_bytes,
              "chunking property: returned counts collected consistently");
        chunk_start = chunk_end;
    }

    size_t new_lens[] = {7, 8, 9};
    for (size_t i = 0; i < chunk_count; i++) {
        size_t chunk_start_for_frame = i == 0 ? 0 : chunk_ends[i - 1];
        size_t chunk_end_for_frame = chunk_ends[i];
        if (designated_start >= chunk_start_for_frame &&
            designated_end <= chunk_end_for_frame) {
            new_lens[1] = 3;
            break;
        }
    }

    uint8_t expected[CHUNKING_STREAM_BYTES];
    size_t expected_bytes = build_expected_frames(stream, frame_lens, new_lens,
                                                  3, expected);
    CHECK(returned_bytes == expected_bytes,
          "chunking property: returned counts sum to expected length");
    CHECK(actual_bytes == expected_bytes,
          "chunking property: collected output has expected length");
    if (actual_bytes == expected_bytes)
        CHECK(memcmp(actual, expected, expected_bytes) == 0,
              "chunking property: output equals expected stream");

    /* The complete reference stream has no outstanding carry-over after the
     * final chunk, regardless of where its boundaries were placed. */
    CHECK(chunk_start == stream_bytes,
          "chunking property: all reference bytes consumed");
    CHECK(state_is_clean(&state), "chunking property: clean final state");
}

/* Every two-way and three-way partition exercises prefix splits, payload
 * splits, coalesced frames, in-place rewrites, and output collection from the
 * returned count. Only a wholly contained designated frame may shrink. */
static void test_exhaustive_chunking(void) {
    uint8_t stream[CHUNKING_STREAM_BYTES];
    size_t stream_bytes = build_chunking_reference(stream);
    CHECK(stream_bytes == sizeof(stream), "chunking property: reference size");

    for (size_t split = 1; split < stream_bytes; split++) {
        size_t ends[2] = {split, stream_bytes};
        run_chunk_partition(stream, stream_bytes, ends, 2);
    }

    for (size_t first = 1; first + 1 < stream_bytes; first++) {
        for (size_t second = first + 1; second < stream_bytes; second++) {
            size_t ends[3] = {first, second, stream_bytes};
            run_chunk_partition(stream, stream_bytes, ends, 3);
        }
    }
}

int main(void) {
    test_single_frame_shortened();
    test_single_frame_unchanged();
    test_coalesced_multiple_frames();
    test_coalesced_read();
    test_shrink_to_zero_alignment();
    test_split_frame();
    test_payload_spanning_three_reads();
    test_split_prefix_payload_handoff();
    test_zero_frame_len();
    test_zero_frames_between_real_frames();
    test_minimal_reads();
    test_frame_len_u16_boundary();
    test_multi_call_no_desync();
    test_exhaustive_chunking();

    if (failures == 0) {
        printf("dns_frame_test: all tests passed\n");
        return 0;
    }

    fprintf(stderr, "dns_frame_test: %d assertion(s) failed\n", failures);
    return 1;
}
