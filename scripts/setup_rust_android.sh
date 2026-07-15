#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CARGO_NDK_VERSION=4.1.2

cd "$ROOT"

if ! command -v rustup >/dev/null 2>&1; then
    echo "rustup is required; install it before running this script." >&2
    exit 1
fi

# With no explicit toolchain argument, rustup installs the active toolchain.
# Running from ROOT makes rust-toolchain.toml authoritative for the compiler,
# profile, components, and Android targets.
rustup toolchain install --no-self-update

if ! command -v cargo-ndk >/dev/null 2>&1 || \
        [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install "cargo-ndk@$CARGO_NDK_VERSION" --locked
fi

# Populate Cargo's cache while networking is allowed. wgbridgeBuild passes
# --offline and --locked so the later Gradle/F-Droid build cannot fetch or
# silently change dependencies.
cargo fetch --manifest-path "$ROOT/wgbridge-rs/Cargo.toml" --locked
