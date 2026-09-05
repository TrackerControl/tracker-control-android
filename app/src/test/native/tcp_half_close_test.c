#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "netguard.h"

static int failures;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                      \
            failures++;                                                     \
        }                                                                   \
    } while (0)

FILE *pcap_file;
char socks5_addr[INET6_ADDRSTRLEN + 1];
int socks5_port;
char socks5_username[127 + 1];
char socks5_password[127 + 1];

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    void *ptr = malloc(size);
    if (ptr == NULL)
        abort();
    return ptr;
}

void ng_free(void *ptr, const char *file, int line) {
    (void) file;
    (void) line;
    free(ptr);
}

int compare_u32(uint32_t first, uint32_t second) {
    if (first == second)
        return 0;
    return (int32_t) (first - second) < 0 ? -1 : 1;
}

char *hex(const u_int8_t *data, const size_t len) {
    (void) data;
    char *result = malloc(len * 3 + 1);
    if (result == NULL)
        abort();
    memset(result, 0, len * 3 + 1);
    return result;
}

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    uint32_t sum = start;
    for (size_t i = 0; i < length; i++)
        sum += buffer[i];
    return (uint16_t) sum;
}

uint16_t get_default_mss(int version) {
    (void) version;
    return 1200;
}

int protect_socket(const struct arguments *args, int socket) {
    (void) args;
    (void) socket;
    return 0;
}

int32_t get_local_port(const int sock) {
    (void) sock;
    return 0;
}

int epoll_ctl(int epoll_fd, int operation, int descriptor,
              struct epoll_event *event) {
    (void) epoll_fd;
    (void) operation;
    (void) descriptor;
    (void) event;
    return 0;
}

void write_pcap_rec(const uint8_t *buffer, size_t len) {
    (void) buffer;
    (void) len;
}

void parse_dns_response(const struct arguments *args, const struct ng_session *session,
                        uint8_t *data, size_t *datalen) {
    (void) args;
    (void) session;
    (void) data;
    (void) datalen;
}

void parse_dns_partial_response(const struct arguments *args,
                                const struct ng_session *session,
                                uint8_t *data, size_t *datalen, int *blanked) {
    (void) args;
    (void) session;
    (void) data;
    (void) datalen;
    *blanked = 0;
}

size_t dns_frame_process_stream(uint8_t *buffer, size_t bytes,
                                struct dns_stream_state *state,
                                dns_frame_parse_fn parse, void *ctx) {
    (void) buffer;
    (void) state;
    (void) parse;
    (void) ctx;
    return bytes;
}

const char *strstate(const int state) {
    (void) state;
    return "TEST";
}

static void make_args(struct arguments *args, struct context *context,
                      struct ng_session *session, int tun) {
    memset(args, 0, sizeof(*args));
    memset(context, 0, sizeof(*context));
    context->ng_session = session;
    args->tun = tun;
    args->ctx = context;
}

static void make_session(struct ng_session *session, int socket, int state) {
    memset(session, 0, sizeof(*session));
    session->protocol = IPPROTO_TCP;
    session->socket = socket;
    session->tcp.version = 4;
    session->tcp.state = state;
    session->tcp.source = htons(40000);
    session->tcp.dest = htons(80);
    session->tcp.saddr.ip4 = inet_addr("10.0.0.2");
    session->tcp.daddr.ip4 = inet_addr("192.0.2.1");
    session->tcp.mss = 1200;
    session->tcp.remote_seq = 100;
    session->tcp.remote_start = 100;
    session->tcp.local_seq = 500;
    session->tcp.local_start = 500;
    session->tcp.acked = 500;
    session->tcp.send_window = 65535;
    session->tcp.recv_window = 65535;
}

