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


## v1.8 beta 1 — cloud-first convoy reliability

This branch is **Phase 1 development**, not a production release and not an implementation of the complete v2.0 roadmap.

- The Home dashboard reports the user's convoy/session status, currently online cars, whether the saved server is cloud or a local Wi-Fi relay, and measured network round-trip delay. This helps diagnose slow synchronization. It does **not** provide a live route map.
- Settings labels "Find local Wi-Fi relay" as a tool for finding a *running laptop/Node relay on the same Wi-Fi*, **not nearby cars**. Search lasts 10 seconds and shows a useful explanation if no relay is found. The public Render endpoint works across distances over mobile data without local discovery.
- Android WebSocket handling now responds to both closing and closed callbacks, displays retry status, stops private-location and microphone services on network loss, and attempts exponential reconnect. Music already on a device may continue while the data connection is interrupted; live coordination and the intercom require the network.
- An intercom request now has an explicit pending state and 6.5-second timeout. Timeouts, denial, server error or disconnect release the microphone foreground service so a user can retry. The backend confirms an existing user's floor when they retry and reports rate-limit denial as a `ptt_denied` event instead of a generic error.
- Updated the emulator regression assertion to the new actionable missing-video guidance, which changed when authenticated MP4 relay was introduced.

### Physical acceptance tests before publishing

1. Test on two *different* Android phones or head units (Android 6–15), both using the **same** current beta client and matching staging backend.
2. Join a cloud session over two different mobile data providers; test guest-to-host and host-to-guest talk, audio routing, permission denial, turn taking and silence for longer than eight seconds.
3. Disconnect mobile data mid-song and mid-PTT, restore coverage, confirm session reconnects, microphone foreground notification closes on loss, and the shared playlist/time position recovers.
4. Test Bluetooth and head-unit speaker delays separately (app clock sync does not guarantee acoustic sync).
5. Test "Find local Wi-Fi relay" with **no** relay (must report none) and separately with Node backend advertising `CARSYNC_LAN_DISCOVERY=1` on a trusted LAN.
6. Confirm MP4 picture remains blocked while driving. Test playback of locally cached audio during weak coverage.
7. Load-test the target convoy size; server currently defaults to one host plus ten guests. No claim of successful 20–50-car operation without load testing.

Do not merge or deploy this branch until required GitHub checks and physical tests pass; maintain the live production server separately.


## v1.8 beta 2 — reference convoy dashboard and opt-in live map

The **Home** screen is now a working version of the supplied CarSync Convoy mockup: live session/role header, three statistic cards (online cars, farthest GPS separation, live PTT status), compact Now Playing progress, one large green Push to Talk / Stop Talking surface, and shortcuts for Live Map / Playlist / Cars. The dashboard shows actual current data, **never mock values**. If distance is unknown, it displays a dash until both this car and another online car voluntarily provide fresh, accurate GPS fixes. Distances are straight-line estimates, not road distances.

**Live Map** is an interactive OpenStreetMap view showing only online convoy cars with valid recent GPS fixes. Each vehicle must independently opt in to sharing; the map has explicit Start/Stop sharing controls and a link to the existing detailed Cars view. Map tiles require an internet connection; the OpenStreetMap tile provider may see the map area requested and a network IP, so do not assume tile requests are location-private. The map uses public community tiles for low-volume beta tests; replace the tile source with a properly contracted/provider-approved production tile service before scaling. Do not bulk download tiles. Attribution is visible on the map page. The app blocks interactive map use while driving or vehicle restrictions apply.

Intercom button labels now follow the real floor state: Start/Stop/Cancel/Listening. Host and guest operate on the same authenticated voice floor. Microphone permission must be granted before transmitting; receiving requires no push-to-talk action. A large on-screen button is not a substitute for hands-free controls; do not use it while driving.

This version is based on the previous passing v1.8.0-beta.1 development branch. **New CI checks are required** before phone testing. Keep production unchanged and keep this branch in draft until emulator testing and physical two-device verification pass.


## v1.8.0-beta.3 — full convoy member roster and distance comparison

The Live Map now displays a visible, labeled map marker for **every online member with a fresh, accurate, voluntarily shared GPS position**, rather than showing unlabeled pins. The view auto-fits all sharing cars when the set changes, while preserving a passenger's manual pan/zoom during ordinary location updates. A manual **Fit all cars** control resets the view.

**Every registered convoy member** appears in a list below the map, including offline members and members without usable GPS. These entries show "Offline," "Location not shared or unavailable," or "GPS stale or inaccurate" instead of fabricating coordinates. Each car with an acceptable GPS fix shows its coordinates, accuracy, age, and straight-line separation from the selected reference car. **Tap another live car** (map label or roster entry) to recalculate distance from it to all other sharing cars. With 15 or fewer located cars, thin lines illustrate these straight-line separations; they are **not road routes or driving times**.

