# Publishing a GitHub APK release

TrackerControl builds release APK payloads on Linux so that external rebuilders
can reproduce the native Rust libraries. The long-lived Android signing key
stays on the release maintainer's Mac and is never uploaded to GitHub Actions.

The release has two stages:

1. A numeric tag triggers `.github/workflows/release-build.yml`. It performs a
   clean, unsigned `githubRelease` build on Ubuntu 22.04 with pinned Android,
   Rust, cargo-ndk, and Java toolchains.
2. `scripts/build_and_sign.sh` downloads that exact workflow artifact, checks
   that its commit matches the tag, signs it locally, verifies the certificate,
   and creates a draft GitHub release.

## One-time local setup

Install and authenticate the GitHub CLI, and ensure Android SDK Build Tools are
installed:

```bash
gh auth login
"$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager" \
  'build-tools;36.1.0'
```

Place the release keystore at `trackercontrol.jks` in the repository root. The
path is ignored by Git. A different path can be supplied with
`RELEASE_STORE_FILE`.

The script expects the alias `trackercontrol`. Override it with
`RELEASE_KEY_ALIAS` if necessary.

Store both passwords in macOS Keychain under the service names used by the
existing release process:

```bash
security add-generic-password -U -a "$USER" \
  -s android_keystore_password -w
security add-generic-password -U -a "$USER" \
  -s android_key_password -w
```

Keep an offline backup of the keystore and its passwords. The expected public
certificate SHA-256 is hard-coded in the script so that the wrong keystore
cannot accidentally sign a release.

## Publish a release

First update and commit `versionCode` and `versionName` in `app/build.gradle`.
Create and push the numeric release tag:

```bash
git tag 2026071301
git push origin 2026071301
```

Wait for **Build unsigned GitHub release APK** to finish successfully. Then run:

```bash
./scripts/build_and_sign.sh 2026071301
```

The script writes the signed APK and verification metadata to
`output/release-2026071301/` and creates a draft GitHub release containing:

- `TrackerControl-githubRelease-latest.apk`
- `TrackerControl-githubRelease-latest.apk.sha256sums`
- `TrackerControl-githubRelease-latest.apk.build-info.txt`

