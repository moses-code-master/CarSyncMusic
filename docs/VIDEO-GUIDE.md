# CarSync Music & Video — by Brayo

Version 1.5.0 is a review candidate, not a published official release. Installed product name remains CarSync Music. Creator Brayo · +254 740 636 336 · https://github.com/moses-code-master.

## Import and play

Open Videos → Import videos for MP4, MKV, WebM and other device-supported videos. Pick files from internal storage, USB or SD providers. If the system picker is missing, a compatible file-manager picker is tried; FileManager → Open with / Share → CarSync Music is also supported. Scan videos reads MediaStore after the appropriate media permission. USB files must be indexed to appear in a scan; otherwise use the picker.

Picker imports are hashed and copied into private storage, with a 4 GB local-video limit and free-space checks. Scanned videos retain their original URI and must remain accessible. The 100 MB MP3 cloud limit is unchanged. Video files are never uploaded to Render. Metadata includes title, duration, size, dimensions and a best-effort thumbnail. Codec/container support depends on the hardware; H.264/AAC MP4 is recommended for older units. No DRM bypass or transcoding is included.

Search the library, select videos in order, name and save a video playlist. Edit / rename lets you move or remove selected entries; it preserves playlist identity. Music and video playlists are kept separate. Remove record deletes a video from the library and cleans its owned copy; queued videos must be cleared or the session left first. Clear local playback is available in Videos.

Play locally without a session or internet. Open Player, confirm parked, and use Fullscreen for a landscape player. Picture aspect ratio is fitted with letterboxing, never stretched. Play/pause, ±10 seconds, progress seeking and next/previous use the same playback service and timeline as music. Audio only disables the video track while preserving audio and background controls where supported.

## Two screens

Update both devices to 1.5.0 and import byte-identical copies on both. Matching titles are insufficient: encoding, metadata or container changes produce a different SHA-256. Create a host session and join with the same cloud server and code. The host chooses a video or publishes a video playlist; use Play after the clocks are ready. Both players use future deadlines, late-start catch-up, pause/resume/seek, progression and reconnect snapshots. Each screen must confirm parked before showing its own picture.

A missing/mismatched video stays silent and displays an exact-file import instruction. It never substitutes another video or attempts a video relay. Importing the matching file automatically recovers the canonical timeline. Existing music clients retain audio-session compatibility; video sessions require both clients to advertise 1.5 video capability. Older clients receive an update-required message rather than video metadata they cannot interpret. Free Render sessions can sleep or disappear after restart; create a new session when needed.

## Safety and hardware limits

Viewing targets stationary vehicles. Confirmation is required before displaying a picture, is revoked when the activity backgrounds, and cannot override Driving Mode. Audio-only video remains available on generic Android where supported. A manual toggle does not reliably detect motion; generic aftermarket units have no universal parking/speed signal. Never treat the confirmation as automatic movement detection.

On devices declaring Android Automotive OS, an optional android.car integration listens to CarUxRestrictionsManager. Restricted or unavailable vehicle state fails closed for video; video playback pauses on active restrictions. Generic units use explicit parked confirmation. No manufacturer restriction is bypassed. This is not a certified Android Auto/Automotive distribution integration. Physical verification of manufacturer signals and audio routing remains required.

## Data, validation and review

Room schema 1→2 adds media metadata/type columns with audio defaults, preserving existing tracks and playlists. Application ID remains app.carsync.music; signing uses the same private key as 1.1+. Android 6+ remains the minimum.

The proposed PR runs lint, unit/backend tests, clean installs, upgrade checks and emulator integration tests on Android 6/8/15. Tests import synthetic H.264/AAC MP4/MKV videos, decode frames, exercise local/host/guest controls, missing-file recovery, reconnect, playlist progression, migration, safety and branding/music regressions. Emulator timeline/frame checks do not establish sub-100 ms visible or audible alignment on physical head units. Test while parked and measure actual output latency before making any synchronization claim.

No official release may be published without owner approval. The review APK, screenshots, exact run results and known limitations are linked from the PR.
