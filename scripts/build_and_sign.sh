#!/bin/bash

# first run docker build -t fdroid-buildserver fdroidserver/buildserver

export RELEASE_STORE_PASSWORD=$(security find-generic-password - a $USER -s "android_keystore_password" -w)
export RELEASE_KEY_PASSWORD=$(security find-generic-password -a $USER -s "android_key_password" -w)

docker run --rm \
    -v "$(pwd)/output:/workspace/output" \
    -w /workspace \
    -e RELEASE_STORE_FILE="/workspace/trackercontrol.jks" \
    -e RELEASE_STORE_PASSWORD="$RELEASE_STORE_PASSWORD" \
    -e RELEASE_KEY_ALIAS="trackercontrol" \
    -e RELEASE_KEY_PASSWORD="$RELEASE_KEY_PASSWORD" \
    fdroid-buildserver \
    bash -c "git clone https://github.com/TrackerControl/tracker-control-android.git && \
             cd tracker-control-android && \
             export ANDROID_HOME=/opt/android-sdk && \
             ./gradlew clean assembleGithubRelease --no-build-cache --no-configuration-cache --no-daemon && \
             cp -r app/build/outputs/apk/github/release/ /workspace/output/"

#docker run --rm \
#    -v "$(pwd):/workspace" \
#    -v "$(pwd)/output:/workspace/output" \
#    -w /workspace \
#    -e RELEASE_STORE_FILE="/workspace/trackercontrol.jks" \
#    -e RELEASE_STORE_PASSWORD="$RELEASE_STORE_PASSWORD" \
#    -e RELEASE_KEY_ALIAS="trackercontrol" \
#    -e RELEASE_KEY_PASSWORD="$RELEASE_KEY_PASSWORD" \
#    fdroid-buildserver \
#    bash -c "export ANDROID_HOME=/opt/android-sdk && \
#             ./gradlew clean assembleGithubRelease --no-build-cache --no-configuration-cache --no-daemon && \
#             mkdir -p /workspace/output && \
#             cp -r app/build/outputs/apk/github/release/ /workspace/output/"