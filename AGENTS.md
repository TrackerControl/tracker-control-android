# AGENTS.md

This is the shared, tool-agnostic guide for anyone — human or AI agent — working
in this repository. It is the single source of truth for how to navigate, build,
and reason about TrackerControl, plus the rationale used when triaging issues.

One document sits next to this one and stays authoritative for their own scope:

- **[wgbridge-rs/README.md](wgbridge-rs/README.md)** — the Rust WireGuard bridge:
  architecture, the JNI API surface, and how to build/test the crate. Read it
  before touching anything under `wgbridge-rs/` or `net.kollnig.missioncontrol.wg*`.

---

## 1. What TrackerControl is

An Android app that monitors and blocks hidden tracking in other apps. It runs a
local `VpnService` (no root, no external server by default) and detects trackers
by parsing **plaintext DNS** on-device, then blocks by IP according to the active
blocking mode. It optionally offers **Secure DNS (DNS-over-HTTPS)** and **remote
VPN egress** (WireGuard, via Mullvad/IVPN/self-hosted). It is a fork of NetGuard;
the firewall/VPN core still lives in the `eu.faircode.netguard` package.

The design bias is **simplicity and rigour over configurability**, and
**privacy-preserving by construction** (no SSL/TLS interception). These two
sentences decide most feature requests — see §5.

---

## 2. Repository layout — how to navigate

```
app/                         Android app module
  src/main/java/
    eu/faircode/netguard/    NetGuard fork: VPN service, firewall, DB, UI shell
    net/kollnig/missioncontrol/   TrackerControl additions (detection + UI)
  src/main/jni/netguard/     Native C packet engine (built via CMake)
  src/main/assets/           Blocklists (Disconnect, X-Ray, DDG), hosts.txt, GeoLite2
  src/main/res/              Layouts, strings (values-*/ are Crowdin translations)
  src/{github,fdroid,play}/  Per-flavour overrides (differ mainly in update-check)
  build.gradle               App build + the wgbridgeBuild (Rust) task
  CMakeLists.txt             Native C library build
wgbridge-rs/                 Rust crate embedding gotatun (Mullvad WireGuard)
```

