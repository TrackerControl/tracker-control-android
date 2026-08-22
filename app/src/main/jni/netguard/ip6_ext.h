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

#ifndef IP6_EXT_H
#define IP6_EXT_H

/*
 * Pure IPv6 extension header walk, extracted out of handle_ip() (ip.c) so
 * it can be unit-tested on the host without pulling in JNI/Android
 * dependencies. This header and its implementation (ip6_ext.c) depend only
 * on libc (<netinet/in.h> for the IPPROTO_* constants) -- no netguard.h,
 * no JNI, no struct ip6_hdr/ip6_ext from <netinet/ip6.h>, so the fixed
 * header size below is duplicated rather than taken from sizeof(struct
 * ip6_hdr) (the two must and do agree: RFC 8200 defines the fixed IPv6
 * header as exactly 40 bytes, with no options).
 *
 * See https://www.rfc-editor.org/rfc/rfc8200 (section 4) and
 * https://www.iana.org/assignments/protocol-numbers/protocol-numbers.xhtml
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Fixed IPv6 header length in bytes (RFC 8200 section 3). */
#define IP6_EXT_FIXED_HDR_LEN 40

/*
 * Walks the IPv6 extension header chain that starts right after the fixed
 * 40-byte IPv6 header, looking for an upper-layer protocol this engine can
 * actually parse (TCP, UDP, ICMP, ICMPv6).
 *
 * pkt/length: the full IP packet buffer, exactly as received from the
 *        tun device. length must be >= IP6_EXT_FIXED_HDR_LEN (the caller
 *        already rejects shorter packets before calling this).
 *
 * On return, *protocol_out and *payload_off_out are always set, and
 * *payload_off_out is always a valid offset into pkt (<= length):
 *
 *   - Return 1: an upper-layer protocol was found. *protocol_out is that
 *     protocol (TCP/UDP/ICMP/ICMPv6) and *payload_off_out is the offset of
 *     its header -- exactly what the pre-extraction code intended to
 *     compute.
 *
 *   - Return 0: no upper-layer protocol was found before the walk had to
 *     stop. *protocol_out is the extension-header type (or other next-
 *     header value) the walk stopped on, and *payload_off_out is the
 *     offset of that header (i.e. right after everything successfully
 *     walked). *protocol_out in this case is guaranteed not to collide
 *     with TCP/UDP/ICMP/ICMPv6, so a caller that only special-cases those
 *     four values downstream (as handle_ip does) treats a stopped walk
 *     exactly like an unparseable/unknown protocol -- it is never
 *     misread as a real transport header. Reasons to stop:
 *       - No Next Header (59): a legitimate, clean end of the chain.
 *       - Fragment (44): ip6e_len is a reserved field for this header,
 *         not a length, so it cannot be walked; the packet may also not
 *         be first-fragment, so there may be no upper-layer header here
 *         at all.
 *       - ESP (50): the payload is encrypted; nothing after it is
 *         parseable in plaintext.
 *       - An unrecognised/non-walkable next-header value.
 *       - The declared header length would run past `length`, or there
 *         are not even enough bytes left to read the 2-byte extension
 *         header itself (truncated packet).
 *       - MAX_IP6_EXT_HEADERS extension headers were walked without
 *         reaching an upper-layer protocol (a defensively small cap
 *         against a maliciously long or looping chain).
 *
 * Hop-by-Hop (0), Routing (43) and Destination Options (60) are walked
 * using the standard 8-octet-unit encoding: 8 * (Hdr Ext Len + 1) bytes.
 * Authentication Header (51) is walked using its own 4-octet-unit
 * encoding: (Hdr Ext Len + 2) * 4 bytes (RFC 4302 section 2.2) -- AH only
 * authenticates, it does not encrypt, so the headers after it (including
 * the real transport header, in AH transport mode) remain in plaintext
 * and are worth walking into.
 */
int ip6_skip_ext_headers(const uint8_t *pkt, size_t length,
                          uint8_t *protocol_out, size_t *payload_off_out);

#ifdef __cplusplus
}
#endif

#endif /* IP6_EXT_H */
