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
#include "dns_frame.h"

extern char socks5_addr[INET6_ADDRSTRLEN + 1];
extern int socks5_port;
extern char socks5_username[127 + 1];
extern char socks5_password[127 + 1];

extern FILE *pcap_file;

struct dns_frame_parse_context {
    const struct arguments *args;
    struct ng_session *session;
};

static size_t parse_dns_frame(void *opaque, uint8_t *payload, size_t length) {
    const struct dns_frame_parse_context *ctx =
            (const struct dns_frame_parse_context *) opaque;
    size_t parsed_length = length;
    parse_dns_response(ctx->args, ctx->session, payload, &parsed_length);
    return parsed_length;
}

static int reserve_dns_frame(void *opaque, struct dns_frame_stream *stream,
                             size_t required) {
    (void) opaque;
    if (required > DNS_FRAME_MAX_BUFFER)
        return -1;
    if (stream->buffer != NULL && stream->capacity >= required)
        return 0;

    uint8_t *storage = ng_malloc(required, "dns tcp reassembly");
    if (storage == NULL)
        return -1;
    if (stream->buffer != NULL) {
        memcpy(storage, stream->buffer, stream->buffered);
        ng_free(stream->buffer, __FILE__, __LINE__);
    }
    stream->buffer = storage;
    stream->capacity = required;
    return 0;
}

static int reserve_dns_pending(void *opaque, struct dns_frame_stream *stream,
                               size_t required) {
    (void) opaque;
    if (required > DNS_FRAME_MAX_PENDING)
        return -1;
    if (stream->pending != NULL && stream->pending_capacity >= required)
        return 0;

    /* Grow geometrically, but stop at half the cap before promoting to the
     * final capacity. This bounds an old+new replacement allocation. */
    size_t capacity = stream->pending_capacity;
    const size_t half = DNS_FRAME_MAX_PENDING / 2;
    if (capacity < 2)
        capacity = 2;
    while (capacity < required) {
        if (capacity >= half) {
            capacity = DNS_FRAME_MAX_PENDING;
            break;
        }
        if (capacity > half / 2)
            capacity = half;
        else
            capacity *= 2;
    }
    if (capacity < required)
        return -1;

    uint8_t *storage = ng_malloc(capacity, "dns tcp pending");
    if (storage == NULL)
        return -1;
    if (stream->pending != NULL) {
        const size_t pending = stream->pending_length - stream->pending_offset;
        memcpy(storage, stream->pending + stream->pending_offset, pending);
        ng_free(stream->pending, __FILE__, __LINE__);
        stream->pending_offset = 0;
        stream->pending_length = pending;
    }
    stream->pending = storage;
    stream->pending_capacity = capacity;
    return 0;
}

static void release_dns_pending(void *opaque, struct dns_frame_stream *stream) {
    (void) opaque;
    ng_free(stream->pending, __FILE__, __LINE__);
}

static int emit_dns_frame_chunk(void *opaque, const uint8_t *frame, size_t length) {
    const struct dns_frame_parse_context *ctx =
            (const struct dns_frame_parse_context *) opaque;
    struct tcp_session *tcp = &ctx->session->tcp;
    if (length == 0 || length > tcp->mss)
        return -1;
    if (write_data(ctx->args, tcp, frame, length) < 0)
        return -1;
    tcp->local_seq += length;
    tcp->unconfirmed++;
    return 0;
}

static void release_dns_frame_storage(struct tcp_session *tcp) {
    if (tcp->dns_frames.buffer == NULL || tcp->dns_frames.buffered != 0)
        return;

    ng_free(tcp->dns_frames.buffer, __FILE__, __LINE__);
    tcp->dns_frames.buffer = NULL;
    tcp->dns_frames.capacity = 0;
}

static void release_dns_pending_storage(struct tcp_session *tcp) {
    dns_frame_stream_release_pending(&tcp->dns_frames, release_dns_pending, NULL);
}

static void discard_dns_frame_state(struct tcp_session *tcp) {
    dns_frame_stream_reset(&tcp->dns_frames);
    release_dns_frame_storage(tcp);
    release_dns_pending_storage(tcp);
}

static void terminate_dns_session(const struct arguments *args,
                                  struct ng_session *session) {
    write_rst(args, &session->tcp);
    discard_dns_frame_state(&session->tcp);
    session->tcp.state = TCP_CLOSING;
    session->tcp.dns_terminal = DNS_FRAME_TERMINAL_NONE;
}

static int begin_dns_terminal(struct ng_session *session, int epoll_fd,
                              int terminal_state) {
    if ((terminal_state != DNS_FRAME_TERMINAL_FIN_DRAIN &&
         !dns_frame_stream_has_pending(&session->tcp.dns_frames)) ||
        session->tcp.dns_terminal != DNS_FRAME_TERMINAL_NONE)
        return -1;
    if (session->socket < 0)
        return -1;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_DEL, session->socket, NULL)) {
        log_android(ANDROID_LOG_WARN, "DNS TCP terminal epoll delete error %d: %s",
                    errno, strerror(errno));
        /* Closing the descriptor still removes HUP from epoll if deletion
         * failed because the event was already detached. Keep queued output
         * and use the reset-drain state; a failed detach cannot prove EOF. */
        if (close(session->socket))
            log_android(ANDROID_LOG_WARN, "DNS TCP terminal close error %d: %s",
                        errno, strerror(errno));
        session->socket = -1;
        session->tcp.dns_terminal = DNS_FRAME_TERMINAL_DRAIN;
        return 0;
    }
    if (close(session->socket))
        log_android(ANDROID_LOG_WARN, "DNS TCP terminal close error %d: %s",
                    errno, strerror(errno));
    session->socket = -1;
    session->tcp.dns_terminal = (uint8_t) terminal_state;
    return 0;
}

/* HUP can arrive together with readable bytes. Detach it from epoll while
 * downstream output drains, but retain the fd so those bytes can be consumed
 * after an ACK reopens the downstream window. */
static int begin_dns_hup_pending(struct ng_session *session, int epoll_fd) {
    if (!dns_frame_stream_has_pending(&session->tcp.dns_frames) ||
        session->tcp.dns_terminal != DNS_FRAME_TERMINAL_NONE ||
        session->socket < 0)
        return -1;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_DEL, session->socket, NULL)) {
        log_android(ANDROID_LOG_WARN, "DNS TCP HUP epoll delete error %d: %s",
                    errno, strerror(errno));
        /* The pending response remains valid. Closing guarantees HUP cannot
         * wake the loop forever; treat unread bytes as an explicit reset. */
        if (close(session->socket))
            log_android(ANDROID_LOG_WARN, "DNS TCP HUP close error %d: %s",
                        errno, strerror(errno));
        session->socket = -1;
        session->tcp.dns_terminal = DNS_FRAME_TERMINAL_DRAIN;
        return 0;
    }
    session->ev.events = EPOLLERR;
    session->tcp.dns_terminal = DNS_FRAME_TERMINAL_HUP_PENDING;
    return 0;
}

static int resume_dns_hup(struct ng_session *session, int epoll_fd) {
    struct tcp_session *tcp = &session->tcp;
    if (!dns_frame_should_resume_hup(
                tcp->dns_terminal,
                dns_frame_stream_has_pending(&tcp->dns_frames),
                get_send_window(tcp)))
        return 0;
    if (session->socket < 0)
        return -1;

    session->ev.events = EPOLLIN | EPOLLERR;
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, session->socket, &session->ev)) {
        log_android(ANDROID_LOG_WARN, "DNS TCP HUP epoll add error %d: %s",
                    errno, strerror(errno));
        if (close(session->socket))
            log_android(ANDROID_LOG_WARN, "DNS TCP HUP resume close error %d: %s",
                        errno, strerror(errno));
        session->socket = -1;
        /* The final emitted bytes are still valid. Wait for their ACKs and
         * use the reset terminal path instead of sending an immediate RST. */
        tcp->dns_terminal = dns_frame_hup_rearm_failure_state(tcp->dns_terminal);
        return 0;
    }
    tcp->dns_terminal = DNS_FRAME_TERMINAL_NONE;
    return 0;
}

static void finish_dns_terminal(const struct arguments *args,
                                struct ng_session *session) {
    terminate_dns_session(args, session);
}

static void finish_dns_fin(const struct arguments *args,
                           struct ng_session *session) {
    struct tcp_session *tcp = &session->tcp;
    /* A FIN consumes sequence space and must not be sent into a closed
     * downstream window. The next ACK/window update retries this path. */
    if (get_send_window(tcp) == 0)
        return;
    if (write_fin_ack(args, tcp) < 0) {
        tcp->dns_terminal = DNS_FRAME_TERMINAL_NONE;
        return;
    }
    tcp->local_seq++; // local FIN
    if (tcp->state == TCP_ESTABLISHED)
        tcp->state = TCP_FIN_WAIT1;
    else if (tcp->state == TCP_CLOSE_WAIT)
        tcp->state = TCP_LAST_ACK;
    else {
        log_android(ANDROID_LOG_ERROR, "DNS TCP invalid EOF state %d", tcp->state);
        write_rst(args, tcp);
        tcp->dns_terminal = DNS_FRAME_TERMINAL_NONE;
        return;
    }
    discard_dns_frame_state(tcp);
    tcp->dns_terminal = DNS_FRAME_TERMINAL_NONE;
}

