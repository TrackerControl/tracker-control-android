#include <arpa/inet.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

static int failures;
static int write_calls;
static size_t written_length;
static uint8_t written_packet[2048];
static int parse_calls;
static int close_calls;
static ssize_t recv_result;
static int sendto_calls;

#define IPV4_HEADER_SIZE 20u
#define UDP_HEADER_SIZE 8u

FILE *pcap_file;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                      \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t byte_count, const char *tag) {
    (void) tag;
    return calloc(1, byte_count);
}

void ng_free(void *pointer, const char *file, int line) {
    (void) file;
    (void) line;
    free(pointer);
}

void write_pcap_rec(const uint8_t *buffer, size_t length) {
    (void) buffer;
    (void) length;
}

void parse_dns_response(const struct arguments *args, const struct ng_session *session,
                        uint8_t *data, size_t *data_length) {
    (void) args;
    (void) session;
    (void) data;
    (void) data_length;
    parse_calls++;
}

ssize_t __wrap_recv(int socket, void *buffer, size_t length, int flags) {
    (void) socket;
    (void) buffer;
    (void) length;
    (void) flags;
    if (recv_result > 0 && (size_t) recv_result <= length)
        memset(buffer, 0, (size_t) recv_result);
    return recv_result;
}

ssize_t __wrap_write(int file_descriptor, const void *buffer, size_t length) {
    (void) file_descriptor;
    if (length <= sizeof(written_packet))
        memcpy(written_packet, buffer, length);
    write_calls++;
    written_length = length;
    return (ssize_t) length;
}

int __wrap_close(int file_descriptor) {
    (void) file_descriptor;
    close_calls++;
    return 0;
}

int protect_socket(const struct arguments *args, int socket) {
    (void) args;
    (void) socket;
    return 0;
}

int check_dhcp(const struct arguments *args, const struct udp_session *session,
               const uint8_t *data, const size_t data_length) {
    (void) args;
    (void) session;
    (void) data;
    (void) data_length;
    return -1;
}

int epoll_ctl(int epoll_fd, int operation, int descriptor,
              struct epoll_event *event) {
    (void) epoll_fd;
    (void) operation;
    (void) descriptor;
    (void) event;
    return 0;
}

ssize_t __wrap_sendto(int socket, const void *buffer, size_t length, int flags,
                      const struct sockaddr *destination, socklen_t destination_length) {
    (void) socket;
    (void) buffer;
    (void) flags;
    (void) destination;
    (void) destination_length;
    sendto_calls++;
    return (ssize_t) length;
}

void account_usage(const struct arguments *args, jint version, jint protocol,
                   const char *destination, jint port, jint uid,
                   jlong sent, jlong received) {
    (void) args;
    (void) version;
    (void) protocol;
    (void) destination;
    (void) port;
    (void) uid;
    (void) sent;
    (void) received;
}

static void check_zero_length_datagram(uint16_t destination, uint8_t expected_state,
                                       int expected_parse_calls, const char *message) {
    struct ng_session session = {0};
    session.socket = 42;
    session.udp.version = 4;
    session.udp.mss = 64;
    session.udp.saddr.ip4 = inet_addr("192.0.2.1");
    session.udp.daddr.ip4 = inet_addr("198.51.100.1");
    session.udp.source = htons(40000);
    session.udp.dest = htons(destination);
    session.udp.state = UDP_ACTIVE;

    struct epoll_event event = {0};
    event.events = EPOLLIN;
    event.data.ptr = &session;

    struct arguments args = {0};
    args.tun = 17;

    write_calls = 0;
    written_length = SIZE_MAX;
    parse_calls = 0;
    close_calls = 0;
    recv_result = 0;
    check_udp_socket(&args, &event);

    // A DNS datagram must leave the mapping active so a second query/reply on
    // the same five-tuple can use the existing socket.
    if (destination == 53)
        check_udp_socket(&args, &event);

    CHECK(session.udp.state == expected_state, message);
    CHECK(write_calls == (destination == 53 ? 2 : 1), message);
    CHECK(close_calls == 0, message);
    CHECK(written_length == IPV4_HEADER_SIZE + UDP_HEADER_SIZE, message);
    CHECK(parse_calls == expected_parse_calls, message);

    uint16_t ip_length;
    uint16_t udp_length;
    uint16_t udp_source;
    uint16_t udp_destination;
    memcpy(&ip_length, written_packet + 2, sizeof(ip_length));
    memcpy(&udp_length, written_packet + IPV4_HEADER_SIZE + 4, sizeof(udp_length));
    memcpy(&udp_source, written_packet + IPV4_HEADER_SIZE, sizeof(udp_source));
    memcpy(&udp_destination, written_packet + IPV4_HEADER_SIZE + 2,
           sizeof(udp_destination));
    CHECK(ntohs(ip_length) == written_length, message);
    CHECK(ntohs(udp_length) == UDP_HEADER_SIZE, message);
    CHECK(udp_source == session.udp.dest, message);
    CHECK(udp_destination == session.udp.source, message);
}

static void test_dns_tuple_reuse(void) {
    struct ng_session session = {0};
    session.socket = 42;
    session.udp.version = 4;
    session.udp.mss = 64;
    session.udp.saddr.ip4 = inet_addr("192.0.2.1");
    session.udp.daddr.ip4 = inet_addr("198.51.100.1");
    session.udp.source = htons(40000);
    session.udp.dest = htons(53);
    session.udp.state = UDP_ACTIVE;

    struct epoll_event event = {0};
    event.events = EPOLLIN;
    event.data.ptr = &session;
    struct arguments args = {0};
    args.tun = 17;

    write_calls = 0;
    parse_calls = 0;
    close_calls = 0;
    recv_result = 4;
    check_udp_socket(&args, &event);
    check_udp_socket(&args, &event);

    CHECK(session.udp.state == UDP_ACTIVE,
          "a DNS reply leaves the tuple active for another outstanding query");
    CHECK(write_calls == 2 && written_length == IPV4_HEADER_SIZE + UDP_HEADER_SIZE + 4,
          "two DNS replies on one tuple reach the tun");
    CHECK(parse_calls == 2,
          "each DNS reply is parsed while the socket remains reusable");
    CHECK(close_calls == 0,
          "reusable DNS mapping does not close its socket after the first reply");
    recv_result = 0;
}

