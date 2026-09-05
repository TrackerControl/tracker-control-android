#ifndef TRACKERCONTROL_WG_FLOW_CACHE_H
#define TRACKERCONTROL_WG_FLOW_CACHE_H

#include <netinet/in.h>
#include <stdint.h>

static inline int can_reuse_wg_udp_verdict(int wg_required, int protocol,
                                           int route_cached, int uid_known,
                                           int wants_tunnel) {
    return wg_required && protocol == IPPROTO_UDP && route_cached &&
           uid_known && wants_tunnel;
}

// RFC 793 reset construction for an observed outbound segment. An ACK-bearing
// segment is reset with its ACK as the sequence number and no ACK flag. A
// segment without ACK is reset with an ACK for its sequence space.
static inline void tcp_stateless_reset_fields(uint32_t segment_seq,
                                               uint32_t segment_ack,
                                               uint32_t segment_length,
                                               int syn, int fin, int input_ack,
                                               uint32_t *reset_seq,
                                               uint32_t *reset_ack,
                                               int *reset_has_ack) {
    if (input_ack) {
        *reset_seq = segment_ack;
        *reset_ack = 0;
        *reset_has_ack = 0;
    } else {
        *reset_seq = 0;
        *reset_ack = segment_seq + segment_length +
                     (syn ? 1u : 0u) + (fin ? 1u : 0u);
        *reset_has_ack = 1;
    }
}

#endif
