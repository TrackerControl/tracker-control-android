#!/usr/bin/env python3
"""Refresh the bundled Disconnect.me tracker list.

TrackerControl identifies tracker companies (and, in domain-based blocking mode,
blocks them) using Disconnect.me's ``services.json`` — the same list Mozilla
repackages and serves to Firefox via its "shavar" endpoint. TrackerControl
consumes the canonical upstream directly (see ``TrackerList.loadDisconnectTrackers``).

The committed asset ``app/src/main/assets/disconnect-blacklist.reversed.json`` is
the whole ``services.json`` object stored *reversed* character-by-character — an
old workaround for anti-virus scanners flagging the raw domain list (see
issue #30). This script fetches the current ``services.json``, validates it, and
rewrites the reversed asset only when the content actually changed.

Note: Disconnect occasionally renames or adds categories. This script does not
police that (categories fold safely to "Uncategorised" at runtime); the
``DisconnectCategoryCoverageTest`` unit test is the flag that fails the build
when a category can no longer be mapped to a UI bucket.

Usage:
    python3 scripts/update_disconnect_list.py           # fetch + refresh asset
    python3 scripts/update_disconnect_list.py --check    # validate only, never write
    python3 scripts/update_disconnect_list.py --input f  # use a local services.json

Exit status:
    0  asset already up to date, or successfully refreshed / validated
    1  fetch or validation failed (asset left untouched)

Data license: ``services.json`` is CC BY-NC-SA 4.0 (Disconnect, Inc.); its
"license" field is preserved verbatim inside the asset. This script only mirrors
the published list and does not relicense it.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

DISCONNECT_URL = (
    "https://raw.githubusercontent.com/disconnectme/"
    "disconnect-tracking-protection/master/services.json"
)

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "disconnect-blacklist.reversed.json"

# Guard against an obviously-broken/truncated response.
MIN_BYTES = 50_000


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "TrackerControl-updater"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} from {url}")
        return resp.read().decode("utf-8")


def validate(raw: str) -> dict:
    """Parse and structurally validate services.json. Returns stats on success."""
    if len(raw.encode("utf-8")) < MIN_BYTES:
        raise RuntimeError(f"payload is only {len(raw)} chars (< {MIN_BYTES}); looks truncated")

    try:
        root = json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"payload is not valid JSON: {e}") from e

    if not isinstance(root, dict) or "categories" not in root:
        raise RuntimeError("payload has no top-level 'categories' object")

    categories = root["categories"]
    if not isinstance(categories, dict) or not categories:
        raise RuntimeError("'categories' is empty or not an object")

    return {"categories": sorted(categories.keys())}


def reverse(text: str) -> str:
    """Reverse by code point, mirroring Java's StringBuilder(str).reverse()."""
    reversed_text = text[::-1]
    if reversed_text[::-1] != text:  # defensive; true for all BMP text
        raise RuntimeError("reverse round-trip mismatch")
    return reversed_text


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument(
        "--check",
        action="store_true",
        help="validate the (fetched or --input) payload but never write the asset",
    )
    ap.add_argument(
        "--input",
        metavar="FILE",
        help="read services.json from a local file instead of the network",
    )
    ap.add_argument(
        "--url",
        default=DISCONNECT_URL,
        help=f"override the source URL (default: {DISCONNECT_URL})",
    )
    args = ap.parse_args()

    try:
        if args.input:
            raw = Path(args.input).read_text(encoding="utf-8")
            source = args.input
        else:
            raw = fetch(args.url)
            source = args.url
    except Exception as e:  # noqa: BLE001 - report any fetch/read failure cleanly
        print(f"ERROR: could not obtain payload: {e}", file=sys.stderr)
        return 1

    try:
        stats = validate(raw)
    except RuntimeError as e:
        print(f"ERROR: validation failed: {e}", file=sys.stderr)
        return 1

    print(f"Fetched from: {source}")
    print(f"  categories ({len(stats['categories'])}): {', '.join(stats['categories'])}")

    if args.check:
        print("Validation OK (--check: asset not written).")
        return 0

    reversed_text = reverse(raw)
    existing = ASSET_PATH.read_text(encoding="utf-8") if ASSET_PATH.exists() else None
    if existing == reversed_text:
        print(f"Asset already up to date: {ASSET_PATH.relative_to(REPO_ROOT)}")
        return 0

    ASSET_PATH.write_text(reversed_text, encoding="utf-8")
    print(f"Updated asset: {ASSET_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
