#define main existing_harness_main
#define epoll_ctl mocked_epoll_ctl
#include "tcp_half_close_test.c"
#undef main
#undef epoll_ctl
#include <time.h>
extern int epoll_ctl(int, int, int, struct epoll_event *);
extern int __real_close(int);

static long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);
    return t.tv_sec*1000L+t.tv_nsec/1000000;
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
    return failures?1:0;
}
