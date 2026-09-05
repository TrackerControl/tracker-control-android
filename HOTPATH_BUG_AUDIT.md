# TrackerControl packet-hotpath bug audit

Date: 5 September 2026
Audited revision: `e6c6d0fa`
Scope: ordinary tracker detection and blocking, the NetGuard C packet engine,
WireGuard/Rust, Java policy, DNS handling and credible battery costs.

Production source was not modified during the audit. Host-side C and Rust
witnesses extracted the current production functions and replaced only their
OS or JNI boundaries. Android tests exercised the real service, database,
tracker cache and bundled tracker data under Robolectric.

No confirmed critical memory-safety or remote-code-execution issue was found.
In this report, **High** means material default-path blocking, privacy,
availability or battery impact.

## Summary

| # | Bug | Categories | Severity | Fix difficulty | Status | Evidence |
|---:|---|---|---|---|---|---|
| 1 | Default WireGuard UDP/QUIC performs UID, JNI policy and SQLite work per packet | Battery, performance, hotpath | **High** | Medium | **Fixed** | Reproduced |
| 2 | Blocked UDP sessions bypass the 409-session cap | Resource exhaustion, battery, availability | **High** | Medium | **Fixed** | Reproduced |
| 3 | A DNS refresh does not invalidate the tracker-verdict cache | Blocking correctness, privacy, stale state | **High** | Easy | **Fixed** | Reproduced in both directions |
| 4 | Intermediate CNAME trackers are discarded | Detection coverage, privacy, DNS | **High** | Medium–Hard | **Fixed** | Reproduced in direct and WG paths |
| 5 | Native DNS closes after one reply and rejects tuple reuse for 60 seconds | DNS reliability, connectivity | **High** | Medium | **Fixed** | Reproduced |
| 6 | Overlapping TCP retransmissions wedge the forwarding queue | TCP correctness, connectivity, battery | **High** | Hard | **Fixed** | Reproduced |
| 7 | Protected → No Internet does not reload VPN policy | Blocking enforcement, privacy | **High** | Easy | **Fixed** | Source-proven |
| 8 | Established WireGuard TCP streams survive policy reloads | Blocking enforcement, privacy, WireGuard | **High** | Hard | **Fixed** | Source-proven |
| 9 | A client TCP FIN is not propagated upstream | TCP correctness, compatibility | **Medium–High** | Hard | **Fixed** | Reproduced |
| 10 | A server TCP FIN closes both directions | TCP correctness, compatibility | **Medium–High** | Hard | **Fixed** | Reproduced |
| 11 | TCP send-window calculation uses 16-bit rather than 32-bit wrap arithmetic | TCP correctness, connectivity | **Medium** | Easy | **Fixed** | Reproduced |
| 12 | Closed TCP windows cause 100 ms polling and repeated ACK probes | Battery, CPU, radio activity | **Medium** | Medium | **Fixed** | Reproduced |
| 13 | One WireGuard DNS-over-TCP gap permanently disables detection | Detection coverage, DNS reliability | **Medium** | Hard | **Fixed** | Reproduced |
| 14 | WireGuard TUN write failures remain invisible to its watchdog | Recovery, connectivity, observability | **Medium** | Medium | **Fixed** | Source-proven |
| 15 | UDP redirects apply only to the first datagram | DNS routing, Secure DNS, privacy | **Medium** | Easy–Medium | **Fixed** | Reproduced |
| 16 | The DoH proxy has an unbounded request queue | Battery, memory, availability, Secure DNS | **Medium** | Medium | **Fixed** | Source-proven |
| 17 | Screen-off DoH repeatedly schedules connection-pool eviction | Battery, CPU, Secure DNS | **Low–Medium** | Easy | **Fixed** | Source-proven |
| 18 | Zero-length UDP datagrams are treated as EOF | Protocol correctness, compatibility | **Low** | Easy | **Fixed** | Reproduced |

The strongest battery candidates are #1, #2, #6 and #12. Issues #16 and #17
also affect battery when the advanced Secure DNS feature is enabled.

## Fix status

The easy fixes are implemented on `codex/fix-easy-hotpath`. The high-severity,
TCP, WireGuard, UDP and Secure DNS fixes are implemented in successive stacked
branches:

| Finding | Status | Implementation |
|---:|---|---|
| #3 | **Fixed** | Successful `INSERTED` and `REFRESHED` DNS outcomes now invalidate the numeric resource's tracker cache entry. |
| #7 | **Fixed** | A real Internet-block state change now clears rule state and requests a VPN policy reload; null and no-op writes do not. |
| #11 | **Fixed** | TCP send-window distance now uses unsigned 32-bit modular subtraction. |
| #17 | **Fixed** | Screen-off DoH evicts after network-backed work, while cache hits no longer queue redundant eviction. |
| #18 | **Fixed** | Zero-length UDP datagrams are forwarded as valid empty datagrams rather than treated as EOF. |
| #1 | **Fixed** | The global WireGuard route now records a generation-scoped per-flow result; known owners reuse it while unresolved owners retry. |
| #2 | **Fixed** | Retained blocked UDP entries are capped at 256 and evict only the oldest blocked entry, preserving active sessions. |
| #4 | **Fixed** | The Rust DNS parser now follows a bounded, order-independent CNAME graph and reports every connected alias with the terminal address and conservative TTL. |
| #5 | **Fixed** | DNS UDP mappings remain active for multiple replies, and an idle closed DNS tuple can be reopened immediately rather than being rejected for the retention period. |
| #6 | **Fixed** | TCP queue insertion now trims forwarded prefixes, preserves first-seen bytes, fills gaps and merges overlaps with modular sequence arithmetic. |
| #8 | **Fixed** | Established tunnelled TCP flows revalidate owner and Java policy after each native policy generation change; allowed and blocked results are cached, and a newly blocked stream receives a valid reset. |
| #9 | **Fixed** | Client FINs are retained until all preceding queued bytes drain, then propagated upstream with one `shutdown(SHUT_WR)` and acknowledged idempotently. |
| #10 | **Fixed** | Upstream EOF now closes only the read half, sends one FIN to the app, and leaves the write half available until the client also closes. |
| #12 | **Fixed** | Zero-window probes now use deadline-based exponential backoff from 100 ms to 5 seconds while normal socket and TUN readiness still wakes immediately. |
| #13 | **Fixed** | SYN-established DNS-over-TCP framing survives gaps and idle periods; bounded first-seen reassembly recovers only from sequence-valid bytes. |
| #14 | **Fixed** | Native TUN write totals and streaks reach the watchdog, which recovers only after at least eight consecutive failures continue across five seconds of advancing samples. |
| #15 | **Fixed** | Each admitted UDP session stores its resolved address family, address and network-order port, so every later datagram uses the same redirect. |
| #16 | **Fixed** | Secure DNS uses 16 workers and a bounded 64-request backlog; overload receives an immediate UDP `SERVFAIL` or a closed TCP socket. |

All 18 findings are fixed across the stacked branches.

## Detailed findings

### 1. WireGuard's default UDP route misses the flow cache

**Categories:** Battery, performance, hotpath
**Severity:** High
**Fix difficulty:** Medium
**Confidence:** High

**Status: Fixed in the high-severity pull request.** The no-override path now
stores the global route and whether its owner was resolved. The next packet
reuses a stable tunnelled UDP verdict; an unresolved owner remains retryable.

`resolve_tunnel_uid()` stores a flow only inside `route_uid_relevant()`. With
the ordinary global WireGuard route and no per-app routing overrides, that
condition is false, so no entry is stored
([`ip.c`](app/src/main/jni/netguard/ip.c), lines 147–194). The early UDP
fastpath requires that entry (lines 489–499). Every QUIC or UDP packet therefore
repeats `get_uid_q()`, `isAddressAllowed()` and `logPacket()`.

The cost is larger than a Java method call. `log_app` defaults to true, and
`LogHandler.log()` calls `DatabaseHelper.getQAName()` before deciding whether
an access row needs updating
([`ServiceSinkhole.java`](app/src/main/java/eu/faircode/netguard/ServiceSinkhole.java),
lines 1183–1279). Consequently, ordinary WireGuard UDP can cause a SQLite query
per datagram even when the detailed traffic log is disabled.

Production `handle_ip()` witness using 10,000 packets on one UDP flow:

```text
WG UDP overrides=0: packets=10000, UID lookups=10000, Java policy calls=10000
WG UDP overrides=1: packets=10000, UID lookups=1, Java policy calls=1
```

A fix should cache the default-route result as well as per-app results while
retaining the existing invalidation and unresolved-UID behaviour.

### 2. Blocked UDP sessions bypass the session cap