static size_t make_udp_packet(uint8_t *packet, uint16_t source, uint16_t destination) {
    memset(packet, 0, IPV4_HEADER_SIZE + UDP_HEADER_SIZE);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = IPV4_HEADER_SIZE >> 2;
    ip4->protocol = IPPROTO_UDP;
    ip4->saddr = inet_addr("192.0.2.1");
    ip4->daddr = inet_addr("198.51.100.1");

    struct udphdr *udp = (struct udphdr *) (packet + IPV4_HEADER_SIZE);
    udp->source = htons(source);
    udp->dest = htons(destination);
    udp->len = htons(UDP_HEADER_SIZE);
    return IPV4_HEADER_SIZE + UDP_HEADER_SIZE;
}

static int count_sessions(const struct context *ctx, int state) {
    int count = 0;
    for (const struct ng_session *s = ctx->ng_session; s != NULL; s = s->next)
        if (s->protocol == IPPROTO_UDP && (state < 0 || s->udp.state == state))
            count++;
    return count;
}

static int contains_session(const struct context *ctx, const struct ng_session *needle) {
    for (const struct ng_session *s = ctx->ng_session; s != NULL; s = s->next)
        if (s == needle)
            return 1;
    return 0;
}

static void free_test_sessions(struct context *ctx, struct ng_session *active) {
    struct ng_session *s = ctx->ng_session;
    while (s != NULL) {
        struct ng_session *next = s->next;
        if (s != active)
            ng_free(s, __FILE__, __LINE__);
        s = next;
    }
    ctx->ng_session = NULL;
}

static void test_blocked_cap_and_active_preservation(void) {
    struct context ctx = {0};
    struct arguments args = {0};
    args.ctx = &ctx;

    struct ng_session active = {0};
    active.protocol = IPPROTO_UDP;
    active.udp.version = 4;
    active.udp.state = UDP_ACTIVE;
    active.next = NULL;
    ctx.ng_session = &active;

    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet(packet, 41000, 12345);
    block_udp(&args, packet, packet_length,
              packet + IPV4_HEADER_SIZE, 10001);
    CHECK(get_udp_session_state(&args, packet, packet + IPV4_HEADER_SIZE) == UDP_BLOCKED,
          "repeated blocked five-tuple hits the retained negative entry");

    // Churn more unique blocked ports than the cap. Only blocked nodes may be
    // evicted; the active session must remain linked and usable.
    for (int i = 0; i < UDP_BLOCKED_MAX + 8; i++) {
        packet_length = make_udp_packet(packet, (uint16_t) (42000 + i), 12345);
        block_udp(&args, packet, packet_length,
                  packet + IPV4_HEADER_SIZE, 10001);
    }

    CHECK(count_sessions(&ctx, UDP_BLOCKED) == UDP_BLOCKED_MAX,
          "blocked UDP retention is independently capped");
    CHECK(count_sessions(&ctx, UDP_ACTIVE) == 1,
          "blocked eviction preserves active UDP sessions");
    CHECK(contains_session(&ctx, &active),
          "active UDP session remains in the linked list");

    free_test_sessions(&ctx, &active);
}

static void test_closed_dns_tuple_reopens(void) {
    struct context ctx = {0};
    struct arguments args = {0};
    args.ctx = &ctx;
    args.fwd53 = 1;

    struct ng_session *closed = ng_malloc(sizeof(struct ng_session), "closed dns");
    closed->protocol = IPPROTO_UDP;
    closed->socket = -1;
    closed->udp.version = 4;
    closed->udp.saddr.ip4 = inet_addr("192.0.2.1");
    closed->udp.daddr.ip4 = inet_addr("198.51.100.1");
    closed->udp.source = htons(43000);
    closed->udp.dest = htons(53);
    closed->udp.state = UDP_CLOSED;
    ctx.ng_session = closed;

    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet(packet, 43000, 53);
    sendto_calls = 0;
    CHECK(handle_udp(&args, packet, packet_length,
                     packet + IPV4_HEADER_SIZE, 10001, NULL, 99) == 1,
          "a new query can reopen a retained closed DNS tuple");
    CHECK(ctx.ng_session != closed && ctx.ng_session != NULL &&
          ctx.ng_session->udp.state == UDP_ACTIVE,
          "closed DNS retention is replaced by a fresh active mapping");
    CHECK(sendto_calls == 1,
          "the query is sent through the fresh DNS socket");

    struct ng_session *active = ctx.ng_session;
    active->socket = -1;
    ng_free(active, __FILE__, __LINE__);
    ctx.ng_session = NULL;
}

int main(void) {
    check_zero_length_datagram(12345, UDP_ACTIVE, 0,
                               "empty non-DNS datagram stays active and is forwarded");
    check_zero_length_datagram(53, UDP_ACTIVE, 0,
                               "empty DNS datagrams keep a reusable tuple active");
    test_dns_tuple_reuse();
    test_blocked_cap_and_active_preservation();
    test_closed_dns_tuple_reopens();

    if (failures != 0)
        return 1;

    puts("udp_socket_test: all tests passed");
    return 0;
}
