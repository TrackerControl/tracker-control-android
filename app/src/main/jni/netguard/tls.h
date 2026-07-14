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
#ifndef TLS_H
#define TLS_H

#include <stdint.h>
#include <stddef.h>

/* Maximum length of a fully-qualified domain name (server name). */
#define FQDN_MAX 255

/* parse_tls_header() return codes (see tls.c for details) */
#define TLS_PARSE_INCOMPLETE  (-1)  /* TLS record not fully received; buffer more and retry */
#define TLS_PARSE_NO_SNI      (-2)  /* complete ClientHello, but no server name present */
#define TLS_PARSE_INVALID     (-5)  /* not a TLS ClientHello handshake */

/* Returns the hostname length (>= 0) on success and writes it into *hostname
 * (caller buffer of at least FQDN_MAX + 1 == 256 bytes), or one of the
 * TLS_PARSE_* codes above. */
int parse_tls_header(const char *data, size_t data_len, char *hostname);

#endif //TLS_H
