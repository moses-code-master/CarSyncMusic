# CarSync v1.9 beta — server endpoints and release gates

## Which server should cars use?

The radio and live-map Render deployments are **independent test installations** of the CarSync backend, not linked radio and GPS microservices. Each maintains its own session codes, credentials, playback timeline, audio uploads and last-shared positions. **Do not mix URLs within a convoy.** For the v1.9.0-beta.6 trial, all participating devices should select `https://carsync-music-map-test.onrender.com`. The older `https://carsync-music-radio-test.onrender.com` is a separate v1.9 radio test environment.

The new beta automatically points **inactive, non-customized** clients to the map test endpoint. Existing active sessions retain their original endpoint, and manually configured URLs remain unchanged. Leave a session before switching; an existing radio-server session code will not automatically transfer to the map server.

All public server addresses require HTTPS, and WebSocket sessions use WSS. Plain HTTP remains a deliberate exception for trusted, private LAN relays. Do not use an untrusted server or paste credentials into an endpoint URL. The client rejects endpoint paths, query parameters, fragments and userinfo, and rejects HTTP(S) redirects so bearer credentials cannot follow a redirect. The Android manifest still permits private-LAN cleartext traffic; the application-level server-root validator controls public endpoint selection.

## Automated release evidence

GitHub Actions runs backend tests and npm security audit; Android compilation, unit tests and lint; plus decoder/playback emulator checks on Android API 23, 26 and 35. Recheck **every job after the last commit** and capture a successful run URL. A service marked live in Render only proves that Render deployed a container, not that GPS or car speaker behavior passed physical tests.

## Parked two-device acceptance: audio

1. Install the **same APK** on two parked phones/head units; point both at the exact map beta server URL. Make a fresh host session, join the second device with its code, and load two known-good matching MP3 files.
2. Play music for at least 10 continuous minutes with normal phone output, then Bluetooth call routing OFF. Record any audible scratches, clicks or gaps. Inspect Settings > Music playback quality. Compare unexpected rebuffer and large sync-recovery counters before and after.
3. Repeat with server-shared audio enabled, then with the intercom talking and releasing the floor. Confirm ducking restores gradually and the music does not hard-seek during speech.
4. Interrupt one device's network for 15–30 seconds, reconnect, and verify automatic timeline recovery. Export telemetry. Confirm there is no repeated hard-seek loop or crash.
5. Test Bluetooth call routing only while parked and only with supported hardware. No emulated software test establishes speaker output quality or acoustic synchronization.

## Parked two-device acceptance: GPS and consent

1. Each phone joins the **same map beta** session. On both phones grant location access and separately opt in to convoy GPS sharing; a nonsharing phone stays on the roster without a map pin.
2. Check that both marker positions reflect real nearby locations, show their GPS age, and update while moving outdoors with a passenger operating the app. Do not infer accuracy from map UI alone; compare each device against its own known location.
3. Turn off Location on one phone; confirm its marker expires and the other car remains visible. Re-enable the provider and confirm a fresh fix appears. Check for misleading cached/stale coordinates.
4. Briefly disable mobile data on one phone, reconnect to the same session, and confirm the user's location-sharing consent resumes as designed. Then explicitly tap **Stop sharing my location**, reconnect again and confirm the app never resumes sharing without a fresh opt-in.
5. Test leaving a session, force-stopping the app, denying location permission, and background restrictions. Confirm no location is shared after explicit opt-out or leave. GPS output from head units without a location provider may require a phone.
6. Record device model, Android version, GPS provider/permission mode, server URL, time, last fix age, and results. Check network access to map tiles separately; blank tiles do not necessarily mean missing GPS.

**Release hold:** The pull requests must remain drafts until a full latest-head CI run passes **and** real two-device audio, GPS, privacy and reconnection checks have documented outcomes. Do not merge to `main`, deploy to the live `carsync-music` Render service, or claim physical tests passed before then.
