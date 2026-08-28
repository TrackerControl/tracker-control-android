/*
 * Host unit tests for the DNS-over-TCP framing stream extracted into
 * app/src/main/jni/netguard/dns_frame.{h,c}.
 *
 * This is a plain C test program with a tiny assert-based harness (no test
 * framework dependency), so it can build and run with the system compiler
 * on any host -- see .github/workflows/test.yml. It intentionally does not
 * link netguard.h, JNI, or parse_dns_response: the stream state machine is
 * pure, so a DNS-parse outcome is simulated inline instead of calling the
 * real parser.
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

struct stream_observer {
    size_t calls;
    uint8_t ids[16];
    size_t lengths[16];
    size_t emitted;
    size_t output_len;
    uint8_t output[DNS_FRAME_MAX_BUFFER + 64];
    int mutate;
    int shrink;
    int reserve_fail;
    int pending_reserve_fail;
    int emit_fail;
    int enforce_send_window;
    uint16_t mss;
    uint16_t unconfirmed;
    uint32_t acked;
    uint32_t local_seq;
    uint32_t send_window;
    size_t writes;
    size_t reserve_calls;
    size_t pending_reserve_calls;
    size_t pending_transient_peak;
    int pending_reserve_fail_after;
};

static size_t observe_frame(void *opaque, uint8_t *payload, size_t length) {
    struct stream_observer *observer = (struct stream_observer *) opaque;
    CHECK(observer->calls < sizeof(observer->ids), "stream observer capacity");
    if (observer->calls < sizeof(observer->ids)) {
        observer->ids[observer->calls] = length == 0 ? 0 : payload[0];
        observer->lengths[observer->calls] = length;
    }
    observer->calls++;

    if (observer->mutate && length != 0)
        payload[0] ^= 0xFF;
    if (observer->shrink && length > 3)
        return length - 3;
    return length;
}

static int emit_frame(void *opaque, const uint8_t *frame, size_t length) {
    struct stream_observer *observer = (struct stream_observer *) opaque;
    if (observer->emit_fail)
        return -1;
    if (observer->output_len + length > sizeof(observer->output))
        return -1;
    memcpy(observer->output + observer->output_len, frame, length);
    observer->output_len += length;
    observer->emitted++;
    return 0;
}

static int emit_tcp_frame(void *opaque, const uint8_t *frame, size_t length) {
    struct stream_observer *observer = (struct stream_observer *) opaque;
    const size_t budget = dns_frame_send_budget(
            dns_frame_send_window(observer->acked, observer->local_seq,
                                  observer->unconfirmed, observer->send_window),
            observer->mss);
    if (!observer->enforce_send_window || length > budget || length > observer->mss)
        return observer->enforce_send_window ? -1 : emit_frame(opaque, frame, length);
    if (observer->output_len + length > sizeof(observer->output))
        return -1;
    memcpy(observer->output + observer->output_len, frame, length);
    observer->output_len += length;
    observer->writes++;
    observer->local_seq += (uint32_t) length;
    observer->unconfirmed++;
    return 0;
}

static int reserve_frame(void *opaque, struct dns_frame_stream *stream,
                         size_t required) {
    struct stream_observer *observer = (struct stream_observer *) opaque;
    observer->reserve_calls++;
    if (observer->reserve_fail)
        return -1;

    uint8_t *storage = malloc(required);
    if (storage == NULL)
        return -1;
    if (stream->buffer != NULL) {
        memcpy(storage, stream->buffer, stream->buffered);
        free(stream->buffer);
    }
    stream->buffer = storage;
    stream->capacity = required;
    return 0;
}

static int reserve_pending(void *opaque, struct dns_frame_stream *stream,
                           size_t required) {
    struct stream_observer *observer = (struct stream_observer *) opaque;
    observer->reserve_calls++;
    observer->pending_reserve_calls++;
    if (observer->pending_reserve_fail ||
        (observer->pending_reserve_fail_after > 0 &&
         observer->pending_reserve_calls > (size_t) observer->pending_reserve_fail_after))
        return -1;

    size_t capacity = stream->pending_capacity;
    const size_t half = DNS_FRAME_MAX_PENDING / 2;
    if (capacity < 2)
        capacity = 2;
    while (capacity < required) {
        if (capacity >= half) {
            capacity = DNS_FRAME_MAX_PENDING;
            break;
        }
        if (capacity > half / 2)
            capacity = half;
        else
            capacity *= 2;
    }
    if (capacity < required)
        return -1;

    if (stream->pending_capacity + capacity > observer->pending_transient_peak)
        observer->pending_transient_peak = stream->pending_capacity + capacity;

    uint8_t *storage = malloc(capacity);
    if (storage == NULL)
        return -1;
    if (stream->pending != NULL) {
        size_t pending = stream->pending_length - stream->pending_offset;
        memcpy(storage, stream->pending + stream->pending_offset, pending);
        free(stream->pending);
        stream->pending_offset = 0;
        stream->pending_length = pending;
    }
    stream->pending = storage;
    stream->pending_capacity = capacity;
    return 0;
}

static void release_pending(void *opaque, struct dns_frame_stream *stream) {
    (void) opaque;
    free(stream->pending);
}

static void flush_stream(struct dns_frame_stream *stream,
                         struct stream_observer *observer, size_t budget) {
    while (dns_frame_stream_has_pending(stream)) {
        struct dns_frame_stream_result result;
        CHECK(dns_frame_stream_flush(stream, budget, emit_frame, observer,
                                     &result) == 0,
              "stream pending flush");
        CHECK(result.emitted != 0, "stream pending flush made progress");
    }
    dns_frame_stream_release_pending(stream, release_pending, NULL);
}

static size_t make_frame(uint8_t *buffer, size_t payload_len, uint8_t id) {
    set_prefix(buffer, payload_len);
    for (size_t i = 0; i < payload_len; i++)
        buffer[2 + i] = (uint8_t) (id + i);
    return payload_len + 2;
}

static void test_stream_consecutive_split_reads(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "split stream storage allocation");
    if (storage == NULL)
        return;

    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    uint8_t frame1[14];
    uint8_t frame2[12];
    size_t frame1_len = make_frame(frame1, 12, 0xA1);
    size_t frame2_len = make_frame(frame2, 10, 0xB2);
    uint8_t chunk[32];
    size_t bytes;
    struct dns_frame_stream_result result;

    /* Prefix + part of frame 1. */
    memcpy(chunk, frame1, 6);
    bytes = 6;
    CHECK(dns_frame_stream_feed(&stream, chunk, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "split stream first feed");
    CHECK(observer.calls == 0, "split stream does not parse incomplete frame");
    CHECK(stream.buffered == 6, "split stream retains first partial frame");

    /* Finish frame 1 and begin frame 2 in the same read. */
    memcpy(chunk, frame1 + 6, frame1_len - 6);
    memcpy(chunk + frame1_len - 6, frame2, 6);
    bytes = frame1_len - 6 + 6;
    uint8_t snapshot[sizeof(chunk)];
    memcpy(snapshot, chunk, bytes);
    CHECK(dns_frame_stream_feed(&stream, chunk, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "split stream second feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 1, "split stream parses first frame once");
    CHECK(observer.ids[0] == 0xA1, "split stream records first frame");
    CHECK(observer.emitted == 1 && observer.output_len == 11 &&
          read_prefix(observer.output) == 9 &&
          observer.output[2] == (uint8_t) (frame1[2] ^ 0xFF),
          "split stream emits the rewritten first frame");
    CHECK(stream.buffered == 6, "split stream retains second partial frame");
    CHECK(memcmp(chunk, snapshot, bytes) == 0,
          "split stream forwards completion bytes unchanged");

    /* Finish frame 2. It must not cause frame 1 to be recorded again. */
    memcpy(chunk, frame2 + 6, frame2_len - 6);
    bytes = frame2_len - 6;
    memcpy(snapshot, chunk, bytes);
    CHECK(dns_frame_stream_feed(&stream, chunk, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "split stream third feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 2, "split stream parses second frame once");
    CHECK(observer.ids[1] == 0xB2, "split stream records second frame");
    CHECK(observer.emitted == 2 && observer.output_len == 20 &&
          read_prefix(observer.output + 11) == 7 &&
          observer.output[13] == (uint8_t) (frame2[2] ^ 0xFF),
          "split stream emits the rewritten second frame");
    CHECK(stream.buffered == 0 && stream.expected == 0,
          "split stream is empty after consecutive frames");
    CHECK(memcmp(chunk, snapshot, bytes) == 0,
          "split stream forwards final bytes unchanged");
    free(storage);
}

static void test_stream_coalesced_frames(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "coalesced stream storage allocation");
    if (storage == NULL)
        return;

    uint8_t data[40];
    size_t first = make_frame(data, 8, 0x11);
    size_t second = make_frame(data + first, 12, 0x22);
    size_t third = make_frame(data + first + second, 6, 0x33);
    size_t bytes = first + second + third;
    uint8_t snapshot[sizeof(data)];
    memcpy(snapshot, data, bytes);

    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    struct dns_frame_stream_result result;
    CHECK(dns_frame_stream_feed(&stream, data, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "coalesced stream feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 3, "coalesced stream parses every frame");
    CHECK(result.parsed == 3, "coalesced stream result count");
    CHECK(observer.ids[0] == 0x11 && observer.ids[1] == 0x22 &&
          observer.ids[2] == 0x33, "coalesced stream records all frame ids");
    CHECK(observer.output_len == 23 && read_prefix(observer.output) == 5 &&
          observer.output[2] == (uint8_t) (0x11 ^ 0xFF),
          "coalesced stream emits first rewritten frame");
    CHECK(read_prefix(observer.output + 7) == 9 &&
          observer.output[9] == (uint8_t) (0x22 ^ 0xFF),
          "coalesced stream emits second rewritten frame");
    CHECK(read_prefix(observer.output + 18) == 3 &&
          observer.output[20] == (uint8_t) (0x33 ^ 0xFF),
          "coalesced stream emits third rewritten frame");
    CHECK(bytes == first + second + third,
          "coalesced stream preserves total bytes");
    CHECK(memcmp(data, snapshot, bytes) == 0,
          "coalesced stream forwards original bytes despite parser rewrite");
    CHECK(stream.buffered == 0, "coalesced stream is empty");
    free(storage);
}

static void test_stream_isolated_rewrite(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "isolated stream storage allocation");
    if (storage == NULL)
        return;

    uint8_t frame[14];
    size_t frame_len = make_frame(frame, 12, 0x66);
    uint8_t original_payload[12];
    memcpy(original_payload, frame + 2, sizeof(original_payload));
    size_t bytes = frame_len;
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    struct dns_frame_stream_result result;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "isolated stream feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 1 && result.parsed == 1,
          "isolated stream parses one frame");
    CHECK(bytes == frame_len,
          "isolated stream leaves input buffer untouched");
    CHECK(observer.output_len == frame_len - 3,
          "isolated stream emits safe shortened length");
    CHECK(read_prefix(observer.output) == 9,
          "isolated stream rewrites emitted prefix");
    CHECK(observer.output[2] == (uint8_t) (original_payload[0] ^ 0xFF),
          "isolated stream emits parser payload rewrite");
    CHECK(stream.buffered == 0, "isolated stream is empty after rewrite");
    free(storage);
}

static void test_stream_bounded_flush_and_gate(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "bounded flush storage allocation");
    if (storage == NULL)
        return;

    uint8_t data[40];
    size_t first = make_frame(data, 24, 0x90);
    size_t second = make_frame(data + first, 6, 0xA0);
    size_t bytes = first + second;
    uint8_t snapshot[sizeof(data)];
    memcpy(snapshot, data, bytes);

    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;
    CHECK(dns_frame_stream_feed(&stream, data, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "bounded flush feed");
    CHECK(result.parsed == 2 && observer.calls == 2 &&
          dns_frame_stream_has_pending(&stream),
          "bounded flush queues coalesced frames");

    CHECK(dns_frame_stream_flush(&stream, 5, emit_frame, &observer, &result) == 0 &&
          result.emitted == 5 && observer.output_len == 5,
          "bounded flush respects initial window");
    CHECK(dns_frame_stream_has_pending(&stream) && observer.calls == 2,
          "pending output gates another upstream read");
    CHECK(memcmp(observer.output, snapshot, 5) == 0,
          "bounded flush preserves first bytes");

    CHECK(dns_frame_stream_flush(&stream, 7, emit_frame, &observer, &result) == 0 &&
          result.emitted == 7 && observer.output_len == 12,
          "bounded flush resumes partial drain");
    CHECK(dns_frame_stream_has_pending(&stream) && observer.calls == 2,
          "partial drain keeps upstream gated");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.output_len == bytes &&
          memcmp(observer.output, snapshot, bytes) == 0,
          "bounded flush drains without duplication");
    CHECK(!dns_frame_stream_has_pending(&stream),
          "bounded flush clears pending state");
    free(storage);
}

static void test_stream_tcp_window_ack_and_mss(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "TCP window storage allocation");
    if (storage == NULL)
        return;

    uint8_t frame[22];
    size_t frame_len = make_frame(frame, 20, 0xA5);
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {
            .mss = 7,
            .send_window = 45,
            .enforce_send_window = 1,
    };
    struct dns_frame_stream_result result;
    size_t bytes = frame_len;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "TCP window feed");
    CHECK(observer.local_seq == 0 && observer.unconfirmed == 0 &&
          dns_frame_stream_has_pending(&stream),
          "TCP window starts with output pending");
    CHECK(!dns_frame_should_read(1, 1, observer.send_window),
          "TCP terminal drain keeps upstream reads gated");

    size_t flushes = 0;
    while (dns_frame_stream_has_pending(&stream)) {
        size_t window = dns_frame_send_window(observer.acked, observer.local_seq,
                                              observer.unconfirmed,
                                              observer.send_window);
        if (window == 0) {
            /* This models an ACK handled by handle_tcp(): the peer confirms
             * all emitted bytes and advertises the same window again. */
            observer.acked = observer.local_seq;
            observer.unconfirmed = 0;
            window = dns_frame_send_window(observer.acked, observer.local_seq,
                                           observer.unconfirmed,
                                           observer.send_window);
        }
        size_t budget = dns_frame_send_budget(window, observer.mss);
        CHECK(budget != 0 && budget <= observer.mss,
              "TCP window computes MSS-bounded flush budget");
        CHECK(dns_frame_stream_flush(&stream, budget, emit_tcp_frame,
                                     &observer, &result) == 0,
              "TCP window flush");
        CHECK(result.emitted != 0, "TCP window flush makes progress");
        flushes++;
        CHECK(flushes < 16, "TCP window flush remains bounded");
    }
    CHECK(observer.output_len == frame_len &&
          memcmp(observer.output, frame, frame_len) == 0,
          "TCP window drains complete response in order");
    CHECK(observer.local_seq == frame_len && observer.writes > 1,
          "TCP window advances local sequence per segment");
    CHECK(!dns_frame_stream_has_pending(&stream),
          "TCP window clears pending output after ACK reopening");
    CHECK(dns_frame_should_read(0, 0, observer.send_window),
          "TCP window reopens upstream reads after drain");
    dns_frame_stream_release_pending(&stream, release_pending, NULL);
    free(storage);
}

