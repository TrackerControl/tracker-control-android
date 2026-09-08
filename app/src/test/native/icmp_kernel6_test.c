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

static uint16_t checksum6(const struct in6_addr *source,
                          const struct in6_addr *destination,
                          const uint8_t *data, size_t length) {
    struct ip6_hdr_pseudo pseudo = {0};
    pseudo.ip6ph_src = *source;
    pseudo.ip6ph_dst = *destination;
    pseudo.ip6ph_len = htonl((uint32_t) length);
    pseudo.ip6ph_nxt = IPPROTO_ICMPV6;
    return calc_checksum(calc_checksum(0, (uint8_t *) &pseudo, sizeof(pseudo)),
                         data, length);
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

    uint8_t packet[56] = {0};
    struct ip6_hdr *ip6 = (struct ip6_hdr *) packet;
    ip6->ip6_ctlun.ip6_un2_vfc = IPV6_VERSION;
    ip6->ip6_ctlun.ip6_un1.ip6_un1_plen = htons(16);
    ip6->ip6_ctlun.ip6_un1.ip6_un1_nxt = IPPROTO_ICMPV6;
    ip6->ip6_ctlun.ip6_un1.ip6_un1_hlim = 3;
    inet_pton(AF_INET6, "fd00::1", &ip6->ip6_src);
    inet_pton(AF_INET6, "::1", &ip6->ip6_dst);
    struct in6_addr packet_source;
    struct in6_addr packet_destination;
    memcpy(&packet_source, &ip6->ip6_src, sizeof(packet_source));
    memcpy(&packet_destination, &ip6->ip6_dst, sizeof(packet_destination));
    struct icmp6_hdr *echo = (struct icmp6_hdr *) (packet + 40);
    echo->icmp6_type = ICMP6_ECHO_REQUEST;
    echo->icmp6_id = htons(4321);
    echo->icmp6_seq = htons(7);
    memset(packet + 48, 0xab, 8);
    echo->icmp6_cksum = ~checksum6(&packet_source, &packet_destination,
                                   packet + 40, 16);
    uint8_t original[sizeof(packet)];
    memcpy(original, packet, sizeof(packet));

    assert(handle_icmp(&args, packet, sizeof(packet), packet + 40,
                       10000, epoll_fd));
    struct ng_session *session = context.ng_session;
    assert(session != NULL);
    struct sockaddr_in6 local = {0};
    socklen_t local_length = sizeof(local);
    assert(getsockname(session->socket, (struct sockaddr *) &local,
                       &local_length) == 0);
    int hops = 0;
    socklen_t hops_length = sizeof(hops);
    assert(getsockopt(session->socket, IPPROTO_IPV6, IPV6_UNICAST_HOPS,
                      &hops, &hops_length) == 0 && hops == 3);
    assert(memcmp(packet, original, sizeof(packet)) == 0);
    struct epoll_event error_only = { .events = EPOLLERR, .data.ptr = session };
    assert(epoll_ctl(epoll_fd, EPOLL_CTL_MOD, session->socket, &error_only) == 0);

    uint8_t error_packet[64] = {0};
    struct icmp6_hdr *error = (struct icmp6_hdr *) error_packet;
    error->icmp6_type = ICMP6_TIME_EXCEEDED;
    memcpy(error_packet + 8, original, sizeof(original));
    struct ip6_hdr *quoted = (struct ip6_hdr *) (error_packet + 8);
    inet_pton(AF_INET6, "::1", &quoted->ip6_src);
    struct in6_addr quoted_source;
    struct in6_addr quoted_destination;
    memcpy(&quoted_source, &quoted->ip6_src, sizeof(quoted_source));
    memcpy(&quoted_destination, &quoted->ip6_dst, sizeof(quoted_destination));
    struct icmp6_hdr *quoted_echo = (struct icmp6_hdr *) (error_packet + 48);
    quoted_echo->icmp6_id = local.sin6_port;
    quoted_echo->icmp6_cksum = 0;
    quoted_echo->icmp6_cksum = ~checksum6(&quoted_source, &quoted_destination,
                                          (uint8_t *) quoted_echo, 16);

    int raw = socket(AF_INET6, SOCK_RAW, IPPROTO_ICMPV6);
    assert(raw >= 0);
    struct sockaddr_in6 destination = { .sin6_family = AF_INET6 };
    inet_pton(AF_INET6, "::1", &destination.sin6_addr);
    assert(sendto(raw, error_packet, sizeof(error_packet), 0,
                  (struct sockaddr *) &destination, sizeof(destination)) ==
           (ssize_t) sizeof(error_packet));

    struct epoll_event event;
    assert(epoll_wait(epoll_fd, &event, 1, 1000) == 1);
    assert(event.events & EPOLLERR);
    check_icmp_socket(&args, &event);

    uint8_t output[512];
    ssize_t output_length = recv(tun[1], output, sizeof(output), 0);
    assert(output_length >= 96);
    struct ip6_hdr *output_ip = (struct ip6_hdr *) output;
    assert(memcmp(&output_ip->ip6_src, &destination.sin6_addr, 16) == 0);
    assert(memcmp(&output_ip->ip6_dst, &ip6->ip6_src, 16) == 0);
    assert(output[40] == ICMP6_TIME_EXCEEDED);
    struct in6_addr output_source;
    struct in6_addr output_destination;
    memcpy(&output_source, &output_ip->ip6_src, sizeof(output_source));
    memcpy(&output_destination, &output_ip->ip6_dst, sizeof(output_destination));
    assert(checksum6(&output_source, &output_destination,
                     output + 40, output_length - 40) == 0xffff);
    assert(memcmp(output + 48, original, sizeof(original)) == 0);
    assert(session->icmp.stop == 0);
    puts("icmp_kernel6_test: IPV6_RECVERR, quote, hop limit and checksum passed");
    close(raw);
    close(session->socket);
    free(session);
    close(epoll_fd);
    close(tun[0]);
    close(tun[1]);
    return 0;
}
