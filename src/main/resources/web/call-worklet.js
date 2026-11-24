'use strict';
// Audio for relayed calls, run on the browser's audio thread.
// ember-capture: cuts the microphone into 20 ms frames for the encoder.
// ember-play: a small jitter buffer that plays the other person; it starts after 40 ms of audio and
// drops the oldest audio when more than 160 ms piles up, so delay never grows during a call.

class Capture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = Math.round(sampleRate / 50);
    this.buf = new Float32Array(this.size);
    this.n = 0;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch) {
      for (let i = 0; i < ch.length; i++) {
        this.buf[this.n++] = ch[i];
        if (this.n === this.size) {
          this.port.postMessage(this.buf, [this.buf.buffer]);
          this.buf = new Float32Array(this.size);
          this.n = 0;
        }
      }
    }
    return true;
  }
}

class Play extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.queued = 0;
    this.offset = 0;
    this.started = false;
    this.port.onmessage = (e) => {
      this.queue.push(e.data);
      this.queued += e.data.length;
      const max = sampleRate * 0.16;
      while (this.queued - this.offset > max && this.queue.length > 1) {
        this.queued -= this.queue.shift().length;
        this.offset = 0;
      }
    };
  }

  process(_, outputs) {
    const out = outputs[0][0];
    if ((this.reports = (this.reports || 0) + 1) % 75 === 0) this.port.postMessage({ depthMs: Math.round(((this.queued - this.offset) / sampleRate) * 1000) });
    if (!this.started && this.queued >= sampleRate * 0.04) this.started = true;
    let i = 0;
    while (this.started && i < out.length && this.queue.length) {
      const head = this.queue[0];
      const take = Math.min(out.length - i, head.length - this.offset);
      out.set(head.subarray(this.offset, this.offset + take), i);
      i += take;
      this.offset += take;
      if (this.offset === head.length) {
        this.queue.shift();
        this.queued -= head.length;
        this.offset = 0;
      }
    }
    if (i < out.length) {
      out.fill(0, i);
      if (this.started && !this.queue.length) this.started = false; // underrun: build up 40 ms again
    }
    for (let c = 1; c < outputs[0].length; c++) outputs[0][c].set(out);
    return true;
  }
}

registerProcessor('ember-capture', Capture);
registerProcessor('ember-play', Play);
