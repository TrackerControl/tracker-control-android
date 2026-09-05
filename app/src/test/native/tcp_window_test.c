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

    struct tcp_session probe = {0};
    int send_probe = 0;
    CHECK(tcp_window_probe_delay(&probe, 1000, 0, &send_probe) == 100,
          "zero-window probe starts with a 100 ms deadline");
    CHECK(send_probe == 0, "initial zero-window deadline does not probe early");
    CHECK(tcp_window_probe_delay(&probe, 1099, 0, &send_probe) == 1,
          "zero-window probe keeps the first deadline");
    CHECK(tcp_window_probe_delay(&probe, 1100, 0, &send_probe) == 200,
          "zero-window probe doubles after the first probe");
    CHECK(send_probe == 1, "zero-window probe is emitted at its deadline");
    CHECK(tcp_window_probe_delay(&probe, 1300, 0, &send_probe) == 400,
          "zero-window probe continues exponential backoff");
    CHECK(send_probe == 1, "second zero-window probe is emitted at its deadline");
    CHECK(tcp_window_probe_delay(&probe, 1301, 1, &send_probe) == 0,
          "opening the window cancels the probe deadline");
    CHECK(send_probe == 0, "opening the window does not emit a probe");
    CHECK(probe.window_probe_delay == 0 && probe.window_probe_deadline == 0,
          "opening the window resets probe state");
    probe.upstream_read_eof = 1;
    CHECK(tcp_window_probe_delay(&probe, 2000, 0, &send_probe) == 0,
          "upstream EOF disables zero-window probing");

    if (failures != 0)
        return 1;

    puts("tcp_window_test: all tests passed");
    return 0;
}
