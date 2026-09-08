#ifndef TRACKERCONTROL_NATIVE_TEST_LINUX_ERRQUEUE_H
#define TRACKERCONTROL_NATIVE_TEST_LINUX_ERRQUEUE_H

#if defined(__linux__)
#include_next <linux/errqueue.h>
#else
#include <stdint.h>
#include <sys/socket.h>

struct sock_extended_err {
    uint32_t ee_errno;
    uint8_t ee_origin;
    uint8_t ee_type;
    uint8_t ee_code;
    uint8_t ee_pad;
    uint32_t ee_info;
    uint32_t ee_data;
};

#ifndef IP_RECVERR
#define IP_RECVERR 11
#endif
#ifndef IPV6_RECVERR
#define IPV6_RECVERR 25
#endif
#ifndef SO_EE_ORIGIN_ICMP
#define SO_EE_ORIGIN_ICMP 2
#endif
#ifndef SO_EE_ORIGIN_ICMP6
#define SO_EE_ORIGIN_ICMP6 3
#endif
#ifndef MSG_ERRQUEUE
#define MSG_ERRQUEUE 0x2000
#endif
#endif

#endif
