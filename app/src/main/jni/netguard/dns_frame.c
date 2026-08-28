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

#include "dns_frame.h"

#include <string.h>

static void dns_frame_stream_clear_frame(struct dns_frame_stream *stream) {
    stream->buffered = 0;
    stream->expected = 0;
    stream->split = 0;
}

void dns_frame_stream_init(struct dns_frame_stream *stream,
                           uint8_t *storage, size_t capacity) {
    memset(stream, 0, sizeof(*stream));
    stream->buffer = storage;
    stream->capacity = capacity;
}

void dns_frame_stream_reset(struct dns_frame_stream *stream) {
    dns_frame_stream_clear_frame(stream);
    stream->pending_offset = 0;
    stream->pending_length = 0;
    stream->pending_passthrough = 0;
}

void dns_frame_stream_disable(struct dns_frame_stream *stream) {
    stream->disabled = 1;
}

static int dns_frame_pending_reserve(struct dns_frame_stream *stream,
                                     size_t additional,
                                     dns_frame_pending_reserve_fn reserve,
                                     void *ctx) {
    if (stream->pending_offset != 0 &&
        additional > stream->pending_capacity - stream->pending_length) {
        const size_t retained = stream->pending_length - stream->pending_offset;
        memmove(stream->pending, stream->pending + stream->pending_offset,
                retained);
        stream->pending_offset = 0;
        stream->pending_length = retained;
    }

    if (additional > SIZE_MAX - stream->pending_length)
        return -1;
    const size_t required = stream->pending_length + additional;
    if (required <= stream->pending_capacity)
        return 0;
    if (reserve == NULL || reserve(ctx, stream, required) < 0 ||
        stream->pending == NULL || stream->pending_capacity < required)
        return -1;
    return 0;
}

static int dns_frame_pending_append(struct dns_frame_stream *stream,
                                    const uint8_t *data, size_t bytes,
                                    dns_frame_pending_reserve_fn reserve,
                                    void *ctx) {
    if (bytes == 0)
        return 0;
    if (dns_frame_pending_reserve(stream, bytes, reserve, ctx) < 0)
        return -1;
    memcpy(stream->pending + stream->pending_length, data, bytes);
    stream->pending_length += bytes;
    return 0;
}

/* Allocation failure must not lose a prefix or bytes already retained from a
 * preceding recv(). If rewritten output is queued, report a terminal error
 * instead of mixing raw bytes after it; otherwise queue the held bytes first,
 * then the unread part of this recv(), and enter fail-open mode. */
static int dns_frame_fail_open(struct dns_frame_stream *stream,
                               uint8_t *data, size_t pos, size_t input_len,
                               dns_frame_pending_reserve_fn reserve, void *ctx) {
    stream->disabled = 1;
    /* Never mix raw fail-open bytes after already-rewritten output. The owner
     * must terminate so queued bytes cannot be observed in a different order. */
    if (dns_frame_stream_has_pending(stream) && !stream->pending_passthrough)
        return -1;
    stream->pending_passthrough = 1;
    if (dns_frame_pending_append(stream, stream->buffer, stream->buffered,
                                 reserve, ctx) < 0)
        return -1;
    if (pos < input_len &&
        dns_frame_pending_append(stream, data + pos, input_len - pos,
                                 reserve, ctx) < 0)
        return -1;
    dns_frame_stream_clear_frame(stream);
    return 0;
}

