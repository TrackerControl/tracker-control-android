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
#include <linux/errqueue.h>

extern FILE *pcap_file;

#ifndef ICMP_DEST_UNREACH
#define ICMP_DEST_UNREACH 3
#endif
#ifndef ICMP_TIME_EXCEEDED
#define ICMP_TIME_EXCEEDED 11
#endif

#define ICMP_ERROR_DRAIN_MAX 16

static uint16_t read_u16(const uint8_t *p) {
    uint16_t value;
    memcpy(&value, p, sizeof(value));
    return value;
}

static void write_u16(uint8_t *p, uint16_t value) {
    memcpy(p, &value, sizeof(value));
}

static void write_u32(uint8_t *p, uint32_t value) {
    memcpy(p, &value, sizeof(value));
}

static int is_echo_request(int version, const uint8_t *data) {
    return (version == 4 && data[0] == ICMP_ECHO && data[1] == 0) ||
           (version == 6 && data[0] == ICMP6_ECHO_REQUEST && data[1] == 0);
}

static int is_echo_reply(int version, const uint8_t *data) {
    return (version == 4 && data[0] == ICMP_ECHOREPLY && data[1] == 0) ||
           (version == 6 && data[0] == ICMP6_ECHO_REPLY && data[1] == 0);
}

static void expire_icmp_quotes(struct icmp_session *cur, time_t now) {
    if (cur->quotes == NULL)
        return;

    for (int i = 0; i < ICMP_QUOTE_SLOTS; i++) {
        struct icmp_quote *quote = &cur->quotes[i];
        if (quote->valid && quote->time + ICMP_TIMEOUT <= now)
            quote->valid = 0;
    }
}

static void invalidate_icmp_quote(struct icmp_session *cur, uint16_t seq) {
    if (cur->quotes == NULL)
        return;

    for (int i = 0; i < ICMP_QUOTE_SLOTS; i++)
        if (cur->quotes[i].valid && cur->quotes[i].seq == seq)
            cur->quotes[i].valid = 0;
}

static void remember_icmp_quote(struct icmp_session *cur,
                                const uint8_t *pkt, size_t length,
                                const uint8_t *payload, uint16_t seq) {
    if (cur->quotes == NULL)
        return;

    time_t now = time(NULL);
    expire_icmp_quotes(cur, now);
    invalidate_icmp_quote(cur, seq);
    if (payload < pkt)
        return;

    size_t ip_offset = (size_t) (payload - pkt);
    if (ip_offset > ICMP_QUOTE_MAXLEN - ICMP_MINLEN ||
        length < ip_offset + ICMP_MINLEN)
        return;

    int slot_index = cur->quote_next % ICMP_QUOTE_SLOTS;
    struct icmp_quote *slot = &cur->quotes[slot_index];

    size_t quote_len = length < ICMP_QUOTE_MAXLEN ? length : ICMP_QUOTE_MAXLEN;
    memset(slot, 0, sizeof(*slot));
    memcpy(slot->packet, pkt, quote_len);
    slot->time = now;
    slot->seq = seq;
    slot->len = (uint16_t) quote_len;
    slot->ip_offset = (uint16_t) ip_offset;
    slot->version = (uint8_t) cur->version;
    slot->valid = 1;
    cur->quote_next = (uint8_t) ((slot_index + 1) % ICMP_QUOTE_SLOTS);
}

static struct icmp_quote *find_icmp_quote(struct icmp_session *cur,
                                          uint16_t seq) {
    if (cur->quotes == NULL)
        return NULL;

    expire_icmp_quotes(cur, time(NULL));
    for (int i = 0; i < ICMP_QUOTE_SLOTS; i++)
        if (cur->quotes[i].valid && cur->quotes[i].seq == seq)
            return &cur->quotes[i];
    return NULL;
}

static uint16_t icmp_checksum(const struct icmp_session *cur,
                              const uint8_t *data, size_t length) {
    uint16_t csum = 0;
    if (cur->version == 6) {
        struct ip6_hdr_pseudo pseudo;
        memset(&pseudo, 0, sizeof(pseudo));
        memcpy(&pseudo.ip6ph_src, &cur->daddr.ip6, 16);
        memcpy(&pseudo.ip6ph_dst, &cur->saddr.ip6, 16);
        pseudo.ip6ph_len = htonl((uint32_t) length);
        pseudo.ip6ph_nxt = IPPROTO_ICMPV6;
        csum = calc_checksum(0, (const uint8_t *) &pseudo, sizeof(pseudo));
    }
    return (uint16_t) ~calc_checksum(csum, data, length);
}

