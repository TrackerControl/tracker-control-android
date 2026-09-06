# Stack regression checks

The Test workflow runs the portable native suites and socket harnesses with
ASan/UBSan on Linux. `tcp_epoll_test.c` uses real kernel epoll and socketpairs;
its JNI/TUN surroundings come from the existing TCP harness. It checks reordered
payload, sequence wrap, short writes, send-buffer backpressure, FIN, idle
readiness and closed-window HUP/reopen. It is not an entire VpnService test.

`ip_header_failfast.c` is only for `ip_header_test.c`: downstream operations
abort, proving malformed headers return before protocol dispatch.

The additional Android tests use isolated preferences and, for policy testing,
a separate SQLite database. Build and invoke them explicitly after following
`agents/docs/device-testing.md` and backing up the target application:

```sh
./gradlew :app:assembleGithubDebugAndroidTest -q
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -r \
  -e class eu.faircode.netguard.StackPolicyDeviceTest,net.kollnig.missioncontrol.dns.StackDeviceTest \
  net.kollnig.missioncontrol.test.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell am start -n net.kollnig.missioncontrol.test/eu.faircode.netguard.ActivityMain
```

Inspect instrumentation output for `OK` and zero failures; its shell exit
status alone is insufficient.
Instrumentation can interrupt the running VPN process. Verify that the app's
original VPN state is restored afterwards. Avoid `connectedAndroidTest` on the
maintainer's existing installation: its managed lifecycle can remove the app.

The overload test holds 16 workers and 64 queued TCP connections, verifies TCP
rejection and UDP SERVFAIL beyond that bound, closes queued sockets at stop,
and performs five restart cycles under a forced-open circuit breaker. It does
not send load to an external resolver or inject faults into the user's VPN.

`ip_flow_policy_test.c` calls the real `handle_ip()` and generation-scoped
route cache with synthetic TCP and UDP/443 packets. JNI ownership/policy and
WireGuard writes are observable boundary stubs. It checks policy revalidation,
negative verdict reuse, reused SYN tuples and unresolved-owner fail-closed
behaviour. UDP negative-session storage is a fixture stub; this is not a live
QUIC application test.

`WgbridgeInstrumentedTest` also starts synthetic tunnels through the packaged
JNI library, with no remote endpoint or saved profile. It checks the five-field
native stats payload, re-protection after rebind, repeated stop, stale-handle
exceptions and preservation of the caller-owned descriptors. The Rust
`transport::ip_send` tests separately exercise successful datagram writes and
real `EBADF` failures through `TunFdSend::send`. These component checks do not
inject persistent failures into a live peer's TUN or prove watchdog recovery
end to end.
