/* Host regression tests for ICMP error-queue correlation and relay safety. */

#include <arpa/inet.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

#ifndef IP_RECVERR
#define IP_RECVERR 11
#endif
#ifndef SO_EE_ORIGIN_ICMP
#define SO_EE_ORIGIN_ICMP 2
#endif
#ifndef ICMP_TIME_EXCEEDED
#define ICMP_TIME_EXCEEDED 11
#endif
#ifndef MSG_ERRQUEUE
#define MSG_ERRQUEUE 0x2000
#endif
#define TEST_ICMP_ERROR_DRAIN_MAX 16

struct fake_extended_err {
    uint32_t ee_errno;
    uint8_t ee_origin;
    uint8_t ee_type;
    uint8_t ee_code;
    uint8_t ee_pad;
    uint32_t ee_info;
    uint32_t ee_data;
};

static int failures;
static int socket_calls;
static int close_calls;
static int sendto_calls;
static int setsockopt_calls;
static int recvmsg_calls;
static int getsockopt_calls;
static int write_calls;
static int fcntl_flags;
static int fake_queue_count;
static int fake_queue_index;
static int fake_queue_bad_control;
static int fake_queue_bad_origin;
static int fake_queue_bad_offender;
static int fake_queue_bad_destination;
static int fake_queue_bad_length;
static int fake_sendto_fail_once;
static int fake_sendto_attempts;
static uint16_t fake_queue_sequence;
static uint16_t fake_queue_sequences[32];
static uint8_t fake_reply[64];
static size_t fake_reply_length;
static uint8_t last_write[512];
static size_t last_write_length;
static uint8_t write_history[8][512];
static size_t write_history_lengths[8];
static uint8_t original_packet[128];
static size_t original_length;

FILE *pcap_file;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    void *pointer = calloc(1, size);
    if (pointer == NULL)
        abort();
    return pointer;
}

void ng_free(void *pointer, const char *file, int line) {
    (void) file;
    (void) line;
    free(pointer);
}

void write_pcap_rec(const uint8_t *buffer, size_t length) {
    (void) buffer;
    (void) length;
}

int protect_socket(const struct arguments *args, int socket) {
    (void) args;
    (void) socket;
    return 0;
}

int __wrap_socket(int domain, int type, int protocol) {
    (void) domain;
    (void) type;
    (void) protocol;
    return 200 + ++socket_calls;
}

int __wrap_close(int file_descriptor) {
    (void) file_descriptor;
    close_calls++;
    return 0;
}

int __wrap_fcntl(int file_descriptor, int command, ...) {
    (void) file_descriptor;
    if (command == F_GETFL)
        return fcntl_flags;
    if (command == F_SETFL) {
        va_list args;
        va_start(args, command);
        fcntl_flags = va_arg(args, int);
        va_end(args);
        return 0;
    }
    errno = EINVAL;
    return -1;
}

int epoll_ctl(int epoll_fd, int operation, int descriptor,
              struct epoll_event *event) {
    (void) epoll_fd;
    (void) operation;
    (void) descriptor;
    (void) event;
    return 0;
}

int __wrap_setsockopt(int socket, int level, int option,
                      const void *value, socklen_t value_length) {
    (void) socket;
    (void) level;
    (void) option;
    (void) value;
    (void) value_length;
    setsockopt_calls++;
    return 0;
}

int __wrap_getsockopt(int socket, int level, int option,
                      void *value, socklen_t *value_length) {
    (void) socket;
    (void) level;
    (void) option;
    (void) value;
    (void) value_length;
    getsockopt_calls++;
    return 0;
}

ssize_t __wrap_sendto(int socket, const void *buffer, size_t length, int flags,
                      const struct sockaddr *destination,
                      socklen_t destination_length) {
    (void) socket;
    (void) buffer;
    (void) length;
    (void) flags;
    (void) destination;
    (void) destination_length;
    sendto_calls++;
    fake_sendto_attempts++;
    if (fake_sendto_fail_once && fake_sendto_attempts == 1) {
        fake_queue_count = 1;
        fake_queue_index = 0;
        errno = EHOSTUNREACH;
        return -1;
    }
    return (ssize_t) length;
}