int get_icmp_timeout(const struct icmp_session *u, int sessions, int maxsessions) {
    int timeout = ICMP_TIMEOUT;

    int scale = 100 - sessions * 100 / maxsessions;
    timeout = timeout * scale / 100;

    return timeout;
}

int check_icmp_session(const struct arguments *args, struct ng_session *s,
                       int sessions, int maxsessions) {
    time_t now = time(NULL);
    expire_icmp_quotes(&s->icmp, now);

    int timeout = get_icmp_timeout(&s->icmp, sessions, maxsessions);
    if (s->icmp.stop || s->icmp.time + timeout < now) {
        char source[INET6_ADDRSTRLEN + 1];
        char dest[INET6_ADDRSTRLEN + 1];
        if (s->icmp.version == 4) {
            inet_ntop(AF_INET, &s->icmp.saddr.ip4, source, sizeof(source));
            inet_ntop(AF_INET, &s->icmp.daddr.ip4, dest, sizeof(dest));
        } else {
            inet_ntop(AF_INET6, &s->icmp.saddr.ip6, source, sizeof(source));
            inet_ntop(AF_INET6, &s->icmp.daddr.ip6, dest, sizeof(dest));
        }
        log_android(ANDROID_LOG_WARN, "ICMP idle %d/%d sec stop %d from %s to %s",
                    now - s->icmp.time, timeout, s->icmp.stop, dest, source);

        if (close(s->socket))
            log_android(ANDROID_LOG_ERROR, "ICMP close %d error %d: %s",
                        s->socket, errno, strerror(errno));
        s->socket = -1;

        return 1;
    }

    return 0;
}

static int accepted_error(int version, uint8_t type) {
    if (version == 4)
        return type == ICMP_DEST_UNREACH || type == ICMP_TIME_EXCEEDED;
    return type == ICMP6_DST_UNREACH || type == ICMP6_TIME_EXCEEDED ||
           type == ICMP6_PACKET_TOO_BIG;
}

/* Parse and validate the one error cmsg emitted by a ping socket. */
static int parse_icmp_error(const struct icmp_session *cur,
                            const struct msghdr *message,
                            struct sock_extended_err *error,
                            struct sockaddr_storage *offender) {
    if ((message->msg_flags & MSG_CTRUNC) || message->msg_control == NULL ||
        message->msg_controllen == 0)
        return 0;

    if (message->msg_name == NULL)
        return 0;
    if (cur->version == 4) {
        if (message->msg_namelen < sizeof(struct sockaddr_in) ||
            ((const struct sockaddr *) message->msg_name)->sa_family != AF_INET ||
            ((const struct sockaddr_in *) message->msg_name)->sin_addr.s_addr !=
                cur->daddr.ip4)
            return 0;
    } else {
        if (message->msg_namelen < sizeof(struct sockaddr_in6) ||
            ((const struct sockaddr *) message->msg_name)->sa_family != AF_INET6 ||
            memcmp(&((const struct sockaddr_in6 *) message->msg_name)->sin6_addr,
                   &cur->daddr.ip6, sizeof(cur->daddr.ip6)) != 0)
            return 0;
    }

    const int level = cur->version == 4 ? IPPROTO_IP : IPPROTO_IPV6;
    const int type = cur->version == 4 ? IP_RECVERR : IPV6_RECVERR;
    const uint8_t *start = (const uint8_t *) message->msg_control;
    const uint8_t *end = start + message->msg_controllen;
    int found = 0;
    struct msghdr mutable_message = *message;

    for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&mutable_message); cmsg != NULL;
         cmsg = CMSG_NXTHDR(&mutable_message, cmsg)) {
        const uint8_t *cstart = (const uint8_t *) cmsg;
        if (cstart < start || cstart >= end || cmsg->cmsg_len < CMSG_LEN(0) ||
            (size_t) cmsg->cmsg_len > (size_t) (end - cstart))
            return 0;
        if (cmsg->cmsg_level != level || cmsg->cmsg_type != type || found)
            return 0;
        if (cmsg->cmsg_len < CMSG_LEN(sizeof(*error)) + sizeof(struct sockaddr))
            return 0;

        memcpy(error, CMSG_DATA(cmsg), sizeof(*error));
        const struct sockaddr *source =
                (const struct sockaddr *) (CMSG_DATA(cmsg) + sizeof(*error));
        if (source->sa_family != (cur->version == 4 ? AF_INET : AF_INET6))
            return 0;
        size_t source_len = cur->version == 4 ? sizeof(struct sockaddr_in)
                                              : sizeof(struct sockaddr_in6);
        if (cmsg->cmsg_len < CMSG_LEN(sizeof(*error)) + source_len)
            return 0;
        memset(offender, 0, sizeof(*offender));
        memcpy(offender, source, source_len);
        found = 1;
    }

    if (!found)
        return 0;
    if (error->ee_origin != (cur->version == 4 ? SO_EE_ORIGIN_ICMP
                                               : SO_EE_ORIGIN_ICMP6))
        return 0;
    if (!accepted_error(cur->version, error->ee_type))
        return 0;
    return 1;
}