static int flush_dns_frames(const struct arguments *args, struct ng_session *session) {
    struct tcp_session *tcp = &session->tcp;
    if (!dns_frame_stream_has_pending(&tcp->dns_frames)) {
        release_dns_pending_storage(tcp);
        return 0;
    }

    struct dns_frame_parse_context context = {
            .args = args,
            .session = session,
    };
    size_t chunks = 0;
    while (dns_frame_stream_has_pending(&tcp->dns_frames) &&
           dns_frame_flush_chunk_allowed(chunks)) {
        if (tcp->mss == 0)
            return -1;
        uint32_t window = get_send_window(tcp);
        if (window == 0)
            break;
        size_t budget = dns_frame_send_budget(window, tcp->mss);
        struct dns_frame_stream_result result;
        if (dns_frame_stream_flush(&tcp->dns_frames, budget,
                                   emit_dns_frame_chunk, &context, &result) < 0)
            return -1;
        if (result.emitted == 0)
            break;
        chunks++;
    }
    release_dns_pending_storage(tcp);
    return 0;
}

static void flush_dns_ack(const struct arguments *args,
                          struct ng_session *session, int epoll_fd) {
    struct tcp_session *tcp = &session->tcp;
    if (tcp->dns_terminal == DNS_FRAME_TERMINAL_WAIT_ACK) {
        if (dns_frame_terminal_next(tcp->dns_terminal, 0, tcp->acked,
                                    tcp->local_seq) == DNS_FRAME_TERMINAL_NONE)
            finish_dns_terminal(args, session);
        return;
    }

    if (tcp->dns_terminal == DNS_FRAME_TERMINAL_FIN_DRAIN) {
        if (dns_frame_stream_has_pending(&tcp->dns_frames) &&
            flush_dns_frames(args, session) < 0) {
            log_android(ANDROID_LOG_WARN, "DNS TCP EOF output failed");
            terminate_dns_session(args, session);
        } else if (!dns_frame_stream_has_pending(&tcp->dns_frames))
            finish_dns_fin(args, session);
        return;
    }

    if (tcp->dns_terminal == DNS_FRAME_TERMINAL_HUP_PENDING) {
        if (dns_frame_stream_has_pending(&tcp->dns_frames) &&
            flush_dns_frames(args, session) < 0) {
            log_android(ANDROID_LOG_WARN, "DNS TCP HUP output failed");
            terminate_dns_session(args, session);
        } else if (resume_dns_hup(session, epoll_fd) < 0) {
            log_android(ANDROID_LOG_WARN, "DNS TCP HUP resume failed");
            terminate_dns_session(args, session);
        } else if (tcp->dns_terminal == DNS_FRAME_TERMINAL_DRAIN) {
            /* Re-arm failure changed HUP_PENDING into reset-drain. Apply the
             * ACK transition now; no later ACK is guaranteed. */
            flush_dns_ack(args, session, epoll_fd);
        }
        return;
    }

    if (tcp->dns_terminal == DNS_FRAME_TERMINAL_DRAIN) {
        if (flush_dns_frames(args, session) < 0) {
            log_android(ANDROID_LOG_WARN, "DNS TCP terminal output failed");
            terminate_dns_session(args, session);
        } else {
            tcp->dns_terminal = dns_frame_terminal_next(
                    tcp->dns_terminal,
                    dns_frame_stream_has_pending(&tcp->dns_frames),
                    tcp->acked, tcp->local_seq);
            if (tcp->dns_terminal == DNS_FRAME_TERMINAL_NONE)
                finish_dns_terminal(args, session);
        }
    } else if (dns_frame_stream_has_pending(&tcp->dns_frames) &&
               flush_dns_frames(args, session) < 0) {
        log_android(ANDROID_LOG_WARN, "DNS TCP pending output failed");
        terminate_dns_session(args, session);
    }
}

void clear_tcp_data(struct tcp_session *cur) {
    struct segment *s = cur->forward;
    while (s != NULL) {
        struct segment *p = s;
        s = s->next;
        ng_free(p->data, __FILE__, __LINE__);
        ng_free(p, __FILE__, __LINE__);
    }
    if (cur->tls_data != NULL) {
        ng_free(cur->tls_data, __FILE__, __LINE__);
        cur->tls_data = NULL;
        cur->tls_len = 0;
    }
    if (cur->dns_frames.buffer != NULL) {
        ng_free(cur->dns_frames.buffer, __FILE__, __LINE__);
        cur->dns_frames.buffer = NULL;
        cur->dns_frames.capacity = 0;
    }
    if (cur->dns_frames.pending != NULL)
        ng_free(cur->dns_frames.pending, __FILE__, __LINE__);
    dns_frame_stream_init(&cur->dns_frames, NULL, 0);
    cur->dns_terminal = DNS_FRAME_TERMINAL_NONE;
}

int get_tcp_timeout(const struct tcp_session *t, int sessions, int maxsessions) {
    int timeout;
    int terminal_timeout = dns_frame_terminal_timeout(t->dns_terminal,
                                                       TCP_CLOSE_TIMEOUT);
    if (terminal_timeout != 0)
        timeout = terminal_timeout;
    else if (t->state == TCP_LISTEN || t->state == TCP_SYN_RECV)
        timeout = TCP_INIT_TIMEOUT;
    else if (t->state == TCP_ESTABLISHED)
        timeout = TCP_IDLE_TIMEOUT;
    else
        timeout = TCP_CLOSE_TIMEOUT;

    int scale = 100 - sessions * 100 / maxsessions;
    timeout = timeout * scale / 100;

    return timeout;
}

int check_tcp_session(const struct arguments *args, struct ng_session *s,
                      int sessions, int maxsessions) {
    time_t now = time(NULL);

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (s->tcp.version == 4) {
        inet_ntop(AF_INET, &s->tcp.saddr.ip4, source, sizeof(source));
        inet_ntop(AF_INET, &s->tcp.daddr.ip4, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &s->tcp.saddr.ip6, source, sizeof(source));
        inet_ntop(AF_INET6, &s->tcp.daddr.ip6, dest, sizeof(dest));
    }

    char session[250];
    sprintf(session, "TCP socket from %s/%u to %s/%u %s socket %d",
            source, ntohs(s->tcp.source), dest, ntohs(s->tcp.dest),
            strstate(s->tcp.state), s->socket);

    int timeout = get_tcp_timeout(&s->tcp, sessions, maxsessions);

    // Check session timeout
    if (s->tcp.state != TCP_CLOSING && s->tcp.state != TCP_CLOSE &&
        s->tcp.time + timeout < now) {
        log_android(ANDROID_LOG_WARN, "%s idle %d/%d sec ", session, now - s->tcp.time,
                    timeout);
        if (s->tcp.state == TCP_LISTEN)
            s->tcp.state = TCP_CLOSING;
        else if (ntohs(s->tcp.dest) == 53)
            terminate_dns_session(args, s);
        else
            write_rst(args, &s->tcp);
    }

    // Check closing sessions
    if (s->tcp.state == TCP_CLOSING) {
        /* DNS buffers are session state, not part of the generic TCP linger
         * period. Release them as soon as any terminal path reaches closing. */
        discard_dns_frame_state(&s->tcp);

        // eof closes socket
        if (s->socket >= 0) {
            if (close(s->socket))
                log_android(ANDROID_LOG_ERROR, "%s close error %d: %s",
                            session, errno, strerror(errno));
            else
                log_android(ANDROID_LOG_WARN, "%s close", session);
            s->socket = -1;
        }

        s->tcp.time = time(NULL);
        s->tcp.state = TCP_CLOSE;
    }

    if ((s->tcp.state == TCP_CLOSING || s->tcp.state == TCP_CLOSE) &&
        (s->tcp.sent || s->tcp.received)) {
        account_usage(args, s->tcp.version, IPPROTO_TCP,
                      dest, ntohs(s->tcp.dest), s->tcp.uid, s->tcp.sent, s->tcp.received);
        s->tcp.sent = 0;
        s->tcp.received = 0;
    }

    // Cleanup lingering sessions
    if (s->tcp.state == TCP_CLOSE && s->tcp.time + TCP_KEEP_TIMEOUT < now)
        return 1;

    return 0;
}

int monitor_tcp_session(const struct arguments *args, struct ng_session *s, int epoll_fd) {
    int recheck = 0;
    unsigned int events = EPOLLERR;

    /* Terminal DNS sessions close their upstream descriptor and flush from
     * handle_tcp() when an ACK reopens the TUN-side window. Never re-arm
     * epoll or create a maintenance poll for this state. */
    if (s->tcp.dns_terminal != DNS_FRAME_TERMINAL_NONE)
        return 0;

    if (s->tcp.state == TCP_LISTEN) {
        // Check for connected = writable
        if (s->tcp.socks5 == SOCKS5_NONE)
            events = events | EPOLLOUT;
        else
            events = events | EPOLLIN;
    } else if (s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) {
        // Check for incoming data
        uint32_t send_window = get_send_window(&s->tcp);
        if (s->tcp.state != TCP_CLOSING &&
            dns_frame_should_read(s->tcp.dns_terminal,
                                  dns_frame_stream_has_pending(&s->tcp.dns_frames),
                                  send_window))
            events = events | EPOLLIN;
        else if (dns_frame_requires_recheck(send_window)) {
            recheck = 1;

            long long ms = get_ms();
            if (dns_frame_recheck_due(ms, s->tcp.last_keep_alive,
                                      EPOLL_MIN_CHECK)) {
                s->tcp.last_keep_alive = ms;
                log_android(ANDROID_LOG_WARN, "Sending keep alive to update send window");
                s->tcp.remote_seq--;
                write_ack(args, &s->tcp);
                s->tcp.remote_seq++;
            }
        }

        // Check for outgoing data
        if (s->tcp.forward != NULL) {
            uint32_t buffer_size = get_receive_buffer(s);
            if (s->tcp.forward->seq == s->tcp.remote_seq &&
                s->tcp.forward->len - s->tcp.forward->sent < buffer_size)
                events = events | EPOLLOUT;
            else
                recheck = 1;
        }
    }

    if (events != s->ev.events) {
        s->ev.events = events;
        if (epoll_ctl(epoll_fd, EPOLL_CTL_MOD, s->socket, &s->ev)) {
            if (ntohs(s->tcp.dest) == 53)
                terminate_dns_session(args, s);
            else
                s->tcp.state = TCP_CLOSING;
            recheck = 1;
            log_android(ANDROID_LOG_ERROR, "epoll mod tcp error %d: %s", errno, strerror(errno));
        } else
            log_android(ANDROID_LOG_DEBUG, "epoll mod tcp socket %d in %d out %d",
                        s->socket, (events & EPOLLIN) != 0, (events & EPOLLOUT) != 0);
    }

    return recheck;
}