ssize_t __wrap_write(int file_descriptor, const void *buffer, size_t length) {
    (void) file_descriptor;
    if (write_calls < (int) (sizeof(write_history) / sizeof(write_history[0]))) {
        size_t copy = length < sizeof(write_history[0]) ? length : sizeof(write_history[0]);
        memcpy(write_history[write_calls], buffer, copy);
        write_history_lengths[write_calls] = copy;
    }
    write_calls++;
    last_write_length = length < sizeof(last_write) ? length : sizeof(last_write);
    memcpy(last_write, buffer, last_write_length);
    return (ssize_t) length;
}

ssize_t __wrap_recv(int socket, void *buffer, size_t length, int flags) {
    (void) socket;
    (void) flags;
    if (fake_reply_length == 0) {
        errno = EAGAIN;
        return -1;
    }
    size_t copy = fake_reply_length < length ? fake_reply_length : length;
    memcpy(buffer, fake_reply, copy);
    fake_reply_length = 0;
    return (ssize_t) copy;
}

ssize_t __wrap_recvmsg(int socket, struct msghdr *message, int flags) {
    (void) socket;
    (void) flags;
    recvmsg_calls++;
    if (fake_queue_index >= fake_queue_count) {
        errno = EAGAIN;
        return -1;
    }

    fake_queue_index++;
    uint8_t *data = message->msg_iov[0].iov_base;
    memset(data, 0, message->msg_iov[0].iov_len);
    data[0] = ICMP_ECHO;
    data[1] = 0;
    uint16_t id = htons(0xaaaa);
    uint16_t queued_sequence = fake_queue_sequences[fake_queue_index - 1];
    if (queued_sequence == 0)
        queued_sequence = fake_queue_sequence;
    uint16_t seq = htons(queued_sequence);
    memcpy(data + 4, &id, sizeof(id));
    memcpy(data + 6, &seq, sizeof(seq));

    struct sockaddr_in *destination = (struct sockaddr_in *) message->msg_name;
    memset(destination, 0, sizeof(*destination));
    destination->sin_family = AF_INET;
    destination->sin_addr.s_addr = inet_addr(fake_queue_bad_destination
                                             ? "198.51.100.2" : "198.51.100.1");

    if (fake_queue_bad_control) {
        message->msg_flags = MSG_CTRUNC;
        return ICMP_MINLEN;
    }

    struct cmsghdr *cmsg = (struct cmsghdr *) message->msg_control;
    memset(cmsg, 0, message->msg_controllen);
    cmsg->cmsg_level = IPPROTO_IP;
    cmsg->cmsg_type = IP_RECVERR;
    cmsg->cmsg_len = CMSG_LEN(sizeof(struct fake_extended_err) +
                               sizeof(struct sockaddr_in));
    if (fake_queue_bad_length)
        cmsg->cmsg_len = CMSG_LEN(sizeof(struct fake_extended_err)) - 1;
    struct fake_extended_err error = {0};
    error.ee_origin = fake_queue_bad_origin ? SO_EE_ORIGIN_ICMP + 1
                                             : SO_EE_ORIGIN_ICMP;
    error.ee_type = ICMP_TIME_EXCEEDED;
    error.ee_code = 0;
    memcpy(CMSG_DATA(cmsg), &error, sizeof(error));
    struct sockaddr_in *offender = (struct sockaddr_in *)
            (CMSG_DATA(cmsg) + sizeof(error));
    memset(offender, 0, sizeof(*offender));
    offender->sin_family = fake_queue_bad_offender ? AF_INET6 : AF_INET;
    offender->sin_addr.s_addr = inet_addr("203.0.113.9");
    message->msg_controllen = cmsg->cmsg_len;
    message->msg_flags = 0;
    return ICMP_MINLEN;
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    uint32_t sum = start;
    while (length > 1) {
        uint16_t word;
        memcpy(&word, buffer, sizeof(word));
        sum += word;
        buffer += 2;
        length -= 2;
    }
    if (length != 0)
        sum += *buffer;
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t) sum;
}

