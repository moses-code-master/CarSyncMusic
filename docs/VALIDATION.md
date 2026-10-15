# Verification performed — 16 October 2026, version 1.3.0

## Guest audio recovery fix

Previously, uploaded MP3s were broadcast in a session snapshot without changing the playback revision. Android only rescheduled audio for playback revisions/reconnect/revocation, so a guest with no local file could remain silent after an upload until Retry audio was tapped. The old test explicitly called Retry audio and did not cover automatic recovery.

The backend now sends audioAvailable hashes independently of playback revisions. Android waits for the requested file, then prepares and catches up automatically when its availability changes. The host Player exposes an explicit Share music with guest action. After consent, missing playlist audio uploads automatically. Guests see whether sharing is off or the upload is pending. Files are not shared until the host opts in; disabling sharing removes uploaded audio and stops guest relay playback.

## Automated checks

[Version 1.3 verification](https://github.com/moses-code-master/CarSyncMusic/actions/runs/38819642046) builds the signed non-debuggable release APK, runs Android lint and three JVM tests, runs eight backend tests, and verifies clean release installation/launch plus native playback on API 23, 26 and 35.

The guest regression test has an empty local library. It clicks the actual host Player → Share music with guest button, waits for automatic upload and guest playback without any guest retry action, tests shared pause/resume, then revokes sharing and verifies silence/missing-file state. Other tests cover local decoding/seek/resume, background service playback, matching-local-file host/guest synchronization/reconnect, playlist edits and API 35 phone scanning.

The backend regression verifies that an uploaded file becomes available to the guest without a change to its playback revision, and that revocation clears availability. Live HTTPS/WebSocket checks passed the same notification path, rapid controls, authenticated MP3 byte-range transfer, reconnect and telemetry export. See cloud-test-results.txt. Test sessions and uploaded test files were deleted.

## APK and deployment

Version code 5, version name 1.3.0, minimum Android 6.0 (API 23), target API 35. Universal ARM32/ARM64/x86/x86_64 APK with v1/v2 signatures and the persistent private test key used since 1.1. Update both phones. Version 1.0 requires uninstall/reinstall because its key differs; versions 1.1+ can update in place.

Backend deployed to https://carsync-music.onrender.com on the existing free service. The app and server both need this fix for automatic guest recovery. Free hosting may sleep or lose sessions/uploads on restart. A fresh session and a new permitted upload may be needed after deployment.

## Physical testing limits

These are emulator decoder/timeline tests and real cloud protocol/transfer checks. They do not confirm speaker output or Bluetooth routing on the user's phones, manufacturer power-policy behavior, or sub-100 ms acoustic synchronization. If the guest remains silent after updating both phones and enabling sharing, inspect its on-screen message and audio output path. A generic phone installation error still needs its installer details if the signed release cannot be installed.
