#define main existing_harness_main
#define epoll_ctl mocked_epoll_ctl
#define __wrap_close tcp_half_close_mock_close
#include "tcp_half_close_test.c"
#undef __wrap_close
#undef main
#undef epoll_ctl
#include <errno.h>
#include <fcntl.h>
#include <time.h>
extern int epoll_ctl(int, int, int, struct epoll_event *);
extern int __real_close(int);

int __wrap_close(int file_descriptor) {
    close_calls++;
    return __real_close(file_descriptor);
}

static long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);
    return t.tv_sec*1000L+t.tv_nsec/1000000;
}

static void close_real_fd(int *file_descriptor) {
    if (*file_descriptor >= 0) {
        __real_close(*file_descriptor);
        *file_descriptor = -1;
    }
}

static void finish_real_epoll_session(struct ng_session *session,
                                      struct arguments *args,
                                      int epoll_fd,
                                      int upstream_fd,
                                      int close_count_before,
                                      int expected_state) {
    struct epoll_event event;
    CHECK(epoll_wait(epoll_fd, &event, 1, 100) == 0,
          "finished upstream socket is removed from real epoll");
    CHECK(session->socket < 0 && close_calls == close_count_before + 1,
          "finished upstream socket closes exactly once");
    errno = 0;
    CHECK(fcntl(upstream_fd, F_GETFD) < 0 && errno == EBADF,
          "finished upstream descriptor is closed in the kernel");
    CHECK(session->tcp.state == expected_state,
          "TCP session remains pending after upstream descriptor release");

    session->tcp.time = time(NULL);
    CHECK(check_tcp_session(args, session, 0, SESSION_MAX) == 0 &&
                  session->tcp.state == expected_state,
          "pending TCP session survives an immediate timeout check");
}

static void real_client_fin_then_upstream_eof(void) {
    int upstream[2] = {-1, -1};
    int tun[2] = {-1, -1};
    int epoll_fd = -1;
    int close_count_start = close_calls;
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0, upstream) == 0,
          "client-first real socketpair");
    CHECK(pipe2(tun, O_NONBLOCK) == 0, "client-first real TUN surrogate");
    epoll_fd = epoll_create1(0);
    CHECK(epoll_fd >= 0, "client-first real epoll");
    if (upstream[0] < 0 || upstream[1] < 0 || tun[0] < 0 || tun[1] < 0 ||
        epoll_fd < 0)
        goto cleanup;

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);
    session.ev.events = EPOLLERR;
    session.ev.data.ptr = &session;
    CHECK(epoll_ctl(epoll_fd, EPOLL_CTL_ADD, upstream[0], &session.ev) == 0,
          "client-first real epoll registration");

    uint8_t packet[256];
    uint8_t queued[50];
    memset(queued, 'g', sizeof(queued));
    size_t length = make_packet(packet, 150, session.tcp.local_seq, 65535,
                                queued, sizeof(queued), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "client-first out-of-order data is queued");
    drain_tun(tun[0]);

    length = make_packet(packet, 200, session.tcp.local_seq, 65535,
                         NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "client-first FIN is retained behind the data gap");
    CHECK(session.tcp.client_fin_seen && !session.tcp.client_fin_consumed,
          "client-first FIN is not consumed before the gap drains");
    drain_tun(tun[0]);

    uint8_t prefix[50];
    memset(prefix, 'p', sizeof(prefix));
    length = make_packet(packet, 100, session.tcp.local_seq, 65535,
                         prefix, sizeof(prefix), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "client-first gap-filling data is accepted");
    drain_tun(tun[0]);
    monitor_tcp_session(&args, &session, epoll_fd);

    struct epoll_event event;
    CHECK(epoll_wait(epoll_fd, &event, 1, 1000) == 1,
          "client-first queued data becomes writable");
    if (session.socket >= 0)
        check_tcp_socket(&args, &event, epoll_fd);
    uint8_t received[100];
    CHECK(recv(upstream[1], received, sizeof(received), MSG_DONTWAIT) == 100,
          "client-first queued data drains before FIN consumption");
    CHECK(session.tcp.client_fin_consumed && session.tcp.upstream_write_shutdown,
          "client-first FIN is consumed after the gap drains");
    CHECK(session.socket >= 0 && close_calls == close_count_start,
          "client-first keeps the socket until upstream EOF");

    CHECK(shutdown(upstream[1], SHUT_WR) == 0,
          "client-first peer sends upstream EOF");
    monitor_tcp_session(&args, &session, epoll_fd);
    CHECK(epoll_wait(epoll_fd, &event, 1, 1000) == 1,
          "client-first upstream EOF reaches real epoll");
    int close_count_before = close_calls;
    if (session.socket >= 0)
        check_tcp_socket(&args, &event, epoll_fd);
    finish_real_epoll_session(&session, &args, epoll_fd, upstream[0],
                              close_count_before, TCP_LAST_ACK);
    upstream[0] = -1;

    // The final ACK is deliberately delayed until after the no-spin check.
    length = make_packet(packet, session.tcp.remote_seq, session.tcp.local_seq,
                         65535, NULL, 0, 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1 &&
                  session.tcp.server_fin_acked && session.tcp.state == TCP_CLOSING,
          "client-first final ACK advances LAST_ACK to CLOSING");
    drain_tun(tun[0]);
    clear_tcp_data(&session.tcp);

