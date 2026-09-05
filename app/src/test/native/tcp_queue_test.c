#include <arpa/inet.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"

static int failures;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                      \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    void *ptr = malloc(size);
    if (ptr == NULL)
        abort();
    return ptr;
}

void ng_free(void *ptr, const char *file, int line) {
    (void) file;
    (void) line;
    free(ptr);
}

int compare_u32(uint32_t s1, uint32_t s2) {
    if (s1 == s2)
        return 0;
    return (int32_t) (s1 - s2) < 0 ? -1 : 1;
}

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

static void clear_queue(struct tcp_session *session) {
    while (session->forward != NULL) {
        struct segment *segment = session->forward;
        session->forward = segment->next;
        ng_free(segment->data, __FILE__, __LINE__);
        ng_free(segment, __FILE__, __LINE__);
    }
}

static void queue_data(struct tcp_session *session, uint32_t seq,
                       uint16_t len, uint8_t value, int psh) {
    uint8_t data[UINT16_MAX];
    memset(data, value, len);
    struct tcphdr tcphdr = {0};
    tcphdr.seq = htonl(seq);
    tcphdr.psh = psh;
    queue_tcp(NULL, &tcphdr, "test", session, data, len);
}

static void expect_bytes(const struct segment *segment, uint16_t offset,
                         uint16_t len, uint8_t value, const char *message) {
    for (uint16_t i = 0; i < len; i++)
        CHECK(segment->data[offset + i] == value, message);
}

static void expect_one(const struct tcp_session *session, uint32_t seq,
                       uint16_t len, const char *message) {
    CHECK(session->forward != NULL, message);
    if (session->forward == NULL)
        return;
    CHECK(session->forward->seq == seq, message);
    CHECK(session->forward->len == len, message);
    CHECK(session->forward->next == NULL, message);
}

static void test_overlapping_retransmission(void) {
    struct tcp_session session = {0};
    session.remote_seq = 1000;
    queue_data(&session, 1000, 1000, 'A', 0);
    queue_data(&session, 1500, 1000, 'B', 1);

    expect_one(&session, 1000, 1500, "overlap coalesces into one forwardable node");
    expect_bytes(session.forward, 0, 1000, 'A', "first-seen bytes are retained");
    expect_bytes(session.forward, 1000, 500, 'B', "new suffix is retained");
    CHECK(session.forward->psh, "PSH follows the resulting final byte");
    clear_queue(&session);
}

static void test_duplicates_and_prefix_trim(void) {
    struct tcp_session session = {0};
    session.remote_seq = 1000;
    queue_data(&session, 1000, 400, 'A', 0);
    queue_data(&session, 1000, 400, 'Z', 0);
    expect_one(&session, 1000, 400, "exact duplicate does not add a node");
    expect_bytes(session.forward, 0, 400, 'A', "exact duplicate keeps first-seen bytes");

    queue_data(&session, 900, 300, 'B', 0);
    expect_one(&session, 1000, 400, "already-forwarded prefix is discarded");
    expect_bytes(session.forward, 0, 400, 'A', "prefix trim does not replace queued bytes");
    clear_queue(&session);

    memset(&session, 0, sizeof(session));
    session.remote_seq = 1000;
    queue_data(&session, 1000, 100, 'A', 0);
    queue_data(&session, 900, 300, 'B', 0);
    expect_one(&session, 1000, 200, "already-forwarded prefix keeps a new suffix");
    expect_bytes(session.forward, 0, 100, 'A', "prefix overlap keeps first-seen bytes");
    expect_bytes(session.forward, 100, 100, 'B', "prefix overlap retains the suffix");
    clear_queue(&session);
}

static void test_bridge_and_out_of_order_gap(void) {
    struct tcp_session session = {0};
    session.remote_seq = 1000;
    queue_data(&session, 1000, 100, 'A', 0);
    queue_data(&session, 1200, 100, 'B', 0);
    queue_data(&session, 1050, 200, 'C', 0);

    expect_one(&session, 1000, 300, "bridging range coalesces predecessor and successor");
    expect_bytes(session.forward, 0, 100, 'A', "bridge keeps predecessor bytes");
    expect_bytes(session.forward, 100, 100, 'C', "bridge keeps first-seen gap bytes");
    expect_bytes(session.forward, 200, 100, 'B', "bridge keeps successor bytes");
    clear_queue(&session);

    memset(&session, 0, sizeof(session));
    session.remote_seq = 1000;
    queue_data(&session, 1200, 100, 'B', 0);
    queue_data(&session, 1000, 100, 'A', 0);
    CHECK(session.forward != NULL && session.forward->seq == 1000,
          "out-of-order range is inserted before successor");
    CHECK(session.forward != NULL && session.forward->next != NULL &&
                  session.forward->next->seq == 1200,
          "out-of-order gap remains explicit");
    clear_queue(&session);
}

static void test_wraparound_and_sent_offset(void) {
    struct tcp_session session = {0};
    session.remote_seq = UINT32_MAX - 15;
    queue_data(&session, UINT32_MAX - 15, 32, 'A', 0);
    queue_data(&session, 8, 32, 'B', 1);

    expect_one(&session, UINT32_MAX - 15, 56, "wrapped overlap coalesces");
    expect_bytes(session.forward, 0, 32, 'A', "wrapped prefix is retained");
    expect_bytes(session.forward, 32, 24, 'B', "wrapped suffix is retained");
    CHECK(session.forward->psh, "wrapped PSH follows final byte");
    clear_queue(&session);

    memset(&session, 0, sizeof(session));
    session.remote_seq = 1000;
    queue_data(&session, 1000, 100, 'A', 0);
    session.forward->sent = 40;
    queue_data(&session, 1000, 150, 'Z', 0);
    expect_one(&session, 1000, 150, "partial send keeps one merged node");
    CHECK(session.forward->sent == 40, "partial send offset is preserved");
    expect_bytes(session.forward, 0, 100, 'A', "partial send keeps first-seen bytes");
    expect_bytes(session.forward, 100, 50, 'Z', "partial send retains new suffix");
    clear_queue(&session);
}

int main(void) {
    test_overlapping_retransmission();
    test_duplicates_and_prefix_trim();
    test_bridge_and_out_of_order_gap();
    test_wraparound_and_sent_offset();

    if (failures != 0)
        return 1;

    puts("tcp_queue_test: all tests passed");
    return 0;
}
