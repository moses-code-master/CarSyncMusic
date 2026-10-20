# CarSync Convoy v1.7 — experimental branch

This branch adds a prototype PTT PCM relay, floor arbitration, optional GPS publishing, client-side LAN discovery and guest queue voting.

## Known constraints

- Do **not** treat this branch as a production release until the Android build, Node.js tests and physical head-unit acceptance tests pass.
- PTT uses PCM16 at 16 kHz over authenticated WebSockets, not Opus. The microphone starts only while the foreground player UI has permission and the server grants the floor. Do not rely on PTT as an emergency communication device. Use stop-talking to release the floor. A server lease expires after 8 seconds without frames. The Android implementation uses start/stop controls rather than press-and-hold.
- Location is explicitly opt-in and is broadcast only to authenticated members in the same session. The backend retains the latest reported location in memory, not SQLite. GPS foreground-service and background location support are **not** implemented. Position reporting while Android stops the activity may be unreliable.
- The radar UI currently toggles sharing but does not render geographic distances or gap alerts.
- LAN discovery finds a service only if an existing relay registers the _carsync._tcp. service. The current Node backend does not advertise this service, and Android does not host a local relay. Discovery is **not** automatic pairing yet.
- Cabin-versus-convoy transports have not been split. The cloud relay remains the common synchronization authority in both LAN and internet modes.
- This update does not implement acoustic chirp calibration, native Opus, guest-supplied file transfer, or TLS pinning.
- Global usesCleartextTraffic=true remains enabled for existing LAN HTTP support; do not use an untrusted LAN for sensitive audio or location data.
- Physical output delay and Android system volume vary across head units. Verify music is not interrupted when speech begins or ends.
- Voting applies to the upcoming queue, keeps the current track in place and only accepts authenticated session members. A single vote per user toggles on repeated selection.

## Suggested checks

1. Run `cd backend && npm ci && npm test` with Node 24.
2. Run `cd android && ./gradlew test assembleDebug` with a configured Android SDK.
3. Pair two Android head units; verify microphone permissions and app foreground behavior.
4. Test simultaneous talk requests, channel release, speaker disconnect and an extended mute.
5. Test GPS consent, disabling GPS, and disconnecting the session.
6. Test song voting and unchanged host-only playback privileges.
7. Test network handoff between LAN and mobile data before accepting the new behavior.

All additions are on feature/convoy-hub-v17 until review and merge.
