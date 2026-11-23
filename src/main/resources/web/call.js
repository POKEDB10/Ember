'use strict';
// Ember voice and video calls (one-to-one). Loaded after app.js and uses its helpers.
//
// How a call connects:
//   1. Ring and answer go through the Ember server, with the same rules as a direct message.
//   2. Both browsers make a fresh key pair (ECDH P-256) and swap public keys. From then on every
//      call message is encrypted with a key the server never learns, including the network
//      addresses (ICE candidates). Both screens show a safety code made from the two keys: if
//      the codes match, nobody (not even the server) is in the middle.
//   3. Direct first: a WebRTC peer-to-peer link gives the lowest delay (DTLS-SRTP encrypted).
//   4. If that does not connect within a few seconds, or "Hide my IP" is on, or the network blocks
//      it, the call switches to the relay: Opus audio and VP8 video packets travel inside the
//      normal HTTPS WebSocket, each one AES-GCM encrypted end to end. To the network it looks
//      like an ordinary web page.
// Low data use: mono Opus at 16-20 kbit/s with silence suppression, video at 320x240, 15 fps,
// about 150 kbit/s. A voice call uses roughly 10 MB per hour.

const CALL = { AUDIO_BPS: 16000, VIDEO_BPS: 150000, W: 320, H: 240, FPS: 15, DIRECT_WAIT_MS: 6000, KEY_EVERY: 45 };
const PKT = { AUDIO: 1, VIDEO: 2, PING: 3, PONG: 4 };
const AC = { OPUS: 1, MULAW: 2 };
const VC = { VP8: 1, AVC: 2 };
const VCODEC = { [VC.VP8]: 'vp8', [VC.AVC]: 'avc1.42001f' };
let call = null;

const callPrefs = { hideIp: store.get('ember.hideIp') === '1' };
const u32 = (n) => n >>> 0;
const nowMs = () => u32(Math.round(performance.now()));
const b64 = (bytes) => b64url(bytes);
const unb64 = (text) => urlKey(text);

// ---- starting, answering, ending ----
async function startCall(video) {
  const c = S.convs.get(S.active);
  const p = c && peer(c);
  if (!p || call) return;
  call = newCall({ peer: p, conv: c.id, video, role: 'caller' });
  showCall('calling ' + name(p) + '…');
  try {
    await getMedia(video);
    if (!call) return;
    const f = await request(OP.CALL, { kind: 'ring', convId: c.id, video: String(video), hideIp: String(callPrefs.hideIp) });
    if (!call) return;
    if (f.kind !== 'ringing') { endLocal(reasonText(f.reason)); return; }
    call.id = f.callId;
    ringTone(true, false);
  } catch (e) { endLocal(e.message === 'offline' ? 'you are offline' : e.message); }
}

function newCall(fields) {
  return { id: '', state: 'ringing', mode: '', stream: null, pc: null, iceQueue: [], peerHideIp: false,
    sendCtr: 0, recvCtr: -1, sigSend: 0, sigRecv: -1, sendChain: Promise.resolve(), recvChain: Promise.resolve(),
    sigChain: Promise.resolve(), keyPair: null, keyReady: null, key: null, timers: [], micOn: true, camOn: fields.video,
    relay: null, startedAt: 0, ...fields };
}

async function onRing(f) {
  if (call) { send(OP.CALL, { kind: 'decline', callId: f.callId }); return; } // already on a call
  call = newCall({ id: f.callId, peer: f.from, conv: f.convId, video: f.video === 'true', role: 'callee', peerHideIp: f.hideIp === 'true' });
  await ensureNames([f.from]);
  if (!call) return;
  showCall(name(f.from) + (call.video ? ' · video call' : ' · voice call'), true);
  ringTone(true, true);
  if (document.hidden) systemNotify(name(f.from), call.video ? '🎥 incoming video call' : '📞 incoming call', f.convId);
}

