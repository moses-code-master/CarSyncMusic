# CarSync Convoy v1.7 — experimental branch

This branch adds a prototype PTT PCM relay, floor arbitration, foreground-service opt-in GPS publishing, local DNS-SD advertisement and discovery, separation calculation, and guest voting.

## Known constraints

- Do **not** treat this branch as a production release until the Android build, Node.js tests and physical head-unit acceptance tests pass.
- PTT uses PCM16 at 16 kHz over authenticated WebSockets, not Opus. The microphone foreground service starts from a visible, permission-granted user action, and capture begins only after the server grants the floor. Do not rely on PTT as an emergency communication device. Use stop-talking to release the floor. A server lease expires after 8 seconds without frames. The Android implementation uses start/stop controls rather than press-and-hold.
- Location is opt-in and shared only with authenticated members of the same session. The backend holds live observations in memory, not SQLite, and hides stale coordinates. Android now uses a user-started location foreground service with a visible notification and stops it on session loss or exit. Background permission is not requested. Test OEM battery optimizations and notification permission behavior on physical car units.
- With location sharing active, GPS speed greater than 5 km/h can automatically enable restrictive driver mode; GPS cannot be relied on as the sole safety source.
- The radar UI now displays straight-line separation for two consenting participants with fresh GPS fixes, and labels gaps greater than 2.5 km. It does not know the road route, reliably determine who is ahead, or send push alerts.
- LAN discovery finds an external Node relay that advertises _carsync._tcp. Enable server-side advertising **only on a trusted local network** with `CARSYNC_LAN_DISCOVERY=1 npm start`; the app shows the discovered URL for explicit user review before saving. On older Android, multicast receive permissions are requested at installation. Android still does not host a local relay or join automatically; a Node server must be running on the LAN.
- Cabin-versus-convoy transports have not been split. The cloud relay remains the common synchronization authority in both LAN and internet modes.
- A parked-only 1–6 kHz acoustic chirp probe now estimates device speaker-to-microphone loopback delay through FFT cross-correlation. **This measurement includes microphone and room delay. Never copy its value directly into output-latency compensation.** True inter-device acoustic calibration remains incomplete. Native Opus, guest-supplied file transfer, self-hosting on an Android head unit, synchronized visualizer, and local TLS pinning are not implemented.
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

## Deployment caution

The Node relay advertises itself over mDNS only if `CARSYNC_LAN_DISCOVERY=1` is set on a trusted local LAN. Set it for a laptop-hosted relay, not the public Render deployment. Open TCP 8080 and UDP 5353 on the trusted local firewall as appropriate. An Android hotspot may isolate connected clients or suppress multicast; no automatic discovery guarantee applies. Cloud relay remains the fallback. A separate encrypted LAN design remains outstanding.

## Release gate

This branch stays a draft until: backend tests, Android unit tests, Android lint and emulator checks pass; two physical head units complete playback, PTT, reconnection and permission-denial exercises; speaker volume restores after voice frames; location opt-out propagates; microphone and GPS stop after exit. No device tests were performed merely by generating this source.
