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
static int live_allocations;
static int epoll_result;
static int close_calls;
static int connect_calls;
static int last_connect_family;
static struct sockaddr_storage last_connect_address;
static socklen_t last_connect_length;
static size_t send_limit;
static int credential_logged;

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
    live_allocations++;
    return ptr;
}

void ng_free(void *ptr, const char *file, int line) {
    (void) file;
    (void) line;
    if (ptr) live_allocations--;
    free(ptr);
}

int compare_u32(uint32_t first, uint32_t second) {
    if (first == second)
        return 0;
    return (int32_t) (first - second) < 0 ? -1 : 1;
}

char *hex(const u_int8_t *data, const size_t len) {
    char *result = malloc(len * 2 + 1);
    if (!result) abort();
    for (size_t i = 0; i < len; i++) sprintf(result + i * 2, "%02x", data[i]);
    result[len * 2] = 0;
    live_allocations++;
    return result;
}

void log_android(int priority, const char *format, ...) {
    (void) priority;
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    if (*socks5_password != 0 && (strstr(message, socks5_password) != NULL || strstr(message, "736563726574") != NULL))
        credential_logged = 1;
}

long long get_ms(void) {
    return 0;
}

void account_usage(const struct arguments *args, jint version, jint protocol,
                   const char *daddr, jint dport, jint uid, jlong sent,
                   jlong received) {
    (void) args;
    (void) version;
    (void) protocol;
    (void) daddr;
    (void) dport;
    (void) uid;
    (void) sent;
    (void) received;
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
    return epoll_result;
}

int __real_close(int file_descriptor);
int __wrap_close(int file_descriptor) {
    (void) file_descriptor;
    close_calls++;
    return __real_close(file_descriptor);
}

int __wrap_connect(int socket, const struct sockaddr *address,
                   socklen_t address_length) {
    (void) socket;
    connect_calls++;
    last_connect_family = address->sa_family;
    last_connect_length = address_length;
    memset(&last_connect_address, 0, sizeof(last_connect_address));
    if (address_length <= sizeof(last_connect_address))
        memcpy(&last_connect_address, address, address_length);
    errno = EINPROGRESS;
    return -1;
}

#ifdef __linux__
ssize_t __real_send(int socket, const void *buffer, size_t length, int flags);
#endif

ssize_t __wrap_send(int socket, const void *buffer, size_t length, int flags) {
    size_t allowed = length;
    if (send_limit > 0 && allowed > send_limit)
        allowed = send_limit;
#ifdef __linux__
    return __real_send(socket, buffer, allowed, flags);
#else
    return send(socket, buffer, allowed, flags);
#endif
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


static size_t make_syn_packet(uint8_t *packet, uint32_t seq,
                              const uint8_t *data, size_t len) {
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
    tcp->doff = sizeof(*tcp) >> 2;
    tcp->syn = 1;
    tcp->psh = 1;
    tcp->window = htons(65535);
    if (len > 0)
        memcpy(packet + sizeof(*ip) + sizeof(*tcp), data, len);
    return sizeof(*ip) + sizeof(*tcp) + len;
}

static void test_tcp_epoll_add_failure_does_not_retain_session(void) {
    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    struct allowed redirect = {0};
    snprintf(redirect.raddr, sizeof(redirect.raddr), "%s", "192.0.2.3");
    redirect.rport = 80;

    uint8_t packet[256];
    size_t length = make_syn_packet(packet, 800, (const uint8_t *) "x", 1);
    epoll_result = -1;
    close_calls = 0;
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, &redirect, -1) == 0,
          "TCP rejects a session when epoll admission fails");
    CHECK(context.ng_session == NULL && close_calls == 1,
          "TCP epoll failure closes and frees the session before linking it");
    CHECK(live_allocations == 0, "failed admission releases all session allocations");
    epoll_result = 0;
}

static void test_tcp_connect_address_is_zero_initialised(void) {
    struct tcp_session session = {0};
    session.version = 4;
    session.daddr.ip4 = inet_addr("198.51.100.10");
    session.dest = htons(443);
    struct arguments args = {0};

    connect_calls = 0;
    int socket = open_tcp_socket(&args, &session, NULL);
    CHECK(socket >= 0,
          "TCP opener accepts a valid IPv4 session");
    CHECK(connect_calls == 1 && last_connect_family == AF_INET,
          "TCP opener connects with an IPv4 sockaddr");
    if (last_connect_family == AF_INET) {
        const struct sockaddr_in *address =
                (const struct sockaddr_in *) &last_connect_address;
        CHECK(address->sin_addr.s_addr == session.daddr.ip4 &&
                      address->sin_port == session.dest,
              "TCP opener preserves the IPv4 destination");
        CHECK(address->sin_zero[0] == 0 && address->sin_zero[sizeof(address->sin_zero) - 1] == 0,
              "TCP opener zero-initialises IPv4 sockaddr padding");
    }
    if (socket >= 0)
        close(socket);

    session.version = 6;
    inet_pton(AF_INET6, "2001:db8::10", &session.daddr.ip6);
    session.dest = htons(443);
    connect_calls = 0;
    int socket6 = open_tcp_socket(&args, &session, NULL);
    CHECK(socket6 >= 0 && connect_calls == 1 && last_connect_family == AF_INET6,
          "TCP opener connects with an IPv6 sockaddr");
    if (last_connect_family == AF_INET6) {
        const struct sockaddr_in6 *address6 =
                (const struct sockaddr_in6 *) &last_connect_address;
        CHECK(memcmp(&address6->sin6_addr, &session.daddr.ip6,
                     sizeof(address6->sin6_addr)) == 0 &&
                      address6->sin6_port == session.dest,
              "TCP opener preserves the IPv6 destination");
        CHECK(address6->sin6_flowinfo == 0 && address6->sin6_scope_id == 0,
              "TCP opener zero-initialises IPv6 sockaddr fields");
    }
    if (socket6 >= 0)
        close(socket6);
}

static void test_authentication_does_not_log_credentials(void) {
    int pair[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0, "authentication socketpair");
    if (failures) return;
    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, pair[0], TCP_LISTEN);
    make_args(&args, &context, &session, -1);
    session.tcp.socks5 = SOCKS5_AUTH;
    strcpy(socks5_username, "user");
    strcpy(socks5_password, "secret");
    credential_logged = 0;
    struct epoll_event event = {.events = EPOLLOUT, .data.ptr = &session};
    check_tcp_socket(&args, &event, -1);
    uint8_t actual[64] = {0};
    const uint8_t expected[] = {1, 4, 'u', 's', 'e', 'r', 6, 's', 'e', 'c', 'r', 'e', 't'};
    ssize_t count = recv(pair[1], actual, sizeof(actual), MSG_DONTWAIT);
    CHECK(count == sizeof(expected) && memcmp(actual, expected, sizeof(expected)) == 0,
          "removing credential logs preserves authentication bytes");
    CHECK(!credential_logged, "password is absent from plain and hex logs");
    socks5_username[0] = socks5_password[0] = 0;
    close(pair[0]); close(pair[1]);
}

int main(void) {
    test_authentication_does_not_log_credentials();
    test_tcp_epoll_add_failure_does_not_retain_session();
    test_tcp_connect_address_is_zero_initialised();
    if (failures) return 1;
    puts("tcp_defensive_test: all tests passed");
    return 0;
}
