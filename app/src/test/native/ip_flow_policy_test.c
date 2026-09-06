/* Production-bound routing and policy regressions through handle_ip(). */

#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"
#include "udp_state.h"

static int failures;
static int unexpected_handler_calls;
static int policy_calls;
static int uid_calls;
static int wireguard_writes;
static int rst_writes;
static int blocked_udp_calls;
static int configured_uid = 10042;
static int policy_allowed = 1;
static int blocked_udp;
static jint last_policy_uid = -1;
static struct allowed allowed_empty;

FILE *pcap_file;
int loglevel = ANDROID_LOG_WARN;
_Atomic int wg_required = 1;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

static struct context context;
static struct arguments args;

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    return calloc(1, size);
}

void ng_free(void *pointer, const char *file, int line) {
    (void) file;
    (void) line;
    free(pointer);
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    (void) start;
    (void) buffer;
    (void) length;
    return 1;
}

void hex2bytes(const char *hex, uint8_t *buffer) {
    (void) hex;
    (void) buffer;
    unexpected_handler_calls++;
}

jint get_uid_q(const struct arguments *unused, jint version, jint protocol,
              const char *source, jint sport, const char *dest, jint dport) {
    (void) unused;
    (void) version;
    (void) protocol;
    (void) source;
    (void) sport;
    (void) dest;
    (void) dport;
    uid_calls++;
    return configured_uid;
}

jobject create_packet(const struct arguments *unused, jint version, jint protocol,
                      const char *flags, const char *source, jint sport,
                      const char *dest, jint dport, const char *data, jint uid,
                      jboolean allowed) {
    (void) unused;
    (void) version;
    (void) protocol;
    (void) flags;
    (void) source;
    (void) sport;
    (void) dest;
    (void) dport;
    (void) data;
    (void) allowed;
    last_policy_uid = uid;
    return (jobject) (uintptr_t) 1;
}

struct allowed *is_address_allowed(const struct arguments *unused,
                                   jobject objPacket) {
    (void) unused;
    (void) objPacket;
    policy_calls++;
    return policy_allowed ? &allowed_empty : NULL;
}

int write_wireguard_packet(const void *packet, size_t length,
                           ssize_t *written, int *write_errno) {
    (void) packet;
    wireguard_writes++;
    if (written != NULL)
        *written = (ssize_t) length;
    if (write_errno != NULL)
        *write_errno = 0;
    return 1;
}

ssize_t write_tcp(const struct arguments *unused, const struct tcp_session *cur,
                  const uint8_t *data, size_t datalen, int syn, int ack,
                  int fin, int rst) {
    (void) unused;
    (void) cur;
    (void) data;
    (void) datalen;
    (void) syn;
    (void) ack;
    (void) fin;
    if (!rst)
        unexpected_handler_calls++;
    else
        rst_writes++;
    return 0;
}

void write_rst(const struct arguments *unused, struct tcp_session *cur) {
    (void) unused;
    (void) cur;
    unexpected_handler_calls++;
}

int get_udp_session_state(const struct arguments *unused, const uint8_t *packet,
                          const uint8_t *payload) {
    (void) unused;
    (void) packet;
    (void) payload;
    return blocked_udp ? UDP_BLOCKED : -1;
}

void block_udp(const struct arguments *unused, const uint8_t *packet,
               size_t length, const uint8_t *payload, int uid) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    blocked_udp_calls++;
    blocked_udp = 1;
}

jboolean handle_icmp(const struct arguments *unused, const uint8_t *packet,
                     size_t length, const uint8_t *payload, int uid,
                     const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) epoll_fd;
    unexpected_handler_calls++;
    return 0;
}

jboolean handle_udp(const struct arguments *unused, const uint8_t *packet,
                    size_t length, const uint8_t *payload, int uid,
                    struct allowed *redirect, const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) redirect;
    (void) epoll_fd;
    unexpected_handler_calls++;
    return 0;
}

jboolean handle_tcp(const struct arguments *unused, const uint8_t *packet,
                    size_t length, const uint8_t *payload, int uid, int allowed,
                    struct allowed *redirect, const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) allowed;
    (void) redirect;
    (void) epoll_fd;
    unexpected_handler_calls++;
    return 0;
}

