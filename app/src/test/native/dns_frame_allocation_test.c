/* Deterministic allocation failure and lifetime tests for the stream buffer. */
#include <assert.h>
#include <stdlib.h>
#include "dns_frame.h"

static int fail_allocation;
static size_t live_allocations, last_allocation, replays;
static void *frame_alloc(size_t size) {
    last_allocation = size;
    if (fail_allocation) return NULL;
    void *p = malloc(size);
    assert(p != NULL);
    live_allocations++;
    return p;
}
static void frame_free(void *p) {
    if (p != NULL) { assert(live_allocations > 0); live_allocations--; }
    free(p);
}
#define malloc frame_alloc
#define free frame_free
#include "dns_frame.c"
#undef malloc
#undef free

static size_t parse(void *ctx, uint8_t *data, size_t len,
                    enum dns_frame_parse_mode mode, int *blank_rest) {
    (void)ctx; (void)data;
    *blank_rest = 0;
    if (mode == DNS_FRAME_REPLAY) replays++;
    return len;
}
int main(void) {
    struct dns_stream_state state = {0};
    uint8_t first[] = {0, 5, 1}, rest[] = {2, 3, 4, 5};
    assert(dns_frame_process_stream(first, sizeof(first), &state, parse, NULL) == sizeof(first));
    assert(live_allocations == 1 && last_allocation == 5);
    assert(dns_frame_process_stream(rest, sizeof(rest), &state, parse, NULL) == sizeof(rest));
    assert(replays == 1 && live_allocations == 0 && state.frame_buffer == NULL);
    dns_frame_reset(&state);

    /* A connection closes before the announced maximum frame arrives. */
    uint8_t maximum[] = {255, 255};
    dns_frame_process_stream(maximum, sizeof(maximum), &state, parse, NULL);
    assert(last_allocation == 65535 && live_allocations == 1);
    dns_frame_reset(&state);
    dns_frame_reset(&state);
    assert(live_allocations == 0 && state.frame_remaining == 0);

    /* OOM must preserve forwarding/alignment and the previous partial parser. */
    fail_allocation = 1;
    assert(dns_frame_process_stream(first, sizeof(first), &state, parse, NULL) == sizeof(first));
    assert(state.frame_buffer == NULL && state.frame_remaining == 4);
    assert(dns_frame_process_stream(rest, sizeof(rest), &state, parse, NULL) == sizeof(rest));
    assert(replays == 1 && state.frame_remaining == 0 && live_allocations == 0);
    assert(first[2] == 1 && rest[0] == 2 && rest[3] == 5);
    dns_frame_reset(&state);
    return 0;
}
