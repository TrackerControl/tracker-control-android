#ifndef TRACKERCONTROL_NATIVE_TEST_LINUX_H
#define TRACKERCONTROL_NATIVE_TEST_LINUX_H

/* Keep the real Linux protocol headers. Supply only Android/BSD declarations
 * absent from glibc; Android's bionic already provides these declarations. */
#if defined(__linux__) && !defined(__ANDROID__)
#include <stdint.h>
#include <netinet/in.h>
#include <linux/sockios.h>

struct ippseudo {
    struct in_addr ippseudo_src;
    struct in_addr ippseudo_dst;
    uint8_t ippseudo_pad;
    uint8_t ippseudo_p;
    uint16_t ippseudo_len;
};
_Static_assert(sizeof(struct ippseudo) == 12, "IPv4 pseudo-header wire size");
#endif

#endif
