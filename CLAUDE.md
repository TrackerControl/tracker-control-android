# CLAUDE.md — TrackerControl for Android

Guidance for AI agents and contributors. Read this before proposing features or
touching the VPN/packet path. Prefer linking to `README.md` and `TODO.md` over
duplicating them.

## What this is

TrackerControl is an Android app that monitors and blocks in-app tracking
**locally on the device**, using Android's VPN API — no root, and no external
VPN server by default. Trackers are detected primarily by **DNS interception**.
Detection combines the Disconnect blocklist, the DuckDuckGo Tracker Radar, and
an in-house (X-Ray) blocklist; tracker libraries in app code are matched via
ClassyShark3xodus/Exodus signatures.

It deliberately does **not** do SSL/TLS interception — only connection metadata
is logged, so it stays privacy-preserving and works on unrooted devices. It is a
fork/descendant of the **NetGuard** firewall (hence the `eu.faircode.netguard`
package). Licensed under **GPLv3**.

---

## Design philosophy (read this first)

These are the load-bearing product principles. When evaluating a change, the
first question is: **does it help the default (minimal-mode) user, or help a
user recover from breakage?** If it only adds an expert knob, scope it down or
reject it.

- **Simplicity and rigour over configurability.** Do not proliferate
  fine-grained options. Explicitly avoid "Rethink-style" expert configuration
  unless it directly helps users recover from app breakage. (`TODO.md` states
  this directive verbatim.)
- **Sensible defaults; breakage is expected outside minimal mode.** Minimal
  (DuckDuckGo-style) mode is the safe default and the app trades completeness
  for reliability there. In Standard/Strict modes, **some app breakage is
  expected by design** — the user is expected to configure per-app exclusions
  and per-tracker exceptions. That is not a bug to paper over with more toggles.
- **Battery and background behaviour are first-class concerns.** The app runs
  permanently as a foreground VPN service. Any new work in the packet path, or
  any new background/network activity, must be justified against its
  wakeup/battery cost — not just throughput. (See `TODO.md`: system-app routing,
  Secure DNS/DoH screen-off battery.)
- **Privacy-preserving by construction.** No SSL interception; no personal data
  leaves the device by default (the optional remote VPN egress is the only
  exception, and it is user-initiated).
- **Prefer the default user.** A feature that only serves power users at the
  cost of simplicity or battery is the wrong shape for this app.

---

## Architecture (hybrid — important)

Two Java package roots plus a native C tunnel and an optional Rust engine:

- **`eu.faircode.netguard`** — the NetGuard-inherited VPN service core.
  - `ServiceSinkhole` (~3800 lines) is the always-on `VpnService`. It owns the
    tun file descriptor, drives the native tunnel via JNI (`jni_init`,
    `jni_start`, `jni_run`, `jni_stop`, `jni_clear`, …), and handles reloads.
  - Reload/battery churn is throttled by `NetworkReloadPolicy` and
    `InteractiveStatePolicy` (debounced reloads on network/interactivity
    changes).
- **`net.kollnig.missioncontrol`** — TrackerControl's additions: UI
  (activities/fragments/adapters), tracker data model and blocklists
  (`data/`), local DNS proxy + DoH (`dns/`), and the WireGuard glue
  (`wg/`, `wgbridge/`).
- **Native tunnel (core local-inspection path):** C code in
  `app/src/main/jni/netguard/` (`netguard.c`, `ip.c`, `tcp.c`, `udp.c`,
  `dns.c`, `tls.c`, `icmp.c`, `session.c`, `dhcp.c`, `pcap.c`, `util.c`), built
  into `libnetguard.so` via `app/CMakeLists.txt` (CMake + NDK). This does the
  low-level packet and DNS processing that detects trackers.
- **Optional remote egress (downstream/optional):** a Rust WireGuard engine in
  `wgbridge-rs/` that embeds **gotatun** (Mullvad's Rust WireGuard
  implementation). Built into `libwgbridge.so` via `cargo-ndk`. Java glue lives
  in `net.kollnig.missioncontrol.wgbridge` (JNI surface: `Wgbridge`, `Tunnel`,
  `TunnelStats`, `Protector`, `DnsRecorder`) and `net.kollnig.missioncontrol.wg`
  (profile management, key rotation, connectivity monitor for Mullvad / IVPN /
  custom WireGuard). This was recently **migrated from wireguard-go to gotatun
  (Rust)**. It is optional; the netguard C tunnel remains the core path.