**Categories:** Resource exhaustion, battery, availability
**Severity:** High
**Fix difficulty:** Medium
**Confidence:** High

**Status: Fixed in the high-severity pull request.** `UDP_BLOCKED` has its own
256-entry cap. Insertion evicts the oldest blocked node only, so negative-cache
churn cannot grow without bound or evict an active UDP mapping.

The event loop counts only `UDP_ACTIVE` entries
([`session.c`](app/src/main/jni/netguard/session.c), lines 101–121), while
`block_udp()` allocates a retained `UDP_BLOCKED` entry for every new five-tuple
([`udp.c`](app/src/main/jni/netguard/udp.c), lines 189–235). Blocked and closed
entries remain for 60 seconds (lines 77–80).

The admission count can therefore stay at zero while the linked list grows.
Packet lookup and periodic cleanup linearly traverse that list, so sustained
port churn becomes progressively more expensive.

Production `handle_ip()`, `block_udp()` and session-state witness:

```text
Blocked UDP: configured session cap=409, retained sessions=5000, counted active=0, bytes=1000000
```

The witness used ordinary IPv4 UDP packets with distinct source ports and
completed before the 60-second retention period expired. A fix should bound
negative entries without making every repeated blocked datagram cross JNI.

### 3. DNS refreshes can leave the cached tracker verdict stale

**Categories:** Blocking correctness, privacy, stale state
**Severity:** High
**Fix difficulty:** Easy
**Confidence:** High

**Status: Fixed in this pull request.** `dnsResolved()` now invalidates numeric
resources for both `INSERTED` and `REFRESHED`, with regression coverage for
refresh invalidation and failed-insert preservation.

`DatabaseHelper.insertDns()` refreshes the timestamp of an existing
`(qname, aname, resource)` row and returns `REFRESHED`
([`DatabaseHelper.java`](app/src/main/java/eu/faircode/netguard/DatabaseHelper.java),
lines 1159–1208). Updating that timestamp can change which alias is selected by
the `MAX(time)` query (lines 1294–1324).

`dnsResolved()` invalidates the IP verdict cache only for `INSERTED`, not for
`REFRESHED`
([`ServiceSinkhole.java`](app/src/main/java/eu/faircode/netguard/ServiceSinkhole.java),
lines 2861–2877). With the default minimum DNS TTL, the incorrect result can
remain cached for days.

Tests against the real service, database and cache demonstrate both directions:

```text
REFRESH block->allow: cached=true, fresh=false
REFRESH allow->block: cached=false, fresh=true
```

Here, `true` means blocked. Clearing only `TrackerCache` immediately produces
the fresh, opposite verdict. Invalidating a numeric resource after every
successful insert or refresh is the direct fix; the existing generation guard
already protects concurrent cache publication.

### 4. Intermediate CNAME trackers are invisible

**Categories:** Detection coverage, privacy, DNS
**Severity:** High
**Fix difficulty:** Medium–Hard
**Confidence:** High

**Status: Fixed in the high-severity pull request.** The shared Rust parser
follows up to eight connected CNAME links, independent of answer order, emits
each real alias edge against the terminal A/AAAA address, and carries the
minimum TTL from that edge to the terminal record. Loops, disconnected aliases
and duplicate rows are bounded or suppressed.

The shared Rust parser emits only A and AAAA records and ignores CNAME records
([`message.rs`](wgbridge-rs/tc-dns/src/message.rs), lines 149–198). For a chain
such as `first-party → tracker → CDN → IP`, Java receives only
`first-party → CDN → IP` and cannot test the intermediate tracker name.

A valid three-answer response using `stats.doubleclick.net` produced:

```text
C DNS parser recorded: collect.firstparty.example -> edge.cloudfront.net -> 203.0.113.7
WG DNS rows: [("collect.firstparty.example", "edge.cloudfront.net", "203.0.113.7")]
CNAME classification: original=not tracker; final=not tracker; intermediate=Google Ads (Google) (blocked)
```

The first two results come from the actual C ABI and WireGuard `DnsInspector`.
The third loads the bundled production tracker lists. The fixture is a valid
synthetic DNS response; it is not a claim that this exact public chain is live.

A fix needs to parse CNAME targets and retain the relevant chain, including
loop and depth bounds and TTL handling, before associating terminal addresses.

### 5. Native DNS black-holes a reused UDP tuple