async function acceptCall() {
  if (!call || call.role !== 'callee' || call.state !== 'ringing') return;
  ringTone(false);
  $('callAccept').hidden = true;
  setCallStatus('connecting…');
  try {
    await getMedia(call.video);
    if (!call) return;
    makeKeys();
    send(OP.CALL, { kind: 'accept', callId: call.id, hideIp: String(callPrefs.hideIp) });
    call.state = 'connecting';
    sendKey();
  } catch (e) { hangUp(); toast('⚠ ' + e.message); }
}

function hangUp() {
  if (!call) return;
  if (call.id) send(OP.CALL, { kind: call.state === 'ringing' && call.role === 'callee' ? 'decline' : 'end', callId: call.id });
  endLocal('call ended');
}

function reasonText(r) {
  return { busy: 'they are on another call', unavailable: 'they cannot take calls right now', declined: 'call declined',
    noanswer: 'no answer', missed: 'missed call', elsewhere: 'answered on another device', ended: 'call ended' }[r] || 'call ended';
}

/** Frees everything the call held: camera, microphone, connections, codecs, timers. */
function endLocal(text) {
  const c = call;
  call = null;
  if (!c) return;
  ringTone(false);
  c.timers.forEach((t) => clearInterval(t));
  if (c.stream) c.stream.getTracks().forEach((t) => t.stop());
  if (c.pc) c.pc.close();
  stopRelay(c);
  $('callRemote').srcObject = null; $('callLocal').srcObject = null;
  $('callBox').hidden = true;
  if (text) toast(text + (c.startedAt ? ' · ' + clock(Date.now() - c.startedAt) : ''));
}

const clock = (ms) => Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0');

// ---- events from the server ----
function onCallEvent(f) {
  if (f.kind === 'ring') { onRing(f); return; }
  if (!call || f.callId !== call.id) return;
  if (f.kind === 'end') { endLocal(reasonText(f.reason)); return; }
  if (f.kind === 'accept' && call.role === 'caller') {
    ringTone(false);
    call.state = 'connecting';
    call.peerHideIp = f.hideIp === 'true';
    setCallStatus('connecting…');
    makeKeys();
    sendKey();
    return;
  }
  const c = call;
  c.sigChain = c.sigChain.then(() => onSignal(c, f.kind, f.data)).catch(() => { if (call === c) { hangUp(); toast('⚠ call could not be secured'); } });
}

async function onSignal(c, kind, data) {
  if (call !== c) return;
  if (kind === 'key') { await deriveKey(c, data); return; }
  await c.keyReady;
  const msg = await openSignal(c, data);
  if (call !== c) return;
  if (kind === 'sdp') await onSdp(c, msg);
  else if (kind === 'ice') {
    if (c.pc && c.pc.remoteDescription) await c.pc.addIceCandidate(msg).catch(() => {});
    else c.iceQueue.push(msg);
  } else if (kind === 'relay') onRelaySignal(c, msg);
}

// ---- end-to-end keys and the safety code ----
function makeKeys() {
  call.keyPair = crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  let done;
  call.keyReady = new Promise((r) => { done = r; });
  call.keyDone = done;
}

async function sendKey() {
  const c = call;
  const raw = await crypto.subtle.exportKey('raw', (await c.keyPair).publicKey);
  c.myPub = new Uint8Array(raw);
  if (call === c) send(OP.CALL, { kind: 'key', callId: c.id, data: b64(raw) });
}