int dns_frame_stream_feed(struct dns_frame_stream *stream,
                          uint8_t *data, size_t *bytes,
                          dns_frame_parse_fn parser,
                          dns_frame_reserve_fn reserve,
                          dns_frame_pending_reserve_fn pending_reserve,
                          void *ctx,
                          struct dns_frame_stream_result *result) {
    if (stream == NULL || bytes == NULL || (data == NULL && *bytes != 0))
        return -1;

    if (result != NULL)
        memset(result, 0, sizeof(*result));

    const size_t input_len = *bytes;

    if (stream->disabled) {
        /* dns_frame_stream_disable() can be called while a prefix is held.
         * Preserve that prefix before switching to raw fail-open forwarding. */
        return dns_frame_fail_open(stream, data, 0, input_len,
                                   pending_reserve, ctx);
    }

    size_t pos = 0;

    while (pos < input_len) {
        /* Gather the two-byte prefix. A one-byte prefix at the end of this
         * feed remains in stream->buffer and is never interpreted as payload
         * or as a future prefix. */
        if (stream->expected == 0) {
            if (stream->buffer == NULL || stream->capacity < 2) {
                if (reserve == NULL || reserve(ctx, stream, 2) < 0 ||
                    stream->buffer == NULL || stream->capacity < 2)
                    return dns_frame_fail_open(stream, data, pos, input_len,
                                               pending_reserve, ctx);
            }
            size_t needed = 2 - stream->buffered;
            size_t available = input_len - pos;
            size_t copied = needed < available ? needed : available;
            memcpy(stream->buffer + stream->buffered, data + pos, copied);
            stream->buffered += copied;
            pos += copied;

            if (stream->buffered < 2) {
                stream->split = 1;
                continue;
            }

            size_t payload_len = ((size_t) stream->buffer[0] << 8) |
                                 stream->buffer[1];
            stream->expected = payload_len + 2;

            if (payload_len == 0) {
                if (dns_frame_pending_append(stream, stream->buffer, 2,
                                             pending_reserve, ctx) < 0)
                    return dns_frame_fail_open(stream, data, pos, input_len,
                                               pending_reserve, ctx);
                if (result != NULL)
                    result->skipped++;
                dns_frame_stream_clear_frame(stream);
                continue;
            }

            if (stream->expected > stream->capacity) {
                if (reserve != NULL && reserve(ctx, stream, stream->expected) == 0 &&
                    stream->buffer != NULL && stream->capacity >= stream->expected)
                    continue;

                /* The prefix cannot be retained at the declared size. Queue
                 * it and all remaining bytes unchanged, then disable further
                 * inspection: without a frame-sized buffer, treating a later
                 * prefix as inspectable could reorder or drop the stream. */
                return dns_frame_fail_open(stream, data, pos, input_len,
                                           pending_reserve, ctx);
            }
        }

        size_t needed = stream->expected - stream->buffered;
        size_t available = input_len - pos;
        size_t copied = needed < available ? needed : available;
        memcpy(stream->buffer + stream->buffered, data + pos, copied);
        stream->buffered += copied;
        pos += copied;

        if (stream->buffered < stream->expected) {
            stream->split = 1;
            continue;
        }

        /* Reserve before calling the parser. If queuing the frame can fail,
         * parser mutations must not be allowed to alter fail-open bytes. */
        if (dns_frame_pending_reserve(stream, stream->expected,
                                      pending_reserve, ctx) < 0)
            return dns_frame_fail_open(stream, data, pos, input_len,
                                       pending_reserve, ctx);

        const size_t payload_len = stream->expected - 2;
        size_t parsed_len = payload_len;
        if (parser != NULL)
            parsed_len = parser(ctx, stream->buffer + 2, payload_len);
        if (parsed_len > payload_len)
            parsed_len = payload_len;

        if (parsed_len != payload_len) {
            stream->buffer[0] = (uint8_t) (parsed_len >> 8);
            stream->buffer[1] = (uint8_t) parsed_len;
        }
        if (dns_frame_pending_append(stream, stream->buffer, parsed_len + 2,
                                     pending_reserve, ctx) < 0)
            return -1;

        if (result != NULL)
            result->parsed++;
        dns_frame_stream_clear_frame(stream);
    }

    return 0;
}

int dns_frame_stream_flush(struct dns_frame_stream *stream, size_t max_bytes,
                           dns_frame_emit_fn emitter, void *ctx,
                           struct dns_frame_stream_result *result) {
    if (stream == NULL)
        return -1;
    if (result != NULL)
        memset(result, 0, sizeof(*result));
    if (max_bytes == 0 || !dns_frame_stream_has_pending(stream))
        return 0;
    if (emitter == NULL)
        return -1;

    size_t bytes = stream->pending_length - stream->pending_offset;
    if (bytes > max_bytes)
        bytes = max_bytes;
    if (emitter(ctx, stream->pending + stream->pending_offset, bytes) < 0)
        return -1;
    stream->pending_offset += bytes;
    if (result != NULL)
        result->emitted = bytes;
    if (stream->pending_offset == stream->pending_length) {
        stream->pending_offset = 0;
        stream->pending_length = 0;
        stream->pending_passthrough = 0;
    }
    return 0;
}