**Categories:** DNS reliability, connectivity
**Severity:** High
**Fix difficulty:** Medium
**Confidence:** High

**Status: Fixed in the high-severity pull request.** Receiving a DNS datagram
no longer finishes the mapping, so one socket can deliver multiple outstanding
replies. If the 15-second idle timeout has already closed it, a new query on
the retained tuple replaces it with a fresh active mapping immediately.

After any port-53 reply, `check_udp_socket()` changes the entry to
`UDP_FINISHING` ([`udp.c`](app/src/main/jni/netguard/udp.c), lines 135–149).
The event loop closes it and retains it as `UDP_CLOSED` for 60 seconds (lines
56–80). `handle_udp()` rejects every packet matching a retained non-active
entry (lines 250–275).

Socket reuse and multiple outstanding DNS queries on one UDP socket can
therefore lose later queries or responses:

```text
DNS UDP socket reuse: first query sent, response delivered, second accepted=0, sends=1, retained state=2
```

The fix must preserve a mapping long enough for reuse and multiple replies,
while still bounding descriptors and retaining enough state to reject late
packets safely.

### 6. Overlapping TCP retransmissions wedge forwarding

**Categories:** TCP correctness, connectivity, battery
**Severity:** High
**Fix difficulty:** Hard
**Confidence:** High

**Status: Fixed in the high-severity pull request.** Queue insertion now
normalises retransmissions and overlapping ranges, including sequence-number
wrap, already-forwarded prefixes, gaps, exact buffer fits and PSH boundaries.

`queue_tcp()` sorts segments by starting sequence but does not trim or merge
overlaps ([`tcp.c`](app/src/main/jni/netguard/tcp.c), lines 1054–1112). With
queued ranges `1000..2000` and `1500..2500`, forwarding advances `remote_seq`
to 2000 and leaves a head starting at 1500. The forwarding condition requires
exact sequence equality forever.

```text
Overlapping TCP segments: bytes forwarded=1000, next expected seq=2000, stuck head seq=1500
```

The connection then enters the 100-ms recheck path and eventually times out.
The same function also discards a retransmission that starts before
`remote_seq` even when it contains a new suffix. Fixing this requires proper
modular range comparison, trimming, deduplication and merging.

### 7. No Internet changes can omit the required reload

**Categories:** Blocking enforcement, privacy
**Severity:** High
**Fix difficulty:** Easy
**Confidence:** High

**Status: Fixed in this pull request.** `AppProtectionWriter` snapshots the
effective Internet-block state and reloads only when it actually changes.
Focused tests cover protected-to-No-Internet, an identical write and nullable
no-op semantics. Finding #8 remains open.

`AppProtectionWriter` decides whether to reload from changes to `apply` and
tracker protection, but excludes `internetBlocked`
([`AppProtectionWriter.java`](app/src/main/java/net/kollnig/missioncontrol/data/AppProtectionWriter.java),
lines 86–109). A protected app changed to **No Internet** therefore does not
reload when its other protection flags stay unchanged.

Direct established TCP and UDP sessions retain their earlier verdict. The
local fix is to include the actual before/after internet-block state in the
reload decision. That alone does not solve issue #8.

### 8. Established WireGuard TCP streams survive policy reloads

**Categories:** Blocking enforcement, privacy, WireGuard
**Severity:** High
**Fix difficulty:** Hard
**Confidence:** High

**Status: Fixed in the high-severity pull request.** Route and policy verdicts
now share the generation invalidated by every `pushRoutingToNative()` during a
native reload. The first later packet of a tunnelled stream resolves its owner
and crosses the Java policy boundary once. A block is negatively cached and
sends one stateless TCP reset; an unavailable owner stays fail-closed and is
retried instead of caching an unattributed allow.

Native reload deliberately leaves WireGuard running to avoid repeated
handshakes
([`ServiceSinkhole.java`](app/src/main/java/eu/faircode/netguard/ServiceSinkhole.java),
lines 2318–2323). Tunnelled TCP flows never create native `ng_session` state,
and non-SYN TCP packets bypass the Java policy decision
([`ip.c`](app/src/main/jni/netguard/ip.c), lines 580–584).

Consequently, even a correctly requested native reload does not revoke an
already-established WireGuard TCP flow after an app is blocked. The fix needs
selective WireGuard flow revocation, a generation checked by the C forwarding
path, or a deliberate WireGuard restart on enforcement changes. The latter is
simpler but costs a handshake and disrupts unrelated flows.

