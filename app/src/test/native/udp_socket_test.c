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
    return 0;
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
    check_udp_socket(&args, &event);

    CHECK(session.udp.state == expected_state, message);
    CHECK(write_calls == 1, message);
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

int main(void) {
    check_zero_length_datagram(12345, UDP_ACTIVE, 0,
                               "empty non-DNS datagram stays active and is forwarded");
    check_zero_length_datagram(53, UDP_FINISHING, 0,
                               "empty DNS datagram keeps the one-response lifecycle");

    if (failures != 0)
        return 1;

    puts("udp_socket_test: all tests passed");
    return 0;
}
