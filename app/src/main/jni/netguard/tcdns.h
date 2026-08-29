#ifndef TCDNS_H
#define TCDNS_H

#include <stddef.h>
#include <stdint.h>

#define TCDNS_ABI_VERSION 1u
#define TCDNS_UNCHANGED ((size_t) -1)

/*
 * Callbacks used by the dependency-free tc-dns message rewriter.
 *
 * tcdns_process_response receives one bare DNS message: it must not include
 * an IP/UDP header or a DNS-over-TCP two-byte length prefix. The data pointer
 * is valid for reads and writes for len bytes and is never retained.
 *
 * Every string passed to a callback is NUL-terminated, valid UTF-8 and
 * modified-UTF-8 compatible, owned by the library, and valid only for the
 * duration of that callback. Invalid UTF-8, embedded NUL bytes and
 * supplementary scalar values are replaced with U+FFFD. ctx is opaque and is
 * passed back verbatim. Callbacks must not re-enter
 * tcdns_process_response or longjmp.
 */
typedef struct tcdns_callbacks {
    uint32_t abi_version;
    void (*record_answer)(void *ctx, const char *qname, const char *aname,
                          const char *resource, int32_t ttl);
    int (*is_domain_blocked)(void *ctx, const char *qname);
    uint8_t (*blocked_rcode)(void *ctx);
    void (*on_blanked)(void *ctx, const char *qname, uint16_t qtype, uint8_t rcode);
    /* Reserved for bounded malformed-input diagnostics; currently unused. */
    void (*log)(void *ctx, int32_t priority, const char *msg); /* nullable */
} tcdns_callbacks;

/* Android builds are 32- or 64-bit; catch accidental C ABI field drift. */
_Static_assert(sizeof(tcdns_callbacks) == (sizeof(void *) == 8 ? 48 : 24),
               "tcdns_callbacks ABI layout changed");
_Static_assert(offsetof(tcdns_callbacks, record_answer) == (sizeof(void *) == 8 ? 8 : 4),
               "tcdns_callbacks.record_answer offset changed");
_Static_assert(offsetof(tcdns_callbacks, is_domain_blocked) == (sizeof(void *) == 8 ? 16 : 8),
               "tcdns_callbacks.is_domain_blocked offset changed");
_Static_assert(offsetof(tcdns_callbacks, blocked_rcode) == (sizeof(void *) == 8 ? 24 : 12),
               "tcdns_callbacks.blocked_rcode offset changed");
_Static_assert(offsetof(tcdns_callbacks, on_blanked) == (sizeof(void *) == 8 ? 32 : 16),
               "tcdns_callbacks.on_blanked offset changed");
_Static_assert(offsetof(tcdns_callbacks, log) == (sizeof(void *) == 8 ? 40 : 20),
               "tcdns_callbacks.log offset changed");

/*
 * Returns the new message length (end of the question section) when a policy
 * hit blanks the response, or TCDNS_UNCHANGED otherwise. Bytes past the new
 * length are left untouched.
 */
size_t tcdns_process_response(uint8_t *data, size_t len,
                               const tcdns_callbacks *cb, void *ctx);

/*
 * Processes the visible prefix of a DNS-over-TCP message in place. The
 * caller must not shrink the buffer on a non-TCDNS_UNCHANGED return. A return
 * value other than TCDNS_UNCHANGED means "blanked in place; keep blanking the
 * rest of this frame"; the caller must keep forwarding the original length.
 */
size_t tcdns_process_partial_response(uint8_t *data, size_t len,
                                       const tcdns_callbacks *cb, void *ctx);

uint32_t tcdns_abi_version(void);

#endif /* TCDNS_H */
