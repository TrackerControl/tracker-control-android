#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CARGO_NDK_VERSION=4.1.2
RUST_TOOLCHAIN_FILE="$ROOT/rust-toolchain.toml"

cd "$ROOT"

if ! command -v rustup >/dev/null 2>&1; then
    echo "rustup is required; install it before running this script." >&2
    exit 1
fi

# Older rustup releases, including the one in F-Droid's build image, require
# an explicit toolchain argument. Read the pinned channel, profile, and Android
# targets from rust-toolchain.toml so that file stays authoritative for both
# local and F-Droid builds.
RUST_TOOLCHAIN=$(awk -F '"' '/^[[:space:]]*channel[[:space:]]*=/ { print $2; exit }' "$RUST_TOOLCHAIN_FILE")
RUST_PROFILE=$(awk -F '"' '/^[[:space:]]*profile[[:space:]]*=/ { print $2; exit }' "$RUST_TOOLCHAIN_FILE")
RUST_TARGETS=$(awk -F '"' '/^[[:space:]]*"[^"]+"[[:space:]]*,?[[:space:]]*$/ { print $2 }' "$RUST_TOOLCHAIN_FILE")

if [ -z "$RUST_TOOLCHAIN" ] || [ -z "$RUST_PROFILE" ] || [ -z "$RUST_TARGETS" ]; then
    echo "Could not read the Rust toolchain, profile, or targets from $RUST_TOOLCHAIN_FILE." >&2
    exit 1
fi

rustup toolchain install --no-self-update --profile "$RUST_PROFILE" "$RUST_TOOLCHAIN"
# The explicit install above is compatible with older rustup, but does not
# apply rust-toolchain.toml's target list automatically.
# shellcheck disable=SC2086
rustup target add --toolchain "$RUST_TOOLCHAIN" $RUST_TARGETS

if ! command -v cargo-ndk >/dev/null 2>&1 || \
        [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install "cargo-ndk@$CARGO_NDK_VERSION" --locked
fi

# Populate Cargo's cache while networking is allowed. wgbridgeBuild passes
# --offline and --locked so the later Gradle/F-Droid build cannot fetch or
# silently change dependencies.
cargo fetch --manifest-path "$ROOT/wgbridge-rs/Cargo.toml" --locked