**Where things live (the files you'll actually open):**

- **VPN / firewall core** — `eu.faircode.netguard`:
  - `ServiceSinkhole.java` — the `VpnService`. Packet loop wiring, DNS handling,
    `getDns()`, per-connection block dispatch, lifecycle (`onStartCommand`,
    `onRevoke`), MTU/routes, native start/stop. **This is the load-bearing file;
    most connectivity/battery/lifecycle issues trace here.**
  - `DatabaseHelper.java` — the `dns`, `access`, and `log` tables. Note the DNS
    history is **UID-global** (`getQAName` ignores `uid`) — see TODO.md and §5 C-3.
  - `ActivityMain.java`, `ActivitySettings.java`, `ActivityLog.java`,
    `ActivityDns.java` — main UI, settings, raw traffic log, DNS log.
  - `WidgetAdmin.java` — pause/resume alarms (`INTENT_ON`). `ServiceTileMain.java`
    — Quick-Settings tile. `ReceiverAutostart.java` — boot/always-on restart.
  - `VpnRoutes.java` — the tun route set (RFC1918/CGNAT excludes; see TODO.md).
  - Policy helpers: `InteractiveStatePolicy`, `NativeFailureRecoveryPolicy`,
    `NetworkReloadPolicy`, `VpnReplacementSequencer`.
- **Tracker detection + TC UI** — `net.kollnig.missioncontrol`:
  - `data/TrackerList.java` — loads the blocklists into the static
    `hostnameToTracker` map; the heart of detection. Blocking-mode list selection
    lives here (`loadTrackers`). Watch memory (§5 WI-9).
  - `data/InsightsData*.java`, `InsightsActivity.kt` — the insights/summary UI.
  - `analysis/` — static tracker-library detection in app code (dexlib2 signatures).
  - `details/`, `DetailsActivity.java`, `TrackersListAdapter` — per-app tracker
    list + the ALLOWED/BLOCKED toggles.
  - `dns/DnsOverHttpsClient.java`, `dns/DnsProxyServer.java` — Secure DNS (DoH).
  - `wg/` (Kotlin) — WireGuard config/egress: `WgConfig.kt` (wg-quick → UAPI),
    `WgEgress.kt` (lifecycle, hostname re-resolution), `WgConnectivityMonitor.kt`
    (the 1 s stats poll — battery-relevant, §5 WI-10).
  - `wgbridge/` — hand-written JNI bindings to the Rust crate: `Wgbridge`,
    `Tunnel`, `Protector`, `Logger`, `DnsRecorder`. Mirror of `wgbridge-rs`.
- **Native C packet engine** — `app/src/main/jni/netguard/`: `netguard.c`,
  `session.c`, `ip.c`, `tcp.c`, `udp.c`, `icmp.c`, `dns.c` (plaintext DNS parse),
  `tls.c` (SNI, research-only), `dhcp.c`, `pcap.c`. Built by `CMakeLists.txt`.
- **Rust WireGuard bridge** — `wgbridge-rs/` (see its README): `jni_bindings.rs`,
  `tunnel.rs`, `config.rs` (UAPI), `dns.rs` (passive DNS inspection), `transport/`
  (socketpair + tun-fd transports), `keys.rs`, `callbacks.rs`.

**End-to-end data flow.** App packets → `VpnService` tun → native C engine parses
DNS and applies IP-based, per-app blocking → traffic either sinks, exits directly,
or is handed to egress (global SOCKS5, or WireGuard via `wgbridge-rs`/gotatun). DNS
answers (from C, or from the Rust side when WireGuard is up) feed `TrackerList`'s
IP→hostname mapping, which drives future block decisions recorded in `DatabaseHelper`.

**Blocking modes (central to many issues).** *Minimal* loads only the DDG list
(low battery, default for many after onboarding); *Standard* loads Disconnect +
X-Ray + DDG and **allows** ambiguous shared-IP hosts; *Strict* **blocks** those
ambiguous shared-IP hosts. Shared-IP ambiguity + UID-global DNS evidence is the
root of a whole cluster of reports (§5 C-3).

---

## 3. Building, testing, linting

Prerequisites: JDK 17, Android SDK (compile/target SDK 37, min SDK 23), NDK
`27.2.12479018`, CMake. For the WireGuard bridge you also need Rust ≥ 1.95 with the
four Android targets and `cargo-ndk` — but the Gradle build wires that in for you
(it even installs `cargo-ndk` if missing). See `wgbridge-rs/README.md` for the
exact `rustup target add …` list and F-Droid build metadata.

Three product flavours — **github**, **fdroid**, **play** — differ only in the
update-check API; **github** is the normal local dev flavour. Debug builds install
side-by-side (`applicationIdSuffix ".test"`).

```bash
# From the repo root. Use ./gradlew (the wrapper).

# Full debug APK (also triggers the native C + Rust builds):
./gradlew assembleGithubDebug

# Fast compile check while iterating on Java (what most PRs verify against):
./gradlew :app:compileGithubDebugJavaWithJavac -q

# JVM unit tests (Robolectric); swap the flavour as needed:
./gradlew :app:testGithubDebugUnitTest
# One test class/method:
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest'
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest.someMethod'

# Android lint (MissingTranslation / ExtraTranslation are disabled on purpose):
./gradlew :app:lintGithubDebug
```

**Native code builds automatically** with the app:

- The **C engine** builds via CMake through AGP's `externalNativeBuild`.
- The **Rust WireGuard bridge** builds via the `wgbridgeBuild` Gradle task, which
  runs `cargo ndk` for all four ABIs and is a `dependsOn` of `preBuild`. It only
  re-runs when `wgbridge-rs/src/**`, `Cargo.toml`, or `Cargo.lock` change.
  If Gradle can't find `cargo` (Android Studio sanitizes `PATH`), pass
  `-PcargoBin=/path/to/cargo` or put `~/.cargo/bin` on `PATH`.

**Rust host tests** (config/DNS/key parsing — no device needed):

```bash
cd wgbridge-rs && cargo test
```

Reproducibility is deliberately protected (path remapping, stripped `.comment`/
build-id, 16 KB page alignment) in both `build.gradle` and `CMakeLists.txt` — don't
undo those flags casually; IzzyOnDroid green-list depends on them.

---

## 4. Design philosophy & standing constraints

The non-negotiables that decide most changes:

1. **Simplicity over configurability.** TrackerControl is a focused tracker
   blocker, not a general firewall. Do **not** add Rethink-style expert knobs, or
   per-app/per-network firewall rules — that is NetGuard's job. Exceptions are only
   made when a control directly helps a user *recover from breakage*.
2. **Privacy-preserving by construction.** No SSL/TLS interception, ever. Detection
   works off DNS metadata; SNI/TLS parsing is confined to an opt-in research mode
   because acting on it would leak the user's IP to the tracker first.
3. **Battery is a first-class constraint.** Anything periodic must be gated off
   idle/screen-off. Do not make DoH a stronger default until its screen-off cost is
   profiled and fixed (TODO.md). Battery is also frequently mis-attributed to the
   VPN UID — surface stats, don't re-investigate.
4. **Attribution is global, not per-app** (the DNS table has no UID). Treat this as
   a known, deliberately-deferred limitation, not a bug to patch ad-hoc (TODO.md,
   §5 C-3).