static size_t make_echo(uint8_t *packet, uint16_t id, uint16_t seq) {
    memset(packet, 0, 64);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->ttl = 7;
    ip4->protocol = IPPROTO_ICMP;
    ip4->saddr = inet_addr("192.0.2.1");
    ip4->daddr = inet_addr("198.51.100.1");
    struct icmp *icmp = (struct icmp *) (packet + 20);
    icmp->icmp_type = ICMP_ECHO;
    icmp->icmp_id = htons(id);
    icmp->icmp_seq = htons(seq);
    memcpy(packet + 28, "quote-ok", 8);
    return 36;
}

static size_t make_echo_with_options(uint8_t *packet, uint16_t id, uint16_t seq) {
    memset(packet, 0, 64);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 6;
    ip4->tot_len = htons(40);
    ip4->ttl = 7;
    ip4->protocol = IPPROTO_ICMP;
    ip4->saddr = inet_addr("192.0.2.1");
    ip4->daddr = inet_addr("198.51.100.1");
    packet[20] = 1;
    packet[21] = 2;
    packet[22] = 3;
    packet[23] = 4;
    struct icmp *icmp = (struct icmp *) (packet + 24);
    icmp->icmp_type = ICMP_ECHO;
    icmp->icmp_id = htons(id);
    icmp->icmp_seq = htons(seq);
    memcpy(packet + 32, "optquote", 8);
    return 40;
}

static void reset_queue(void) {
    fake_queue_count = 0;
    fake_queue_index = 0;
    fake_queue_bad_control = 0;
    fake_queue_bad_origin = 0;
    fake_queue_bad_offender = 0;
    fake_queue_bad_destination = 0;
    fake_queue_bad_length = 0;
    fake_queue_sequence = 7;
    memset(fake_queue_sequences, 0, sizeof(fake_queue_sequences));
    fake_sendto_fail_once = 0;
    fake_sendto_attempts = 0;
    fake_reply_length = 0;
    recvmsg_calls = 0;
    write_calls = 0;
    last_write_length = 0;
    memset(write_history, 0, sizeof(write_history));
    memset(write_history_lengths, 0, sizeof(write_history_lengths));
}

static struct ng_session *make_session(struct context *context,
                                       struct arguments *args,
                                       uint8_t *packet, uint16_t id) {
    size_t length = make_echo(packet, id, 7);
    memcpy(original_packet, packet, length);
    original_length = length;
    memset(context, 0, sizeof(*context));
    memset(args, 0, sizeof(*args));
    args->ctx = context;
    CHECK(handle_icmp(args, packet, length, packet + 20, 10001, 99) == 1,
          "echo request is admitted");
    return context->ng_session;
}

static void test_error_is_relayed_with_original_quote(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x1234);
    recvmsg_calls = 0;
    CHECK(session != NULL, "session exists for error relay");
    fake_queue_count = 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(recvmsg_calls == 2, "error queue drain stops at EAGAIN");
    CHECK(write_calls == 1 && last_write_length == 64,
          "one ICMP error is written to tun");
    if (write_calls == 1) {
        struct iphdr *ip4 = (struct iphdr *) last_write;
        CHECK(ip4->saddr == inet_addr("203.0.113.9"),
              "error source is the ICMP offender");
        CHECK(ip4->daddr == inet_addr("192.0.2.1"),
              "error destination is the requesting host");
        CHECK(last_write[20] == ICMP_TIME_EXCEEDED,
              "error type is preserved");
        CHECK(memcmp(last_write + 28, original_packet, original_length) == 0,
              "error quotes the exact original IP and echo header");
        CHECK(calc_checksum(0, last_write, 20) == 0xffff &&
                      calc_checksum(0, last_write + 20, last_write_length - 20) == 0xffff,
              "relayed IPv4 and ICMP checksums are valid");
    }
    CHECK(session->icmp.stop == 0, "network error does not stop the session");
    ng_free(session, __FILE__, __LINE__);
}

