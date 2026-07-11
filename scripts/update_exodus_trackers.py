#!/usr/bin/env python3
"""Refresh the bundled Exodus Privacy tracker signatures.

TrackerControl detects tracker libraries inside app code and maps traffic to
known trackers using signatures published by Exodus Privacy. At runtime the app
downloads the latest signatures from the official Exodus JSON API and caches
them (see ``TrackerSignatureManager.updateSignatures``). The copy committed at
``app/src/main/assets/trackers.json`` is the *offline fallback* used on first
launch and whenever the live fetch fails.

This script refreshes that bundled fallback from the same official API endpoint
the app uses. It is a robust, reproducible replacement for ad-hoc HTML scraping
of ``reports.exodus-privacy.eu.org`` (see issue #318): it hits the documented
JSON API, validates the payload against the schema the app's parser expects, and
only rewrites the asset when the content actually changed.

Usage:
    python3 scripts/update_exodus_trackers.py           # fetch + refresh asset
    python3 scripts/update_exodus_trackers.py --check    # validate only, never write
    python3 scripts/update_exodus_trackers.py --input f  # use a local JSON file

Exit status:
    0  asset already up to date, or successfully refreshed / validated
    1  fetch or validation failed (asset left untouched)

Data license: the tracker signatures are produced by Exodus Privacy
(https://exodus-privacy.eu.org/) and distributed under their own terms; this
script only mirrors their published API output and does not relicense it.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

# Same endpoint the Android app fetches at runtime
# (TrackerSignatureManager.EXODUS_URL).
EXODUS_URL = "https://reports.exodus-privacy.eu.org/api/trackers"

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "trackers.json"

# Fields the app's parser reads (TrackerSignatureManager.parseTrackers).
REQUIRED_ENTRY_FIELDS = ("id", "name")
EXPECTED_ENTRY_FIELDS = (
    "id",
    "name",
    "code_signature",
    "network_signature",
    "website",
    "categories",
)
# A healthy dump has thousands... no: Exodus currently ships a few hundred.
# Guard only against an obviously-broken/truncated response.
MIN_TRACKERS = 100


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "TrackerControl-updater"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} from {url}")
        return resp.read().decode("utf-8")


def validate(raw: str) -> dict:
    """Parse and structurally validate the payload. Returns stats on success."""
    try:
        root = json.loads(raw)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"payload is not valid JSON: {e}") from e

    if not isinstance(root, dict) or "trackers" not in root:
        raise RuntimeError("payload has no top-level 'trackers' object")

    trackers = root["trackers"]
    if not isinstance(trackers, dict) or not trackers:
        raise RuntimeError("'trackers' is empty or not an object")

    if len(trackers) < MIN_TRACKERS:
        raise RuntimeError(
            f"only {len(trackers)} trackers (< {MIN_TRACKERS}); "
            "response looks truncated"
        )

    n_net, n_code, n_bad_regex = 0, 0, 0
    for key, entry in trackers.items():
        if not isinstance(entry, dict):
            raise RuntimeError(f"tracker '{key}' is not an object")
        for field in REQUIRED_ENTRY_FIELDS:
            if field not in entry:
                raise RuntimeError(f"tracker '{key}' missing required field '{field}'")

        net = entry.get("network_signature") or ""
        code = entry.get("code_signature") or ""
        if net:
            n_net += 1
            # Java uses java.util.regex.Pattern; Python re is a close-enough
            # sanity check to catch grossly malformed rules.
            try:
                re.compile(net)
            except re.error:
                n_bad_regex += 1
        if code:
            n_code += 1

    if n_net == 0:
        raise RuntimeError("no network_signature values present; suspicious payload")

    return {
        "trackers": len(trackers),
        "with_network_signature": n_net,
        "with_code_signature": n_code,
        "uncompilable_network_regex": n_bad_regex,
    }


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
        help="read the payload from a local file instead of the Exodus API",
    )
    ap.add_argument(
        "--url",
        default=EXODUS_URL,
        help=f"override the API endpoint (default: {EXODUS_URL})",
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
    print(f"  trackers:                {stats['trackers']}")
    print(f"  with network_signature:  {stats['with_network_signature']}")
    print(f"  with code_signature:     {stats['with_code_signature']}")
    if stats["uncompilable_network_regex"]:
        print(
            f"  WARNING: {stats['uncompilable_network_regex']} network_signature "
            "value(s) did not compile as Python regex (may still be valid Java regex)"
        )

    if args.check:
        print("Validation OK (--check: asset not written).")
        return 0

    existing = ASSET_PATH.read_text(encoding="utf-8") if ASSET_PATH.exists() else None
    if existing == raw:
        print(f"Asset already up to date: {ASSET_PATH.relative_to(REPO_ROOT)}")
        return 0

    # Write the raw API bytes verbatim so the committed asset stays byte-identical
    # to what the app downloads at runtime (the app compares the two as strings).
    ASSET_PATH.write_text(raw, encoding="utf-8")
    print(f"Updated asset: {ASSET_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