async function deriveKey(c, data) {
  if (c.key) return; // one key per call: a second "key" message is ignored
  const theirs = unb64(data);
  const pub = await crypto.subtle.importKey('raw', theirs, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const pair = await c.keyPair;
  while (!c.myPub) await new Promise((r) => setTimeout(r, 10));
  const secret = await crypto.subtle.deriveBits({ name: 'ECDH', public: pub }, pair.privateKey, 256);
  const hkdf = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  c.key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: utf8.encode(c.id), info: utf8.encode('ember-call-v1') },
    hkdf, { name: 'AES-GCM', length: 128 }, false, ['encrypt', 'decrypt']);
  // Safety code: the same 12 digits on both screens unless someone swapped the keys.
  const [a, b] = [c.myPub, theirs].sort((x, y) => (b64(x) < b64(y) ? -1 : 1));
  const both = new Uint8Array(a.length + b.length); both.set(a); both.set(b, a.length);
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', both));
  let digits = '';
  for (let i = 0; i < 6; i++) digits += String(hash[i] % 100).padStart(2, '0');
  $('callCode').textContent = 'safety code ' + digits.replace(/(\d{4})(?=\d)/g, '$1 ') + ' · same on both screens = private call';
  c.keyDone();
  if (c.role === 'caller' && call === c) startTransport(c);
}

/** Caller is direction 0, callee 1; signals use 2 and 3. A packet sent back to its sender is refused. */
const dirOf = (c, mine, signal) => ((c.role === 'caller') === mine ? 0 : 1) + (signal ? 2 : 0);

async function seal(c, dir, ctr, plain) {
  const head = new Uint8Array(5); head[0] = dir; new DataView(head.buffer).setUint32(1, ctr);
  const iv = new Uint8Array(12); iv[0] = dir; iv.set(head.subarray(1), 8);
  const body = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: head }, c.key, plain));
  const out = new Uint8Array(5 + body.length); out.set(head); out.set(body, 5);
  return out;
}

async function unseal(c, bytes, dir, last) {
  if (bytes.length < 21 || bytes[0] !== dir) throw new Error('bad packet');
  const ctr = new DataView(bytes.buffer, bytes.byteOffset, 5).getUint32(1);
  if (ctr <= last) throw new Error('replayed packet');
  const iv = new Uint8Array(12); iv[0] = dir; iv.set(bytes.subarray(1, 5), 8);
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: bytes.subarray(0, 5) }, c.key, bytes.subarray(5));
  return { ctr, plain: new Uint8Array(plain) };
}

async function sendSignal(c, kind, obj) {
  await c.keyReady;
  const sealed = await seal(c, dirOf(c, true, true), ++c.sigSend, utf8.encode(JSON.stringify(obj)));
  if (call === c) send(OP.CALL, { kind, callId: c.id, data: b64(sealed) });
}

async function openSignal(c, data) {
  const { ctr, plain } = await unseal(c, unb64(data), dirOf(c, false, true), c.sigRecv);
  c.sigRecv = ctr;
  return JSON.parse(unutf8.decode(plain));
}

// ---- direct (peer-to-peer WebRTC): lowest delay ----
function startTransport(c) {
  if (callPrefs.hideIp || c.peerHideIp || !window.RTCPeerConnection) { goRelay(c, true); return; }
  startDirect(c).catch(() => goRelay(c, true));
}

async function startDirect(c) {
  const pc = newPeer(c);
  if (c.role === 'caller') {
    const offer = await pc.createOffer();
    await pc.setLocalDescription({ type: offer.type, sdp: thinOpus(offer.sdp) });
    await sendSignal(c, 'sdp', { type: offer.type, sdp: pc.localDescription.sdp });
    c.timers.push(setTimeout(() => { if (call === c && c.mode !== 'direct') goRelay(c, true); }, CALL.DIRECT_WAIT_MS));
  }
}

function newPeer(c) {
  const pc = new RTCPeerConnection({ iceServers: S.ice || [], bundlePolicy: 'max-bundle', rtcpMuxPolicy: 'require' });
  c.pc = pc;
  for (const t of c.stream.getTracks()) {
    const sender = pc.addTrack(t, c.stream);
    if (t.kind === 'video') c.videoSender = sender;
  }
  pc.onicecandidate = (e) => { if (e.candidate && call === c) sendSignal(c, 'ice', e.candidate.toJSON()).catch(() => {}); };
  pc.ontrack = (e) => { $('callRemote').srcObject = e.streams[0]; };
  pc.onconnectionstatechange = () => {
    if (call !== c || c.pc !== pc) return;
    if (pc.connectionState === 'connected') {
      c.mode = 'direct'; connected(c); capBitrates(pc);
    } else if (pc.connectionState === 'failed') {
      goRelay(c, true);
    } else if (pc.connectionState === 'disconnected') {
      c.timers.push(setTimeout(() => { if (call === c && c.pc === pc && pc.connectionState !== 'connected') goRelay(c, true); }, 3000));
    }
  };
  return pc;
}

