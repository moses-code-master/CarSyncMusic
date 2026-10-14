# Test plan for two actual Android devices

## Automated checks

Backend: `cd backend`, `npm ci`, `npm test`. Tests open real HTTP/WebSocket connections to ephemeral-port servers and temporary SQLite databases. They verify scheduled timing, both clients' identical frames, pause/seek/skip, duplicate protection, guest permissions, reconnect snapshots, MP3 relay integrity/ranges, missing tracks and two-car capacity. The relay fixture tests transfer integrity; it is not a playable MP3 or audio test.

Android: in `android`, run `gradlew testDebugUnitTest assembleDebug`. JVM tests cover clock offsets, rejection of delay outliers, pause/play projection and bounded drift correction. Build success is a compilation check, not a hardware media test.

## Setup and measurements

Use two parked head units or Android phones, preferably with wired speakers. Configure the same server and import the two original MP3 pulse tracks included in `test-audio` (or another recording you may share). Start at low volume. Set local latency to zero initially. Place both outputs within reach of a common recorder; one stereo recording with one speaker per channel avoids comparing unsynchronized phone-recorder clocks. Never operate testing controls while driving; a passenger can observe handover while the driver drives normally.

Wait until clocks are ready. Record at 48 kHz. Measure matching transient separation in samples, then divide by 48 to get milliseconds. Adjust the slower car's output latency compensation upward, repeat, and calculate median/p95/max absolute acoustic difference over at least 30 transients. Repeat after changing speaker/Bluetooth connections. Do not substitute UI drift for this result.

Host Settings → Export synchronization telemetry saves a CSV with each car's player drift, RTT, buffering/missing flags and online/offline events. Calculate recovery from reconnection to the first stable, audio-ready drift samples. Offline events may lag the actual radio loss by heartbeat detection. Report manual radio-loss time separately. Keep an observation sheet with device model, Android version, output path, network, calibration values, and exact intervention times.

## Required scenarios

| Scenario | Procedure | Acceptance / observations |
|---|---|---|
| Simultaneous start | Publish two matching tracks, wait for clock readiness, Play | Both use same future deadline; measure acoustic difference. Target p95 <100 ms only under favorable conditions. |
| Host pause | Pause mid-track | Both stop near scheduled deadline; positions remain consistent. Commands have ~1.8 s lead. |
| Host skip/seek | Next, Previous, seek while parked | Same selected hash/order and requested position; record buffering gap and settling time. |
| Guest connection loss | Disable guest network for 15–30 s, then restore | Local MP3 continues; offline controls disabled; session auto-reconnects with same identity and catches up. Measure stable recovery time. |
| Wi-Fi to mobile data | Start using public HTTPS over Wi-Fi, leave its coverage with data enabled | Same server reachable; reconnect and settle. LAN-only endpoint intentionally cannot work outside LAN. |
| Slower processor | Use older head unit / CPU-throttled emulator, cold MP3 decode | Buffering is visible; no false sync claim; late start catches up after readiness. Measure settling and drift. |
| Identical MP3s | Import exact copies with matching SHA-256 | Relay uploads unnecessary; network usage should be metadata/status only. |
| Guest lacks song | Remove/mismatch guest file before joining | Clear missing-song warning; guest does not play wrong track. Host opts in and uploads, then guest retries audio; measure data and buffering. |
| Host closes unexpectedly | Force-stop host while guest plays | Host silent; guest continues local/shared uploaded audio; server advances playlist; host reopening restores credentials and catches up. Also test server restart: session recovers paused. |
| Late join | Host plays for 20 seconds, then guest joins | Clock sampling first, then projected position; no restart from zero; record convergence. |

Additional checks: background activity minimized for 30 minutes; screen sleep; notification and Bluetooth headset play/pause routing; revoked USB/SD access; audio focus interruption; permission revocation; repeat one/all; duplicate local hashes; orientation/phone/1024×600 layouts; expired/full session; incorrect code; sharing off mid-stream; Android notification permission denied; manufacturer battery restrictions.

## Results template

Copy `measurement-template.csv` per test run. Fill hardware/acoustic results only after measuring them. Network control tests and compiled code cannot validate real head-unit output latency. The project intentionally includes no invented physical accuracy measurements.