static void test_stream_emitter_error_retry(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "emitter error storage allocation");
    if (storage == NULL)
        return;

    uint8_t frame[14];
    size_t frame_len = make_frame(frame, 12, 0xB0);
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;
    size_t bytes = frame_len;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "emitter error feed");
    observer.emit_fail = 1;
    CHECK(dns_frame_stream_flush(&stream, frame_len, emit_frame, &observer,
                                 &result) < 0 && result.emitted == 0,
          "emitter error leaves output pending");
    observer.emit_fail = 0;
    CHECK(dns_frame_stream_flush(&stream, frame_len, emit_frame, &observer,
                                 &result) == 0 && result.emitted == frame_len,
          "emitter retry succeeds");
    CHECK(observer.output_len == frame_len &&
          memcmp(observer.output, frame, frame_len) == 0,
          "emitter retry does not duplicate or lose bytes");
    free(storage);
}

static void test_stream_pending_compaction(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "pending compaction storage allocation");
    if (storage == NULL)
        return;

    uint8_t data[32];
    size_t first = make_frame(data, 6, 0xC0);
    size_t second = make_frame(data + first, 6, 0xD0);
    size_t initial = first + second;
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;
    size_t bytes = initial;
    CHECK(dns_frame_stream_feed(&stream, data, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "pending compaction initial feed");
    CHECK(stream.pending_capacity == initial &&
          dns_frame_stream_flush(&stream, 5, emit_frame, &observer, &result) == 0,
          "pending compaction partial drain");
    const size_t reserve_calls = observer.reserve_calls;
    uint8_t third[5];
    size_t third_len = make_frame(third, 3, 0xE0);
    uint8_t snapshot[sizeof(data)];
    memcpy(snapshot, data, initial);
    CHECK(dns_frame_stream_feed(&stream, third, &third_len, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "pending compaction append after drain");
    CHECK(stream.pending_capacity == initial && observer.reserve_calls == reserve_calls,
          "pending compaction reuses capacity after offset advancement");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    memcpy(snapshot + initial, third, third_len);
    CHECK(observer.output_len == initial + third_len &&
          memcmp(observer.output, snapshot, observer.output_len) == 0,
          "pending compaction preserves queued byte order");
    CHECK(observer.calls == 3, "pending compaction parses appended frame once");
    free(storage);
}

static void test_stream_geometric_pending_growth(void) {
    enum { frame_count = 64 };
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    uint8_t *data = malloc(frame_count * 3);
    CHECK(storage != NULL && data != NULL, "geometric pending storage allocation");
    if (storage == NULL || data == NULL) {
        free(storage);
        free(data);
        return;
    }

    size_t bytes = 0;
    size_t zero_frames = 0;
    size_t tiny_frames = 0;
    for (size_t i = 0; i < frame_count; i++) {
        if (i % 8 == 0) {
            bytes += make_frame(data + bytes, 1, (uint8_t) (0x20 + i));
            tiny_frames++;
        } else {
            data[bytes++] = 0;
            data[bytes++] = 0;
            zero_frames++;
        }
    }

    uint8_t *snapshot = malloc(bytes);
    CHECK(snapshot != NULL, "geometric pending snapshot allocation");
    if (snapshot != NULL)
        memcpy(snapshot, data, bytes);

    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;
    size_t input = bytes;
    CHECK(dns_frame_stream_feed(&stream, data, &input, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "geometric pending feed");
    CHECK(result.parsed == tiny_frames && result.skipped == zero_frames &&
          observer.calls == tiny_frames,
          "geometric pending parses zero and tiny frames in order");
    CHECK(stream.pending_capacity <= DNS_FRAME_MAX_PENDING &&
          stream.pending_capacity < bytes * 2,
          "geometric pending capacity stays bounded near queued bytes");
    CHECK(observer.reserve_calls <= 8,
          "geometric pending avoids per-frame reserve calls");
    CHECK(observer.pending_transient_peak <= DNS_FRAME_MAX_TRANSIENT_STORAGE,
          "geometric pending replacement stays within transient bound");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.output_len == bytes && snapshot != NULL &&
          memcmp(observer.output, snapshot, bytes) == 0,
          "geometric pending preserves coalesced byte order");

    free(snapshot);
    free(data);
    free(storage);
}

static void test_stream_lifecycle_decisions(void) {
    CHECK(dns_frame_should_read(0, 0, 1),
          "lifecycle allows read with window and no pending output");
    CHECK(!dns_frame_should_read(1, 0, 1),
          "lifecycle gates read during terminal drain");
    CHECK(!dns_frame_should_read(0, 1, 1),
          "lifecycle gates read while output is pending");
    CHECK(!dns_frame_should_read(0, 0, 0),
          "lifecycle gates read with a closed window");
    CHECK(dns_frame_should_flush_ack(1, 0, 1) &&
          dns_frame_should_flush_ack(1, DNS_FRAME_TERMINAL_DRAIN, 0) &&
          !dns_frame_should_flush_ack(0, 0, 1) &&
          !dns_frame_should_flush_ack(1, 0, 0),
          "lifecycle flushes pending output for ACK packets with or without payload");
    CHECK(dns_frame_socket_terminal_event(1, 0, 1, 0) &&
          dns_frame_socket_terminal_event(0, 1, 0, 0) &&
          !dns_frame_socket_terminal_event(0, 1, 1, 0),
          "lifecycle detects errors and masked HUP without readable data");
    CHECK(dns_frame_hup_pending_state(0, 1, 1) ==
                  DNS_FRAME_TERMINAL_HUP_PENDING &&
          dns_frame_hup_pending_state(1, 1, 1) == DNS_FRAME_TERMINAL_NONE &&
          dns_frame_hup_pending_state(0, 1, 0) == DNS_FRAME_TERMINAL_NONE,
          "lifecycle retains HUP fd for pending data before rearming");
    CHECK(!dns_frame_should_resume_hup(DNS_FRAME_TERMINAL_HUP_PENDING, 1, 1) &&
          !dns_frame_should_resume_hup(DNS_FRAME_TERMINAL_HUP_PENDING, 0, 0) &&
          dns_frame_should_resume_hup(DNS_FRAME_TERMINAL_HUP_PENDING, 0, 1) &&
          !dns_frame_should_resume_hup(DNS_FRAME_TERMINAL_NONE, 0, 1),
          "lifecycle rearms HUP fd only after pending drain and window reopen");
    CHECK(dns_frame_hup_rearm_failure_state(DNS_FRAME_TERMINAL_HUP_PENDING) ==
                  DNS_FRAME_TERMINAL_DRAIN &&
          dns_frame_hup_rearm_failure_state(DNS_FRAME_TERMINAL_NONE) ==
                  DNS_FRAME_TERMINAL_NONE,
          "lifecycle preserves final bytes through HUP rearm failure");
    CHECK(dns_frame_eof_terminal_state(1, 0, 0) == DNS_FRAME_TERMINAL_FIN_DRAIN &&
          dns_frame_eof_terminal_state(1, 1, 0) == DNS_FRAME_TERMINAL_DRAIN &&
          dns_frame_eof_terminal_state(1, 0, 1) == DNS_FRAME_TERMINAL_DRAIN &&
          dns_frame_eof_terminal_state(0, 1, 0) == DNS_FRAME_TERMINAL_NONE &&
          dns_frame_eof_terminal_state(0, 0, 0) == DNS_FRAME_TERMINAL_FIN_DRAIN,
          "lifecycle drains complete output before FIN or truncated-frame reset");
    CHECK(dns_frame_requires_recheck(0) && !dns_frame_requires_recheck(1),
          "lifecycle rechecks only a closed window");
    CHECK(dns_frame_flush_chunk_allowed(0) &&
          dns_frame_flush_chunk_allowed(DNS_FRAME_MAX_FLUSH_CHUNKS - 1) &&
          !dns_frame_flush_chunk_allowed(DNS_FRAME_MAX_FLUSH_CHUNKS),
          "lifecycle caps per-flush chunk work");
    CHECK(dns_frame_terminal_next(DNS_FRAME_TERMINAL_DRAIN, 1, 5, 5) ==
                  DNS_FRAME_TERMINAL_DRAIN &&
          dns_frame_terminal_next(DNS_FRAME_TERMINAL_DRAIN, 0, 4, 5) ==
                  DNS_FRAME_TERMINAL_WAIT_ACK &&
          dns_frame_terminal_next(DNS_FRAME_TERMINAL_DRAIN, 0, 5, 5) ==
                  DNS_FRAME_TERMINAL_NONE &&
          dns_frame_terminal_next(DNS_FRAME_TERMINAL_WAIT_ACK, 0, 4, 5) ==
                  DNS_FRAME_TERMINAL_WAIT_ACK &&
          dns_frame_terminal_next(DNS_FRAME_TERMINAL_WAIT_ACK, 0, 5, 5) ==
          DNS_FRAME_TERMINAL_NONE,
          "lifecycle waits for final ACK before terminal reset");
    CHECK(dns_frame_terminal_timeout(DNS_FRAME_TERMINAL_DRAIN, 20) == 20 &&
          dns_frame_terminal_timeout(DNS_FRAME_TERMINAL_WAIT_ACK, 20) == 20 &&
          dns_frame_terminal_timeout(DNS_FRAME_TERMINAL_NONE, 20) == 0,
          "lifecycle uses close timeout while terminal output drains or waits");
    CHECK(!dns_frame_recheck_due(99, 0, 100) &&
          !dns_frame_recheck_due(100, 0, 100) &&
          dns_frame_recheck_due(101, 0, 100) &&
          !dns_frame_recheck_due(99, 100, 100),
          "lifecycle uses the 100 ms recheck cadence");
}

static void test_stream_pending_reserve_failure_is_terminal(void) {
    uint8_t storage[DNS_FRAME_MAX_BUFFER];
    uint8_t frame[10];
    size_t frame_len = make_frame(frame, 8, 0xF0);
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, sizeof(storage));
    struct stream_observer observer = {.pending_reserve_fail = 1};
    struct dns_frame_stream_result result;
    size_t bytes = frame_len;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) < 0,
          "pending reserve failure reports error");
    CHECK(stream.disabled && !dns_frame_stream_has_pending(&stream) &&
          observer.calls == 0,
          "pending reserve failure does not claim transparent fail-open");
    dns_frame_stream_reset(&stream);
}