### 9. A client FIN is never propagated upstream

**Categories:** TCP correctness, compatibility
**Severity:** Medium–High
**Fix difficulty:** Hard
**Confidence:** High

**Status: Fixed in the TCP half-close pull request.** FIN sequence state is now
kept outside the data queue. The proxy waits for every preceding byte, removes
speculative queued bytes beyond the FIN, calls `shutdown(SHUT_WR)` once, and
only then advances and acknowledges the FIN. Retransmissions and simultaneous
close are idempotent.

On a client FIN, `handle_tcp()` acknowledges it and moves into
`TCP_CLOSE_WAIT` ([`tcp.c`](app/src/main/jni/netguard/tcp.c), lines 957–965),
but the file contains no `shutdown(socket, SHUT_WR)` call. A server waiting for
EOF therefore waits until the 20-second close timeout, after which
TrackerControl resets the flow.

```text
Client FIN: ACKs=1, peer recv=-1 errno=35 (EAGAIN=35), no upstream EOF
Client FIN after 21 seconds: resets=1, socket closed
```

The shutdown must occur only after all queued client data has been forwarded.

### 10. A server FIN closes both directions

**Categories:** TCP correctness, compatibility
**Severity:** Medium–High
**Fix difficulty:** Hard
**Confidence:** High

**Status: Fixed in the TCP half-close pull request.** Upstream EOF now marks
the read half closed and sends one FIN to the app without closing the socket.
Client data can continue to drain through the write half; the descriptor is
closed after the independent FIN/ACK state completes or on a real error.

When upstream returns EOF, the proxy sends FIN to the app and immediately
calls `close()` on the upstream descriptor
([`tcp.c`](app/src/main/jni/netguard/tcp.c), lines 599–623). TCP permits the
app to continue sending after the server half-closes, but its next packet now
finds `socket < 0` and is reset.

```text
Server half-close: native upstream descriptor=-1, TCP state=4
```

The native state machine needs independent read-side and write-side closure
state rather than treating EOF as complete descriptor closure.

### 11. TCP send-window wrap uses the wrong modulus

**Categories:** TCP correctness, connectivity
**Severity:** Medium
**Fix difficulty:** Easy
**Confidence:** High

**Status: Fixed in this pull request.** The distance is now calculated with
unsigned `uint32_t` subtraction. A production-backed host test covers both the
wrap and non-wrap cases; its Linux ASan/UBSan command is ready for future CI
wiring.

`get_send_window()` uses `0x10000 + local_seq - acked` when the 32-bit sequence
number wraps ([`tcp.c`](app/src/main/jni/netguard/tcp.c), lines 175–183).
The correct arithmetic is modulo 2³², not 2¹⁶.

```text
TCP sequence wrap: available window=0, expected=65463
```

Use 32-bit modular subtraction with the same serial-number assumptions already
used by `compare_u32()`.

### 12. Closed TCP windows force a 100-ms wake loop

**Categories:** Battery, CPU, radio activity
**Severity:** Medium
**Fix difficulty:** Medium
**Confidence:** High

**Status: Fixed in the TCP half-close pull request.** The first zero-window
probe remains prompt, then deadlines back off exponentially through 200, 400
and 800 ms to a 5-second cap. An opened window resets the schedule, upstream
EOF cancels it, and ordinary epoll readiness is never delayed by the probe
deadline.

When `get_send_window()` returns zero, `monitor_tcp_session()` requests a
recheck and sends an ACK probe on the `EPOLL_MIN_CHECK` cadence
([`tcp.c`](app/src/main/jni/netguard/tcp.c), lines 133–148). The event loop then
uses a 100-ms epoll timeout
([`session.c`](app/src/main/jni/netguard/session.c), lines 193–196), without
backoff.

```text
60 seconds with closed TCP window: 600 short-poll requests, 299 ACK probes (no backoff)
```

Fixing #11 removes one artificial trigger. Real zero-window conditions still
need exponential or bounded backoff without delaying ordinary readiness events.

### 13. A WireGuard DNS-over-TCP gap permanently disables detection

**Categories:** Detection coverage, DNS reliability
**Severity:** Medium
**Fix difficulty:** Hard
**Confidence:** High

