#!/usr/bin/env bash
# What GitHub Actions runs: SDK packages, uBlock Origin, then the release APKs into out/.
set -euxo pipefail
sdk="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdk" --licenses > /dev/null || true
"$sdk" "platforms;android-36" "build-tools;36.0.0" > /dev/null
./scripts/fetch-ublock.sh
./gradlew --no-daemon --stacktrace assembleRelease
mkdir -p out
cp app/build/outputs/apk/release/*.apk out/
./gradlew -q --no-daemon :app:dependencies --configuration releaseRuntimeClasspath | grep -m1 geckoview > out/engine.txt || true
ls -la out
