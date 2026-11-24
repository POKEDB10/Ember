'use strict';
// Ember call quality rules, shared by the page (call.js) and the Node tests (src/test/web).
// No DOM here: every function is a pure function of its inputs, so it can be tested on its own.
// The numbers follow docs/ladder.md; the desktop app mirrors them in QualityPolicy.java.

/** Voice codec ids on the wire (the desktop uses the same numbers). */
const AC = { OPUS: 1, MULAW: 2, ADPCM: 3, ADPCM32: 4 };

/** Relay video quality ladder (resolution, frame rate, bitrate move together). */
const LADDER = [
  { w: 320, h: 180, fps: 15, bps: 150000 },
  { w: 480, h: 270, fps: 20, bps: 300000 },
  { w: 640, h: 360, fps: 25, bps: 600000 },
  { w: 960, h: 540, fps: 30, bps: 1100000 },
  { w: 1280, h: 720, fps: 30, bps: 1800000 },
];

/** The three quality modes (per device; Auto is the default). */
const QUALITY = {
  datasaver: { label: 'Data saver', opus: 24000, adpcm: 16000, direct: { h: 360, fps: 15 }, relay: { h: 360, fps: 15 }, followsDisplay: true },
  auto: { label: 'Auto', opus: 32000, adpcm: 32000, direct: { h: 720, fps: 30 }, relay: { h: 540, fps: 30 }, followsDisplay: true },
  max: { label: 'Max', opus: 40000, adpcm: 32000, direct: { h: 1080, fps: 30 }, relay: { h: 720, fps: 30 }, followsDisplay: false },
};

function qualityFor(mode) {
  return QUALITY[mode] || QUALITY.auto;
}

/**
 * The voice codec this side sends, fixed when the call connects: Opus when both sides have it,
 * else ADPCM at the mode's rate (32 kHz, or 16 kHz in Data saver) if the other side plays it,
 * else 16 kHz ADPCM, else G.711. Returns 0 when nothing is shared.
 */
function pickVoiceCodec(mode, iCanOpus, theirs) {
  const prefs = (iCanOpus ? [AC.OPUS] : [])
    .concat(qualityFor(mode).adpcm === 32000 ? [AC.ADPCM32, AC.ADPCM] : [AC.ADPCM], [AC.MULAW]);
  return prefs.find((id) => theirs.includes(id)) || 0;
}

/** Codecs the other side can play: its announced list (validated), or what an older build implies. */
function peerDecodes(msg) {
  if (Array.isArray(msg.codecs)) {
    return msg.codecs.filter((id) => Number.isInteger(id) && id >= 1 && id <= 16).slice(0, 8);
  }
  return (msg.opus ? [AC.OPUS] : []).concat(msg.adpcm ? [AC.ADPCM] : [], [AC.MULAW]);
}

/**
 * IMA ADPCM, 4 bits per sample, at 16 or 32 kHz. Each 20 ms frame stands alone: [first sample,
 * 2 bytes LE][step index] then one 4-bit code per further sample (low nibble first). Byte for byte
 * the same as AdpcmCodec.java (shared test vectors: src/test/resources/adpcm-vectors.txt).
 */
const IMA_STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767];
const IMA_INDEX = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8];

function adpcmEncode(samples, state) {
  const n = samples.length, out = new Uint8Array(3 + Math.ceil((n - 1) / 2));
  const s16 = (x) => Math.max(-32768, Math.min(32767, Math.round(x * 32767)));
  let pred = s16(samples[0]), index = state.index;
  out[0] = pred & 0xff; out[1] = (pred >> 8) & 0xff; out[2] = index;
  for (let i = 1; i < n; i++) {
    let diff = s16(samples[i]) - pred, code = 0, step = IMA_STEP[index], delta = step >> 3;
    if (diff < 0) { code = 8; diff = -diff; }
    if (diff >= step) { code |= 4; diff -= step; delta += step; }
    step >>= 1;
    if (diff >= step) { code |= 2; diff -= step; delta += step; }
    step >>= 1;
    if (diff >= step) { code |= 1; delta += step; }
    pred = Math.max(-32768, Math.min(32767, code & 8 ? pred - delta : pred + delta));
    index = Math.max(0, Math.min(88, index + IMA_INDEX[code]));
    out[3 + ((i - 1) >> 1)] |= (i - 1) & 1 ? code << 4 : code;
  }
  state.index = index;
  return out;
}