Data flow: packets enter the tun → native netguard tunnel inspects/filters
locally (DNS-based tracker detection + blocking) → allowed traffic exits
directly, or, when remote routing is enabled, is forwarded through the gotatun
WireGuard tunnel to the chosen provider.

---

## Blocking modes (central to the philosophy)

Defined around `net.kollnig.missioncontrol.data.BlockingMode` /
`BlockingModeLogic`. Default for new installs is **Minimal**.

- **`MODE_MINIMAL`** — the default and safest mode ("least app breakage"),
  DuckDuckGo-style. Only blocks DDG trackers marked `block` (not `ignore`);
  auto-excludes known VPN-incompatible apps (from `assets/ddg-excluded-apps.json`);
  keeps browsers routed but with app-level tracker protection **off by default**;
  hosts-file blocking disabled; no granular per-tracker controls.
- **`MODE_STANDARD`** — all tracker sources (X-Ray, Disconnect, DDG), with
  per-app and per-tracker controls. Allows the `Content` category to reduce
  breakage. Some breakage expected; user configures exceptions.
- **`MODE_STRICT`** — like Standard but also blocks the `Content` category and
  ambiguous mixed shared-IP cases (a tracker and a non-tracker hostname
  resolving to the same IP). Most aggressive; most breakage.

The **Play** build is forced to Minimal only (`normalizeModeForBuild` /
`enforcePlayStoreMode` collapse any stored mode to `MODE_MINIMAL`).

---

## Build flavors

`flavorDimensions "version"` with three flavors (`app/build.gradle`):

- **`play`** — Play Store build. Restricted to comply with Play policy:
  forced to Minimal blocking mode (Standard/Strict unavailable), self-update
  check disabled (`GITHUB_LATEST_API = ""`), and native code compiled with the
  `-DPLAY` C flag. Note: per `README.md`, Secure DNS (DoH) and remote VPN
  routing are still available in the Play build — the main restriction is
  blocking mode.
- **`fdroid`** — full feature set (all blocking modes). Self-update check
  disabled (`GITHUB_LATEST_API = ""`).
- **`github`** — full feature set, plus GitHub self-update checking enabled
  (`GITHUB_LATEST_API` points at the releases API).

Build types: `debug` (installs alongside release via `.test` applicationId
suffix) and `release` (minified/proguard). App id base `net.kollnig.missioncontrol`,
per-flavor suffix (`.play` / `.fdroid`; `github` has no suffix).

---

## Build & test

Toolchain: Android SDK (compile/target SDK 36, min SDK 23), **Java 17**, Android
**NDK 27.2.12479018** (pinned in `build.gradle`), and **Rust via rustup** with
Android targets + `cargo-ndk` for the WireGuard bridge.

```bash
# Rust prerequisites (once)
rustup target add armv7-linux-androideabi aarch64-linux-android \
                  i686-linux-android x86_64-linux-android
cargo install cargo-ndk

# Build the Rust WireGuard bridge (libwgbridge.so); wired into preBuild
./gradlew :app:wgbridgeBuild

# Compile-check a flavor (fast sanity check) — flavor is Play/Fdroid/Github
./gradlew :app:compileGithubDebugJavaWithJavac
./gradlew :app:compileFdroidDebugJavaWithJavac

# Unit tests (JUnit + Robolectric)
./gradlew :app:testGithubDebugUnitTest

# Assemble an APK
./gradlew :app:assembleGithubRelease
```

Notes:
- The native **C tunnel** (`libnetguard.so`) is built automatically by the AGP
  `externalNativeBuild` (CMake) during assemble — no separate step.
- `wgbridgeBuild` is registered as a dependency of `preBuild`, so a plain
  `assemble` also builds the Rust lib. It shells out to `cargo ndk`; if Gradle
  can't find `cargo`, pass `-PcargoBin=/path/to/cargo`.
- Rust and native builds are reproducible (no checked-in binaries); Android 15+
  16KB page alignment is handled via linker flags in both CMake and the Rust
  `RUSTFLAGS`.

---

## Repository map

- `app/src/main/java/eu/faircode/netguard/` — NetGuard-inherited VPN core.
  Key: `ServiceSinkhole.java` (the VpnService), `NetworkReloadPolicy.java`,
  `InteractiveStatePolicy.java`, `ActivityMain/ActivitySettings/ActivityLog`,
  `Util.java`, `IPUtil.java`.