static void test_stream_late_reserve_failure_is_atomic(void) {
    uint8_t storage[DNS_FRAME_MAX_BUFFER];
    uint8_t data[12];
    size_t first = make_frame(data, 3, 0x61);
    size_t second = make_frame(data + first, 3, 0x71);
    const size_t total = first + second;
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, sizeof(storage));
    struct stream_observer observer = {
            .pending_reserve_fail_after = 1,
    };
    struct dns_frame_stream_result result;
    size_t bytes = total;
    CHECK(dns_frame_stream_feed(&stream, data, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) < 0,
          "late pending reserve failure reports terminal error");
    CHECK(observer.calls == 1 && result.parsed == 1 && stream.disabled &&
          !stream.pending_passthrough && stream.pending_length == first,
          "late pending reserve failure keeps only prior rewritten output");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.output_len == first &&
          memcmp(observer.output, data, first) == 0,
          "late pending reserve failure does not append raw suffix");
    dns_frame_stream_reset(&stream);
}

static void test_stream_dynamic_reserve(void) {
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, NULL, 0);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    struct dns_frame_stream_result result;
    uint8_t data[24];
    size_t first = make_frame(data, 8, 0x12);
    size_t second = make_frame(data + first, 12, 0x23);
    size_t bytes = first + second;
    CHECK(dns_frame_stream_feed(&stream, data, &bytes, observe_frame,
                                reserve_frame, reserve_pending, &observer,
                                &result) == 0,
          "dynamic reserve stream feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 2 && observer.emitted == 1,
          "dynamic reserve parses and emits each frame");
    CHECK(observer.reserve_calls >= 4,
          "dynamic reserve grows frame and pending storage");
    CHECK(observer.output_len == (8 - 3 + 2) + (12 - 3 + 2),
          "dynamic reserve emits shortened frames");
    CHECK(read_prefix(observer.output) == 5 && observer.output[2] == (uint8_t) (0x12 ^ 0xFF),
          "dynamic reserve emits first mutation");
    size_t second_offset = 8 - 3 + 2;
    CHECK(read_prefix(observer.output + second_offset) == 9 &&
          observer.output[second_offset + 2] == (uint8_t) (0x23 ^ 0xFF),
          "dynamic reserve emits second mutation");
    CHECK(stream.buffered == 0 && stream.capacity == second,
          "dynamic reserve retains only latest declared capacity until release");
    free(stream.buffer);
}

