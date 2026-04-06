# TODO

## ParcelFileDescriptor Race Fix

The VPN file descriptor can be closed by `stopVPN()` while native code in `jni_run()` is still using it, causing EBADF errors and VPN tunnel failures — typically triggered by network transitions (WiFi/mobile).

**Root cause:** `stopNative()` calls `jni_stop()` + `thread.join()`, then `stopVPN()` immediately closes the FD. There is no guarantee native code has fully released the FD when `join()` returns. A 500ms `Thread.sleep()` in one reload path (ServiceSinkhole.java:607) is evidence of this race.

**Current status: Do not fix.** The race is theoretically real but has never been observed in practice — no user reports and the maintainer has never seen the error. The existing 500ms sleep appears to be a sufficient mitigation. If the race does hit, `nativeExit()` surfaces it as an error notification and disables the VPN, so it would not go unnoticed.

**If revisited:** The proposed fix would move the FD close into `stopNative()`, after `jni_clear()`, so the sequence becomes: `jni_stop()` -> `join thread` -> `jni_clear()` -> `close FD`. Risk is high — touches the critical VPN path, and the likely failure modes (double-close, FD leak) are harder to detect than the original race. Only worth pursuing if user reports indicate the race is actually happening.

## LAN and tethering VPN routing (resolved knowledge)

The `VpnRoutes` exclusions for RFC1918 / CGNAT / link-local are **defense-in-depth, not load-bearing**. On modern Android, LAN access and tethering already bypass the app's VPN tun for OS-level reasons:

- **LAN access**: When Wi-Fi is on `192.168.x.0/24`, the kernel routing table has a more-specific connected route for that subnet via `wlan0`. It outranks the VPN's `0.0.0.0/0`, so local-subnet packets never hit the tun interface regardless of what the VPN advertises.
- **Tethering (USB/hotspot)**: Tethered traffic is forwarded by `netd`/`iptables` in a separate routing context and does not traverse the owning app's `VpnService` tun at all.

The old `lan`/`tethering`/`subnet` toggles in NetGuard were effectively no-ops on modern Android and were removed in PR #546. The new static excludes still matter for the edge case of reaching an RFC1918 destination that is *not* on the currently connected subnet (e.g. talking to `10.x` from a `192.168.x` Wi-Fi), where the kernel would otherwise prefer the VPN's default route.

Don't re-litigate this when someone asks why LAN/tethering work without any toggle — it's intentional.

## System apps VPN routing

Routing system apps through the VPN (`include_system_vpn`) is a noticeable battery drain. The working hypothesis is **wakeup frequency**, not tun throughput: while TC runs permanently as a VPN, any system-app background activity (Play Store updates, Play Services sync, carrier services, etc.) has to traverse the tun and wakes the packet-processing threads. Excluding system apps lets those flows bypass TC entirely so the CPU can stay idle.

Earlier framing as a "download speed slowdown" was likely a misread of the battery symptom — raw tun throughput is probably fine.

- Investigate wakeup behaviour rather than throughput: count wakeups / time-in-packet-loop with system apps included vs excluded, not MB/s.
- Consider simplifying UX: current flow requires three toggles (`include_system_vpn` -> `manage_system` -> `show_system`). Could consolidate to one toggle that drives both VPN routing and UI visibility.
- Note: always routing system apps through VPN was rejected due to the battery impact, but excluding them breaks Android's "Block connections without VPN" setting.

## SNI inspection for tracker detection

Currently we identify trackers purely through DNS interception. Parsing the TLS ClientHello to extract the SNI (Server Name Indication) hostname would improve detection accuracy — it would catch trackers that bypass DNS-based blocking via hardcoded IPs, DNS-over-HTTPS, or cached DNS results. However, there are significant trade-offs:

