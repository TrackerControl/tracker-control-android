#!/bin/bash
set -euo pipefail

readonly REPO=${GH_REPO:-TrackerControl/tracker-control-android}
readonly WORKFLOW=${RELEASE_WORKFLOW:-release-build.yml}
readonly ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
readonly KEYSTORE=${RELEASE_STORE_FILE:-"$ROOT/trackercontrol.jks"}
readonly KEY_ALIAS=${RELEASE_KEY_ALIAS:-trackercontrol}
readonly EXPECTED_CERT_SHA256=${RELEASE_CERT_SHA256:-7f48f74781632067cae334734ff2bdf54eb2a977c061a327499ca9109202d680}

usage() {
    cat >&2 <<EOF
Usage: $0 <numeric-release-tag> [workflow-run-id]

Downloads the successful unsigned Linux build for the tag, signs it with the
TrackerControl release key, verifies the signing certificate, and creates a
draft GitHub release.

Defaults:
  repository: $REPO
  keystore:   $KEYSTORE
  key alias:  $KEY_ALIAS

See docs/RELEASING.md for setup and the complete release procedure.
EOF
    exit 2
}

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

[[ $# -ge 1 && $# -le 2 ]] || usage
readonly TAG=$1
readonly REQUESTED_RUN_ID=${2:-}
[[ $TAG =~ ^[0-9]+$ ]] || fail "release tag must be numeric: $TAG"

require_command gh
require_command git
require_command security
require_command shasum
gh auth status >/dev/null

[[ -f $KEYSTORE ]] || fail "keystore not found: $KEYSTORE"

ANDROID_SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}}
readonly ANDROID_SDK
readonly BUILD_TOOLS_VERSION=${RELEASE_BUILD_TOOLS_VERSION:-36.1.0}
readonly BUILD_TOOLS_DIR="$ANDROID_SDK/build-tools/$BUILD_TOOLS_VERSION"
[[ -d $BUILD_TOOLS_DIR ]] || \
    fail "Android SDK Build Tools $BUILD_TOOLS_VERSION not found under $ANDROID_SDK"
readonly APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
readonly ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
[[ -x $APKSIGNER ]] || fail "apksigner not executable: $APKSIGNER"
[[ -x $ZIPALIGN ]] || fail "zipalign not executable: $ZIPALIGN"

printf 'Resolving release tag %s in %s...\n' "$TAG" "$REPO"
readonly TAG_SHA=$(gh api "repos/$REPO/commits/$TAG" --jq .sha)
[[ $TAG_SHA =~ ^[0-9a-f]{40}$ ]] || fail "could not resolve tag $TAG to a commit"

if [[ -n $REQUESTED_RUN_ID ]]; then
    RUN_ID=$REQUESTED_RUN_ID
else
    RUN_ID=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" \
        --commit "$TAG_SHA" --status success --limit 10 \
        --json databaseId --jq '.[0].databaseId')
fi
readonly RUN_ID
[[ -n $RUN_ID && $RUN_ID =~ ^[0-9]+$ ]] || \
    fail "no successful $WORKFLOW run found for $TAG_SHA"

readonly RUN_SHA=$(gh run view "$RUN_ID" --repo "$REPO" --json headSha --jq .headSha)
readonly RUN_EVENT=$(gh run view "$RUN_ID" --repo "$REPO" --json event --jq .event)
readonly RUN_STATUS=$(gh run view "$RUN_ID" --repo "$REPO" \
    --json status,conclusion --jq '.status + "/" + .conclusion')
readonly RUN_URL=$(gh run view "$RUN_ID" --repo "$REPO" --json url --jq .url)
[[ $RUN_EVENT == workflow_dispatch || $RUN_SHA == "$TAG_SHA" ]] || \
    fail "workflow run built $RUN_SHA, but tag $TAG resolves to $TAG_SHA"
[[ $RUN_STATUS == completed/success ]] || \
    fail "workflow run is not successful: $RUN_STATUS ($RUN_URL)"

readonly TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/trackercontrol-release.XXXXXX")
cleanup() {
    rm -rf -- "$TEMP_DIR"
    unset RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD
}
trap cleanup EXIT

readonly ARTIFACT_DIR="$TEMP_DIR/artifact"
mkdir -p "$ARTIFACT_DIR"
printf 'Downloading unsigned build from %s...\n' "$RUN_URL"
gh run download "$RUN_ID" --repo "$REPO" \
    --name "trackercontrol-github-release-$TAG_SHA" \
    --dir "$ARTIFACT_DIR"