function adpcmDecode(bytes) {
  if (bytes.length < 4) return new Float32Array(0);
  const n = (bytes.length - 3) * 2, out = new Float32Array(n); // frames hold an even number of samples; the last half-byte is padding
  let pred = (bytes[0] | (bytes[1] << 8)) << 16 >> 16, index = Math.min(88, bytes[2]);
  out[0] = pred / 32768;
  for (let i = 1; i < n; i++) {
    const code = ((i - 1) & 1 ? bytes[3 + ((i - 1) >> 1)] >> 4 : bytes[3 + ((i - 1) >> 1)]) & 15;
    const step = IMA_STEP[index];
    let delta = step >> 3;
    if (code & 4) delta += step;
    if (code & 2) delta += step >> 1;
    if (code & 1) delta += step >> 2;
    pred = Math.max(-32768, Math.min(32767, code & 8 ? pred - delta : pred + delta));
    index = Math.max(0, Math.min(88, index + IMA_INDEX[code]));
    out[i] = pred / 32768;
  }
  return out;
}

/**
 * Streaming anti-aliased resampler (polyphase windowed sinc, 64 taps per phase, Blackman window,
 * cut-off at 85% of the lower Nyquist), the same design as Resampler.java. Returns a function
 * that takes a Float32Array chunk and returns the resampled chunk.
 */
function makeResampler(inRate, outRate) {
  if (inRate === outRate) return (chunk) => chunk.slice();
  const gcd = (a, b) => (b ? gcd(b, a % b) : a), g = gcd(inRate, outRate), up = outRate / g, down = inRate / g, taps = 64;
  const len = taps * up, cutoff = (0.85 * 0.5) / Math.max(up, down), centre = (len - 1) / 2;
  const bank = Array.from({ length: up }, () => new Float32Array(taps));
  for (let i = 0; i < len; i++) {
    const x = i - centre;
    const sinc = x === 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
    const w = 0.42 - 0.5 * Math.cos((2 * Math.PI * i) / (len - 1)) + 0.08 * Math.cos((4 * Math.PI * i) / (len - 1));
    bank[i % up][Math.floor(i / up)] = sinc * w * up;
  }
  let history = new Float32Array(taps - 1), position = 0;
  return (chunk) => {
    const joined = new Float32Array(history.length + chunk.length);
    joined.set(history); joined.set(chunk, history.length);
    const out = [];
    while (position < chunk.length * up) {
      const at = Math.floor(position / up) + history.length, phase = position % up, h = bank[phase];
      let sum = 0;
      for (let k = 0; k < taps; k++) sum += h[k] * joined[at - k];
      out.push(sum);
      position += down;
    }
    position -= chunk.length * up;
    history = joined.slice(joined.length - history.length);
    return Float32Array.from(out);
  };
}

// ---- video limits (calls v2) ----

/** Highest rung no taller than h (for ceilings: camera, mode). */
function rungAtMost(h) {
  let rung = 0;
  LADDER.forEach((q, i) => { if (q.h <= h) rung = i; });
  return rung;
}

/** Lowest rung at least as tall as h (for the receiver's display: no point sending more). */
function rungAtLeast(h) {
  const i = LADDER.findIndex((q) => q.h >= h);
  return i < 0 ? LADDER.length - 1 : i;
}

/**
 * Relay send quality = min(camera ceiling, mode ceiling, receiver display, what the network
 * sustains). Returns the rung, the frame rate (camera fps is a soft ceiling) and which of the four
 * is limiting, for the stats overlay. On a tie the first in that order is reported.
 */
function relayLimit({ cameraH, cameraFps, mode, displayH, netRung }) {
  const q = qualityFor(mode);
  const parts = [
    ['camera', rungAtMost(cameraH || 720)],
    ['mode', rungAtMost(q.relay.h)],
    ['display', q.followsDisplay && displayH > 0 ? rungAtLeast(displayH) : LADDER.length - 1],
    ['network', netRung],
  ];
  const [limiting, rung] = parts.reduce((best, part) => (part[1] < best[1] ? part : best));
  const fps = Math.max(5, Math.min(LADDER[rung].fps, q.relay.fps, Math.round(cameraFps || 30)));
  return { ...LADDER[rung], rung, fps, limiting }; // fps after the spread: the camera-capped rate wins
}

/** Direct video cap: 2.5 Mbit/s at 720p30 scaled by pixels and frame rate, capped by call size. */
function directBitrate(height, fps, people) {
  const ceiling = people <= 2 ? 4000000 : people === 3 ? 1200000 : 800000;
  return Math.round(Math.max(150000, Math.min(ceiling, 2500000 * (height / 720) ** 2 * (fps / 30))));
}

/**
 * Direct (WebRTC) ceiling: the browser's congestion control adapts below it. Returns what goes into
 * RTCRtpSender.setParameters: maxBitrate, maxFramerate and scaleResolutionDownBy.
 */
function directLimit({ cameraH, cameraFps, mode, displayH, people }) {
  const q = qualityFor(mode), camera = cameraH || 720;
  const parts = [['camera', camera], ['mode', q.direct.h], ['display', q.followsDisplay && displayH > 0 ? displayH : Infinity]];
  const [limiting, height] = parts.reduce((best, part) => (part[1] < best[1] ? part : best));
  const fps = Math.max(5, Math.min(q.direct.fps, Math.round(cameraFps || 30)));
  return { height: Math.round(height), fps, limiting, scale: Math.max(1, camera / height), bps: directBitrate(height, fps, people) };
}