static int relay_icmp_error(const struct arguments *args,
                            struct ng_session *session,
                            const uint8_t *data, size_t datalen,
                            const struct msghdr *message) {
    if (datalen < ICMP_MINLEN || (message->msg_flags & MSG_CTRUNC))
        return 0;

    struct sock_extended_err error;
    struct sockaddr_storage offender;
    if (!parse_icmp_error(&session->icmp, message, &error, &offender))
        return 0;
    if (!is_echo_request(session->icmp.version, data))
        return 0;

    uint16_t seq = read_u16(data + 6);
    struct icmp_quote *quote = find_icmp_quote(&session->icmp, seq);
    if (quote == NULL || quote->version != (uint8_t) session->icmp.version ||
        quote->len < quote->ip_offset + ICMP_MINLEN)
        return 0;

    union {
        uint16_t align;
        uint8_t data[ICMP_MINLEN + ICMP_QUOTE_MAXLEN];
    } output_storage;
    uint8_t *output = output_storage.data;
    memset(output, 0, sizeof(output_storage.data));
    output[0] = error.ee_type;
    output[1] = error.ee_code;
    uint32_t info = htonl(error.ee_info);
    write_u32(output + 4, info);
    memcpy(output + ICMP_MINLEN, quote->packet, quote->len);

    /* The error has been consumed even when writing to tun subsequently fails. */
    quote->valid = 0;

    struct icmp_session output_session = session->icmp;
    if (session->icmp.version == 4)
        output_session.daddr.ip4 =
                ((const struct sockaddr_in *) &offender)->sin_addr.s_addr;
    else
        memcpy(&output_session.daddr.ip6,
               &((const struct sockaddr_in6 *) &offender)->sin6_addr, 16);
    uint16_t csum = icmp_checksum(&output_session, output,
                                  ICMP_MINLEN + quote->len);
    write_u16(output + 2, csum);

    if (write_icmp(args, &output_session, output,
                   ICMP_MINLEN + quote->len) < 0)
        return -1;
    return 1;
}

static int drain_icmp_errors(const struct arguments *args,
                             struct ng_session *session) {
    uint8_t buffer[ICMP_MINLEN];
    int result = 0;

    for (int count = 0; count < ICMP_ERROR_DRAIN_MAX; count++) {
        struct sockaddr_storage name;
        union {
            struct cmsghdr align;
            uint8_t data[CMSG_SPACE(sizeof(struct sock_extended_err) +
                                    sizeof(struct sockaddr_storage))];
        } control;
        struct iovec vector = { .iov_base = buffer, .iov_len = sizeof(buffer) };
        struct msghdr message;
        memset(&message, 0, sizeof(message));
        memset(&name, 0, sizeof(name));
        memset(&control, 0, sizeof(control));
        message.msg_name = &name;
        message.msg_namelen = sizeof(name);
        message.msg_iov = &vector;
        message.msg_iovlen = 1;
        message.msg_control = control.data;
        message.msg_controllen = sizeof(control.data);

        ssize_t bytes = recvmsg(session->socket, &message,
                                MSG_ERRQUEUE | MSG_DONTWAIT);
        if (bytes < 0) {
            if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)
                log_android(ANDROID_LOG_WARN, "ICMP recv error queue error %d: %s",
                            errno, strerror(errno));
            break;
        }
        if (bytes == 0)
            break;

        result++;
        session->icmp.time = time(NULL);
        if (message.msg_flags & MSG_CTRUNC)
            continue;
        if (message.msg_flags & MSG_TRUNC && bytes < ICMP_MINLEN)
            continue;

        int relayed = relay_icmp_error(args, session, buffer, (size_t) bytes,
                                       &message);
        if (relayed < 0) {
            return -1;
        }
    }

    return result;
}