static void reset_fakes(void) {
    policy_calls = 0;
    uid_calls = 0;
    wireguard_writes = 0;
    rst_writes = 0;
    blocked_udp_calls = 0;
    unexpected_handler_calls = 0;
    last_policy_uid = -1;
    blocked_udp = 0;
    configured_uid = 10042;
    policy_allowed = 1;
    route_flow_invalidate();
}

static size_t make_tcp(uint8_t *packet, uint16_t source_port, int syn, int ack,
                       int psh, const uint8_t *data, size_t data_length) {
    const size_t length = sizeof(struct iphdr) + sizeof(struct tcphdr) + data_length;
    memset(packet, 0, length);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->tot_len = htons((uint16_t) length);
    ip4->protocol = IPPROTO_TCP;
    inet_pton(AF_INET, "192.0.2.10", &ip4->saddr);
    inet_pton(AF_INET, "198.51.100.10", &ip4->daddr);

    struct tcphdr *tcp = (struct tcphdr *) (packet + sizeof(struct iphdr));
    tcp->source = htons(source_port);
    tcp->dest = htons(443);
    tcp->doff = 5;
    tcp->syn = syn;
    tcp->ack = ack;
    tcp->psh = psh;
    tcp->seq = htonl(1000);
    tcp->ack_seq = htonl(2000);
    if (data_length != 0)
        memcpy(packet + sizeof(struct iphdr) + sizeof(struct tcphdr), data, data_length);
    return length;
}

static size_t make_udp(uint8_t *packet, uint16_t source_port,
                       const uint8_t *data, size_t data_length) {
    const size_t length = sizeof(struct iphdr) + sizeof(struct udphdr) + data_length;
    memset(packet, 0, length);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->tot_len = htons((uint16_t) length);
    ip4->protocol = IPPROTO_UDP;
    inet_pton(AF_INET, "192.0.2.10", &ip4->saddr);
    inet_pton(AF_INET, "198.51.100.10", &ip4->daddr);

    struct udphdr *udp = (struct udphdr *) (packet + sizeof(struct iphdr));
    udp->source = htons(source_port);
    udp->dest = htons(443);
    udp->len = htons((uint16_t) (sizeof(struct udphdr) + data_length));
    if (data_length != 0)
        memcpy(packet + sizeof(struct iphdr) + sizeof(struct udphdr), data, data_length);
    return length;
}

static void run(const uint8_t *packet, size_t length) {
    int unexpected_before = unexpected_handler_calls;
    handle_ip(&args, packet, length, -1, 0, 1000);
    CHECK(unexpected_handler_calls == unexpected_before,
          "packet does not reach an unexpected native handler");
}

static void test_tcp_revalidation_and_fresh_syn(void) {
    _Alignas(struct iphdr) uint8_t syn_packet[256];
    _Alignas(struct iphdr) uint8_t packet[256];
    const size_t syn_length = make_tcp(syn_packet, 42000, 1, 0, 0, NULL, 0);

    reset_fakes();
    run(syn_packet, syn_length);
    CHECK(policy_calls == 1 && uid_calls == 1 && wireguard_writes == 1,
          "TCP SYN uses the real allow gate and reaches WireGuard once");
    CHECK(last_policy_uid == configured_uid, "TCP policy receives the resolved UID");

    const size_t ack_length = make_tcp(packet, 42000, 0, 1, 0, NULL, 0);
    run(packet, ack_length);
    run(packet, ack_length);
    CHECK(policy_calls == 1 && uid_calls == 1 && wireguard_writes == 3,
          "established allowed TCP packets reuse the real cached verdict");

    route_flow_invalidate();
    policy_allowed = 0;
    const size_t data_length = make_tcp(packet, 42000, 0, 1, 1,
                                        (const uint8_t *) "data", 4);
    run(packet, data_length);
    CHECK(policy_calls == 2 && wireguard_writes == 3 && rst_writes == 1,
          "invalidated established TCP policy blocks before WireGuard and resets once");
    run(packet, data_length);
    CHECK(policy_calls == 2 && wireguard_writes == 3 && rst_writes == 1,
          "repeated blocked TCP packets skip Java, WireGuard, and duplicate resets");

    route_flow_invalidate();
    policy_allowed = 1;
    run(packet, data_length);
    CHECK(policy_calls == 3 && wireguard_writes == 4,
          "a later invalidation rechecks policy and forwards the established flow");

    policy_allowed = 0;
    make_tcp(syn_packet, 42000, 1, 0, 0, NULL, 0);
    run(syn_packet, syn_length);
    CHECK(policy_calls == 4 && wireguard_writes == 4,
          "a reused tuple SYN clears the old verdict and rechecks policy");
    policy_allowed = 1;
}

