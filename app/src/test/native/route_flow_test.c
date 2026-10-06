#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "netguard.h"
#include "wg_flow_cache.h"

static int failures;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

static const uint8_t source[4] = {192, 0, 2, 10};
static const uint8_t destination[4] = {198, 51, 100, 10};
static time_t owner_clock_seconds = 1000;

// policy.c deliberately uses CLOCK_MONOTONIC for owner expiry. Keep the
// portable CI invocation unchanged while making expiry deterministic here.
int clock_gettime(clockid_t clock_id, struct timespec *now) {
    if (clock_id != CLOCK_MONOTONIC)
        return -1;
    now->tv_sec = owner_clock_seconds;
    now->tv_nsec = 0;
    return 0;
}

static void store_udp_route(int tunnel, int uid_known) {
    route_flow_store(4, IPPROTO_UDP, source, 41000, destination, 443,
                     tunnel, uid_known);
}

static void store_route(int uid_known) {
    store_udp_route(1, uid_known);
}

static void store_tcp_route(int uid_known) {
    route_flow_store(4, IPPROTO_TCP, source, 42000, destination, 443, 1, uid_known);
}

static int lookup_route(int protocol, uint16_t source_port, int *uid_known) {
    int tunnel = 0;
    return route_flow_lookup(4, protocol, source, source_port, destination, 443,
                             &tunnel, uid_known) && tunnel == 1;
}

static int lookup_verdict(int protocol, uint16_t source_port) {
    int verdict = ROUTE_FLOW_VERDICT_UNKNOWN;
    if (!route_flow_lookup_verdict(4, protocol, source, source_port,
                                   destination, 443, &verdict))
        return ROUTE_FLOW_VERDICT_UNKNOWN;
    return verdict;
}

