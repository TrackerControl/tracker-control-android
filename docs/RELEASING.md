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
- `SHA256SUMS.txt`
- `BUILD-INFO.txt`

Install and test the APK from the draft, then publish the release in GitHub.

If more than one successful build exists for the same commit, pass the desired
Actions run ID explicitly:

```bash
./scripts/build_and_sign.sh 2026071301 1234567890
```

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
