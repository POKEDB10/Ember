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