static unsigned owner_set(const uint8_t *saddr, uint16_t sport,
                          const uint8_t *daddr, uint16_t dport) {
    uint32_t h = 2166136261u;
    for (size_t i = 0; i < 4; i++) {
        h = (h ^ saddr[i]) * 16777619u;
        h = (h ^ daddr[i]) * 16777619u;
    }
    h = (h ^ 4u) * 16777619u;
    h = (h ^ IPPROTO_TCP) * 16777619u;
    h = (h ^ (uint8_t) (sport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (sport >> 8)) * 16777619u;
    h = (h ^ (uint8_t) (dport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (dport >> 8)) * 16777619u;
    return h & 255u;
}

static void test_tcp_owner_cache(void) {
    jint uid = -1;
    owner_clock_seconds = 1000;
    tcp_owner_reset();

    tcp_owner_store(4, source, 42000, destination, 443, 10042);
    route_flow_invalidate();
    CHECK(tcp_owner_lookup(4, source, 42000, destination, 443, &uid) && uid == 10042,
          "TCP owner survives route-generation invalidation");

    tcp_owner_forget(4, source, 42000, destination, 443);
    CHECK(!tcp_owner_lookup(4, source, 42000, destination, 443, &uid),
          "TCP owner forget removes one tuple");

    tcp_owner_store(4, source, 42000, destination, 443, 0);
    CHECK(tcp_owner_lookup(4, source, 42000, destination, 443, &uid) && uid == 0,
          "UID 0 remains a valid retained SYN owner");

    owner_clock_seconds = 2000;
    tcp_owner_reset();
    tcp_owner_store(4, source, 42000, destination, 443, 10042);
    owner_clock_seconds += 299;
    CHECK(tcp_owner_lookup(4, source, 42000, destination, 443, &uid) && uid == 10042,
          "TCP owner remains valid before the bounded monotonic idle age");
    owner_clock_seconds = 2000;
    tcp_owner_reset();
    tcp_owner_store(4, source, 42000, destination, 443, 10042);
    owner_clock_seconds += 300;
    CHECK(!tcp_owner_lookup(4, source, 42000, destination, 443, &uid),
          "TCP owner expires at the bounded monotonic idle age");

    // Find five tuples in one set and verify the four-way bound evicts the
    // oldest collision without disturbing unrelated sets.
    uint16_t ports[5];
    unsigned set = 0;
    int found = 0;
    for (uint32_t port = 1000; port < 65536 && found != 2; port++) {
        unsigned candidate = owner_set(source, (uint16_t) port, destination, 443);
        if (found == 0) {
            set = candidate;
            ports[0] = (uint16_t) port;
            found = 1;
        } else if (candidate == set) {
            int count = 1;
            ports[count++] = (uint16_t) port;
            for (uint32_t next = port + 1; next < 65536 && count < 5; next++) {
                if (owner_set(source, (uint16_t) next, destination, 443) == set)
                    ports[count++] = (uint16_t) next;
                port = next;
            }
            if (count == 5)
                found = 2;
        }
    }
    CHECK(found == 2, "test fixture finds five colliding TCP owner tuples");
    if (found == 2) {
        owner_clock_seconds = 3000;
        tcp_owner_reset();
        for (int i = 0; i < 5; i++)
            tcp_owner_store(4, source, ports[i], destination, 443, 20000 + i);
        CHECK(!tcp_owner_lookup(4, source, ports[0], destination, 443, &uid),
              "fifth colliding owner evicts the oldest bounded-cache entry");
        CHECK(tcp_owner_lookup(4, source, ports[4], destination, 443, &uid) && uid == 20004,
              "newest colliding owner remains available");

        tcp_owner_reset();
        for (int i = 0; i < 4; i++)
            tcp_owner_store(4, source, ports[i], destination, 443, 20000 + i);
        tcp_owner_forget(4, source, ports[0], destination, 443);
        owner_clock_seconds++;
        tcp_owner_store(4, source, ports[2], destination, 443, 30002);
        tcp_owner_store(4, source, ports[4], destination, 443, 20004);
        CHECK(tcp_owner_lookup(4, source, ports[1], destination, 443, &uid) && uid == 20001,
              "updating a later matching tuple does not consume an earlier empty slot");
        CHECK(tcp_owner_lookup(4, source, ports[2], destination, 443, &uid) && uid == 30002,
              "updating a retained owner replaces the existing tuple");
    }

    static const uint8_t source6[16] = {
        0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1};
    static const uint8_t destination6[16] = {
        0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1};
    tcp_owner_reset();
    tcp_owner_store(6, source6, 42000, destination6, 443, 10042);
    CHECK(tcp_owner_lookup(6, source6, 42000, destination6, 443, &uid) && uid == 10042,
          "IPv6 TCP owner is retained by the same bounded cache");
}

static void test_default_udp_route_is_reused(void) {
    route_flow_invalidate();
    store_route(1);

    int uid_known = 0;
    CHECK(lookup_route(IPPROTO_UDP, 41000, &uid_known),
          "default WireGuard route is stored after the first UDP decision");
    CHECK(uid_known, "default route cache entry is stable without an app override");
    CHECK(can_reuse_wg_udp_verdict(1, IPPROTO_UDP, 1, uid_known, 1),
          "subsequent default-route UDP packets reuse the cached verdict");
}

static void test_tcp_verdict_revalidation_and_negative_cache(void) {
    route_flow_invalidate();
    store_tcp_route(1);

    // First established packet after a policy generation change must run the
    // Java decision once. Model the production gate with counters: an absent
    // verdict is the only branch that calls Java, while an allowed verdict is
    // the only branch that writes to WireGuard.
    route_flow_invalidate();
    int java_calls = 0;
    int wg_writes = 0;
    int verdict = lookup_verdict(IPPROTO_TCP, 42000);
    CHECK(verdict == ROUTE_FLOW_VERDICT_UNKNOWN,
          "policy invalidation clears the established TCP verdict");
    java_calls++;
    store_tcp_route(1);
    route_flow_store_verdict(4, IPPROTO_TCP, source, 42000, destination, 443,
                             ROUTE_FLOW_VERDICT_ALLOWED);
    wg_writes++;

    verdict = lookup_verdict(IPPROTO_TCP, 42000);
    CHECK(verdict == ROUTE_FLOW_VERDICT_ALLOWED,
          "allowed established TCP result is cached");
    if (verdict == ROUTE_FLOW_VERDICT_ALLOWED)
        wg_writes++;
    CHECK(java_calls == 1 && wg_writes == 2,
          "one post-invalidation policy call and no repeated Java lookup on reuse");

    route_flow_invalidate();
    store_tcp_route(1);
    route_flow_store_verdict(4, IPPROTO_TCP, source, 42000, destination, 443,
                             ROUTE_FLOW_VERDICT_BLOCKED);
    verdict = lookup_verdict(IPPROTO_TCP, 42000);
    CHECK(verdict == ROUTE_FLOW_VERDICT_BLOCKED,
          "blocked established TCP result is negatively cached");
    int blocked_java_calls = 1; // the first post-invalidation packet was evaluated
    int blocked_wg_writes = 0;
    // The cached negative branch returns before Java and before the WG write.
    if (lookup_verdict(IPPROTO_TCP, 42000) != ROUTE_FLOW_VERDICT_BLOCKED) {
        blocked_java_calls++;
        blocked_wg_writes++;
    }
    CHECK(blocked_java_calls == 1 && blocked_wg_writes == 0,
          "repeated blocked TCP packets make no Java call and no WG write");
}

static void test_unresolved_owner_does_not_pin_policy(void) {
    route_flow_invalidate();
    store_route(0);

    int uid_known = 1;
    CHECK(lookup_route(IPPROTO_UDP, 41000, &uid_known) && !uid_known,
          "unresolved owner route remains marked unstable");
    CHECK(lookup_verdict(IPPROTO_UDP, 41000) == ROUTE_FLOW_VERDICT_UNKNOWN,
          "unresolved owner has no reusable policy verdict");

    // Once ownership resolves, the same tuple can receive a stable result.
    store_route(1);
    route_flow_store_verdict(4, IPPROTO_UDP, source, 41000, destination, 443,
                             ROUTE_FLOW_VERDICT_ALLOWED);
    CHECK(lookup_verdict(IPPROTO_UDP, 41000) == ROUTE_FLOW_VERDICT_ALLOWED,
          "resolved owner can be cached after an unresolved retry");
}

static void test_verdict_preserves_resolved_route_metadata(void) {
    route_flow_invalidate();
    store_udp_route(0, 1);

    route_flow_store_verdict(4, IPPROTO_UDP, source, 41000, destination, 443,
                             ROUTE_FLOW_VERDICT_ALLOWED);

    int tunnel = 1;
    int uid_known = 0;
    CHECK(route_flow_lookup(4, IPPROTO_UDP, source, 41000, destination, 443,
                            &tunnel, &uid_known),
          "policy verdict attaches to an existing route entry");
    CHECK(!tunnel && uid_known,
          "policy verdict does not overwrite resolved per-app route metadata");
    CHECK(lookup_verdict(IPPROTO_UDP, 41000) == ROUTE_FLOW_VERDICT_ALLOWED,
          "policy verdict remains cached on the resolved route");
}

static void test_stateless_reset_sequence_shape(void) {
    uint32_t reset_seq = 0;
    uint32_t reset_ack = 0;
    int reset_has_ack = -1;

    tcp_stateless_reset_fields(100, 900, 40, 0, 0, 1,
                               &reset_seq, &reset_ack, &reset_has_ack);
    CHECK(reset_seq == 900 && reset_ack == 0 && !reset_has_ack,
          "ACK-bearing input gets an unacknowledged RST at SEG.ACK");

    tcp_stateless_reset_fields(100, 0, 20, 0, 1, 0,
                               &reset_seq, &reset_ack, &reset_has_ack);
    CHECK(reset_seq == 0 && reset_ack == 121 && reset_has_ack,
          "non-ACK input gets RST|ACK for SEG.SEQ plus SEG.LEN");

    tcp_stateless_reset_fields(100, 0, 20, 1, 1, 0,
                               &reset_seq, &reset_ack, &reset_has_ack);
    CHECK(reset_seq == 0 && reset_ack == 122 && reset_has_ack,
          "SYN and FIN each consume sequence space in a reset ACK");
}

static void test_syn_clears_reused_tuple_verdict(void) {
    route_flow_invalidate();
    store_tcp_route(1);
    route_flow_store_verdict(4, IPPROTO_TCP, source, 42000, destination, 443,
                             ROUTE_FLOW_VERDICT_BLOCKED);
    CHECK(lookup_verdict(IPPROTO_TCP, 42000) == ROUTE_FLOW_VERDICT_BLOCKED,
          "a prior TCP connection can have a blocked cached verdict");

    route_flow_clear_verdict(4, IPPROTO_TCP, source, 42000, destination, 443);
    CHECK(lookup_verdict(IPPROTO_TCP, 42000) == ROUTE_FLOW_VERDICT_UNKNOWN,
          "a fresh SYN clears a stale same-tuple TCP verdict");
}

int main(void) {
    test_tcp_owner_cache();
    test_default_udp_route_is_reused();
    test_tcp_verdict_revalidation_and_negative_cache();
    test_unresolved_owner_does_not_pin_policy();
    test_verdict_preserves_resolved_route_metadata();
    test_stateless_reset_sequence_shape();
    test_syn_clears_reused_tuple_verdict();

    if (failures != 0)
        return 1;
    puts("route_flow_test: all tests passed");
    return 0;
}
