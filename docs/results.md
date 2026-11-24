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
| Grace period after a rung change (decision 15) | no drop back within 1.5 s | no drop within 1.5 s of any of the 8 step-ups seen in the throttled runs (the closest was 2 s later, caused by a real round-trip rise) | throttled runs below |

## Hand-off (desktop → website)

| What | Bar | Measured | How |
|---|---|---|---|
| Link opens nothing by itself | no auto-join | page shows only "JOIN CALL"; `?join=` removed from the address bar | live, Chrome |
| Camera/mic refused | the original call keeps going | original leg still `active`, other side unaffected | live, permission rejection simulated |
| Join → call active on the new device | — | 247 ms (local server) | live, one run |
| After the swap | old device stops; other side re-keys | old leg ended, other side showed a new matching safety code and received video (360p start, 720p on Max) | live; the "old device" was a web tab in relay mode standing in for the desktop |
| Races, reuse, expiry, outsiders, flag off | all refused | all refused | `testCallHandoff` (server) |
| The real desktop app's "Video on website" button | — | **not tested live** (compiles; URL built from the WebSocket address) | |

## Throttled relay

`src/test/java/edu/ember/test/ThrottleProxy.java` sits between one browser and the server: a bandwidth limit, extra one-way delay, and a 64 KB router queue, on a timed profile. One side (Thr) connected through it; the other (Hob) connected straight to the server. Auto mode, relay forced, both tabs kept "visible", synthetic 720p30 cameras. TCP only: there is no packet loss on the relay, only delay and queueing.

| What | Bar | Measured |
|---|---|---|
| Step-down after the link drops to 400 kbit/s + 40 ms | ≤ 2 s after trouble | round-trip spike seen 1.8 s after the drop; first step down in the next 1 s sample (1.8–2.8 s after the drop); 180p floor 4.8 s after the drop. Before pinging every second (decision 25): first step 3 s after the drop |
| Steady state at 400 kbit/s | voice keeps going, video at what fits | 180p/15 with tries at 270p that held for 12 s in run 1 and failed after 3 s in run 2; round trip ~100–130 ms (2.9 s peak while the queue first filled) |
| Voice through the throttle (Thr → Hob) | no dropout | Hob's voice buffer for Thr emptied for one 1 s sample as the throttle began, then 120–150 ms for the rest of it; no other gaps |
| Recovery after the link clears | steps up only after ≥ 6 s calm | first step up 21.8 s and 23 s after clearing (two runs); 540p back after 60 s in run 1. Slow on purpose: rungs that failed wait 12 s, then 24 s, up to 60 s before another try |
| 1.2 Mbit/s + 20 ms | 540p (1.1 Mbit/s) fits | held 540p/30, round trip 54–79 ms, no step-down |
| Pause state lost (a "visible again" event never arrives) | the other side does not stay paused | before the fix: video stopped for good (seen live, run 1). After (decision 24): a stray "paused" cleared after 0.7 s |

## Relay queue guard (tunnel-like buffering)

Same proxy, now with a **4 MB** queue, so it behaves like a tunnel or proxy that holds seconds of data (the website is reached through a Cloudflare tunnel). Profile: 800 kbit/s + 30 ms, dropping to **200 kbit/s for 50 s**, then back. One browser through the proxy, one straight to the server, relay forced, synthetic 720p30 camera, Opus voice. Round trips are the sender's relay pings (24 Nov 2026).

| Round trip while the link was at 200 kbit/s | Without the guard | With the guard (decision 38) |
|---|---|---|
| Median | 8,822 ms | 93 ms |
| 95th percentile | 16,804 ms | 894 ms |
| Max | 17,386 ms | 1,170 ms |

What it costs: at 200 kbit/s video stays paused most of the time (voice keeps going) and is retried every 2–8 s; each try shows as a ~1 s bump. Without the guard video kept flowing, 9–17 s late. Earlier versions measured on the way there: resume as soon as clear (max 1.8 s, bumps every 2–3 s); plus the growing wait (max 5.3 s on the first backlog, because only answered pings counted); plus unanswered pings (the table). On a steady 800 kbit/s link the round trip stayed at 80–100 ms. **Not measured:** the real Cloudflare tunnel with two real people, direct (peer-to-peer) calls under loss.

## Server load

| What | Measured |
|---|---|
| Desktop app CPU, idle / in a call | 0.5 % / 1.0 % (P0 baseline, before calls v2) |
| Render CPU / RAM during a relayed call | **not measured**: needs the Render dashboard (owner access) |
| Throttled network | relay measured with a JDK proxy (below); direct WebRTC (UDP) under loss **not measured** (needs an OS-level tool with admin rights, e.g. Clumsy) |

## Tests

`scripts\run-tests.bat`: 53 Java tests and 11 Node tests, all passing (24 Nov 2026).
