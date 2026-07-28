# Measuring the battery cost of routing system apps (issue #653)

Routing system apps through TrackerControl's tun (the **Monitor system apps**
setting, `include_system_vpn`) has always been reported as costly for battery,
and that is why it is off by default. The working hypothesis is that the cost is
**wakeup frequency**, not tunnel throughput: background chatter from Play Store,
Play Services, carrier services and friends crosses the tun constantly, and each
crossing wakes the native packet loop.

Throughput (MB/s) is the wrong metric for that hypothesis. A few hundred bytes
per minute can still wake the loop hundreds of times per hour, and it is the
wakeups — not the bytes — that keep the CPU out of deep idle. This document
describes the counters the app now records and how to run the comparison.

## What is instrumented

`handle_events()` in `app/src/main/jni/netguard/session.c` is the packet loop:
it walks the session list, computes an epoll timeout, blocks in `epoll_wait()`,
and dispatches whatever became ready. `struct loop_stats`
(`app/src/main/jni/netguard/netguard.h`, implemented in `loopstats.c`) records:

| Counter | Meaning |
| --- | --- |
| `iterations` | loop body entries |
| `polls` | `epoll_wait()` calls |
| `wakeups` | `epoll_wait()` returned at least one ready fd — **the primary metric** |
| `timeouts` | `epoll_wait()` returned 0 (the loop woke on its own timeout) |
| `recheck_polls` | polls whose timeout was capped to `EPOLL_MIN_CHECK` (100 ms) |
| `events_tun` / `events_sock` / `events_pipe` | ready events by source |
| `tun_packets` / `tun_bytes` | packets and bytes read from the tun |
| `cpu_us` | `CLOCK_THREAD_CPUTIME_ID` of the loop thread — **the second primary metric** |
| `scan_us` / `dispatch_us` | wall time walking/checking sessions vs. handling events |
| per-UID `sessions` / `events` | attribution, see the caveat below |

`cpu_us` is read on the loop thread itself, because a thread's CPU clock is not
observable from another thread. It only advances while the thread actually runs,
so it already excludes time blocked in `epoll_wait()` — that makes it directly
comparable between configurations regardless of how long the run was. It is
sampled at most once a second (plus once when the loop stops), because
`CLOCK_THREAD_CPUTIME_ID` is not served from the vDSO and per-iteration sampling
would add a syscall to the app's busiest path; at hour scale the throttling is
invisible.

The counters are always on. Their per-iteration cost is a handful of relaxed
atomic adds and three vDSO `CLOCK_MONOTONIC` reads — orders of magnitude below
the `epoll_wait()` syscall they accompany. There is no preference to enable them,
because a diagnostic nobody turns on measures nothing, and a new expert knob
would collide with the philosophy in `AGENTS.md` §4.

Counters are reset in `jni_start`, i.e. per native run. A `reload()` does a
native restart, so a run ends whenever the network changes, the screen-off delay
fires, or a setting changes — see "reading the numbers" below.

### Per-UID attribution and its limits

The native side only learns a packet's UID when it resolves one, which
`handle_ip()` does **for new sessions only** (a TCP SYN, a new UDP flow, ICMP).
Subsequent packets of an established session carry no UID, so:

- `sessions` per UID counts new-session decisions — a good proxy for "who keeps
  opening connections", which is what background chatter looks like.
- `events` per UID counts session-socket epoll events, taken from the session
  that owns the socket, so the downstream direction is attributed per app.
- `tun_packets` is a **global** total and cannot be split per app.

The table holds `LOOP_UID_SLOTS` (128) UIDs. Once full, further UIDs increment
`uid_overflow` rather than evicting entries, so a full table degrades into
"some traffic is unattributed" instead of silently skewing the split. The report
prints `uid_overflow` whenever it is non-zero; treat any non-zero value as a
reason to distrust the system/user split in that run.

System-vs-user classification happens on the Java side from `Rule.system`
(cached in `ServiceSinkhole.mapUidSystem`), so it matches exactly the
classification the VPN builder uses when it excludes system apps. UIDs that are
not in the rule list at all (root, mediaserver, the DNS daemon) are counted as
system, again matching what the builder excludes.

## Reading the numbers

```bash
adb shell dumpsys activity service \
  net.kollnig.missioncontrol/eu.faircode.netguard.ServiceSinkhole
```

For the debug build, use the suffixed application id:

```bash
adb shell dumpsys activity service \
  net.kollnig.missioncontrol.test/eu.faircode.netguard.ServiceSinkhole
```

This prints elapsed time, per-hour rates, the system/user split and the top
UIDs. `dumpsys` costs nothing when nobody calls it, which is why the report is
exposed there rather than through a periodic sampler or a UI screen.

A one-line summary is also written to logcat every time the packet loop stops,
so a measurement run is not lost if the VPN restarts before anyone polls:

```bash
adb logcat -s TrackerControl.VPN TrackerControl.JNI | grep "Packet loop"
```

`TrackerControl.VPN` carries the Java one-liner (with the routing arm named);
`TrackerControl.JNI` carries the raw native totals, which survive even if the
Java side never got a chance to read them.

Because counters reset per native run, prefer `dumpsys` at the end of a long
undisturbed window over summing logcat lines; if you do sum logcat lines, sum
`wakeups` and `cpu` and sum the elapsed times too, and derive the rate from the
totals rather than averaging the per-run rates.

## Running the comparison

The confound to beat is usage: two days are never equally busy, so raw totals
from an A/B across days say little. In order of increasing confidence:

1. **Within one run.** Turn Monitor system apps on and read the system share of
   attributed work. This needs no second run and is not confounded by usage,
   but it only covers session and socket-event attribution (see the caveat).
2. **A/B with the screen off and the device idle.** Charge to the same level,
   leave the device untouched and screen-off for the same duration (2 h+ each),
   once with the setting on and once off. Compare `wakeups/h` and
   `cpu s/h`. Idle windows are far more comparable than active ones, and idle is
   exactly where a wakeup-driven cost matters.
3. **Correlate with platform accounting.** `adb shell dumpsys batterystats
   --charged net.kollnig.missioncontrol` for the same window. Note that battery
   blamed on the VPN UID is routinely traffic *caused* by other apps and merely
   *attributed* to the tunnel — `AGENTS.md` §4 constraint 3. The point of
   `cpu_us` is to have a figure that is not subject to that mis-attribution.

Keep `wg_enabled` and the blocking mode fixed across the arms of a comparison.
WireGuard changes the packet path (packets are handed to the bridge instead of
running through the userspace TCP/UDP state machines) and the blocking mode
changes how many hosts are resolved, so both move the counters.

Report each arm as: duration, `wakeups/h`, `cpu s/h`, `tun packets/h`, and the
system share. Do not report MB/s as the headline.

## The trade-off to preserve

Excluding system apps is friendlier to battery, but it is not free:

- Android's **Block connections without VPN** ("always-on VPN" lockdown) drops
  traffic from apps the VPN does not route. With system apps excluded, enabling
  lockdown breaks them, which is why the setting's summary tells users lockdown
  must stay disabled.
- Trackers embedded in system apps are neither detected nor blocked while those
  apps bypass the tun.

Whatever the measurement concludes, that trade-off stays: the outcome should be
a better-informed default and a clearer explanation, not a new expert knob. The
user-facing surface is a single **Monitor system apps** switch that drives
`include_system_vpn`, `manage_system` and `show_system` together
(`ActivitySettings.onSharedPreferenceChanged`), and it should stay one switch.
