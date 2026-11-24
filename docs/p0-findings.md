# P0 findings (24 Nov 2026)

No STOP condition. The prompt's description of the current call system is right, with these corrections and details:

**Correct as stated:** relay runs over TCP (the site's WebSocket); direct calls use WebRTC congestion control; web↔desktop uses 16 kHz IMA ADPCM; Opus only between browsers (direct: SDP `maxaveragebitrate=40000` + in-band FEC + DTX; relay: 32 kbit/s + DTX, no FEC); voice outranks video (video skipped before encode, audio only dropped above 512 KB queued); desktop audio runs off the EDT (capture + mixer threads, network on the reader thread, UI via `invokeLater`); call keys are per device pair with a safety code; build/test/Docker scripts and the zero-dependency runner (39 tests) exist.

**Corrections / precise values**
- **Ladder already has 540p.** Rungs: 180p/15 fps/150 k, 270p/20/300 k, 360p/25/600 k, 540p/30/1100 k, 720p/30/1800 k. Starts at 360p.
- **Step-down signal is only the local send queue** (frame skipped when the socket buffer holds > ~250 ms of video at the current rung, or the encoder queue backs up; >1 skip in a 2 s window steps down). **RTT and receiver feedback are not used.** Step-up after 3 clean 2 s windows (6 s); **no limit on changes per 10 s** (oscillation possible).
- **Camera cap uses the delivered `<video>` height** (`videoHeight`), not `getSettings()`; fps is not measured. Direct calls have no explicit ceiling beyond the `getUserMedia` ideal of 1280×720/30.
- **Direct audio sender cap is 48 kbit/s** (`maxBitrate`), SDP average 40 kbit/s.
- **ADPCM frames already carry predictor + step index in a 3-byte header**: 163 bytes per 20 ms (65.2 kbit/s payload); a lost frame cannot desync the decoder. Web and desktop encoders were byte-identical on a test signal (hash match).
- **Desktop audio path:** captures at 16 kHz straight from Java Sound (no own resampler, no DC blocker, no limiter); per-peer FIFO capped at 8 frames, drops the oldest, **no concealment**; the mixer is paced by a blocking 120 ms speaker buffer; mic buffer 80 ms.
- **Codec choice is re-run when people join** (group calls); for 1:1 it is effectively fixed at connect.
- **No quality modes, no pause protocol, no display-size limit, no stats overlay** exist yet.

**Baselines** (details in `docs/baseline.md`): desktop CPU 0.5 % idle → 1.0 % in a voice call (16 cores); desktop jitter queue ≈ 62 ms + speaker ≈ 108 ms → **mouth-to-ear ≈ 190 ms + OS latency (estimate)**; a 500 ms stall gives **440 ms of silence and a permanent latency rise from ~60 to 140 ms** (simulated with the current rule); relay ladder climbs 360→720p in ≈ 12 s and falls to 180p within 6 s under a forced socket backlog.

**Risks found (pre-existing, noted, not in scope unless they block):**
- The server writes relayed packets synchronously on the sender's connection thread: a slow receiver can stall the sender's reads (head-of-line on TCP). Matters for 720p relay on Render; measured in P3.
- Call safety codes are 12 digits (~40 bits): fine for live calls, documented.

**Concept audit:** `docs/concept-coverage.md`. Gaps that fit the call work naturally (filled in P1–P3): abstract class, Comparable + equals/hashCode, Command, State, call-specific Observer/Strategy/Factory, checked call exceptions, contracts/assertions, settings file. Not applicable: Proxy, Singleton, Visitor, JDBC.
