# wgbridge

A small Go module that lets TrackerControl run wireguard-go inside its own
NetGuard-based VpnService. It hands wireguard-go a custom `tun.Device`
backed by a Unix `socketpair` (the C side writes outbound IP packets in,
wireguard-go reads them) and emits decrypted inbound IP packets directly to
the VpnService TUN fd.

The outer UDP socket wireguard-go uses is `VpnService.protect()`-ed via a
Java callback, otherwise its packets would be re-captured by NetGuard's
TUN and loop forever.

## Why a custom tun.Device

Android's `VpnService` only allows one TUN device per process and NetGuard
already owns it. The official `com.wireguard.android:tunnel` library needs
a real `/dev/net/tun` fd, so we cannot use its runtime — we wire up
`golang.zx2c4.com/wireguard/device` directly with our own `tun.Device`
implementation.

## Build

The `wgbridgeBind` Gradle task runs `gomobile bind` automatically as part
of the Android build, so once prerequisites are in place you don't need
to invoke it directly:

```bash
./gradlew assembleGithubDebug   # bridge AAR is built on demand
```

The task tracks `*.go`, `go.mod` and `go.sum` as inputs and the produced
AAR (`app/build/wgbridge/wgbridge.aar`) as its output, so it's skipped
when the Go source hasn't changed.

### Prerequisites

- **Go ≥ 1.25** (current `golang.org/x/mobile` requires it).
- **gomobile**: `go install golang.org/x/mobile/cmd/gomobile@latest`,
  then `gomobile init` once.
- **Android NDK ≥ 27** with `ANDROID_NDK_HOME` exported.

`gomobile_tools.go` carries a build-tag-guarded import of
`golang.org/x/mobile/bind` so that `go mod tidy` keeps `x/mobile` in
`go.mod`. Without it, `gomobile bind` errors out with "no Go package
in `golang.org/x/mobile/bind`".

### Manual build (debugging)

```bash
cd wgbridge
gomobile bind \
    -target=android \
    -androidapi 23 \
    -javapkg=net.kollnig.missioncontrol \
    -ldflags='-extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
    -o ../app/build/wgbridge/wgbridge.aar \
    .
```

## F-Droid build metadata

The F-Droid build server doesn't ship Go by default. Add the following
to the `Builds:` entry in `metadata/net.kollnig.missioncontrol.yml`:

```yaml
sudo:
  - apt-get update
  - apt-get install -y golang-1.25 build-essential
  - update-alternatives --install /usr/local/bin/go go /usr/lib/go-1.25/bin/go 1
prebuild:
  - go install golang.org/x/mobile/cmd/gomobile@latest
  - export PATH=$PATH:$(go env GOPATH)/bin
  - gomobile init
```

(`golang-1.25` may not yet be in the build server's package cache —
adjust the source list / version as needed.)

## Java/Kotlin API surface

After `gomobile bind`, the AAR exposes:

```java
package net.kollnig.missioncontrol.wgbridge;

class Wgbridge {
    static Tunnel startTunnel(
        String uapiConfig,
        int outboundRxFd,
        int tunWriteFd,
        int mtu,
        Protector protect,
        Logger logger) throws Exception;
}

interface Protector { boolean protect(int fd); }
interface Logger    { void verbosef(String s); void errorf(String s); }

class Tunnel { void stop(); }
```

`uapiConfig` is the wireguard-go IpcSet text. `WgConfig.toUapi()` on the
Kotlin side produces it from a parsed `wg-quick` config.
