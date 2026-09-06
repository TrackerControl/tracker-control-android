#!/usr/bin/env bash
# Host framing, buffer lifetime and real Rust-parser regression tests.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
OUT=${OUT:-$(mktemp -d)}
mkdir -p "$OUT"
COMMON=(-O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined
        -fno-sanitize-recover=all -fno-omit-frame-pointer
        -Iapp/src/main/jni/netguard)
"${CC:-cc}" "${COMMON[@]}" app/src/test/native/dns_frame_test.c \
    app/src/main/jni/netguard/dns_frame.c -o "$OUT/dns_frame_test"
"$OUT/dns_frame_test"
"${CC:-cc}" "${COMMON[@]}" app/src/test/native/dns_frame_allocation_test.c \
    -o "$OUT/dns_frame_allocation_test"
"$OUT/dns_frame_allocation_test"
export CARGO_TARGET_DIR="$PWD/wgbridge-rs/target"
cargo build --manifest-path wgbridge-rs/Cargo.toml --lib --locked --offline
"${CC:-cc}" "${COMMON[@]}" app/src/test/native/dns_frame_record_test.c \
    app/src/main/jni/netguard/dns_frame.c app/src/main/jni/netguard/dns.c \
    -D_GNU_SOURCE -include app/src/test/native/host_compat/linux_test.h \
    -idirafter app/src/test/native/host_compat -L"$CARGO_TARGET_DIR/debug" \
    -lwgbridge -Wl,-rpath,"$CARGO_TARGET_DIR/debug" -o "$OUT/dns_frame_record_test"
"$OUT/dns_frame_record_test"
