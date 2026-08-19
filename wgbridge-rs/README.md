# wgbridge-rs

A small Rust crate that lets TrackerControl run [gotatun] — Mullvad's
WireGuard® implementation, a fork of Cloudflare's BoringTun — inside its own
NetGuard-based VpnService. It replaces the earlier Go module that embedded
wireguard-go; Mullvad's data motivated the switch (over 85% of their app
crashes originated in wireguard-go, and their Rust engine also improved
throughput and battery use).

The crate hands gotatun custom transports backed by a Unix `socketpair`
(the C side writes outbound IP packets in, gotatun reads them) and emits
decrypted inbound IP packets directly to the VpnService TUN fd. The outer
UDP sockets gotatun uses are `VpnService.protect()`-ed via a Java callback,
otherwise their packets would be re-captured by NetGuard's TUN and loop
forever.

[gotatun]: https://github.com/mullvad/gotatun

## Why custom transports

Android's `VpnService` only allows one TUN device per process and NetGuard
already owns it. gotatun's `Device` is generic over its transports
(`IpSend`/`IpRecv` for the IP-packet side, `UdpTransportFactory` for the
encrypted side), so we plug in:

- `SocketpairRecv` — reads outbound raw IP packets from the socketpair fd
  written by `jni/netguard/ip.c` (batched, via tokio's `AsyncFd`);
- `TunFdSend` — writes decrypted inbound packets to the VpnService TUN fd,
  recording A/AAAA answers for TrackerControl's mapping and applying its DNS
  response policy (SVCB/HTTPS blanking and the explicit domain-policy hook) on
  the way through;
- `ProtectedUdpFactory` — binds the outer UDP sockets and protects them via
  the Java `Protector` callback. Because gotatun re-invokes the factory on
  every reconfigure, `Tunnel.rebind()` doubles as "move the encrypted
  traffic to the new default network" when roaming.

## Build

The `wgbridgeBuild` Gradle task runs `cargo ndk` automatically as part of
the Android build, so once prerequisites are in place you don't need to
invoke it directly:

```bash
./gradlew assembleGithubDebug   # libwgbridge.so is built on demand
```

The task tracks `src/**`, `Cargo.toml` and `Cargo.lock` as inputs and the
produced per-ABI libraries (`app/build/rustJniLibs/<abi>/libwgbridge.so`)
as its output, so it's skipped when the Rust source hasn't changed.

### Prerequisites

- **Rust 1.95.0** via [rustup](https://rustup.rs). The version and Android
  targets are pinned in the repository's `rust-toolchain.toml`.
- **cargo-ndk 4.1.2**.
- **Android NDK 27.2.12479018 (r27c)** (the Gradle task points cargo-ndk at
  the NDK configured for the app module).

Install the pinned Rust prerequisites and pre-fetch the locked crates with:

```bash
./scripts/setup_rust_android.sh
```

The Gradle build is intentionally offline: it never installs tools or fetches
crates. This keeps local, CI, and F-Droid builds on the same inputs.

### Manual build (debugging)

```bash
cd wgbridge-rs
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 --platform 23 \
    -o ../app/build/rustJniLibs build --release
```

The `RUSTFLAGS` keep ELF LOAD segments 16KB-aligned as required on
Android 15+ devices with 16KB pages.

### Tests

The protocol-independent parts (UAPI config parsing, DNS answer parsing,
key derivation) run on the host:

```bash
cargo test
```

## F-Droid build metadata

The F-Droid build server doesn't ship the required Rust setup by default. Add
the following to the new `Builds:` entry in
`metadata/net.kollnig.missioncontrol.fdroid.yml`:

```yaml
sudo:
  - apt-get update
  - apt-get install -y rustup gcc libc-dev
prebuild:
  - ../scripts/setup_rust_android.sh
ndk: r27c
```

The existing `gradle: [fdroid]` setting remains unchanged. The prebuild step
runs while dependency downloads are allowed; Gradle subsequently compiles the
locked crate graph with Cargo offline.

## Java/Kotlin API surface

The Java classes in `app/src/main/java/net/kollnig/missioncontrol/wgbridge/`
are hand-written JNI bindings (they replace the classes gomobile used to
generate, with the same shape):

```java
package net.kollnig.missioncontrol.wgbridge;

class Wgbridge {
    static Tunnel startTunnel(
        String uapiConfig,
        int outboundRxFd,
        int tunWriteFd,
        int mtu,
        Protector protect,
        Logger logger,
        DnsRecorder dnsRecorder);
    static String generatePrivateKey();
    static String publicKey(String privateKey);
}

interface Protector   { boolean protect(int fd); }
interface Logger      { void verbosef(String s); void errorf(String s); }
interface DnsRecorder {
    void recordDns(String qname, String aname, String resource, int ttl);
    default boolean isDomainBlocked(String qname) { return false; }
    default int blockedRcode() { return 3; }
}

class Tunnel {
    void setConfig(String uapiConfig);
    TunnelStats stats();
    long latestHandshakeMillis();
    void sendKeepalive();
    void rebind();                // re-bind + re-protect UDP sockets (roaming)
    void updateEndpoint(String peerPublicKeyBase64, String endpoint);
    void stop();
}
```

`uapiConfig` is the UAPI text `WgConfig.toUapi()` produces from a parsed
`wg-quick` config; the Rust side maps it onto gotatun's typed API. Peer
endpoints must arrive as resolved IP literals (`WgEgress` resolves
hostnames, and re-resolves them on network changes via `updateEndpoint`).

## Potential improvements

- **Split DNS-over-TCP rewriting**: inbound TCP DNS is recorded with bounded
  reassembly, but response rewriting is limited to complete frames that begin
  and end in one sequence-aligned segment. Rewriting a frame split across
  packets needs buffering plus TCP sequence translation; continuation segments
  are deliberately forwarded unchanged until that exists.
- **DNS upstream privacy**: app DNS packets to port 53 go through WireGuard
  whenever the tunnel is up — `ip.c` forces them into it regardless of
  destination, because an unprotected query would expose the user's physical
  network to the resolver. The one exception is an app the user has routed
  around the tunnel: its queries are redirected to the system resolver
  (see `RemoteRoutingLogic`), which is the cost of letting that app keep
  working when the tunnel drops. The remaining gap is that TrackerControl's
  own DoH proxy is not yet advertised as the sole resolver, which would let
  interception and upstream privacy hold at the same time without the
  redirect.
