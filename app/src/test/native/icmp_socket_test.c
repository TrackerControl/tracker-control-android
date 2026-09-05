/* Host regression tests for ICMP socket admission and nonblocking setup. */

#include <arpa/inet.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

static int failures;
static int protect_result;
static int epoll_result;
static int fcntl_flags;
static int fcntl_calls;
static int fcntl_fail_get;
static int fcntl_fail_set;
static int socket_calls;
static int close_calls;
static int sendto_calls;
static int last_sendto_family;
static struct sockaddr_storage last_sendto_address;

FILE *pcap_file;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    void *pointer = calloc(1, size);
    if (pointer == NULL)
        abort();
    return pointer;
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

int protect_socket(const struct arguments *args, int socket) {
    (void) args;
    (void) socket;
    return protect_result;
}

int __wrap_socket(int domain, int type, int protocol) {
    (void) domain;
    (void) type;
    (void) protocol;
    socket_calls++;
    return 100 + socket_calls;
}

int __wrap_close(int file_descriptor) {
    (void) file_descriptor;
    close_calls++;
    return 0;
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

int epoll_ctl(int epoll_fd, int operation, int descriptor,
              struct epoll_event *event) {
    (void) epoll_fd;
    (void) operation;
    (void) descriptor;
    (void) event;
    return epoll_result;
}

ssize_t __wrap_sendto(int socket, const void *buffer, size_t length, int flags,
                      const struct sockaddr *destination,
                      socklen_t destination_length) {
    (void) socket;
    (void) buffer;
    (void) flags;
    sendto_calls++;
    last_sendto_family = destination->sa_family;
    memset(&last_sendto_address, 0, sizeof(last_sendto_address));
    if (destination_length <= sizeof(last_sendto_address))
        memcpy(&last_sendto_address, destination, destination_length);
    return (ssize_t) length;
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    (void) buffer;
    (void) length;
    return start;
}

static struct icmp_session make_session(void) {
    struct icmp_session session = {0};
    session.version = 4;
    session.saddr.ip4 = inet_addr("192.0.2.1");
    session.daddr.ip4 = inet_addr("198.51.100.1");
    session.id = htons(0x1234);
    return session;
}

static void test_nonblocking_socket_and_open_cleanup(void) {
    struct arguments args = {0};
    struct icmp_session session = make_session();

    protect_result = 0;
    fcntl_flags = 0x200;
    fcntl_calls = 0;
    fcntl_fail_get = 0;
    fcntl_fail_set = 0;
    close_calls = 0;
    int socket = open_icmp_socket(&args, &session);
    CHECK(socket >= 0, "ICMP socket opens when protection and nonblocking setup succeed");
    CHECK(fcntl_calls == 2 && (fcntl_flags & O_NONBLOCK) != 0,
          "ICMP socket is configured O_NONBLOCK");
    close(socket);

    protect_result = -1;
    close_calls = 0;
    socket = open_icmp_socket(&args, &session);
    CHECK(socket < 0 && close_calls == 1,
          "ICMP protection failure closes the newly opened descriptor");

    protect_result = 0;
    fcntl_fail_set = 1;
    close_calls = 0;
    socket = open_icmp_socket(&args, &session);
    CHECK(socket < 0 && close_calls == 1,
          "ICMP nonblocking failure closes the newly opened descriptor");
    fcntl_fail_set = 0;
}

static size_t make_echo_packet(uint8_t *packet) {
    memset(packet, 0, sizeof(struct iphdr) + ICMP_MINLEN);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = sizeof(struct iphdr) >> 2;
    ip4->protocol = IPPROTO_ICMP;
    ip4->saddr = inet_addr("192.0.2.1");
    ip4->daddr = inet_addr("198.51.100.1");

    struct icmp *icmp = (struct icmp *) (packet + sizeof(struct iphdr));
    icmp->icmp_type = ICMP_ECHO;
    icmp->icmp_id = htons(0x1234);
    return sizeof(struct iphdr) + ICMP_MINLEN;
}

static size_t make_echo_packet6(uint8_t *packet) {
    memset(packet, 0, sizeof(struct ip6_hdr) + ICMP_MINLEN);
    struct ip6_hdr *ip6 = (struct ip6_hdr *) packet;
    ip6->ip6_ctlun.ip6_un2_vfc = IPV6_VERSION;
    ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_ICMPV6;
    ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(ICMP_MINLEN);
    inet_pton(AF_INET6, "2001:db8::1", &ip6->ip6_src);
    inet_pton(AF_INET6, "2001:db8::2", &ip6->ip6_dst);

    struct icmp *icmp = (struct icmp *) (packet + sizeof(struct ip6_hdr));
    icmp->icmp_type = ICMP6_ECHO_REQUEST;
    icmp->icmp_id = htons(0x2345);
    return sizeof(struct ip6_hdr) + ICMP_MINLEN;
}

static void test_epoll_add_failure_does_not_retain_session(void) {
    _Alignas(struct iphdr) uint8_t packet[sizeof(struct iphdr) + sizeof(struct icmp)];
    size_t length = make_echo_packet(packet);
    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    protect_result = 0;
    epoll_result = -1;
    close_calls = 0;
    sendto_calls = 0;

    CHECK(handle_icmp(&args, packet, length,
                      packet + sizeof(struct iphdr), 10001, 99) == 0,
          "ICMP rejects a session when epoll admission fails");
    CHECK(context.ng_session == NULL && close_calls == 1 && sendto_calls == 0,
          "ICMP epoll failure closes and frees the session before linking it");
    epoll_result = 0;
}

static void test_icmp_send_address_is_zero_initialised(void) {
    _Alignas(struct ip6_hdr) uint8_t packet[(sizeof(struct ip6_hdr) > sizeof(struct iphdr)
                    ? sizeof(struct ip6_hdr) : sizeof(struct iphdr)) + sizeof(struct icmp)];
    size_t length = make_echo_packet(packet);
    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    protect_result = 0;
    epoll_result = 0;
    sendto_calls = 0;

    CHECK(handle_icmp(&args, packet, length,
                      packet + sizeof(struct iphdr), 10001, 99) == 1,
          "ICMP echo is sent after successful session admission");
    CHECK(sendto_calls == 1 && last_sendto_family == AF_INET,
          "ICMP send uses an IPv4 sockaddr");
    if (last_sendto_family == AF_INET) {
        const struct sockaddr_in *address =
                (const struct sockaddr_in *) &last_sendto_address;
        CHECK(address->sin_addr.s_addr == inet_addr("198.51.100.1"),
              "ICMP send preserves the IPv4 destination");
        CHECK(address->sin_zero[0] == 0 &&
                      address->sin_zero[sizeof(address->sin_zero) - 1] == 0,
              "ICMP send zero-initialises IPv4 sockaddr padding");
    }

    struct ng_session *session = context.ng_session;
    if (session != NULL)
        ng_free(session, __FILE__, __LINE__);
    context.ng_session = NULL;

    length = make_echo_packet6(packet);
    context.ng_session = NULL;
    sendto_calls = 0;
    CHECK(handle_icmp(&args, packet, length,
                      packet + sizeof(struct ip6_hdr), 10001, 99) == 1,
          "IPv6 ICMP echo is sent after successful session admission");
    CHECK(sendto_calls == 1 && last_sendto_family == AF_INET6,
          "ICMP send uses an IPv6 sockaddr");
    if (last_sendto_family == AF_INET6) {
        const struct sockaddr_in6 *address6 =
                (const struct sockaddr_in6 *) &last_sendto_address;
        CHECK(address6->sin6_flowinfo == 0 && address6->sin6_scope_id == 0,
              "ICMP send zero-initialises IPv6 sockaddr fields");
    }
    session = context.ng_session;
    if (session != NULL)
        ng_free(session, __FILE__, __LINE__);
    context.ng_session = NULL;
}

int main(void) {
    test_nonblocking_socket_and_open_cleanup();
    test_epoll_add_failure_does_not_retain_session();
    test_icmp_send_address_is_zero_initialised();

    if (failures != 0)
        return 1;
    puts("icmp_socket_test: all tests passed");
    return 0;
}
