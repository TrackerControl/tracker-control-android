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

#include "netguard.h"

struct tcdns_ctx {
    const struct arguments *args;
    const struct ng_session *s;
};

static void tcdns_record_answer(void *opaque, const char *qname, const char *aname,
                                const char *resource, int32_t ttl) {
    const struct tcdns_ctx *ctx = (const struct tcdns_ctx *) opaque;
    dns_resolved(ctx->args, qname, aname, resource, ttl);
}

static int tcdns_is_domain_blocked(void *opaque, const char *qname) {
    const struct tcdns_ctx *ctx = (const struct tcdns_ctx *) opaque;
    return is_domain_blocked(ctx->args, qname) != 0;
}

static uint8_t tcdns_blocked_rcode(void *opaque) {
    const struct tcdns_ctx *ctx = (const struct tcdns_ctx *) opaque;
    return (uint8_t) ctx->args->rcode;
}

static void tcdns_on_blanked(void *opaque, const char *qname,
                             uint16_t qtype, uint8_t rcode) {
    const struct tcdns_ctx *ctx = (const struct tcdns_ctx *) opaque;
    const struct arguments *args = ctx->args;
    const struct ng_session *s = ctx->s;

    int version;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    uint16_t sport;
    uint16_t dport;

    if (s->protocol == IPPROTO_UDP) {
        version = s->udp.version;
        sport = ntohs(s->udp.source);
        dport = ntohs(s->udp.dest);
        if (s->udp.version == 4) {
            inet_ntop(AF_INET, &s->udp.saddr.ip4, source, sizeof(source));
            inet_ntop(AF_INET, &s->udp.daddr.ip4, dest, sizeof(dest));
        } else {
            inet_ntop(AF_INET6, &s->udp.saddr.ip6, source, sizeof(source));
            inet_ntop(AF_INET6, &s->udp.daddr.ip6, dest, sizeof(dest));
        }
    } else {
        version = s->tcp.version;
        sport = ntohs(s->tcp.source);
        dport = ntohs(s->tcp.dest);
        if (s->tcp.version == 4) {
            inet_ntop(AF_INET, &s->tcp.saddr.ip4, source, sizeof(source));
            inet_ntop(AF_INET, &s->tcp.daddr.ip4, dest, sizeof(dest));
        } else {
            inet_ntop(AF_INET6, &s->tcp.saddr.ip6, source, sizeof(source));
            inet_ntop(AF_INET6, &s->tcp.daddr.ip6, dest, sizeof(dest));
        }
    }

    char name[DNS_QNAME_MAX + 40 + 1];
    (void) snprintf(name, sizeof(name), "qtype %u qname %s rcode %u",
                    qtype, qname, rcode);
    jobject objPacket = create_packet(
            args, version, s->protocol, "",
            source, sport, dest, dport,
            name, 0, 0);
    log_packet(args, objPacket);
}

static const tcdns_callbacks tcdns_callbacks_template = {
    .abi_version = TCDNS_ABI_VERSION,
    .record_answer = tcdns_record_answer,
    .is_domain_blocked = tcdns_is_domain_blocked,
    .blocked_rcode = tcdns_blocked_rcode,
    .on_blanked = tcdns_on_blanked,
    .log = NULL,
};

#define TCDNS_CALLBACKS_INIT tcdns_callbacks_template

void parse_dns_response(const struct arguments *args, const struct ng_session *s,
                        uint8_t *data, size_t *datalen) {
    struct tcdns_ctx ctx = { .args = args, .s = s };
    tcdns_callbacks cb = TCDNS_CALLBACKS_INIT;
    size_t new_len = tcdns_process_response(data, *datalen, &cb, &ctx);
    if (new_len != TCDNS_UNCHANGED)
        *datalen = new_len;
}

void parse_dns_partial_response(const struct arguments *args, const struct ng_session *s,
                                uint8_t *data, size_t *datalen, int *blanked) {
    struct tcdns_ctx ctx = { .args = args, .s = s };
    tcdns_callbacks cb = TCDNS_CALLBACKS_INIT;
    *blanked = 0;
    size_t result = tcdns_process_partial_response(data, *datalen, &cb, &ctx);
    if (result != TCDNS_UNCHANGED)
        *blanked = 1;
}
