#!/bin/sh

# Normalize checkout/worktree paths so equivalent native compiler invocations
# share cache entries across repositories rooted at different absolute paths.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CCACHE_BASEDIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

if command -v ccache >/dev/null 2>&1; then
    exec ccache "$@"
fi

# Ccache remains an optional local acceleration; CI and reproducible release
# builds continue to work on machines where it is not installed.
exec "$@"