static void test_stream_allocation_failure_fail_open(void) {
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, NULL, 0);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    struct dns_frame_stream_result result;
    uint8_t frame[10];
    size_t frame_len = make_frame(frame, 8, 0x31);

    /* Keep the first prefix byte, then fail while growing after the second
     * prefix byte reveals the declared frame size. */
    size_t bytes = 1;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                reserve_frame, reserve_pending, &observer,
                                &result) == 0,
          "allocation failure prefix feed");
    CHECK(stream.buffered == 1 && observer.output_len == 0,
          "allocation failure retains prefix before reserve");
    observer.reserve_fail = 1;
    bytes = 4;
    uint8_t second_and_part[4];
    memcpy(second_and_part, frame + 1, bytes);
    CHECK(dns_frame_stream_feed(&stream, second_and_part, &bytes,
                                observe_frame, reserve_frame, reserve_pending,
                                &observer, &result) == 0,
          "allocation failure fail-open feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(stream.disabled && observer.calls == 0 && observer.output_len == 5,
          "allocation failure queues held prefix and current bytes");
    CHECK(memcmp(observer.output, frame, 5) == 0,
          "allocation failure preserves held byte order");

    bytes = frame_len - 5;
    CHECK(dns_frame_stream_feed(&stream, frame + 5, &bytes, observe_frame,
                                reserve_frame, reserve_pending, &observer,
                                &result) == 0,
          "allocation failure subsequent raw feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.output_len == frame_len &&
          memcmp(observer.output, frame, frame_len) == 0,
          "allocation failure keeps future forwarding fail-open");
    free(stream.buffer);
}