async function onSdp(c, desc) {
  if (c.mode === 'relay') return;
  if (desc.type === 'offer') {
    if (callPrefs.hideIp) { goRelay(c, true); return; }
    const pc = c.pc || newPeer(c);
    await pc.setRemoteDescription(desc);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription({ type: answer.type, sdp: thinOpus(answer.sdp) });
    await sendSignal(c, 'sdp', { type: answer.type, sdp: pc.localDescription.sdp });
  } else if (c.pc) {
    await c.pc.setRemoteDescription(desc);
  }
  for (const cand of c.iceQueue.splice(0)) await c.pc.addIceCandidate(cand).catch(() => {});
}

/** Opus tuned for speech: mono, silence suppression (DTX), loss recovery (FEC), capped bitrate. */
function thinOpus(sdp) {
  const m = sdp.match(/a=rtpmap:(\d+) opus\/48000/i);
  if (!m) return sdp;
  const extra = 'usedtx=1;useinbandfec=1;stereo=0;sprop-stereo=0;maxaveragebitrate=' + (CALL.AUDIO_BPS + 4000);
  const re = new RegExp('a=fmtp:' + m[1] + ' ([^\\r\\n]*)');
  return re.test(sdp) ? sdp.replace(re, (_, p) => 'a=fmtp:' + m[1] + ' ' + p.split(';').filter((x) => !/^(usedtx|useinbandfec|stereo|sprop-stereo|maxaveragebitrate)=/.test(x)).concat(extra).join(';'))
    : sdp.replace(m[0], m[0] + '\r\na=fmtp:' + m[1] + ' ' + extra);
}

function capBitrates(pc) {
  for (const sender of pc.getSenders()) {
    if (!sender.track) continue;
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    const video = sender.track.kind === 'video';
    params.encodings[0].maxBitrate = video ? CALL.VIDEO_BPS : CALL.AUDIO_BPS + 4000;
    if (video) params.encodings[0].maxFramerate = CALL.FPS;
    sender.setParameters(params).catch(() => {});
  }
}

// ---- relay (inside the HTTPS WebSocket): works where peer-to-peer and VoIP ports are blocked ----
function goRelay(c, tell) {
  if (call !== c || c.mode === 'relay') return;
  if (c.pc) { c.pc.close(); c.pc = null; }
  c.mode = 'relay';
  $('callRemote').srcObject = null;
  startRelay(c).then(() => {
    if (tell && call === c) sendCaps(c);
  }).catch(() => { if (call === c) { hangUp(); toast('⚠ this browser cannot relay calls'); } });
}

/** Tells the other side which codecs this browser can decode, once per call. */
function sendCaps(c) {
  if (!c.relay || c.relay.sentCaps) return;
  c.relay.sentCaps = true;
  sendSignal(c, 'relay', { opus: c.relay.opusOk, video: c.relay.decodable }).catch(() => {});
}

async function onRelaySignal(c, msg) {
  if (msg.keyframe) { if (c.relay) c.relay.forceKey = true; return; }
  if (c.mode !== 'relay') goRelay(c, false);
  await c.relayStarted;
  if (call !== c || !c.relay) return;
  sendCaps(c);
  c.relay.peerCaps = msg;
  setupEncoders(c);
}

