#include <arpa/inet.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

static int failures;
static int live_allocations;
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
static struct sockaddr_storage last_address;

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
    void *pointer = calloc(1, byte_count);
    if (!pointer) abort();
    live_allocations++;
    return pointer;
}

void ng_free(void *pointer, const char *file, int line) {
    (void) file;
    (void) line;
    if (pointer) live_allocations--;
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
    memcpy(&last_address, destination, destination_length);
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

static jboolean send_test_packet(struct context *ctx, const uint8_t *packet,
                                 size_t length, struct allowed *redirect) {
    struct arguments args = {0};
    args.ctx = ctx;
    return handle_udp(&args, packet, length,
                      packet + (packet[0] >> 4 == 4 ? IPV4_HEADER_SIZE
                                                   : sizeof(struct ip6_hdr)),
                      10001, redirect, 99);
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

    CHECK(live_allocations == 0, "failed admission releases all session allocations");
    epoll_result = 0;
}

static void test_send_addresses(void) {
    for (int version = 4; version <= 6; version += 2) {
        struct context ctx = {0};
        _Alignas(struct ip6_hdr) uint8_t packet[64] = {0};
        size_t len = version == 4 ? make_udp_packet(packet, 45001, 6001)
            : make_udp_packet6(packet, "2001:db8::1", "2001:db8::2", 45001, 6001);
        CHECK(send_test_packet(&ctx, packet, len, NULL) == 1, "UDP admits and sends packet");
        CHECK(last_sendto_port == htons(6001), "UDP preserves destination port");
        if (version == 4) {
            struct sockaddr_in expected = {0};
            expected.sin_family = AF_INET;
            expected.sin_port = htons(6001);
            expected.sin_addr.s_addr = ((struct iphdr *)packet)->daddr;
            CHECK(memcmp(&last_address, &expected, sizeof(expected)) == 0,
                  "IPv4 destination and all padding match");
        } else {
            struct sockaddr_in6 expected = {0};
            expected.sin6_family = AF_INET6;
            expected.sin6_port = htons(6001);
            inet_pton(AF_INET6, "2001:db8::2", &expected.sin6_addr);
            CHECK(memcmp(&last_address, &expected, sizeof(expected)) == 0,
                  "IPv6 destination, scope and flow fields match");
        }
        if (ctx.ng_session) {close(ctx.ng_session->socket); ng_free(ctx.ng_session, __FILE__, __LINE__);}
    }
}

int main(void) {
    test_epoll_add_failure_does_not_retain_session();
    test_send_addresses();
    if (failures) return 1;
    puts("udp_defensive_test: all tests passed");
    return 0;
}
