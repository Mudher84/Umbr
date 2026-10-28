#!/usr/bin/env bash
# Runs inside the emulator job: install, open pages, take screenshots, keep the log.
set -x
mkdir -p shots
t() { timeout 60 "$@"; }
shot() {
  t adb shell screencap -p /sdcard/shot.png >> shots/adb.txt 2>&1
  t adb pull /sdcard/shot.png "shots/$1.png" >> shots/adb.txt 2>&1
  ls -la "shots/$1.png" >> shots/adb.txt 2>&1
}
t adb wait-for-device
timeout 300 adb install --no-incremental -r umbra-test.apk > shots/install.txt 2>&1
cat shots/install.txt
t adb logcat -c
t adb shell am start -n app.umbra.browser/.MainActivity
sleep 30
shot 1-newtab
t adb shell am start -a android.intent.action.VIEW -d https://en.wikipedia.org/wiki/Black_panther app.umbra.browser
sleep 5
shot 2-loading
sleep 20
shot 3-wikipedia
t adb shell am start -a android.intent.action.VIEW -d https://www.youtube.com app.umbra.browser
sleep 25
shot 4-youtube
t adb shell "cmd uimode night yes"
sleep 8
shot 5-dark
t adb shell "cmd uimode night no"
sleep 3
t adb logcat -d > shots/logcat.txt
grep -E "AndroidRuntime|FATAL|Umbra|uBlock" shots/logcat.txt | head -300 > shots/errors.txt
true
