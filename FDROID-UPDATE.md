# Updating TrackerControl on F-Droid

TrackerControl's WireGuard engine is compiled from Rust source. New F-Droid
build entries must prepare the pinned Rust toolchain and Cargo dependencies
before Gradle invokes Cargo in locked, offline mode.

The examples below use release `2026071301` / `2026.07.13`. Substitute the
actual version code and version name for later releases.

## 1. Tag the release

Only tag a commit that includes the current `rust-toolchain.toml`,
`wgbridge-rs/Cargo.lock`, and `scripts/setup_rust_android.sh` files.
`rust-toolchain.toml` is authoritative for the Rust version, profile, and
Android targets; the setup script installs that specification.

```bash
git tag 2026071301
git push origin 2026071301
```

TrackerControl's F-Droid metadata uses numeric tags as release identifiers.

## 2. Prepare an fdroiddata merge request

Fork [fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata), then clone the
fork and create a branch:

```bash
git clone https://gitlab.com/YOUR_USERNAME/fdroiddata.git
cd fdroiddata
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream master
git switch -c trackercontrol-2026071301 upstream/master
```

Edit:

```text
metadata/net.kollnig.missioncontrol.fdroid.yml
```

Append this entry under `Builds:`:

```yaml
  - versionName: 2026.07.13-fdroid
    versionCode: 2026071301
    commit: '2026071301'
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y rustup gcc libc-dev
    gradle:
      - fdroid
    prebuild:
      - ../scripts/setup_rust_android.sh
    ndk: r27c
```

Update the current-version fields at the bottom of the file:

```yaml
CurrentVersion: 2026.07.13-fdroid
CurrentVersionCode: 2026071301
```

Keep the automatic-update configuration unchanged:

```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags ^[0-9]+$
```

## 3. Validate the metadata

With `fdroidserver` installed and available on `PATH`, run from the
`fdroiddata` checkout:

```bash
fdroid readmeta
fdroid lint net.kollnig.missioncontrol.fdroid
```

For an end-to-end local application check, provision the pinned Rust and
Gradle dependencies while networking is available, then build the release
offline:

```bash
./scripts/setup_rust_android.sh
./gradlew :app:dependencies :app:prefetchAndroidLintDependencies \
  --no-daemon --no-configuration-cache
./gradlew :app:assembleFdroidRelease --offline \
  --no-build-cache --no-configuration-cache --no-daemon
```

The APK should contain `libwgbridge.so` for `armeabi-v7a`, `arm64-v8a`, `x86`,
and `x86_64`.

## 4. Submit the metadata change

From the `fdroiddata` checkout:

```bash
git add metadata/net.kollnig.missioncontrol.fdroid.yml
git commit -m "Update TrackerControl to 2026071301"
git push origin trackercontrol-2026071301
```

Open a merge request from that branch into `fdroid/fdroiddata:master`.

Suggested merge-request description:

```text
TrackerControl now builds its WireGuard engine from Rust source.

The prebuild step installs the repository-pinned Rust toolchain, Android
targets, and cargo-ndk, and fetches Cargo.lock dependencies. The Gradle build
itself runs Cargo with --locked --offline.

Locally verified with:
./gradlew :app:assembleFdroidRelease --offline --no-build-cache \
  --no-configuration-cache --no-daemon
```

## If the update bot opens a merge request first

The checkupdates bot normally copies the previous build recipe, which does not
contain the Rust preparation steps. Its source branch belongs to F-Droid's bot
fork and may not be editable by the TrackerControl maintainers.

Create the complete merge request from the TrackerControl maintainer's own
`fdroiddata` fork as described above. Comment on the bot merge request with a
link to the replacement and ask F-Droid's maintainers to close the bot merge
request in favor of it.

F-Droid's general contribution guide is available at
<https://fdroid.gitlab.io/jekyll-fdroid/docs/Submitting_to_F-Droid_Quick_Start_Guide/>.
