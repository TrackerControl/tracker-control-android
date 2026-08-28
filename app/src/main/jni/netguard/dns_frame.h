/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/

#ifndef DNS_FRAME_H
#define DNS_FRAME_H

/*
 * DNS-over-TCP framing helpers, extracted out of
 * check_tcp_socket() (tcp.c) so it can be unit-tested on the host without
 * pulling in JNI/session dependencies. This header and its implementation
 * (dns_frame.c) must only depend on libc: no netguard.h, no JNI.
 *
 * DNS-over-TCP is a byte stream, rather than a packet protocol. A frame can
 * be split over any number of recv() calls, and one recv() can contain any
 * number of complete frames. The stream helper below retains at most one
 * frame under construction (the maximum DNS-over-TCP payload is 65535 bytes).
 * Complete frames are parsed exactly once, copied to a bounded pending-output
 * queue, and emitted later by dns_frame_stream_flush(). This lets the owner
 * respect its current TCP send window even when a response is larger than
 * that window.
 */

#include <stddef.h>
#include <stdint.h>

/* DNS-over-TCP's two-byte length prefix is an unsigned 16-bit value. */
#define DNS_FRAME_MAX_PAYLOAD ((size_t) 0xFFFF)
#define DNS_FRAME_MAX_BUFFER (DNS_FRAME_MAX_PAYLOAD + 2)
/* One maximum frame plus one maximum-sized socket read. */
#define DNS_FRAME_MAX_PENDING (DNS_FRAME_MAX_BUFFER * 2)
/* Reassembly plus pending output in steady state. A geometric pending
 * replacement keeps the old allocation at or below half the pending cap, so
 * one replacement peaks at this bound (including the reassembly buffer). */
#define DNS_FRAME_MAX_SESSION_STORAGE (DNS_FRAME_MAX_BUFFER + DNS_FRAME_MAX_PENDING)
#define DNS_FRAME_MAX_TRANSIENT_STORAGE \
    (DNS_FRAME_MAX_SESSION_STORAGE + DNS_FRAME_MAX_PENDING / 2)
/* tcp.c emits at most this many MSS/window-bounded chunks per invocation;
 * later ACKs resume the queue without allowing unconfirmed to run away. */
#define DNS_FRAME_MAX_FLUSH_CHUNKS ((size_t) 16)

#define DNS_FRAME_TERMINAL_NONE 0
#define DNS_FRAME_TERMINAL_DRAIN 1
#define DNS_FRAME_TERMINAL_WAIT_ACK 2
/* Upstream HUP: pending output drains before the fd is read again. */
#define DNS_FRAME_TERMINAL_HUP_PENDING 3
/* Upstream EOF: drain rewritten bytes, then send a downstream FIN. */
#define DNS_FRAME_TERMINAL_FIN_DRAIN 4

