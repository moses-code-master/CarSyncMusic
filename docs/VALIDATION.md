# Verification performed — 15 October 2026

## Android 1.1.0

[Verified GitHub run](https://github.com/moses-code-master/CarSyncMusic/actions/runs/38651374239) passed Android build, three JVM tests, eight backend tests, and native instrumentation jobs on API 26 (Android 8) and API 35 (Android 15).

API 35: six runtime tests passed, zero failures/errors. API 26: five passed, with the MediaStore fixture test excluded because it requires API 29. Native tests decode original MP3 audio through ExoPlayer and assert position advancement, pause, seek, resume, next/previous, end-of-track advance, playlist rename/reorder, background service playback, host/guest shared controls, reconnect, and permitted HTTP relay playback. The API 35 test inserts an MP3 into the phone's MediaStore, scans it, and verifies playback. Picker import playback is checked after deleting the source file.

The production APK is the debug artifact from this verified run, package app.carsync.music, versionCode 2, versionName 1.1.0. It uses a persistent private test key stored in GitHub Actions secrets; v1.0 must be uninstalled once before installation because its key differs. Do not commit keystores. UI preview collection is a separate test-only follow-up; app code is unchanged.

## Backend and cloud

Eight automated server tests cover authenticated two-car sessions, scheduled controls, duplicate commands, reconnect, rapid chained controls, sharing/revocation, MP3 hashes and byte ranges, missing files, session capacity, progression, and restart recovery. Rapid track-selection/play and seek/play now preserve pending commands.

The free Render service is https://carsync-music.onrender.com. See cloud-test-results.txt for live HTTPS, secure WebSocket, upload/range access, authorization, reconnect, and telemetry checks. Free storage is temporary; deployment or restart can remove sessions and uploaded songs.

## Physical testing still required

Emulator decoder/timeline tests do not prove sound from a particular phone's speaker, aftermarket head-unit compatibility, acoustic synchronization below 100 ms, Bluetooth output latency, or road-trip handover. Run docs/TESTING.md on the actual devices while parked. No physical speaker timing was measured.

