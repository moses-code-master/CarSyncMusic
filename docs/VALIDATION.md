# Verification performed — 14 October 2026

## Android

- `assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL.
- 3 JVM tests passed, 0 failures/errors: clock-offset/outlier estimation, timeline projection and bounded drift correction, private-network URL validation.
- Installable debug APK produced: `app.carsync.music`, version 1.0.0, minimum Android API 26 (Android 8), target/compile API 35.
- APK Signature Scheme v2 verification passed. Development signing key; use your own release key for distribution.
- Packaged architectures: arm64-v8a, armeabi-v7a, x86, x86_64. APK approximately 14 MB.
- No Android device/emulator runtime testing was performed. Runtime import, speaker playback, foreground-service behavior, OEM compatibility and responsive UI have not been exercised on a device.

## Backend

Seven automated tests use real HTTP/WebSocket connections and temporary SQLite databases. They cover scheduled playback frames, two-car playlist/command agreement, duplicate commands, guest authorization, clock timestamp responses, compact status traffic, authenticated reconnect, sharing permission/revocation, upload hash integrity, byte-range playback, missing tracks, session capacity, host disappearance/automatic progression, and backend restart recovery.

See `backend-test-results.txt` for the final run and `backend-audit.txt` for the dependency audit. These are server/protocol tests; they do not render audio. The original test-audio files are included for physical playback testing.

## Remaining acceptance testing

GitHub/cloud preparation: GitHub workflow YAML parsed successfully; both Render configuration files validated against Render's published JSON schema and Render's authenticated validation API. The selected root configuration is `plan: free`, with no paid disk. The repository is uploaded to `moses-code-master/CarSyncMusic`. [GitHub run 38573596239](https://github.com/moses-code-master/CarSyncMusic/actions/runs/38573596239) passed the backend checks, compiled the Android APK, passed its tests and uploaded the APK artifact. The GitHub-built APK signature verified successfully. Render service creation currently rejects the private repository as unfetchable, so the cloud music server is not deployed yet.

All ten physical scenarios in `TESTING.md` remain to be run on two devices. No audible synchronization accuracy, car-to-car delay, road-trip connection stability or real recovery-time figures have been claimed. The <100 ms objective remains a target requiring acoustic measurement and per-device output calibration. Internet deployment configuration is supplied, but a public server has not been provisioned or deployed. Docker deployment has not been run on this Windows machine.