async function startRelay(c) {
  let ready;
  c.relayStarted = new Promise((r) => { ready = r; });
  const r = c.relay = { sentCaps: false, peerCaps: null, forceKey: true, frames: 0, lastKfAsk: 0 };
  const actx = r.actx = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
  await actx.audioWorklet.addModule('/call-worklet.js');
  r.rate = actx.sampleRate;
  r.opusOk = !!(window.AudioEncoder && window.AudioDecoder) && await AudioEncoder.isConfigSupported(opusConfig(r.rate)).then((x) => x.supported, () => false);
  r.decodable = [];
  if (window.VideoDecoder) {
    for (const id of [VC.VP8, VC.AVC]) {
      if (await VideoDecoder.isConfigSupported({ codec: VCODEC[id] }).then((x) => x.supported, () => false)) r.decodable.push(id);
    }
  }
  r.play = new AudioWorkletNode(actx, 'ember-play', { outputChannelCount: [1] });
  r.play.connect(actx.destination);
  r.cap = new AudioWorkletNode(actx, 'ember-capture');
  actx.createMediaStreamSource(c.stream).connect(r.cap);
  r.cap.connect(actx.destination); // the capture node outputs silence; connecting keeps it running
  if (actx.state === 'suspended') await actx.resume().catch(() => {});
  connected(c);
  ready();
}

function opusConfig(rate) {
  return { codec: 'opus', sampleRate: rate, numberOfChannels: 1, bitrate: CALL.AUDIO_BPS, opus: { frameDuration: 20000, usedtx: true, useinbandfec: true } };
}

/** Picks codecs the other side can decode, then starts sending. */
function setupEncoders(c) {
  const r = c.relay;
  if (r.audioStarted) return;
  r.audioStarted = true;
  const useOpus = r.opusOk && r.peerCaps.opus;
  let ts = 0;
  if (useOpus) {
    r.aenc = new AudioEncoder({ output: (chunk) => sendPacket(c, PKT.AUDIO, AC.OPUS, chunk), error: () => {} });
    r.aenc.configure(opusConfig(r.rate));
  }
  r.cap.port.onmessage = (e) => {
    if (call !== c || !c.micOn || !S.ws || S.ws.bufferedAmount > 256000) return; // muted, or the line is badly backed up
    if (useOpus) {
      r.aenc.encode(new AudioData({ format: 'f32-planar', sampleRate: r.rate, numberOfFrames: e.data.length, numberOfChannels: 1, timestamp: ts, data: e.data }));
      ts += Math.round((e.data.length / r.rate) * 1e6);
    } else {
      const pcm = resample(e.data, r.rate, 8000), bytes = new Uint8Array(pcm.length);
      for (let i = 0; i < pcm.length; i++) bytes[i] = mulawEncode(pcm[i]);
      sendPacket(c, PKT.AUDIO, AC.MULAW, bytes);
    }
  };
  if (c.video && window.VideoEncoder) startVideoEncoder(c, (r.peerCaps.video || []).filter((id) => VCODEC[id])).catch(() => {});
  c.timers.push(setInterval(() => { if (call === c) sendPacket(c, PKT.PING, 0, new Uint8Array(0), nowMs()); }, 2000));
}

