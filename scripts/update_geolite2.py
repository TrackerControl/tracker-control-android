#!/usr/bin/env python3
"""Refresh the bundled MaxMind GeoLite2 Country database.

TrackerControl maps tracker server IPs to countries for its country-level
visualisation using MaxMind's GeoLite2-Country database
(``app/src/main/assets/GeoLite2-Country.mmdb``).

Unlike the other bundled lists, GeoLite2 is license-gated: downloading it needs a
(free) GeoLite2 account license key, and its EULA governs redistribution. That is
exactly why it can only be refreshed through credentialed tooling like this and
never fetched on-device. Provide the key via the ``MAXMIND_LICENSE_KEY``
environment variable (or ``--license-key``).

This script downloads the current database tarball, extracts the ``.mmdb``,
sanity-checks it, and rewrites the asset only when the bytes actually changed.

Usage:
    MAXMIND_LICENSE_KEY=xxxx python3 scripts/update_geolite2.py         # refresh asset
    MAXMIND_LICENSE_KEY=xxxx python3 scripts/update_geolite2.py --check  # validate only
    python3 scripts/update_geolite2.py --input GeoLite2-Country.tar.gz   # use a local tarball

Exit status:
    0  asset already up to date, or successfully refreshed / validated
    1  missing key, download, or validation failed (asset left untouched)

Data license: GeoLite2 data is © MaxMind, Inc. and distributed under the GeoLite2
End User License Agreement. This script only automates the licensed download.
"""

from __future__ import annotations

import argparse
import io
import os
import sys
import tarfile
import urllib.request
from pathlib import Path

EDITION = "GeoLite2-Country"
DOWNLOAD_URL = (
    "https://download.maxmind.com/app/geoip_download"
    "?edition_id={edition}&license_key={key}&suffix=tar.gz"
)

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / f"{EDITION}.mmdb"

MIN_BYTES = 1_000_000
MMDB_MARKER = b"MaxMind.com"  # metadata marker near the end of every .mmdb


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "TrackerControl-updater"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        if resp.status != 200:
            raise RuntimeError(f"HTTP {resp.status} from MaxMind download endpoint")
        return resp.read()


def extract_mmdb(tar_bytes: bytes) -> bytes:
    """Pull the single .mmdb out of the GeoLite2 tarball."""
    target = f"{EDITION}.mmdb"
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:gz") as tar:
        # Match the exact basename of a regular file. Using endswith() here would
        # also match AppleDouble/pax sidecars like "._GeoLite2-Country.mmdb" that
        # some tar implementations add.
        members = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == target]
        if not members:
            raise RuntimeError(f"no {target} found in downloaded archive")
        if len(members) > 1:
            raise RuntimeError(f"multiple {target} entries in archive: {[m.name for m in members]}")
        extracted = tar.extractfile(members[0])
        if extracted is None:
            raise RuntimeError("could not read .mmdb from archive")
        return extracted.read()


def validate(mmdb: bytes) -> dict:
    if len(mmdb) < MIN_BYTES:
        raise RuntimeError(f"database is only {len(mmdb)} bytes (< {MIN_BYTES}); looks truncated")
    if MMDB_MARKER not in mmdb[-200_000:]:
        raise RuntimeError("file does not look like a MaxMind DB (missing metadata marker)")
    return {"bytes": len(mmdb)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument(
        "--check",
        action="store_true",
        help="download/validate but never write the asset",
    )
    ap.add_argument(
        "--input",
        metavar="FILE",
        help="read a local GeoLite2 .tar.gz instead of downloading",
    )
    ap.add_argument(
        "--license-key",
        default=os.environ.get("MAXMIND_LICENSE_KEY"),
        help="MaxMind license key (defaults to $MAXMIND_LICENSE_KEY)",
    )
    args = ap.parse_args()

    try:
        if args.input:
            tar_bytes = Path(args.input).read_bytes()
            source = args.input
        else:
            if not args.license_key:
                raise RuntimeError(
                    "no license key: set MAXMIND_LICENSE_KEY or pass --license-key"
                )
            source = "MaxMind download endpoint"
            tar_bytes = fetch(DOWNLOAD_URL.format(edition=EDITION, key=args.license_key))
        mmdb = extract_mmdb(tar_bytes)
        stats = validate(mmdb)
    except Exception as e:  # noqa: BLE001 - report any failure cleanly
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    print(f"Fetched from: {source}")
    print(f"  database size: {stats['bytes']} bytes")

    if args.check:
        print("Validation OK (--check: asset not written).")
        return 0

    existing = ASSET_PATH.read_bytes() if ASSET_PATH.exists() else None
    if existing == mmdb:
        print(f"Asset already up to date: {ASSET_PATH.relative_to(REPO_ROOT)}")
        return 0

    ASSET_PATH.write_bytes(mmdb)
    print(f"Updated asset: {ASSET_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
