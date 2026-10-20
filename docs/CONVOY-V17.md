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

## Why new controls appeared but did not work

Old CarSync backends support playback, session codes and MP3 host uploads, but **not** convoy PTT, location sharing, voting or guest songs. The Android app used `https://carsync-music.onrender.com` by default, while GitHub Actions only built/tested the branch: it **never deployed this experimental server**. This is a server/client version mismatch, not proof that the phones are incapable of talking.

The updated session snapshot includes `capabilities`, and `GET /health` responds with `{"ok":true,"protocol":"convoy-v1","capabilities":[...]}`. When the app connects to an older server, it explicitly labels convoy features unavailable instead of showing misleading buttons. **Update the server and both APKs together**.

## Two-phone verification with a separate development server

1. Install Node.js 24 on a laptop, and check out the **feature/convoy-v17-hardened** branch; do not use the older `main` backend.
2. Connect the laptop and two Android phones to the same trusted Wi-Fi. In `backend`, execute `npm ci` and `npm start`. The server defaults to TCP port 8080.
3. On both phones, open Settings and set the server to `http://<LAPTOP_LAN_IP>:8080`. Use a real laptop IPv4 address, not `127.0.0.1`. The cloud URL requires a separately deployed updated backend. On the laptop, open `http://localhost:8080/health` and confirm `"protocol":"convoy-v1"`.
4. On Phone A, create a new session. On Phone B, enter the six-digit code. Confirm both devices show connected and report no server upgrade warning.
5. While parked, on **either phone** choose Now Playing → Start talking, grant microphone permission, and check the other phone's speaker. Voice frames travel through the server; guest playback-control settings are unrelated to speech permission. Press Stop talking to release the voice floor.
6. For guest MP3s: on Phone A enable Settings → “I have permission to share this audio” and “Allow guests to add MP3 songs.” On Phone B use Music Library → Import files, select an MP3, then choose “Add MP3 to shared queue.” The server checks the file's SHA-256 and only adds it after a successful upload, so cloud tests incur mobile data traffic. Guest playback controls (play/pause/skip) remain **a separate, optional host setting**.
7. If needed, enable Settings → Guest playback controls on Phone A to allow both phones to play, pause, skip and seek.
8. Location sharing is optional on each device. Each must grant location permission. Turn it off before leaving the session.

### Deployment requirements and remaining limitations

- Don't deploy feature tests to production Render without a separate staging service and a backup plan. Updating an Android APK alone cannot update the backend.
- When creating a new Render Web Service from GitHub, select branch `feature/convoy-v17-hardened`, use the repository `backend/Dockerfile`, and verify its `/health` capabilities. If the platform deploys from main, it will serve the **older** protocol again.
- Local auto-discovery requires `CARSYNC_LAN_DISCOVERY=1` in the backend process and UDP multicast 5353 to traverse Wi-Fi; you can use the explicit server address when discovery fails.
- The intercom still uses PCM16 over WebSocket and lacks Opus, production echo testing and guaranteed foreground-device microphone support. This is not a safety-critical radio.
- The host must approve guest uploads by opting into sharing and guest contributions; guests do not receive admin privileges. Audio must be owned or shared with permission.
- On Render free storage, uploaded media and sessions can disappear on restart/redeploy. Test with short MP3 files first.

## Beta 3 server setup — automatic

Starting with `1.7.0-beta.3`, **no manual server entry is required**. The beta APK defaults to `https://carsync-music-convoy-test.onrender.com` on a new installation. On an upgraded phone that still uses the original uncustomized `https://carsync-music.onrender.com` default, it migrates to the convoy test backend **unless the phone is already in a session**. A phone in an existing session retains its endpoint until the user leaves that session; leaving migrates it to the beta endpoint.

Custom and explicitly saved URLs are preserved. To override the default, use Settings → Server and Save. For older installations without an explicit-override marker, a saved URL identical to the original stock default is treated as the old default. New tests verify migration and override behavior.

The stable v1.6.0 build on `main` and existing production Render service remain unchanged. This beta endpoint is a free, nonproduction staging service; its uploaded media and session records may be lost on restart and its startup may be delayed. Two phones must use **the same endpoint and a new session code**.


## Beta 4 — Talk & Listen and batch guest media

The dedicated **CAR-TO-CAR INTERCOM** appears on Home and Now Playing even with an empty music queue. Listening is automatic when connected; both host and guests may request the microphone. The app handles JSON `null` speaker IDs correctly, so an idle channel no longer becomes a stuck text `"null"` speaker. Stop talking releases the button immediately. Microphone capture retries using the standard MIC source when the voice-communication source is unavailable and reports recording failures.

Guests may select several MP3s in Music Library or multiple MP4 videos on the Videos page and submit them sequentially. The host must enable sharing and guest contributions. Accepted files must be at most **100 MB each**, with no more than **500 session playlist entries**, an overall **2 GB relay storage budget** and a per-guest submission rate limit; therefore uploads are **not literally unlimited**. The server verifies SHA-256 content and only appends each item after successful upload. Only `video/mp4` is supported for remote video streaming. MKV and WebM remain local-only. Playback on a screen requires parked confirmation and respects driving restrictions; audio may continue where supported.

The beta app and companion test backend must both be updated to this feature branch. The existing stable backend and Android release remain untouched. Because the staging server is on Render's free plan and has no persistent volume, uploaded media and sessions may be lost on a restart or redeploy. Actual head-unit microphone/speaker behavior still requires a two-phone or two-head-unit test; green CI alone does not guarantee functional in-car PTT.