The server already sends the GPS timestamp `location.at` and a corresponding `serverNow`. Android now accounts for the age of the fix using those server timestamps, instead of resetting the age to zero on every broadcast. The location provider now requests fixes every four seconds with zero minimum movement distance, because the previous five-meter movement threshold made stationary cars disappear when their server location timed out. Turning off GPS sharing or disabling the provider revokes shared location data immediately.

Privacy: **no app can show the current location of a participant who has not opted into GPS sharing or whose GPS fix is unavailable**. Never advertise unavailable locations as tracked. Public OpenStreetMap tiles still require external internet tile requests, and the beta service is not yet approved for production convoy workloads. Location accuracy threshold is 250 meters and client location freshness threshold is 15 seconds; the backend removes positions after 10 seconds. No public API for historical route tracking or offline tracking has been added.

Acceptance: enable consented GPS on two separate phones and verify both map markers and the real distance label; tap each car to compare separations; test a stopped car for at least a minute; revoke one device's GPS and verify its marker disappears but its roster entry remains; disable cellular data and verify stale location disappears; test three or more cars to confirm Fit all cars includes every usable fix.


## v1.8.0-beta.4 — Automatic acoustic profiling and user-triggered voice commands

**Automatic acoustic check:** Settings → "Automatic acoustic check · 3 tones (parked)" runs three automatic chirp speaker-to-microphone measurements (requires `RECORD_AUDIO`). It calculates and saves the median delay and variation; stable results must have three valid observations and 80 ms or less range. The app warns if an acoustic route appears to have changed by more than 100 ms since the previous reliable measurement. Calibration is blocked during driving, playback, active/pending intercom, and automotive devices without confirmed parked status. It does **not** secretly change the existing `latencyMs` sync compensation, because a phone's microphone/ADC delay is part of this measurement and absolute cross-car acoustic alignment cannot be inferred from it. Different devices and Bluetooth paths still need guided cross-device measurement before fully automatic cross-car synchronization is safe to enable.

**Voice commands:** Home → **Voice** (and Settings → Voice commands) opens an explicit, one-shot speech-recognition dialog. Each request requires user confirmation that the car is parked and microphone permission. If Android 12+ provides an on-device recognizer, CarSync uses it; otherwise the user must **separately opt in** to the installed Android speech service, which may transmit audio over the internet. This choice is not silently enabled. No cloud voice recordings are stored by CarSync. No permanent wake word, background recording, open microphone, or automatic remote listening is implemented.

Recognized English commands include "pause music", "resume music", "next track", "previous track", "talk to convoy", "show map", "show cars", "now playing", and "calibrate audio". The grammar rejects unknown and partial phrases. Playback commands honor guest music-control permissions. Intercom starts only when the authenticated floor is available; recognition ends before microphone capture starts. To stop an active microphone transmission, use the large **Stop Talking** button; concurrent speech recognition and PTT audio capture are blocked. Route/navigation commands never unlock complex UI while driving.

**Testing:** Verify 3-tone behavior with speaker and microphone on a parked device, with silent/noisy environments, changed Bluetooth speaker routing, and denial of microphone permission. Test English recognition on a device with and without on-device SpeechRecognizer support and verify that system-provider network consent is opt-in. Verify guest cannot trigger host-only playback; start-talk requests must respect floor locking and fail visibly when unavailable. Green CI does not prove real speaker calibration or speech-recognition capability on a given aftermarket head unit. Production release and test server remain unchanged on this draft branch.


## v1.8 beta 5 — Android speech language error 12 fix

The earlier speech UI could report "On-device speech recognition available" but fail with Android error `12`: **requested recognition language is not supported by that speech engine**. The previous client passed generic `en` and assumed recognition would work whenever the device advertised an on-device engine. Availability of an on-device engine does not mean an English pack is installed.

The one-shot parked Voice dialog now defaults to an explicit `en-US` locale and lets the user cycle to `en-GB` or **Device default** (does not force `RecognizerIntent.EXTRA_LANGUAGE`). Error 12 and error 13 (language supported but not downloaded/available) display actionable language/model advice. The dialog always offers an **opt-in** Android system-recognizer alternative, even if an on-device recognizer exists; this provider may transmit speech data online. CarSync never switches to a potentially cloud-backed speech provider without the user's opt-in. No always-on wake word or background voice recording was introduced.

Device steps: park, open Home → Voice, confirm parked, choose English (US) then Listen once. On code 12, try English (UK) or Device default. Check Android's speech-recognition language/model downloads. With explicit consent, select the Android system speech provider. If the phone lacks any suitable English speech-recognition engine, the app cannot supply one; installation of supported language packs or a provider may be required. New pure unit tests cover error codes and choices. Real OEM/head-unit testing is still mandatory.


## CarSync 1.9 beta 1 — Professional Convoy Radio foundation

This beta develops the **existing** CarSync one-speaker-at-a-time internet radio. It does not claim walkie-talkie reliability in weak or absent cellular coverage.

