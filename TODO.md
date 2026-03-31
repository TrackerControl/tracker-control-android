# TODO

## System apps VPN routing

Including system apps in the VPN (`include_system_vpn`) causes noticeable download speed slowdowns (e.g. Play Store). Unclear if this is inherent tun overhead or a fixable implementation issue.

- Investigate tun performance: profile packet processing path, test buffer size tuning
- Consider simplifying UX: current flow requires three toggles (`include_system_vpn` -> `manage_system` -> `show_system`). Could consolidate to one toggle that drives both VPN routing and UI visibility.
- Note: always routing system apps through VPN was rejected due to the performance impact, but excluding them breaks Android's "Block connections without VPN" setting.