static size_t make_packet(uint8_t *packet, uint32_t seq, uint32_t ack,
                          uint16_t window, const uint8_t *data, size_t len,
                          int fin) {
    struct iphdr *ip = (struct iphdr *) packet;
    struct tcphdr *tcp = (struct tcphdr *) (packet + sizeof(*ip));
    memset(packet, 0, sizeof(*ip) + sizeof(*tcp) + len);
    ip->version = 4;
    ip->ihl = sizeof(*ip) >> 2;
    ip->protocol = IPPROTO_TCP;
    ip->saddr = inet_addr("10.0.0.2");
    ip->daddr = inet_addr("192.0.2.1");
    tcp->source = htons(40000);
    tcp->dest = htons(80);
    tcp->seq = htonl(seq);
    tcp->ack_seq = htonl(ack);
    tcp->doff = sizeof(*tcp) >> 2;
    tcp->ack = 1;
    tcp->fin = fin;
    tcp->window = htons(window);
    if (len > 0)
        memcpy(packet + sizeof(*ip) + sizeof(*tcp), data, len);
    return sizeof(*ip) + sizeof(*tcp) + len;
}

static void drain_tun(int fd) {
    uint8_t buffer[2048];
    while (read(fd, buffer, sizeof(buffer)) > 0)
        ;
}

static void test_queued_fin_waits_for_gap_and_drains(void) {
    int upstream[2];
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, upstream) == 0, "socketpair for queued FIN");
    CHECK(pipe(tun) == 0, "pipe for queued FIN ACKs");
    if (failures != 0)
        return;
    fcntl(tun[0], F_SETFL, O_NONBLOCK);

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);

    uint8_t packet[256];
    uint8_t speculative[10];
    memset(speculative, 'q', sizeof(speculative));
    size_t length = make_packet(packet, 250, 500, 65535,
                                speculative, sizeof(speculative), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "post-FIN speculative data is queued");
    CHECK(session.tcp.forward != NULL && session.tcp.forward->seq == 250,
          "out-of-order data beyond a not-yet-seen FIN is retained");

    length = make_packet(packet, 200, 500, 65535, NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "out-of-order FIN is accepted");
    CHECK(session.tcp.client_fin_seen && !session.tcp.client_fin_consumed,
          "out-of-order FIN is retained without advancing remote sequence");
    CHECK(session.tcp.forward == NULL,
          "data beyond a newly declared FIN is pruned before FIN consumption");
    CHECK(session.tcp.remote_seq == 100 && session.tcp.state == TCP_ESTABLISHED,
          "out-of-order FIN leaves the established state intact");

    uint8_t payload[100];
    memset(payload, 'x', sizeof(payload));
    length = make_packet(packet, 100, 500, 65535, payload, sizeof(payload), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "missing bytes are accepted before FIN");
    struct epoll_event event = {.events = EPOLLOUT, .data.ptr = &session};
    check_tcp_socket(&args, &event, -1);

    uint8_t received[100];
    CHECK(recv(upstream[1], received, sizeof(received), MSG_DONTWAIT) == 100,
          "queued bytes reach upstream before FIN is consumed");
    CHECK(session.tcp.remote_seq == 201 && session.tcp.client_fin_consumed,
          "FIN is consumed only after the queued bytes drain");
    CHECK(session.tcp.upstream_write_shutdown && session.tcp.client_fin_acked,
          "client FIN is ACKed after a single upstream write shutdown");
    CHECK(recv(upstream[1], received, sizeof(received), MSG_DONTWAIT) == 0,
          "upstream observes the propagated write half close");

    length = make_packet(packet, 200, 500, 65535, NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "retransmitted FIN remains idempotent");
    CHECK(session.tcp.remote_seq == 201 && session.tcp.state == TCP_CLOSE_WAIT,
          "retransmitted FIN does not advance state twice");

    drain_tun(tun[0]);
    close(upstream[0]);
    close(upstream[1]);
    close(tun[0]);
    close(tun[1]);
    clear_tcp_data(&session.tcp);
}