- **Compact radio transport:** Android produces 20 ms, 16 kHz mono PCM16 frames, optionally encoded using G.711 μ-law before sending. The 320-byte G.711 frame is half the 640-byte PCM16 frame. Android and the server negotiate `convoy_ptt_mulaw_v1`; servers without the new capability continue to use the earlier `ptt_pcm` protocol. On the new server, each receiving client declares support for `mulaw16` during WebSocket authentication. The backend converts **once per codec**, then fans out the correct version: older members receive PCM16, newer members receive G.711. Do not advertise this as **Opus** (still a separate roadmap item); G.711 compresses less efficiently.
- **Head-unit voice processing:** The app attempts Android's `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl` attached to its active voice AudioRecord session. Each DSP effect is optional; unsupported devices continue without it. Input attempts Android's voice-communication source before the standard microphone. Output uses the selected Android media-speaker path rather than routing intentionally to the phone earpiece.
- **Smooth music volume reduction:** Music now fades toward 22% of the original volume over 220 ms whenever someone speaks, and smoothly returns afterwards. Playback timing is not paused or adjusted by ducking.
- **Latency robustness:** The client limits buffered live voice frames and the backend drops voice frames for recipients whose WebSocket buffers exceed 128 KiB, avoiding a seconds-long spoken backlog when mobile data is poor. Frame size is checked before broadcast; a compressed packet cannot be injected by a client that did not negotiate the codec.
- **Diagnostics:** Home indicates compressed/standard radio mode; Settings shows codec and whether the current Android microphone offers optional echo and noise suppression.
- **Compatibility:** Older apps keep their PCM16 audio path on this new backend; the new app uses PCM16 on older backends. Both must remain connected to the *same* convoy server. All voice traffic is scoped to authenticated session members. No permanent microphone recording or storage of raw PTT data was added.

Release gate: CI tests (codec quality, mixed-version fanout, Android unit/emulator checks) must pass before APK distribution. Real-head-unit acceptance testing is required with different Bluetooth speakers, an unplugged microphone, repeated PTT handoffs, music duck/unduck, and a network interruption. The current beta does **not** include Opus, persistent offline two-way radio, automatic channel selection, guaranteed Bluetooth SCO microphone routing, safety-critical emergency messaging, or performance-proven 50-car voice broadcast. Do not merge to `main` or deploy production without those constraints being understood.


## v1.9.0-beta.3 — optional Bluetooth voice routing and 10-car integration test

- Settings → Convoy radio → **Prefer Bluetooth call microphone** is an opt-in choice; it defaults OFF and can only be changed when parked and while the radio is idle. Android 12+ requests `BLUETOOTH_CONNECT` ("Nearby devices"). Android 6–11 uses the legacy SCO headset API. When PTT begins, the app attempts Android's Bluetooth **communication/call** route and explicitly prefers a connected Bluetooth call microphone for AudioRecord. When PTT ends or the connection drops, it clears the route and restores the prior audio mode. An ordinary Bluetooth music-only A2DP connection is not guaranteed to offer a call microphone; missing input keeps the default Android route. This may temporarily reduce audio fidelity because SCO call audio has narrower bandwidth than A2DP music. Device support, connected-headset state and routing vary; do not promise support for every car stereo.
- The server now has a **ten-car simulated integration test**: one host plus nine guests use genuine, authenticated WebSocket connections to the in-process server. It verifies the server's session fanout, frame codec negotiation, 15 voice frames at roughly 20 ms pacing, and handoff between two different speakers. It reports elapsed time in the CI logs, but local loopback measurements **are not** end-to-end vehicle-to-vehicle network or speaker latency benchmarks. Real carrier-network testing, latency/drift sampling, varying radio coverage and larger-convoy load testing remain required.
- This branch uses the existing separate v1.9 radio test server; older production and earlier beta servers are unchanged. The new app works with an older PCM16 backend but compact voice needs a backend advertising the G.711 capability.
- Before public release, check Bluetooth microphone permission denial, headphones versus car head unit, disconnected SCO devices, host/guest talk both directions, voice route recovery after disconnect, mixed Bluetooth/non-Bluetooth cars and continued music playback. Measure jitter/latency and ensure there is no stuck microphone foreground service. Test only when parked, never use the touchscreen while driving. Do not imply Opus, local offline intercom or guaranteed 50-car capacity.


## v1.9.0-beta.4 — internet-first setup clarification

CarSync uses **one internet connection per car** (cellular data or internet-connected Wi-Fi) for distant convoy music, talk and location. Bluetooth **cannot** carry the session between cars kilometers apart: it is only an optional local connection to a car's call-capable microphone/speakers. The toggle is OFF for first-time installs and stays OFF until the member explicitly opts in while parked; toggling it does not affect the internet server. Existing users' explicit setting is preserved when upgrading. Head units without a suitable Bluetooth call microphone continue using Android's normal input/output devices. Settings now labels each responsibility plainly, and unit tests verify that internet server defaults never require Bluetooth.
