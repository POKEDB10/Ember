# Calls v2: results

What was measured, how, and against which bar. **Setup for every number below:** one Windows 11 laptop (16 cores), local Ember server (`--calls-v2`, plain HTTP on port 8099), Chrome only (the desktop app's built-in browser pane), synthetic media (a canvas test pattern as the camera, an oscillator as the microphone; the pane blocks real devices). Firefox, Safari, real cameras, real microphones and real networks were **not** tested. Anything not measured says so.

## Voice

| What | Bar | Measured | How |
|---|---|---|---|
| Desktop ↔ web codec | 32 kHz wideband when both can | ADPCM 32 kHz both ways, ~130 kbit/s | live call, web tab ↔ desktop app, stats overlay |
| Desktop jitter buffer depth | 40–200 ms target | 40–60 ms (target 40), 0 frames concealed | same call, local network |
| Recovery after a 500 ms stall | delay back to normal within 3 s | back to ≤ 80 ms within 3 s (before: 440 ms silence, then a permanent +140 ms queue) | simulated 500 ms stall on the desktop jitter buffer |
| Web Opus upload, Data saver / Auto / Max | 24 / 32 / 40 kbit/s voice | 44 / 52 / 60 kbit/s on the wire (about 20 kbit/s is packet overhead) | relay, stats overlay, 10 s windows |
| Mode change applied | ≤ 2 s | ≤ 1.5 s | stats overlay timestamps |
| Speaker mode ducking | mic turned down while the other side is heard; off by default | mic level below 20 % of normal while remote audio plays; back to normal after it stops | `testSpeakerMode` (fake devices) |
| Mouth-to-ear latency | — | **not measured** (needs two machines and a microphone) | |
| Real device unplug | — | **not measured** on hardware; fallback to the default device is covered by `testCallSettingsAndDevices` (fake devices) | |

## Video

| What | Bar | Measured | How |
|---|---|---|---|
| Relay step-down after trouble | ≤ 2 s | 0.2 s (then one rung per second to the 180p floor) | in-page test forcing trouble windows |
| First step-up after trouble clears | after ≥ 6 s calm | 13.9 s | same |
| 640×480 camera | never sent above the camera | never above 480 (relay 360p, direct 480) | synthetic 640×480 camera |
| Direct bitrate vs cap | within ±10 % | 1072 / 1071 / 1074 kbit/s vs 1111 cap (−3 %) | three 10 s windows, `getStats` |
| Display resize follows | immediate | immediate, both directions | resizing the tile |
| Pause when hidden | 0 fps while hidden, key frame on resume | 0 fps → 29 fps on resume | hiding the tab |
| Grace period after a rung change (decision 15) | no drop back within 1.5 s | **not re-measured live** after the fix; ladder logic covered by Node tests | |

## Hand-off (desktop → website)

| What | Bar | Measured | How |
|---|---|---|---|
| Link opens nothing by itself | no auto-join | page shows only "JOIN CALL"; `?join=` removed from the address bar | live, Chrome |
| Camera/mic refused | the original call keeps going | original leg still `active`, other side unaffected | live, permission rejection simulated |
| Join → call active on the new device | — | 247 ms (local server) | live, one run |
| After the swap | old device stops; other side re-keys | old leg ended, other side showed a new matching safety code and received video (360p start, 720p on Max) | live; the "old device" was a web tab in relay mode standing in for the desktop |
| Races, reuse, expiry, outsiders, flag off | all refused | all refused | `testCallHandoff` (server) |
| The real desktop app's "Video on website" button | — | **not tested live** (compiles; URL built from the WebSocket address) | |

## Server load

| What | Measured |
|---|---|
| Desktop app CPU, idle / in a call | 0.5 % / 1.0 % (P0 baseline, before calls v2) |
| Render CPU / RAM during a relayed call | **not measured**: needs the Render dashboard (owner access) |
| Throttled network (Chrome DevTools throttling, Clumsy) | **not measured**: DevTools throttling is not available in the pane; Clumsy needs a download the user must approve |

## Tests

`scripts\run-tests.bat`: 46 Java tests and 9 Node tests, all passing (24 Nov 2026).