cleanup:
    close_real_fd(&upstream[0]);
    close_real_fd(&upstream[1]);
    close_real_fd(&tun[0]);
    close_real_fd(&tun[1]);
    close_real_fd(&epoll_fd);
}

static void real_upstream_eof_then_client_fin(void) {
    int upstream[2] = {-1, -1};
    int tun[2] = {-1, -1};
    int epoll_fd = -1;
    int close_count_start = close_calls;
    CHECK(socketpair(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0, upstream) == 0,
          "upstream-first real socketpair");
    CHECK(pipe2(tun, O_NONBLOCK) == 0, "upstream-first real TUN surrogate");
    epoll_fd = epoll_create1(0);
    CHECK(epoll_fd >= 0, "upstream-first real epoll");
    if (upstream[0] < 0 || upstream[1] < 0 || tun[0] < 0 || tun[1] < 0 ||
        epoll_fd < 0)
        goto cleanup;

    struct ng_session session;
    struct context context;
    struct arguments args;
    make_session(&session, upstream[0], TCP_ESTABLISHED);
    make_args(&args, &context, &session, tun[1]);
    session.ev.events = EPOLLERR;
    session.ev.data.ptr = &session;
    CHECK(epoll_ctl(epoll_fd, EPOLL_CTL_ADD, upstream[0], &session.ev) == 0,
          "upstream-first real epoll registration");

    CHECK(shutdown(upstream[1], SHUT_WR) == 0,
          "upstream-first peer sends EOF");
    monitor_tcp_session(&args, &session, epoll_fd);
    struct epoll_event event;
    CHECK(epoll_wait(epoll_fd, &event, 1, 1000) == 1,
          "upstream-first EOF reaches real epoll");
    if (session.socket >= 0)
        check_tcp_socket(&args, &event, epoll_fd);
    CHECK(session.tcp.upstream_read_eof && session.tcp.server_fin_sent &&
                  session.tcp.state == TCP_FIN_WAIT1,
          "upstream-first EOF sends a FIN while the client direction remains open");
    CHECK(session.socket >= 0 && close_calls == close_count_start,
          "upstream-first keeps the socket before client FIN consumption");
    drain_tun(tun[0]);

    uint8_t packet[256];
    uint8_t queued[50];
    memset(queued, 'g', sizeof(queued));
    uint32_t withheld_ack = session.tcp.local_seq - 1;
    size_t length = make_packet(packet, 150, withheld_ack, 65535,
                                queued, sizeof(queued), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "upstream-first out-of-order data is queued");
    drain_tun(tun[0]);
    length = make_packet(packet, 200, withheld_ack, 65535,
                         NULL, 0, 1);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "upstream-first FIN is retained behind the data gap");
    drain_tun(tun[0]);
    uint8_t prefix[50];
    memset(prefix, 'p', sizeof(prefix));
    length = make_packet(packet, 100, withheld_ack, 65535,
                         prefix, sizeof(prefix), 0);
    CHECK(handle_tcp(&args, packet, length, packet + sizeof(struct iphdr),
                     1, 1, NULL, epoll_fd) == 1,
          "upstream-first gap-filling data is accepted");
    drain_tun(tun[0]);
    monitor_tcp_session(&args, &session, epoll_fd);
    CHECK(epoll_wait(epoll_fd, &event, 1, 1000) == 1,
          "upstream-first queued data becomes writable");
    int close_count_before = close_calls;
    if (session.socket >= 0)
        check_tcp_socket(&args, &event, epoll_fd);
    uint8_t received[100];
    CHECK(recv(upstream[1], received, sizeof(received), MSG_DONTWAIT) == 100,
          "upstream-first queued data drains before FIN consumption");
    CHECK(session.tcp.client_fin_consumed && session.tcp.upstream_write_shutdown,
          "upstream-first FIN is consumed after the gap drains");
    CHECK(session.tcp.state == TCP_FIN_WAIT1,
          "upstream-first remains FIN_WAIT1 while the final ACK is withheld");

    finish_real_epoll_session(&session, &args, epoll_fd, upstream[0],
                              close_count_before, TCP_FIN_WAIT1);
    upstream[0] = -1;
    session.tcp.time = time(NULL) - TCP_CLOSE_TIMEOUT - 1;
    CHECK(check_tcp_session(&args, &session, 0, SESSION_MAX) == 0 &&
                  session.tcp.state == TCP_CLOSE,
          "upstream-first session closes on its FIN timeout without an ACK");
    drain_tun(tun[0]);
    clear_tcp_data(&session.tcp);

