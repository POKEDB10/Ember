'use strict';
// Tests for the website's call rules (src/main/resources/web/quality.js).
// Zero dependencies: Node's built-in test runner. Run: node --test src/test/web
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const q = require('../../main/resources/web/quality.js');

const root = path.join(__dirname, '..', '..', '..');

test('ADPCM matches the shared vectors byte for byte (16 and 32 kHz)', () => {
  const lines = fs.readFileSync(path.join(root, 'src/test/resources/adpcm-vectors.txt'), 'utf8')
    .split(/\r?\n/).filter((line) => line && !line.startsWith('#'));
  for (const rate of [16000, 32000]) {
    const state = { index: 0 }, n = rate / 50;
    for (let frame = 0; frame < 3; frame++) {
      const samples = new Float32Array(n);
      for (let i = 0; i < n; i++) {
        const t = (frame * n + i) / rate;
        samples[i] = Math.round((0.5 * Math.sin(2 * Math.PI * 440 * t) + 0.2 * Math.sin(2 * Math.PI * 3100 * t)) * 32767) / 32767;
      }
      const hex = Buffer.from(q.adpcmEncode(samples, state)).toString('hex');
      const expected = lines.find((line) => line.startsWith(rate + ' ' + frame + ' ')).split(' ')[2];
      assert.strictEqual(hex, expected, `rate ${rate} frame ${frame}`);
      assert.strictEqual(q.adpcmDecode(q.adpcmEncode(samples, { index: 0 })).length, n);
    }
  }
});

test('voice codec choice per call', () => {
  const { AC } = q;
  assert.strictEqual(q.pickVoiceCodec('auto', true, [AC.OPUS, AC.ADPCM32, AC.ADPCM, AC.MULAW]), AC.OPUS);
  assert.strictEqual(q.pickVoiceCodec('auto', true, [AC.ADPCM32, AC.ADPCM, AC.MULAW]), AC.ADPCM32, 'desktop, Auto');
  assert.strictEqual(q.pickVoiceCodec('datasaver', true, [AC.ADPCM32, AC.ADPCM, AC.MULAW]), AC.ADPCM, 'desktop, Data saver');
  assert.strictEqual(q.pickVoiceCodec('auto', true, q.peerDecodes({ adpcm: true })), AC.ADPCM, 'older desktop build');
  assert.strictEqual(q.pickVoiceCodec('auto', false, [AC.OPUS, AC.MULAW]), AC.MULAW, 'no Opus here');
  assert.strictEqual(q.pickVoiceCodec('auto', true, []), 0, 'nothing shared');
});

test('quality modes and the ladder follow docs/ladder.md', () => {
  assert.deepStrictEqual([q.QUALITY.datasaver.opus, q.QUALITY.auto.opus, q.QUALITY.max.opus], [24000, 32000, 40000]);
  assert.deepStrictEqual(q.QUALITY.datasaver.relay, { h: 360, fps: 15 });
  assert.deepStrictEqual(q.QUALITY.auto.relay, { h: 540, fps: 30 });
  assert.deepStrictEqual(q.QUALITY.max.direct, { h: 1080, fps: 30 });
  assert.strictEqual(q.qualityFor('nonsense'), q.QUALITY.auto);
  for (let i = 1; i < q.LADDER.length; i++) {
    assert.ok(q.LADDER[i].h > q.LADDER[i - 1].h && q.LADDER[i].bps > q.LADDER[i - 1].bps, 'ladder must climb');
  }
});

test('peer codec lists are validated (fuzz)', () => {
  let seed = 42;
  const random = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
  const junk = [null, undefined, -1, 0, 17, 1e9, 'x', {}, [], 2.5, NaN, Infinity, true, 4, 1];
  for (let round = 0; round < 1000; round++) {
    const codecs = Array.from({ length: Math.floor(random() * 40) }, () => junk[Math.floor(random() * junk.length)]);
    const decodes = q.peerDecodes({ codecs });
    assert.ok(decodes.length <= 8, 'list must be capped');
    assert.ok(decodes.every((id) => Number.isInteger(id) && id >= 1 && id <= 16), 'only valid ids survive');
  }
  assert.deepStrictEqual(q.peerDecodes({ codecs: 'not a list', opus: true }), [1, 2]);
});