uint32_t get_send_window(const struct tcp_session *cur) {
    uint32_t total = dns_frame_send_window(cur->acked, cur->local_seq,
                                           cur->unconfirmed, cur->send_window);

    log_android(ANDROID_LOG_DEBUG, "Send window window %u total %u",
                cur->send_window, total);

    return total;
}

uint32_t get_receive_buffer(const struct ng_session *cur) {
    if (cur->socket < 0)
        return 0;

    // Get send buffer size
    // /proc/sys/net/core/wmem_default
    int sendbuf = 0;
    int sendbufsize = sizeof(sendbuf);
    if (getsockopt(cur->socket, SOL_SOCKET, SO_SNDBUF, &sendbuf, (socklen_t *) &sendbufsize) < 0)
        log_android(ANDROID_LOG_WARN, "getsockopt SO_RCVBUF %d: %s", errno, strerror(errno));

    if (sendbuf == 0)
        sendbuf = SEND_BUF_DEFAULT;

    // Get unsent data size
    int unsent = 0;
    if (ioctl(cur->socket, SIOCOUTQ, &unsent))
        log_android(ANDROID_LOG_WARN, "ioctl SIOCOUTQ %d: %s", errno, strerror(errno));

    uint32_t total = (uint32_t) (unsent < sendbuf ? sendbuf - unsent : 0);

    log_android(ANDROID_LOG_DEBUG, "Send buffer %u unsent %u total %u",
                sendbuf, unsent, total);

    return total;
}

uint32_t get_receive_window(const struct ng_session *cur) {
    // Get data to forward size
    uint32_t toforward = 0;
    struct segment *q = cur->tcp.forward;
    while (q != NULL) {
        toforward += (q->len - q->sent);
        q = q->next;
    }

    uint32_t window = get_receive_buffer(cur);

    uint32_t max = ((uint32_t) 0xFFFF) << cur->tcp.recv_scale;
    if (window > max) {
        log_android(ANDROID_LOG_DEBUG, "Receive window %u > max %u", window, max);
        window = max;
    }

    uint32_t total = (toforward < window ? window - toforward : 0);

    log_android(ANDROID_LOG_DEBUG, "Receive window toforward %u window %u total %u",
                toforward, window, total);

    return total;
}

