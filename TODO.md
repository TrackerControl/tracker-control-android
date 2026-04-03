# TODO

## ParcelFileDescriptor Race Fix

The VPN file descriptor can be closed by `stopVPN()` while native code in `jni_run()` is still using it, causing EBADF errors and VPN tunnel failures — typically triggered by network transitions (WiFi/mobile).

**Root cause:** `stopNative()` calls `jni_stop()` + `thread.join()`, then `stopVPN()` immediately closes the FD. There is no guarantee native code has fully released the FD when `join()` returns. A 500ms `Thread.sleep()` in one reload path (ServiceSinkhole.java:607) is evidence of this race.

**Current status: Do not fix.** The race is theoretically real but has never been observed in practice — no user reports and the maintainer has never seen the error. The existing 500ms sleep appears to be a sufficient mitigation. If the race does hit, `nativeExit()` surfaces it as an error notification and disables the VPN, so it would not go unnoticed.

**If revisited:** The proposed fix would move the FD close into `stopNative()`, after `jni_clear()`, so the sequence becomes: `jni_stop()` -> `join thread` -> `jni_clear()` -> `close FD`. Risk is high — touches the critical VPN path, and the likely failure modes (double-close, FD leak) are harder to detect than the original race. Only worth pursuing if user reports indicate the race is actually happening.

## System apps VPN routing

Including system apps in the VPN (`include_system_vpn`) causes noticeable download speed slowdowns (e.g. Play Store). Unclear if this is inherent tun overhead or a fixable implementation issue.

- Investigate tun performance: profile packet processing path, test buffer size tuning
- Consider simplifying UX: current flow requires three toggles (`include_system_vpn` -> `manage_system` -> `show_system`). Could consolidate to one toggle that drives both VPN routing and UI visibility.
- Note: always routing system apps through VPN was rejected due to the performance impact, but excluding them breaks Android's "Block connections without VPN" setting.

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

## Material 3 migration — completed

The M3 migration is complete. All themes, components, text appearances, button styles, dialog builders, and layout-level theme references use Material 3 equivalents.

### Edge-to-edge
Edge-to-edge is handled globally via `EdgeToEdge.enable()` in `ApplicationEx`, applied to all activities via lifecycle callbacks. Uses `SystemBarStyle.dark(colorPrimaryDark)` for the status bar (red/dark red scrim with white icons) and `SystemBarStyle.auto(transparent, transparent)` for the navigation bar. The old `AppTheme.EdgeToEdge` style and per-activity manual insets handling in `DetailsActivity` have been removed.

**Important:** The `SystemBarStyle.dark(colorPrimaryDark)` parameter is critical. Without it, `EdgeToEdge.enable()` defaults to a transparent status bar, and M3's light theme produces white status bar icons on a white surface — making the status bar invisible. If the edge-to-edge call is ever changed, ensure a dark status bar style is always specified.

### Remaining AppCompat reference
`ActionBar.Red` in `styles.xml` still uses `Widget.AppCompat.ActionBar.Solid` as parent — there is no Material 3 equivalent for the framework ActionBar widget style. This is cosmetic; the action bar renders correctly with M3 theming applied via `ActionBarTheme.Red` (which uses `ThemeOverlay.Material3.Dark.ActionBar`).