The two metadata files are named with the APK filename as a prefix so they
always sort alphabetically after it. The GitHub API returns release assets
ordered by name, and the app's in-app update checker always downloads
`assets[0]`; without this ordering it can fetch `BUILD-INFO.txt` instead of
the APK (#681).

Smoke-test the signed APK (next section), then publish the release in GitHub.

If more than one successful build exists for the same commit, pass the desired
Actions run ID explicitly:

```bash
./scripts/build_and_sign.sh 2026071301 1234567890
```

## Smoke-test the signed APK

Test the artifact that will actually ship — the signed APK in
`output/release-<tag>/`, not a local debug build. It installs as
`net.kollnig.missioncontrol`, which is a *different package* from both the
`.fdroid` and `.test` debug installs, so it lands side by side and touches no
existing app data.

The release build is **not debuggable**, so the AGENTS.md popup-skipping recipe
only half applies: `run-as` is refused (`package not debuggable`), so the
onboarding preferences cannot be seeded and onboarding must be tapped through.
`appops` and `pm grant` still work, because those are enforced by the framework
rather than by the app's debuggable flag.

```bash
TAG=2026071301
adb install -r output/release-$TAG/TrackerControl-githubRelease-latest.apk

# Confirm the artifact under test is the one that was just built and signed.
adb shell dumpsys package net.kollnig.missioncontrol | grep -E 'versionCode|versionName'

# Optional: pre-grant VPN consent so VpnService.prepare() returns null and the
# consent dialog never appears. Works on the release package; run-as does not.
adb shell appops set net.kollnig.missioncontrol ACTIVATE_VPN allow

adb logcat -c
adb shell monkey -p net.kollnig.missioncontrol -c android.intent.category.LAUNCHER 1
```

**Announce the VPN side effect before enabling the tunnel.** Starting this
build's VPN calls `VpnService.prepare()`, which revokes whichever package
currently holds VPN consent — typically the maintainer's own `.fdroid` or
`.test` build, or their real VPN app. Those will show the consent dialog again
next time. Check the current holder and consent state first, and ask before
proceeding:

```bash
adb shell dumpsys connectivity | grep -oE 'VPN:[a-zA-Z0-9._]+' | sort -u
for p in net.kollnig.missioncontrol{,.fdroid,.test}; do
    printf '%s: ' "$p"; adb shell appops get "$p" ACTIVATE_VPN
done
```

Tap through onboarding. `ActivityOnboarding.setupSlides()` builds the list once
from device state, so pre-grant first (`install -g` plus the `appops` line
above) and the two permission slides drop out, leaving a fixed five-slide run:
**welcome → blocking mode → VPN lockdown → Disable Private DNS → timeline**.
Without pre-granting you also get the **Enable Protection** slide (third) and
the **Stay Informed** notification slide, and the VPN slide raises the system
consent dialog.

Three of these slides raise an "Are you sure?" confirmation on **Next**, and the
dialog's OK button moves vertically with the length of the message — so resolve
it rather than hardcoding it. Coordinates below are for **1080×2400**; check
with `adb shell wm size`.

```bash
# Tap the OK button of the current AlertDialog, wherever it happens to sit.
ok() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml \
        | grep -oE '<node[^>]*text="OK"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
        | grep -oE 'bounds="[^"]*"' | head -1 \
        | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
        | awk '{ printf "%d %d\n", ($1+$3)/2, ($2+$4)/2 }' \
        | xargs adb shell input tap
}
# Print the current slide's title and buttons.
where() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -oE 'text="[^"]{3,60}"' \
        | sed 's/text="//;s/"$//' | head -4
}

adb shell input tap 922 2256   # Next        — welcome
adb shell input tap 922 2256   # Next        — blocking mode (leave Minimal)
adb shell input tap 922 2256   # Next        — VPN lockdown  → confirmation
ok
adb shell input tap 922 2256   # Next        — Disable Private DNS → confirmation
ok
adb shell input tap 878 2256   # Get Started — timeline, last slide

adb shell input tap 178 2256   # Timeline tab
adb shell input tap 540 2256   # Apps tab
```

Call `where` between steps if anything desyncs; a slide whose bottom-right
button reads **Get Started** instead of **Next** is the last one, which is the
cheap check that the run ended where it should. Screenshot the result with
`adb exec-out screencap -p > shot.png`.

Two slides here are worth reading rather than clicking past, because both fire
on a device where the underlying setting is *not* actually a problem — measured
on a Pixel 8 (Android 17) with `always_on_vpn_lockdown=0` and
`private_dns_mode=opportunistic`:

- **VPN Lockdown Setting** offers "Open VPN Settings" and warns on Next even
  though lockdown is off. Per `refreshSlides()` it should read "Disabled" with
  no warning on Q+.
- **Disable Private DNS** appears for any mode other than `off`
  (`Util.isPrivateDns`), so `opportunistic` — Android's default — triggers a
  slide telling the user their setup is broken when DoT is not actually in use.
  #711 removes this slide; once that lands, the sequence above loses its second
  `ok` step.

Checks that must all pass before publishing:

1. **Correct build installed** — `versionCode`/`versionName` match the tag.
2. **Onboarding completes** without a crash, and lands on the main screen.
3. **Tunnel establishes** — `tun0` exists and connectivity names this package:
   ```bash
   adb shell ip -o addr show | grep tun
   adb shell dumpsys connectivity | grep -oE 'VPN:[a-zA-Z0-9._]+' | sort -u
   ```
4. **Detection works end to end** — generate traffic
   (`adb shell am start -a android.intent.action.VIEW -d https://example.com`,
   or open a tracker-heavy app), then confirm the **Timeline** tab lists real
   detections attributed to the right apps. An empty Timeline after real traffic
   means DNS parsing or attribution regressed.
5. **Apps tab renders** the full app list with icons.
6. **No crashes** across the whole run:
   ```bash
   adb logcat -d | grep -E 'FATAL EXCEPTION|E/AndroidRuntime'
   ```

Then verify the draft's asset order — the in-app updater takes `assets[0]`, so
the APK must sort first (#681):

```bash
gh release view "$TAG" --json assets --jq '.assets[].name'
```

Publish, and confirm the release is no longer a draft and is served as latest:

```bash
gh release edit "$TAG" --draft=false --latest
gh api repos/TrackerControl/tracker-control-android/releases/latest \
    --jq '.tag_name + "  assets[0]=" + .assets[0].name'
```

(`gh release view --json isLatest` is not a valid field; query the API's
`releases/latest` as above instead.)

Afterwards, tell the maintainer what the test changed on their device: the VPN
consent now sits with `net.kollnig.missioncontrol` and its tunnel is running.
Leave the package installed unless they ask for it to be removed — uninstalling
is state destruction and needs a yes, like everything else in AGENTS.md §3.

## Manually rebuild a tag or commit

The workflow can also be started from the Actions UI with **Run workflow** and
a tag or commit in the `ref` input. It still produces only an unsigned artifact;
the signing key remains local.

## Overrides

The local script supports these environment variables:

- `GH_REPO`
- `RELEASE_WORKFLOW`
- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_CERT_SHA256`
- `RELEASE_BUILD_TOOLS_VERSION`
- `RELEASE_OUTPUT_DIR`