**Status: Fixed in the WireGuard recovery pull request.** The inspector keeps
the SYN-established sequence and framing anchor, buffers at most eight
segments or 16 KiB for five seconds, preserves first-seen overlap bytes, and
drains only exact contiguous ranges. Overflow and expiry discard speculative
segments without scanning arbitrary payload for a new DNS prefix.

`DnsInspector` removes all flow state when it observes a forward sequence gap
([`dns.rs`](wgbridge-rs/src/dns.rs), lines 149–157). Later data recreates a
flow with `framing_known=false`, and a later complete retransmission on the
same connection never restores a known DNS message boundary. An idle period
past the 60-second state timeout causes the same limitation on persistent
connections.

```text
WG DNS records after reordering + later whole response: 0
```

Recovery requires bounded out-of-order buffering or resynchronisation that
cannot mistake arbitrary payload bytes for a DNS length prefix.

### 14. WireGuard TUN write failures are invisible to the watchdog

**Categories:** Recovery, connectivity, observability
**Severity:** Medium
**Fix difficulty:** Medium
**Confidence:** Medium–High

**Status: Fixed in the WireGuard recovery pull request.** Rust exposes total
and consecutive TUN write failures through the append-only JNI statistics
array. Java remains compatible with the previous three-value array and enters
recovery only when a streak of at least eight failures keeps advancing for five
seconds; a full write, stale sample, restart or suspension resets qualification.

Gotatun increments receive statistics when it decrypts a packet. Afterwards,
`TunFdSend` writes that packet to the Android TUN, suppresses every write error
and returns success
([`ip_send.rs`](wgbridge-rs/src/transport/ip_send.rs), lines 54–71).

The Java connectivity monitor treats advancing receive counters as proof that
the whole data path works. A persistent TUN write failure can therefore leave
applications offline while the watchdog considers the tunnel healthy.

This is source-proven but was not exercised with a live WireGuard tunnel. A fix
could expose consecutive TUN write failures in tunnel statistics and include
them in liveness decisions, while preserving the intentional choice not to
terminate gotatun on one transient `ENOBUFS`.

### 15. UDP redirects apply only to the first datagram

**Categories:** DNS routing, Secure DNS, privacy
**Severity:** Medium
**Fix difficulty:** Easy–Medium
**Confidence:** High

**Status: Fixed in the UDP redirect pull request.** Session creation resolves
and validates the selected endpoint once, then stores its address family,
address and network-order port. Every datagram uses that endpoint while the
original tuple remains the identity for lookup, response reconstruction,
accounting and logs. IPv4, IPv6 and cross-family redirects are covered.

Java supplies a redirect only while making the first policy decision. The
native UDP session does not store it. Later packets on the same tuple reach
`handle_udp()` with `redirect == NULL` and use the original destination
([`udp.c`](app/src/main/jni/netguard/udp.c), lines 347–375).

```text
UDP same flow: datagram 1 -> 127.0.0.1 (redirect); datagram 2 -> 8.8.8.8 (original destination)
```

This currently matters mainly to the advanced Secure DNS and direct-DNS
routing paths. Store the resolved destination in session state and use it for
the session lifetime.

### 16. The DoH proxy has an unbounded request queue

**Categories:** Battery, memory, availability, Secure DNS
**Severity:** Medium
**Fix difficulty:** Medium
**Confidence:** High

**Status: Fixed in the bounded DoH queue pull request.** The 16 workers now
share a bounded 64-request queue. When both are full, UDP receives an immediate
`SERVFAIL` and TCP connections are closed; overload logging is rate-limited.
Shutdown also closes accepted TCP sockets that were still queued.

The proxy creates a fixed pool of 16 worker threads, backed by an unbounded
queue
([`DnsProxyServer.java`](app/src/main/java/net/kollnig/missioncontrol/dns/DnsProxyServer.java),
lines 122–124). Every received query is submitted without admission control
(lines 262–275), while workers can remain blocked for roughly 20 seconds on a
failed DoH request.

A failing endpoint or sustained query burst can accumulate stale requests,
arrays and executor work. A bounded queue needs a DNS-appropriate overload
response or drop policy and should avoid replying after the request is no
longer useful.

### 17. Screen-off DoH repeatedly schedules pool eviction

**Categories:** Battery, CPU, Secure DNS
**Severity:** Low–Medium
**Fix difficulty:** Easy
**Confidence:** High

**Status: Fixed in this pull request.** Per-resolution eviction is now gated on
network-backed work. Tests prove that an HTTP cache hit schedules no additional
eviction while a real network response still does; `setScreenOff(true)` keeps
its immediate eviction.