static void test_malformed_error_is_ignored(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x2345);
    recvmsg_calls = 0;
    fake_queue_count = 1;
    fake_queue_bad_control = 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "truncated control data is ignored safely");
    reset_queue();
    fake_queue_count = 1;
    fake_queue_bad_origin = 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "wrong error origin is ignored safely");
    reset_queue();
    fake_queue_count = 1;
    fake_queue_bad_destination = 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "a destination mismatch is ignored safely");
    reset_queue();
    fake_queue_count = 1;
    fake_queue_bad_length = 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "a short error cmsg is ignored safely");
    reset_queue();
    fake_queue_count = 1;
    fake_queue_sequence = 99;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "an unknown sequence is ignored safely");
    reset_queue();
    fake_reply_length = 4;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLIN,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "a short echo reply is ignored safely");
    ng_free(session, __FILE__, __LINE__);
}

static void test_out_of_order_quotes_and_eviction(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x4567);

    uint8_t later[64];
    uint8_t first_quote[64];
    memcpy(first_quote, packet, sizeof(first_quote));
    size_t later_length = make_echo(later, 0x4567, 8);
    CHECK(handle_icmp(&args, later, later_length, later + 20, 10001, 99) == 1,
          "a second sequence is recorded");
    reset_queue();
    fake_queue_count = 2;
    fake_queue_sequences[0] = 8;
    fake_queue_sequences[1] = 7;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 2 && session->icmp.stop == 0 &&
                  memcmp(write_history[0] + 28, later, later_length) == 0 &&
                  memcmp(write_history[1] + 28, first_quote, 36) == 0,
          "out-of-order errors relay the exact matching quote bytes");
    ng_free(session, __FILE__, __LINE__);
}

static void test_quote_ring_retains_recent_edges(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    uint8_t probe[64];
    uint8_t oldest_retained[64];
    uint8_t newest_retained[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x4a4a);
    for (uint16_t sequence = 8; sequence <= 23; sequence++) {
        size_t length = make_echo(probe, 0x4a4a, sequence);
        if (sequence == 8)
            memcpy(oldest_retained, probe, length);
        if (sequence == 23)
            memcpy(newest_retained, probe, length);
        CHECK(handle_icmp(&args, probe, length, probe + 20, 10001, 99) == 1,
              "bounded quote ring accepts a recent probe");
    }

    reset_queue();
    fake_queue_count = 1;
    fake_queue_sequence = 7;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 0 && session->icmp.stop == 0,
          "the bounded quote ring evicts the earliest outstanding quote");

    reset_queue();
    fake_queue_count = 1;
    fake_queue_sequence = 8;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 1 && memcmp(write_history[0] + 28, oldest_retained, 36) == 0,
          "the oldest retained quote is relayed exactly");

    reset_queue();
    fake_queue_count = 1;
    fake_queue_sequence = 23;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 1 && memcmp(write_history[0] + 28, newest_retained, 36) == 0,
          "the newest retained quote is relayed exactly");
    ng_free(session, __FILE__, __LINE__);
}

static void test_async_send_error_retries_once(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    sendto_calls = 0;
    fake_sendto_fail_once = 1;
    struct ng_session *session = make_session(&context, &args, packet, 0x5678);
    CHECK(sendto_calls == 2 && session != NULL && session->icmp.stop == 0,
          "a send race drains once and retries without stopping the session");
    ng_free(session, __FILE__, __LINE__);
}

