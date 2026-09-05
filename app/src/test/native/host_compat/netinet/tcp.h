#ifndef TRACKERCONTROL_NATIVE_TEST_NETINET_TCP_H
#define TRACKERCONTROL_NATIVE_TEST_NETINET_TCP_H

/* Linux/Android's TCP header is not provided by every host libc. */
struct tcphdr {
    uint16_t source;
    uint16_t dest;
    uint32_t seq;
    uint32_t ack_seq;
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    uint16_t res1 : 4;
    uint16_t doff : 4;
    uint16_t fin : 1;
    uint16_t syn : 1;
    uint16_t rst : 1;
    uint16_t psh : 1;
    uint16_t ack : 1;
    uint16_t urg : 1;
    uint16_t res2 : 2;
#else
    uint16_t doff : 4;
    uint16_t res1 : 4;
    uint16_t res2 : 2;
    uint16_t urg : 1;
    uint16_t ack : 1;
    uint16_t psh : 1;
    uint16_t rst : 1;
    uint16_t syn : 1;
    uint16_t fin : 1;
#endif
    uint16_t window;
    uint16_t check;
    uint16_t urg_ptr;
};

/* Linux/Android's TCP state names are not provided by every host libc. */
#ifndef TCP_LISTEN
#define TCP_LISTEN 1
#endif
#ifndef TCP_SYN_RECV
#define TCP_SYN_RECV 2
#endif
#ifndef TCP_ESTABLISHED
#define TCP_ESTABLISHED 3
#endif
#ifndef TCP_CLOSING
#define TCP_CLOSING 4
#endif
#ifndef TCP_CLOSE
#define TCP_CLOSE 5
#endif
#ifndef TCP_CLOSE_WAIT
#define TCP_CLOSE_WAIT 6
#endif
#ifndef TCP_FIN_WAIT1
#define TCP_FIN_WAIT1 7
#endif
#ifndef TCP_FIN_WAIT2
#define TCP_FIN_WAIT2 8
#endif
#ifndef TCP_LAST_ACK
#define TCP_LAST_ACK 9
#endif

#ifndef MSG_MORE
#define MSG_MORE 0x4000
#endif
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
#ifndef SOL_TCP
#define SOL_TCP IPPROTO_TCP
#endif
#ifndef TCP_NODELAY
#define TCP_NODELAY 1
#endif
#ifndef SIOCOUTQ
#define SIOCOUTQ 0x5411
#endif

#endif