/** Uses the first codec (in the other side's order) that this browser can also encode. */
async function startVideoEncoder(c, codecs) {
  const r = c.relay;
  let codec = 0, config = null;
  for (const id of codecs) {
    const cfg = { codec: VCODEC[id], width: CALL.W, height: CALL.H, bitrate: CALL.VIDEO_BPS, framerate: CALL.FPS, latencyMode: 'realtime' };
    if (id === VC.AVC) cfg.avc = { format: 'annexb' };
    if (await VideoEncoder.isConfigSupported(cfg).then((x) => x.supported, () => false)) { codec = id; config = cfg; break; }
  }
  if (!config || call !== c) { if (call === c) toast('video cannot be relayed between these browsers; voice only'); return; }
  r.venc = new VideoEncoder({ output: (chunk) => sendPacket(c, PKT.VIDEO, codec | (chunk.type === 'key' ? 0x80 : 0), chunk), error: () => {} });
  r.venc.configure(config);
  const canvas = h('canvas', { width: CALL.W, height: CALL.H }), g = canvas.getContext('2d');
  const local = $('callLocal');
  c.timers.push(setInterval(() => {
    if (call !== c || !c.camOn || local.readyState < 2) return;
    if (r.venc.encodeQueueSize > 1 || !S.ws || S.ws.bufferedAmount > 48000) { r.forceKey = true; return; } // congested: skip a frame
    g.drawImage(local, 0, 0, CALL.W, CALL.H);
    const vf = new VideoFrame(canvas, { timestamp: performance.now() * 1000 });
    const keyFrame = r.forceKey || r.frames++ % CALL.KEY_EVERY === 0;
    r.forceKey = false;
    r.venc.encode(vf, { keyFrame });
    vf.close();
  }, Math.round(1000 / CALL.FPS)));
}

/** Packet: [type][codec/flags][4-byte time] + data, then encrypted. Sent in order. */
function sendPacket(c, type, flags, data, time) {
  const len = data.byteLength;
  const plain = new Uint8Array(6 + len);
  plain[0] = type; plain[1] = flags;
  new DataView(plain.buffer).setUint32(2, time == null ? nowMs() : time);
  if (data.copyTo) data.copyTo(plain.subarray(6)); else plain.set(data, 6);
  const ctr = ++c.sendCtr;
  c.sendChain = c.sendChain.then(() => seal(c, dirOf(c, true, false), ctr, plain)).then((sealed) => {
    if (call === c && S.ws && S.ws.readyState === 1) S.ws.send(frame(OP.CALL_MEDIA, 0, sealed));
  }).catch(() => {});
}

function onCallMedia(bytes) {
  const c = call;
  if (!c || !c.key || !c.relay) return;
  const copy = bytes.slice();
  c.recvChain = c.recvChain.then(async () => {
    const { ctr, plain } = await unseal(c, copy, dirOf(c, false, false), c.recvCtr);
    c.recvCtr = ctr;
    onPacket(c, plain);
  }).catch(() => {});
}

function onPacket(c, p) {
  const r = c.relay, type = p[0], flags = p[1], time = new DataView(p.buffer).getUint32(2), data = p.subarray(6);
  if (type === PKT.PING) { sendPacket(c, PKT.PONG, 0, new Uint8Array(0), time); return; }
  if (type === PKT.PONG) { showPing(u32(nowMs() - time)); return; }
  if (type === PKT.AUDIO && flags === AC.OPUS && window.AudioDecoder) {
    if (!r.adec) {
      r.adec = new AudioDecoder({ output: (ad) => {
        const buf = new Float32Array(ad.numberOfFrames);
        ad.copyTo(buf, { planeIndex: 0, format: 'f32-planar' });
        const rate = ad.sampleRate; ad.close();
        const out = rate === r.rate ? buf : resample(buf, rate, r.rate);
        r.play.port.postMessage(out, [out.buffer]);
      }, error: () => { r.adec = null; } });
      r.adec.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 });
    }
    r.adec.decode(new EncodedAudioChunk({ type: 'key', timestamp: time * 1000, data }));
  } else if (type === PKT.AUDIO && flags === AC.MULAW) {
    const pcm = new Float32Array(data.length);
    for (let i = 0; i < data.length; i++) pcm[i] = mulawDecode(data[i]);
    const out = resample(pcm, 8000, r.rate);
    r.play.port.postMessage(out, [out.buffer]);
  } else if (type === PKT.VIDEO && window.VideoDecoder) {
    const codec = flags & 0x7f, key = !!(flags & 0x80);
    if (!r.vdec || r.vcodec !== codec) {
      if (!key) { askKeyframe(c); return; }
      if (r.vdec) r.vdec.close();
      r.vcodec = codec;
      r.vdec = new VideoDecoder({ output: drawRemote, error: () => { r.vdec = null; askKeyframe(c); } });
      r.vdec.configure({ codec: VCODEC[codec], optimizeForLatency: true });
    }
    if (r.vdec.decodeQueueSize > 3 && !key) return; // behind: wait for the next key frame
    r.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: time * 1000, data }));
  }
}

