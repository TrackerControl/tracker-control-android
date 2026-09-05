#ifndef TRACKERCONTROL_NATIVE_TEST_NETINET_IP_H
#define TRACKERCONTROL_NATIVE_TEST_NETINET_IP_H

#include <stdint.h>

#ifndef IP_MAXPACKET
#define IP_MAXPACKET 65535
#endif
#ifndef IPDEFTTL
#define IPDEFTTL 64
#endif

/* Layouts referenced by the IPv4 checksum code on platforms without them. */
struct ip {
    uint8_t bytes[20];
};

struct ippseudo {
    struct in_addr ippseudo_src;
    struct in_addr ippseudo_dst;
    uint8_t ippseudo_pad;
    uint8_t ippseudo_p;
    uint16_t ippseudo_len;
};

struct iphdr {
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    uint8_t ihl : 4;
    uint8_t version : 4;
#else
    uint8_t version : 4;
    uint8_t ihl : 4;
#endif
    uint8_t tos;
    uint16_t tot_len;
    uint16_t id;
    uint16_t frag_off;
    uint8_t ttl;
    uint8_t protocol;
    uint16_t check;
    uint32_t saddr;
    uint32_t daddr;
};

#endif