void check_tcp_socket(const struct arguments *args,
                      const struct epoll_event *ev,
                      const int epoll_fd) {
    struct ng_session *s = (struct ng_session *) ev->data.ptr;

    int oldstate = s->tcp.state;
    uint32_t oldlocal = s->tcp.local_seq;
    uint32_t oldremote = s->tcp.remote_seq;

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (s->tcp.version == 4) {
        inet_ntop(AF_INET, &s->tcp.saddr.ip4, source, sizeof(source));
        inet_ntop(AF_INET, &s->tcp.daddr.ip4, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &s->tcp.saddr.ip6, source, sizeof(source));
        inet_ntop(AF_INET6, &s->tcp.daddr.ip6, dest, sizeof(dest));
    }
    char session[250];
    sprintf(session, "TCP socket from %s/%u to %s/%u %s loc %u rem %u",
            source, ntohs(s->tcp.source), dest, ntohs(s->tcp.dest),
            strstate(s->tcp.state),
            s->tcp.local_seq - s->tcp.local_start,
            s->tcp.remote_seq - s->tcp.remote_start);

    // Check socket error or hangup. EPOLLHUP is delivered even when EPOLLIN
    // is masked, so it must detach the upstream descriptor or epoll spins.
    const int socket_error = (ev->events & EPOLLERR) != 0;
    const int socket_hup = (ev->events & EPOLLHUP) != 0;
    const int pending_dns = dns_frame_stream_has_pending(&s->tcp.dns_frames);
    const int hup_pending = dns_frame_hup_pending_state(
            socket_error, socket_hup, pending_dns);
    if (dns_frame_socket_terminal_event(
                socket_error, socket_hup, (ev->events & EPOLLIN) != 0,
                pending_dns)) {
        s->tcp.time = time(NULL);

        int serr = 0;
        socklen_t optlen = sizeof(int);
        int err = 0;
        if (socket_error) {
            err = getsockopt(s->socket, SOL_SOCKET, SO_ERROR, &serr, &optlen);
            if (err < 0)
                log_android(ANDROID_LOG_ERROR, "%s getsockopt error %d: %s",
                            session, errno, strerror(errno));
            else if (serr)
                log_android(ANDROID_LOG_ERROR, "%s SO_ERROR %d: %s",
                            session, serr, strerror(serr));
        }

        /* A socket error stops upstream reads. Keep complete rewritten DNS
         * bytes queued while the downstream window drains; a zero window is
         * reopened by a later ACK handled on the TUN side. */
        int terminal_state = DNS_FRAME_TERMINAL_DRAIN;
        if (!socket_error) {
            terminal_state = dns_frame_eof_terminal_state(
                    dns_frame_stream_has_pending(&s->tcp.dns_frames),
                    s->tcp.dns_frames.buffered,
                    s->tcp.forward != NULL);
        }
        int terminal_started = 0;
        if ((s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) &&
            ntohs(s->tcp.dest) == 53 && hup_pending == DNS_FRAME_TERMINAL_HUP_PENDING &&
            begin_dns_hup_pending(s, epoll_fd) == 0) {
            flush_dns_ack(args, s, epoll_fd);
            terminal_started = 1;
        }
        if (!terminal_started &&
            (s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) &&
            terminal_state != DNS_FRAME_TERMINAL_NONE &&
            ntohs(s->tcp.dest) == 53 &&
            begin_dns_terminal(s, epoll_fd, terminal_state) == 0) {
            flush_dns_ack(args, s, epoll_fd);
            terminal_started = 1;
        }
        if (!terminal_started) {
            /* Detach HUP descriptors even when no complete DNS response is
             * available, otherwise epoll reports the same HUP forever. */
            if (socket_hup && s->socket >= 0) {
                if (epoll_ctl(epoll_fd, EPOLL_CTL_DEL, s->socket, NULL))
                    log_android(ANDROID_LOG_WARN,
                                "%s HUP epoll delete error %d: %s",
                                session, errno, strerror(errno));
                if (close(s->socket))
                    log_android(ANDROID_LOG_WARN, "%s HUP close error %d: %s",
                                session, errno, strerror(errno));
                s->socket = -1;
            }
            /* Incomplete frames and any failed detach cannot be forwarded
             * safely. Send an explicit reset and release all DNS state now. */
            terminate_dns_session(args, s);
        }

        // Connection refused
        if (0)
            if (err >= 0 && (serr == ECONNREFUSED || serr == EHOSTUNREACH)) {
                struct icmp icmp;
                memset(&icmp, 0, sizeof(struct icmp));
                icmp.icmp_type = ICMP_UNREACH;
                if (serr == ECONNREFUSED)
                    icmp.icmp_code = ICMP_UNREACH_PORT;
                else
                    icmp.icmp_code = ICMP_UNREACH_HOST;
                icmp.icmp_cksum = 0;
                icmp.icmp_cksum = ~calc_checksum(0, (const uint8_t *) &icmp, 4);

                struct icmp_session sicmp;
                memset(&sicmp, 0, sizeof(struct icmp_session));
                sicmp.version = s->tcp.version;
                if (s->tcp.version == 4) {
                    sicmp.saddr.ip4 = (__be32) s->tcp.saddr.ip4;
                    sicmp.daddr.ip4 = (__be32) s->tcp.daddr.ip4;
                } else {
                    memcpy(&sicmp.saddr.ip6, &s->tcp.saddr.ip6, 16);
                    memcpy(&sicmp.daddr.ip6, &s->tcp.daddr.ip6, 16);
                }

                write_icmp(args, &sicmp, (uint8_t *) &icmp, 8);
            }
    } else {
        // Assume socket okay
        if (s->tcp.state == TCP_LISTEN) {
            // Check socket connect
            if (s->tcp.socks5 == SOCKS5_NONE) {
                if (ev->events & EPOLLOUT) {
                    log_android(ANDROID_LOG_INFO, "%s connected", session);

                    // https://tools.ietf.org/html/rfc1928
                    // https://tools.ietf.org/html/rfc1929
                    // https://en.wikipedia.org/wiki/SOCKS#SOCKS5
                    if (*socks5_addr && socks5_port)
                        s->tcp.socks5 = SOCKS5_HELLO;
                    else
                        s->tcp.socks5 = SOCKS5_CONNECTED;
                }
            } else {
                if (ev->events & EPOLLIN) {
                    uint8_t buffer[32];
                    ssize_t bytes = recv(s->socket, buffer, sizeof(buffer), 0);
                    if (bytes < 0) {
                        log_android(ANDROID_LOG_ERROR, "%s recv SOCKS5 error %d: %s",
                                    session, errno, strerror(errno));
                        write_rst(args, &s->tcp);
                    } else {
                        char *h = hex(buffer, (const size_t) bytes);
                        log_android(ANDROID_LOG_INFO, "%s recv SOCKS5 %s", session, h);
                        ng_free(h, __FILE__, __LINE__);

                        if (s->tcp.socks5 == SOCKS5_HELLO &&
                            bytes == 2 && buffer[0] == 5) {
                            if (buffer[1] == 0)
                                s->tcp.socks5 = SOCKS5_CONNECT;
                            else if (buffer[1] == 2)
                                s->tcp.socks5 = SOCKS5_AUTH;
                            else {
                                s->tcp.socks5 = 0;
                                log_android(ANDROID_LOG_ERROR, "%s SOCKS5 auth %d not supported",
                                            session, buffer[1]);
                                write_rst(args, &s->tcp);
                            }

                        } else if (s->tcp.socks5 == SOCKS5_AUTH &&
                                   bytes == 2 &&
                                   (buffer[0] == 1 || buffer[0] == 5)) {
                            if (buffer[1] == 0) {
                                s->tcp.socks5 = SOCKS5_CONNECT;
                                log_android(ANDROID_LOG_WARN, "%s SOCKS5 auth OK", session);
                            } else {
                                s->tcp.socks5 = 0;
                                log_android(ANDROID_LOG_ERROR, "%s SOCKS5 auth error %d",
                                            session, buffer[1]);
                                write_rst(args, &s->tcp);
                            }

                        } else if (s->tcp.socks5 == SOCKS5_CONNECT &&
                                   bytes == 6 + (s->tcp.version == 4 ? 4 : 16) &&
                                   buffer[0] == 5) {
                            if (buffer[1] == 0) {
                                s->tcp.socks5 = SOCKS5_CONNECTED;
                                log_android(ANDROID_LOG_WARN, "%s SOCKS5 connected", session);
                            } else {
                                s->tcp.socks5 = 0;
                                log_android(ANDROID_LOG_ERROR, "%s SOCKS5 connect error %d",
                                            session, buffer[1]);
                                write_rst(args, &s->tcp);
                                /*
                                    0x00 = request granted
                                    0x01 = general failure
                                    0x02 = connection not allowed by ruleset
                                    0x03 = network unreachable
                                    0x04 = host unreachable
                                    0x05 = connection refused by destination host
                                    0x06 = TTL expired
                                    0x07 = command not supported / protocol error
                                    0x08 = address type not supported
                                 */
                            }

                        } else {
                            s->tcp.socks5 = 0;
                            log_android(ANDROID_LOG_ERROR, "%s recv SOCKS5 state %d",
                                        session, s->tcp.socks5);
                            write_rst(args, &s->tcp);
                        }
                    }
                }
            }

            if (s->tcp.socks5 == SOCKS5_HELLO) {
                uint8_t buffer[4] = {5, 2, 0, 2};
                char *h = hex(buffer, sizeof(buffer));
                log_android(ANDROID_LOG_INFO, "%s sending SOCKS5 hello: %s",
                            session, h);
                ng_free(h, __FILE__, __LINE__);
                ssize_t sent = send(s->socket, buffer, sizeof(buffer), MSG_NOSIGNAL);
                if (sent < 0) {
                    log_android(ANDROID_LOG_ERROR, "%s send SOCKS5 hello error %d: %s",
                                session, errno, strerror(errno));
                    write_rst(args, &s->tcp);
                }

            } else if (s->tcp.socks5 == SOCKS5_AUTH) {
                uint8_t ulen = strlen(socks5_username);
                uint8_t plen = strlen(socks5_password);
                uint8_t buffer[512];
                *(buffer + 0) = 1; // Version
                *(buffer + 1) = ulen;
                memcpy(buffer + 2, socks5_username, ulen);
                *(buffer + 2 + ulen) = plen;
                memcpy(buffer + 2 + ulen + 1, socks5_password, plen);

                size_t len = 2 + ulen + 1 + plen;

                char *h = hex(buffer, len);
                log_android(ANDROID_LOG_INFO, "%s sending SOCKS5 auth: %s",
                            session, h);
                ng_free(h, __FILE__, __LINE__);
                ssize_t sent = send(s->socket, buffer, len, MSG_NOSIGNAL);
                if (sent < 0) {
                    log_android(ANDROID_LOG_ERROR,
                                "%s send SOCKS5 connect error %d: %s",
                                session, errno, strerror(errno));
                    write_rst(args, &s->tcp);
                }

            } else if (s->tcp.socks5 == SOCKS5_CONNECT) {
                uint8_t buffer[22];
                *(buffer + 0) = 5; // version
                *(buffer + 1) = 1; // TCP/IP stream connection
                *(buffer + 2) = 0; // reserved
                *(buffer + 3) = (uint8_t) (s->tcp.version == 4 ? 1 : 4);
                if (s->tcp.version == 4) {
                    memcpy(buffer + 4, &s->tcp.daddr.ip4, 4);
                    *((__be16 *) (buffer + 4 + 4)) = s->tcp.dest;
                } else {
                    memcpy(buffer + 4, &s->tcp.daddr.ip6, 16);
                    *((__be16 *) (buffer + 4 + 16)) = s->tcp.dest;
                }

                size_t len = (s->tcp.version == 4 ? 10 : 22);

                char *h = hex(buffer, len);
                log_android(ANDROID_LOG_INFO, "%s sending SOCKS5 connect: %s",
                            session, h);
                ng_free(h, __FILE__, __LINE__);
                ssize_t sent = send(s->socket, buffer, len, MSG_NOSIGNAL);
                if (sent < 0) {
                    log_android(ANDROID_LOG_ERROR,
                                "%s send SOCKS5 connect error %d: %s",
                                session, errno, strerror(errno));
                    write_rst(args, &s->tcp);
                }

            } else if (s->tcp.socks5 == SOCKS5_CONNECTED) {
                s->tcp.remote_seq++; // remote SYN
                if (write_syn_ack(args, &s->tcp) >= 0) {
                    s->tcp.time = time(NULL);
                    s->tcp.local_seq++; // local SYN
                    s->tcp.state = TCP_SYN_RECV;
                }
            }
        } else {

            // Always forward data
            int fwd = 0;
            if (ev->events & EPOLLOUT) {
                // Forward data
                uint32_t buffer_size = get_receive_buffer(s);
                while (s->tcp.forward != NULL &&
                       s->tcp.forward->seq == s->tcp.remote_seq &&
                       s->tcp.forward->len - s->tcp.forward->sent < buffer_size) {
                    log_android(ANDROID_LOG_DEBUG, "%s fwd %u...%u sent %u",
                                session,
                                s->tcp.forward->seq - s->tcp.remote_start,
                                s->tcp.forward->seq + s->tcp.forward->len - s->tcp.remote_start,
                                s->tcp.forward->sent);

                    ssize_t sent = send(s->socket,
                                        s->tcp.forward->data + s->tcp.forward->sent,
                                        s->tcp.forward->len - s->tcp.forward->sent,
                                        (unsigned int) (MSG_NOSIGNAL | (s->tcp.forward->psh
                                                                        ? 0
                                                                        : MSG_MORE)));
                    if (sent < 0) {
                        log_android(ANDROID_LOG_ERROR, "%s send error %d: %s",
                                    session, errno, strerror(errno));
                        if (errno == EINTR || errno == EAGAIN) {
                            // Retry later
                            break;
                        } else {
                            write_rst(args, &s->tcp);
                            break;
                        }
                    } else {
                        fwd = 1;
                        buffer_size -= sent;
                        s->tcp.sent += sent;
                        s->tcp.forward->sent += sent;

                        if (s->tcp.forward->len == s->tcp.forward->sent) {
                            s->tcp.remote_seq = s->tcp.forward->seq + s->tcp.forward->sent;

                            struct segment *p = s->tcp.forward;
                            s->tcp.forward = s->tcp.forward->next;
                            ng_free(p->data, __FILE__, __LINE__);
                            ng_free(p, __FILE__, __LINE__);
                        } else {
                            log_android(ANDROID_LOG_WARN,
                                        "%s partial send %u/%u",
                                        session, s->tcp.forward->sent, s->tcp.forward->len);
                            break;
                        }
                    }
                }

                // Log data buffered
                struct segment *seg = s->tcp.forward;
                while (seg != NULL) {
                    log_android(ANDROID_LOG_WARN, "%s queued %u...%u sent %u",
                                session,
                                seg->seq - s->tcp.remote_start,
                                seg->seq + seg->len - s->tcp.remote_start,
                                seg->sent);
                    seg = seg->next;
                }
            }

            // Get receive window
            uint32_t window = get_receive_window(s);
            uint32_t prev = s->tcp.recv_window;
            s->tcp.recv_window = window;
            if ((prev == 0 && window > 0) || (prev > 0 && window == 0))
                log_android(ANDROID_LOG_WARN, "%s recv window %u > %u",
                            session, prev, window);

            // Acknowledge forwarded data
            if (fwd || (prev == 0 && window > 0)) {
                if (fwd && s->tcp.forward == NULL && s->tcp.state == TCP_CLOSE_WAIT) {
                    log_android(ANDROID_LOG_WARN, "%s confirm FIN", session);
                    s->tcp.remote_seq++; // remote FIN
                }
                if (write_ack(args, &s->tcp) >= 0)
                    s->tcp.time = time(NULL);
            }

            if (s->tcp.state == TCP_ESTABLISHED || s->tcp.state == TCP_CLOSE_WAIT) {
                // Check socket read
                // Send window can be changed in the mean time

                uint32_t send_window = get_send_window(&s->tcp);
                if ((ev->events & EPOLLIN) &&
                    dns_frame_should_read(
                            s->tcp.dns_terminal,
                            dns_frame_stream_has_pending(&s->tcp.dns_frames),
                            send_window)) {
                    s->tcp.time = time(NULL);

                    uint32_t buffer_size = (send_window > s->tcp.mss
                                            ? s->tcp.mss : send_window);
                    uint8_t *buffer = ng_malloc(buffer_size, "tcp socket");
                    ssize_t bytes = recv(s->socket, buffer, (size_t) buffer_size, 0);
                    if (bytes < 0) {
                        // Socket error
                        log_android(ANDROID_LOG_ERROR, "%s recv error %d: %s",
                                    session, errno, strerror(errno));

                        if (errno != EINTR && errno != EAGAIN) {
                            if (ntohs(s->tcp.dest) == 53)
                                terminate_dns_session(args, s);
                            else
                                write_rst(args, &s->tcp);
                        }
                    } else if (bytes == 0) {
                        log_android(ANDROID_LOG_WARN, "%s recv eof", session);

                        int dns_stream = ntohs(s->tcp.dest) == 53;
                        int preserve_dns_terminal = 0;
                        int eof_terminal = dns_frame_eof_terminal_state(
                                dns_stream && dns_frame_stream_has_pending(&s->tcp.dns_frames),
                                s->tcp.dns_frames.buffered,
                                s->tcp.forward != NULL);
                        if (dns_stream && eof_terminal != DNS_FRAME_TERMINAL_NONE) {
                            if (begin_dns_terminal(s, epoll_fd, eof_terminal) == 0) {
                                /* Keep complete rewritten responses alive
                                 * after upstream EOF; ACKs flush the queue and
                                 * then finish_dns_fin or finish_dns_terminal. */
                                preserve_dns_terminal = 1;
                                flush_dns_ack(args, s, epoll_fd);
                            } else {
                                log_android(ANDROID_LOG_WARN,
                                            "%s DNS TCP EOF detach failed; resetting",
                                            session);
                                terminate_dns_session(args, s);
                            }
                        } else if (dns_stream && s->tcp.dns_frames.buffered != 0) {
                            log_android(ANDROID_LOG_WARN,
                                        "%s DNS TCP EOF with incomplete frame; resetting",
                                        session);
                            write_rst(args, &s->tcp);
                        } else if (s->tcp.forward == NULL) {
                            if (write_fin_ack(args, &s->tcp) >= 0) {
                                log_android(ANDROID_LOG_WARN, "%s FIN sent", session);
                                s->tcp.local_seq++; // local FIN
                            }

                            if (s->tcp.state == TCP_ESTABLISHED)
                                s->tcp.state = TCP_FIN_WAIT1;
                            else if (s->tcp.state == TCP_CLOSE_WAIT)
                                s->tcp.state = TCP_LAST_ACK;
                            else
                                log_android(ANDROID_LOG_ERROR, "%s invalid close", session);
                        } else {
                            // There was still data to send
                            log_android(ANDROID_LOG_ERROR, "%s close with queue", session);
                            write_rst(args, &s->tcp);
                        }

                        if (s->socket >= 0) {
                            if (close(s->socket))
                                log_android(ANDROID_LOG_ERROR, "%s close error %d: %s",
                                            session, errno, strerror(errno));
                            s->socket = -1;
                        }
                        if (!preserve_dns_terminal)
                            discard_dns_frame_state(&s->tcp);

                    } else {
                        // Socket read data
                        log_android(ANDROID_LOG_DEBUG, "%s recv bytes %d", session, bytes);
                        s->tcp.received += bytes;

                        // Process DNS response. TCP reads are arbitrary stream
                        // chunks, so retain incomplete frames and inspect every
                        // complete frame exactly once. Completed output stays
                        // queued until the downstream send window permits it.
                        int dns_stream = ntohs(s->tcp.dest) == 53;
                        if (dns_stream) {
                            struct dns_frame_parse_context parse_context = {
                                    .args = args,
                                    .session = s,
                            };
                            size_t stream_bytes = (size_t) bytes;
                            struct dns_frame_stream_result result;
                            int feed_error = dns_frame_stream_feed(
                                    &s->tcp.dns_frames, buffer, &stream_bytes,
                                    parse_dns_frame, reserve_dns_frame,
                                    reserve_dns_pending, &parse_context, &result);
                            if (feed_error < 0) {
                                log_android(ANDROID_LOG_WARN,
                                            "%s DNS TCP fail-open queue failed; closing",
                                            session);
                                terminate_dns_session(args, s);
                            } else if (flush_dns_frames(args, s) < 0) {
                                log_android(ANDROID_LOG_WARN,
                                            "%s DNS TCP pending output failed",
                                            session);
                                terminate_dns_session(args, s);
                            }
                            release_dns_frame_storage(&s->tcp);
                        } else {
                            // Forward non-DNS data to tun.
                            if (write_data(args, &s->tcp, buffer, (size_t) bytes) >= 0) {
                                s->tcp.local_seq += bytes;
                                s->tcp.unconfirmed++;
                            }
                        }
                    }
                    ng_free(buffer, __FILE__, __LINE__);
                }
            }
        }
    }

    if (s->tcp.state != oldstate || s->tcp.local_seq != oldlocal ||
        s->tcp.remote_seq != oldremote)
        log_android(ANDROID_LOG_DEBUG, "%s new state", session);
}