function askKeyframe(c) {
  const r = c.relay;
  if (Date.now() - r.lastKfAsk < 2000) return;
  r.lastKfAsk = Date.now();
  sendSignal(c, 'relay', { keyframe: true }).catch(() => {});
}

function drawRemote(vf) {
  const canvas = $('callCanvas');
  if (canvas.width !== vf.displayWidth) { canvas.width = vf.displayWidth; canvas.height = vf.displayHeight; }
  canvas.getContext('2d').drawImage(vf, 0, 0);
  vf.close();
  canvas.hidden = false; $('callRemote').hidden = true;
}

function stopRelay(c) {
  const r = c.relay;
  if (!r) return;
  for (const codec of [r.aenc, r.adec, r.venc, r.vdec]) { try { if (codec && codec.state !== 'closed') codec.close(); } catch { /* already closed */ } }
  if (r.actx) r.actx.close().catch(() => {});
  c.relay = null;
}

function resample(input, from, to) {
  if (from === to) return input;
  const out = new Float32Array(Math.round((input.length * to) / from)), step = from / to;
  for (let i = 0; i < out.length; i++) {
    const x = i * step, j = Math.floor(x), t = x - j;
    if (step > 1) { // downsampling: average the span as a simple low-pass filter
      let sum = 0, n = 0;
      for (let k = j; k < Math.min(input.length, Math.floor(x + step)); k++) { sum += input[k]; n++; }
      out[i] = n ? sum / n : 0;
    } else {
      out[i] = (input[j] || 0) * (1 - t) + (input[j + 1] || input[j] || 0) * t;
    }
  }
  return out;
}

/** G.711 mu-law: the fallback voice codec for browsers without an Opus encoder (64 kbit/s). */
function mulawEncode(x) {
  let s = Math.round(Math.max(-1, Math.min(1, x)) * 32635);
  const sign = s < 0 ? 0x80 : 0;
  if (sign) s = -s;
  s += 0x84;
  let exp = 7;
  for (let mask = 0x4000; (s & mask) === 0 && exp > 0; mask >>= 1) exp--;
  return ~(sign | (exp << 4) | ((s >> (exp + 3)) & 0x0f)) & 0xff;
}

function mulawDecode(b) {
  b = ~b & 0xff;
  const exp = (b >> 4) & 7, s = ((((b & 0x0f) << 3) + 0x84) << exp) - 0x84;
  return (b & 0x80 ? -s : s) / 32768;
}

// ---- screen ----
async function getMedia(video) {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('calls need HTTPS and a modern browser');
  try {
    call.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
      video: video ? { width: { ideal: CALL.W }, height: { ideal: CALL.H }, frameRate: { ideal: CALL.FPS, max: CALL.FPS }, facingMode: 'user' } : false,
    });
  } catch (e) {
    throw new Error(e.name === 'NotAllowedError' ? 'allow the microphone' + (video ? ' and camera' : '') + ' to call' : 'no microphone found');
  }
  $('callLocal').srcObject = call.stream;
  $('callLocal').hidden = !video;
}

function showCall(text, incoming) {
  $('callTitle').textContent = '▣ ' + name(call.peer);
  $('callAccept').hidden = !incoming;
  $('callCam').hidden = !call.video;
  $('callStage').hidden = !call.video;
  $('callRemote').hidden = false; $('callCanvas').hidden = true;
  $('callCode').textContent = '';
  $('callInfo').textContent = '';
  $('callMic').textContent = 'MIC ON'; $('callCam').textContent = 'CAM ON';
  setCallStatus(text);
  $('callBox').hidden = false;
  (incoming ? $('callAccept') : $('callEnd')).focus();
}

