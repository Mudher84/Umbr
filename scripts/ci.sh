#!/usr/bin/env bash
# What GitHub Actions runs: SDK packages, uBlock Origin, then the release APKs into out/.
set -euxo pipefail
sdk="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdk" --licenses > /dev/null || true
"$sdk" --list 2>/dev/null | grep -oE 'platforms;android-[0-9][^ ]*' | sort -uV | tail -5
plat=$("$sdk" --list 2>/dev/null | grep -oE 'platforms;android-37[^ ]*' | sort -uV | head -1)
"$sdk" "$plat" > /dev/null
./scripts/fetch-ublock.sh
# newest stable Android Gradle plugin, unless one is pinned in build.gradle.kts
if grep -q AGP_VERSION build.gradle.kts; then
  agp=$(curl -sL https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' | sed 's/<[^>]*>//g' | sort -V | tail -1)
  echo "AGP $agp"
  sed -i "s/AGP_VERSION/$agp/" build.gradle.kts
fi
./gradlew --no-daemon --stacktrace assembleRelease
mkdir -p out
cp app/build/outputs/apk/release/*.apk out/
./gradlew -q --no-daemon :app:dependencies --configuration releaseRuntimeClasspath | grep -m1 geckoview > out/engine.txt || true
ls -la out