jboolean handle_tcp(const struct arguments *args,
                    const uint8_t *pkt, size_t length,
                    const uint8_t *payload,
                    int uid, int allowed, struct allowed *redirect,
                    const int epoll_fd) {
    // Get headers
    const uint8_t version = (*pkt) >> 4;
    const struct iphdr *ip4 = (struct iphdr *) pkt;
    const struct ip6_hdr *ip6 = (struct ip6_hdr *) pkt;
    const struct tcphdr *tcphdr = (struct tcphdr *) payload;
    const uint8_t tcpoptlen = (uint8_t) ((tcphdr->doff - 5) * 4);
    if (tcphdr->doff < 5 ||
        sizeof(struct tcphdr) + tcpoptlen > (size_t) (length - (payload - pkt))) {
        log_android(ANDROID_LOG_WARN, "TCP invalid data offset");
        return 0;
    }
    const uint8_t *tcpoptions = payload + sizeof(struct tcphdr);
    const uint8_t *data = payload + sizeof(struct tcphdr) + tcpoptlen;
    const uint16_t datalen = (const uint16_t) (length - (data - pkt));

    // Search session
    struct ng_session *cur = args->ctx->ng_session;
    while (cur != NULL &&
           !(cur->protocol == IPPROTO_TCP &&
             cur->tcp.version == version &&
             cur->tcp.source == tcphdr->source && cur->tcp.dest == tcphdr->dest &&
             (version == 4 ? cur->tcp.saddr.ip4 == ip4->saddr &&
                             cur->tcp.daddr.ip4 == ip4->daddr
                           : memcmp(&cur->tcp.saddr.ip6, &ip6->ip6_src, 16) == 0 &&
                             memcmp(&cur->tcp.daddr.ip6, &ip6->ip6_dst, 16) == 0)))
        cur = cur->next;

    // Prepare logging
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    if (version == 4) {
        inet_ntop(AF_INET, &ip4->saddr, source, sizeof(source));
        inet_ntop(AF_INET, &ip4->daddr, dest, sizeof(dest));
    } else {
        inet_ntop(AF_INET6, &ip6->ip6_src, source, sizeof(source));
        inet_ntop(AF_INET6, &ip6->ip6_dst, dest, sizeof(dest));
    }

    char flags[10];
    int flen = 0;
    if (tcphdr->syn)
        flags[flen++] = 'S';
    if (tcphdr->ack)
        flags[flen++] = 'A';
    if (tcphdr->psh)
        flags[flen++] = 'P';
    if (tcphdr->fin)
        flags[flen++] = 'F';
    if (tcphdr->rst)
        flags[flen++] = 'R';
    if (tcphdr->urg)
        flags[flen++] = 'U';
    flags[flen] = 0;

    char packet[250];
    sprintf(packet,
            "TCP %s %s/%u > %s/%u seq %u ack %u data %u win %u uid %d",
            flags,
            source, ntohs(tcphdr->source),
            dest, ntohs(tcphdr->dest),
            ntohl(tcphdr->seq) - (cur == NULL ? 0 : cur->tcp.remote_start),
            tcphdr->ack ? ntohl(tcphdr->ack_seq) - (cur == NULL ? 0 : cur->tcp.local_start) : 0,
            datalen, ntohs(tcphdr->window), uid);
    log_android(tcphdr->urg ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, packet);

    // Drop URG data
    if (tcphdr->urg)
        return 1;

    // Check session
    if (cur == NULL) {
        if (tcphdr->syn) {
            // Decode options
            // http://www.iana.org/assignments/tcp-parameters/tcp-parameters.xhtml#tcp-parameters-1
            uint16_t mss = get_default_mss(version);
            uint8_t ws = 0;
            int optlen = tcpoptlen;
            uint8_t *options = (uint8_t *) tcpoptions;
            while (optlen > 0) {
                uint8_t kind = *options;
                if (kind == 0) // End of options list
                    break;

                if (kind == 1) {
                    optlen--;
                    options++;
                    continue;
                }

                if (optlen < 2)
                    break;

                uint8_t len = *(options + 1);
                if (len < 2 || len > optlen)
                    break;

                if (kind == 2 && len == 4)
                    mss = ntohs(*((uint16_t *) (options + 2)));

                else if (kind == 3 && len == 3)
                    ws = *(options + 2);

                optlen -= len;
                options += len;
            }

            // In tethering compatibility mode, clamp the MSS we use for
            // downstream segmentation into the tun.
            // This bounds the size of the segments we emit toward the peer so
            // they fit constrained paths even though PMTUD ICMP is dropped
            // (#478). Smaller values advertised by the peer remain unchanged.
            if (args->ctx->tcp_mss_clamp && mss > TCP_MSS_CLAMP)
                mss = TCP_MSS_CLAMP;

            log_android(ANDROID_LOG_WARN, "%s new session mss %u ws %u window %u",
                        packet, mss, ws, ntohs(tcphdr->window) << ws);

            // Register session
            struct ng_session *s = ng_malloc(sizeof(struct ng_session), "tcp session");
            s->protocol = IPPROTO_TCP;

            s->tcp.time = time(NULL);
            s->tcp.uid = uid;
            s->tcp.version = version;
            s->tcp.mss = mss;
            s->tcp.recv_scale = ws;
            s->tcp.send_scale = ws;
            s->tcp.send_window = ((uint32_t) ntohs(tcphdr->window)) << s->tcp.send_scale;
            s->tcp.unconfirmed = 0;
            s->tcp.remote_seq = ntohl(tcphdr->seq); // ISN remote
            s->tcp.local_seq = (uint32_t) rand(); // ISN local
            s->tcp.remote_start = s->tcp.remote_seq;
            s->tcp.local_start = s->tcp.local_seq;
            s->tcp.acked = 0;
            s->tcp.last_keep_alive = 0;
            s->tcp.sent = 0;
            s->tcp.received = 0;

            if (version == 4) {
                s->tcp.saddr.ip4 = (__be32) ip4->saddr;
                s->tcp.daddr.ip4 = (__be32) ip4->daddr;
            } else {
                memcpy(&s->tcp.saddr.ip6, &ip6->ip6_src, 16);
                memcpy(&s->tcp.daddr.ip6, &ip6->ip6_dst, 16);
            }

            s->tcp.source = tcphdr->source;
            s->tcp.dest = tcphdr->dest;
            s->tcp.state = TCP_LISTEN;
            s->tcp.socks5 = SOCKS5_NONE;
            s->tcp.forward = NULL;
            s->tcp.checkedHostname = 0;
            s->tcp.tls_data = NULL;
            s->tcp.tls_len = 0;
            dns_frame_stream_init(&s->tcp.dns_frames, NULL, 0);
            s->tcp.dns_terminal = DNS_FRAME_TERMINAL_NONE;
            s->next = NULL;

            if (datalen) {
                log_android(ANDROID_LOG_WARN, "%s SYN data", packet);
                s->tcp.forward = ng_malloc(sizeof(struct segment), "syn segment");
                s->tcp.forward->seq = s->tcp.remote_seq;
                s->tcp.forward->len = datalen;
                s->tcp.forward->sent = 0;
                s->tcp.forward->psh = tcphdr->psh;
                s->tcp.forward->data = ng_malloc(datalen, "syn segment data");
                memcpy(s->tcp.forward->data, data, datalen);
                s->tcp.forward->next = NULL;
            }

            // Open socket
            s->socket = open_tcp_socket(args, &s->tcp, redirect);
            if (s->socket < 0) {
                // Remote might retry
                ng_free(s, __FILE__, __LINE__);
                return 0;
            }

            s->tcp.recv_window = get_receive_window(s);

            log_android(ANDROID_LOG_DEBUG, "TCP socket %d lport %d",
                        s->socket, get_local_port(s->socket));

            // Monitor events
            memset(&s->ev, 0, sizeof(struct epoll_event));
            s->ev.events = EPOLLOUT | EPOLLERR;
            s->ev.data.ptr = s;
            if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, s->socket, &s->ev))
                log_android(ANDROID_LOG_ERROR, "epoll add tcp error %d: %s",
                            errno, strerror(errno));

            s->next = args->ctx->ng_session;
            args->ctx->ng_session = s;

            if (!allowed) {
                log_android(ANDROID_LOG_WARN, "%s resetting blocked session", packet);
                write_rst(args, &s->tcp);
            }
        } else {
            log_android(ANDROID_LOG_WARN, "%s unknown session", packet);

            struct tcp_session rst;
            memset(&rst, 0, sizeof(struct tcp_session));
            rst.version = version;
            rst.local_seq = ntohl(tcphdr->ack_seq);
            rst.remote_seq = ntohl(tcphdr->seq) + datalen + (tcphdr->syn || tcphdr->fin ? 1 : 0);

            if (version == 4) {
                rst.saddr.ip4 = (__be32) ip4->saddr;
                rst.daddr.ip4 = (__be32) ip4->daddr;
            } else {
                memcpy(&rst.saddr.ip6, &ip6->ip6_src, 16);
                memcpy(&rst.daddr.ip6, &ip6->ip6_dst, 16);
            }

            rst.source = tcphdr->source;
            rst.dest = tcphdr->dest;

            write_rst(args, &rst);
            return 0;
        }
    } else {
        char session[250];
        sprintf(session,
                "%s %s loc %u rem %u acked %u",
                packet,
                strstate(cur->tcp.state),
                cur->tcp.local_seq - cur->tcp.local_start,
                cur->tcp.remote_seq - cur->tcp.remote_start,
                cur->tcp.acked - cur->tcp.local_start);

        // Session found
        if (cur->tcp.state == TCP_CLOSING || cur->tcp.state == TCP_CLOSE) {
            log_android(ANDROID_LOG_WARN, "%s was closed", session);
            write_rst(args, &cur->tcp);
            return 0;
        } else {
            int oldstate = cur->tcp.state;
            uint32_t oldlocal = cur->tcp.local_seq;
            uint32_t oldremote = cur->tcp.remote_seq;

            log_android(ANDROID_LOG_DEBUG, "%s handling", session);

            if (!tcphdr->syn)
                cur->tcp.time = time(NULL);
            cur->tcp.send_window = ((uint32_t) ntohs(tcphdr->window)) << cur->tcp.send_scale;
            cur->tcp.unconfirmed = 0;

            // Do not change the order of the conditions

            // Queue data to forward
            if (datalen) {
                if (cur->socket < 0) {
                    if (ntohs(cur->tcp.dest) != 53 ||
                        cur->tcp.dns_terminal == DNS_FRAME_TERMINAL_NONE) {
                        log_android(ANDROID_LOG_ERROR,
                                    "%s data while local closed", session);
                        write_rst(args, &cur->tcp);
                        return 0;
                    }
                    /* A detached DNS upstream cannot accept another query,
                     * but a valid response may still be queued downstream.
                     * Keep that response alive and process this packet's ACK;
                     * do not turn an ACK+payload into an unconditional RST. */
                    log_android(ANDROID_LOG_WARN,
                                "%s DNS data while upstream terminal; not forwarding",
                                session);
                } else if (cur->tcp.state == TCP_CLOSE_WAIT) {
                    log_android(ANDROID_LOG_ERROR, "%s data while remote closed", session);
                    write_rst(args, &cur->tcp);
                    return 0;
                } else {
                    queue_tcp(args, tcphdr, session, &cur->tcp, data, datalen);
                }
            }

            if (tcphdr->rst /* +ACK */) {
                // No sequence check
                // http://tools.ietf.org/html/rfc1122#page-87
                log_android(ANDROID_LOG_WARN, "%s received reset", session);
                cur->tcp.state = TCP_CLOSING;
                if (cur->tcp.dns_frames.buffered != 0)
                    log_android(ANDROID_LOG_WARN,
                                "%s DNS TCP reset with incomplete frame, dropping",
                                session);
                discard_dns_frame_state(&cur->tcp);
                cur->tcp.dns_terminal = DNS_FRAME_TERMINAL_NONE;
                return 0;
            } else {
                if (!tcphdr->ack || ntohl(tcphdr->ack_seq) == cur->tcp.local_seq) {
                    if (tcphdr->syn) {
                        log_android(ANDROID_LOG_WARN, "%s repeated SYN", session);
                        // The socket is probably not opened yet

                    } else if (tcphdr->fin /* +ACK */) {
                        if (cur->tcp.state == TCP_ESTABLISHED) {
                            log_android(ANDROID_LOG_WARN, "%s FIN received", session);
                            if (cur->tcp.forward == NULL) {
                                cur->tcp.remote_seq++; // remote FIN
                                if (write_ack(args, &cur->tcp) >= 0)
                                    cur->tcp.state = TCP_CLOSE_WAIT;
                            } else
                                cur->tcp.state = TCP_CLOSE_WAIT;
                        } else if (cur->tcp.state == TCP_CLOSE_WAIT) {
                            log_android(ANDROID_LOG_WARN, "%s repeated FIN", session);
                            // The socket is probably not closed yet
                        } else if (cur->tcp.state == TCP_FIN_WAIT1) {
                            log_android(ANDROID_LOG_WARN, "%s last ACK", session);
                            cur->tcp.remote_seq++; // remote FIN
                            if (write_ack(args, &cur->tcp) >= 0)
                                cur->tcp.state = TCP_CLOSE;
                        } else {
                            log_android(ANDROID_LOG_ERROR, "%s invalid FIN", session);
                            return 0;
                        }

                    } else if (tcphdr->ack) {
                        cur->tcp.acked = ntohl(tcphdr->ack_seq);

                        if (cur->tcp.state == TCP_SYN_RECV)
                            cur->tcp.state = TCP_ESTABLISHED;

                        else if (cur->tcp.state == TCP_ESTABLISHED) {
                            // Do nothing
                        } else if (cur->tcp.state == TCP_LAST_ACK)
                            cur->tcp.state = TCP_CLOSING;

                        else if (cur->tcp.state == TCP_CLOSE_WAIT) {
                            // ACK after FIN/ACK
                        } else if (cur->tcp.state == TCP_FIN_WAIT1) {
                            // Do nothing
                        } else {
                            log_android(ANDROID_LOG_ERROR, "%s invalid state", session);
                            return 0;
                        }
                    } else {
                        log_android(ANDROID_LOG_ERROR, "%s unknown packet", session);
                        return 0;
                    }
                } else {
                    uint32_t ack = ntohl(tcphdr->ack_seq);
                    if ((uint32_t) (ack + 1) == cur->tcp.local_seq) {
                        // Keep alive
                        if (cur->tcp.state == TCP_ESTABLISHED && cur->socket >= 0) {
                            int on = 1;
                            if (setsockopt(cur->socket, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on)))
                                log_android(ANDROID_LOG_ERROR,
                                            "%s setsockopt SO_KEEPALIVE error %d: %s",
                                            session, errno, strerror(errno));
                            else
                                log_android(ANDROID_LOG_WARN, "%s enabled keep alive", session);
                        } else
                            log_android(ANDROID_LOG_WARN, "%s keep alive", session);

                    } else if (compare_u32(ack, cur->tcp.local_seq) < 0) {
                        if (compare_u32(ack, cur->tcp.acked) <= 0)
                            log_android(
                                    ack == cur->tcp.acked ? ANDROID_LOG_WARN : ANDROID_LOG_ERROR,
                                    "%s repeated ACK %u/%u",
                                    session,
                                    ack - cur->tcp.local_start,
                                    cur->tcp.acked - cur->tcp.local_start);
                        else {
                            log_android(ANDROID_LOG_WARN, "%s previous ACK %u",
                                        session, ack - cur->tcp.local_seq);
                            cur->tcp.acked = ack;
                        }

                        if (dns_frame_should_flush_ack(
                                    tcphdr->ack,
                                    cur->tcp.dns_terminal,
                                    dns_frame_stream_has_pending(&cur->tcp.dns_frames)))
                            flush_dns_ack(args, cur, epoll_fd);
                        return 1;
                    } else {
                        log_android(ANDROID_LOG_ERROR, "%s future ACK", session);
                        write_rst(args, &cur->tcp);
                        return 0;
                    }
                }
            }

            if (dns_frame_should_flush_ack(
                        tcphdr->ack,
                        cur->tcp.dns_terminal,
                        dns_frame_stream_has_pending(&cur->tcp.dns_frames)))
                flush_dns_ack(args, cur, epoll_fd);

            if (cur->tcp.state != oldstate ||
                cur->tcp.local_seq != oldlocal ||
                cur->tcp.remote_seq != oldremote)
                log_android(ANDROID_LOG_INFO, "%s > %s loc %u rem %u",
                            session,
                            strstate(cur->tcp.state),
                            cur->tcp.local_seq - cur->tcp.local_start,
                            cur->tcp.remote_seq - cur->tcp.remote_start);
        }
    }

    return 1;
}

