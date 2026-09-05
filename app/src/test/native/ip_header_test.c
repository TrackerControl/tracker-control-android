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
    /* With IHL zero, the old unsigned option-length calculation produced a
     * payload pointer at byte 256 and let this packet reach later dispatch.
     * A full-sized backing buffer makes that pointer look superficially valid,
     * so the test proves the parser rejects the header itself rather than
     * relying on an incidental short-packet check. */
    uint8_t packet[264] = {0};
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 0;
    ip4->protocol = IPPROTO_UDP;
    ip4->tot_len = htons(sizeof(packet));

    struct context context = {0};
    struct arguments args = {0};
    args.ctx = &context;
    invalid_header_logs = 0;

    handle_ip(&args, packet, sizeof(packet), -1, 1, 1);
    CHECK(invalid_header_logs == 1,
          "IPv4 IHL below five is rejected before computing payload placement");
    CHECK(context.ng_session == NULL,
          "an invalid IPv4 IHL cannot create a protocol session");
}

int main(void) {
    test_ihl_is_validated_before_payload_placement();

    if (failures != 0)
        return 1;
    puts("ip_header_test: all tests passed");
    return 0;
}
