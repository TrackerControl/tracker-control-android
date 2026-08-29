#!/usr/bin/env python3
"""Refresh the bundled DuckDuckGo Tracker Radar list.

TrackerControl uses DuckDuckGo's Android Tracker Data Set (TDS) for additional,
mobile-specific tracker identification (see ``TrackerList.loadDuckDuckGoTrackers``).
The loader only reads, per tracker domain, ``owner.displayName`` and ``default``
("block"/"ignore"). The full upstream TDS is ~1.6 MB of rules / prevalence /
entities the app never touches, so the committed asset
``app/src/main/assets/duckduckgo-android-tds.json`` keeps only the consumed
fields: ``{ owner: {name, displayName}, default }`` per tracker (≈100 KB).

One class of upstream ``rules`` does survive the strip: under a ``default:
"block"`` tracker, a rule whose regex is a bare hostname (no path, no wildcard)
with ``action: "ignore"`` and no exception/option context is DDG's curated
breakage fix at a granularity a DNS-level blocker can honour (e.g.
``oauth.reddit.com`` under blocked ``reddit.com``). Each such hostname is
emitted as its own ``default: "ignore"`` entry; ``TrackerList.findTracker``
checks the exact hostname before walking parent domains, so the exception wins
without any loader changes. Everything else in ``rules`` is URL-path- or
context-scoped and is invisible to DNS blocking, so it stays dropped.

This script fetches the current TDS, strips it to those fields, validates that
every entry still exposes a string ``default`` and ``owner.displayName`` (the
Java loader aborts the whole list otherwise), and rewrites the asset only when
the content actually changed.

Note: this tracks the *current* upstream tracker set. If it has grown since the
last bundled snapshot, more domains become labelled/blockable — which the
blocking baseline test in CI is there to vet.

Usage:
    python3 scripts/update_ddg_tds.py           # fetch + refresh asset
    python3 scripts/update_ddg_tds.py --check    # validate only, never write
    python3 scripts/update_ddg_tds.py --input f  # use a local android-tds.json

Exit status:
    0  asset already up to date, or successfully refreshed / validated
    1  fetch or validation failed (asset left untouched)

Data license: Tracker Radar is Apache-2.0 (DuckDuckGo). This script only mirrors
their published data and does not relicense it.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

DDG_TDS_URL = "https://staticcdn.duckduckgo.com/trackerblocking/v5/current/android-tds.json"

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "duckduckgo-android-tds.json"

# Guard against an obviously-broken/truncated response.
MIN_TRACKERS = 100

# A TDS rule regex that is nothing but a literal hostname: label characters
# joined by escaped dots. Anything with a path (``\/``), a wildcard, a
# character class, or an alternation does not match and is dropped as before.
HOSTNAME_ONLY_RULE = re.compile(r"^[a-z0-9-]+(?:\\\.[a-z0-9-]+)+$")


def hostname_exception(rule) -> str | None:
    """The hostname a rule exempts from blocking, or None.

    Only an unconditional ``action: "ignore"`` rule counts: one carrying
    ``exceptions`` or ``options`` applies in site contexts a DNS-level blocker
    cannot see, so honouring it unconditionally would under-block.
    """
    if not isinstance(rule, dict) or rule.get("action") != "ignore":
        return None
    if rule.get("exceptions") or rule.get("options"):
        return None
    pattern = rule.get("rule")
    if not isinstance(pattern, str) or not HOSTNAME_ONLY_RULE.match(pattern):
        return None
    return pattern.replace("\\.", ".")


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "TrackerControl-updater"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} from {url}")
        return resp.read().decode("utf-8")


def strip(raw: str) -> tuple[str, dict]:
    """Parse, validate and field-strip the TDS. Returns (asset_text, stats)."""
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
            f"only {len(trackers)} trackers (< {MIN_TRACKERS}); response looks truncated"
        )

    stripped: dict = {}
    exceptions: dict = {}
    for domain, entry in trackers.items():
        if not isinstance(entry, dict):
            raise RuntimeError(f"tracker '{domain}' is not an object")
        owner = entry.get("owner")
        default = entry.get("default")
        if not isinstance(owner, dict) or not isinstance(owner.get("displayName"), str):
            raise RuntimeError(f"tracker '{domain}' lacks a string owner.displayName")
        if not isinstance(default, str):
            raise RuntimeError(f"tracker '{domain}' lacks a string 'default'")
        stripped[domain] = {
            "owner": {"name": owner.get("name"), "displayName": owner["displayName"]},
            "default": default,
        }
        if default == "block":
            for rule in entry.get("rules") or []:
                host = hostname_exception(rule)
                if host is not None:
                    exceptions[host] = {
                        "owner": {"name": owner.get("name"), "displayName": owner["displayName"]},
                        "default": "ignore",
                    }

    # Breakage exceptions become ordinary entries; a hostname upstream already
    # lists as a tracker in its own right keeps its upstream default.
    emitted_exceptions = 0
    for host, entry in exceptions.items():
        if host not in stripped:
            stripped[host] = entry
            emitted_exceptions += 1

    asset = {"version": root.get("version"), "trackers": stripped}
    # Compact + sorted keys: deterministic output so future diffs are meaningful.
    text = json.dumps(asset, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return text, {"trackers": len(stripped), "exceptions": emitted_exceptions}


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
        help="read android-tds.json from a local file instead of the network",
    )
    ap.add_argument(
        "--url",
        default=DDG_TDS_URL,
        help=f"override the source URL (default: {DDG_TDS_URL})",
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
        asset_text, stats = strip(raw)
    except RuntimeError as e:
        print(f"ERROR: validation failed: {e}", file=sys.stderr)
        return 1

    print(f"Fetched from: {source}")
    print(f"  trackers (after field-strip): {stats['trackers']}")
    print(f"  of which hostname-level breakage exceptions: {stats['exceptions']}")

    if args.check:
        print("Validation OK (--check: asset not written).")
        return 0

    existing = ASSET_PATH.read_text(encoding="utf-8") if ASSET_PATH.exists() else None
    if existing == asset_text:
        print(f"Asset already up to date: {ASSET_PATH.relative_to(REPO_ROOT)}")
        return 0

    ASSET_PATH.write_text(asset_text, encoding="utf-8")
    print(f"Updated asset: {ASSET_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