void check_icmp_socket(const struct arguments *args, const struct epoll_event *ev) {
    struct ng_session *s = (struct ng_session *) ev->data.ptr;

    if (ev->events & EPOLLERR) {
        if (drain_icmp_errors(args, s) < 0)
            s->icmp.stop = 1;

        /* Clear a residual SO_ERROR without treating a network error as EOF. */
        int serr = 0;
        socklen_t optlen = sizeof(serr);
        int err = getsockopt(s->socket, SOL_SOCKET, SO_ERROR, &serr, &optlen);
        if (err < 0)
            log_android(ANDROID_LOG_WARN, "ICMP getsockopt error %d: %s",
                        errno, strerror(errno));
        else if (serr)
            log_android(ANDROID_LOG_INFO, "ICMP network error %d: %s",
                        serr, strerror(serr));
    }

    if (ev->events & EPOLLIN) {
        s->icmp.time = time(NULL);

        uint16_t blen = (uint16_t) (s->icmp.version == 4 ? ICMP4_MAXMSG : ICMP6_MAXMSG);
        uint8_t *buffer = ng_malloc(blen, "icmp socket");
        ssize_t bytes = recv(s->socket, buffer, blen, 0);
        if (bytes < 0) {
            if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                log_android(ANDROID_LOG_WARN, "ICMP recv error %d: %s",
                            errno, strerror(errno));
                if (errno == EBADF || errno == ENOTSOCK || errno == EINVAL)
                    s->icmp.stop = 1;
            }
        } else if (bytes == 0) {
            log_android(ANDROID_LOG_WARN, "ICMP recv eof");
            s->icmp.stop = 1;
        } else if (bytes >= ICMP_MINLEN && is_echo_reply(s->icmp.version, buffer)) {
            uint16_t seq = read_u16(buffer + 6);
            invalidate_icmp_quote(&s->icmp, seq);
            write_u16(buffer + 4, s->icmp.id);
            write_u16(buffer + 2, 0);
            write_u16(buffer + 2, icmp_checksum(&s->icmp, buffer, (size_t) bytes));

            if (write_icmp(args, &s->icmp, buffer, (size_t) bytes) < 0)
                s->icmp.stop = 1;
        } else if (bytes < ICMP_MINLEN) {
            log_android(ANDROID_LOG_WARN, "ICMP short reply %d", bytes);
        }
        ng_free(buffer, __FILE__, __LINE__);
    }
}

