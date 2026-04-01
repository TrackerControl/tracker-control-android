# TODO

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