void queue_tcp(const struct arguments *args,
               const struct tcphdr *tcphdr,
               const char *session, struct tcp_session *cur,
               const uint8_t *data, uint16_t datalen) {
    uint32_t seq = ntohl(tcphdr->seq);
    if (compare_u32(seq, cur->remote_seq) < 0)
        log_android(ANDROID_LOG_WARN, "%s already forwarded %u..%u",
                    session,
                    seq - cur->remote_start, seq + datalen - cur->remote_start);
    else {
        struct segment *p = NULL;
        struct segment *s = cur->forward;
        while (s != NULL && compare_u32(s->seq, seq) < 0) {
            p = s;
            s = s->next;
        }

        if (s == NULL || compare_u32(s->seq, seq) > 0) {
            log_android(ANDROID_LOG_DEBUG, "%s queuing %u...%u",
                        session,
                        seq - cur->remote_start, seq + datalen - cur->remote_start);
            struct segment *n = ng_malloc(sizeof(struct segment), "tcp segment");
            n->seq = seq;
            n->len = datalen;
            n->sent = 0;
            n->psh = tcphdr->psh;
            n->data = ng_malloc(datalen, "tcp segment");
            memcpy(n->data, data, datalen);
            n->next = s;
            if (p == NULL)
                cur->forward = n;
            else
                p->next = n;
        } else if (s != NULL && s->seq == seq) {
            if (s->len == datalen)
                log_android(ANDROID_LOG_WARN, "%s segment already queued %u..%u",
                            session,
                            s->seq - cur->remote_start, s->seq + s->len - cur->remote_start);
            else if (s->len < datalen) {
                log_android(ANDROID_LOG_WARN, "%s segment smaller %u..%u > %u",
                            session,
                            s->seq - cur->remote_start, s->seq + s->len - cur->remote_start,
                            s->seq + datalen - cur->remote_start);
                ng_free(s->data, __FILE__, __LINE__);
                s->len = datalen;
                s->data = ng_malloc(datalen, "tcp segment smaller");
                memcpy(s->data, data, datalen);
            } else {
                log_android(ANDROID_LOG_ERROR, "%s segment larger %u..%u < %u",
                            session,
                            s->seq - cur->remote_start, s->seq + s->len - cur->remote_start,
                            s->seq + datalen - cur->remote_start);
                ng_free(s->data, __FILE__, __LINE__);
                s->len = datalen;
                s->data = ng_malloc(datalen, "tcp segment larger");
                memcpy(s->data, data, datalen);
            }
        }
    }
}