static void test_upstream_eof_keeps_write_half(void) {
    int upstream[2];
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, upstream) == 0, "socketpair for upstream EOF");
    CHECK(pipe(tun) == 0, "pipe for upstream EOF FIN");
    if (failures != 0)
        return;
    fcntl(tun[0], F_SETFL, O_NONBLOCK);

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);
    CHECK(shutdown(upstream[1], SHUT_WR) == 0, "peer sends EOF");

    struct epoll_event event = {.events = EPOLLIN, .data.ptr = &session};
    check_tcp_socket(&args, &event, -1);
    CHECK(session.tcp.upstream_read_eof && session.tcp.server_fin_sent,
          "upstream EOF sends one downstream FIN");
    CHECK(session.socket >= 0 && session.tcp.state == TCP_FIN_WAIT1,
          "upstream EOF keeps the socket and write direction alive");

    uint8_t packet[256];
    uint8_t payload[16];
    memset(payload, 'y', sizeof(payload));
    size_t length = make_packet(packet, 100, session.tcp.local_seq, 65535,
                                payload, sizeof(payload), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "client data after upstream EOF is accepted");
    event.events = EPOLLOUT;
    check_tcp_socket(&args, &event, -1);
    uint8_t received[16];
    CHECK(recv(upstream[1], received, sizeof(received), MSG_DONTWAIT) == 16,
          "client data remains writable after upstream EOF");
    CHECK(session.tcp.upstream_read_eof && session.tcp.server_fin_sent,
          "upstream EOF state remains stable while writes drain");

    length = make_packet(packet, 116, session.tcp.local_seq, 65535,
                         NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "simultaneous client FIN is accepted");
    CHECK(session.tcp.client_fin_consumed && session.tcp.upstream_write_shutdown,
          "simultaneous FIN shuts down only the upstream write half");
    CHECK(session.tcp.remote_seq == 117 && session.tcp.state == TCP_CLOSING,
          "simultaneous FIN closes after the packet ACKs the upstream FIN");

    drain_tun(tun[0]);
    close(upstream[0]);
    close(upstream[1]);
    close(tun[0]);
    close(tun[1]);
    clear_tcp_data(&session.tcp);
}

static void test_hup_marks_only_read_half_closed(void) {
    int upstream[2];
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, upstream) == 0, "socketpair for HUP");
    CHECK(pipe(tun) == 0, "pipe for HUP FIN");
    if (failures != 0)
        return;
    fcntl(tun[0], F_SETFL, O_NONBLOCK);

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);
    CHECK(shutdown(upstream[1], SHUT_WR) == 0, "peer half closes for HUP");

    struct epoll_event event = {.events = EPOLLHUP, .data.ptr = &session};
    check_tcp_socket(&args, &event, -1);
    CHECK(session.tcp.upstream_read_eof && session.socket >= 0,
          "HUP does not close the upstream descriptor");
    CHECK(session.tcp.server_fin_sent && session.tcp.state == TCP_FIN_WAIT1,
          "HUP follows the half-close path");

    drain_tun(tun[0]);
    close(upstream[0]);
    close(upstream[1]);
    close(tun[0]);
    close(tun[1]);
    clear_tcp_data(&session.tcp);
}

static void test_payload_after_fin_is_rejected(void) {
    int upstream[2];
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, upstream) == 0,
          "socketpair for post-FIN payload");
    CHECK(pipe(tun) == 0, "pipe for post-FIN payload");
    if (failures != 0)
        return;
    fcntl(tun[0], F_SETFL, O_NONBLOCK);

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);
    uint8_t packet[256];
    size_t length = make_packet(packet, 100, 500, 65535, NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 1, "in-order FIN is accepted");
    CHECK(session.tcp.client_fin_consumed && session.tcp.remote_seq == 101,
          "in-order FIN is consumed");

    uint8_t byte = 'z';
    length = make_packet(packet, 101, 500, 65535, &byte, 1, 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr), 1, 1,
                     NULL, -1) == 0, "payload after FIN is rejected");
    CHECK(session.tcp.state == TCP_CLOSING,
          "payload after FIN terminates the invalid session");

    drain_tun(tun[0]);
    close(upstream[0]);
    close(upstream[1]);
    close(tun[0]);
    close(tun[1]);
    clear_tcp_data(&session.tcp);
}

int main(void) {
    test_queued_fin_waits_for_gap_and_drains();
    test_upstream_eof_keeps_write_half();
    test_hup_marks_only_read_half_closed();
    test_payload_after_fin_is_rejected();

    if (failures != 0)
        return 1;

    puts("tcp_half_close_test: all tests passed");
    return 0;
}