/**
 * What the network sustains on the relay, as a rung. Called every second with whether that window had
 * trouble (send queue backing up, round trip rising, or the receiver reporting late/lost frames).
 * Steps down at once; steps up only after 6 s without trouble. A rung that failed must stay calm
 * twice as long before it is tried again (12 s, then 24 s, up to 60 s), so a link that can almost
 * carry a rung does not flip back and forth.
 */
class Ladder {
  constructor(start = 2, top = LADDER.length - 1) {
    this.net = start;
    this.top = top;
    this.calmSince = null;
    this.lastChange = -Infinity;
    this.upWait = LADDER.map(() => 6000);
    this.changes = [];
  }

  /** @param current the rung actually in use; a drop starts from it, so every step down is visible at once */
  update(now, trouble, current = this.net) {
    if (trouble) {
      this.calmSince = null;
      this.net = Math.min(this.net, current);
      if (this.net > 0) {
        this.upWait[this.net] = Math.min(60000, Math.max(12000, this.upWait[this.net] * 2));
        this.change(now, this.net - 1);
      }
      return this.net;
    }
    if (this.calmSince === null) this.calmSince = now;
    if (this.net < this.top && now - this.calmSince >= this.upWait[this.net + 1] && now - this.lastChange >= 6000) {
      this.change(now, this.net + 1);
      this.calmSince = now;
    }
    return this.net;
  }

  change(now, rung) {
    this.net = rung;
    this.lastChange = now;
    this.changes.push(now);
    if (this.changes.length > 50) this.changes.shift();
  }
}

/** Trouble in the last window: frames skipped for a backed-up socket, rising round trip, or receiver losses. */
function relayTrouble({ skipped, rttMs, minRttMs, report, fps }) {
  const rttRising = rttMs > 0 && minRttMs > 0 && rttMs > minRttMs + 150 && rttMs > minRttMs * 1.5;
  const expected = Math.max(1, ((report && report.windowMs) || 2000) / 1000 * (fps || 15));
  const receiverBad = !!report && (report.late + report.lost) / expected > 0.1;
  return skipped > 1 || rttRising || receiverBad;
}

/**
 * Calls v2 control messages from the other side (inside the encrypted signal channel). Everything
 * is validated and clamped; unknown types and fields are ignored, so old and new builds mix.
 */
function parseControl(msg) {
  if (!msg || typeof msg !== 'object' || msg.v !== 2 || typeof msg.t !== 'string') return null;
  const num = (x, lo, hi) => (typeof x === 'number' && Number.isFinite(x) ? Math.round(Math.max(lo, Math.min(hi, x))) : null);
  switch (msg.t) {
    case 'caps': {
      const features = Array.isArray(msg.features) ? msg.features.filter((f) => ['disp', 'vstat', 'vpause', 'vkey'].includes(f)).slice(0, 8) : [];
      return { t: 'caps', codecs: peerDecodes(msg), features, maxVideo: num(msg.maxVideo, 0, 4320) || 0 };
    }
    case 'disp': {
      const w = num(msg.w, 0, 8192), h = num(msg.h, 0, 8192);
      const dpr = typeof msg.dpr === 'number' && Number.isFinite(msg.dpr) ? Math.max(0.5, Math.min(4, msg.dpr)) : 1;
      return w === null || h === null ? null : { t: 'disp', w, h, dpr };
    }
    case 'vstat': {
      const late = num(msg.late, 0, 10000), lost = num(msg.lost, 0, 10000), windowMs = num(msg.windowMs, 500, 10000);
      return late === null || lost === null || windowMs === null ? null : { t: 'vstat', late, lost, windowMs };
    }
    case 'vpause': return typeof msg.paused === 'boolean' ? { t: 'vpause', paused: msg.paused } : null;
    case 'vkey': return { t: 'vkey' };
    default: return null;
  }
}

/** Minimum gap between control messages of one type from one peer (anything faster is dropped). */
const CONTROL_GAP_MS = { caps: 1000, disp: 200, vstat: 1000, vpause: 200, vkey: 1000 };

function allowControl(last, type, now) {
  if (now - (last[type] ?? -Infinity) < (CONTROL_GAP_MS[type] ?? 1000)) return false;
  last[type] = now;
  return true;
}

if (typeof module !== 'undefined') {
  module.exports = { AC, LADDER, QUALITY, qualityFor, pickVoiceCodec, peerDecodes, adpcmEncode, adpcmDecode, makeResampler,
    rungAtMost, rungAtLeast, relayLimit, directLimit, directBitrate, Ladder, relayTrouble, parseControl, allowControl };
}