test('anti-aliasing resampler: flat passband, deep stopband (48 -> 32 kHz)', () => {
  const gainDb = (freq) => {
    const run = q.makeResampler(48000, 32000), input = new Float32Array(24000);
    for (let i = 0; i < input.length; i++) input[i] = 0.5 * Math.sin((2 * Math.PI * freq * i) / 48000);
    const out = run(input);
    let pin = 0, pout = 0;
    for (let i = 6000; i < input.length; i++) pin += input[i] * input[i];
    for (let i = Math.floor(out.length / 4); i < out.length; i++) pout += out[i] * out[i];
    return 10 * Math.log10((pout / (out.length - Math.floor(out.length / 4))) / (pin / (input.length - 6000)) + 1e-20);
  };
  for (const f of [300, 1000, 4000, 8000, 12000]) assert.ok(Math.abs(gainDb(f)) < 1, `${f} Hz passband`);
  for (const f of [17500, 20000, 23000]) assert.ok(gainDb(f) < -50, `${f} Hz stopband`);
});

test('send limit = min(camera, mode, display, network), with the limiting factor named', () => {
  const vga = q.relayLimit({ cameraH: 480, cameraFps: 30, mode: 'max', displayH: 0, netRung: 4 });
  assert.ok(vga.h <= 480, 'a 640x480 camera must never be encoded above 480p');
  assert.strictEqual(vga.limiting, 'camera');
  assert.strictEqual(q.relayLimit({ cameraH: 720, mode: 'auto', displayH: 0, netRung: 4 }).h, 540, 'Auto relay caps at 540p');
  assert.strictEqual(q.relayLimit({ cameraH: 720, mode: 'auto', displayH: 0, netRung: 4 }).limiting, 'mode');
  const pip = q.relayLimit({ cameraH: 720, mode: 'auto', displayH: 200, netRung: 4 });
  assert.strictEqual(pip.h, 270, 'a 200 px tall tile needs only the 270p rung');
  assert.strictEqual(pip.limiting, 'display');
  assert.strictEqual(q.relayLimit({ cameraH: 720, mode: 'max', displayH: 200, netRung: 4 }).h, 720, 'Max ignores display size');
  const net = q.relayLimit({ cameraH: 720, mode: 'auto', displayH: 1000, netRung: 1 });
  assert.strictEqual(net.limiting, 'network');
  assert.strictEqual(q.relayLimit({ cameraH: 720, cameraFps: 12, mode: 'auto', displayH: 0, netRung: 4 }).fps, 12, 'camera fps is a ceiling');
  assert.deepStrictEqual(q.relayLimit({ cameraH: 1080, mode: 'datasaver', displayH: 0, netRung: 4 }).h, 360);
  const direct = q.directLimit({ cameraH: 480, cameraFps: 30, mode: 'max', displayH: 0, people: 2 });
  assert.ok(direct.height <= 480 && direct.scale === 1, 'direct never above the camera');
  const shrink = q.directLimit({ cameraH: 1080, cameraFps: 30, mode: 'auto', displayH: 360, people: 2 });
  assert.strictEqual(shrink.height, 360);
  assert.strictEqual(shrink.scale, 3);
  assert.strictEqual(shrink.limiting, 'display');
});

