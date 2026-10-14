# Architecture and data design

## Components

`MainActivity` provides eight Compose pages with large controls and manual driving restrictions. `LibraryDb` persists local files and playlists. `PlaybackService` owns one Media3 ExoPlayer and a MediaSession; notification/headset controls route through the synchronization coordinator instead of directly mutating shared playback. The service uses audio focus and pauses on noisy-output events. App playback continues with the activity minimized while its media service is active.

`SyncCoordinator` owns WebSocket credentials, reconnect, clock samples, staged commands, local file matching, telemetry, HTTP audio access, and UI state. Clock math is isolated in `SyncMath.kt`. Files are opened using Storage Access Framework URIs and persistable read grants; no broad storage permission or Play Services is needed.

The backend owns the canonical playlist and playback timeline. It is the common rendezvous in BOTH networking modes. It accepts at most two token-authenticated cars and schedules all commands against one server time domain. A scheduled frame is persisted before broadcasting. Host identity remains stable across reconnect; disconnect does not promote the guest. Host can delegate playback controls, but never file upload or session administration.

## Time protocol

Client time is `SystemClock.elapsedRealtime()`; server time is a Date.now epoch anchored once to `performance.now()` to avoid wall-clock jumps. Ping carries client send time t0. Pong carries server receive t1 and send t2. Client records receive t3.

offset = ((t1 - t0) + (t2 - t3)) / 2

RTT = (t3 - t0) - (t2 - t1)

Keep 20 samples, reject RTT > 3 seconds and invalid samples, and use the median offset among the five best RTT samples. Require three samples after each connect. Sample every 2 seconds. Asymmetric routes still bias offset; RTT is not an accuracy guarantee.

A frame has `revision`, `index`, `positionMs`, `playing`, and `executeAt`. The server schedules each accepted action 1800 ms in the future. It broadcasts the previous effective frame plus the new frame. Future commands supersede pending commands; the coordinator cancels its previous scheduled task. Duplicate command IDs are retained (512/session) and acknowledged without replaying the mutation. Commands submitted while offline are rejected rather than replayed much later. The client does not automatically resend uncertain controls.

Target position after deadline = anchor position + elapsed server time if playing. Pause anchors the position at its future deadline. Seek and track selection share the same deadline on both cars. Late joins/reconnects project the current anchor forward instead of starting from zero. A new track is prepared paused before its deadline, so prior audio may stop early; this implementation prioritizes readiness over gapless output. Seek on a running same-track player happens at the deadline and then catches up if decoding stalls.

The coordinator polls every 250 ms. Above 300 ms error it seeks, throttled to at most once per 1.5 seconds. Between 35 and 300 ms error it adjusts speed within 0.98–1.02. Below 35 ms it restores normal speed. Clock estimates and drift correction suspend while disconnected; local/buffered audio continues. On network regain the socket retries with jitter and exponential delay up to about 32 seconds, authenticates with the same token, acquires clock samples, then reapplies the authoritative state. A server heartbeat detects dead sockets in roughly 15–30 seconds.

Configured output latency advances the local execution deadline and target position to compensate for that device's downstream audio delay. It must be calibrated acoustically on the actual output path. There is no sample-clock-locked renderer; sub-100 ms audible synchronization cannot be established solely from these estimates.

## Local database (Room)

`tracks`: hash TEXT primary key; uri TEXT; title TEXT; artist TEXT; album TEXT; durationMs INTEGER; artPath nullable TEXT. SHA-256 is computed from file bytes off the main thread. Embedded cover art is stored privately. URI is not broadcast.

`playlists`: id INTEGER auto primary key; name TEXT; hashes TEXT (ordered comma-delimited SHA-256 values; hashes cannot contain commas). Renaming and editing update the same row. The simple list representation preserves duplicates/order; missing local references are displayed during editing.

Session endpoint, name, token, code, role, driving mode and output latency are app-private SharedPreferences. Backup is disabled to avoid migrating a car's token to another installation. Normal app reopening restores the server-owned last track/position. Local-only playback position is not persisted across process death.

## Backend database (SQLite/WAL)

`sessions(code TEXT PRIMARY KEY, body TEXT NOT NULL)`: JSON record includes members (random stable ID, token SHA-256, name, role), playlist, frame, previous, flags, expiry, and dedup IDs. Raw bearer tokens are returned once and never stored server-side. This compact document schema keeps per-session mutations atomic in a single Node event loop.

`telemetry(code, member, at, event, drift, rtt, buffered, missing)`, indexed by code/at: online/offline transitions and status samples. Host-only CSV export at `/sessions/{code}/metrics`, exposed in Android Settings. Automatic retention 24 h. Telemetry contains measured player drift and readiness, not acoustic observations. Users can compare online/offline events with first stable status after recovery.

Media files use `{code}-{sha256}.mp3` names under the private data directory. Uploads are temporary until the server verifies SHA-256. Streaming requires a member bearer header and supports byte ranges for ExoPlayer seeking. No unauthenticated media URLs or credential query strings are used. Redirects are disabled on the Android audio client to prevent forwarding a bearer to another origin. Turning off sharing deletes relay files and tells the guest to stop remote playback. The server never decides ownership; the host explicitly attests sharing permission.

## HTTP / WebSocket contracts

- POST `/sessions` {name} → {code, token, memberId, role}
- POST `/sessions/{code}/join` {name} → independent guest credentials
- GET `/sessions/{code}` + bearer → snapshot
- DELETE `/sessions/{code}` + host bearer → delete session/media
- PUT `/sessions/{code}/audio/{hash}` + host bearer, known Content-Length → permission-gated, hash-verified upload
- GET/HEAD same media path + member bearer → MP3/range
- GET `/sessions/{code}/metrics` + host bearer → CSV
- WS `/ws`: authenticate first within 5 seconds: {type:"auth",code,token}; then ping, snapshot, status, or mutation messages with UUID `id`.
- Mutations: {type:"playlist",id,playlist:[{hash,title,artist,album,durationMs}]}; {type:"control",id,action:{kind:"play|pause|next|previous|select|seek",index?,positionMs?}}; {type:"settings",id,guestControls,sharing,repeat:"off|one|all"}.
- Server messages: state, cars, pong, ack, error. Periodic car status uses compact `cars` messages without resending the playlist; full state is sent on connect and canonical state changes. Revisions are monotonic within each persisted session. Periodic server end detection advances tracks even when the host is offline. After server restart the recovered frame is paused and revised.

## Trust and deployment boundary

TLS terminates at Caddy. Private LAN HTTP is allowed by the Android app for hotspot use, and is vulnerable to trusted-LAN interception; public endpoints require HTTPS in the UI. Six-digit invitation codes are throttled per connecting IP, sessions hold two cars, and subsequent access uses high-entropy tokens. Limits are in-process and single-node; a production multi-tenant deployment would need stronger enrollment, durable per-user quotas, gateway abuse protection, authorization review and operational monitoring. No cloud account is provisioned or deployed by this source delivery.
