/* Opt-in Linux/Android kernel test: requires CAP_NET_RAW and a real network stack. */

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

FILE *pcap_file;

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    void *pointer = calloc(1, size);
    assert(pointer != NULL);
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
    return 0;
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    uint32_t sum = start;
    while (length > 1) {
        uint16_t word;
        memcpy(&word, buffer, sizeof(word));
        sum += word;
        buffer += 2;
        length -= 2;
    }
    if (length != 0)
        sum += *buffer;
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t) sum;
}

int main(void) {
    int tun[2];
    assert(socketpair(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK, 0, tun) == 0);
    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    args.tun = tun[0];
    int epoll_fd = epoll_create1(0);
    assert(epoll_fd >= 0);

    uint8_t packet[36] = {0};
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->tot_len = htons(sizeof(packet));
    ip4->ttl = 3;
    ip4->protocol = IPPROTO_ICMP;
    ip4->saddr = inet_addr("10.10.10.1");
    ip4->daddr = inet_addr("127.0.0.2");
    struct icmphdr *echo = (struct icmphdr *) (packet + 20);
    echo->type = ICMP_ECHO;
    echo->un.echo.id = htons(4321);
    echo->un.echo.sequence = htons(7);
    memset(packet + 28, 0xab, 8);
    echo->checksum = ~calc_checksum(0, packet + 20, 16);
    ip4->check = ~calc_checksum(0, packet, 20);
    uint8_t original[sizeof(packet)];
    memcpy(original, packet, sizeof(packet));

    assert(handle_icmp(&args, packet, sizeof(packet), packet + 20,
                       10000, epoll_fd));
    struct ng_session *session = context.ng_session;
    assert(session != NULL);
    struct sockaddr_in local = {0};
    socklen_t local_length = sizeof(local);
    assert(getsockname(session->socket, (struct sockaddr *) &local,
                       &local_length) == 0);
    int ttl = 0;
    local_length = sizeof(ttl);
    assert(getsockopt(session->socket, IPPROTO_IP, IP_TTL, &ttl,
                      &local_length) == 0 && ttl == 3);
    assert(memcmp(packet, original, sizeof(packet)) == 0);
    struct epoll_event error_only = { .events = EPOLLERR, .data.ptr = session };
    assert(epoll_ctl(epoll_fd, EPOLL_CTL_MOD, session->socket, &error_only) == 0);

    uint8_t error_packet[sizeof(packet) + 8] = {0};
    struct icmphdr *error = (struct icmphdr *) error_packet;
    error->type = ICMP_TIME_EXCEEDED;
    memcpy(error_packet + 8, original, sizeof(original));
    struct iphdr *quoted = (struct iphdr *) (error_packet + 8);
    quoted->saddr = inet_addr("127.0.0.1");
    quoted->check = 0;
    quoted->check = ~calc_checksum(0, (uint8_t *) quoted, 20);
    struct icmphdr *quoted_echo = (struct icmphdr *) (error_packet + 28);
    quoted_echo->un.echo.id = local.sin_port;
    quoted_echo->checksum = 0;
    quoted_echo->checksum = ~calc_checksum(0, (uint8_t *) quoted_echo, 16);
    error->checksum = ~calc_checksum(0, error_packet, sizeof(error_packet));

    int raw = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    assert(raw >= 0);
    struct sockaddr_in from = {
        .sin_family = AF_INET,
        .sin_addr.s_addr = inet_addr("127.0.0.3"),
    };
    assert(bind(raw, (struct sockaddr *) &from, sizeof(from)) == 0);
    struct sockaddr_in destination = {
        .sin_family = AF_INET,
        .sin_addr.s_addr = inet_addr("127.0.0.1"),
    };
    assert(sendto(raw, error_packet, sizeof(error_packet), 0,
                  (struct sockaddr *) &destination, sizeof(destination)) ==
           (ssize_t) sizeof(error_packet));

    struct epoll_event event;
    assert(epoll_wait(epoll_fd, &event, 1, 1000) == 1);
    assert(event.events & EPOLLERR);
    check_icmp_socket(&args, &event);

    uint8_t output[512];
    ssize_t output_length = recv(tun[1], output, sizeof(output), 0);
    assert(output_length >= 56);
    struct iphdr *output_ip = (struct iphdr *) output;
    assert(output_ip->saddr == from.sin_addr.s_addr);
    assert(output_ip->daddr == ip4->saddr);
    assert(output[20] == ICMP_TIME_EXCEEDED);
    assert(calc_checksum(0, output, 20) == 0xffff);
    assert(calc_checksum(0, output + 20, output_length - 20) == 0xffff);
    assert(memcmp(output + 28, original, sizeof(original)) == 0);
    assert(session->icmp.stop == 0);
    puts("icmp_kernel4_test: IP_RECVERR, offender, quote, TTL and checksums passed");
    close(raw);
    close(session->socket);
    free(session);
    close(epoll_fd);
    close(tun[0]);
    close(tun[1]);
    return 0;
}
