# Codebase map

How to navigate TrackerControl: where things live, how a packet flows, and what
the blocking modes actually change.

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

## Where things live (the files you'll actually open)

- **VPN / firewall core** — `eu.faircode.netguard`:
  - `ServiceSinkhole.java` — the `VpnService`. Packet loop wiring, DNS handling,
    `getDns()`, per-connection block dispatch, lifecycle (`onStartCommand`,
    `onRevoke`), MTU/routes, native start/stop. **This is the load-bearing file;
    most connectivity/battery/lifecycle issues trace here.**
  - `DatabaseHelper.java` — the `dns`, `access`, and `log` tables. Note the DNS
    history is **UID-global** (`getQAName` ignores `uid`) — see the standing
    constraints in AGENTS.md.
  - `ActivityMain.java`, `ActivitySettings.java`, `ActivityLog.java`,
    `ActivityDns.java` — main UI, settings, raw traffic log, DNS log.
  - `WidgetAdmin.java` — pause/resume alarms (`INTENT_ON`). `ServiceTileMain.java`
    — Quick-Settings tile. `ReceiverAutostart.java` — boot/always-on restart.
  - `VpnRoutes.java` — the tun route set (RFC1918/CGNAT excludes).
  - Policy helpers: `InteractiveStatePolicy`, `NativeFailureRecoveryPolicy`,
    `NetworkReloadPolicy`, `VpnReplacementSequencer`.
- **Tracker detection + TC UI** — `net.kollnig.missioncontrol`:
  - `data/TrackerList.java` — loads the blocklists into the static
    `hostnameToTracker` map; the heart of detection. Blocking-mode list selection
    lives here (`loadTrackers`). Watch memory.
  - `data/InsightsData*.java`, `InsightsActivity.kt` — the insights/summary UI.
  - `analysis/` — static tracker-library detection in app code (dexlib2 signatures).
  - `details/`, `DetailsActivity.java`, `TrackersListAdapter` — per-app tracker
    list + the ALLOWED/BLOCKED toggles.
  - `dns/DnsOverHttpsClient.java`, `dns/DnsProxyServer.java` — Secure DNS (DoH).
  - `wg/` (Kotlin) — WireGuard config/egress: `WgConfig.kt` (wg-quick → UAPI),
    `WgEgress.kt` (lifecycle, hostname re-resolution), `WgConnectivityMonitor.kt`
    (the 1 s stats poll — battery-relevant).
  - `wgbridge/` — hand-written JNI bindings to the Rust crate: `Wgbridge`,
    `Tunnel`, `Protector`, `Logger`, `DnsRecorder`. Mirror of `wgbridge-rs`.
- **Native C packet engine** — `app/src/main/jni/netguard/`: `netguard.c`,
  `session.c`, `ip.c`, `tcp.c`, `udp.c`, `icmp.c`, `dns.c` (plaintext DNS parse),
  `tls.c` (SNI, research-only), `dhcp.c`, `pcap.c`. Built by `CMakeLists.txt`.
- **Rust WireGuard bridge** — `wgbridge-rs/` (see its README): `jni_bindings.rs`,
  `tunnel.rs`, `config.rs` (UAPI), `dns.rs` (passive DNS inspection), `transport/`
  (socketpair + tun-fd transports), `keys.rs`, `callbacks.rs`.

## End-to-end data flow

App packets → `VpnService` tun → native C engine parses DNS and applies IP-based,
per-app blocking → traffic either sinks, exits directly, or is handed to egress
(global SOCKS5, or WireGuard via `wgbridge-rs`/gotatun). DNS answers (from C, or
from the Rust side when WireGuard is up) feed `TrackerList`'s IP→hostname mapping,
which drives future block decisions recorded in `DatabaseHelper`.

## Blocking modes (central to many issues)

*Minimal* loads only the DDG list (low battery, default for many after
onboarding); *Standard* loads Disconnect + X-Ray + DDG and **allows** ambiguous
shared-IP hosts; *Strict* **blocks** those ambiguous shared-IP hosts. Shared-IP
ambiguity + UID-global DNS evidence is the root of a whole cluster of reports.

## Known limitations (deliberate)

* **Fragmented traffic is dropped on the direct path.** IPv4 packets with
  `IP_MF` set or a non-zero fragment offset (first, middle and last
  fragments alike) drop up front; IPv6 packets whose
  extension-header chain stops at a Fragment (44) or ESP (50) header never
  reach an L4 dispatch branch, so they drop too -- even when the destination
  would be allowed. No reassembly engine, on purpose: memory/battery cost
  outweighs traffic PMTUD keeps rare. IPv4 fragments drop before the
  WireGuard hijack as well, while IPv6 fragmented flows pass through when
  WireGuard-routed (raw forward). ESP stays a permanent limitation.
  See issue #779.
