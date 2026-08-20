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

struct dns_frame_decision dns_frame_decide(size_t bytes, size_t frame_len) {
    struct dns_frame_decision d;
    d.frame_len = frame_len;

    if (frame_len == 0) {
        // A zero-length declared frame is not something the parser can
        // act on; skip it entirely, matching the original "if (frame_len
        // > 0)" guard.
        d.should_parse = 0;
        d.isolated = 0;
        d.dlen = 0;
        return d;
    }

    d.should_parse = 1;
    d.isolated = (frame_len + 2 == bytes) ? 1 : 0;

    // avail is the payload available in this recv() after the 2-byte
    // prefix. The caller guarantees bytes > 2, so avail >= 1.
    size_t avail = bytes - 2;
    // isolated implies frame_len == avail.
    d.dlen = (frame_len < avail) ? frame_len : avail;

    return d;
}

void dns_frame_apply_rewrite(uint8_t *buffer,
                              const struct dns_frame_decision *decision,
                              size_t post_parse_dlen,
                              ssize_t *bytes) {
    if (decision->isolated && post_parse_dlen != decision->frame_len) {
        buffer[0] = (uint8_t) (post_parse_dlen >> 8);
        buffer[1] = (uint8_t) post_parse_dlen;
        *bytes = (ssize_t) post_parse_dlen + 2;
    }
}