readonly UNSIGNED_APK="$ARTIFACT_DIR/TrackerControl-githubRelease-unsigned.apk"
readonly UNSIGNED_SUMS="$ARTIFACT_DIR/SHA256SUMS-unsigned.txt"
readonly BUILD_INFO="$ARTIFACT_DIR/BUILD-INFO.txt"
[[ -f $UNSIGNED_APK ]] || fail "unsigned APK missing from downloaded artifact"
[[ -f $UNSIGNED_SUMS ]] || fail "unsigned checksum missing from downloaded artifact"
[[ -f $BUILD_INFO ]] || fail "build information missing from downloaded artifact"
grep -qx "commit=$TAG_SHA" "$BUILD_INFO" || \
    fail "build information does not identify the expected commit"
(
    cd "$ARTIFACT_DIR"
    shasum -a 256 -c "$(basename "$UNSIGNED_SUMS")"
)

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    fail "GitHub release $TAG already exists; refusing to overwrite release assets"
fi

if [[ -z ${RELEASE_STORE_PASSWORD:-} ]]; then
    RELEASE_STORE_PASSWORD=$(security find-generic-password \
        -a "$USER" -s android_keystore_password -w)
fi
if [[ -z ${RELEASE_KEY_PASSWORD:-} ]]; then
    RELEASE_KEY_PASSWORD=$(security find-generic-password \
        -a "$USER" -s android_key_password -w)
fi
export RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD

readonly OUTPUT_DIR=${RELEASE_OUTPUT_DIR:-"$ROOT/output/release-$TAG"}
mkdir -p "$OUTPUT_DIR"
readonly ALIGNED_APK="$TEMP_DIR/TrackerControl-githubRelease-aligned.apk"
readonly TEMP_SIGNED_APK="$TEMP_DIR/TrackerControl-githubRelease-signed.apk"
readonly SIGNED_APK="$OUTPUT_DIR/TrackerControl-githubRelease-latest.apk"
readonly SIGNED_SUMS="$OUTPUT_DIR/SHA256SUMS.txt"
readonly OUTPUT_BUILD_INFO="$OUTPUT_DIR/BUILD-INFO.txt"
[[ ! -e $SIGNED_APK ]] || fail "signed output already exists: $SIGNED_APK"

printf 'Aligning and signing the APK...\n'
"$ZIPALIGN" -P 16 -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:RELEASE_STORE_PASSWORD \
    --key-pass env:RELEASE_KEY_PASSWORD \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled false \
    --v4-signing-enabled false \
    --out "$TEMP_SIGNED_APK" \
    "$ALIGNED_APK"

readonly VERIFY_OUTPUT=$("$APKSIGNER" verify --verbose --print-certs "$TEMP_SIGNED_APK")
printf '%s\n' "$VERIFY_OUTPUT"
readonly ACTUAL_CERT_SHA256=$(printf '%s\n' "$VERIFY_OUTPUT" | awk -F': ' \
    '/Signer #1 certificate SHA-256 digest:/ { print $2; exit }')
[[ $ACTUAL_CERT_SHA256 == "$EXPECTED_CERT_SHA256" ]] || \
    fail "unexpected signing certificate: $ACTUAL_CERT_SHA256"
"$ZIPALIGN" -c -P 16 -v 4 "$TEMP_SIGNED_APK" >/dev/null
cp "$TEMP_SIGNED_APK" "$SIGNED_APK"

(
    cd "$OUTPUT_DIR"
    shasum -a 256 "$(basename "$SIGNED_APK")" > "$(basename "$SIGNED_SUMS")"
)
cp "$BUILD_INFO" "$OUTPUT_BUILD_INFO"
printf 'signing_certificate_sha256=%s\n' "$ACTUAL_CERT_SHA256" \
    >> "$OUTPUT_BUILD_INFO"
printf 'workflow_run=%s\n' "$RUN_URL" >> "$OUTPUT_BUILD_INFO"

printf 'Creating draft GitHub release %s...\n' "$TAG"
gh release create "$TAG" \
    "$SIGNED_APK" \
    "$SIGNED_SUMS" \
    "$OUTPUT_BUILD_INFO" \
    --repo "$REPO" \
    --verify-tag \
    --draft \
    --generate-notes \
    --title "$TAG"

printf '\nSigned APK: %s\n' "$SIGNED_APK"
printf 'A draft release was created. Test the APK, then publish it in GitHub.\n'
