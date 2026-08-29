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

#include <string.h>

#include "dns_frame.h"

size_t dns_frame_process_stream(uint8_t *buffer, size_t bytes,
                                struct dns_stream_state *state,
                                dns_frame_parse_fn parse, void *ctx) {
    // Defensive: nothing sensible to do without a buffer, state or parser.
    if (buffer == NULL || state == NULL || parse == NULL)
        return bytes;

    size_t cursor = 0;  // bytes of the buffer already accounted for
    size_t end = bytes; // bytes to forward; only ever shrinks, never below cursor

    // 1. Continuation of a frame whose earlier bytes were already forwarded in
    //    a previous recv(). Those bytes are neither parsed (their DNS header is
    //    gone) nor rewritten (their prefix is already on the wire).
    if (state->frame_remaining > 0) {
        size_t remaining = end - cursor;
        size_t skip = (state->frame_remaining < remaining
                       ? (size_t) state->frame_remaining : remaining);
        if (state->blank_remaining != 0 && skip > 0)
            memset(buffer + cursor, 0, skip);
        state->frame_remaining -= (uint32_t) skip;
        cursor += skip;
        if (state->frame_remaining == 0)
            state->blank_remaining = 0;
        if (cursor >= end)
            return end;
    }

    // 2. A length prefix split across recv()s: the high byte was stashed (and
    //    forwarded) last time, its low byte is the first byte here. This
    //    frame's prefix is already on the wire, so it is parse-only: the
    //    payload may be mutated in place, but its length can never change.
    if (state->have_prefix_hi && cursor < end) {
        size_t frame_len = ((size_t) state->prefix_hi << 8) | buffer[cursor];
        cursor++;
        state->prefix_hi = 0;
        state->have_prefix_hi = 0;

        size_t avail = end - cursor;
        if (frame_len > avail) {
            int blank_rest = 0;
            if (avail > 0)
                (void) parse(ctx, buffer + cursor, avail, 1, &blank_rest);
            state->frame_remaining = (uint32_t) (frame_len - avail);
            state->blank_remaining = (uint8_t) (blank_rest != 0);
            return end;
        }
        if (frame_len > 0) {
            int blank_rest = 0;
            (void) parse(ctx, buffer + cursor, frame_len, 1, &blank_rest); // shrink ignored
            cursor += frame_len;
        }
    }

    // 3. Frames whose 2-byte prefix starts inside this buffer.
    while (cursor < end) {
        size_t remaining = end - cursor;

        // A lone trailing byte is the high half of the next frame's length
        // prefix. It cannot be withheld (that would need buffering), so it is
        // forwarded as-is and stashed for the next call.
        if (remaining == 1) {
            state->prefix_hi = buffer[cursor];
            state->have_prefix_hi = 1;
            return end;
        }

        size_t frame_len = ((size_t) buffer[cursor] << 8) | buffer[cursor + 1];
        cursor += 2;

        // A zero-length frame is a legal no-op; its two bytes forward as-is.
        if (frame_len == 0)
            continue;

        size_t avail = end - cursor;

        if (frame_len > avail) {
            // Frame runs past this read: parse what is visible (blanking only,
            // any shrink is ignored) and remember the overflow.
            int blank_rest = 0;
            if (avail > 0)
                (void) parse(ctx, buffer + cursor, avail, 1, &blank_rest);
            state->frame_remaining = (uint32_t) (frame_len - avail);
            state->blank_remaining = (uint8_t) (blank_rest != 0);
            return end;
        }

        // Complete frame, prefix and payload both inside this buffer: nothing
        // here has been forwarded yet, so it may be shortened.
        int blank_rest = 0;
        size_t new_dlen = parse(ctx, buffer + cursor, frame_len, 0, &blank_rest);
        if (new_dlen > frame_len)
            new_dlen = frame_len; // defensive: a parser must never grow a frame

        if (new_dlen < frame_len) {
            buffer[cursor - 2] = (uint8_t) (new_dlen >> 8);
            buffer[cursor - 1] = (uint8_t) new_dlen;

            size_t tail_start = cursor + frame_len; // <= end
            size_t tail_len = end - tail_start;
            if (tail_len > 0)
                memmove(buffer + cursor + new_dlen, buffer + tail_start, tail_len);

            end -= (frame_len - new_dlen);
        }

        cursor += new_dlen; // cursor <= end still holds
    }

    return end;
}
