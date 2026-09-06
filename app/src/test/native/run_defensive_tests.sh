#!/usr/bin/env bash
# Linux: app/src/test/native/run_defensive_tests.sh
# Android: set CC to the NDK compiler, SANITIZERS=undefined and BUILD_ONLY=1;
# copy the resulting binaries to the device and execute them there.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
OUT=${OUT:-$(mktemp -d)}
mkdir -p "$OUT"
COMMON=(-D_GNU_SOURCE -O1 -g -Wall -Wextra -Wno-unused-parameter
        -Wno-sign-compare -fsanitize="${SANITIZERS:-address,undefined}"
        -fno-sanitize-recover=all -fno-omit-frame-pointer
        -ffunction-sections -fdata-sections -Wl,--gc-sections
        -include app/src/test/native/host_compat/linux_test.h
        -idirafter app/src/test/native/host_compat -Iapp/src/main/jni/netguard)
compile() {
    local name=$1
    shift
    "${CC:-cc}" "${COMMON[@]}" -o "$OUT/$name" "$@"
    if [[ ${BUILD_ONLY:-0} != 1 ]]; then "$OUT/$name"; fi
}
compile tcp_defensive_test app/src/test/native/tcp_defensive_test.c \
    app/src/main/jni/netguard/tcp.c app/src/main/jni/netguard/dns_frame.c \
    -Wl,--wrap=close -Wl,--wrap=connect -Wl,--wrap=send
compile udp_defensive_test app/src/test/native/udp_defensive_test.c \
    app/src/main/jni/netguard/udp.c \
    -Wl,--wrap=close -Wl,--wrap=fcntl -Wl,--wrap=recv \
    -Wl,--wrap=sendto -Wl,--wrap=socket -Wl,--wrap=write
compile icmp_defensive_test app/src/test/native/icmp_defensive_test.c \
    app/src/main/jni/netguard/icmp.c \
    -Wl,--wrap=close -Wl,--wrap=fcntl -Wl,--wrap=sendto -Wl,--wrap=socket
compile ip_header_test app/src/test/native/ip_header_test.c \
    app/src/test/native/ip_header_failfast.c app/src/main/jni/netguard/ip.c
compile tcp_window_test app/src/test/native/tcp_window_test.c \
    app/src/main/jni/netguard/tcp.c
compile udp_socket_test app/src/test/native/udp_socket_test.c \
    app/src/main/jni/netguard/udp.c \
    -Wl,--wrap=close -Wl,--wrap=fcntl -Wl,--wrap=recv \
    -Wl,--wrap=sendto -Wl,--wrap=socket -Wl,--wrap=write
compile icmp_socket_test app/src/test/native/icmp_socket_test.c \
    app/src/main/jni/netguard/icmp.c \
    -Wl,--wrap=close -Wl,--wrap=fcntl -Wl,--wrap=sendto -Wl,--wrap=socket
compile ip_flow_policy_test app/src/test/native/ip_flow_policy_test.c \
    app/src/main/jni/netguard/ip.c app/src/main/jni/netguard/policy.c \
    app/src/main/jni/netguard/ip6_ext.c app/src/main/jni/netguard/tls.c \
    -ldl -pthread
