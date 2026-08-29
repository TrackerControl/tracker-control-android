# Building, testing, linting

The everyday commands are in AGENTS.md. This file covers the prerequisites, the
flavour matrix, the native builds, and the reproducibility flags.

## Prerequisites

JDK 17, Android SDK (compile/target SDK 37, min SDK 23), NDK `27.2.12479018`,
CMake. Native builds also need Rust ≥ 1.95 with the four Android targets;
the WireGuard bridge additionally needs `cargo-ndk`. Gradle wires both Rust
builds in but deliberately does not install tools or fetch crates. See
`wgbridge-rs/README.md` for setup and F-Droid build metadata.

## Flavours

Three product flavours — **github**, **fdroid**, **play** — differ only in the
update-check API; **github** is the normal local dev and real-device flavour.
Debug builds install side-by-side (`applicationIdSuffix ".test"`). Before
updating an existing GitHub debug install on a real device, back up its
configuration, especially WireGuard profiles and the Mullvad login — see
`agents/docs/device-testing.md`.

## Commands

```bash
# From the repo root. Use ./gradlew (the wrapper).

# Full debug APK (also triggers the native C + Rust builds):
./gradlew assembleGithubDebug

# Fast compile check while iterating on Java (what most PRs verify against):
./gradlew :app:compileGithubDebugJavaWithJavac -q

# JVM unit tests (Robolectric); swap the flavour as needed:
./gradlew :app:testGithubDebugUnitTest
# One test class/method:
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest'
./gradlew :app:testGithubDebugUnitTest --tests 'net.kollnig.missioncontrol.SomeTest.someMethod'

# Android lint (MissingTranslation / ExtraTranslation are disabled on purpose):
./gradlew :app:lintGithubDebug
```

**Rust host tests** (config/DNS/key parsing — no device needed):

```bash
cd wgbridge-rs && cargo test --workspace
cd wgbridge-rs && cargo test -p tc-dns --features capi
```

## Native code builds automatically with the app

- The **C engine** builds via CMake through AGP's `externalNativeBuild` and
  calls `tc-dns` through the C ABI exported by `libwgbridge.so`. CMake
  configure/build tasks depend on `wgbridgeBuild`; JVM compilation and unit
  tests remain Rust-free.
- The **Rust WireGuard bridge** builds via the `wgbridgeBuild` Gradle task, which
  runs `cargo ndk` for all four ABIs and is needed only by JNI-library packaging
  tasks. It also tracks the shared `tc-dns` source because the bridge links the
  same core.
  If Gradle can't find `cargo` (Android Studio sanitizes `PATH`), pass
  `-PcargoBin=/path/to/cargo` or put `~/.cargo/bin` on `PATH`.

## Reproducibility

Reproducibility is deliberately protected (path remapping, stripped `.comment`/
build-id, 16 KB page alignment) in both `build.gradle` and `CMakeLists.txt` —
don't undo those flags casually; IzzyOnDroid green-list depends on them.
