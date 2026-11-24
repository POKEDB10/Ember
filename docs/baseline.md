# Call baselines (before calls v2)

Recorded on 24 Nov 2026 at tag `demo-safe-pre-calls-v2` (commit b2cb623). Windows 11, 16 logical cores, Chrome (the app's built-in browser pane). All runs are on one machine through a local server (`--no-tls --http-port 8099`), so network delay is close to zero. **Synthetic audio** (a 440 Hz oscillator stands in for the web microphone); the desktop used the real default microphone and speaker.

| What | How | Result |
|---|---|---|
| Desktop process CPU, idle (signed in, no call) | `OperatingSystemMXBean.getProcessCpuLoad`, averaged over 5 s | **0.5 %** of all cores |
| Desktop process CPU, during a web→desktop voice call (relay, 16 kHz ADPCM) | same, averaged over 10 s | **1.0 %** of all cores (16 cores) |
| Desktop jitter queue depth during the call | sampled every 250 ms for 10 s by reflection on `CallSession.Peer.voice` | min 2 / avg **3.1** / max 4 frames of 20 ms (**≈ 62 ms**) |
| Desktop speaker line occupancy | `SourceDataLine.getBufferSize() - available()`, same window | avg 3472 of 3840 bytes at 16 kHz/16-bit (**≈ 108 ms**) |
| Desktop microphone line buffer | `TargetDataLine.getBufferSize()` | 2560 bytes (80 ms upper bound) |
| Mouth-to-ear, web→desktop | **estimate, not measured acoustically**: web capture frame 20 ms + network ≈ 1 ms (loopback) + jitter queue ≈ 62 ms + speaker buffer ≈ 108 ms; OS/driver output latency not measured | **≈ 190 ms + OS audio latency** |
| Jitter queue after a 500 ms stall | **simulated** with the exact rule in `CallSession` (deque, drop oldest above 8 frames, mixer pulls 1 frame / 20 ms); start depth 3 (as measured); stall at 1.0 s, then a 26-frame burst (TCP delivers everything late) | **440 ms of silence** (no concealment), 18 frames dropped, then the depth **stays at 7 frames (140 ms) for good**: latency never recovers to the 60 ms before the stall |
| Relay ladder, climbing (web→web, `Hide my IP`, 1280×720 synthetic camera) | rung sampled every 2 s (Chrome, real encoder) | 2,2,3,3,3,3,4,4 → **360p → 540p after ≈ 4 s → 720p after ≈ 12 s**; ≈ 1.9 Mbit/s up at 720p; receiver decoded 1280×720 |
| Relay ladder under injected trouble | `S.ws.bufferedAmount` forced to 300 000 (simulated backed-up socket), rung sampled every 2 s | from 720p: 4,3,1,0,0 → **down 1 rung within 2 s, at the floor (180p) within 6 s**; voice kept flowing (audio threshold 512 KB) |
| Direct 720p (web→web, WebRTC) | `getStats` outbound-rtp after ramp-up | 1280×720, 31 fps, ≈ 2513 kbit/s, `qualityLimitationReason: none` (took ≈ 15 s to ramp from 480×270) |

Not measured at baseline: acoustic mouth-to-ear, packet loss behaviour (TCP relay does not lose), Firefox/Safari, real cameras, real networks.