static void test_stream_partial_prefix(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "partial prefix storage allocation");
    if (storage == NULL)
        return;

    uint8_t frame[10];
    size_t frame_len = make_frame(frame, 8, 0x44);
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {.mutate = 1, .shrink = 1};
    struct dns_frame_stream_result result;
    size_t bytes = 1;
    uint8_t prefix = frame[0];
    CHECK(dns_frame_stream_feed(&stream, &prefix, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "partial prefix first byte");
    CHECK(observer.calls == 0 && stream.buffered == 1,
          "partial prefix retains one prefix byte");

    bytes = 1;
    uint8_t second_prefix = frame[1];
    CHECK(dns_frame_stream_feed(&stream, &second_prefix, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "partial prefix second byte");
    CHECK(observer.calls == 0 && stream.buffered == 2 &&
          stream.expected == frame_len,
          "partial prefix waits for payload after complete prefix");

    bytes = frame_len - 2;
    uint8_t payload[8];
    memcpy(payload, frame + 2, bytes);
    uint8_t snapshot[sizeof(payload)];
    memcpy(snapshot, payload, bytes);
    CHECK(dns_frame_stream_feed(&stream, payload, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "partial prefix payload");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 1 && observer.ids[0] == 0x44,
          "partial prefix parses complete frame once");
    CHECK(observer.output_len == 7 && read_prefix(observer.output) == 5,
          "partial prefix emits rewritten frame after completion");
    CHECK(memcmp(payload, snapshot, bytes) == 0,
          "partial prefix forwards payload unchanged");
    free(storage);
}

static void test_stream_zero_and_oversized_frames(void) {
    uint8_t storage[16];
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, sizeof(storage));
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;

    /* Zero-length frames are consumed but are not DNS messages. */
    uint8_t valid[8];
    size_t valid_len = make_frame(valid, 6, 0x55);
    uint8_t zero_and_valid[sizeof(valid) + 2];
    zero_and_valid[0] = 0;
    zero_and_valid[1] = 0;
    memcpy(zero_and_valid + 2, valid, valid_len);
    size_t bytes = valid_len + 2;
    CHECK(dns_frame_stream_feed(&stream, zero_and_valid, &bytes,
                                observe_frame, NULL, reserve_pending,
                                &observer, &result) == 0,
          "zero-length stream feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(result.skipped == 1 && result.parsed == 1 && observer.calls == 1,
          "zero-length frame skipped and following frame parsed");
    CHECK(observer.ids[0] == 0x55, "zero-length stream stays aligned");

    /* A maximum-prefix frame is too large for this deliberately small test
     * storage. It is discarded by declared length, not reinterpreted as a
     * sequence of prefixes, and the following valid frame still parses. */
    dns_frame_stream_reset(&stream);
    observer.calls = 0;
    observer.emitted = 0;
    observer.output_len = 0;
    uint8_t first[2 + 5];
    set_prefix(first, DNS_FRAME_MAX_PAYLOAD);
    memset(first + 2, 0x66, 5);
    bytes = sizeof(first);
    CHECK(dns_frame_stream_feed(&stream, first, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "oversized stream first feed");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 0 && stream.disabled && observer.output_len == sizeof(first),
          "oversized stream switches to bounded fail-open forwarding");

    const size_t remaining = DNS_FRAME_MAX_PAYLOAD - 5;
    uint8_t *tail = malloc(remaining + valid_len);
    CHECK(tail != NULL, "oversized stream tail allocation");
    if (tail != NULL) {
        memset(tail, 0x77, remaining);
        memcpy(tail + remaining, valid, valid_len);
        bytes = remaining + valid_len;
        CHECK(dns_frame_stream_feed(&stream, tail, &bytes, observe_frame,
                                    NULL, reserve_pending, &observer, &result) == 0,
              "oversized stream completion feed");
        flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
        CHECK(observer.calls == 0 && observer.output_len == DNS_FRAME_MAX_BUFFER + valid_len,
              "oversized stream forwards all subsequent bytes unchanged");
        CHECK(memcmp(observer.output, first, sizeof(first)) == 0 &&
              memcmp(observer.output + DNS_FRAME_MAX_BUFFER, valid, valid_len) == 0,
              "oversized stream preserves following bytes after max frame");
        free(tail);
    }
}

