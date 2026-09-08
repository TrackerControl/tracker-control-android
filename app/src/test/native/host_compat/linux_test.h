#ifndef TRACKERCONTROL_NATIVE_TEST_LINUX_H
#define TRACKERCONTROL_NATIVE_TEST_LINUX_H

/* Keep the real Linux protocol headers. Supply only Android/BSD declarations
 * absent from glibc; Android's bionic already provides these declarations. */
#if defined(__linux__) && !defined(__ANDROID__)
#include <stdint.h>
#include <netinet/in.h>
#include <linux/sockios.h>
#include <netinet/ip6.h>

#ifndef IPV6_VERSION
#define IPV6_VERSION 0x60
#endif
#ifndef IPV6_MAXPACKET
#define IPV6_MAXPACKET 65535
#endif

struct ippseudo {
    struct in_addr ippseudo_src;
    struct in_addr ippseudo_dst;
    uint8_t ippseudo_pad;
    uint8_t ippseudo_p;
    uint16_t ippseudo_len;
};
_Static_assert(sizeof(struct ippseudo) == 12, "IPv4 pseudo-header wire size");
#endif

/* Darwin has the BSD IPv4 header but no Linux struct iphdr. */
#if !defined(__linux__) && !defined(__ANDROID__)
#include <stdint.h>
#ifndef IP_MAXPACKET
#define IP_MAXPACKET 65535
#endif
#ifndef IPDEFTTL
#define IPDEFTTL 64
#endif
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

#endif