cleanup:
    close_real_fd(&upstream[0]);
    close_real_fd(&upstream[1]);
    close_real_fd(&tun[0]);
    close_real_fd(&tun[1]);
    close_real_fd(&epoll_fd);
}

static void real_stream(uint32_t start) {
    int pair[2],tun[2];
    CHECK(socketpair(AF_UNIX,SOCK_STREAM|SOCK_NONBLOCK,0,pair)==0,"real stream pair");
    CHECK(pipe2(tun,O_NONBLOCK)==0,"nonblocking TUN surrogate");
    int ep=epoll_create1(0), size=1024;
    setsockopt(pair[0],SOL_SOCKET,SO_SNDBUF,&size,sizeof size);
    struct ng_session s; struct context ctx; struct arguments args;
    make_session(&s,pair[0],TCP_ESTABLISHED); make_args(&args,&ctx,&s,tun[1]);
    s.tcp.remote_seq=start; s.tcp.remote_start=start;
    s.ev.events=EPOLLERR; s.ev.data.ptr=&s;
    CHECK(epoll_ctl(ep,EPOLL_CTL_ADD,pair[0],&s.ev)==0,"real epoll registration");
    unsigned char expected[32768],actual[32768],packet[1024];
    for(int i=0;i<32768;i++) expected[i]=(i*31+i/251)&255;
    for(int offset=32768-512;offset>=0;offset-=512) {
        size_t n=make_packet(packet,start+offset,500,65535,expected+offset,512,0);
        CHECK(handle_tcp(&args,packet,n,packet+sizeof(struct iphdr),1,1,NULL,ep)==1,"reordered segment accepted");
        drain_tun(tun[0]);
    }
    size_t n=make_packet(packet,start+32768,500,65535,NULL,0,1);
    CHECK(handle_tcp(&args,packet,n,packet+sizeof(struct iphdr),1,1,NULL,ep)==1,"queued FIN accepted");
    drain_tun(tun[0]);
    send_limit=17;
    int received=0,eof=0,events=0,backpressure=0; long deadline=now_ms()+15000;
    while(!eof && now_ms()<deadline) {
        monitor_tcp_session(&args,&s,ep);
        struct epoll_event event;
        int ready=epoll_wait(ep,&event,1,20);
        CHECK(ready>=0,"kernel readiness wait");
        if(ready>0) { check_tcp_socket(&args,&event,ep); events++; }
        drain_tun(tun[0]);
        if(received==0 && ready>0 && events<2000) continue;
        if(received==0 && ready==0 && events>0) backpressure=1;
        unsigned char buffer[4096]; int got;
        while((got=recv(pair[1],buffer,sizeof buffer,0))>0) {
            CHECK(received+got<=32768,"no duplicate stream bytes");
            if(received+got<=32768) memcpy(actual+received,buffer,got);
            received+=got;
        }
        if(got==0) eof=1;
    }
    CHECK(eof && received==32768,"all bytes arrive before real EOF");
    CHECK(backpressure,"full send buffer suppresses writable readiness until peer drains");
    CHECK(memcmp(expected,actual,32768)==0,"reordered short-write stream is byte exact");
    CHECK(s.tcp.client_fin_consumed && s.tcp.upstream_write_shutdown,"real write half-close completes");
    monitor_tcp_session(&args,&s,ep);
    struct epoll_event idle;
    CHECK(epoll_wait(ep,&idle,1,50)==0,"drained connection does not spin on writable events");
    printf("real_epoll start=%u bytes=%d events=%d passed\n",start,received,events);
    send_limit=0; clear_tcp_data(&s.tcp);
    __real_close(pair[0]); __real_close(pair[1]); __real_close(tun[0]); __real_close(tun[1]); __real_close(ep);
}
static void real_closed_window(void) {
    int pair[2],tun[2];
    CHECK(socketpair(AF_UNIX,SOCK_STREAM|SOCK_NONBLOCK,0,pair)==0,"HUP socketpair");
    CHECK(pipe2(tun,O_NONBLOCK)==0,"HUP TUN surrogate");
    int ep=epoll_create1(0);
    struct ng_session s; struct context ctx; struct arguments args;
    make_session(&s,pair[0],TCP_ESTABLISHED); make_args(&args,&ctx,&s,tun[1]);
    s.tcp.send_window=0; s.ev.events=EPOLLERR; s.ev.data.ptr=&s;
    CHECK(epoll_ctl(ep,EPOLL_CTL_ADD,pair[0],&s.ev)==0,"HUP real epoll registration");
    CHECK(write(pair[1],"DATA",4)==4,"queue unread upstream payload");
    __real_close(pair[1]);
    struct epoll_event event;
    CHECK(epoll_wait(ep,&event,1,1000)==1,"real HUP arrives");
    check_tcp_socket(&args,&event,ep);
    CHECK(s.tcp.upstream_hup_pending && !s.tcp.upstream_read_eof,"HUP defers EOF behind a closed window");
    monitor_tcp_session(&args,&s,ep);
    if(epoll_wait(ep,&event,1,50)==1) check_tcp_socket(&args,&event,ep);
    monitor_tcp_session(&args,&s,ep);
    CHECK(epoll_wait(ep,&event,1,100)==0,"HUP stays dormant with a closed window");
    s.tcp.send_window=65535; monitor_tcp_session(&args,&s,ep);
    CHECK(epoll_wait(ep,&event,1,1000)==1,"window reopening re-arms real readiness");
    check_tcp_socket(&args,&event,ep);
    CHECK(s.tcp.local_seq>=504,"unread payload forwarded after window reopening");
    drain_tun(tun[0]); clear_tcp_data(&s.tcp);
    __real_close(pair[0]); __real_close(tun[0]); __real_close(tun[1]); __real_close(ep);
    puts("real_epoll closed-window HUP and reopen passed");
}
int main(void) {
    real_stream(100); real_stream(UINT32_MAX-16000);
    real_closed_window();
    real_client_fin_then_upstream_eof();
    real_upstream_eof_then_client_fin();
    return failures?1:0;
}
