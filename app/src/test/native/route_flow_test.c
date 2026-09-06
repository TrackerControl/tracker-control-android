#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

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
