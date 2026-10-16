#!/bin/sh
# One shell owns the exit status. emulator-runner executes separate script lines
# in separate shells, so an in-memory result variable across lines is unsafe.
set -eu
collect() {
  mkdir -p ../runtime-results
  adb logcat -d > ../runtime-results/logcat.txt || true
  adb pull /sdcard/Download/CarSync-previews ../runtime-results/screenshots || true
}
trap collect EXIT
sh gradlew assembleRelease --no-daemon
adb install app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n app.carsync.music/.MainActivity
sleep 3
adb shell ps -A | grep app.carsync.music || adb shell ps | grep app.carsync.music
adb uninstall app.carsync.music
adb install /tmp/carsync-previous.apk
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -W -n app.carsync.music/.MainActivity
sleep 3
adb shell ps -A | grep app.carsync.music || adb shell ps | grep app.carsync.music
adb uninstall app.carsync.music
sh gradlew connectedDebugAndroidTest --no-daemon
