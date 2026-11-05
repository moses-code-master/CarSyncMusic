# CarSync iPhone Home Screen preview

This browser client is served by the same CarSync server as Android. It is not a signed native iOS app. Public publishing is pending; localhost is only a development preview and is not an installation address for a remote iPhone.

## Run or deploy

Use Node 24 or later. In `backend`, run `npm ci`, `npm test`, then `npm start`. The server serves the browser app at `/`, API at `/sessions`, websocket at `/ws`, and privacy policy at `/privacy`. Use the repository's Dockerfile/Render setup for HTTPS production hosting. The Docker image must copy `public` as well as `src`.

After publishing, open the verified HTTPS app URL in iPhone Safari, select Share > Add to Home Screen > Add. Launch CarSync from that icon, join a session using the Android host's six-digit code and tap Enable audio. Both devices must use this same server. Create a session/import MP3 to host from the browser instead.

## Verification

43 backend and web tests passed on Windows. A local desktop browser created a convoy, imported and uploaded the included 660 Hz test MP3, and reported active playback with advancing currentTime. Actual iPhone Safari, Add to Home Screen installation, screen-locked behavior, Bluetooth speakers and acoustic sync remain untested.

## Limits and privacy

Audio-only preview; no intercom, GPS, CarPlay or guest music import. Keep the app visible for the first parked test. Safari may suspend it while locked or backgrounded. Use Enable audio again if Safari blocks playback. This is not verified driving software.

MP3 imports are capped at 100 MB. Uploads retry/resume from server offsets, but after termination the user must select the same file again. Downloads buffer the whole song, verify SHA256, and save it in IndexedDB. Cache has manual clearing but no automatic size limit; iOS may evict it. Service worker caches only public UI files, never session API responses or auth headers. Session credentials stay in localStorage until Leave session; they are not protected by native Keychain in this browser version. No third-party scripts, analytics, microphone or location permission requests are included.

Publishing a new Docker version can restart the relay. The existing free Render plan has temporary storage; session/media persistence across deploys is not guaranteed. Do not deploy during an active convoy. No paid storage or account purchase is authorized or configured by this source.
