#ifndef TRACKERCONTROL_NATIVE_TEST_NETINET_UDP_H
#define TRACKERCONTROL_NATIVE_TEST_NETINET_UDP_H

#include <stdint.h>

// Linux/Android names these fields source/dest. macOS exposes a different
// layout, so host tests provide the ABI used by the native packet code.
struct udphdr {
    uint16_t source;
    uint16_t dest;
    uint16_t len;
    uint16_t check;
};

#endif
