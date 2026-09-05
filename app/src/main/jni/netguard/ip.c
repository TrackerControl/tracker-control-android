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
#include "tls.h"
#include "ip6_ext.h"
#include "wg_flow_cache.h"
#include <stdatomic.h>

int max_tun_msg = 0;
extern int loglevel;
extern FILE *pcap_file;
extern _Atomic int wg_required;

static atomic_long wg_drop_count = 0;
static atomic_long wg_gap_drop_count = 0;
static atomic_long undispatchable_drop_count = 0;
static atomic_long undispatchable_log_count = 0;

// Skip tunneling for addresses WireGuard cannot meaningfully forward
// (multicast, link-local, loopback). Apps targeting these wouldn't gain
// privacy from WG and the peer would likely drop them anyway.
static int is_local_dest(int version, const void *daddr) {
    if (version == 4) {
        const uint8_t *b = (const uint8_t *) daddr;
        if (b[0] == 127) return 1;                   // 127.0.0.0/8 loopback
        if (b[0] == 169 && b[1] == 254) return 1;    // 169.254.0.0/16 link-local
        if (b[0] >= 224) return 1;                   // 224.0.0.0/4 multicast + reserved
        return 0;
    } else {
        const uint8_t *b = (const uint8_t *) daddr;
        // ::1
        int is_loopback = 1;
        for (int i = 0; i < 15; i++) if (b[i] != 0) { is_loopback = 0; break; }
        if (is_loopback && b[15] == 1) return 1;
        // fe80::/10 link-local
        if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return 1;
        // ff00::/8 multicast
        if (b[0] == 0xff) return 1;
        return 0;
    }
}

