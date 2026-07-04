# On-Device APK Patching for HTTPS Content Inspection

## Overview

TrackerControl can now repackage installed apps on-device so their HTTPS
traffic can be inspected by a local TLS-terminating proxy. This is the same
circumvention offered by [apk-mitm](https://github.com/shroudedcode/apk-mitm)
and ReVanced Manager's "Override certificate pinning" patch, but running
entirely on the phone — no PC, no `adb`, no Node.js, no root required for the
patching step itself.

The feature is exposed as a **"Patch for traffic inspection"** action in the
per-app details screen (`DetailsActivity`) and produces a signed, installable
APK. Because the APK is re-signed with TrackerControl's local patching key,
Android usually cannot install it over the original app; uninstall the original
first if the package installer reports incompatible signatures.

## Why this exists

Android 7 (Nougat) stopped trusting user-installed CAs for app traffic by
default, and many apps additionally pin certificates in code. A local MITM
proxy therefore needs both:

1. The app to **trust the proxy's CA** — requires a
   `network_security_config.xml` that trusts user CAs with
   `overridePins="true"`.
2. The app to **not reject the proxy cert via code-level pinning** — requires
   replacing `X509TrustManager` / `HostnameVerifier` / OkHttp
   `CertificatePinner` (and similar) implementations with no-op / always-accept
   stubs.

Doing only one is insufficient. This patcher does both statically by repackaging
the APK, so no runtime hook (Frida/Xposed) is needed and the change survives
restarts.

## Architecture

```
                DetailsActivity
                      │
                      ▼  (resolve sourceDir + splitSourceDirs via PackageManager)
                PatcherFactory.get()
                      │
                      ▼  (reflection — play flavor → NoOpPatcher)
                Dexlib2Patcher.patch()
                      │
            ┌─────────┼─────────────────────────┐
            ▼         ▼                         ▼
      mergeSplits  patchDexes            ResourcePatcher
      (ARSCLib)    (dexlib2)             (ARSCLib)
            │         │                         │
            │         │  SmaliPatches            │  inject res/xml/
            │         │  (declarative)           │  network_security_config.xml
            │         │                         │  + manifest attribute
            │         │                         │  + debuggable=true
            └─────────┴─────────────────────────┘
                      ▼
                ApkModule.writeApk()  →  unsigned.apk
                      ▼
                apksig sign (v1+v2+v3, AndroidKeyStore RSA)
                      ▼
                patched.apk  →  FileProvider  →  install intent
```

### Two trust-defeat layers

| Layer | What it defeats | Engine | License |
|---|---|---|---|
| **Code-level** (`DexPatcher` + `SmaliPatches`) | OkHttp `CertificatePinner`, custom `X509TrustManager`/`HostnameVerifier`, TrustKit, Appcelerator, IBM WorkLight, CWAC-Netsecurity, Netty, PhoneGap, Appmattus CT | dexlib2 (smali) | BSD-3 |
| **Resource-level** (`ResourcePatcher`) | Default platform `TrustManagerImpl` rejecting user CAs; NSC-declared pin sets | ARSCLib (binary resource editor) | Apache-2.0 |

The resource layer is the **static equivalent** of httptoolkit's Frida
`TrustedCertificateIndex` injection script — instead of hooking Conscrypt at
runtime, we patch the app's resource config once so the platform itself
accepts the MITM CA on every subsequent launch.

### Split APK handling

Modern apps distributed via App Bundles install as a `base.apk` plus per-config
splits (`split_config.arm64_v8a.apk`, `split_config.xxhdpi.apk`, language
splits, …). `ApplicationInfo.sourceDir` only returns the base.

The patcher:

1. Resolves `ApplicationInfo.splitSourceDirs` (available since API 21; minSdk
   is 23).
2. Loads each split as an `ApkModule` and calls `base.merge(split)` (ARSCLib)
   — unions all dex files, native libraries, resources, and assets into the
   base.
3. Calls `ApkSplitInfoCleaner.cleanSplitInfo(base)` to strip the `<split>` /
   `<isSplitRequired>` manifest metadata so Android treats the merged APK as
   a standalone base.

The result is a single installable APK containing everything from all splits.

## File layout

```
app/src/main/java/net/kollnig/missioncontrol/patch/
├── ApkPatcher.java          # interface
├── PatchResult.java         # result data class
├── NoOpPatcher.java         # fallback for play flavor
├── PatcherFactory.java      # reflection-based flavor dispatch
├── DexPatcher.java          # dexlib2 method-body replacement engine
└── SmaliPatches.java        # declarative patch definitions (apk-mitm + Frida ports)

app/src/patcher/java/net/kollnig/missioncontrol/patch/   # fdroid/github only
├── Dexlib2Patcher.java      # the real ApkPatcher impl (orchestrates everything)
├── ResourcePatcher.java     # ARSCLib manifest + network_security_config
└── SigningKeyManager.java   # AndroidKeyStore RSA key for re-signing
```

The `patcher` source set is only compiled into the `fdroid` and `github`
product flavors (see `app/build.gradle` `sourceSets`). The `play` flavor
excludes it entirely and `PatcherFactory` returns `NoOpPatcher` at runtime,
because:

- Play Store policy and the additional dependency weight (apksig + ARSCLib
  ≈ 10 MB) make bundling the engine in the Play build undesirable.
- The engine uses on-device APK repackaging, which sits awkwardly with Play
  policy regardless.

## Dependencies

| Dependency | Version | License | Purpose |
|---|---|---|---|
| `org.smali:dexlib2` | 2.5.2 (existing) | BSD-3 | DEX read/write & method-body replacement |
| `com.android.tools.build:apksig` | 9.0.1 | Apache-2.0 | Re-signing the patched APK (v1+v2+v3) |
| `io.github.reandroid:ARSCLib` | 1.4.0 | Apache-2.0 | Binary resource editing (manifest + res/xml), split merging |

All three are permissively licensed — no GPL dependency was added (ReVanced's
GPL-3.0 patcher was deliberately avoided). The TrackerControl base is GPL-3.0,
which remains compatible.

The signing key is an RSA-2048 key generated and stored in the AndroidKeyStore
(non-exportable), with a self-signed cert valid for 100 years. The patched APK
is signed with v1 + v2 + v3 signature schemes for maximum install compatibility.
This does not preserve the target app's original signing identity, so Android's
normal update flow will reject installing it over the still-installed original.

## What the patcher does to an APK

1. **Merge splits** (if any) into a single base module.
2. **Strip split manifest metadata** (`<split>`, `<isSplitRequired>`).
3. **Patch every `classes*.dex`**: for each class implementing a known pinning
   interface or extending a known pinning class, replace the matching methods'
   bodies with stubs:
   - `X509TrustManager.checkClientTrusted/checkServerTrusted` → `return-void`
   - `X509TrustManager.getAcceptedIssuers` → `return new X509Certificate[0]`
   - `HostnameVerifier.verify` → `return true`
   - `OkHttp CertificatePinner.check`/`check$okhttp` → `return-void`
   - TrustKit / Appcelerator / WorkLight / Netty / CWAC / PhoneGap / Appmattus
     pinning methods → no-op or return-true/empty-array
4. **Inject `res/xml/network_security_config.xml`** that trusts user CAs with
   `overridePins="true"` and permits cleartext, plus debug-overrides trusting
   user CAs.
5. **Set `android:networkSecurityConfig`** on `<application>` to reference the
   new XML resource, and set `android:debuggable="true"`.
6. **Strip the original signature** (META-INF/*.SF, *.RSA, *.DSA, *.EC,
   MANIFEST.MF) and **re-sign** with the AndroidKeyStore key via apksig.

## Covered pinning libraries

Ported from apk-mitm and the declarative subset of
[httptoolkit/frida-interception-and-unpinning](https://github.com/httptoolkit/frida-interception-and-unpinning):

- `javax.net.ssl.X509TrustManager` (any implementor)
- `javax.net.ssl.HostnameVerifier` (any implementor)
- `com.squareup.okhttp.CertificatePinner` (OkHttp 2.x)
- `okhttp3.CertificatePinner` (OkHttp 3.x / 4.x, including `check$okhttp`)
- `com.datatheorem.android.trustkit.pinning.PinningTrustManager`
- `appcelerator.https.PinningTrustManager`
- `com.worklight.wlclient.certificatepinning.HostNameVerifierWithCertificatePinning`
- `com.worklight.androidgap.plugin.WLCertificatePinningPlugin`
- `com.commonsware.cwac.netsecurity.conscrypt.CertPinManager`
- `io.netty.handler.ssl.util.FingerprintTrustManagerFactory`
- `nl.xservices.plugins.sslCertificateChecker` (PhoneGap)
- `com.appmattus.certificatetransparency.internal.verifier.CertificateTransparencyHostnameVerifier`

## Limitations

- **Framework classes cannot be dex-patched.** `com.android.okhttp.*`,
  `com.android.org.conscrypt.*`, and `javax.net.ssl.*` live in the framework,
  not the app's dex. They are handled by the resource step (NSC trusting user
  CAs + `overridePins="true"`) instead.
- **Apps pinning via uncovered classes** will still reject the MITM CA. The
  proxy must fall back to pass-through for those flows so connectivity is
  never broken.
- **No runtime hooks.** This is static repackaging — no Frida/Xposed. The
  patch persists across restarts, but the app must be reinstalled and any
  app updates will overwrite the patch.
- **No split-APK output.** The output is always a single merged APK; if the
  original was a large App Bundle, the patched APK will be larger than the
  base alone because all splits are inlined.
- **Non-rooted CA trust.** Even with `network_security_config` trusting user
  CAs, the MITM CA must be installed as a user CA via Android's certificate
  settings (or as a system CA on rooted devices) for the platform to actually
  present it during TLS handshakes.

---

# Future Work: the MITM Engine

The patcher makes an app **interceptable**; it does not yet intercept. The
missing half is a TLS-terminating proxy that sits inside the VPN tunnel and
decrypts HTTPS for display. This section documents the design for that follow-on
work.

## Goal

When the user enables "Inspect HTTPS content" for a patched app, the VPN
engine should:

1. Detect TLS flows (TCP/443) from that app.
2. Terminate TLS on the device using a locally-generated CA.
3. Decrypt request/response bodies.
4. Store them in a new `content` table for display in a "Content" tab.
5. Re-encrypt toward the real upstream server.
6. Fall back to **pass-through** on any handshake failure (never break
   connectivity).

## Architecture: Option B (Kotlin TLS relay) — recommended

A userspace TLS-terminating proxy on the TUN side. Two implementation options
were considered:

- **Option A — Native (mbedTLS in C):** Highest performance, hardest to
  write, duplicates a TLS stack already linked for WireGuard.
- **Option B — Kotlin `javax.net.ssl` relay:** Reuses Android's TLS, easier to
  maintain, handles modern TLS versions/ALPN/ cert validation correctly. Slower
  but adequate for the per-flow counts a single device produces.

Recommended: **Option B**.

```
app  ──TLS──►  [TC interceptor: terminate TLS w/ on-the-fly leaf cert]
                     │
                     ├── decrypted request bytes ──► capture ──► DB
                     │
                     └── open real TLS to upstream ──► decrypted response ──► capture ──► DB
                                                                                       └─► re-encrypt → app
```

## Components to build

### 1. Local CA + leaf cert generation (`net.kollnig.missioncontrol.intercept.ca`)

- `LocalCaManager` — generates an RSA/EC root keypair on first run, stores in
  AndroidKeyStore (or BouncyCastle-backed PEM in app-private storage).
  `signLeaf(sni: String): X509Certificate` produces a per-host leaf signed by
  the CA on the fly.
- Settings screen to export the CA cert (`KeyChain.createInstallIntent`) and
  check install status. **This is the step that requires the user to install
  the CA in Android's certificate settings** (or as a system CA on rooted
  devices) — the patched app's `network_security_config` will trust it once
  installed.

### 2. TLS interceptor (`net.kollnig.missioncontrol.intercept`)

- `TlsInterceptor` — per-session state: `SSLSocket clientSide` (toward app,
  presented with the leaf cert) and `SSLSocket serverSide` (toward upstream,
  using the default trust manager). Two pump threads per session copying bytes
  both directions, calling `capture(...)` on each chunk.
- `InterceptorRegistry` — pool of active sessions keyed by
  `(saddr,sport,daddr,dport)`. Bounded (e.g. 64 concurrent) with overflow
  falling back to pass-through.
- **Pass-through fallback** on any handshake failure (pinning not fully
  defeated, protocol error) — the app keeps working, that flow is just not
  decrypted.

### 3. Native hook (`app/src/main/jni/netguard/`)

The current TCP engine (`tcp.c:queue_tcp` line 977) reassembles segments and
forwards bytes plaintext to the upstream socket. To intercept TLS:

- Add `jni_intercept_tls(boolean)` mirroring `jni_sni` (`netguard.c:368`).
- Add `is_intercept` global in `ip.c` mirroring `is_play` (line 259).
- In `handle_tcp` (~`tcp.c:623`): if `args->intercept_tls && dest == 443` and
  the rules layer returned `allowed && intercept` for this flow:
  - Instead of opening the upstream socket in `connect_tcp`, post the TUN-side
    socket fd + session metadata up to Java via a new JNI callback
    `intercept_flow(Packet, int clientFd)`.
  - Java (`ServiceSinkhole.interceptFlow`, modelled on `isAddressAllowed`
    line 2118) hands the fd to `TlsInterceptor`.
  - The native session's `write_data` path is then drained by Java owning the
    socket; native stops forwarding upstream and only relays TUN ↔ client fd.
- Persist the toggle as `intercept_tls_enabled` pref, default **off**, gated
  to non-WG-active state (WG outbound packets bypass `tcp.c`/`udp.c` entirely
  at `ip.c:571`; intercepting them needs a separate, more invasive Rust-side
  hook — out of scope for v1).

### 4. Capture pipeline + storage

- New `content` table in `DatabaseHelper.java`:
  ```sql
  CREATE TABLE content (
    id INTEGER PRIMARY KEY,
    access_id INTEGER,            -- FK to access.rowid (uid,daddr,dport,...)
    direction INTEGER,            -- 0=request, 1=response
    seq INTEGER,
    mime TEXT,
    mime_utf8 INTEGER,            -- is it printable
    body BLOB,                    -- capped, e.g. 16 KB per chunk
    time INTEGER,
    redacted INTEGER
  );
  ```
- `ContentCaptureStore` (Kotlin) — writers from `TlsInterceptor.capture(...)`:
  - Redaction regexes for `Authorization`, `Cookie`, `Set-Cookie`, `token=`,
    `password=` etc. (configurable list).
  - Size cap per flow (e.g. 64 KB total) to bound DB growth.
  - Binary detection — only store printable/UTF-8; otherwise store mime +
    length only.

### 5. UI

- New `ActivityContent` / `AdapterContent` mirroring `ActivityLog` — list of
  captured flows with uid, host, time, size.
- Detail view: per-flow request/response viewer (hex + utf-8 panes), redacted
  sections marked.
- Entry point from `DetailsActivity` (per-app) and a top-level "Content" tab.

### 6. Privacy & UX guards

- Toggle default off, with a clear warning dialog (mirrors the existing SNI
  research-mode warning flow).
- Per-app allowlist for interception (extend `Rule.java`) — defaults to empty;
  user explicitly opts apps in. Avoids intercepting system apps.
- A "panic" clear-all button on the Content activity that drops the table.
- Crash safety: if `TlsInterceptor` fails a handshake, fall back to
  **pass-through** so the app keeps working — never break connectivity.

### 7. WireGuard path

- `wgbridge-rs/src/transport/ip_send.rs:38` writes decrypted inbound packets
  to the TUN fd; outbound encrypted packets bypass `tcp.c`/`udp.c` (go
  straight to `wg_fd` at `ip.c:571`).
- For TLS interception on the WG path you'd need to also intercept the
  **outbound** encrypted WG packets before encryption, which is more invasive
  (you'd hook the Rust tunnel's outbound send). **Out of scope for v1** —
  restrict the toggle to disabled-when-WG-active, mirroring how `is_play` is
  gated. Document this in the toggle's settings summary.

## Suggested implementation order

1. `LocalCaManager` + settings entry + CA install flow. Verify a test app
   with `network_security_config` trusting user CAs accepts a signed leaf.
2. `content` table + `ContentCaptureStore` + stub UI list.
3. `TlsInterceptor` in pure Kotlin against a unit-tested in-process TLS pair
   (no native integration yet) — prove the MITM logic.
4. Native hook: `jni_intercept_tls` + `intercept_flow` callback + plumb the
   client fd to `TlsInterceptor`. Pass-through fallback on any error.
5. UI detail view + redaction.
6. Per-app allowlist + WG-disable guard.
7. Lint/typecheck, tests, manual end-to-end.

## Open design questions

1. **HTTP/2 and WebSocket:** `javax.net.ssl` exposes plaintext bytes after
   TLS, but HTTP/2 framing and WebSocket need parsing to be readable. Ship a
   basic HTTP/1.x parser only for v1, or include h2 via OkHttp's `Http2`
   codec?
2. **Certificate install UX:** Should the app guide the user through the
   Android CA-install flow with a wizard, or just present the cert and let
   them install it manually?
3. **Per-flow vs per-app toggle:** Granular per-flow control (intercept some
   hosts but not others) adds complexity; per-app is simpler and probably
   sufficient for v1.
