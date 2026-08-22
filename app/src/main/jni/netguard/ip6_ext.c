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

#include "ip6_ext.h"

#include <netinet/in.h>

/*
 * Extension header "Next Header" values that are not IPPROTO_* upper-layer
 * protocols and cannot be walked further, but that this engine still wants
 * to name accurately in logs/decisions rather than lump in with a generic
 * "unknown". Not all libc's define every one of these (IPPROTO_NONE and
 * IPPROTO_DSTOPTS in particular vary), so they are given literal values
 * straight from the IANA protocol-numbers registry rather than relying on
 * <netinet/in.h> to supply them.
 */
#define IP6_EXT_HOPOPTS  0
#define IP6_EXT_ROUTING  43
#define IP6_EXT_FRAGMENT 44
#define IP6_EXT_ESP      50
#define IP6_EXT_AH       51
#define IP6_EXT_DSTOPTS  60
#define IP6_EXT_NONE     59 /* "No Next Header" */

/*
 * Defensive cap on the number of extension headers walked for a single
 * packet. Legitimate chains are one or two headers long; this exists only
 * to bound a maliciously (or corruptly) long chain, not to accommodate
 * real traffic -- the `length` bound below already makes the loop
 * terminate, but a packet can still carry many minimum-size (8-byte)
 * extension headers, so an explicit cap keeps the walk cheap regardless.
 */
#define MAX_IP6_EXT_HEADERS 8

static int is_upper_layer_protocol(uint8_t protocol) {
    return protocol == IPPROTO_TCP ||
           protocol == IPPROTO_UDP ||
           protocol == IPPROTO_ICMP ||
           protocol == IPPROTO_ICMPV6;
}

int ip6_skip_ext_headers(const uint8_t *pkt, size_t length,
                          uint8_t *protocol_out, size_t *payload_off_out) {
    if (pkt == NULL || protocol_out == NULL || payload_off_out == NULL)
        return 0;

    if (length < IP6_EXT_FIXED_HDR_LEN) {
        /* Defensive only: handle_ip() already rejects a packet shorter than
         * the fixed IPv6 header before ever calling this. Report a value
         * that can never be mistaken for TCP/UDP/ICMP and never point
         * payload_off_out past the buffer. */
        *protocol_out = IP6_EXT_NONE;
        *payload_off_out = length;
        return 0;
    }

    /* The fixed IPv6 header's Next Header field is byte 6 (after the 4-byte
     * Version/Traffic Class/Flow Label and the 2-byte Payload Length). */
    uint8_t next = pkt[6];
    size_t off = IP6_EXT_FIXED_HDR_LEN;

    for (int hops = 0; ; hops++) {
        if (is_upper_layer_protocol(next)) {
            *protocol_out = next;
            *payload_off_out = off;
            return 1;
        }

        /* Headers that cannot be walked at all: report and stop right where
         * we are, without trying to read a length field that (for Fragment)
         * does not mean what it would for the walkable headers, or (for
         * ESP/No-Next-Header/anything unrecognised) simply is not there to
         * find plaintext structure behind. */
        if (next == IP6_EXT_FRAGMENT || next == IP6_EXT_ESP || next == IP6_EXT_NONE ||
            !(next == IP6_EXT_HOPOPTS || next == IP6_EXT_ROUTING ||
              next == IP6_EXT_DSTOPTS || next == IP6_EXT_AH)) {
            *protocol_out = next;
            *payload_off_out = off;
            return 0;
        }

        if (hops >= MAX_IP6_EXT_HEADERS) {
            /* Chain too long -- bail out where we are rather than keep
             * walking an attacker-controlled sequence of headers. */
            *protocol_out = next;
            *payload_off_out = off;
            return 0;
        }

        /* Every walkable header (Hop-by-Hop, Routing, Destination Options,
         * AH) starts with a 1-byte Next Header and a 1-byte length field;
         * need both in bounds before reading either. */
        if (off + 2 > length) {
            *protocol_out = next;
            *payload_off_out = off;
            return 0;
        }

        uint8_t hdr_next = pkt[off];
        uint8_t hdr_len = pkt[off + 1];

        /* AH's length is in 4-octet units, not including the first 8
         * octets, per RFC 4302 section 2.2: (Hdr Ext Len + 2) * 4. Every
         * other walkable header uses RFC 8200's 8-octet units, not
         * including the first 8 octets: 8 * (Hdr Ext Len + 1). */
        size_t advance = (next == IP6_EXT_AH)
                                 ? ((size_t) hdr_len + 2) * 4
                                 : 8 * ((size_t) hdr_len + 1);

        if (advance > length || off > length - advance) {
            /* Declared length runs past the end of the buffer. */
            *protocol_out = next;
            *payload_off_out = off;
            return 0;
        }

        off += advance;
        next = hdr_next;
    }
}