Every screen-off DoH resolution calls `evictIdle()` from its `finally` block
([`DnsOverHttpsClient.java`](app/src/main/java/net/kollnig/missioncontrol/dns/DnsOverHttpsClient.java),
lines 310–316). `evictIdle()` queues work on a single-thread executor (lines
166–168), including after cache hits and when no pooled socket remains.

Coalesce eviction, perform it only when a network call could have populated
the pool, or make the screen-off policy transition own the eviction.

### 18. Zero-length UDP datagrams are treated as EOF

**Categories:** Protocol correctness, compatibility
**Severity:** Low
**Fix difficulty:** Easy
**Confidence:** High

**Status: Fixed in this pull request.** A zero-length read now follows the
datagram data path and emits an IP/UDP packet with an empty payload. The
production-backed host test covers both non-DNS and port-53 lifecycle
behaviour; its Linux ASan/UBSan command is ready for future CI wiring.

Datagram sockets can legitimately receive a zero-length datagram. Native
`check_udp_socket()` interprets `recv() == 0` as EOF, forwards nothing and
closes the mapping ([`udp.c`](app/src/main/jni/netguard/udp.c), lines 110–123).

```text
Zero-length UDP response: writes=0, state=1 (FINISHING=1)
```

For `SOCK_DGRAM`, zero is a successful zero-length message rather than stream
EOF. It should be handled as data, subject to whether the TUN packet builder
can represent it.

## Repair status

All findings in this audit are implemented across the stacked pull requests.

## Validation performed

- Two Robolectric audit classes passed three tests against the real service,
  database and tracker-list code.
- The Rust workspace passed 95 tests across six suites on the final stack.
- Two additional extracted DNS/WireGuard tests passed.
- Native DNS-frame, IPv6-extension, DHCP-option, UDP-state and WireGuard
  flow-cache suites passed under ASan/UBSan.
- The connection, queue and session witnesses described above also passed under
  ASan/UBSan.
- The integrated easy-fix branch additionally passed 18 focused Java policy
  tests, the complete `DnsOverHttpsClientTest`, the five existing native host
  suites, and `assembleGithubDebug` for all four Android ABIs.
- The new production-backed TCP-window and UDP-socket tests compile in the
  Android toolchain. Their Linux ASan/UBSan commands are ready for future CI
  wiring.
- The high-severity branch passed the Rust workspace, the TCP overlap and
  route/verdict host suites, the existing native host suites, the focused Java
  policy tests and a full `assembleGithubDebug` build for all four Android
  ABIs.
- The TCP half-close branch passed strict host compilation, the TCP window,
  queue and half-close suites under ASan/UBSan, and `assembleGithubDebug` for
  all four Android ABIs.
- The WireGuard recovery branch passed 95 Rust tests across six suites and the
  focused Kotlin connectivity-checker suite. Its changed Rust files pass
  `rustfmt`; the workspace-wide check still reports pre-existing formatting in
  `config.rs` and `transport/udp.rs`.
- The UDP redirect branch passed its production-backed socket suite under
  ASan/UBSan after stacking, covering repeated, unredirected, IPv4, IPv6,
  cross-family, invalid-address, original-tuple and response-tuple cases.
- The bounded DoH queue branch passed `DnsProxyServerTest` after stacking,
  including deterministic admission and stopped-executor rejection cases.
- The final stacked branch passed the complete `testGithubDebugUnitTest` suite,
  the 95-test Rust workspace, and `assembleGithubDebug`. The first integrated
  Android build exposed an incorrect JNI array-length type; after correcting
  it, the rerun passed and the APK contained both `libnetguard.so` and
  `libwgbridge.so` for arm64-v8a, armeabi-v7a, x86 and x86_64.

## Limits

- No real-device traffic capture or power trace was taken. Battery severity is
  based on verified call frequency, database work and wake cadence rather than
  a measured milliamp-hour delta.
- The C witnesses use the current production functions but replace Android JNI,
  logging and socket-protection boundaries with deterministic host stubs.
- The CNAME chain is a syntactically valid synthetic fixture selected to prove
  that a bundled blocked tracker can disappear between the question and final
  address owner.
- Issue #14 is supported by the Rust data flow and dependency counter semantics,
  but not by an end-to-end live-tunnel failure injection.
