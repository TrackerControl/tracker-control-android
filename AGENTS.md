# AGENTS.md

This is the shared, tool-agnostic guide for anyone — human or AI agent — working
in this repository. Keep it short: everything here is loaded into *every*
session, so anything situational belongs in `agents/docs/` instead (see the table
below). `CLAUDE.md` is a one-line pointer at this file; Codex and other tools
read it directly.

---

## What TrackerControl is

An Android app that monitors and blocks hidden tracking in other apps. It runs a
local `VpnService` (no root, no external server by default) and detects trackers
by parsing **plaintext DNS** on-device, then blocks by IP according to the active
blocking mode. It optionally offers **Secure DNS (DNS-over-HTTPS)** and **remote
VPN egress** (WireGuard, via Mullvad/IVPN/self-hosted). It is a fork of NetGuard;
the firewall/VPN core still lives in the `eu.faircode.netguard` package.

The design bias is **simplicity and rigour over configurability**, and
**privacy-preserving by construction** (no SSL/TLS interception). Those two
sentences decide most feature requests.

---

## Read these when the situation applies

These files are not in context. Read the whole file before doing the work it
covers — don't rely on what you remember of it.

| Read | When |
|---|---|
| `agents/docs/codebase-map.md` | finding your way around: which file owns a behaviour, how a packet flows from tun to egress, what the blocking modes change |
| `agents/docs/build-and-test.md` | anything beyond the commands below — prerequisites, flavours, the native C/Rust builds, the reproducibility flags |
| `agents/docs/device-testing.md` | **before any `adb` command** — flavour choice, seeding away the permission prompts, editing preferences safely |
| `agents/docs/triage.md` | reviewing, triaging or closing an issue — the verdict vocabulary and the two reusable close messages |
| `wgbridge-rs/README.md` | touching `wgbridge-rs/`, `tc-dns`, DNS response rewriting, or `net.kollnig.missioncontrol.wg*` — architecture, the C/JNI API surfaces, building/testing the workspace |
| `docs/RELEASING.md` | cutting a release — version bump, Fastlane changelog, tag-triggered unsigned build, local signing, smoke-test checklist |

---

## Design philosophy & standing constraints

The non-negotiables that decide most changes:

1. **Simplicity over configurability.** TrackerControl is a focused tracker
   blocker, not a general firewall. Do **not** add Rethink-style expert knobs, or
   per-app/per-network firewall rules — that is NetGuard's job. Exceptions are only
   made when a control directly helps a user *recover from breakage*.
2. **Privacy-preserving by construction.** No SSL/TLS interception, ever. Detection
   works off DNS metadata; SNI/TLS parsing is confined to an opt-in research mode
   because acting on it would leak the user's IP to the tracker first. This SNI
   parsing happens in the native `handle_ip()` block/allow decision (`ip.c`), but
   only for directly-routed flows: a flow the remote VPN (WireGuard) tunnels
   never gets the per-flow session state the reassembly needs, so research mode
   collects nothing for it, and the app tells the user so in the Research
   preference summary when WireGuard is on.
3. **Battery is a first-class constraint.** Anything periodic must be gated off
   idle/screen-off. Do not make DoH a stronger default until its screen-off cost is
   profiled and fixed. Battery is also frequently mis-attributed to the
   VPN UID — surface stats, don't re-investigate.
4. **Attribution is global, not per-app** (the DNS table has no UID). Treat this as
   a known, deliberately-deferred limitation, not a bug to patch ad-hoc.

The still-live reasoning behind these constraints (screen-off DoH battery, the
ParcelFileDescriptor close race, LAN/tethering routing, DNS attribution) lives in the
GitHub issue tracker — search there rather than re-deriving.

---

## Build & test commands

```bash
# Fast compile check while iterating on Java (what most PRs verify against):
./gradlew :app:compileGithubDebugJavaWithJavac -q

# JVM unit tests (Robolectric); swap the flavour as needed:
./gradlew :app:testGithubDebugUnitTest
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest'

# Android lint (MissingTranslation / ExtraTranslation are disabled on purpose):
./gradlew :app:lintGithubDebug

# Full debug APK (also triggers the native C + Rust builds):
./gradlew assembleGithubDebug

# Rust host tests (config/DNS/key parsing — no device needed):
cd wgbridge-rs && cargo test
```

Gradle at default log level floods an agent's context. `agents/hooks/gradle-quiet.sh`
adds `-q` to `./gradlew` invocations that don't already carry a log-level flag:
Claude Code applies it automatically as a `PreToolUse` hook (wired in
`.claude/settings.json`), and any other tool can pipe a command through
`agents/hooks/gradle-quiet.sh --rewrite '<command>'` — or just pass `-q` itself.
`-q` hides `BUILD SUCCESSFUL`, so judge success by the exit code, and re-run
without `-q` when you genuinely need the verbose output.

---

## Device work

Full procedure — including how to skip every permission prompt — is in
`agents/docs/device-testing.md`. Two rules that must not be discovered late:

- **Never destroy state without asking.** `adb uninstall`, `pm clear`,
  overwriting a preferences file, revoking a permission: all irreversible, on a
  device that usually holds someone's real configuration. Ask first, naming the
  package and what will be lost. Update in place with `adb install -r`.
- **Announce the VPN side effect.** Enabling the VPN calls
  `VpnService.prepare()`, which revokes whatever VPN app currently holds consent
  — the maintainer's own build, or their real VPN.