int open_tcp_socket(const struct arguments *args,
                    const struct tcp_session *cur, const struct allowed *redirect) {
    int sock;
    int version;
    if (redirect == NULL) {
        if (*socks5_addr && socks5_port)
            version = (strstr(socks5_addr, ":") == NULL ? 4 : 6);
        else
            version = cur->version;
    } else
        version = (strstr(redirect->raddr, ":") == NULL ? 4 : 6);

    // Get TCP socket
    if ((sock = socket(version == 4 ? PF_INET : PF_INET6, SOCK_STREAM, 0)) < 0) {
        log_android(ANDROID_LOG_ERROR, "socket error %d: %s", errno, strerror(errno));
        return -1;
    }

    // Protect
    if (protect_socket(args, sock) < 0)
        return -1;

    int on = 1;
    if (setsockopt(sock, SOL_TCP, TCP_NODELAY, &on, sizeof(on)) < 0)
        log_android(ANDROID_LOG_ERROR, "setsockopt TCP_NODELAY error %d: %s",
                    errno, strerror(errno));

    // Set non blocking
    int flags = fcntl(sock, F_GETFL, 0);
    if (flags < 0 || fcntl(sock, F_SETFL, flags | O_NONBLOCK) < 0) {
        log_android(ANDROID_LOG_ERROR, "fcntl socket O_NONBLOCK error %d: %s",
                    errno, strerror(errno));
        return -1;
    }

    // Build target address
    struct sockaddr_in addr4;
    struct sockaddr_in6 addr6;
    if (redirect == NULL) {
        if (*socks5_addr && socks5_port) {
            log_android(ANDROID_LOG_WARN, "TCP%d SOCKS5 to %s/%u",
                        version, socks5_addr, socks5_port);

            if (version == 4) {
                addr4.sin_family = AF_INET;
                inet_pton(AF_INET, socks5_addr, &addr4.sin_addr);
                addr4.sin_port = htons(socks5_port);
            } else {
                addr6.sin6_family = AF_INET6;
                inet_pton(AF_INET6, socks5_addr, &addr6.sin6_addr);
                addr6.sin6_port = htons(socks5_port);
            }
        } else {
            if (version == 4) {
                addr4.sin_family = AF_INET;
                addr4.sin_addr.s_addr = (__be32) cur->daddr.ip4;
                addr4.sin_port = cur->dest;
            } else {
                addr6.sin6_family = AF_INET6;
                memcpy(&addr6.sin6_addr, &cur->daddr.ip6, 16);
                addr6.sin6_port = cur->dest;
            }
        }
    } else {
        log_android(ANDROID_LOG_WARN, "TCP%d redirect to %s/%u",
                    version, redirect->raddr, redirect->rport);

        if (version == 4) {
            addr4.sin_family = AF_INET;
            inet_pton(AF_INET, redirect->raddr, &addr4.sin_addr);
            addr4.sin_port = htons(redirect->rport);
        } else {
            addr6.sin6_family = AF_INET6;
            inet_pton(AF_INET6, redirect->raddr, &addr6.sin6_addr);
            addr6.sin6_port = htons(redirect->rport);
        }
    }

    // Initiate connect
    int err = connect(sock,
                      (version == 4 ? (const struct sockaddr *) &addr4
                                    : (const struct sockaddr *) &addr6),
                      (socklen_t) (version == 4
                                   ? sizeof(struct sockaddr_in)
                                   : sizeof(struct sockaddr_in6)));
    if (err < 0 && errno != EINPROGRESS) {
        log_android(ANDROID_LOG_ERROR, "connect error %d: %s", errno, strerror(errno));
        return -1;
    }

    return sock;
}