function setCallStatus(text) { $('callStatus').textContent = text; }

function connected(c) {
  if (call !== c) return;
  if (!c.startedAt) {
    c.startedAt = Date.now();
    c.timers.push(setInterval(() => { if (call === c) setCallStatus(clock(Date.now() - c.startedAt)); }, 1000));
    c.timers.push(setInterval(() => directPing(c), 2000));
  }
  c.state = 'active';
  setCallStatus(clock(0));
  $('callInfo').textContent = c.mode === 'direct' ? 'direct' : 'relay · hidden IP';
}

async function directPing(c) {
  if (call !== c || c.mode !== 'direct' || !c.pc) return;
  const stats = await c.pc.getStats();
  stats.forEach((s) => { if (s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded' && s.currentRoundTripTime != null) showPing(Math.round(s.currentRoundTripTime * 1000)); });
}

function showPing(ms) {
  if (!call) return;
  $('callInfo').textContent = (call.mode === 'direct' ? 'direct' : 'relay · hidden IP') + ' · ' + ms + ' ms';
}

/** A short beep pattern made on the fly, so no sound file is downloaded. */
function ringTone(on, incoming) {
  clearInterval(ringTone.timer);
  if (ringTone.ctx) { ringTone.ctx.close().catch(() => {}); ringTone.ctx = null; }
  if (!on || S.myStatus === 'dnd') return;
  const beep = () => {
    try {
      const ctx = ringTone.ctx || (ringTone.ctx = new AudioContext());
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.frequency.value = incoming ? 660 : 440; g.gain.value = 0.06;
      o.connect(g).connect(ctx.destination); o.start(); o.stop(ctx.currentTime + (incoming ? 0.4 : 0.9));
    } catch { /* audio blocked until the user taps the page */ }
  };
  beep();
  ringTone.timer = setInterval(beep, incoming ? 1500 : 3000);
}

async function toggleCam() {
  const c = call;
  if (!c || !c.video) return;
  c.camOn = !c.camOn;
  $('callCam').textContent = c.camOn ? 'CAM ON' : 'CAM OFF';
  const old = c.stream.getVideoTracks()[0];
  const sender = c.pc && c.videoSender;
  if (!c.camOn) {
    if (sender) await sender.replaceTrack(null);
    if (old) { old.stop(); c.stream.removeTrack(old); } // the camera light goes off
    return;
  }
  try {
    const fresh = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: CALL.W }, height: { ideal: CALL.H }, frameRate: { ideal: CALL.FPS, max: CALL.FPS }, facingMode: 'user' } });
    const track = fresh.getVideoTracks()[0];
    c.stream.addTrack(track);
    $('callLocal').srcObject = c.stream;
    if (sender) await sender.replaceTrack(track);
    if (c.relay) c.relay.forceKey = true;
  } catch { c.camOn = false; $('callCam').textContent = 'CAM OFF'; toast('camera unavailable'); }
}

function toggleMic() {
  if (!call) return;
  call.micOn = !call.micOn;
  call.stream.getAudioTracks().forEach((t) => { t.enabled = call.micOn; });
  $('callMic').textContent = call.micOn ? 'MIC ON' : 'MUTED';
}

function renderHideIp() { $('hideIpBtn').textContent = '🛡 HIDE MY IP IN CALLS: ' + (callPrefs.hideIp ? 'ON' : 'OFF'); }

$('callBtn').onclick = () => startCall(false);
$('videoBtn').onclick = () => startCall(true);
$('callAccept').onclick = acceptCall;
$('callEnd').onclick = hangUp;
$('callMic').onclick = toggleMic;
$('callCam').onclick = toggleCam;
$('hideIpBtn').onclick = () => { callPrefs.hideIp = !callPrefs.hideIp; store.set('ember.hideIp', callPrefs.hideIp ? '1' : null); renderHideIp(); };
renderHideIp();
addEventListener('pagehide', hangUp);