static void test_simultaneous_error_and_echo_reply(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    uint8_t later[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x6789);
    size_t later_length = make_echo(later, 0x6789, 8);
    CHECK(handle_icmp(&args, later, later_length, later + 20, 10001, 99) == 1,
          "a valid echo reply probe is outstanding");

    fake_queue_count = 1;
    fake_queue_sequence = 7;
    memset(fake_reply, 0, sizeof(fake_reply));
    fake_reply[0] = ICMP_ECHOREPLY;
    fake_reply[1] = 0;
    uint16_t translated_id = htons(0xbeef);
    uint16_t reply_sequence = htons(8);
    memcpy(fake_reply + 4, &translated_id, sizeof(translated_id));
    memcpy(fake_reply + 6, &reply_sequence, sizeof(reply_sequence));
    fake_reply_length = ICMP_MINLEN;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR | EPOLLIN,
        .data.ptr = session,
    });
    uint16_t restored_id = htons(0x6789);
    CHECK(write_calls == 2 && session->icmp.stop == 0,
          "simultaneous network error and echo input both reach tun");
    CHECK(write_calls >= 1 && write_history[0][20] == ICMP_TIME_EXCEEDED &&
                  memcmp(write_history[0] + 28, packet, 36) == 0,
          "the error is relayed before the echo reply");
    CHECK(write_calls == 2 && write_history[1][20] == ICMP_ECHOREPLY &&
                  memcmp(write_history[1] + 24, &restored_id, sizeof(restored_id)) == 0 &&
                  memcmp(write_history[1] + 26, &reply_sequence, sizeof(reply_sequence)) == 0 &&
                  calc_checksum(0, write_history[1] + 20,
                                write_history_lengths[1] - 20) == 0xffff,
          "the echo reply restores its ID and checksum after EPOLLERR");
    ng_free(session, __FILE__, __LINE__);
}

static void test_ipv4_options_quote_is_preserved(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    size_t length = make_echo_with_options(packet, 0x789a, 7);
    memcpy(original_packet, packet, length);
    original_length = length;
    memset(&context, 0, sizeof(context));
    memset(&args, 0, sizeof(args));
    args.ctx = &context;
    CHECK(handle_icmp(&args, packet, length, packet + 24, 10001, 99) == 1,
          "an IPv4 echo with options is admitted");
    struct ng_session *session = context.ng_session;
    CHECK(session != NULL, "IPv4 options session exists");
    fake_queue_count = 1;
    fake_queue_sequence = 7;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR,
        .data.ptr = session,
    });
    CHECK(write_calls == 1 && last_write_length == 68 &&
                  memcmp(last_write + 28, original_packet, original_length) == 0 &&
                  last_write[48] == 1 && last_write[49] == 2 &&
                  last_write[50] == 3 && last_write[51] == 4,
          "IPv4 options and the exact quoted header are preserved");
    ng_free(session, __FILE__, __LINE__);
}

static void test_bound_and_simultaneous_drain(void) {
    struct context context;
    struct arguments args;
    uint8_t packet[64];
    reset_queue();
    struct ng_session *session = make_session(&context, &args, packet, 0x3456);
    recvmsg_calls = 0;
    fake_queue_count = TEST_ICMP_ERROR_DRAIN_MAX + 1;
    check_icmp_socket(&args, &(struct epoll_event) {
        .events = EPOLLERR | EPOLLIN,
        .data.ptr = session,
    });
    CHECK(recvmsg_calls == TEST_ICMP_ERROR_DRAIN_MAX,
          "error queue drain is bounded at sixteen messages");
    CHECK(session->icmp.stop == 0, "simultaneous error and input do not stop session");
    ng_free(session, __FILE__, __LINE__);
}

int main(void) {
    test_error_is_relayed_with_original_quote();
    test_malformed_error_is_ignored();
    test_bound_and_simultaneous_drain();
    test_out_of_order_quotes_and_eviction();
    test_quote_ring_retains_recent_edges();
    test_async_send_error_retries_once();
    test_simultaneous_error_and_echo_reply();
    test_ipv4_options_quote_is_preserved();
    if (failures != 0)
        return 1;
    puts("icmp_error_test: all tests passed");
    return 0;
}
