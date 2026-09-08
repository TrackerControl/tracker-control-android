#!/usr/bin/env bash
set -euo pipefail

# These tests inject raw ICMP errors into real ping sockets.  They are opt-in:
# runtime needs CAP_NET_RAW (or root) and a Linux/Android network stack.
cd "$(dirname "$0")/../../../.."
if [[ "${OSTYPE:-}" == darwin* && "${CC:-}" != *android* ]]; then
    echo "icmp kernel tests require Linux or Android" >&2
    exit 2
fi

OUT=${OUT:-$(mktemp -d)}
mkdir -p "$OUT"
COMMON=(-D_GNU_SOURCE -O1 -g -Wall -Wextra -Wno-unused-parameter
        -Wno-sign-compare -fno-omit-frame-pointer
        -include app/src/test/native/host_compat/linux_test.h
        -idirafter app/src/test/native/host_compat -Iapp/src/main/jni/netguard)
if [[ -n "${SANITIZERS:-}" ]]; then
    COMMON+=(-fsanitize="$SANITIZERS" -fno-sanitize-recover=all)
fi

for family in 4 6; do
    "${CC:-cc}" "${COMMON[@]}" -Wl,--gc-sections \
        -o "$OUT/icmp_kernel$family" \
        "app/src/test/native/icmp_kernel${family}_test.c" \
        app/src/main/jni/netguard/icmp.c
done

if [[ ${BUILD_ONLY:-0} == 1 || ${RUN_KERNEL:-0} != 1 ]]; then
    echo "icmp kernel tests built in $OUT; set RUN_KERNEL=1 to execute with CAP_NET_RAW"
else
    "$OUT/icmp_kernel4"
    "$OUT/icmp_kernel6"
fi
