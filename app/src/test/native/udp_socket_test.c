#include <arpa/inet.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

static int failures;
static int write_calls;
static size_t written_length;
_Alignas(struct iphdr) static uint8_t written_packet[2048];
static int parse_calls;
static int close_calls;
static ssize_t recv_result;
static int sendto_calls;
static int socket_calls;
static int last_socket_domain;
static int protect_result;
static int epoll_result;
static int fcntl_flags;
static int fcntl_calls;
static int fcntl_fail_get;
static int fcntl_fail_set;
static int last_sendto_family;
static struct in_addr last_sendto_addr4;
static struct in6_addr last_sendto_addr6;
static uint16_t last_sendto_port;

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
    return protect_result;
}

int __wrap_socket(int domain, int type, int protocol) {
    (void) type;
    (void) protocol;
    socket_calls++;
    last_socket_domain = domain;
    return 100 + socket_calls;
}

int __wrap_fcntl(int file_descriptor, int command, ...) {
    (void) file_descriptor;
    fcntl_calls++;
    if (command == F_GETFL) {
        if (fcntl_fail_get) {
            errno = EBADF;
            return -1;
        }
        return fcntl_flags;
    }

    if (command == F_SETFL) {
        va_list args;
        va_start(args, command);
        int flags = va_arg(args, int);
        va_end(args);
        if (fcntl_fail_set) {
            errno = EIO;
            return -1;
        }
        fcntl_flags = flags;
        return 0;
    }

    errno = EINVAL;
    return -1;
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
    return epoll_result;
}