static void test_stream_reset_and_teardown(void) {
    uint8_t *storage = malloc(DNS_FRAME_MAX_BUFFER);
    CHECK(storage != NULL, "reset stream storage allocation");
    if (storage == NULL)
        return;

    uint8_t frame[12];
    size_t frame_len = make_frame(frame, 10, 0x77);
    struct dns_frame_stream stream;
    dns_frame_stream_init(&stream, storage, DNS_FRAME_MAX_BUFFER);
    struct stream_observer observer = {0};
    struct dns_frame_stream_result result;
    size_t bytes = 5;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "reset stream partial feed");
    CHECK(stream.buffered == 5 && observer.calls == 0,
          "reset stream has partial state before reset");

    dns_frame_stream_reset(&stream);
    CHECK(stream.buffered == 0 && stream.expected == 0,
          "reset stream drops partial state");
    bytes = frame_len;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "reset stream fresh frame feed");
    CHECK(dns_frame_stream_has_pending(&stream),
          "reset stream has output before teardown");
    dns_frame_stream_reset(&stream);
    CHECK(!dns_frame_stream_has_pending(&stream),
          "reset stream drops pending output on teardown");
    dns_frame_stream_release_pending(&stream, release_pending, NULL);
    observer.calls = 0;
    observer.output_len = 0;
    observer.emitted = 0;
    bytes = frame_len;
    CHECK(dns_frame_stream_feed(&stream, frame, &bytes, observe_frame,
                                NULL, reserve_pending, &observer, &result) == 0,
          "reset stream feed after pending teardown");
    flush_stream(&stream, &observer, DNS_FRAME_MAX_BUFFER);
    CHECK(observer.calls == 1 && observer.ids[0] == 0x77,
          "reset stream parses fresh frame once");

    /* The owner, as clear_tcp_data() does in production, releases storage
     * after resetting the state. */
    dns_frame_stream_reset(&stream);
    free(storage);
}

int main(void) {
    test_stream_consecutive_split_reads();
    test_stream_coalesced_frames();
    test_stream_isolated_rewrite();
    test_stream_bounded_flush_and_gate();
    test_stream_tcp_window_ack_and_mss();
    test_stream_emitter_error_retry();
    test_stream_pending_compaction();
    test_stream_geometric_pending_growth();
    test_stream_lifecycle_decisions();
    test_stream_pending_reserve_failure_is_terminal();
    test_stream_late_reserve_failure_is_atomic();
    test_stream_dynamic_reserve();
    test_stream_allocation_failure_fail_open();
    test_stream_partial_prefix();
    test_stream_zero_and_oversized_frames();
    test_stream_reset_and_teardown();

    if (failures == 0) {
        printf("dns_frame_test: all tests passed\n");
        return 0;
    }

    fprintf(stderr, "dns_frame_test: %d assertion(s) failed\n", failures);
    return 1;
}