**Benefits:**
- Catches connections that evade DNS-based detection
- Could resolve ambiguous domain→IP mappings (the `uncertain` flag problem), since SNI gives the actual hostname the app intended to connect to
- Would reduce false positives from shared infrastructure (CDNs, cloud hosting) where multiple domains resolve to the same IP

**Concerns:**
- SNI is only available in the TLS ClientHello, which arrives *after* the TCP handshake completes — meaning the user's real IP is already exposed to the tracker server before we can block
- Adds per-connection processing overhead (TLS parsing on every new TCP stream)
- Some apps may react poorly to a mid-handshake block (e.g., aggressive retries, fallback to other protocols)
- ECH (Encrypted Client Hello) is gaining adoption and will make SNI inspection impossible for connections that use it

**If pursued:**
- DNS blocking should remain the primary mechanism (blocks before any connection)
- SNI should be a secondary/fallback layer only
- Need to handle fragmented ClientHello messages across multiple TCP segments
- Consider whether the IP exposure trade-off is acceptable given TC's threat model (local VPN, no remote proxy to hide behind)

## Tracker blocking ambiguity: global DNS evidence vs per-app decisions

Tracker detection currently relies on two separate data stores with different levels of attribution:

- The `access` table stores `uid`, destination IP, block decision, and the `uncertain` flag.
- The `dns` table stores only `qname`, `aname`, `resource`, `time`, and `ttl`. It does **not** store `uid`.

That means `ServiceSinkhole` can ask `getQAName(uid, ip, ...)`, but the current implementation cannot truly answer "which hostname did this app resolve for this IP?". It can only answer "which hostnames have recently been seen for this IP globally?". The `uid` parameter is accepted by the method, but it is not used in the SQL query.

### Why this matters

Tracker blocking is applied per app, but the DNS evidence used to infer whether an IP belongs to a tracker is global. This creates two related ambiguity problems:

1. **Shared-IP ambiguity**
   - Multiple unrelated hostnames can legitimately resolve to the same IP.
   - Some of those hostnames may be trackers; some may not.
   - The current code already models this via the `uncertain` states and blocking-mode policy.

2. **Cross-app attribution ambiguity**
   - Even if app A triggered the DNS observation, app B may later connect to the same IP.
   - The code may then reuse the global hostname evidence when deciding whether to block app B.
   - This is conceptually consistent with the current database model, but it can still lead to surprising per-app blocking decisions.

### Possible low-risk improvement

A relatively small runtime-only change would be to make the in-memory tracker verdict cache UID-aware:

- current shape: cache by destination IP only
- safer shape: cache by `(uid, destination IP)`

This would reduce cross-app cache contamination, because one app's inferred tracker verdict would no longer automatically carry over to another app. Importantly, this would **not** change the underlying database model and would **not** make DNS attribution truly app-specific. It would only make cache reuse more conservative.

### Why this is still not a full fix

Even with a UID-aware cache:

- the DNS table would still be global
- `getQAName(...)` would still return globally observed hostnames for an IP
- uncertainty handling would still be necessary

So a UID-aware cache is only a partial correctness improvement. It does not solve the deeper attribution problem.

### Real fix if stronger attribution is desired

If the goal is to make per-app tracker blocking decisions rest on genuinely per-app DNS evidence, the persistence model would need redesign. Options include:

- storing DNS observations with a UID
- linking DNS evidence to specific access observations
- moving to a different attribution model entirely

This is a larger change because it affects schema, collection logic, query logic, migration, and likely the semantics of `uncertain`.

### Current decision

Do **not** change this yet.

Reasoning:

- The current behavior matches the limitations of the existing database design.
- A UID-aware runtime cache would be easy to add, but it only partially addresses the issue.
- A true attribution fix is more invasive and needs a deliberate product decision: how conservative should `standard` be when evidence is global and ambiguous?

If revisited later, start by deciding whether the desired goal is:

- just to reduce cross-app cache bleed, or
- to redesign tracker attribution so "per-app blocking" is backed by per-app evidence.