- `app/src/main/java/net/kollnig/missioncontrol/` — TrackerControl additions.
  - `data/` — tracker model + blocklists (`BlockingMode`, `BlockingModeLogic`,
    `TrackerList`, `TrackerBlocklist`, `Tracker`, `InsightsData*`).
  - `dns/` — local DNS proxy (`DnsProxyServer`) and DoH client
    (`DnsOverHttpsClient`).
  - `wg/` — WireGuard profiles/keys (`WgProfileManager`, `WgEgress`,
    `WgConnectivityMonitor`, `VpnKeyRotationManager`, Mullvad/IVPN generators).
  - `wgbridge/` — JNI surface onto the Rust engine (`Wgbridge`, `Tunnel`, …).
  - top-level activities/adapters for onboarding, details, insights, timeline.
- `app/src/main/jni/netguard/` — native C tunnel source.
- `app/CMakeLists.txt` — native build definition (`libnetguard.so`).
- `wgbridge-rs/` — Rust WireGuard bridge (embeds gotatun); `src/lib.rs`,
  `jni_bindings.rs`, `tunnel.rs`, `dns.rs`, `config.rs`, `keys.rs`, `transport/`.
- `app/src/main/assets/` — blocklists/data: `duckduckgo-android-tds.json`,
  `disconnect-blacklist.reversed.json`, `xray-blacklist.json`, `trackers.json`,
  `ddg-excluded-apps.json`, `hosts.txt`, `GeoLite2-Country.mmdb`, `world.svg`.
- `app/build.gradle` — flavors, native/Rust build wiring, dependencies.
- `README.md` — user-facing overview, VPN support, build instructions, credits.
- `TODO.md` — standing engineering decisions and open investigations (below).

---

## Conventions

- **License headers.** Source files carry a GPLv3 header. Two variants:
  NetGuard-inherited files start with *"This file is from NetGuard."* (Copyright
  Marcel Bokhorst / Konrad Kollnig); TrackerControl-original files start with
  *"TrackerControl is free software…"* (Copyright Konrad Kollnig). Match the
  variant of the file you touch. (Some newer helper files currently omit the
  header — add the GPLv3 header when creating new source files.)
- **Two package roots, clear split.** Put VPN-service / firewall-core changes in
  `eu.faircode.netguard`; put TrackerControl features (tracker data, UI,
  insights, DoH, WireGuard) in `net.kollnig.missioncontrol`. Don't blur them.
- **Java 17**, Android AGP/Gradle. Kotlin is used for some newer files
  (`.kt`) alongside Java.
- Keep the packet path and background/network work lean — see philosophy.

---

## Standing engineering constraints (from `TODO.md`)

Read `TODO.md` for full rationale. Do not re-litigate these:

- **ParcelFileDescriptor race — do not fix.** `stopVPN()` can close the tun FD
  while native `jni_run()` may still use it. The race is theoretically real but
  has never been observed; a 500ms sleep mitigates it, and `nativeExit()` would
  surface any hit. The proposed fix touches the critical VPN path with harder-
  to-detect failure modes — only revisit on real user reports.
- **LAN/tethering routing works without toggles by OS design.** The old
  `lan`/`tethering`/`subnet` toggles were no-ops on modern Android (removed in
  PR #546). Kernel connected routes and `netd`/`iptables` forwarding already
  bypass the app's tun. The static `VpnRoutes` RFC1918/CGNAT excludes are
  defense-in-depth for off-subnet edge cases only. Don't re-add toggles.
- **SNI inspection is research-mode-only.** Extracting the TLS ClientHello SNI
  would improve detection but requires completing the TCP handshake *to the
  tracker* before we can act — leaking the user's IP. It also adds per-connection
  overhead and is defeated by ECH. DNS blocking stays the primary mechanism;
  SNI stays disabled by default (Research mode only).
- **Secure DNS / DoH has an unresolved screen-off battery cost.** The Java DoH
  client can warm the phone while the screen is off (idle connections, retries,
  network-change handling). **Do not make DoH a stronger default** until its idle
  behaviour is profiled and fixed.
- **System-app VPN routing is a battery drain (wakeups, not throughput).**
  Routing system apps through the tun wakes the packet loop on their background
  activity. Investigate wakeup frequency, not MB/s. Excluding them, however,
  breaks Android's "Block connections without VPN".
- **Tracker attribution is global, not per-app (known limitation).** The `dns`
  table has no `uid`; `getQAName(uid, ip, …)` answers "hostnames seen for this IP
  globally," not per-app. A UID-aware runtime cache is a partial mitigation only;
  a true fix needs a schema/attribution redesign. Left as-is deliberately.
