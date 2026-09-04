#include <stdio.h>

#include "udp_state.h"

#define TEST_EPOLLIN 1u
#define TEST_EPOLLERR 8u
#define TEST_EPOLLHUP 16u

static int failures = 0;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

int main(void) {
    CHECK(udp_state_blocks_outbound(UDP_BLOCKED),
          "blocked session rejects repeated outbound packets");
    CHECK(!udp_state_blocks_outbound(UDP_ACTIVE),
          "active session remains forwardable");
    CHECK(udp_event_action(TEST_EPOLLERR, TEST_EPOLLERR | TEST_EPOLLHUP,
                           TEST_EPOLLIN) == UDP_EVENT_TERMINAL,
          "socket error reaches terminal handler");
    CHECK(udp_event_action(TEST_EPOLLHUP, TEST_EPOLLERR | TEST_EPOLLHUP,
                           TEST_EPOLLIN) == UDP_EVENT_TERMINAL,
          "socket hangup reaches terminal handler");
    CHECK(udp_event_action(TEST_EPOLLERR | TEST_EPOLLIN,
                           TEST_EPOLLERR | TEST_EPOLLHUP,
                           TEST_EPOLLIN) == UDP_EVENT_TERMINAL,
          "terminal event takes priority over readable data");
    CHECK(udp_event_action(TEST_EPOLLIN, TEST_EPOLLERR | TEST_EPOLLHUP,
                           TEST_EPOLLIN) == UDP_EVENT_READ,
          "readable event reaches receive loop");

    if (failures != 0)
        return 1;

    puts("udp_state_test: all tests passed");
    return 0;
}
