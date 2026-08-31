#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# ///
"""Refresh Konrad Kollnig copyright ranges from each tracked file's Git history.

Only existing notices that name Konrad are changed.  NetGuard's Marcel Bokhorst
notice, as well as all unrelated third-party notices, are deliberately left alone.
Header-only revisions are ignored when determining a file's last substantive year.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KONRAD_HEADER = re.compile(
    r"(?P<intro>Copyright © )(?P<start>20\d{2})(?:(?P<separator>[–-])20\d{2})? Konrad Kollnig"
    r"(?: \(University of Oxford\))?"
)
KONRAD_RESOURCE = re.compile(
    r"(?P<prefix>\\u00A9 (?P<start>20\d{2})[–-])20\d{2} Konrad Kollnig"
)
SHARED_NETGUARD_HEADER = re.compile(
    r"(?P<indent>\s*\* )Copyright © 2015[–-]2020 by Marcel Bokhorst \(M66B\), Konrad\n"
    r"\s*\* Kollnig \(University of Oxford\)"
)


def git(*args: str) -> str:
    return subprocess.check_output(("git", *args), cwd=ROOT, text=True).strip()


def git_show(revision: str, path: str) -> str | None:
    result = subprocess.run(
        ("git", "show", f"{revision}:{path}"),
        cwd=ROOT,
        text=True,
        capture_output=True,
    )
    return result.stdout if result.returncode == 0 else None


def is_notice_only_revision(revision: str, path: str) -> bool:
    diff = subprocess.check_output(
        ("git", "diff", "--unified=0", f"{revision}^", revision, "--", path),
        cwd=ROOT,
        text=True,
    )
    for line in diff.splitlines():
        if line.startswith(("+++", "---")) or not line.startswith(("+", "-")):
            continue
        changed = line[1:].strip()
        if changed and "Copyright" not in changed and "Kollnig (University of Oxford)" not in changed:
            return False
    return True


def last_updated_year(path: str) -> str:
    revisions = git("log", "--format=%H", "--", path).splitlines()
    for revision in revisions:
        before = git_show(f"{revision}^", path)
        after = git_show(revision, path)
        if before is None or after is None or not is_notice_only_revision(revision, path):
            return git("show", "-s", "--format=%ad", "--date=format:%Y", revision)
    raise RuntimeError(f"No revision found for {path}")


def refresh(text: str, year: str) -> str:
    text = SHARED_NETGUARD_HEADER.sub(
        lambda match: (
            f"{match.group('indent')}Copyright © 2015–2020 Marcel Bokhorst (M66B)\n"
            f"{match.group('indent').lstrip(chr(10))}Copyright © 2019–{year} Konrad Kollnig"
        ),
        text,
    )
    text = KONRAD_HEADER.sub(
        lambda match: (
            f"{match.group('intro')}{match.group('start')} Konrad Kollnig"
            if match.group("start") == year
            else (
                f"{match.group('intro')}{match.group('start')}"
                f"{match.group('separator') or '–'}{year} Konrad Kollnig"
            )
        ),
        text,
    )
    return KONRAD_RESOURCE.sub(
        lambda match: f"{match.group('prefix')}{year} Konrad Kollnig", text
    )


def has_recognized_notice(text: str) -> bool:
    return any(
        pattern.search(text)
        for pattern in (SHARED_NETGUARD_HEADER, KONRAD_HEADER, KONRAD_RESOURCE)
    )


def tracked_konrad_files() -> list[str]:
    # Several inherited NetGuard headers split "Konrad Kollnig" over two lines.
    files = git("grep", "-I", "-l", "-e", "Konrad").splitlines()
    return [
        path
        for path in files
        if path and not path.startswith("app/src/main/res/values")
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="apply the refresh")
    parser.add_argument("paths", nargs="*", help="tracked files to refresh")
    args = parser.parse_args()

    paths = args.paths or tracked_konrad_files()
    changed: list[str] = []
    ignored: list[str] = []
    for path in paths:
        file = ROOT / path
        original = file.read_text()
        if not has_recognized_notice(original):
            ignored.append(path)
            continue
        updated = refresh(original, last_updated_year(path))
        if updated == original:
            continue
        changed.append(path)
        if args.write:
            file.write_text(updated)

    for path in changed:
        print(path)
    if ignored:
        print(f"Ignored {len(ignored)} file(s) without a recognized Konrad notice.", file=sys.stderr)
    if changed and not args.write:
        print("Run with --write to apply these changes.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