jboolean handle_icmp(const struct arguments *args,
                     const uint8_t *pkt, size_t length,
                     const uint8_t *payload,
                     int uid,
                     const int epoll_fd) {
    if (pkt == NULL || payload == NULL || length == 0 || payload < pkt)
        return 0;

    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (const struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (const struct ip6_hdr *) pkt;
    size_t payload_offset = (size_t) (payload - pkt);
    if ((version == 4 && (length < sizeof(struct iphdr) ||
                          ip4->ihl < 5 ||
                          payload_offset < (size_t) ip4->ihl * 4)) ||
        (version == 6 && (length < sizeof(struct ip6_hdr) ||
                          payload_offset < sizeof(struct ip6_hdr))) ||
        payload_offset > length || length - payload_offset < ICMP_MINLEN)
        return 0;

    const uint8_t *icmp = payload;
    if (!is_echo_request(version, icmp)) {
        log_android(ANDROID_LOG_WARN, "ICMP type %d code %d not supported",
                    icmp[0], icmp[1]);
        return 0;
    }

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (version == 4) {
        inet_ntop(AF_INET, &ip4->saddr, source, sizeof(source));
        inet_ntop(AF_INET, &ip4->daddr, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &ip6->ip6_src, source, sizeof(source));
        inet_ntop(AF_INET6, &ip6->ip6_dst, dest, sizeof(dest));
    }

    uint16_t original_id = read_u16(icmp + 4);
    struct ng_session *cur = args->ctx->ng_session;
    while (cur != NULL &&
           !((cur->protocol == IPPROTO_ICMP || cur->protocol == IPPROTO_ICMPV6) &&
             !cur->icmp.stop && cur->icmp.version == version &&
             cur->icmp.id == original_id &&
             (version == 4 ? cur->icmp.saddr.ip4 == ip4->saddr &&
                             cur->icmp.daddr.ip4 == ip4->daddr
                           : memcmp(&cur->icmp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                             memcmp(&cur->icmp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    if (cur == NULL) {
        log_android(ANDROID_LOG_INFO, "ICMP new session from %s to %s id %x",
                    source, dest, original_id);

        size_t allocation = sizeof(struct ng_session) +
                            ICMP_QUOTE_SLOTS * sizeof(struct icmp_quote);
        struct ng_session *s = ng_malloc(allocation, "icmp session");
        memset(s, 0, allocation);
        s->protocol = (uint8_t) (version == 4 ? IPPROTO_ICMP : IPPROTO_ICMPV6);
        s->icmp.time = time(NULL);
        s->icmp.uid = uid;
        s->icmp.version = version;
        s->icmp.id = original_id;
        s->icmp.quotes = (struct icmp_quote *) (s + 1);

        if (version == 4) {
            s->icmp.saddr.ip4 = (__be32) ip4->saddr;
            s->icmp.daddr.ip4 = (__be32) ip4->daddr;
        } else {
            memcpy(&s->icmp.saddr.ip6, &ip6->ip6_src, 16);
            memcpy(&s->icmp.daddr.ip6, &ip6->ip6_dst, 16);
        }

        s->socket = open_icmp_socket(args, &s->icmp);
        if (s->socket < 0) {
            ng_free(s, __FILE__, __LINE__);
            return 0;
        }

        memset(&s->ev, 0, sizeof(s->ev));
        s->ev.events = EPOLLIN | EPOLLERR;
        s->ev.data.ptr = s;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, s->socket, &s->ev)) {
            log_android(ANDROID_LOG_ERROR, "epoll add icmp error %d: %s",
                        errno, strerror(errno));
            close(s->socket);
            ng_free(s, __FILE__, __LINE__);
            return 0;
        }

        s->next = args->ctx->ng_session;
        args->ctx->ng_session = s;
        cur = s;
    }

    /* Clear an earlier asynchronous error before this probe is queued. */
    if (drain_icmp_errors(args, cur) < 0) {
        cur->icmp.stop = 1;
        return 0;
    }

    /* Ping sockets rewrite the identifier and checksum themselves. */
    int hop = version == 4 ? ip4->ttl : ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim;
    int level = version == 4 ? IPPROTO_IP : IPPROTO_IPV6;
    int option = version == 4 ? IP_TTL : IPV6_UNICAST_HOPS;
    if (setsockopt(cur->socket, level, option, &hop, sizeof(hop)) < 0) {
        log_android(ANDROID_LOG_ERROR, "ICMP hop limit setup error %d: %s",
                    errno, strerror(errno));
        cur->icmp.stop = 1;
        return 0;
    }

    uint16_t seq = read_u16(icmp + 6);
    size_t icmplen = length - payload_offset;
    struct sockaddr_in server4;
    struct sockaddr_in6 server6;
    memset(&server4, 0, sizeof(server4));
    memset(&server6, 0, sizeof(server6));
    if (version == 4) {
        server4.sin_family = AF_INET;
        server4.sin_addr.s_addr = ip4->daddr;
    } else {
        server6.sin6_family = AF_INET6;
        server6.sin6_addr = ip6->ip6_dst;
    }
    int retried = 0;
    for (;;) {
        remember_icmp_quote(&cur->icmp, pkt, length, payload, seq);
        ssize_t sent = sendto(cur->socket, payload, (socklen_t) icmplen, MSG_NOSIGNAL,
                              version == 4 ? (const struct sockaddr *) &server4
                                           : (const struct sockaddr *) &server6,
                              version == 4 ? sizeof(server4) : sizeof(server6));
        if (sent == (ssize_t) icmplen) {
            cur->icmp.time = time(NULL);
            break;
        }

        int send_error = errno;
        invalidate_icmp_quote(&cur->icmp, seq);
        log_android(ANDROID_LOG_WARN, "ICMP sendto error %d: %s",
                    send_error, strerror(send_error));

        /* A kernel error may be queued just before sendto reports it.  Drain
         * once and retry once when that drain consumed an error; never spin. */
        int drained = drain_icmp_errors(args, cur);
        if (drained < 0) {
            cur->icmp.stop = 1;
            return 0;
        }
        if (!retried && drained > 0) {
            retried = 1;
            continue;
        }

        /* Network failures are asynchronous and do not invalidate the
         * session; the next probe gets its own bounded retry opportunity. */
        if (send_error != EINTR && send_error != EAGAIN &&
            send_error != EWOULDBLOCK)
            log_android(ANDROID_LOG_INFO, "ICMP network send deferred");
        break;
    }

    return 1;
}

int open_icmp_socket(const struct arguments *args, const struct icmp_session *cur) {
    int sock = socket(cur->version == 4 ? PF_INET : PF_INET6, SOCK_DGRAM,
                      cur->version == 4 ? IPPROTO_ICMP : IPPROTO_ICMPV6);
    if (sock < 0) {
        log_android(ANDROID_LOG_ERROR, "ICMP socket error %d: %s", errno, strerror(errno));
        return -1;
    }

    if (protect_socket(args, sock) < 0) {
        close(sock);
        return -1;
    }

    int enable = 1;
    int level = cur->version == 4 ? IPPROTO_IP : IPPROTO_IPV6;
    int option = cur->version == 4 ? IP_RECVERR : IPV6_RECVERR;
    if (setsockopt(sock, level, option, &enable, sizeof(enable)) < 0) {
        log_android(ANDROID_LOG_ERROR, "ICMP error queue setup error %d: %s",
                    errno, strerror(errno));
        close(sock);
        return -1;
    }

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags < 0 || fcntl(sock, F_SETFL, flags | O_NONBLOCK) < 0) {
        log_android(ANDROID_LOG_ERROR, "fcntl socket O_NONBLOCK error %d: %s",
                    errno, strerror(errno));
        close(sock);
        return -1;
    }

    return sock;
}

ssize_t write_icmp(const struct arguments *args, const struct icmp_session *cur,
                   uint8_t *data, size_t datalen) {
    size_t len;
    u_int8_t *buffer;
    uint8_t type = datalen >= 1 ? data[0] : 0;
    uint8_t code = datalen >= 2 ? data[1] : 0;
    uint16_t id = datalen >= 6 ? read_u16(data + 4) : 0;
    uint16_t seq = datalen >= 8 ? read_u16(data + 6) : 0;

    // Build packet
    if (cur->version == 4) {
        len = sizeof(struct iphdr) + datalen;
        buffer = ng_malloc(len, "icmp write4");
        struct iphdr *ip4 = (struct iphdr *) buffer;

        if (datalen)
            memcpy(buffer + sizeof(struct iphdr), data, datalen);

        // Build IP4 header
        memset(ip4, 0, sizeof(struct iphdr));
        ip4->version = 4;
        ip4->ihl = sizeof(struct iphdr) >> 2;
        ip4->tot_len = htons(len);
        ip4->ttl = IPDEFTTL;
        ip4->protocol = IPPROTO_ICMP;
        ip4->saddr = cur->daddr.ip4;
        ip4->daddr = cur->saddr.ip4;
        // Calculate IP4 checksum
        ip4->check = ~calc_checksum(0, (uint8_t *) ip4, sizeof(struct iphdr));
    } else {
        len = sizeof(struct ip6_hdr) + datalen;
        buffer = ng_malloc(len, "icmp write6");
        struct ip6_hdr *ip6 = (struct ip6_hdr *) buffer;
        if (datalen)
            memcpy(buffer + sizeof(struct ip6_hdr), data, datalen);

        // Build IP6 header
        memset(ip6, 0, sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_flow = 0;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(len - sizeof(*ip6));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_ICMPV6;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = IPDEFTTL;
        ip6->ip6_ctlun.ip6_un2_vfc = IPV6_VERSION;
        memcpy(&(ip6->ip6_src), &cur->daddr.ip6, 16);
        memcpy(&(ip6->ip6_dst), &cur->saddr.ip6, 16);
    }

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? (const void *) &cur->saddr.ip4
                                : (const void *) &cur->saddr.ip6,
              source, sizeof(source));
    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? (const void *) &cur->daddr.ip4
                                : (const void *) &cur->daddr.ip6,
              dest, sizeof(dest));

    // Send ICMP message to tun
    log_android(ANDROID_LOG_WARN,
                "ICMP sending to tun %d from %s to %s data %u type %d code %d id %x seq %d",
                args->tun, dest, source, datalen, type, code, id, seq);

    ssize_t res = write(args->tun, buffer, len);

    // Write PCAP record
    if (res >= 0) {
        if (pcap_file != NULL)
            write_pcap_rec(buffer, (size_t) res);
    } else
        log_android(ANDROID_LOG_WARN, "ICMP write error %d: %s", errno, strerror(errno));

    ng_free(buffer, __FILE__, __LINE__);
    if (res != len) {
        log_android(ANDROID_LOG_ERROR, "write %d/%d", res, len);
        return -1;
    }
    return res;
}
