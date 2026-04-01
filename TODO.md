# TODO

## ParcelFileDescriptor Race Fix

The VPN file descriptor can be closed by `stopVPN()` while native code in `jni_run()` is still using it, causing EBADF errors and VPN tunnel failures — typically triggered by network transitions (WiFi/mobile).

**Root cause:** `stopNative()` calls `jni_stop()` + `thread.join()`, then `stopVPN()` immediately closes the FD. There is no guarantee native code has fully released the FD when `join()` returns. A 500ms `Thread.sleep()` in one reload path (ServiceSinkhole.java:607) is evidence of this race.

**Proposed fix:** Move the FD close into `stopNative()`, after `jni_clear()`, so the sequence becomes: `jni_stop()` -> `join thread` -> `jni_clear()` -> `close FD`. Then `stopVPN()` no longer closes the FD. Each callsite of `stopVPN()` needs auditing to prevent double-close or missed-close.

**Risk:** High — touches the critical VPN path. A bug here makes the VPN completely non-functional rather than occasionally racy. Needs careful testing on real devices across network transitions.

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
