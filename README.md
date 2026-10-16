# CarSync Music

**by Brayo** · Created and maintained by **Brayo**
Contact: **+254 740 636 336** · [GitHub: moses-code-master](https://github.com/moses-code-master)

[Creator branding and attribution](docs/BRANDING.md) · [1.4.0 release notes](docs/releases/1.4.0.md)

Native Kotlin/Compose Android music app plus a self-hosted Node.js/WebSocket relay. The app plays ordinary MP3 files through each head unit's speakers, without Google Play Services or paid music subscriptions. Requires Android 6.0 or newer, third-party APK support, and network access.

## Your live cloud setup

- Public source: [moses-code-master/CarSyncMusic](https://github.com/moses-code-master/CarSyncMusic)
- APK: [v1.4.0 test release](https://github.com/moses-code-master/CarSyncMusic/releases/tag/v1.4.0)
- Server address prefilled in Settings on BOTH cars: `https://carsync-music.onrender.com`
- [Server health check](https://carsync-music.onrender.com/health)

The cloud backend is deployed on Render's Free plan in Frankfurt, without a paid disk. Your laptop can be off. Both cars need network access to this server. Free hosting can sleep and lose sessions/uploads on restart or redeployment; recreate a session if that happens. Import identical local MP3s for lowest data use. This URL is a backend API, so its root path is not a website page; `/health` reports availability. The APK remains a development/test build, and physical head-unit audio timing still needs validation.

## Start here

1. Install Node.js 24 LTS on a laptop or server.
2. In `backend`, run `npm ci`, then `npm start`. Server listens on port 8080. Keep it running during the trip.
3. Local Wi-Fi: connect both head units and the server computer to the same hotspot; allow inbound TCP 8080 in the computer firewall. In both apps, Settings → enter `http://192.168.x.x:8080` using the server computer's actual address. Hotspot client isolation must be disabled. A server process is required; the Android host does not run the session server itself.
4. Mobile data: deploy the server using the HTTPS instructions below; enter the same HTTPS URL in both apps. This endpoint can also be used while on Wi-Fi, allowing reconnect after switching to mobile data without changing the session.
5. Park. Set car names. Library → Scan phone and allow music permission to discover MP3s already on your device. Tap any song to play. Alternatively use Import MP3; picker imports are copied into app storage and remain available after removing the original drive. Scanned songs stay in their original location and must remain available. Video files and non-MP3 audio are not supported.
6. Host → Create Session. Guest → Join Session, enter six-digit code. Alternatively import a QR image from the host's Create Session page; review and save the suggested server address before joining. External QR readers can open the `carsync://join` link, which fills the Join Session code and suggests the server address for review.
7. Host → Music Library → select tracks in order → Save Playlist or Publish to Session. Wait for clocks to synchronize (about six seconds), then Play. Commands deliberately take about 1.8 seconds to execute so both devices have a future deadline.
8. If both devices imported byte-identical MP3s, only metadata and synchronization traffic cross the network. Different tags/encodings change the SHA-256 hash even if the song sounds identical.
9. Missing song: guest gets an explicit waiting message. Import the identical MP3, or on the host Player tap Share music with guest for audio you have permission to share. The host uploads missing playlist songs automatically after permission is enabled. The guest starts automatically when each song is ready; no manual retry is required. Uploaded files are streamed with HTTP range requests; metadata-only playlist synchronization does not distribute audio.

Local-only playback: open Library and tap a song without joining a session. Volume always affects only the current car. Playlist editing supports rename, remove, add, reorder and shuffle; synchronized repeat supports off/one/all. Shuffle publishes a new canonical order and resets to track one.

## Install on head units

When upgrading from v1.0.0, uninstall the old app first because its signing key differs; this clears its saved library and session. Then install v1.3.1 and scan/import music again. Future GitHub builds use a persistent private test signing key. Copy `CarSyncMusic.apk` to USB or internal storage, open it with the head unit file manager, and allow installation from that file manager when prompted. The published APK uses a non-debuggable release build, signed with the persistent private test key. For public distribution build and sign your own release APK with a private keystore. Do not install while driving.

For USB debugging, `adb install -r CarSyncMusic-debug.apk`. Allow notification permission on Android 13+ to see media controls. Some manufacturers require allowing background activity or disabling battery restrictions for CarSync. Ordinary Android head units are supported; Android Auto projection / certified Android Automotive app integration is outside this project.

## If Android says “App not installed”

- Check Settings → About phone → Android version. Version 1.2 supports Android 6.0 (API 23) and newer. Earlier versions of CarSync required Android 8. Android 5 and older remain unsupported by this build.
- If version 1.0 is installed, uninstall it once before installing the new APK because its signing key differs. This clears the old app library/session; scan or import again afterward. Versions 1.1 and newer share the persistent signing key and can update in place.
- Download the complete `CarSyncMusic.apk` asset from Releases; do not try installing a source ZIP. Allow “Install unknown apps” for the browser or file manager used to open it. Ensure sufficient free storage.
- The APK is universal and includes ARM 32-bit, ARM 64-bit, x86 and x86_64 native libraries. If installation still fails, record the exact Android version and installer message; USB debugging can provide the precise error with `adb install -r CarSyncMusic.apk`.

For Xiaomi/Redmi phones: search Settings for “Install unknown apps”, select the browser or File Manager used to open the APK, and permit installation from that app. Menu locations differ by MIUI/HyperOS version; see [Xiaomi's official instructions](https://www.mi.com/global/support/faq/details/KA-06974/). A Redmi 12 is newer than the app's minimum requirement; “App not installed” on that model does not establish an Android-version problem.

## Importing on aftermarket head units

Some head units lack a working Android document picker even when they report a recent Android version. Version 1.3.1 tries the document picker, falls back to a compatible file manager, and shows help instead of crashing if neither can open.

Use Library → Scan phone to discover MP3s already indexed on internal storage. For USB/SD music, open the head unit's FileManager, select an MP3, and choose Open with / Share → CarSync Music. FileManager must grant read access; inaccessible files are reported without hiding the error. Imported music is copied into app storage. QR image import and report export also handle unavailable pickers.

A generic “CarSync Music has stopped” dialog does not identify the underlying exception. If a device still closes after selecting a particular song, record whether the picker opened and which file was selected; obtain its Android crash log if possible.

## Build an APK

Install Android Studio, JDK 17, SDK Platform 35 and Build Tools 35.0.0. Open `android` in Android Studio. Let Gradle sync. Use Build → Generate APK, or:

Windows: `gradlew.bat assembleDebug testDebugUnitTest`

Linux/macOS: `sh gradlew assembleDebug testDebugUnitTest`

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Set `ANDROID_HOME` to your SDK, or create a local `android/local.properties` containing `sdk.dir=<SDK path>` (use forward slashes on Windows). A machine-specific local.properties is excluded from the source archive.

Pinned project stack: AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20, Compose BOM 2025.04.01, Media3 1.8.0, Room 2.7.2, OkHttp 4.12.0. Stable pinned versions are used for reproducible compilation rather than automatically upgrading to newest releases.

## Internet deployment

The source is available in the public GitHub repository [moses-code-master/CarSyncMusic](https://github.com/moses-code-master/CarSyncMusic), with a GitHub APK-build workflow and Render cloud deployment files. The cloud music server is deployed at `https://carsync-music.onrender.com`. Follow `docs/GITHUB-CLOUD.md` for details. The root `render.yaml` selects the free trial requested by the user, with temporary storage and no paid disk.

Use a Linux machine with Docker and Docker Compose; a VPS may cost money even though the software is free. Point your domain's A/AAAA records at that machine, open inbound 80/443, and set `DOMAIN=music.yourdomain.example` in a project-root `.env`. Run `docker compose up -d --build`. Caddy obtains HTTPS certificates and proxies HTTP and WebSocket traffic to the private server container. No public 8080 port is needed. Check `https://your-domain/health` before entering the URL in both apps. The supplied example domain is a placeholder.

Data is persisted in the Docker volume `sessions`. Back it up if needed. Restrict filesystem access because it contains permitted MP3 uploads and session records. Use a single backend instance; this implementation does not support replicas or load-balanced sessions. Stop with `docker compose down` (keeps data). Session lifetime and telemetry retention are 24 hours. Host can end a session immediately. Six-digit codes are invitations, not passwords; use trusted networks and keep codes private. Server limits join attempts and uses independent 256-bit bearer credentials after joining. The guest slot remains reserved to its token until expiration or host deletion; leaving without credentials requires creating a fresh session.

## What is implemented and what still needs device validation

Implemented: foreground Media3 service/notification; local MP3 import and metadata/artwork; Room playlists; authenticated host/guest sessions; monotonic clock estimation; timestamped play/pause/seek/track commands; duplicate IDs; automatic reconnect and authoritative snapshots; canonical shuffle/repeat; file hashes; explicit missing-file state; opt-in upload and authenticated range streaming; latency calibration; connection/drift UI and CSV telemetry export.

Read `docs/ARCHITECTURE.md`, `docs/TESTING.md`, and `docs/VALIDATION.md` for the protocol, limitations and actual verification results. A compiled APK and server tests do not prove audible sub-100 ms alignment on your head units. Physical testing, especially Bluetooth output delay and network handover, remains required.

## Operational limitations

- Below 100 ms is a target under favorable networks and calibrated outputs, not a certified result. ExoPlayer position estimates, CPU load, decoder startup, asymmetric network routes and speaker/Bluetooth buffering can introduce error. CSV drift is player-timeline drift, not microphone-measured audible drift.
- Each control has 1.8 s lead time. A new track is staged paused in the single player to prebuffer; this causes a preparation gap and is not gapless playback. End-of-track transitions have a scheduled gap. Slow or missing downloads catch up by seeking after buffering rather than pretending they started on time.
- Local-to-internet handover uses the SAME reachable remote server before and after Wi-Fi disconnect. A private LAN-only server cannot be reached after leaving hotspot range; the app cannot migrate that session to a different server automatically. Public HTTPS is recommended for mixed-range trips.
- The Android host uploads the entire permitted file before guest streaming. This is a store-and-stream relay, not a continuous uplink from the host's decoder. Uploads use data and relay disk space; 100 MB per file and 2 GB total relay disk quota. Already shared tracks can keep playing after host loss. Guest relay playback needs connectivity; there is no persistent guest download/cache UI.
- Driving mode is manual because generic aftermarket Android exposes no reliable vehicle-speed API. It hides imports, playlist editing, session setup and seeking. Basic controls remain large. It cannot automatically detect movement or enforce driver/passenger roles.
- QR joining supports QR image decoding with ZXing and code entry; it has no integrated live-camera scanner. Guest album art is shown only when a matching local file contains artwork; artwork is not uploaded separately.
- Android may stop a paused service, and manufacturer power policies may terminate background apps. Force-stop silences that car; reopening restores session credentials and catches up. Server restart restores sessions paused to avoid an invalid time anchor. Internet disconnection keeps buffered/local audio playing and temporarily disables shared controls.
- Sessions are limited to two cars and expire after 24 hours. No account system, public music catalog, DRM bypass, streaming subscription integration or production fleet scaling is included.

## Source references

Android background-playback architecture follows [MediaSessionService guidance](https://developer.android.com/media/media3/session/background-playback). Compose dependency alignment follows the [official BOM guide](https://developer.android.com/develop/ui/compose/bom). Build compatibility: [AGP 8.9 release notes](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
