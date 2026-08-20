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
 * Pure decision logic for DNS-over-TCP framing, extracted out of
 * check_tcp_socket() (tcp.c) so it can be unit-tested on the host without
 * pulling in JNI/session dependencies. This header and its implementation
 * (dns_frame.c) must only depend on libc: no netguard.h, no JNI.
 *
 * A single recv() on a DNS-over-TCP (port 53) socket may contain:
 *   - an isolated complete frame (2-byte length prefix + exactly one
 *     DNS message, with nothing left over);
 *   - a coalesced read (that frame plus additional bytes -- the start of
 *     the next frame, or more);
 *   - a split/partial frame (fewer bytes than the prefix declares).
 *
 * Policy: every frame's DNS payload is handed to the DNS parser so header
 * blanking/policy enforcement always applies, but only an isolated
 * complete frame may be shortened afterward (with its 2-byte length
 * prefix rewritten to match). Shortening a coalesced or split read would
 * discard bytes that still belong to the TCP stream.
 */

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct dns_frame_decision {
    /* Whether the DNS parser should be invoked at all for this recv(). */
    int should_parse;
    /* Whether this recv() held exactly one complete frame (frame_len + 2
     * == bytes). Only an isolated frame's prefix may be rewritten. */
    int isolated;
    /* The frame length taken verbatim from the 2-byte prefix. */
    size_t frame_len;
    /* The payload length to hand to the DNS parser: at most bytes - 2,
     * so a split/partial read is never over-read. */
    size_t dlen;
};

/*
 * Computes the framing decision for one recv() on a DNS-over-TCP socket.
 *
 * bytes: total bytes read by recv(); the caller guarantees bytes > 2
 *        (there is at least a full 2-byte length prefix plus one byte).
 * frame_len: the 16-bit frame length taken from the first two bytes of
 *        the buffer (buffer[0] << 8 | buffer[1]), before this call.
 */
struct dns_frame_decision dns_frame_decide(size_t bytes, size_t frame_len);

/*
 * Applies the post-parse rewrite to buffer, if any. buffer must point at
 * the full recv() buffer, including the 2-byte length prefix, and must be
 * at least *bytes long. post_parse_dlen is the (possibly shrunk) payload
 * length the DNS parser reported. *bytes is updated in place when a
 * rewrite happens; otherwise it is left untouched.
 *
 * No-op unless decision->isolated and post_parse_dlen differs from
 * decision->frame_len -- matching the original inline check.
 */
void dns_frame_apply_rewrite(uint8_t *buffer,
                              const struct dns_frame_decision *decision,
                              size_t post_parse_dlen,
                              ssize_t *bytes);

#ifdef __cplusplus
}
#endif

#endif /* DNS_FRAME_H */
