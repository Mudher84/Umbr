#!/usr/bin/env bash
# Runs inside the emulator job: install, open pages, take screenshots, keep the log.
set -x
mkdir -p shots
adb install -r umbra-test.apk > shots/install.txt 2>&1
adb logcat -c
adb shell am start -n app.umbra.browser/.MainActivity
sleep 25
adb exec-out screencap -p > shots/1-newtab.png
adb shell am start -a android.intent.action.VIEW -d https://en.wikipedia.org/wiki/Black_panther app.umbra.browser
sleep 4
adb exec-out screencap -p > shots/2-loading.png
sleep 18
adb exec-out screencap -p > shots/3-wikipedia.png
adb shell am start -a android.intent.action.VIEW -d https://www.youtube.com app.umbra.browser
sleep 20
adb exec-out screencap -p > shots/4-youtube.png
adb shell "cmd uimode night yes"
sleep 6
adb exec-out screencap -p > shots/5-dark.png
adb shell "cmd uimode night no"
sleep 4
adb logcat -d > shots/logcat.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|Umbra|uBlock|E GeckoConsole" | head -300 > shots/errors.txt
true
