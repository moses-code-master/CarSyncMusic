# Verification performed — 15 October 2026, version 1.2.0

## Compatibility changes

Minimum Android version lowered from API 26 (Android 8.0) to API 23 (Android 6.0). Version code 4, version name 1.2.0. Published APK uses the release build with debuggable=false, explicit v1/v2 signatures, and the same persistent private test signing key as 1.1/1.1.1. This retains update compatibility for those builds. Version 1.0 used a different key and needs uninstall/reinstall.

The APK includes armeabi-v7a, arm64-v8a, x86 and x86_64 libraries. Android 5 and older remain unsupported. Android 15 already met the previous APK's minimum version; a generic “App not installed” message on a phone with no older installation has not established its root cause.

## Automated evidence

[Initial Android 6 compatibility run](https://github.com/moses-code-master/CarSyncMusic/actions/runs/38760760764) passed lint, APK build, three JVM clock tests, eight backend tests, five native playback tests on API 23, five on API 26, and six on API 35. The MediaStore fixture test requires API 29 and is excluded on older emulators.

[Release package verification](https://github.com/moses-code-master/CarSyncMusic/actions/runs/38761502843) builds the signed release APK and checks clean installation and launch on API 23, 26 and 35 before executing the native playback suite. The artifact CarSyncMusic-release-apk contains the published APK. Android lint checks the lowered API compatibility.

Runtime tests cover imported MP3 decoding after the source file is removed, advancing position, pause/seek/resume, next/previous/end progression, playlist editing, foreground service playback, host/guest shared playback and reconnect, missing audio, sharing permission and authenticated HTTP MP3 relay playback. API 35 also scans and plays a MediaStore MP3.

## Cloud and physical testing

The backend is unchanged in 1.2.0. Its free Render service is https://carsync-music.onrender.com; see cloud-test-results.txt for live secure WebSocket, rapid command sequencing, MP3 transfer/authorization, reconnect and telemetry checks. Free storage is temporary.

Emulator installation success does not identify an affected manufacturer's installer restriction or prove that every physical phone installs successfully. If a fresh install still fails, obtain the exact phone model, Android version and installer error (adb install provides a detailed error code). Physical speakers, manufacturer background policies, Bluetooth output latency and sub-100 ms acoustic synchronization still require device testing.
