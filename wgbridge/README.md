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

```bash
# Prerequisites: Go 1.25+ (required by current golang.org/x/mobile),
# Android NDK 27+, gomobile.
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# Produce the AAR consumed by the app. -javapkg places the generated Java
# classes at net.kollnig.missioncontrol.wgbridge.* so they sit cleanly
# alongside the rest of the app namespace. -ldflags forces the C linker
# to use 16 KB max page size, required for Play Store compatibility on
# Android 15+.
gomobile bind \
    -target=android \
    -androidapi 23 \
    -javapkg=net.kollnig.missioncontrol \
    -ldflags='-extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
    -o ../app/libs/wgbridge.aar \
    .
```

`gomobile_tools.go` carries a build-tag-guarded import of
`golang.org/x/mobile/bind` so that `go mod tidy` keeps `x/mobile` in
`go.mod`. Without that stub, `gomobile bind` errors out with "no Go
package in golang.org/x/mobile/bind" before it gets to our code.

The resulting `wgbridge.aar` is checked into `app/libs/` so contributors
without the Go toolchain can still build the app.

## Java/Kotlin API surface (after `gomobile bind`)

```java
// package wgbridge produced by gomobile
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