// The UID of an established flow, for packets that arrive without one.
//
// Packet 2+ of a direct flow reaches the routing fork with uid == -1: the
// expensive lookup above is deliberately skipped for existing UDP sessions and
// non-SYN TCP. The uid cache usually answers, but it expires and is evicted,
// and defaulting on a miss would send the rest of an established, already-NATted
// flow into the tunnel mid-stream. The session table is the authoritative and
// free source, so consult it before giving up.
//
// This runs only behind a route_flow_lookup miss, so it is the second fallback
// rather than the per-packet cost it once was. The UDP arm repeats the 5-tuple
// match in udp.c's get_udp_session_state; keep the two in step.
static jint get_session_uid(const struct arguments *args, int version, int protocol,
                            const uint8_t *pkt, const uint8_t *payload) {
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;

    for (struct ng_session *cur = args->ctx->ng_session; cur != NULL; cur = cur->next) {
        if (cur->protocol != protocol)
            continue;

        if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) {
            const struct icmp *icmp = (struct icmp *) payload;
            if (cur->icmp.version == version && cur->icmp.id == icmp->icmp_id &&
                (version == 4
                 ? cur->icmp.saddr.ip4 == ip4->saddr && cur->icmp.daddr.ip4 == ip4->daddr
                 : memcmp(&cur->icmp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                   memcmp(&cur->icmp.daddr.ip6, &ip6->ip6_dst, 16) == 0))
                return cur->icmp.uid;
            continue;
        }

        if (protocol == IPPROTO_UDP) {
            const struct udphdr *udphdr = (struct udphdr *) payload;
            if (cur->udp.version == version &&
                cur->udp.source == udphdr->source && cur->udp.dest == udphdr->dest &&
                (version == 4
                 ? cur->udp.saddr.ip4 == ip4->saddr && cur->udp.daddr.ip4 == ip4->daddr
                 : memcmp(&cur->udp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                   memcmp(&cur->udp.daddr.ip6, &ip6->ip6_dst, 16) == 0))
                return cur->udp.uid;
        } else if (protocol == IPPROTO_TCP) {
            const struct tcphdr *tcphdr = (struct tcphdr *) payload;
            if (cur->tcp.version == version &&
                cur->tcp.source == tcphdr->source && cur->tcp.dest == tcphdr->dest &&
                (version == 4
                 ? cur->tcp.saddr.ip4 == ip4->saddr && cur->tcp.daddr.ip4 == ip4->daddr
                 : memcmp(&cur->tcp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                   memcmp(&cur->tcp.daddr.ip6, &ip6->ip6_dst, 16) == 0))
                return cur->tcp.uid;
        }
    }

    return -1;
}

// Tunnelled flows return from the WireGuard write before handle_tcp() creates
// an ng_session. Direct flows do create one, and must keep their established
// TCP shortcut even when the route cache generation changes.
static int has_tcp_session(const struct arguments *args, int version,
                           const uint8_t *pkt, const uint8_t *payload) {
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct tcphdr *tcphdr = (struct tcphdr *) payload;

    for (const struct ng_session *cur = args->ctx->ng_session;
         cur != NULL; cur = cur->next) {
        if (cur->protocol != IPPROTO_TCP || cur->tcp.version != version ||
            cur->tcp.source != tcphdr->source || cur->tcp.dest != tcphdr->dest)
            continue;
        if (version == 4) {
            if (cur->tcp.saddr.ip4 == ip4->saddr && cur->tcp.daddr.ip4 == ip4->daddr)
                return 1;
        } else if (memcmp(&cur->tcp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                   memcmp(&cur->tcp.daddr.ip6, &ip6->ip6_dst, 16) == 0)
            return 1;
    }
    return 0;
}

// Send a reset to the app for an established tunnelled flow whose policy was
// changed while it was in flight. The observed packet is outbound, so
// write_tcp()'s address/port reversal builds the reset in the opposite
// direction. A reset carrying the observed ACK is valid for an ACK-bearing
// segment; otherwise acknowledge the segment's sequence space.
static void write_stateless_tcp_reset(const struct arguments *args, int version,
                                      const uint8_t *pkt, const uint8_t *payload,
                                      size_t length) {
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct tcphdr *tcphdr = (struct tcphdr *) payload;
    struct tcp_session rst;
    memset(&rst, 0, sizeof(rst));
    rst.version = version;
    rst.source = tcphdr->source;
    rst.dest = tcphdr->dest;
    if (version == 4) {
        rst.saddr.ip4 = (__be32) ip4->saddr;
        rst.daddr.ip4 = (__be32) ip4->daddr;
    } else {
        memcpy(&rst.saddr.ip6, &ip6->ip6_src, 16);
        memcpy(&rst.daddr.ip6, &ip6->ip6_dst, 16);
    }

    size_t tcp_length = length - (size_t) (payload - pkt);
    size_t tcp_header_length = sizeof(struct tcphdr);
    if (tcphdr->doff >= 5 && (size_t) tcphdr->doff * 4 <= tcp_length)
        tcp_header_length = (size_t) tcphdr->doff * 4;
    size_t datalen = tcp_length - tcp_header_length;

    uint32_t reset_seq;
    uint32_t reset_ack;
    int reset_has_ack;
    tcp_stateless_reset_fields(ntohl(tcphdr->seq), ntohl(tcphdr->ack_seq),
                               (uint32_t) datalen, tcphdr->syn, tcphdr->fin,
                               tcphdr->ack, &reset_seq, &reset_ack,
                               &reset_has_ack);
    rst.local_seq = reset_seq;
    rst.remote_seq = reset_ack;
    if (write_tcp(args, &rst, NULL, 0, 0, reset_has_ack, 0, 1) < 0)
        log_android(ANDROID_LOG_WARN, "TCP stateless reset write failed");
}

// Re-run the authoritative UID lookup only after both the flow cache and the
// native session table miss. This is deliberately off the hot path: established
// UDP/TCP packets normally hit the flow cache, while a miss can be caused by a
// cache expiry or hash collision. Keep the Android <=28 procfs path identical
// to the initial lookup and use ConnectivityManager on newer releases.
static jint get_route_uid(const struct arguments *args, int version, int protocol,
                          const void *saddr, uint16_t sport,
                          const void *daddr, uint16_t dport,
                          const char *source, const char *dest) {
    if (args->ctx->sdk <= 28)
        return get_uid(version, protocol, saddr, sport, daddr, dport);
    return get_uid_q(args, version, protocol, source, sport, dest, dport);
}

static jint resolve_flow_owner(const struct arguments *args, int version, int protocol,
                               const void *saddr, uint16_t sport,
                               const void *daddr, uint16_t dport,
                               const char *source, const char *dest,
                               const uint8_t *pkt, const uint8_t *payload) {
    jint route_uid = get_session_uid(args, version, protocol, pkt, payload);
    if (route_uid < 0)
        route_uid = get_route_uid(args, version, protocol,
                                  saddr, sport, daddr, dport, source, dest);
    return route_uid;
}

// Whether a flow's owning app should be routed through the tunnel. Shared by
// handle_ip's routing fork and, for SNI research mode, by the earlier check
// that decides whether a 443 flow will ever get an ng_session to reassemble
// a ClientHello into (a tunnelled flow never does: the WireGuard write below
// returns before handle_tcp creates one). uid is the already-known UID for
// this packet, or -1 when the caller has not resolved one yet. out_uid, when
// not NULL, is filled with any UID this call resolves from the session table
// or the authoritative lookup, so a caller that still needs a UID afterwards
// (SNI research mode attributing a flow that just lost its exemption) can
// reuse it instead of paying for the same lookup again.
static int resolve_tunnel_uid(const struct arguments *args, int version, uint8_t protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport,
                              const char *source, const char *dest,
                              const uint8_t *pkt, const uint8_t *payload,
                              jint uid, jint *out_uid) {
    int tunnel_uid;
    if (route_uid_relevant()) {
        // A flow keeps the verdict its first packet was given. A fresh UID
        // is authoritative even when a previous flow happened to reuse the
        // same 5-tuple, so do not consult the flow cache in that case.
        jint route_uid = uid;
        int cached_uid_known = 0;
        if (route_uid >= 0) {
            tunnel_uid = is_tunnel_uid(route_uid);
            route_flow_store(version, protocol, saddr, sport, daddr, dport,
                             tunnel_uid, 1);
        } else if (route_flow_lookup(version, protocol,
                                     saddr, sport, daddr, dport,
                                     &tunnel_uid, &cached_uid_known) &&
                   cached_uid_known) {
            // Established tunnelled flows never create an ng_session — the
            // WireGuard write below returns first — so the cache preserves
            // their first-packet answer without a per-packet UID lookup.
        } else {
            // A cache expiry, collision, or unresolved-owner entry must retry
            // ownership. Reusing an unresolved entry would pin the
            // unknown-system policy and per-app route for a busy flow.
            route_uid = resolve_flow_owner(args, version, protocol,
                                           saddr, sport, daddr, dport,
                                           source, dest, pkt, payload);

            if (route_uid >= 0) {
                tunnel_uid = is_tunnel_uid(route_uid);
                route_flow_store(version, protocol, saddr, sport, daddr, dport,
                                 tunnel_uid, 1);
                if (out_uid != NULL)
                    *out_uid = route_uid;
            } else {
                // Unknown ownership is privacy-sensitive: keep this packet in
                // the remote tunnel rather than fail-open to direct routing.
                // Mark the entry unstable so the next packet retries ownership
                // and policy.
                tunnel_uid = 1;
                route_flow_store(version, protocol, saddr, sport, daddr, dport,
                                 tunnel_uid, 0);
                log_android(ANDROID_LOG_WARN,
                            "Route UID unavailable for v%d p%d %s/%u > %s/%u; tunnelling",
                            version, protocol, source, sport, dest, dport);
            }
        }
    } else {
        // With no per-app override every UID has the same route. Still retain
        // that stable answer per flow: tunnelled UDP has no native session to
        // carry it into packet two, and the cache avoids resolving the UID on
        // every datagram. If a caller needs the owner for a fresh block
        // decision, resolve it once here without changing the global route.
        tunnel_uid = route_default_is_tunnel();
        jint resolved_uid = -1;
        if (out_uid != NULL && uid < 0) {
            resolved_uid = resolve_flow_owner(args, version, protocol,
                                              saddr, sport, daddr, dport,
                                              source, dest, pkt, payload);
            if (resolved_uid >= 0)
                *out_uid = resolved_uid;
        }
        // uid_known is also the stable-policy bit for the UDP fast path. An
        // unresolved owner must remain retryable even though the global route
        // itself is independent of UID.
        route_flow_store(version, protocol, saddr, sport, daddr, dport,
                         tunnel_uid, uid >= 0 || resolved_uid >= 0);
    }
    return tunnel_uid;
}

uint16_t get_mtu() {
    return 10000;
}

uint16_t get_default_mss(int version) {
    if (version == 4)
        return (uint16_t) (get_mtu() - sizeof(struct iphdr) - sizeof(struct tcphdr));
    else
        return (uint16_t) (get_mtu() - sizeof(struct ip6_hdr) - sizeof(struct tcphdr));
}

int check_tun(const struct arguments *args,
              const struct epoll_event *ev,
              const int epoll_fd,
              int sessions, int maxsessions) {
    // Check tun error
    if (ev->events & EPOLLERR) {
        log_android(ANDROID_LOG_ERROR, "tun %d exception", args->tun);
        if (fcntl(args->tun, F_GETFL) < 0) {
            int error = errno;
            log_android(ANDROID_LOG_ERROR, "fcntl tun %d F_GETFL error %d: %s",
                        args->tun, error, strerror(error));
            report_exit(args, error, "fcntl tun %d F_GETFL error %d: %s",
                        args->tun, error, strerror(error));
        } else
            report_exit(args, 0, "tun %d exception", args->tun);
        return -1;
    }

    // Check tun read
    if (ev->events & EPOLLIN) {
        uint8_t *buffer = ng_malloc(get_mtu(), "tun read");
        ssize_t length = read(args->tun, buffer, get_mtu());
        if (length < 0) {
            int error = errno;
            ng_free(buffer, __FILE__, __LINE__);

            log_android(ANDROID_LOG_ERROR, "tun %d read error %d: %s",
                        args->tun, error, strerror(error));
            if (error == EINTR || error == EAGAIN)
                // Retry later
                return 0;
            else {
                report_exit(args, error, "tun %d read error %d: %s",
                            args->tun, error, strerror(error));
                return -1;
            }
        } else if (length > 0) {
            // Write pcap record
            if (pcap_file != NULL)
                write_pcap_rec(buffer, (size_t) length);

            if (length > max_tun_msg) {
                max_tun_msg = length;
                log_android(ANDROID_LOG_WARN, "Maximum tun msg length %d", max_tun_msg);
            }

            // Handle IP from tun
            handle_ip(args, buffer, (size_t) length, epoll_fd, sessions, maxsessions);

            ng_free(buffer, __FILE__, __LINE__);
        } else {
            // tun eof
            ng_free(buffer, __FILE__, __LINE__);

            log_android(ANDROID_LOG_ERROR, "tun %d empty read", args->tun);
            report_exit(args, 0, "tun %d empty read", args->tun);
            return -1;
        }
    }

    return 0;
}

// SNI extraction disabled by default: connecting to tracker IPs to read TLS
// ClientHello leaks the user's IP address to the tracker server.
// Can be enabled at runtime via jni_sni() for research purposes.
int is_play = 0;

// Upper bound on bytes buffered while reassembling a ClientHello that spans
// multiple TCP segments (research-mode SNI extraction). A single TLS record is
// at most 2^14 + 5 bytes; real ClientHellos are well under this even with
// post-quantum key shares. The buffer is per-session, allocated only for a
// split ClientHello, and freed as soon as the record is complete or the cap is
// reached, so the battery/memory cost stays bounded and confined to is_play.
#define TLS_SNI_MAX_BUFFER 16384

void handle_ip(const struct arguments *args,
               const uint8_t *pkt, const size_t length,
               const int epoll_fd,
               int sessions, int maxsessions) {
    uint8_t protocol;
    void *saddr;
    void *daddr;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    char flags[10];
    char data[16];
    int flen = 0;
    uint8_t *payload;

    // Get protocol, addresses & payload
    uint8_t version = (*pkt) >> 4;
    if (version == 4) {
        if (length < sizeof(struct iphdr)) {
            log_android(ANDROID_LOG_WARN, "IP4 packet too short length %d", length);
            return;
        }

        struct iphdr *ip4hdr = (struct iphdr *) pkt;

        protocol = ip4hdr->protocol;
        saddr = &ip4hdr->saddr;
        daddr = &ip4hdr->daddr;

        uint16_t frag_off = ntohs(ip4hdr->frag_off);

        // Deliberate: every IPv4 fragment -- MF set or a non-zero offset,
        // so first, middle and last alike -- is dropped on the direct path.
        // The L4 state machines have no reassembly, and a non-first
        // fragment has no header they could parse. This return also fires before the WireGuard hijack
        // below, so unlike IPv6 fragments, IPv4 ones die even when
        // WireGuard-routed. Same standing limitation as the IPv6
        // Fragment/ESP stop further down; see issue #779. The ntohs above
        // is load-bearing: frag_off is network byte order but IP_MF and
        // IP_OFFMASK are host-order constants; don't simplify it away.
        if ((frag_off & IP_MF) || (frag_off & IP_OFFMASK)) {
            log_android(ANDROID_LOG_ERROR, "IP fragment MF %d offset %u",
                        (frag_off & IP_MF) != 0, (frag_off & IP_OFFMASK) * 8);
            return;
        }

        uint8_t ipoptlen = (uint8_t) ((ip4hdr->ihl - 5) * 4);
        payload = (uint8_t *) (pkt + sizeof(struct iphdr) + ipoptlen);

        if (ip4hdr->ihl < 5 || sizeof(struct iphdr) + ipoptlen > length) {
            log_android(ANDROID_LOG_WARN, "IP4 invalid header length");
            return;
        }

        if (ntohs(ip4hdr->tot_len) != length) {
            log_android(ANDROID_LOG_ERROR, "Invalid length %u header length %u",
                        length, ntohs(ip4hdr->tot_len));
            return;
        }

        if (loglevel < ANDROID_LOG_WARN) {
            if (!calc_checksum(0, (uint8_t *) ip4hdr, sizeof(struct iphdr))) {
                log_android(ANDROID_LOG_ERROR, "Invalid IP checksum");
                return;
            }
        }
    } else if (version == 6) {
        if (length < sizeof(struct ip6_hdr)) {
            log_android(ANDROID_LOG_WARN, "IP6 packet too short length %d", length);
            return;
        }

        struct ip6_hdr *ip6hdr = (struct ip6_hdr *) pkt;

        // Skip extension headers. ip6_skip_ext_headers() (ip6_ext.c) owns the
        // walk -- RFC 8200 Hdr Ext Len arithmetic, hard bounds checks against
        // `length`, and deciding which header types are walkable -- so it can
        // be unit-tested on the host; see ip6_ext.h for the exact contract.
        size_t payload_off;
        if (!ip6_skip_ext_headers(pkt, length, &protocol, &payload_off))
            log_android(ANDROID_LOG_WARN, "IP6 extension %d not walkable", protocol);

        // A stopped walk leaves protocol at the stopping header type --
        // Fragment (44) or ESP (50) in practice -- which matches none of
        // the dispatch branches below. On the direct path such a packet is
        // therefore dropped even when the destination passes the allow
        // check: there is no L4 header to parse, no session to attach to,
        // and no raw-forward path in this userspace stack. WireGuard-routed
        // flows are unaffected: the WG hijack below forwards them raw
        // before dispatch. Fragment reassembly was considered and rejected
        // (memory and battery cost against traffic PMTUD keeps rare); ESP
        // stays a documented limitation permanently. See issue #779.

        saddr = &ip6hdr->ip6_src;
        daddr = &ip6hdr->ip6_dst;

        payload = (uint8_t *) (pkt + payload_off);

        // TODO checksum
    } else {
        log_android(ANDROID_LOG_ERROR, "Unknown version %d", version);
        return;
    }

    inet_ntop(version == 4 ? AF_INET : AF_INET6, saddr, source, sizeof(source));
    inet_ntop(version == 4 ? AF_INET : AF_INET6, daddr, dest, sizeof(dest));

    // Get ports & flags
    int syn = 0;
    uint16_t sport = 0;
    uint16_t dport = 0;
    *data = 0;
    if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) {
        if (length - (payload - pkt) < ICMP_MINLEN) {
            log_android(ANDROID_LOG_WARN, "ICMP packet too short");
            return;
        }

        struct icmp *icmp = (struct icmp *) payload;

        sprintf(data, "type %d/%d", icmp->icmp_type, icmp->icmp_code);

        // http://lwn.net/Articles/443051/
        sport = ntohs(icmp->icmp_id);
        dport = ntohs(icmp->icmp_id);

    } else if (protocol == IPPROTO_UDP) {
        if (length - (payload - pkt) < sizeof(struct udphdr)) {
            log_android(ANDROID_LOG_WARN, "UDP packet too short");
            return;
        }

        struct udphdr *udp = (struct udphdr *) payload;

        sport = ntohs(udp->source);
        dport = ntohs(udp->dest);

        // TODO checksum (IPv6)
    } else if (protocol == IPPROTO_TCP) {
        if (length - (payload - pkt) < sizeof(struct tcphdr)) {
            log_android(ANDROID_LOG_WARN, "TCP packet too short");
            return;
        }

        struct tcphdr *tcp = (struct tcphdr *) payload;

        sport = ntohs(tcp->source);
        dport = ntohs(tcp->dest);

        if (tcp->syn) {
            syn = 1;
            flags[flen++] = 'S';
        }
        if (tcp->ack)
            flags[flen++] = 'A';
        if (tcp->psh)
            flags[flen++] = 'P';
        if (tcp->fin)
            flags[flen++] = 'F';
        if (tcp->rst)
            flags[flen++] = 'R';

        // TODO checksum
    } else if (protocol != IPPROTO_HOPOPTS && protocol != IPPROTO_IGMP && protocol != IPPROTO_ESP)
        log_android(ANDROID_LOG_WARN, "Unknown protocol %d", protocol);

    flags[flen] = 0;

    // A blocked UDP session remembers the verdict so repeated datagrams do not
    // redo the Java policy lookup. Reject it before the generic
    // existing-session shortcut below, otherwise packet two is marked allowed
    // and the WireGuard hijack forwards it without reaching block_udp().
    int udp_session_state = protocol == IPPROTO_UDP
            ? get_udp_session_state(args, pkt, payload) : -1;
    if (udp_state_blocks_outbound(udp_session_state))
        return;

    // Reuse the same lookup throughout this packet. On UDP this is otherwise
    // a linear session-table walk at both the UID and allow-policy gates.
    int udp_session_exists = udp_session_state >= 0;

    // Limit number of sessions
    if (sessions >= maxsessions) {
        if ((protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6) ||
            (protocol == IPPROTO_UDP && !udp_session_exists) ||
            (protocol == IPPROTO_TCP && syn)) {
            log_android(ANDROID_LOG_ERROR,
                        "%d of max %d sessions, dropping version %d protocol %d",
                        sessions, maxsessions, protocol, version);
            return;
        }
    }

    int wg_is_required = atomic_load_explicit(&wg_required, memory_order_acquire);

    jint uid = -1;

    // A reused TCP five-tuple starts with a fresh SYN. Do not let a verdict
    // from the previous connection suppress this connection's policy check.
    if (protocol == IPPROTO_TCP && syn)
        route_flow_clear_verdict(version, protocol, saddr, sport, daddr, dport);

    // Tunnelled TCP has no native session, so the usual established-TCP
    // shortcut cannot tell whether a policy generation has changed. Keep the
    // block verdict in the same generation-scoped cache as the route verdict.
    // Direct TCP has an ng_session and keeps its existing state machine. Do
    // not scan that session list until the route/verdict cache misses: the
    // established WG fast path has no reason to look for a native session.
    int tcp_native_session = 0;
    int tcp_flow_policy_pending = 0;
    int tcp_flow_cached_allowed = 0;
    int tcp_flow_route_cached = 0;
    int tcp_flow_tunnel = 0;
    int tcp_flow_uid_known = 0;
    int tcp_flow_verdict = ROUTE_FLOW_VERDICT_UNKNOWN;

    if (wg_is_required && protocol == IPPROTO_TCP && !syn) {
        tcp_flow_route_cached = route_flow_lookup(version, protocol,
                                                   saddr, sport, daddr, dport,
                                                   &tcp_flow_tunnel,
                                                   &tcp_flow_uid_known);
        // The policy verdict is needed even when a reload changes this flow
        // from tunnelled to direct: without an ng_session, the established
        // TCP shortcut would otherwise bypass Java and feed an unowned packet
        // to handle_tcp(). The route decision below still preserves local and
        // direct-DNS semantics.
        int tcp_flow_verdict_cached = route_flow_lookup_verdict(
                version, protocol, saddr, sport, daddr, dport,
                &tcp_flow_verdict);
        if (tcp_flow_verdict_cached &&
            tcp_flow_verdict == ROUTE_FLOW_VERDICT_BLOCKED)
                return;
        if (tcp_flow_verdict_cached &&
            tcp_flow_verdict == ROUTE_FLOW_VERDICT_ALLOWED) {
            tcp_flow_cached_allowed = 1;
        } else {
            // Only a cache miss needs to distinguish a direct native flow
            // from a tunnelled flow. A direct flow already has its policy and
            // state in ng_session; a tunnelled flow needs a fresh owner and
            // Java decision for this generation.
            tcp_native_session = has_tcp_session(args, version, pkt, payload);
            if (!tcp_native_session) {
                tcp_flow_policy_pending = 1;
                if (!tcp_flow_route_cached) {
                    jint resolved_uid = -1;
                    tcp_flow_tunnel = resolve_tunnel_uid(
                            args, version, protocol, saddr, sport, daddr, dport,
                            source, dest, pkt, payload, uid, &resolved_uid);
                    if (resolved_uid >= 0)
                        uid = resolved_uid;
                }

                // A route cache entry can be known-stable while its owner is
                // not (the global route does not depend on UID). Retry
                // ownership for a pending Java decision so an unresolved
                // lookup never gets pinned.
                if (uid < 0) {
                    jint resolved_uid = resolve_flow_owner(
                            args, version, protocol, saddr, sport, daddr, dport,
                            source, dest, pkt, payload);
                    if (resolved_uid >= 0)
                        uid = resolved_uid;
                }
            }
        }
    }

    if (tcp_flow_policy_pending && uid < 0) {
        // ServiceSinkhole treats INVALID_UID as an allowed/unknown packet.
        // Keep this flow fail-closed and leave its verdict unknown so the next
        // packet retries ownership after Android has published the socket.
        log_android(ANDROID_LOG_WARN,
                    "WG TCP owner unavailable for %s/%u > %s/%u; dropping",
                    source, sport, dest, dport);
        return;
    }

    // Reuse an established-flow verdict before the UID and Java policy lookups
    // only when its owner was resolved. An entry created under INVALID_UID is
    // deliberately unstable: subsequent packets retry ownership, allowing the
    // unknown-system fallback and per-app route to be corrected. Direct UDP
    // gets the same stable shortcut from its ng_session; tunnelled UDP has no
    // ng_session. Policy reloads invalidate the route cache.
    int cached_udp_tunnel = 0;
    int cached_udp_uid_known = 0;
    int udp_route_cached = 0;
    int reuse_wg_udp_verdict = 0;
    if (wg_is_required && protocol == IPPROTO_UDP && !udp_session_exists) {
        udp_route_cached = route_flow_lookup(version, protocol,
                                             saddr, sport, daddr, dport,
                                             &cached_udp_tunnel,
                                             &cached_udp_uid_known);
        int wants_tunnel = udp_route_cached &&
                route_wants_tunnel(is_local_dest(version, daddr), dport == 53,
                                   cached_udp_tunnel, route_dns_direct());
        reuse_wg_udp_verdict = can_reuse_wg_udp_verdict(
                wg_is_required, protocol, udp_route_cached,
                cached_udp_uid_known, wants_tunnel);
    }

    // Get uid. SNI research mode deliberately lets a 443 SYN through without
    // one so the ClientHello can be reassembled first. That is fine for the
    // block decision, but the routing fork needs the UID now: with no UID and
    // no session, the SYN falls to the global default and every later packet
    // of that flow inherits the answer from it.
    int sni_candidate = (is_play && protocol == IPPROTO_TCP && dport == 443);
    if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6 ||
        (protocol == IPPROTO_UDP && !reuse_wg_udp_verdict &&
         !udp_session_exists) ||
        (protocol == IPPROTO_TCP && syn &&
         (!sni_candidate || route_uid_relevant()))) {
            if (args->ctx->sdk <= 28) // Android 9 Pie
                uid = get_uid(version, protocol, saddr, sport, daddr, dport);
            else
                uid = get_uid_q(args, version, protocol, source, sport, dest, dport);
    }

    // SNI research mode reassembles a ClientHello on the ng_session that
    // handle_tcp creates for a 443 flow — but the WireGuard hijack below hands
    // a tunnelled flow's packets to the WG bridge and returns before
    // handle_tcp ever runs, so such a flow never gets a session to reassemble
    // on. Left alone that means the reassembly guard always sees cur == NULL,
    // so no SNI is ever collected, and every later segment re-runs the full
    // is_address_allowed() upcall and a UID lookup forever, because the
    // once-per-session shortcut lives inside a cur != NULL branch a tunnelled
    // flow never reaches. Rather than build per-flow reassembly state for a
    // tunnelled flow, resolve the routing verdict up front and, when it
    // tunnels, drop sni_active so the packet takes the ordinary path below:
    // decide once on the SYN by IP, then allowed = 1, exactly as WireGuard
    // behaves without research mode.
    int sni_active = sni_candidate;
    int sni_tunnel_uid = 0;
    int sni_tunnel_uid_known = 0;
    jint sni_resolved_uid = -1;
    if (wg_is_required && sni_candidate) {
        // is_dns is 0 here by construction: this is only reached for dport
        // 443. uid is unresolved for every packet but the SYN of a per-app
        // routed flow, which is what the flow cache and the session-table
        // fallback inside resolve_tunnel_uid are for. sni_resolved_uid, when
        // filled in, lets the UID-attribution fallback below reuse whatever
        // this call already paid a session-table/procfs lookup for, instead
        // of resolving it a second time.
        sni_tunnel_uid = resolve_tunnel_uid(args, version, protocol,
                                            saddr, sport, daddr, dport,
                                            source, dest, pkt, payload, uid,
                                            &sni_resolved_uid);
        sni_tunnel_uid_known = 1;
        if (route_wants_tunnel(is_local_dest(version, daddr), 0,
                               sni_tunnel_uid, route_dns_direct()))
            sni_active = 0;
    }

    // The ordinary path decides on the SYN, so a flow that just lost research
    // mode still needs the UID the exemption above skipped — without it the
    // decision would run unattributed, which WireGuard without research mode
    // never does. Prefer whatever resolve_tunnel_uid already resolved above;
    // only fall back to a fresh lookup when it did not (route_uid_relevant()
    // was false, so no UID needed resolving for the routing verdict).
    if (sni_candidate && !sni_active && syn && uid < 0) {
        if (sni_resolved_uid >= 0)
            uid = sni_resolved_uid;
        else if (args->ctx->sdk <= 28) // Android 9 Pie
            uid = get_uid(version, protocol, saddr, sport, daddr, dport);
        else
            uid = get_uid_q(args, version, protocol, source, sport, dest, dport);
    }

    log_android(ANDROID_LOG_DEBUG,
                "Packet v%d %s/%u > %s/%u proto %d flags %s uid %d",
                version, source, sport, dest, dport, protocol, flags, uid);

    // Check if allowed
    int allowed = 0;
    struct allowed *redirect = NULL;
    if (reuse_wg_udp_verdict)
        allowed = 1;
    else if (protocol == IPPROTO_UDP && udp_session_exists)
        allowed = 1; // could be a lingering/blocked session
    else if (protocol == IPPROTO_TCP &&
             (tcp_flow_cached_allowed ||
              ((!syn && !tcp_flow_policy_pending && (dport != 443 || !sni_active)) // assume existing session
                                         || (uid == 0 && dport == 53)         // assume existing session
                                         || (dport == 443 && syn && sni_active)))) // let SYN pass by until SNI can be extracted
        allowed = 1;
    else {
        struct ng_session *cur = NULL;
        char* packetdata = data;
        // While a ClientHello is still being reassembled across TCP segments we
        // let the segment through but postpone the block decision until the SNI
        // is available (or we give up), so it is not made on partial evidence.
        int defer_sni = 0;

        // Check if we have a CLIENT HELLO, and if so extract SNI
        if (protocol == IPPROTO_TCP && dport == 443 && !syn && sni_active) {
            // Get TCP headers
            const uint8_t version = (*pkt) >> 4;
            const struct iphdr *ip4 = (struct iphdr *) pkt;
            const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
            const struct tcphdr *tcphdr = (struct tcphdr *) payload;
            const uint8_t tcpoptlen = (uint8_t) ((tcphdr->doff - 5) * 4);
            if (tcphdr->doff < 5 ||
                sizeof(struct tcphdr) + tcpoptlen > (size_t) (length - (payload - pkt))) {
                log_android(ANDROID_LOG_WARN, "TCP invalid data offset");
                return;
            }
            const uint8_t *tcpoptions = payload + sizeof(struct tcphdr);
            const uint8_t *data = payload + sizeof(struct tcphdr) + tcpoptlen;
            const uint16_t datalen = (const uint16_t) (length - (data - pkt));

            // Search existing TCP session (created on the SYN). Assign the
            // outer cur so the block-decision below sees the same session
            // instead of a shadowed local that stayed NULL (which meant
            // checkedHostname was never set and the decision re-ran every
            // packet).
            cur = args->ctx->ng_session;
            while (cur != NULL &&
                   !(cur->protocol == IPPROTO_TCP &&
                     cur->tcp.version == version &&
                     cur->tcp.source == tcphdr->source && cur->tcp.dest == tcphdr->dest &&
                     (version == 4 ? cur->tcp.saddr.ip4 == ip4->saddr &&
                                     cur->tcp.daddr.ip4 == ip4->daddr
                                   : memcmp(&cur->tcp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                                     memcmp(&cur->tcp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
                cur = cur->next;

            // Try to parse the Server Name Indication once per session. A
            // ClientHello can span several TCP segments; buffer up to
            // TLS_SNI_MAX_BUFFER bytes and retry instead of losing the hostname
            // when it does not fit in the first segment.
            if (cur != NULL && cur->tcp.checkedHostname == 0) {
                char hostname[FQDN_MAX + 1] = "";
                int rc;

                if (datalen == 0) {
                    // Bare ACK (e.g. completing the TCP handshake) or FIN:
                    // no ClientHello bytes to parse yet. Keep waiting for
                    // data without spending the reassembly buffer.
                    defer_sni = 1;
                } else if (cur->tcp.tls_data == NULL) {
                    // First segment: try to parse it on its own.
                    rc = parse_tls_header((const char *) data, datalen, hostname);
                    if (rc == TLS_PARSE_INCOMPLETE && datalen < TLS_SNI_MAX_BUFFER) {
                        // ClientHello continues in later segments: start
                        // bounded reassembly.
                        cur->tcp.tls_data = ng_malloc(TLS_SNI_MAX_BUFFER, "tls sni");
                        if (cur->tcp.tls_data != NULL) {
                            memcpy(cur->tcp.tls_data, data, datalen);
                            cur->tcp.tls_len = datalen;
                            defer_sni = 1;
                        }
                    }
                } else {
                    // Continuation: append this segment and re-parse the
                    // accumulated record.
                    uint16_t space = (uint16_t) (TLS_SNI_MAX_BUFFER - cur->tcp.tls_len);
                    uint16_t copy = datalen < space ? datalen : space;
                    memcpy(cur->tcp.tls_data + cur->tcp.tls_len, data, copy);
                    cur->tcp.tls_len += copy;
                    rc = parse_tls_header((const char *) cur->tcp.tls_data,
                                          cur->tcp.tls_len, hostname);
                    if (rc == TLS_PARSE_INCOMPLETE && cur->tcp.tls_len < TLS_SNI_MAX_BUFFER)
                        defer_sni = 1; // still need more segments
                }

                if (!defer_sni) {
                    // Determined: SNI found, no SNI, invalid, or cap reached.
                    if (strnlen(hostname, sizeof(hostname)) > 0) {
                        log_android(ANDROID_LOG_DEBUG, "Seen SNI: %s", hostname);
                        packetdata = hostname;
                    }
                    if (cur->tcp.tls_data != NULL) {
                        ng_free(cur->tcp.tls_data, __FILE__, __LINE__);
                        cur->tcp.tls_data = NULL;
                        cur->tcp.tls_len = 0;
                    }
                }
            }

            // Find uid to handle in main activity
            if (uid < 0) {
                if (args->ctx->sdk <= 28) // Android 9 Pie.
                    uid = get_uid(version, protocol, saddr, sport, daddr, dport);
                else
                    uid = get_uid_q(args, version, protocol, source, sport, dest, dport);
            }

            allowed = 1;
        }

        // No existing TCP session, or unhandled TLS session? Skip while a
        // ClientHello is still being reassembled (the segment is already
        // allowed to pass; the decision waits for the SNI).
        if (!defer_sni && (cur == NULL || cur->tcp.checkedHostname == 0)) {
            jobject objPacket = create_packet(
                    args, version, protocol, flags, source, sport, dest, dport, packetdata, uid, 0);
            redirect = is_address_allowed(args, objPacket);
            allowed = (redirect != NULL);
            if (redirect != NULL && (*redirect->raddr == 0 || redirect->rport == 0))
                redirect = NULL;

            if (cur != NULL) {
                cur->tcp.checkedHostname = 1;
                // A blocked verdict on an established session must reset the
                // connection: dropping only this segment is undone by TCP
                // retransmission, because later segments skip the
                // once-per-session decision above and pass with allowed = 1.
                // Mirrors handle_tcp's write_rst for blocked new sessions.
                if (!allowed)
                    write_rst(args, &cur->tcp);
            }
        }
    }

    // Handle allowed traffic
    if (allowed) {
        // WireGuard hijack: when enabled, hand the raw IP packet to the WG
        // bridge instead of running the userspace TCP/UDP state machines.
        // Per-app UID lookup and the block decision above still apply.
        // Loopback/link-local/multicast are kept on the local path. DNS is
        // intentionally protected by WG too: in WG mode the VPN builder uses
        // WG DNS or public fallback DNS, and unprotected DNS would leak the
        // user's physical network.
        int is_dns = (dport == 53 &&
                      (protocol == IPPROTO_UDP || protocol == IPPROTO_TCP));

        // Which app this packet belongs to — but only when that can change the
        // answer. With no per-app override configured every UID routes the same
        // way, and this is the per-packet path: resolving a UID there would
        // cost a lock and, for the established flows that arrive with uid == -1
        // (existing UDP sessions, non-SYN TCP, i.e. most packets), a walk of the
        // whole session table, which grows with load. That is pure waste for
        // everyone who has not opted in. The SNI research-mode check above
        // already resolved this (and stored it in the flow cache) for a
        // candidate 443 flow while WireGuard is required; reuse that answer
        // instead of resolving and re-storing it a second time.
        int tunnel_uid = udp_route_cached && cached_udp_uid_known
                ? cached_udp_tunnel
                : sni_tunnel_uid_known
                ? sni_tunnel_uid
                : resolve_tunnel_uid(args, version, protocol,
                                     saddr, sport, daddr, dport,
                                     source, dest, pkt, payload, uid, NULL);

        int wg_dest = route_wants_tunnel(is_local_dest(version, daddr), is_dns,
                                         tunnel_uid, route_dns_direct());

        // A tunnelled TCP flow has no ng_session, so retain the Java decision
        // beside the route. This is generation-scoped and owner-scoped: an
        // unresolved UID must retry rather than pin an answer for this tuple.
        if (wg_is_required && protocol == IPPROTO_TCP && !tcp_native_session &&
            uid >= 0)
            route_flow_store_verdict(version, protocol, saddr, sport, daddr, dport,
                                     ROUTE_FLOW_VERDICT_ALLOWED);

        if (wg_dest) {
            ssize_t w;
            int write_errno;
            if (write_wireguard_packet(pkt, length, &w, &write_errno)) {
                if (w != (ssize_t) length) {
                    if (w < 0 && (write_errno == EAGAIN || write_errno == EWOULDBLOCK)) {
                        long drops = atomic_fetch_add_explicit(
                                &wg_drop_count, 1, memory_order_relaxed) + 1;
                        if ((drops & 1023L) == 1)
                            log_android(ANDROID_LOG_WARN,
                                        "wg socket buffer full, dropped %ld packets", drops);
                    } else
                        log_android(ANDROID_LOG_WARN, "wg write %zd/%zu errno %d: %s",
                                    w, length, write_errno, strerror(write_errno));
                }
                // Fail-closed: if the write fails, drop. Do not fall through to direct.
                return;
            }
        }

        // Fail closed while WG is required but not (yet) running — e.g. the
        // brief window during a tunnel restart, or after a failed start.
        // Falling through to direct forwarding here would leak traffic onto
        // the raw network. DNS is exempted: WG recovery needs to re-resolve
        // the peer endpoint, and blocking DNS would deadlock that recovery
        // (the resolver runs on the VPN network). This briefly exposes DNS
        // queries to the configured (WG/public fallback) DNS server over the
        // physical network, which is far less than leaking all traffic.
        if (wg_is_required && wg_dest && !is_dns) {
            long drops = atomic_fetch_add_explicit(
                    &wg_gap_drop_count, 1, memory_order_relaxed) + 1;
            if ((drops & 255L) == 1)
                log_android(ANDROID_LOG_WARN,
                            "wg not running, dropped %ld packets (fail closed)", drops);
            return;
        }

        if (protocol == IPPROTO_ICMP || protocol == IPPROTO_ICMPV6)
            handle_icmp(args, pkt, length, payload, uid, epoll_fd);
        else if (protocol == IPPROTO_UDP)
            handle_udp(args, pkt, length, payload, uid, redirect, epoll_fd);
        else if (protocol == IPPROTO_TCP)
            handle_tcp(args, pkt, length, payload, uid, allowed, redirect, epoll_fd);
        else {
            // Allowed but undispatchable: no L4 handler for this protocol
            // (in practice a Fragment/ESP-stopped ext-header walk, see
            // above). Drop visibly rather than vanish silently, but
            // rate-limit: a single ESP/IPsec flow can hit this every
            // packet and would otherwise spam logcat.
            long drops = atomic_fetch_add_explicit(
                    &undispatchable_drop_count, 1, memory_order_relaxed) + 1;
            // HOPOPTS/IGMP/ESP are exempted from the log here too, mirroring
            // the "Unknown protocol" exemption above -- still counted so the
            // running total stays honest, just never logged. The throttle
            // runs off its own counter: sharing one with the exempt
            // protocols would let a steady ESP flow eat every slot and
            // silence the protocols worth seeing.
            if (protocol != IPPROTO_HOPOPTS && protocol != IPPROTO_IGMP &&
                protocol != IPPROTO_ESP) {
                long logged = atomic_fetch_add_explicit(
                        &undispatchable_log_count, 1, memory_order_relaxed) + 1;
                if ((logged & 1023L) == 1)
                    log_android(ANDROID_LOG_WARN,
                                "Protocol %d allowed but not forwardable, dropped %ld packets (%ld total)",
                                protocol, logged, drops);
            }
        }
    } else {
        if (protocol == IPPROTO_UDP)
            block_udp(args, pkt, length, payload, uid);

        // Keep a negative result for tunnelled TCP too. It prevents repeated
        // packets (including retransmissions) from crossing JNI, while an
        // unresolved owner remains deliberately uncached and can be retried.
        if (wg_is_required && protocol == IPPROTO_TCP && !tcp_native_session &&
            uid >= 0) {
            // Cache every newly blocked established no-session flow,
            // including one whose route changed to direct after invalidation.
            // The policy decision must not be bypassed on the next packet, and
            // a blocked packet must never reach the WireGuard write below.
            if (!tcp_flow_route_cached)
                (void) resolve_tunnel_uid(
                        args, version, protocol, saddr, sport, daddr, dport,
                        source, dest, pkt, payload, uid, NULL);
            route_flow_store_verdict(version, protocol, saddr, sport, daddr, dport,
                                     ROUTE_FLOW_VERDICT_BLOCKED);
            if (tcp_flow_policy_pending && !syn &&
                !((const struct tcphdr *) payload)->rst)
                write_stateless_tcp_reset(args, version, pkt, payload, length);
        }

        log_android(ANDROID_LOG_WARN, "Address v%d p%d %s/%u syn %d not allowed",
                    version, protocol, dest, dport, syn);
    }
}

jint get_uid(const int version, const int protocol,
             const void *saddr, const uint16_t sport,
             const void *daddr, const uint16_t dport) {
    jint uid = -1;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    inet_ntop(version == 4 ? AF_INET : AF_INET6, saddr, source, sizeof(source));
    inet_ntop(version == 4 ? AF_INET : AF_INET6, daddr, dest, sizeof(dest));

    struct timeval time;
    gettimeofday(&time, NULL);
    long now = (time.tv_sec * 1000) + (time.tv_usec / 1000);

    // Check IPv6 table first
    if (version == 4) {
        int8_t saddr128[16];
        memset(saddr128, 0, 10);
        saddr128[10] = (uint8_t) 0xFF;
        saddr128[11] = (uint8_t) 0xFF;
        memcpy(saddr128 + 12, saddr, 4);

        int8_t daddr128[16];
        memset(daddr128, 0, 10);
        daddr128[10] = (uint8_t) 0xFF;
        daddr128[11] = (uint8_t) 0xFF;
        memcpy(daddr128 + 12, daddr, 4);

        uid = get_uid_sub(6, protocol, saddr128, sport, daddr128, dport, source, dest, now);
        log_android(ANDROID_LOG_DEBUG, "uid v%d p%d %s/%u > %s/%u => %d as inet6",
                    version, protocol, source, sport, dest, dport, uid);
    }

    if (uid == -1) {
        uid = get_uid_sub(version, protocol, saddr, sport, daddr, dport, source, dest, now);
        log_android(ANDROID_LOG_DEBUG, "uid v%d p%d %s/%u > %s/%u => %d fallback",
                    version, protocol, source, sport, dest, dport, uid);
    }

    if (uid == -1)
        log_android(ANDROID_LOG_WARN, "uid v%d p%d %s/%u > %s/%u => not found",
                    version, protocol, source, sport, dest, dport);
    else if (uid >= 0)
        log_android(ANDROID_LOG_INFO, "uid v%d p%d %s/%u > %s/%u => %d",
                    version, protocol, source, sport, dest, dport, uid);

    return uid;
}

int uid_cache_size = 0;
struct uid_cache_entry *uid_cache = NULL;

jint get_uid_sub(const int version, const int protocol,
                 const void *saddr, const uint16_t sport,
                 const void *daddr, const uint16_t dport,
                 const char *source, const char *dest,
                 long now) {
    // NETLINK is not available on Android due to SELinux policies :-(
    // http://stackoverflow.com/questions/27148536/netlink-implementation-for-the-android-ndk
    // https://android.googlesource.com/platform/system/sepolicy/+/master/private/app.te (netlink_tcpdiag_socket)

    static uint8_t zero[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    int ws = (version == 4 ? 1 : 4);

    // Check cache
    for (int i = 0; i < uid_cache_size; i++)
        if (now - uid_cache[i].time <= UID_MAX_AGE &&
            uid_cache[i].version == version &&
            uid_cache[i].protocol == protocol &&
            uid_cache[i].sport == sport &&
            (uid_cache[i].dport == dport || uid_cache[i].dport == 0) &&
            (memcmp(uid_cache[i].saddr, saddr, (size_t) (ws * 4)) == 0 ||
             memcmp(uid_cache[i].saddr, zero, (size_t) (ws * 4)) == 0) &&
            (memcmp(uid_cache[i].daddr, daddr, (size_t) (ws * 4)) == 0 ||
             memcmp(uid_cache[i].daddr, zero, (size_t) (ws * 4)) == 0)) {

            log_android(ANDROID_LOG_INFO, "uid v%d p%d %s/%u > %s/%u => %d (from cache)",
                        version, protocol, source, sport, dest, dport, uid_cache[i].uid);

            return uid_cache[i].uid;
        }

    // Get proc file name
    char *fn = NULL;
    if (protocol == IPPROTO_ICMP && version == 4)
        fn = "/proc/net/icmp";
    else if (protocol == IPPROTO_ICMPV6 && version == 6)
        fn = "/proc/net/icmp6";
    else if (protocol == IPPROTO_TCP)
        fn = (version == 4 ? "/proc/net/tcp" : "/proc/net/tcp6");
    else if (protocol == IPPROTO_UDP)
        fn = (version == 4 ? "/proc/net/udp" : "/proc/net/udp6");
    else
        return -1;

    // Open proc file
    FILE *fd = fopen(fn, "r");
    if (fd == NULL) {
        log_android(ANDROID_LOG_ERROR, "fopen %s error %d: %s", fn, errno, strerror(errno));
        return -2;
    }

    jint uid = -1;

    char line[250];
    int fields;

    char shex[16 * 2 + 1];
    uint8_t _saddr[16];
    int _sport;

    char dhex[16 * 2 + 1];
    uint8_t _daddr[16];
    int _dport;

    jint _uid;

    // Scan proc file
    int l = 0;
    *line = 0;
    const char *fmt = (version == 4
                       ? "%*d: %8s:%X %8s:%X %*X %*lX:%*lX %*X:%*X %*X %d %*d %*ld"
                       : "%*d: %32s:%X %32s:%X %*X %*lX:%*lX %*X:%*X %*X %d %*d %*ld");
    // Each file is a complete snapshot of one (version, protocol) table, so
    // entries cached by an earlier scan of it are either re-read below or
    // belong to sockets that have since gone. Drop them, and every expired
    // entry of any protocol, in one pass before the scan; the rows are then
    // appended without searching the cache, keeping a lookup O(cache + rows)
    // and the cache itself bounded by UID_CACHE_MAX (allocated once).
    if (uid_cache == NULL)
        uid_cache = ng_malloc(sizeof(struct uid_cache_entry) * UID_CACHE_MAX, "uid_cache");
    int kept = 0;
    for (int i = 0; i < uid_cache_size; i++)
        if (now - uid_cache[i].time <= UID_MAX_AGE &&
            !(uid_cache[i].version == version && uid_cache[i].protocol == protocol)) {
            if (kept != i)
                uid_cache[kept] = uid_cache[i];
            kept++;
        }
    uid_cache_size = kept;
    int cache_full_logged = 0;

    while (fgets(line, sizeof(line), fd) != NULL) {
        if (!l++)
            continue;

        fields = sscanf(line, fmt, shex, &_sport, dhex, &_dport, &_uid);
        if (fields == 5 && strlen(shex) == ws * 8 && strlen(dhex) == ws * 8) {
            hex2bytes(shex, _saddr);
            hex2bytes(dhex, _daddr);

            for (int w = 0; w < ws; w++)
                ((uint32_t *) _saddr)[w] = htonl(((uint32_t *) _saddr)[w]);

            for (int w = 0; w < ws; w++)
                ((uint32_t *) _daddr)[w] = htonl(((uint32_t *) _daddr)[w]);

            if (_sport == sport &&
                (_dport == dport || _dport == 0) &&
                (memcmp(_saddr, saddr, (size_t) (ws * 4)) == 0 ||
                 memcmp(_saddr, zero, (size_t) (ws * 4)) == 0) &&
                (memcmp(_daddr, daddr, (size_t) (ws * 4)) == 0 ||
                 memcmp(_daddr, zero, (size_t) (ws * 4)) == 0))
                uid = _uid;

            // Append this row; the compaction above already made room for
            // it by dropping the previous snapshot of this table, so no
            // per-row search of the cache is needed.
            if (uid_cache_size < UID_CACHE_MAX) {
                struct uid_cache_entry *e = &uid_cache[uid_cache_size++];
                e->version = (uint8_t) version;
                e->protocol = (uint8_t) protocol;
                memcpy(e->saddr, _saddr, (size_t) (ws * 4));
                e->sport = (uint16_t) _sport;
                memcpy(e->daddr, _daddr, (size_t) (ws * 4));
                e->dport = (uint16_t) _dport;
                e->uid = _uid;
                e->time = now;
            } else if (!cache_full_logged) {
                cache_full_logged = 1;
                log_android(ANDROID_LOG_WARN, "uid cache full (%d entries), not caching remaining %s rows",
                            UID_CACHE_MAX, fn);
            }
        } else {
            log_android(ANDROID_LOG_ERROR, "Invalid field #%d: %s", fields, line);
            if (fclose(fd))
                log_android(ANDROID_LOG_ERROR, "fclose %s error %d: %s", fn, errno, strerror(errno));
            return -2;
        }
    }

    if (fclose(fd))
        log_android(ANDROID_LOG_ERROR, "fclose %s error %d: %s", fn, errno, strerror(errno));

    return uid;
}
