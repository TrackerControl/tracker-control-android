/* Real framing + real tc-dns C ABI, with only the Java record sink replaced. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "dns_frame.h"
#include "netguard.h"

static unsigned records, policy_calls, log_calls;
static int force_block;
static struct arguments args = { .rcode = 3 };
static struct ng_session session = { .protocol = IPPROTO_UDP, .udp = { .version = 4 } };
void dns_resolved(const struct arguments *unused, const char *q, const char *a,
                  const char *ip, int ttl) {
    (void)unused;
    assert(strcmp(q, "doubleclick.net") == 0);
    assert(strcmp(a, "doubleclick.net") == 0);
    assert(strcmp(ip, "203.0.113.5") == 0);
    assert(ttl == 300);
    records++;
}
jboolean is_domain_blocked(const struct arguments *unused, const char *q) {
    (void)unused; (void)q; policy_calls++; return force_block;
}
jobject create_packet(const struct arguments *unused, jint version, jint protocol,
                      const char *flags, const char *source, jint sport,
                      const char *dest, jint dport, const char *data, jint uid,
                      jboolean allowed) {
    (void)unused; (void)version; (void)protocol; (void)flags; (void)source;
    (void)sport; (void)dest; (void)dport; (void)data; (void)uid; (void)allowed;
    return NULL;
}
void log_packet(const struct arguments *unused, jobject packet) {
    (void)unused; (void)packet; log_calls++;
}
static size_t parse(void *ctx, uint8_t *data, size_t len,
                    enum dns_frame_parse_mode mode, int *blank_rest) {
    (void)ctx;
    *blank_rest = 0;
    if (mode == DNS_FRAME_REPLAY) record_dns_response(&args, data, len);
    else if (mode == DNS_FRAME_PARTIAL)
        parse_dns_partial_response(&args, &session, data, &len, blank_rest);
    else parse_dns_response(&args, &session, data, &len);
    return len;
}
static const uint8_t frame[] = {
    0,49, /* TCP length */
    0x12,0x34,0x81,0x80,0,1,0,1,0,0,0,0,
    11,'d','o','u','b','l','e','c','l','i','c','k',3,'n','e','t',0,
    0,1,0,1,
    0xc0,0x0c,0,1,0,1,0,0,1,0x2c,0,4,203,0,113,5
};
static unsigned run(size_t split, int bytewise) {
    uint8_t buffer[sizeof(frame)];
    memcpy(buffer, frame, sizeof(buffer));
    struct dns_stream_state state = {0};
    records = 0;
    if (bytewise) {
        for (size_t i=0; i<sizeof(buffer); i++)
            assert(dns_frame_process_stream(buffer+i,1,&state,parse,NULL)==1);
    } else {
        assert(dns_frame_process_stream(buffer,split,&state,parse,NULL)==split);
        if (split < sizeof(buffer))
            assert(dns_frame_process_stream(buffer+split,sizeof(buffer)-split,&state,parse,NULL)==sizeof(buffer)-split);
    }
    assert(memcmp(buffer,frame,sizeof(buffer))==0);
    assert(state.frame_remaining==0 && !state.have_prefix_hi);
    dns_frame_reset(&state);
    return records;
}
static void test_replay_has_no_policy_or_logging_side_effects(void) {
    uint8_t response[sizeof(frame) - 2 + 12];
    memcpy(response, frame + 2, sizeof(frame) - 2);
    /* HTTPS triggers unconditional blanking; replay must not log that either. */
    const uint8_t https[] = {0xc0,0x0c,0,65,0,1,0,0,1,0x2c,0,0};
    memcpy(response + sizeof(frame) - 2, https, sizeof(https));
    response[7] = 2;
    force_block = 1;
    records = policy_calls = log_calls = 0;
    record_dns_response(&args, response, sizeof(response));
    assert(records == 1 && policy_calls == 0 && log_calls == 0);
    /* Control: ordinary processing still enforces and logs a policy hit. */
    memcpy(response, frame + 2, sizeof(frame) - 2);
    size_t len = sizeof(frame) - 2;
    parse_dns_response(&args, &session, response, &len);
    assert(policy_calls == 1 && log_calls == 1 && len < sizeof(frame) - 2);
    force_block = 0;
}
int main(void) {
    assert(sizeof(frame)==51);
    assert(run(sizeof(frame),0) == 1);
    assert(run(1,0) == 1);
    unsigned missed=0;
    for(size_t split=2;split<sizeof(frame);split++)
        if(run(split,0)!=1) missed++;
    printf("payload split sweep: %u/%zu split positions miss the mapping\n",missed,sizeof(frame)-2);
    assert(run(0,1) == 1);
    puts("forwarded bytes and completed stream alignment: unchanged in every case");
    test_replay_has_no_policy_or_logging_side_effects();
    return missed ? 1 : 0;
}