int dns_frame_stream_has_pending(const struct dns_frame_stream *stream) {
    return stream != NULL && stream->pending_offset < stream->pending_length;
}

void dns_frame_stream_release_pending(struct dns_frame_stream *stream,
                                      dns_frame_pending_release_fn release,
                                      void *ctx) {
    if (stream == NULL || dns_frame_stream_has_pending(stream) ||
        stream->pending == NULL || release == NULL)
        return;
    release(ctx, stream);
    stream->pending = NULL;
    stream->pending_capacity = 0;
    stream->pending_offset = 0;
    stream->pending_length = 0;
    stream->pending_passthrough = 0;
}

uint32_t dns_frame_send_window(uint32_t acked, uint32_t local_seq,
                               uint16_t unconfirmed, uint32_t send_window) {
    uint32_t behind;
    if (acked <= local_seq)
        behind = local_seq - acked;
    else
        behind = 0x10000 + local_seq - acked;
    behind += ((uint32_t) unconfirmed + 1) * 40;
    return behind < send_window ? send_window - behind : 0;
}

size_t dns_frame_send_budget(uint32_t send_window, uint16_t mss) {
    if (mss == 0)
        return 0;
    return send_window < mss ? send_window : mss;
}

int dns_frame_should_read(int terminal, int pending, uint32_t send_window) {
    return !terminal && !pending && send_window != 0;
}

int dns_frame_should_flush_ack(int has_ack, int terminal, int pending) {
    return has_ack && (terminal || pending);
}

int dns_frame_should_resume_hup(int terminal, int pending,
                                uint32_t send_window) {
    return terminal == DNS_FRAME_TERMINAL_HUP_PENDING && !pending &&
           send_window != 0;
}

int dns_frame_hup_rearm_failure_state(int terminal) {
    return terminal == DNS_FRAME_TERMINAL_HUP_PENDING
           ? DNS_FRAME_TERMINAL_DRAIN
           : terminal;
}

int dns_frame_eof_terminal_state(int pending, int buffered, int forward_pending) {
    if (buffered || forward_pending)
        return pending ? DNS_FRAME_TERMINAL_DRAIN : DNS_FRAME_TERMINAL_NONE;
    return DNS_FRAME_TERMINAL_FIN_DRAIN;
}

int dns_frame_socket_terminal_event(int has_error, int has_hup, int has_read,
                                    int pending) {
    return has_error || (has_hup && (pending || !has_read));
}

int dns_frame_hup_pending_state(int has_error, int has_hup, int pending) {
    return !has_error && has_hup && pending
           ? DNS_FRAME_TERMINAL_HUP_PENDING
           : DNS_FRAME_TERMINAL_NONE;
}

int dns_frame_recheck_due(long long now, long long last_check, long long interval) {
    return interval <= 0 || (now >= last_check && now - last_check > interval);
}

int dns_frame_requires_recheck(uint32_t send_window) {
    return send_window == 0;
}

int dns_frame_flush_chunk_allowed(size_t chunks) {
    return chunks < DNS_FRAME_MAX_FLUSH_CHUNKS;
}

int dns_frame_terminal_timeout(int terminal, int close_timeout) {
    return terminal != DNS_FRAME_TERMINAL_NONE ? close_timeout : 0;
}

int dns_frame_terminal_next(int state, int pending, uint32_t acked,
                            uint32_t local_seq) {
    if (state == DNS_FRAME_TERMINAL_DRAIN && !pending)
        return acked == local_seq ? DNS_FRAME_TERMINAL_NONE
                                  : DNS_FRAME_TERMINAL_WAIT_ACK;
    if (state == DNS_FRAME_TERMINAL_WAIT_ACK && acked == local_seq)
        return DNS_FRAME_TERMINAL_NONE;
    return state;
}
