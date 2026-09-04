#include <netinet/in.h>
#include <stdio.h>

#include "wg_flow_cache.h"

static int failures = 0;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

int main(void) {
    CHECK(can_reuse_wg_udp_verdict(1, IPPROTO_UDP, 1, 1),
          "established tunnelled UDP reuses its accepted verdict");
    CHECK(!can_reuse_wg_udp_verdict(0, IPPROTO_UDP, 1, 1),
          "disabled WireGuard never reuses the verdict");
    CHECK(!can_reuse_wg_udp_verdict(1, IPPROTO_TCP, 1, 1),
          "TCP cannot use the UDP shortcut");
    CHECK(!can_reuse_wg_udp_verdict(1, IPPROTO_UDP, 0, 1),
          "a cache miss still resolves ownership and policy");
    CHECK(!can_reuse_wg_udp_verdict(1, IPPROTO_UDP, 1, 0),
          "direct UDP still follows its native session path");

    if (failures != 0)
        return 1;

    puts("wg_flow_cache_test: all tests passed");
    return 0;
}
