/* Host regression tests for the IPv4 header bounds checked by handle_ip(). */

#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "netguard.h"

static int failures;
static int invalid_header_logs;

FILE *pcap_file;
int loglevel = ANDROID_LOG_WARN;
_Atomic int wg_required;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void log_android(int priority, const char *format, ...) {
    (void) priority;
    if (strcmp(format, "IP4 invalid header length") == 0)
        invalid_header_logs++;
}

int get_udp_session_state(const struct arguments *args,
                          const uint8_t *pkt, const uint8_t *payload) {
    (void) args;
    (void) pkt;
    (void) payload;
    return -1;
}

static void test_ihl_is_validated_before_payload_placement(void) {
    _Alignas(struct iphdr) uint8_t packet[64] = {0};
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->protocol = IPPROTO_UDP;

    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    for (unsigned ihl = 0; ihl <= 15; ihl++) {
        // Invalid base headers, then valid IHL values with truncated options.
        size_t length = ihl < 5 ? sizeof(packet) : ihl * 4 - 1;
        ip4->ihl = ihl;
        ip4->tot_len = htons(length);
        invalid_header_logs = 0;
        handle_ip(&args, packet, length, -1, 1, 1);
        CHECK(invalid_header_logs == (ihl == 5 ? 0 : 1),
              "invalid or truncated IPv4 header is rejected before dispatch");
        CHECK(context.ng_session == NULL, "invalid header creates no session");
    }
}

int main(void) {
    test_ihl_is_validated_before_payload_placement();

    if (failures != 0)
        return 1;
    puts("ip_header_test: all tests passed");
    return 0;
}
