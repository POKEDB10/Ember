# Video quality ladder and limits (calls v2)

The single table the website (`src/main/resources/web/quality.js`) and the desktop policy (`client/call/QualityPolicy.java`) follow. Node tests check the website's copy (`src/test/web/quality.test.js`).

## Relay rungs

Resolution, frame rate and bitrate always move together.

| Rung | Resolution | fps | Video bitrate |
|---|---|---|---|
| 0 | 320×180 | 15 | 150 kbit/s |
| 1 | 480×270 | 20 | 300 kbit/s |
| 2 | 640×360 | 25 | 600 kbit/s (start) |
| 3 | 960×540 | 30 | 1100 kbit/s |
| 4 | 1280×720 | 30 | 1800 kbit/s |

## Quality modes

| Mode | Direct video ceiling | Relay ceiling | Follows the other side's display | Web voice (Opus) | Desktop voice |
|---|---|---|---|---|---|
| Data saver | 360p / 15 fps | 360p / 15 fps | yes | 24 kbit/s | ADPCM 16 kHz |
| Auto (default) | 720p / 30 fps | 540p / 30 fps | yes | 32 kbit/s | ADPCM 32 kHz |
| Max | up to the camera, 1080p / 30 fps | 720p / 30 fps | no | 40 kbit/s | ADPCM 32 kHz |

Opus always uses DTX (near-silent when nobody talks). In-band FEC is on for direct calls (UDP) only; the relay is TCP and never loses packets.

## Send quality

**Send quality = min(camera ceiling, mode ceiling, the other side's display size, what the network sustains).** The stats overlay (Ctrl+Shift+D) names the one that is limiting.

- **Camera ceiling:** resolution from `getSettings()`. The frame rate is measured from frames the camera actually delivers (`MediaStreamTrackProcessor`; else `requestVideoFrameCallback`; else the setting), during ringing and again every 10 s. It is a soft ceiling: when auto-exposure lowers it, the call follows.
- **Display size:** the other side reports its video tile in device pixels (CSS px × devicePixelRatio), debounced 300 ms. The relay sends the lowest rung at least that tall. Changes apply at once in both directions, still bounded by the network.
- **Network (relay):** checked every second. A window has trouble when:
  - more than one frame was skipped for a backed-up socket (outside the 1.5 s grace period after a rung change);
  - the round trip rises more than 150 ms and 1.5× above its minimum;
  - the receiver reports more than 10 % of frames late or lost (`vstat`, every 2 s).

  On trouble it steps down at once from the rung in use. It steps up only after 6 s without trouble. A rung that failed waits twice as long before it is tried again (12 s, 24 s, …, up to 60 s), so a link that can almost carry a rung does not flip back and forth.
- **Network (direct):** the ceiling goes into `RTCRtpSender.setParameters` (`maxBitrate`, `maxFramerate`, `scaleResolutionDownBy`). WebRTC's own congestion control adapts below it. The direct bitrate cap is 2.5 Mbit/s at 720p30, scaled by pixels and frame rate, and capped by call size: 4 Mbit/s for 2 people, 1.2 Mbit/s for 3, 0.8 Mbit/s for 4.

## Pause

- **My window hidden:** I stop decoding their video and tell them to pause (`vpause`).
- **They pause me:** I stop encoding (relay), or set the sender inactive (direct). On resume, a key frame is sent.
- **Key-frame requests (`vkey`):** at most one per second.
- **Pause and display messages that arrive too fast:** they are coalesced, not dropped: the newest one is applied once the gap passes.
