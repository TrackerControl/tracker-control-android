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
 * Bufferless cursor over a DNS-over-TCP (port 53) byte stream, extracted out
 * of check_tcp_socket() (tcp.c) so it can be unit-tested on the host without
 * pulling in JNI/session dependencies. This header and its implementation
 * (dns_frame.c) must only depend on libc: no netguard.h, no JNI.
 *
 * DNS over TCP frames each message as a 2-byte big-endian length prefix
 * followed by that many payload bytes. A single recv() is not aligned to that
 * framing, so one read may hold:
 *   - exactly one complete frame;
 *   - several coalesced frames, possibly with a trailing part of the next;
 *   - the continuation of a frame whose prefix arrived in an earlier read;
 *   - a lone byte that is the first half of a length prefix.
 *
 * dns_frame_process_stream() walks every frame boundary inside one recv()
 * buffer and carries enough state to stay aligned into the next call without
 * buffering stream bytes. A frame is offered to the DNS parser only in the
 * recv() where its prefix is completed. If its payload continues into later
 * reads, only the bytes visible in that first call are parsed; continuation
 * bytes are skipped because they no longer include the DNS header. Policy
 * enforcement for such split frames therefore remains best-effort.
 *
 * Length rewrites (a blocking hit that shortens a DNS message) are applied
 * only to a frame whose 2-byte prefix *and* whole payload lie inside the
 * current buffer: nothing in this buffer has been forwarded to the tun yet, so
 * shrinking such a frame is sequence-safe, and the frames behind it are
 * memmove()d down. A frame whose prefix or payload carries over from an earlier
 * recv() can never be shortened because bytes committed earlier cannot be
 * taken back. When the prefix alone was split, its payload may still be parsed
 * and mutated in place; when the payload was split, later continuation bytes
 * are forwarded untouched.
 */

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Per-connection carry-over between recv()s. Zero-initialise it when the TCP
 * session is created; all-zero means "next byte starts a length prefix".
 */
struct dns_stream_state {
    uint32_t frame_remaining; /* payload bytes of the current frame whose prefix
                                 was seen in an earlier recv() and whose earlier
                                 bytes were already forwarded */
    uint8_t prefix_hi;        /* stashed first byte of a length prefix split
                                 across recv()s */
    uint8_t have_prefix_hi;   /* nonzero when prefix_hi is valid */
};

/*
 * Called for each DNS payload (or the visible part of one) found in the
 * buffer; stands in for parse_dns_response(). Returns the possibly-shrunk
 * payload length; a return > dlen must be treated by the caller as
 * "unchanged" (defensive clamp).
 */
typedef size_t (*dns_frame_parse_fn)(void *ctx, uint8_t *data, size_t dlen);

/*
 * Processes one recv() buffer of a DNS-over-TCP stream in place.
 *
 * buffer: the bytes just read, none of which have been forwarded yet.
 * bytes: how many bytes buffer holds.
 * state: carry-over from the previous call on the same connection; updated to
 *        describe exactly the bytes this call reports as forwarded.
 * parse/ctx: the DNS parser and its opaque context.
 *
 * Returns the total byte count to forward, <= bytes (smaller when a frame that
 * lay wholly inside this buffer was shortened).
 */
size_t dns_frame_process_stream(uint8_t *buffer, size_t bytes,
                                struct dns_stream_state *state,
                                dns_frame_parse_fn parse, void *ctx);

#ifdef __cplusplus
}
#endif

#endif /* DNS_FRAME_H */