test('ladder: down at once, up only after 6 s calm, failed rungs back off', () => {
  const ladder = new q.Ladder(2);
  let now = 0;
  ladder.update(now, false);
  for (now = 2000; now < 6000; now += 2000) assert.strictEqual(ladder.update(now, false), 2, 'no step up before 6 s');
  assert.strictEqual(ladder.update(6000, false), 3, 'step up after 6 s calm');
  assert.strictEqual(ladder.update(8000, true), 2, 'step down in the first window with trouble');
  // Calm starts at the first trouble-free window (10 s); the failed rung needs 12 s of calm.
  for (now = 10000; now < 22000; now += 2000) assert.strictEqual(ladder.update(now, false), 2, 'a rung that failed waits 12 s');
  assert.strictEqual(ladder.update(22000, false), 3);

  // Steady trouble just above rung 2 (rung 3 always fails): measure the change rate over 10 minutes.
  const steady = new q.Ladder(3);
  for (now = 0; now <= 600000; now += 2000) steady.update(now, steady.net >= 3);
  const late = steady.changes.filter((t) => t > 60000);
  const headroom = new q.Ladder(4);
  assert.strictEqual(headroom.update(1000, true, 3), 2, 'a drop starts from the rung in use (mode-capped at 3), not the network estimate');
  assert.ok(late.length <= (600000 - 60000) / 10000, `${late.length} changes after settling: more than 1 per 10 s`);
});

test('trouble signals: send backlog, rising round trip, receiver reports', () => {
  assert.ok(q.relayTrouble({ skipped: 2 }));
  assert.ok(!q.relayTrouble({ skipped: 1, rttMs: 60, minRttMs: 40 }));
  assert.ok(q.relayTrouble({ skipped: 0, rttMs: 400, minRttMs: 40 }), 'round trip up 360 ms');
  assert.ok(q.relayTrouble({ skipped: 0, report: { late: 6, lost: 0, windowMs: 2000 }, fps: 25 }), '12% late');
  assert.ok(!q.relayTrouble({ skipped: 0, report: { late: 2, lost: 0, windowMs: 2000 }, fps: 25 }));
});

test('control messages are validated, clamped and rate-limited (fuzz)', () => {
  assert.deepStrictEqual(q.parseControl({ v: 2, t: 'disp', w: 99999, h: 360, dpr: 10 }), { t: 'disp', w: 8192, h: 360, dpr: 4 });
  assert.strictEqual(q.parseControl({ v: 1, t: 'disp', w: 1, h: 1 }), null, 'wrong version');
  assert.strictEqual(q.parseControl({ v: 2, t: 'explode' }), null, 'unknown type ignored');
  assert.strictEqual(q.parseControl({ v: 2, t: 'vpause', paused: 'yes' }), null);
  assert.deepStrictEqual(q.parseControl({ v: 2, t: 'vstat', late: -5, lost: 3.6, windowMs: 1 }), { t: 'vstat', late: 0, lost: 4, windowMs: 500 });
  let seed = 9;
  const random = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
  const values = [null, undefined, NaN, Infinity, -1e12, 1e12, 0, 1, 'x', '', true, [], {}, [1, 2], { a: 1 }];
  const types = ['caps', 'disp', 'vstat', 'vpause', 'vkey', 'bogus', 7, null];
  for (let round = 0; round < 5000; round++) {
    const msg = { v: random() < 0.9 ? 2 : values[Math.floor(random() * values.length)], t: types[Math.floor(random() * types.length)] };
    for (const field of ['w', 'h', 'dpr', 'late', 'lost', 'windowMs', 'paused', 'codecs', 'features', 'maxVideo']) {
      if (random() < 0.5) msg[field] = values[Math.floor(random() * values.length)];
    }
    const parsed = q.parseControl(msg);
    if (parsed) {
      for (const value of Object.values(parsed)) {
        if (typeof value === 'number') assert.ok(Number.isFinite(value) && Math.abs(value) <= 10000, 'clamped');
      }
    }
  }
  const last = {};
  assert.ok(q.allowControl(last, 'vkey', 0));
  assert.ok(!q.allowControl(last, 'vkey', 500), 'key-frame requests at most once a second');
  assert.ok(q.allowControl(last, 'vkey', 1000));
});