int write_syn_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 1, 1, 0, 0) < 0) {
        if (ntohs(cur->dest) == 53)
            discard_dns_frame_state(cur);
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

int write_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 0, 1, 0, 0) < 0) {
        if (ntohs(cur->dest) == 53)
            discard_dns_frame_state(cur);
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

int write_data(const struct arguments *args, struct tcp_session *cur,
               const uint8_t *buffer, size_t length) {
    if (write_tcp(args, cur, buffer, length, 0, 1, 0, 0) < 0) {
        if (ntohs(cur->dest) == 53)
            discard_dns_frame_state(cur);
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

int write_fin_ack(const struct arguments *args, struct tcp_session *cur) {
    if (write_tcp(args, cur, NULL, 0, 0, 1, 1, 0) < 0) {
        if (ntohs(cur->dest) == 53)
            discard_dns_frame_state(cur);
        cur->state = TCP_CLOSING;
        return -1;
    }
    return 0;
}

void write_rst(const struct arguments *args, struct tcp_session *cur) {
    // https://www.snellman.net/blog/archive/2016-02-01-tcp-rst/
    const int dns_stream = ntohs(cur->dest) == 53;
    int ack = 0;
    if (cur->state == TCP_LISTEN) {
        ack = 1;
        cur->remote_seq++; // SYN
    }
    write_tcp(args, cur, NULL, 0, 0, ack, 0, 1);
    if (cur->state != TCP_CLOSE)
        cur->state = TCP_CLOSING;
    if (dns_stream) {
        discard_dns_frame_state(cur);
        cur->dns_terminal = DNS_FRAME_TERMINAL_NONE;
    }
}

ssize_t write_tcp(const struct arguments *args, const struct tcp_session *cur,
                  const uint8_t *data, size_t datalen,
                  int syn, int ack, int fin, int rst) {
    size_t len;
    u_int8_t *buffer;
    struct tcphdr *tcp;
    uint16_t csum;
    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];

    // Build packet
    int optlen = (syn ? 4 + 3 + 1 : 0);
    uint8_t *options;
    if (cur->version == 4) {
        len = sizeof(struct iphdr) + sizeof(struct tcphdr) + optlen + datalen;
        buffer = ng_malloc(len, "tcp write4");
        struct iphdr *ip4 = (struct iphdr *) buffer;
        tcp = (struct tcphdr *) (buffer + sizeof(struct iphdr));
        options = buffer + sizeof(struct iphdr) + sizeof(struct tcphdr);
        if (datalen)
            memcpy(buffer + sizeof(struct iphdr) + sizeof(struct tcphdr) + optlen, data, datalen);

        // Build IP4 header
        memset(ip4, 0, sizeof(struct iphdr));
        ip4->version = 4;
        ip4->ihl = sizeof(struct iphdr) >> 2;
        ip4->tot_len = htons(len);
        ip4->ttl = IPDEFTTL;
        ip4->protocol = IPPROTO_TCP;
        ip4->saddr = cur->daddr.ip4;
        ip4->daddr = cur->saddr.ip4;

        // Calculate IP4 checksum
        ip4->check = ~calc_checksum(0, (uint8_t *) ip4, sizeof(struct iphdr));

        // Calculate TCP4 checksum
        struct ippseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ippseudo));
        pseudo.ippseudo_src.s_addr = (__be32) ip4->saddr;
        pseudo.ippseudo_dst.s_addr = (__be32) ip4->daddr;
        pseudo.ippseudo_p = ip4->protocol;
        pseudo.ippseudo_len = htons(sizeof(struct tcphdr) + optlen + datalen);

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ippseudo));
    } else {
        len = sizeof(struct ip6_hdr) + sizeof(struct tcphdr) + optlen + datalen;
        buffer = ng_malloc(len, "tcp write 6");
        struct ip6_hdr *ip6 = (struct ip6_hdr *) buffer;
        tcp = (struct tcphdr *) (buffer + sizeof(struct ip6_hdr));
        options = buffer + sizeof(struct ip6_hdr) + sizeof(struct tcphdr);
        if (datalen)
            memcpy(buffer + sizeof(struct ip6_hdr) + sizeof(struct tcphdr) + optlen, data, datalen);

        // Build IP6 header
        memset(ip6, 0, sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(len - sizeof(struct ip6_hdr));
        ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_TCP;
        ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = IPDEFTTL;
        ip6->ip6_ctlun.ip6_un2_vfc = 0x60;
        memcpy(&(ip6->ip6_src), &cur->daddr.ip6, 16);
        memcpy(&(ip6->ip6_dst), &cur->saddr.ip6, 16);

        // Calculate TCP6 checksum
        struct ip6_hdr_pseudo pseudo;
        memset(&pseudo, 0, sizeof(struct ip6_hdr_pseudo));
        memcpy(&pseudo.ip6ph_src, &ip6->ip6_dst, 16);
        memcpy(&pseudo.ip6ph_dst, &ip6->ip6_src, 16);
        pseudo.ip6ph_len = ip6->ip6_ctlun.ip6_un1.ip6_un1_plen;
        pseudo.ip6ph_nxt = ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt;

        csum = calc_checksum(0, (uint8_t *) &pseudo, sizeof(struct ip6_hdr_pseudo));
    }


    // Build TCP header
    memset(tcp, 0, sizeof(struct tcphdr));
    tcp->source = cur->dest;
    tcp->dest = cur->source;
    tcp->seq = htonl(cur->local_seq);
    tcp->ack_seq = htonl((uint32_t) (cur->remote_seq));
    tcp->doff = (__u16) ((sizeof(struct tcphdr) + optlen) >> 2);
    tcp->syn = (__u16) syn;
    tcp->ack = (__u16) ack;
    tcp->fin = (__u16) fin;
    tcp->rst = (__u16) rst;
    tcp->window = htons(cur->recv_window >> cur->recv_scale);

    if (!tcp->ack)
        tcp->ack_seq = 0;

    // TCP options
    if (syn) {
        // In tethering compatibility mode, advertise a clamped MSS to the peer
        // so it sizes its segments to fit constrained upstream paths without
        // relying on PMTUD ICMP (#478).
        // The MSS option value goes on the wire in network byte order (the
        // parser reads it with ntohs, see handle SYN above); htons is required
        // here for the clamp to take effect - the value must not be written in
        // host order.
        uint16_t mss = get_default_mss(cur->version);
        if (args->ctx->tcp_mss_clamp && mss > TCP_MSS_CLAMP)
            mss = TCP_MSS_CLAMP;

        *(options) = 2; // MSS
        *(options + 1) = 4; // total option length
        *((uint16_t *) (options + 2)) = htons(mss);

        *(options + 4) = 3; // window scale
        *(options + 5) = 3; // total option length
        *(options + 6) = cur->recv_scale;

        *(options + 7) = 0; // End, padding
    }

    // Continue checksum
    csum = calc_checksum(csum, (uint8_t *) tcp, sizeof(struct tcphdr));
    csum = calc_checksum(csum, options, (size_t) optlen);
    csum = calc_checksum(csum, data, datalen);
    tcp->check = ~csum;

    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? (const void *) &cur->saddr.ip4 : (const void *) &cur->saddr.ip6,
              source, sizeof(source));
    inet_ntop(cur->version == 4 ? AF_INET : AF_INET6,
              cur->version == 4 ? (const void *) &cur->daddr.ip4 : (const void *) &cur->daddr.ip6,
              dest, sizeof(dest));

    // Send packet
    log_android(ANDROID_LOG_DEBUG,
                "TCP sending%s%s%s%s to tun %s/%u seq %u ack %u data %u",
                (tcp->syn ? " SYN" : ""),
                (tcp->ack ? " ACK" : ""),
                (tcp->fin ? " FIN" : ""),
                (tcp->rst ? " RST" : ""),
                dest, ntohs(tcp->dest),
                ntohl(tcp->seq) - cur->local_start,
                ntohl(tcp->ack_seq) - cur->remote_start,
                datalen);

    ssize_t res = write(args->tun, buffer, len);

    // Write pcap record
    if (res >= 0) {
        if (pcap_file != NULL)
            write_pcap_rec(buffer, (size_t) res);
    } else
        log_android(ANDROID_LOG_ERROR, "TCP write%s%s%s%s data %d error %d: %s",
                    (tcp->syn ? " SYN" : ""),
                    (tcp->ack ? " ACK" : ""),
                    (tcp->fin ? " FIN" : ""),
                    (tcp->rst ? " RST" : ""),
                    datalen,
                    errno, strerror((errno)));

    ng_free(buffer, __FILE__, __LINE__);

    if (res != len) {
        log_android(ANDROID_LOG_ERROR, "TCP write %d/%d", res, len);
        return -1;
    }

    return res;
}
