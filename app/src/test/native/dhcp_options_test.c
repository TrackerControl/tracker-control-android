/*
 * Host unit tests for DHCP option parsing and request/reply classification.
 * This is a plain C test program with no Android or JNI dependencies.
 */

#include <stdint.h>
#include <stdio.h>

#include "dhcp_options.h"

static int failures = 0;

#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            fprintf(stderr, "FAIL: %s (%s:%d)\n", (msg), __FILE__, __LINE__); \
            failures++;                                                       \
        }                                                                     \
    } while (0)

static void test_discover_maps_to_offer(void) {
    const uint8_t options[] = {53, 1, DHCP_MESSAGE_DISCOVER, 255};
    int request_type = dhcp_message_type(options, sizeof(options));

    CHECK(request_type == DHCP_MESSAGE_DISCOVER,
          "DISCOVER: option 53 is parsed");
    CHECK(dhcp_reply_type(request_type) == DHCP_MESSAGE_OFFER,
          "DISCOVER: response is OFFER");
}

static void test_request_maps_to_ack(void) {
    const uint8_t options[] = {53, 1, DHCP_MESSAGE_REQUEST, 255};
    int request_type = dhcp_message_type(options, sizeof(options));

    CHECK(request_type == DHCP_MESSAGE_REQUEST,
          "REQUEST: option 53 is parsed");
    CHECK(dhcp_reply_type(request_type) == DHCP_MESSAGE_ACK,
          "REQUEST: response is ACK");
}

static void test_padding_and_preceding_options(void) {
    const uint8_t options[] = {
            0,
            50, 4, 10, 1, 10, 2,
            0,
            53, 1, DHCP_MESSAGE_REQUEST,
            255
    };

    CHECK(dhcp_message_type(options, sizeof(options)) == DHCP_MESSAGE_REQUEST,
          "parser skips padding and preceding options");
}

static void test_missing_message_type(void) {
    const uint8_t options[] = {50, 4, 10, 1, 10, 2, 255};

    CHECK(dhcp_message_type(options, sizeof(options)) == -1,
          "missing option 53 is rejected");
}

static void test_truncated_option(void) {
    const uint8_t options[] = {50, 4, 10, 1};

    CHECK(dhcp_message_type(options, sizeof(options)) == -1,
          "truncated option payload is rejected");
}

static void test_malformed_message_type(void) {
    const uint8_t zero_length[] = {53, 0, 255};
    const uint8_t two_bytes[] = {53, 2, DHCP_MESSAGE_REQUEST, 0, 255};

    CHECK(dhcp_message_type(zero_length, sizeof(zero_length)) == -1,
          "zero-length option 53 is rejected");
    CHECK(dhcp_message_type(two_bytes, sizeof(two_bytes)) == -1,
          "multi-byte option 53 is rejected");
}

static void test_unsupported_message_type(void) {
    const uint8_t options[] = {53, 1, 8, 255};
    int request_type = dhcp_message_type(options, sizeof(options));

    CHECK(request_type == 8, "unsupported type is parsed");
    CHECK(dhcp_reply_type(request_type) == -1,
          "unsupported type has no fabricated reply");
}

int main(void) {
    test_discover_maps_to_offer();
    test_request_maps_to_ack();
    test_padding_and_preceding_options();
    test_missing_message_type();
    test_truncated_option();
    test_malformed_message_type();
    test_unsupported_message_type();

    if (failures == 0) {
        printf("dhcp_options_test: all tests passed\n");
        return 0;
    }

    fprintf(stderr, "dhcp_options_test: %d assertion(s) failed\n", failures);
    return 1;
}
