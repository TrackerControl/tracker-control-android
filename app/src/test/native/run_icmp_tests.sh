#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../../../.."
OUT=${OUT:-$(mktemp -d)}
mkdir -p "$OUT"

COMMON=(-D_GNU_SOURCE -O1 -g -Wall -Wextra -Wno-unused-parameter
        -Wno-sign-compare -fsanitize="${SANITIZERS:-address,undefined}"
        -fno-sanitize-recover=all -fno-omit-frame-pointer
        -ffunction-sections -fdata-sections
        -include app/src/test/native/host_compat/linux_test.h
        -idirafter app/src/test/native/host_compat -Iapp/src/main/jni/netguard)

TESTS=(icmp_socket_test icmp_error_test icmp_defensive_test)
WRAPS=(-Wl,--wrap=close -Wl,--wrap=fcntl -Wl,--wrap=getsockopt
       -Wl,--wrap=recv -Wl,--wrap=recvmsg -Wl,--wrap=sendto
       -Wl,--wrap=setsockopt -Wl,--wrap=socket -Wl,--wrap=write)

compile_linux() {
    local name=$1
    shift
    "${CC:-cc}" "${COMMON[@]}" -o "$OUT/$name" \
        "app/src/test/native/$name.c" app/src/main/jni/netguard/icmp.c \
        -Wl,--gc-sections "${WRAPS[@]}"
}

compile_darwin() {
    local object="$OUT/icmp.o"
    "${CC:-cc}" "${COMMON[@]}" \
        -Dclose=__wrap_close -Dfcntl=__wrap_fcntl \
        -Dgetsockopt=__wrap_getsockopt -Drecv=__wrap_recv \
        -Drecvmsg=__wrap_recvmsg -Dsendto=__wrap_sendto \
        -Dsetsockopt=__wrap_setsockopt -Dsocket=__wrap_socket \
        -Dwrite=__wrap_write -c app/src/main/jni/netguard/icmp.c -o "$object"
    for name in "${TESTS[@]}"; do
        "${CC:-cc}" "${COMMON[@]}" -o "$OUT/$name" \
            "app/src/test/native/$name.c" "$object"
    done
}

if [[ "${OSTYPE:-}" == darwin* ]]; then
    # Darwin's linker has no --wrap; rename syscalls on the production object.
    compile_darwin
else
    for name in "${TESTS[@]}"; do
        compile_linux "$name"
    done
fi

if [[ ${BUILD_ONLY:-0} != 1 ]]; then
    for name in "${TESTS[@]}"; do
        "$OUT/$name"
    done
fi
