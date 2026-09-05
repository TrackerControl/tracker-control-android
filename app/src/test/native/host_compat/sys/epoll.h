#ifndef TRACKERCONTROL_NATIVE_TEST_EPOLL_H
#define TRACKERCONTROL_NATIVE_TEST_EPOLL_H

#include <stdint.h>

#define EPOLLIN 0x001
#define EPOLLOUT 0x004
#define EPOLLERR 0x008
#define EPOLLHUP 0x010

#define EPOLL_CTL_ADD 1
#define EPOLL_CTL_DEL 2
#define EPOLL_CTL_MOD 3

struct epoll_event {
    uint32_t events;
    union {
        void *ptr;
        int fd;
        uint32_t u32;
        uint64_t u64;
    } data;
};

int epoll_ctl(int epoll_fd, int operation, int descriptor,
              struct epoll_event *event);

#endif
