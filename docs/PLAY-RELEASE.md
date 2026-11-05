# CarSync Music 1.9.3 — release foundations

This candidate adds Android 16/API 36 targeting, audio-only Android Auto media browsing/search, optional private crash diagnostics, a privacy screen, explicit location-sharing disclosure, larger driving controls, and relay-authoritative host handover. It is built on 1.9.2; the earlier 1.9.1 convoy/radio/maps/calibration and upload-recovery work remain present.

## Build outputs and signing

Run from `android` with JDK 17, Android SDK 36 and the project Gradle wrapper:

```
gradlew assembleInternalRelease testInternalDebugUnitTest lintInternalRelease
```

The `internal` APK retains the existing test signing identity for in-place upgrades from the supplied 1.9.1/1.9.2 APKs. Release builds enable R8 and resource shrinking. Save the matching `outputs/mapping/<variant>/mapping.txt` for interpreting obfuscated stack frames.

The Play variant has a separate signing configuration. Supply `CARSYNC_UPLOAD_KEYSTORE`, `CARSYNC_UPLOAD_STORE_PASSWORD`, and optionally `CARSYNC_UPLOAD_KEY_ALIAS` (default `carsync-upload`) and `CARSYNC_UPLOAD_KEY_PASSWORD`. Supply secrets through a protected environment or credential manager; never commit them. Then:

```
gradlew bundlePlayRelease assemblePlayRelease lintPlayRelease
```

`app-play-release.aab` is the Play upload candidate. A bundle build fails if the private upload signing configuration is missing; it does not fall back to the test certificate. `app-play-release.apk` is for separately signed release QA, not an in-place replacement for the internal test APK.

The supplied upload AAB uses `scripts/Normalize-PlayBundle.ps1` to place JAR manifest/signature entries first for stream verification. The script verifies that every uncompressed entry payload is identical to the Gradle-signed original. This preserves the upload signature; it does not apply to APK files. Revalidate the output with bundletool and jarsigner after packaging.

The production upload key and the old test key differ. Enroll in Play App Signing and decide the app-signing/package migration strategy before public publication. Do not tell existing test users to uninstall without a data migration/backup plan. The same package name does not bypass Android's signing-key upgrade checks. Back up the private upload key and password securely; they are intentionally excluded from source/download packages.

## Android Auto scope

`PlaybackService` is a MediaLibraryService with an audio-only catalogue, bounded pagination, search, media-search intents and playback resumption. Browser requests resolve known catalogue IDs; caller-supplied file paths/URLs cannot become playback sources. Media3 trust checks guard external controllers. The car display receives no video catalogue, permission prompts, session setup or intercom controls. When a trusted car display connects, video is blocked and driving mode stays enabled until the display is disconnected and the user exits driving mode.

Car playback is local-library audio outside a session and approved queued audio inside a session. Guest playback authority continues to follow the host's settings. Android Auto integration is implemented but has not yet passed Desktop Head Unit/vehicle hardware validation or Google automotive review.

## Host handover scope

An updated host opts into handover during authenticated connection. After it remains disconnected for 45 seconds, the relay selects the first connected capable guest in join order. Video sessions require a video-capable successor. A shorter dropout/reconnection retains the host. Older hosts/guests are not silently opted into migration. The playlist, current timeline and participant credentials are preserved, and the former host reconnects as a guest. A new host's role is persisted across relay process restarts.

This transfers control on the same relay. It cannot migrate a failed/unreachable relay, elect peers without a server, or copy media still only stored on the departed phone. Local Wi-Fi listening still needs a running reachable relay; it is not an embedded phone-only server.

## Privacy and diagnostics

Settings includes the complete offline privacy notice and optional local crash diagnostics. Diagnostic capture is off by default, bounded, strips exception messages/causes/file paths, and never uploads automatically. Users can clear or explicitly export the latest report. No advertising, payment or cloud analytics SDK was added.

Before location starts, a prominent disclosure explains recipients, fields, minimized-app behavior, the notification and how to stop. Android permission is requested after consent. The same policy text is served publicly at `/privacy` and `/privacy.txt` by the accompanying backend. That public URL is only available after backend deployment; a local file alone is insufficient for a Play store listing.

### Play Console data-safety worksheet (review against deployment)

| Data flow | Implementation | Console review needed |
|---|---|---|
| Chosen car name, session/member identifiers | Sent to selected relay and session participants | User-generated text and identifier categories; app functionality |
| Shared audio/video and metadata | Opt-in session sharing; relay stores approved media | Audio/video/content categories; recipient sharing and user-directed transfer exceptions |
| Precise/approximate GPS, bearing, speed, accuracy | Explicit opt-in; recent fixes in relay memory, not session DB | Location collection/sharing and foreground/background behavior |
| Push-to-talk audio | In-memory relay to participants; no bundled-server recording | Voice/audio processing, ephemeral handling and microphone declaration |
| Playback RTT/drift/buffering and member IDs | Relay telemetry; approximately 24-hour retention | Diagnostics and identifier categories |
| IP addresses and infrastructure logs | IP is visible to relay/hosting and map provider | Verify actual hosting/map-provider logging and retention |
| Map tile regions | OpenStreetMap requests for displayed area | External provider and location inference |
| Local crash report | Optional on-device storage and explicit export only | Do not describe automatic cloud crash collection |
| Optional system speech recognition | User-selected installed provider, network provider opt-in | Provider-specific processing; do not promise all recognition stays local |

Do not automatically mark every transfer as encrypted: public relay URLs require TLS, but trusted LAN HTTP is supported. The policy documents the absence of end-to-end encryption. Do not claim telemetry is anonymous when it is associated with a session member. Review the final SDK list and actual hosting arrangements before completing legal declarations.

Provide the final public support email and developer identity in Play Console, select the actual audience and content rating, complete the Data safety form, and provide foreground-service declarations/evidence for mediaPlayback, microphone and location. Account verification/testing requirements must be completed in the actual developer account.

## Verification before publication

- Run backend tests and Android internal unit/lint/build checks.
- Validate the signed AAB with bundletool and retain R8 mapping files.
- Check native ELF alignment with `node scripts/check-native-pages.mjs <merged-native-libs>` and packaged APK alignment with Android `zipalign -c -P 16 4 <apk>`.
- Compile/run `CarBrowserRuntimeTest` and the existing playback/video integration suite. CI is configured for API 23/26/35/36; this does not mean those new CI runs have already executed.
- Test the minified release on phones, landscape head units and Android Auto DHU/car hardware: browse/search/play/pause/seek/next/previous, guest permissions, reconnect and handover, denied permissions, video blocking and speech search.
- Verify no location is transmitted before disclosure/permission, foreground notifications remain accurate, opt-out stops sharing, and diagnostic export contains no private messages/tokens.
- After explicitly authorized deployment, verify the public privacy URL and backend capabilities, exercise two phones, then complete Play's internal/closed testing and review process. No source change can guarantee store approval.

Official references checked November 5, 2026:

- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/training/cars/media
- https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession.Callback
- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/guide/practices/page-sizes
- https://support.google.com/googleplay/android-developer/answer/9859455
