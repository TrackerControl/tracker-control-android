#ifndef TRACKERCONTROL_WG_FLOW_CACHE_H
#define TRACKERCONTROL_WG_FLOW_CACHE_H

#include <netinet/in.h>

static inline int can_reuse_wg_udp_verdict(int wg_required, int protocol,
                                           int route_cached, int uid_known,
                                           int wants_tunnel) {
    return wg_required && protocol == IPPROTO_UDP && route_cached &&
           uid_known && wants_tunnel;
}

#endif
