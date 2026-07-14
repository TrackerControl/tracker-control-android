/*
 * Copyright (c) 2011 and 2012, Dustin Lundquist <dustin@null-ptr.net>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * This is a minimal TLS implementation intended only to parse the server name
 * extension.  This was created based primarily on Wireshark dissection of a
 * TLS handshake and RFC4366.
 */

#include <stdio.h>
#include <stdlib.h> /* malloc() */
#include <string.h> /* strncpy() */
#include <sys/socket.h>

#include "tls.h"

#define TLS_HEADER_LEN 5
#define TLS_HANDSHAKE_CONTENT_TYPE 0x16
#define TLS_HANDSHAKE_TYPE_CLIENT_HELLO 0x01

#ifndef MIN
#define MIN(X, Y) ((X) < (Y) ? (X) : (Y))
#endif


/* Parse a TLS packet for the Server Name Indication extension in the client
 * hello handshake, writing the first server name found into *hostname (a
 * caller-provided buffer of at least FQDN_MAX + 1 bytes).
 *
 * Returns:
 *  >= 0                     - length of the hostname written to *hostname
 *  TLS_PARSE_INCOMPLETE(-1) - the TLS record is not fully present yet; the
 *                             caller may buffer more TCP payload and retry
 *  TLS_PARSE_NO_SNI  (-2)   - a complete ClientHello with no server name
 *  TLS_PARSE_INVALID (-5)   - not a TLS ClientHello handshake
 */

static int parse_server_name_extension(const char *data, size_t data_len,
                                       char *hostname)
{
    size_t pos = 2; /* skip server name list length */

    /*
     * Each entry is a 3-byte header (1 byte name type + 2 byte length)
     * followed by <length> bytes of name. Reading the header needs pos,
     * pos + 1 and pos + 2 in range, i.e. pos + 3 <= data_len. The original
     * "pos + 3 < data_len" dropped a name entry ending exactly at the buffer
     * boundary.
     */
    while (pos + 3 <= data_len) {
        size_t len = ((unsigned char)data[pos + 1] << 8) +
                     (unsigned char)data[pos + 2];

        if (pos + 3 + len > data_len)
            return TLS_PARSE_NO_SNI;

        switch (data[pos]) { /* name type */
            case 0x00: /* host_name */
                len = MIN(len, FQDN_MAX);
                strncpy(hostname, data + pos + 3, len);
                hostname[len] = '\0';
                return (int) len;
        }
        pos += 3 + len;
    }
    return TLS_PARSE_NO_SNI;
}

static int parse_extensions(const char *data, size_t data_len, char *hostname)
{
    size_t pos = 0;

    /* Parse each 4 bytes for the extension header */
    while (pos + 4 <= data_len) {
        /* Extension Length */
        size_t len = ((unsigned char)data[pos + 2] << 8) +
                     (unsigned char)data[pos + 3];

        /* Check if it's a server name extension */
        if (data[pos] == 0x00 && data[pos + 1] == 0x00) {
            /*
             * There can be only one extension of each type,
             * so we break our state and move p to beinnging
             * of the extension here
             */
            if (pos + 4 + len > data_len)
                return TLS_PARSE_NO_SNI;
            return parse_server_name_extension(data + pos + 4, len,
                                               hostname);
        }
        pos += 4 + len; /* Advance to the next extension header */
    }
    return TLS_PARSE_NO_SNI;
}

/*
 * Parse a TLS packet for the Server Name Indication extension in the client
 * hello handshake, writing the first server name found into *hostname.
 * See the block comment above for return values.
 */
int parse_tls_header(const char *data, size_t data_len, char *hostname)
{
    char tls_content_type;
    char tls_version_major;
    char tls_version_minor;
    size_t pos = TLS_HEADER_LEN;
    size_t len;

    /*
     * Check that our TCP payload is at least large enough for a
     * TLS header
     */
    if (data_len < TLS_HEADER_LEN)
        return TLS_PARSE_INCOMPLETE;

    /*
     * SSL 2.0 compatible Client Hello
     *
     * High bit of first byte (length) and content type is Client Hello
     *
     * See RFC5246 Appendix E.2
     */
    if (data[0] & 0x80 && data[2] == 1)
        return TLS_PARSE_INVALID;

    tls_content_type = data[0];
    if (tls_content_type != TLS_HANDSHAKE_CONTENT_TYPE)
        return TLS_PARSE_INVALID;

    tls_version_major = data[1];
    tls_version_minor = data[2];
    if (tls_version_major < 3)
        return TLS_PARSE_INVALID;

    /* Full TLS record length (5-byte header + payload) */
    len = ((unsigned char)data[3] << 8) +
          (unsigned char)data[4] + TLS_HEADER_LEN;

    /*
     * A ClientHello can be split across several TCP segments (large extension
     * sets, post-quantum key shares). If the whole record is not present yet,
     * ask the caller to buffer more rather than silently giving up — which
     * previously lost the SNI for good.
     */
    if (data_len < len)
        return TLS_PARSE_INCOMPLETE;
    data_len = len;

    /* Handshake */
    if (pos + 1 > data_len)
        return TLS_PARSE_NO_SNI;
    if (data[pos] != TLS_HANDSHAKE_TYPE_CLIENT_HELLO)
        return TLS_PARSE_INVALID;

    /*
     * Skip past fixed length records:
     * 1	Handshake Type
     * 3	Length
     * 2	Version (again)
     * 32	Random
     * to	Session ID Length
     */
    pos += 38;

    /* Session ID */
    if (pos + 1 > data_len)
        return TLS_PARSE_NO_SNI;
    len = (unsigned char)data[pos];
    pos += 1 + len;

    /* Cipher Suites */
    if (pos + 2 > data_len)
        return TLS_PARSE_NO_SNI;
    len = ((unsigned char)data[pos] << 8) + (unsigned char)data[pos + 1];
    pos += 2 + len;

    /* Compression Methods */
    if (pos + 1 > data_len)
        return TLS_PARSE_NO_SNI;
    len = (unsigned char)data[pos];
    pos += 1 + len;

    if (pos == data_len && tls_version_major == 3 &&
        tls_version_minor == 0)
        return TLS_PARSE_NO_SNI;

    /* Extensions */
    if (pos + 2 > data_len)
        return TLS_PARSE_NO_SNI;
    len = ((unsigned char)data[pos] << 8) + (unsigned char)data[pos + 1];
    pos += 2;

    if (pos + len > data_len)
        return TLS_PARSE_NO_SNI;

    return parse_extensions(data + pos, len, hostname);
}