ssize_t __wrap_sendto(int socket, const void *buffer, size_t length, int flags,
                      const struct sockaddr *destination, socklen_t destination_length) {
    (void) socket;
    (void) buffer;
    (void) flags;
    sendto_calls++;
    last_sendto_family = destination->sa_family;
    if (destination->sa_family == AF_INET && destination_length >= sizeof(struct sockaddr_in)) {
        const struct sockaddr_in *addr4 = (const struct sockaddr_in *) destination;
        last_sendto_addr4 = addr4->sin_addr;
        last_sendto_port = addr4->sin_port;
    } else if (destination->sa_family == AF_INET6 &&
               destination_length >= sizeof(struct sockaddr_in6)) {
        const struct sockaddr_in6 *addr6 = (const struct sockaddr_in6 *) destination;
        last_sendto_addr6 = addr6->sin6_addr;
        last_sendto_port = addr6->sin6_port;
    }
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

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    (void) start;
    (void) buffer;
    (void) length;
    return 0;
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

static size_t make_udp_packet4(uint8_t *packet, const char *source_address,
                               const char *destination_address,
                               uint16_t source, uint16_t destination) {
    memset(packet, 0, IPV4_HEADER_SIZE + UDP_HEADER_SIZE);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = IPV4_HEADER_SIZE >> 2;
    ip4->protocol = IPPROTO_UDP;
    ip4->saddr = inet_addr(source_address);
    ip4->daddr = inet_addr(destination_address);

    struct udphdr *udp = (struct udphdr *) (packet + IPV4_HEADER_SIZE);
    udp->source = htons(source);
    udp->dest = htons(destination);
    udp->len = htons(UDP_HEADER_SIZE);
    return IPV4_HEADER_SIZE + UDP_HEADER_SIZE;
}

static size_t make_udp_packet(uint8_t *packet, uint16_t source, uint16_t destination) {
    return make_udp_packet4(packet, "192.0.2.1", "198.51.100.1", source, destination);
}

static size_t make_udp_packet6(uint8_t *packet, const char *source_address,
                               const char *destination_address,
                               uint16_t source, uint16_t destination) {
    memset(packet, 0, sizeof(struct ip6_hdr) + UDP_HEADER_SIZE);
    struct ip6_hdr *ip6 = (struct ip6_hdr *) packet;
    ip6->ip6_vfc = 0x60;
    ip6->ip6_nxt = IPPROTO_UDP;
    ip6->ip6_plen = htons(UDP_HEADER_SIZE);
    inet_pton(AF_INET6, source_address, &ip6->ip6_src);
    inet_pton(AF_INET6, destination_address, &ip6->ip6_dst);

    struct udphdr *udp = (struct udphdr *) (packet + sizeof(struct ip6_hdr));
    udp->source = htons(source);
    udp->dest = htons(destination);
    udp->len = htons(UDP_HEADER_SIZE);
    return sizeof(struct ip6_hdr) + UDP_HEADER_SIZE;
}

static void reset_sendto_capture(void) {
    socket_calls = 0;
    last_socket_domain = AF_UNSPEC;
    sendto_calls = 0;
    last_sendto_family = AF_UNSPEC;
    memset(&last_sendto_addr4, 0, sizeof(last_sendto_addr4));
    memset(&last_sendto_addr6, 0, sizeof(last_sendto_addr6));
    last_sendto_port = 0;
}

static void check_last_sendto_ipv4(const char *address, uint16_t port,
                                   const char *message) {
    CHECK(last_sendto_family == AF_INET, message);
    CHECK(last_sendto_addr4.s_addr == inet_addr(address), message);
    CHECK(last_sendto_port == htons(port), message);
}

static void check_last_sendto_ipv6(const char *address, uint16_t port,
                                   const char *message) {
    struct in6_addr expected;
    memset(&expected, 0, sizeof(expected));
    inet_pton(AF_INET6, address, &expected);
    CHECK(last_sendto_family == AF_INET6, message);
    CHECK(memcmp(&last_sendto_addr6, &expected, sizeof(expected)) == 0, message);
    CHECK(last_sendto_port == htons(port), message);
}

static struct allowed make_redirect(const char *address, uint16_t port) {
    struct allowed redirect = {0};
    strncpy(redirect.raddr, address, sizeof(redirect.raddr) - 1);
    redirect.rport = port;
    return redirect;
}

static jboolean send_test_packet(struct context *ctx, const uint8_t *packet,
                                 size_t length, struct allowed *redirect) {
    struct arguments args = {0};
    args.ctx = ctx;
    return handle_udp(&args, packet, length,
                      packet + (packet[0] >> 4 == 4 ? IPV4_HEADER_SIZE
                                                   : sizeof(struct ip6_hdr)),
                      10001, redirect, 99);
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

static void free_all_sessions(struct context *ctx) {
    struct ng_session *s = ctx->ng_session;
    while (s != NULL) {
        struct ng_session *next = s->next;
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
    CHECK(ctx.ng_session != NULL && ctx.ng_session->udp.state == UDP_ACTIVE,
          "closed DNS retention is replaced by a fresh active mapping");
    CHECK(sendto_calls == 1,
          "the query is sent through the fresh DNS socket");

    struct ng_session *active = ctx.ng_session;
    active->socket = -1;
    ng_free(active, __FILE__, __LINE__);
    ctx.ng_session = NULL;
}

static void test_persisted_ipv4_redirect(void) {
    struct context ctx = {0};
    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet4(packet, "192.0.2.10", "198.51.100.10",
                                            44000, 6000);
    struct allowed redirect = make_redirect("203.0.113.10", 5353);

    reset_sendto_capture();
    CHECK(send_test_packet(&ctx, packet, packet_length, &redirect) == 1,
          "an IPv4 redirect opens a UDP session");
    struct ng_session *session = ctx.ng_session;
    CHECK(session != NULL && session->udp.resolved_version == 4,
          "the IPv4 redirect family is persisted on the session");
    CHECK(last_socket_domain == PF_INET,
          "the IPv4 redirect opens an IPv4 socket");
    check_last_sendto_ipv4("203.0.113.10", 5353,
                           "the first IPv4 redirect reaches sendto");

    // A subsequent packet has no Java redirect result. It must reuse the
    // endpoint selected for the first packet.
    CHECK(send_test_packet(&ctx, packet, packet_length, NULL) == 1,
          "a later IPv4 packet uses the existing session");
    CHECK(sendto_calls == 2, "the repeated IPv4 packet is forwarded");
    check_last_sendto_ipv4("203.0.113.10", 5353,
                           "the IPv4 endpoint survives a NULL redirect");

    // A changed policy result must not retarget the already-open socket.
    struct allowed changed = make_redirect("203.0.113.11", 5354);
    CHECK(send_test_packet(&ctx, packet, packet_length, &changed) == 1,
          "a later IPv4 packet remains on the existing session");
    CHECK(sendto_calls == 3, "the changed-policy packet is forwarded");
    check_last_sendto_ipv4("203.0.113.10", 5353,
                           "a later redirect cannot retarget the session");
    CHECK(ctx.ng_session == session, "the original IPv4 session is reused");

    free_all_sessions(&ctx);
}

static void test_persisted_ipv6_redirect(void) {
    struct context ctx = {0};
    uint8_t packet[sizeof(struct ip6_hdr) + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet6(packet, "2001:db8::10", "2001:db8::11",
                                            44001, 6001);
    struct allowed redirect = make_redirect("2001:db8::20", 5355);

    reset_sendto_capture();
    CHECK(send_test_packet(&ctx, packet, packet_length, &redirect) == 1,
          "an IPv6 redirect opens a UDP session");
    struct ng_session *session = ctx.ng_session;
    CHECK(session != NULL && session->udp.resolved_version == 6,
          "the IPv6 redirect family is persisted on the session");
    CHECK(last_socket_domain == PF_INET6,
          "the IPv6 redirect opens an IPv6 socket");
    check_last_sendto_ipv6("2001:db8::20", 5355,
                           "the first IPv6 redirect reaches sendto");

    CHECK(send_test_packet(&ctx, packet, packet_length, NULL) == 1,
          "a later IPv6 packet uses the existing session");
    CHECK(sendto_calls == 2, "the repeated IPv6 packet is forwarded");
    check_last_sendto_ipv6("2001:db8::20", 5355,
                           "the IPv6 endpoint survives a NULL redirect");
    CHECK(ctx.ng_session == session, "the original IPv6 session is reused");

    free_all_sessions(&ctx);
}

static void test_cross_family_redirects(void) {
    struct context ipv4_context = {0};
    uint8_t ipv4_packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t ipv4_length = make_udp_packet4(ipv4_packet, "192.0.2.20", "198.51.100.20",
                                          44002, 6002);
    struct allowed ipv6_redirect = make_redirect("2001:db8::30", 5356);
    reset_sendto_capture();
    CHECK(send_test_packet(&ipv4_context, ipv4_packet, ipv4_length, &ipv6_redirect) == 1,
          "an IPv4 packet can use an IPv6 redirect");
    CHECK(ipv4_context.ng_session != NULL &&
          ipv4_context.ng_session->udp.resolved_version == 6,
          "the IPv4-to-IPv6 redirect family is persisted");
    CHECK(last_socket_domain == PF_INET6,
          "the IPv4-to-IPv6 redirect opens an IPv6 socket");
    check_last_sendto_ipv6("2001:db8::30", 5356,
                           "the IPv4-to-IPv6 redirect reaches sendto");
    free_all_sessions(&ipv4_context);

    struct context ipv6_context = {0};
    uint8_t ipv6_packet[sizeof(struct ip6_hdr) + UDP_HEADER_SIZE];
    size_t ipv6_length = make_udp_packet6(ipv6_packet, "2001:db8::40", "2001:db8::41",
                                          44003, 6003);
    struct allowed ipv4_redirect = make_redirect("203.0.113.30", 5357);
    reset_sendto_capture();
    CHECK(send_test_packet(&ipv6_context, ipv6_packet, ipv6_length, &ipv4_redirect) == 1,
          "an IPv6 packet can use an IPv4 redirect");
    CHECK(ipv6_context.ng_session != NULL &&
          ipv6_context.ng_session->udp.resolved_version == 4,
          "the IPv6-to-IPv4 redirect family is persisted");
    CHECK(last_socket_domain == PF_INET,
          "the IPv6-to-IPv4 redirect opens an IPv4 socket");
    check_last_sendto_ipv4("203.0.113.30", 5357,
                           "the IPv6-to-IPv4 redirect reaches sendto");
    free_all_sessions(&ipv6_context);
}

static void test_unredirected_reuse(void) {
    struct context ctx = {0};
    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet4(packet, "192.0.2.30", "198.51.100.30",
                                            44004, 6004);

    reset_sendto_capture();
    CHECK(send_test_packet(&ctx, packet, packet_length, NULL) == 1,
          "an unredirected UDP tuple opens a session");
    CHECK(send_test_packet(&ctx, packet, packet_length, NULL) == 1,
          "an unredirected UDP tuple is reusable");
    CHECK(sendto_calls == 2, "both unredirected datagrams are forwarded");
    check_last_sendto_ipv4("198.51.100.30", 6004,
                           "unredirected reuse keeps the original endpoint");
    CHECK(ctx.ng_session != NULL && ctx.ng_session->udp.resolved_version == 4,
          "unredirected reuse persists the original family");

    free_all_sessions(&ctx);
}

static void test_original_tuple_lookup(void) {
    struct context ctx = {0};
    uint8_t first_packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    uint8_t second_packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t first_length = make_udp_packet4(first_packet, "192.0.2.40", "198.51.100.40",
                                           44005, 6005);
    size_t second_length = make_udp_packet4(second_packet, "192.0.2.40", "198.51.100.41",
                                            44005, 6005);
    struct allowed redirect = make_redirect("203.0.113.40", 5358);

    reset_sendto_capture();
    CHECK(send_test_packet(&ctx, first_packet, first_length, &redirect) == 1,
          "the first original tuple opens a session");
    CHECK(send_test_packet(&ctx, second_packet, second_length, NULL) == 1,
          "a different original destination opens its own session");
    CHECK(count_sessions(&ctx, UDP_ACTIVE) == 2,
          "session lookup retains the original destination tuple");
    check_last_sendto_ipv4("198.51.100.41", 6005,
                           "the second original tuple uses its own endpoint");

    free_all_sessions(&ctx);
}

static void test_invalid_redirect_cleanup(void) {
    struct context ctx = {0};
    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet4(packet, "192.0.2.50", "198.51.100.50",
                                            44006, 6006);
    struct allowed invalid = make_redirect("not-an-ip-address", 5359);

    reset_sendto_capture();
    CHECK(send_test_packet(&ctx, packet, packet_length, &invalid) == 0,
          "an invalid redirect rejects session creation");
    CHECK(ctx.ng_session == NULL,
          "an invalid redirect leaves no retained session");
    CHECK(sendto_calls == 0,
          "an invalid redirect never reaches sendto");
}

static struct udp_session make_socket_session(void) {
    struct udp_session session = {0};
    session.version = 4;
    session.resolved_version = 4;
    session.daddr.ip4 = inet_addr("198.51.100.1");
    return session;
}

static void test_nonblocking_socket_and_open_cleanup(void) {
    struct arguments args = {0};
    struct udp_session session = make_socket_session();

    protect_result = 0;
    fcntl_flags = 0x200;
    fcntl_calls = 0;
    fcntl_fail_get = 0;
    fcntl_fail_set = 0;
    close_calls = 0;
    int socket = open_udp_socket(&args, &session);
    CHECK(socket >= 0, "UDP socket opens when protection and nonblocking setup succeed");
    CHECK(fcntl_calls == 2 && (fcntl_flags & O_NONBLOCK) != 0,
          "UDP socket is configured O_NONBLOCK");
    close(socket);

    protect_result = -1;
    close_calls = 0;
    socket = open_udp_socket(&args, &session);
    CHECK(socket < 0 && close_calls == 1,
          "UDP protection failure closes the newly opened descriptor");

    protect_result = 0;
    fcntl_fail_set = 1;
    close_calls = 0;
    socket = open_udp_socket(&args, &session);
    CHECK(socket < 0 && close_calls == 1,
          "UDP nonblocking failure closes the newly opened descriptor");
    fcntl_fail_set = 0;
}

static void test_epoll_add_failure_does_not_retain_session(void) {
    struct context ctx = {0};
    struct arguments args = {0};
    args.ctx = &ctx;
    args.fwd53 = 1;

    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet(packet, 45000, 6000);
    protect_result = 0;
    epoll_result = -1;
    errno = EIO;
    close_calls = 0;
    sendto_calls = 0;

    CHECK(send_test_packet(&ctx, packet, packet_length, NULL) == 0,
          "UDP rejects a session when epoll admission fails");
    CHECK(ctx.ng_session == NULL && close_calls == 1 && sendto_calls == 0,
          "UDP epoll failure closes and frees the session before linking it");

    epoll_result = 0;
}

static void test_response_original_tuple(void) {
    struct context ctx = {0};
    uint8_t packet[IPV4_HEADER_SIZE + UDP_HEADER_SIZE];
    size_t packet_length = make_udp_packet4(packet, "192.0.2.60", "198.51.100.60",
                                            44007, 6007);
    struct allowed redirect = make_redirect("2001:db8::60", 5360);
    CHECK(send_test_packet(&ctx, packet, packet_length, &redirect) == 1,
          "a cross-family session opens for response reconstruction");
    struct ng_session *session = ctx.ng_session;

    struct epoll_event event = {0};
    event.events = EPOLLIN;
    event.data.ptr = session;
    struct arguments args = {0};
    args.tun = 17;
    write_calls = 0;
    written_length = SIZE_MAX;
    recv_result = 4;
    check_udp_socket(&args, &event);
    recv_result = 0;

    CHECK(write_calls == 1 && written_length == IPV4_HEADER_SIZE + UDP_HEADER_SIZE + 4,
          "the response is reconstructed on the original IP version");
    if (written_length >= IPV4_HEADER_SIZE + UDP_HEADER_SIZE) {
        const struct iphdr *response_ip4 = (const struct iphdr *) written_packet;
        const struct udphdr *response_udp =
            (const struct udphdr *) (written_packet + IPV4_HEADER_SIZE);
        CHECK(response_ip4->saddr == inet_addr("198.51.100.60"),
              "the response source remains the original destination");
        CHECK(response_ip4->daddr == inet_addr("192.0.2.60"),
              "the response destination remains the original source");
        CHECK(response_udp->source == htons(6007),
              "the response source port remains the original destination port");
        CHECK(response_udp->dest == htons(44007),
              "the response destination port remains the original source port");
    }

    free_all_sessions(&ctx);
}

int main(void) {
    check_zero_length_datagram(12345, UDP_ACTIVE, 0,
                               "empty non-DNS datagram stays active and is forwarded");
    check_zero_length_datagram(53, UDP_ACTIVE, 0,
                               "empty DNS datagrams keep a reusable tuple active");
    test_dns_tuple_reuse();
    test_blocked_cap_and_active_preservation();
    test_closed_dns_tuple_reopens();
    test_persisted_ipv4_redirect();
    test_persisted_ipv6_redirect();
    test_cross_family_redirects();
    test_unredirected_reuse();
    test_original_tuple_lookup();
    test_invalid_redirect_cleanup();
    test_response_original_tuple();
    test_nonblocking_socket_and_open_cleanup();
    test_epoll_add_failure_does_not_retain_session();

    if (failures != 0)
        return 1;

    puts("udp_socket_test: all tests passed");
    return 0;
}