static void test_tcp_unknown_owner_fails_closed_then_recovers(void) {
    _Alignas(struct iphdr) uint8_t syn_packet[256];
    _Alignas(struct iphdr) uint8_t packet[256];
    const size_t syn_length = make_tcp(syn_packet, 42001, 1, 0, 0, NULL, 0);
    const size_t ack_length = make_tcp(packet, 42001, 0, 1, 1,
                                       (const uint8_t *) "data", 4);

    reset_fakes();
    run(syn_packet, syn_length);
    CHECK(wireguard_writes == 1 && policy_calls == 1,
          "unknown-owner regression starts from an allowed tunnelled SYN");

    route_flow_invalidate();
    configured_uid = -1;
    run(packet, ack_length);
    int verdict = ROUTE_FLOW_VERDICT_UNKNOWN;
    int tunnel = 0;
    int uid_known = 1;
    CHECK(wireguard_writes == 1 && policy_calls == 1,
          "unknown established owner fails closed without Java policy or WireGuard");
    CHECK(route_flow_lookup_verdict(4, IPPROTO_TCP,
                                    &((struct iphdr *) packet)->saddr, 42001,
                                    &((struct iphdr *) packet)->daddr, 443,
                                    &verdict) && verdict == ROUTE_FLOW_VERDICT_UNKNOWN,
          "unknown owner does not pin an allowed TCP verdict");
    CHECK(route_flow_lookup(4, IPPROTO_TCP,
                            &((struct iphdr *) packet)->saddr, 42001,
                            &((struct iphdr *) packet)->daddr, 443,
                            &tunnel, &uid_known) && tunnel == 1 && !uid_known,
          "unknown owner remains unstable in the real route cache");

    configured_uid = 10042;
    run(packet, ack_length);
    CHECK(policy_calls == 2 && wireguard_writes == 2,
          "resolving the owner on the next packet permits a fresh policy decision");
}

static void test_udp_policy_invalidation_and_negative_state(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    const uint8_t opaque[] = {0x17, 0x03, 0x03, 0x00, 0x01, 0x7f};
    const size_t length = make_udp(packet, 41000, opaque, sizeof(opaque));

    reset_fakes();
    run(packet, length);
    run(packet, length);
    CHECK(policy_calls == 1 && wireguard_writes == 2,
          "allowed UDP/443 tuple caches the real route/policy result");

    route_flow_invalidate();
    policy_allowed = 0;
    run(packet, length);
    CHECK(policy_calls == 2 && wireguard_writes == 2 && blocked_udp_calls == 1,
          "invalidated UDP policy blocks before WireGuard and records negative state");
    run(packet, length);
    CHECK(policy_calls == 2 && wireguard_writes == 2 && blocked_udp_calls == 1,
          "repeated blocked UDP packets use negative state without Java or WireGuard");
}

int main(void) {
    memset(&context, 0, sizeof(context));
    memset(&args, 0, sizeof(args));
    args.ctx = &context;
    args.fwd53 = 1;
    args.rcode = 3;
    context.sdk = 29;
    CHECK(atomic_load_explicit(&wg_required, memory_order_acquire) == 1,
          "WireGuard is required for the production-bound fixture");
    CHECK(route_default_is_tunnel() == 1, "the fixture starts with the default tunnel route");

    test_tcp_revalidation_and_fresh_syn();
    test_tcp_unknown_owner_fails_closed_then_recovers();
    test_udp_policy_invalidation_and_negative_state();
    if (failures != 0)
        return 1;
    puts("ip_flow_policy_test: all tests passed");
    return 0;
}
