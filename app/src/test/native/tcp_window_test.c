#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "netguard.h"

static int failures;
static uint32_t logged_behind;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                      \
            failures++;                                                     \
        }                                                                   \
    } while (0)

void log_android(int priority, const char *format, ...) {
    (void) priority;

    if (strcmp(format, "Send window behind %u window %u total %u") == 0) {
        va_list args;
        va_start(args, format);
        logged_behind = va_arg(args, unsigned int);
        va_end(args);
    }
}

static void check_window(uint32_t local_seq, uint32_t acked, uint32_t expected_behind,
                         uint32_t expected_total, const char *message) {
    struct tcp_session session = {0};
    session.local_seq = local_seq;
    session.acked = acked;
    session.unconfirmed = 0;
    session.send_window = 0x10080;
    logged_behind = 0;

    uint32_t total = get_send_window(&session);
    CHECK(logged_behind == expected_behind, message);
    CHECK(total == expected_total, message);
}

int main(void) {
    check_window(0x00000020, 0xfffffff0, 0x58, 0x10028,
                 "wrapped sequence distance keeps the 0x30-byte gap");
    check_window(0x00001030, 0x00001000, 0x58, 0x10028,
                 "non-wrapped sequence distance remains unchanged");

    if (failures != 0)
        return 1;

    puts("tcp_window_test: all tests passed");
    return 0;
}