#ifdef __cplusplus
extern "C" {
#endif

struct dns_frame_stream;

typedef size_t (*dns_frame_parse_fn)(void *ctx, uint8_t *payload, size_t length);
typedef int (*dns_frame_emit_fn)(void *ctx, const uint8_t *frame, size_t length);
typedef int (*dns_frame_reserve_fn)(void *ctx, struct dns_frame_stream *stream,
                                    size_t required);
typedef int (*dns_frame_pending_reserve_fn)(void *ctx,
                                            struct dns_frame_stream *stream,
                                            size_t required);
typedef void (*dns_frame_pending_release_fn)(void *ctx,
                                             struct dns_frame_stream *stream);

struct dns_frame_stream {
    /* Storage is supplied by the owner so Android can use ng_malloc/ng_free. */
    uint8_t *buffer;
    size_t capacity;
    /* Bytes retained for the current frame, including its prefix. */
    size_t buffered;
    /* Total frame size including prefix after a complete prefix is known. */
    size_t expected;
    /* A frame has received bytes from more than one feed call. */
    int split;
    /* Set by the owner when allocation fails; parsing stays disabled. */
    int disabled;
    /* Complete, rewritten frames waiting for the downstream send window. */
    uint8_t *pending;
    size_t pending_capacity;
    size_t pending_offset;
    size_t pending_length;
    /* Pending bytes are raw fail-open data rather than rewritten frames. */
    int pending_passthrough;
};

struct dns_frame_stream_result {
    /* Number of complete, non-zero frames handed to the parser. */
    size_t parsed;
    /* Number of zero-length frames consumed without invoking the parser. */
    size_t skipped;
    /* Number of bytes emitted by dns_frame_stream_flush(). */
    size_t emitted;
};

/* Initializes an empty stream state. storage may be NULL; feed() calls reserve
 * when it needs storage for a declared frame. */
void dns_frame_stream_init(struct dns_frame_stream *stream,
                           uint8_t *storage, size_t capacity);

/* Drops a partial frame and any pending output, starting the next feed at a
 * fresh prefix. Storage remains owned by the caller and is not freed. */
void dns_frame_stream_reset(struct dns_frame_stream *stream);

/* Disables inspection after an allocation failure. This is fail-open; a
 * subsequent feed queues any held bytes followed by its input unchanged. */
void dns_frame_stream_disable(struct dns_frame_stream *stream);

/* Feeds one recv() buffer. The input is held until each declared frame is
 * complete, then parsed once and appended to the pending output queue. The
 * parser may shorten a frame; the helper rewrites its prefix before queueing
 * it. The input is never retained by the helper and *bytes is not changed. On
 * reserve failure, held/current bytes are queued unchanged and inspection is
 * disabled unless rewritten output is already queued; in that mixed case it
 * returns -1 so the owner can terminate atomically. */
int dns_frame_stream_feed(struct dns_frame_stream *stream,
                          uint8_t *data, size_t *bytes,
                          dns_frame_parse_fn parser,
                          dns_frame_reserve_fn reserve,
                          dns_frame_pending_reserve_fn pending_reserve,
                          void *ctx,
                          struct dns_frame_stream_result *result);

/* Emits at most max_bytes from the pending queue. The caller chooses a budget
 * that accounts for the current TCP send window and MSS. Bytes are removed
 * only after the emitter succeeds, so an emitter error cannot duplicate data
 * on a later flush. */
int dns_frame_stream_flush(struct dns_frame_stream *stream, size_t max_bytes,
                           dns_frame_emit_fn emitter, void *ctx,
                           struct dns_frame_stream_result *result);

int dns_frame_stream_has_pending(const struct dns_frame_stream *stream);

/* Releases pending storage after the queue has drained. The owner supplies
 * the matching allocator because Android uses ng_malloc/ng_free. */
void dns_frame_stream_release_pending(struct dns_frame_stream *stream,
                                      dns_frame_pending_release_fn release,
                                      void *ctx);

/* Shared TCP-output arithmetic used by the production path and host tests. */
uint32_t dns_frame_send_window(uint32_t acked, uint32_t local_seq,
                               uint16_t unconfirmed, uint32_t send_window);
size_t dns_frame_send_budget(uint32_t send_window, uint16_t mss);

/* Shared lifecycle decisions used by tcp.c and host tests. */
int dns_frame_should_read(int terminal, int pending, uint32_t send_window);
int dns_frame_should_flush_ack(int has_ack, int terminal, int pending);
int dns_frame_should_resume_hup(int terminal, int pending, uint32_t send_window);
int dns_frame_hup_rearm_failure_state(int terminal);
int dns_frame_eof_terminal_state(int pending, int buffered, int forward_pending);
int dns_frame_socket_terminal_event(int has_error, int has_hup, int has_read,
                                    int pending);
int dns_frame_hup_pending_state(int has_error, int has_hup, int pending);
int dns_frame_recheck_due(long long now, long long last_check, long long interval);
int dns_frame_requires_recheck(uint32_t send_window);
int dns_frame_flush_chunk_allowed(size_t chunks);
int dns_frame_terminal_timeout(int terminal, int close_timeout);
int dns_frame_terminal_next(int state, int pending, uint32_t acked,
                            uint32_t local_seq);

#ifdef __cplusplus
}
#endif

#endif /* DNS_FRAME_H */
