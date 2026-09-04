#ifndef TRACKERCONTROL_UDP_STATE_H
#define TRACKERCONTROL_UDP_STATE_H

#include <stdint.h>

#define UDP_ACTIVE 0
#define UDP_FINISHING 1
#define UDP_CLOSED 2
#define UDP_BLOCKED 3

enum udp_event_action {
    UDP_EVENT_NONE = 0,
    UDP_EVENT_READ,
    UDP_EVENT_TERMINAL,
};

static inline int udp_state_blocks_outbound(int state) {
    return state == UDP_BLOCKED;
}

static inline enum udp_event_action udp_event_action(uint32_t events,
                                                     uint32_t terminal_events,
                                                     uint32_t readable_event) {
    if (events & terminal_events)
        return UDP_EVENT_TERMINAL;
    if (events & readable_event)
        return UDP_EVENT_READ;
    return UDP_EVENT_NONE;
}

#endif
