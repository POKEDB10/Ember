'use strict';
// Ember voice and video calls: one-to-one and group (up to 8 people). Loaded after app.js and
// e2e.js and uses their helpers.
//
// How a call connects:
//   1. Ringing, joining and leaving go through the Ember server, with the same rules as messages.
//   2. Every pair of people in the call makes fresh keys (ECDH P-256) and swaps public keys. From
//      then on every call message between them is encrypted with a key the server never learns,
//      including network addresses. Each pair sees a safety code; matching codes mean nobody (not
//      even the server) is in the middle.
//   3. Direct first: WebRTC peer-to-peer links (lowest delay, DTLS-SRTP), for calls of up to 4.
//   4. Relay otherwise (bigger calls, "Hide my IP", the desktop app, or a network that blocks
//      peer-to-peer and VoIP): each person encodes once (Opus or G.711 voice, VP8/H.264 video),
//      encrypts each packet once with their own sender key (shared with the others over the
//      encrypted pair channels) and sends it inside the normal HTTPS WebSocket; the server copies it
//      to everyone else without being able to read it. When someone leaves, the keys change.
// Quality: fullband Opus voice (48 kHz) and up to 720p / 30 fps video, adapting to the connection:
// direct links use the browser's congestion control; the relay climbs a quality ladder while the
// connection keeps up and steps down at once when it does not. Silence costs almost nothing (DTX).

const CALL = { AUDIO_BPS: 32000, W: 1280, H: 720, FPS: 30, DIRECT_WAIT_MS: 6000, KEY_SECONDS: 4, MAX_DIRECT: 4 };
/** Direct video bitrate cap per link, by how many people are in the call (each link uploads separately). */
const DIRECT_VIDEO_BPS = (people) => (people <= 2 ? 2500000 : people === 3 ? 1200000 : 800000);
const PKT = { AUDIO: 1, VIDEO: 2, PING: 3, PONG: 4 };
const VC = { VP8: 1, AVC: 2 };
const VCODEC = { [VC.VP8]: 'vp8', [VC.AVC]: 'avc1.42001f' };
let call = null;

/** Per-device call settings: Hide my IP, quality mode (Auto / Max / Data saver), and the calls v2 switch. */
const callPrefs = { hideIp: store.get('ember.hideIp') === '1', mode: store.get('ember.quality') || 'auto', v2: store.get('ember.callsV2') !== '0' };
const u32 = (n) => n >>> 0;
const nowMs = () => u32(Math.round(performance.now()));
const b64 = (bytes) => b64url(bytes);
const unb64 = (text) => urlKey(text);

// ---- starting, answering, ending ----
async function startCall(video) {
  const c = S.convs.get(S.active);
  if (!c || call) return;
  const group = c.id.startsWith('grp:');
  call = newCall({ conv: c.id, group, video, role: 'caller', title: group ? title(c) : name(peer(c)) });
  showCall((group ? 'calling ' : 'calling ') + call.title + '…');
  try {
    await getMedia(video);
    if (!call) return;
    const f = await request(OP.CALL, { kind: 'ring', convId: c.id, video: String(video) });
    if (!call) return;
    call.id = f.callId;
    ringTone(true, false);
  } catch (e) { endLocal(e.message === 'offline' ? 'you are offline' : e.message); }
}

function newCall(fields) {
  const c = { id: '', state: 'ringing', mode: '', stream: null, peers: new Map(), byTag: new Map(), timers: [],
    micOn: true, camOn: fields.video, relay: null, startedAt: 0, ...fields };
  c.v2 = !!(S.callsV2 && callPrefs.v2 && !c.group); // calls v2: server and this device both on; 1:1 only
  return c;
}

async function onRing(f) {
  if (call) { send(OP.CALL, { kind: 'decline', callId: f.callId }); return; } // already on a call
  const group = f.group === 'true';
  await ensureNames([f.from]);
  call = newCall({ id: f.callId, conv: f.convId, video: f.video === 'true', role: 'callee', group,
    title: group ? title2(f.convId) : name(f.from) });
  const what = (call.video ? 'video' : 'voice') + (group ? ' group call · ' + call.title : ' call');
  showCall(name(f.from) + ' · ' + what, true);
  ringTone(true, true);
  if (document.hidden) systemNotify(name(f.from), (call.video ? '🎥 incoming ' : '📞 incoming ') + what, f.convId);
}

async function acceptCall() {
  if (!call || call.role !== 'callee' || call.state !== 'ringing') return;
  ringTone(false);
  $('callAccept').hidden = true;
  setCallStatus('connecting…');
  const c = call;
  try {
    await getMedia(c.video);
    if (call !== c) return;
    c.state = 'connecting';
    const f = await request(OP.CALL, { kind: 'accept', callId: c.id });
    if (call !== c) return;
    for (let i = 0; i < +f.n; i++) addPeer(c, f['user.' + i]);
    renderPeople();
  } catch (e) { if (call === c) { endLocal('⚠ ' + e.message); } }
}

function hangUp() {
  if (!call) return;
  if (call.id) send(OP.CALL, { kind: call.state === 'ringing' && call.role === 'callee' ? 'decline' : 'end', callId: call.id });
  endLocal('call ended');
}

function reasonText(r) {
  return { declined: 'call declined', noanswer: 'no answer', missed: 'missed call', elsewhere: 'answered on another device',
    ended: 'call ended' }[r] || 'call ended';
}

/** Frees everything the call held: camera, microphone, connections, codecs, timers. */
function endLocal(text) {
  const c = call;
  call = null;
  if (!c) return;
  ringTone(false);
  c.timers.forEach((t) => clearInterval(t));
  if (c.stream) c.stream.getTracks().forEach((t) => t.stop());
  for (const p of c.peers.values()) closePeer(p);
  stopRelay(c);
  $('callLocal').srcObject = null;
  $('callTiles').replaceChildren();
  $('callBox').hidden = true;
  if (text) toast(text + (c.startedAt ? ' · ' + clock(Date.now() - c.startedAt) : ''));
}

const clock = (ms) => Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0');

// ---- events from the server ----
function onCallEvent(f) {
  if (f.kind === 'ring') { onRing(f); return; }
  const c = call;
  if (!c || f.callId !== c.id) return;
  if (f.kind === 'end') { endLocal(reasonText(f.reason)); return; }
  if (f.kind === 'joined') {
    ringTone(false);
    if (c.state === 'ringing') { c.state = 'connecting'; setCallStatus('connecting…'); }
    addPeer(c, f.userId);
    if (c.group) toast(name(f.userId) + ' joined');
    renderPeople();
    return;
  }
  if (f.kind === 'left') {
    const p = c.peers.get(f.userId);
    if (p) { closePeer(p); c.peers.delete(f.userId); }
    toast(name(f.userId) + ' left');
    if (c.mode === 'relay') rotateSenderKey(c); // the person who left can no longer read what follows
    renderPeople();
    return;
  }
  const p = c.peers.get(f.from) || addPeer(c, f.from);
  p.chain = p.chain.then(() => onSignal(c, p, f.kind, f.data)).catch(() => {
    if (call === c) { toast('⚠ could not secure the call with ' + name(p.user)); dropPeer(c, p); }
  });
}

async function onSignal(c, p, kind, data) {
  if (call !== c) return;
  if (kind === 'key') { await derivePairKey(c, p, data); return; }
  await p.keyReady;
  const msg = await openSignal(p, data);
  if (call !== c) return;
  if (kind === 'sdp') await onSdp(c, p, msg);
  else if (kind === 'ice') {
    if (p.pc && p.pc.remoteDescription) await p.pc.addIceCandidate(msg).catch(() => {});
    else p.iceQueue.push(msg);
  } else if (kind === 'relay') await onRelaySignal(c, p, msg);
}

// ---- people in the call ----
/** A person in the call: our pair keys, our connection to them, and their tile on screen. */
function addPeer(c, user) {
  if (!user || user === S.me) return null;
  if (c.peers.has(user)) return c.peers.get(user);
  let done;
  const p = { user, pair: crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']),
    myPub: null, key: null, keyReady: new Promise((r) => { done = r; }), sigSend: 0, sigRecv: -1, chain: Promise.resolve(),
    pc: null, iceQueue: [], videoSender: null, code: '', relay: null, sentRelay: false, tile: tile(user) };
  p.keyDone = done;
  c.peers.set(user, p);
  sendPairKey(c, p);
  return p;
}

function tile(user) {
  const video = h('video', { autoplay: true, playsInline: true });
  const canvas = h('canvas', { hidden: true });
  const el = h('div', { class: 'tile' }, video, canvas, h('span', { class: 'who' }, name(user)));
  $('callTiles').append(el);
  return { el, video, canvas };
}

function closePeer(p) {
  if (p.pc) { p.pc.close(); p.pc = null; }
  const r = p.relay;
  if (r) for (const codec of [r.adec, r.vdec]) { try { if (codec && codec.state !== 'closed') codec.close(); } catch { /* closed */ } }
  if (r && r.play) r.play.disconnect();
  p.tile.video.srcObject = null;
  p.tile.el.remove();
}

function dropPeer(c, p) { closePeer(p); c.peers.delete(p.user); renderPeople(); }

function renderPeople() {
  if (!call) return;
  for (const p of call.peers.values()) if (p.pc && p.pc.connectionState === 'connected') capBitrates(call, p.pc);
  const n = call.peers.size + 1;
  $('callStage').classList.toggle('many', n > 2);
  $('callCode').replaceChildren(...[...call.peers.values()].filter((p) => p.code).map((p) =>
    h('div', {}, 'safety code with ' + name(p.user) + ': ' + p.code)));
  if (call.peers.size) $('callCode').append(h('div', {}, 'same codes on both screens = private call'));
  if (call.group) $('callTitle').textContent = '▣ ' + call.title + ' · ' + n + ' in call';
}

// ---- pair keys (one per pair of people) and the safety codes ----
async function sendPairKey(c, p) {
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', (await p.pair).publicKey));
  p.myPub = raw;
  if (call === c) send(OP.CALL, { kind: 'key', callId: c.id, to: p.user, data: b64(raw) });
}

async function derivePairKey(c, p, data) {
  if (p.key) return; // one key per pair: a second "key" message is ignored
  const theirs = unb64(data);
  const pub = await crypto.subtle.importKey('raw', theirs, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const pair = await p.pair;
  while (!p.myPub) await new Promise((r) => setTimeout(r, 10));
  const secret = await crypto.subtle.deriveBits({ name: 'ECDH', public: pub }, pair.privateKey, 256);
  const hkdf = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  const salt = utf8.encode(c.id + '|' + [S.me, p.user].sort().join('|'));
  p.key = await crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: utf8.encode('ember-call-v2') },
    hkdf, { name: 'AES-GCM', length: 128 }, false, ['encrypt', 'decrypt']);
  const [a, b] = [p.myPub, theirs].sort((x, y) => (b64(x) < b64(y) ? -1 : 1));
  const both = new Uint8Array(a.length + b.length); both.set(a); both.set(b, a.length);
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', both));
  let digits = '';
  for (let i = 0; i < 6; i++) digits += String(hash[i] % 100).padStart(2, '0');
  p.code = digits.replace(/(\d{4})(?=\d)/g, '$1 ');
  p.keyDone();
  renderPeople();
  if (call === c) pairReady(c, p);
}

/** Pair signals: the person with the smaller name uses direction 2, the other 3; a bounced packet is refused. */
const sigDir = (p, mine) => ((S.me < p.user) === mine ? 2 : 3);

async function seal(key, head, plain) {
  const iv = new Uint8Array(12); iv.set(head);
  const body = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: head }, key, plain));
  const out = new Uint8Array(head.length + body.length); out.set(head); out.set(body, head.length);
  return out;
}

async function sendSignal(c, p, kind, obj) {
  await p.keyReady;
  const head = new Uint8Array(5); head[0] = sigDir(p, true); new DataView(head.buffer).setUint32(1, ++p.sigSend);
  const sealed = await seal(p.key, head, utf8.encode(JSON.stringify(obj)));
  if (call === c) send(OP.CALL, { kind, callId: c.id, to: p.user, data: b64(sealed) });
}

async function openSignal(p, data) {
  const bytes = unb64(data);
  if (bytes.length < 21 || bytes[0] !== sigDir(p, false)) throw new Error('bad signal');
  const ctr = new DataView(bytes.buffer, bytes.byteOffset, 5).getUint32(1);
  if (ctr <= p.sigRecv) throw new Error('replayed signal');
  const iv = new Uint8Array(12); iv.set(bytes.subarray(0, 5));
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: bytes.subarray(0, 5) }, p.key, bytes.subarray(5));
  p.sigRecv = ctr;
  return JSON.parse(unutf8.decode(new Uint8Array(plain)));
}

// ---- choosing direct or relay ----
function pairReady(c, p) {
  if (c.mode === 'relay') { c.relayStarted.then(() => sendRelayInfo(c, p)); return; }
  if (callPrefs.hideIp || !window.RTCPeerConnection || c.peers.size + 1 > CALL.MAX_DIRECT) { goRelay(c); return; }
  if (S.me < p.user) startDirect(c, p).catch(() => goRelay(c));
  c.timers.push(setTimeout(() => { if (call === c && c.mode !== 'relay' && (!p.pc || p.pc.connectionState !== 'connected')) goRelay(c); }, CALL.DIRECT_WAIT_MS));
}

// ---- direct (peer-to-peer WebRTC): lowest delay ----
async function startDirect(c, p) {
  const pc = newPeerConnection(c, p);
  const offer = await pc.createOffer();
  await pc.setLocalDescription({ type: offer.type, sdp: thinOpus(offer.sdp, opusBps(c)) });
  await sendSignal(c, p, 'sdp', { type: offer.type, sdp: pc.localDescription.sdp });
}

function newPeerConnection(c, p) {
  const pc = new RTCPeerConnection({ iceServers: S.ice || [], bundlePolicy: 'max-bundle', rtcpMuxPolicy: 'require' });
  p.pc = pc;
  for (const t of c.stream.getTracks()) {
    const sender = pc.addTrack(t, c.stream);
    if (t.kind === 'video') p.videoSender = sender;
  }
  pc.onicecandidate = (e) => { if (e.candidate && call === c) sendSignal(c, p, 'ice', e.candidate.toJSON()).catch(() => {}); };
  pc.ontrack = (e) => { p.tile.video.srcObject = e.streams[0]; };
  pc.onconnectionstatechange = () => {
    if (call !== c || p.pc !== pc) return;
    if (pc.connectionState === 'connected') {
      if (c.mode !== 'relay') c.mode = 'direct';
      connected(c); capBitrates(c, pc);
    } else if (pc.connectionState === 'failed') {
      goRelay(c);
    } else if (pc.connectionState === 'disconnected') {
      c.timers.push(setTimeout(() => { if (call === c && p.pc === pc && pc.connectionState !== 'connected') goRelay(c); }, 3000));
    }
  };
  return pc;
}

async function onSdp(c, p, desc) {
  if (c.mode === 'relay') return;
  if (desc.type === 'offer') {
    if (callPrefs.hideIp) { goRelay(c); return; }
    const pc = p.pc || newPeerConnection(c, p);
    await pc.setRemoteDescription(desc);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription({ type: answer.type, sdp: thinOpus(answer.sdp, opusBps(c)) });
    await sendSignal(c, p, 'sdp', { type: answer.type, sdp: pc.localDescription.sdp });
  } else if (p.pc) {
    await p.pc.setRemoteDescription(desc);
  }
  for (const cand of p.iceQueue.splice(0)) if (p.pc) await p.pc.addIceCandidate(cand).catch(() => {});
}

/** Opus for voice: fullband mono, silence suppression (DTX), loss recovery (FEC) for UDP, 40 kbit/s ceiling. */
function thinOpus(sdp, bps) {
  const m = sdp.match(/a=rtpmap:(\d+) opus\/48000/i);
  if (!m) return sdp;
  const extra = 'usedtx=1;useinbandfec=1;stereo=0;sprop-stereo=0;maxplaybackrate=48000;maxaveragebitrate=' + bps;
  const re = new RegExp('a=fmtp:' + m[1] + ' ([^\\r\\n]*)');
  return re.test(sdp) ? sdp.replace(re, (_, x) => 'a=fmtp:' + m[1] + ' ' + x.split(';').filter((y) => !/^(usedtx|useinbandfec|stereo|sprop-stereo|maxaveragebitrate)=/.test(y)).concat(extra).join(';'))
    : sdp.replace(m[0], m[0] + '\r\na=fmtp:' + m[1] + ' ' + extra);
}

/** Caps each link: voice first (high priority), video shared out by call size; the browser adapts below that. */
function capBitrates(c, pc) {
  for (const sender of pc.getSenders()) {
    if (!sender.track) continue;
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    const video = sender.track.kind === 'video';
    params.encodings[0].maxBitrate = video ? DIRECT_VIDEO_BPS(c.peers.size + 1) : opusBps(c) + (c.v2 ? 0 : 8000);
    params.encodings[0].priority = params.encodings[0].networkPriority = video ? 'low' : 'high';
    if (video) { params.encodings[0].maxFramerate = CALL.FPS; params.degradationPreference = 'balanced'; }
    sender.setParameters(params).catch(() => {});
  }
}

// ---- relay (inside the HTTPS WebSocket): works where peer-to-peer and VoIP are blocked ----
/** Switches the whole call to the relay and tells everyone (each over their own encrypted channel). */
function goRelay(c) {
  if (call !== c || c.mode === 'relay') return;
  c.mode = 'relay';
  for (const p of c.peers.values()) { if (p.pc) { p.pc.close(); p.pc = null; } p.tile.video.srcObject = null; }
  let ready;
  c.relayStarted = new Promise((r) => { ready = r; });
  startRelay(c).then(() => {
    ready();
    for (const p of c.peers.values()) if (p.key) sendRelayInfo(c, p);
  }).catch(() => { if (call === c) { hangUp(); toast('⚠ this browser cannot relay calls'); } });
}

/** Tells one person our sender key (for our packets) and which codecs we can decode. */
function sendRelayInfo(c, p) {
  const r = c.relay;
  if (!r || call !== c) return;
  p.sentRelay = true;
  const info = { key: b64(r.sendKeyRaw), tag: b64(r.sendTag), opus: r.opusOk, adpcm: true, video: r.decodable };
  if (c.v2) { info.v = 2; info.codecs = (r.opusOk ? [AC.OPUS] : []).concat([AC.ADPCM32, AC.ADPCM, AC.MULAW]); }
  sendSignal(c, p, 'relay', info).catch(() => {});
}

async function onRelaySignal(c, p, msg) {
  if (msg.keyframe) { if (c.relay) c.relay.forceKey = true; return; }
  if (c.mode !== 'relay') goRelay(c);
  await c.relayStarted;
  if (call !== c || !c.relay) return;
  if (!p.sentRelay) sendRelayInfo(c, p);
  if (p.relay && p.relay.tag) c.byTag.delete(p.relay.tag);
  const key = await crypto.subtle.importKey('raw', unb64(msg.key), 'AES-GCM', false, ['decrypt']);
  p.relay = { ...(p.relay || {}), key, tag: msg.tag, lastCtr: -1, opus: !!msg.opus, adpcm: !!msg.adpcm, decodes: peerDecodes(msg), video: msg.video || [], lastKfAsk: 0, chain: Promise.resolve() };
  c.byTag.set(msg.tag, p);
  chooseCodecs(c);
  connected(c);
}

async function startRelay(c) {
  const r = c.relay = { sendKeyRaw: null, sendKey: null, sendTag: null, sendCtr: 0, sendChain: Promise.resolve(), forceKey: true, frames: 0 };
  await newSenderKey(r);
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
  r.cap = new AudioWorkletNode(actx, 'ember-capture');
  actx.createMediaStreamSource(c.stream).connect(r.cap);
  r.cap.connect(actx.destination); // the capture node outputs silence; connecting keeps it running
  r.cap.port.onmessage = (e) => sendAudio(c, e.data);
  if (actx.state === 'suspended') await actx.resume().catch(() => {});
  c.timers.push(setInterval(() => { if (call === c && c.relay) sendPacket(c, PKT.PING, 0, new Uint8Array(0)); }, 2000));
}

async function newSenderKey(r) {
  r.sendKeyRaw = rand(16);
  r.sendTag = rand(4);
  r.sendKey = await crypto.subtle.importKey('raw', r.sendKeyRaw, 'AES-GCM', false, ['encrypt']);
  r.sendCtr = 0;
  r.forceKey = true;
}

/** After someone leaves: a new sender key, given only to the people still in the call. */
async function rotateSenderKey(c) {
  if (!c.relay) return;
  await newSenderKey(c.relay);
  for (const p of c.peers.values()) if (p.key) sendRelayInfo(c, p);
}

/** The relay runs over TCP, which never loses packets, so loss-recovery (FEC) bits go to the voice itself. */
function opusConfig(rate, bps = CALL.AUDIO_BPS) {
  return { codec: 'opus', sampleRate: rate, numberOfChannels: 1, bitrate: bps, opus: { frameDuration: 20000, usedtx: true } };
}

/** Opus bitrate for this call: the quality mode's (calls v2) or the classic 32 kbit/s. */
function opusBps(c) {
  return c && c.v2 ? qualityFor(callPrefs.mode).opus : CALL.AUDIO_BPS + 8000;
}

/** Calls v2: a mode change mid-call re-applies the voice bitrate at once (video limits follow in P2 code). */
function applyMode(c) {
  if (!c || !c.v2) return;
  const r = c.relay;
  if (r && r.aenc && r.audioCodec === AC.OPUS) r.aenc.configure(opusConfig(r.rate, qualityFor(callPrefs.mode).opus));
  for (const p of c.peers.values()) if (p.pc && p.pc.connectionState === 'connected') capBitrates(c, p.pc);
}

/** One encoding for everyone: codecs every listener can decode (re-chosen when people change). */
function chooseCodecs(c) {
  const r = c.relay;
  const listeners = [...c.peers.values()].filter((p) => p.relay && p.relay.key);
  if (!listeners.length) return;
  // Calls v2: chosen once at connect from what the other side announced. Classic: Opus when
  // everyone has it; else wideband ADPCM (the desktop app); else narrowband G.711 (re-chosen as people change).
  const audio = c.v2 ? (r.audioCodec || pickVoiceCodec(callPrefs.mode, r.opusOk, listeners[0].relay.decodes))
    : r.opusOk && listeners.every((p) => p.relay.opus) ? AC.OPUS
      : listeners.every((p) => p.relay.adpcm) ? AC.ADPCM : AC.MULAW;
  if (!audio) toast('⚠ no shared voice codec with this device');
  if (audio && audio !== r.audioCodec) {
    if (r.aenc) { try { r.aenc.close(); } catch { /* closed */ } r.aenc = null; }
    r.audioCodec = audio; r.ats = 0;
    if (audio === AC.OPUS) {
      r.aenc = new AudioEncoder({ output: (chunk) => sendPacket(c, PKT.AUDIO, AC.OPUS, chunk), error: () => {} });
      r.aenc.configure(opusConfig(r.rate, c.v2 ? qualityFor(callPrefs.mode).opus : CALL.AUDIO_BPS));
    }
  }
  const video = c.video && window.VideoEncoder ? [VC.VP8, VC.AVC].find((id) => listeners.every((p) => p.relay.video.includes(id))) : 0;
  if (video !== r.videoCodec) {
    r.videoCodec = video;
    startVideoEncoder(c, video).catch(() => {});
  }
}

function sendAudio(c, pcm) {
  const r = c.relay;
  if (call !== c || !r || !r.audioCodec || !c.micOn || !S.ws || S.ws.bufferedAmount > 512000) return; // muted or badly backed up
  if (r.audioCodec === AC.OPUS && r.aenc) {
    r.aenc.encode(new AudioData({ format: 'f32-planar', sampleRate: r.rate, numberOfFrames: pcm.length, numberOfChannels: 1, timestamp: r.ats, data: pcm }));
    r.ats += Math.round((pcm.length / r.rate) * 1e6);
  } else if (r.audioCodec === AC.ADPCM || r.audioCodec === AC.ADPCM32) {
    const rate = r.audioCodec === AC.ADPCM32 ? 32000 : 16000;
    // Calls v2 resamples with a real anti-aliasing filter; classic calls keep the simple averaging.
    if (c.v2 && (!r.down || r.downRate !== rate)) { r.down = makeResampler(r.rate, rate); r.downRate = rate; r.downPending = new Float32Array(0); }
    if (c.v2) {
      const fresh = r.down(pcm), joined = new Float32Array(r.downPending.length + fresh.length);
      joined.set(r.downPending); joined.set(fresh, r.downPending.length);
      let at = 0;
      for (; joined.length - at >= rate / 50; at += rate / 50) {
        sendPacket(c, PKT.AUDIO, r.audioCodec, adpcmEncode(joined.subarray(at, at + rate / 50), r.adpcm || (r.adpcm = { index: 0 })));
      }
      r.downPending = joined.slice(at);
      return;
    }
    const wide = resample(pcm, r.rate, 16000);
    sendPacket(c, PKT.AUDIO, AC.ADPCM, adpcmEncode(wide, r.adpcm || (r.adpcm = { index: 0 })));
  } else if (r.audioCodec === AC.MULAW) {
    const low = resample(pcm, r.rate, 8000), bytes = new Uint8Array(low.length);
    for (let i = 0; i < low.length; i++) bytes[i] = mulawEncode(low[i]);
    sendPacket(c, PKT.AUDIO, AC.MULAW, bytes);
  }
}

async function startVideoEncoder(c, codec) {
  const r = c.relay;
  if (r.venc) { try { r.venc.close(); } catch { /* closed */ } r.venc = null; }
  clearInterval(r.videoTimer); clearInterval(r.adaptTimer);
  r.vcodecId = codec;
  if (!codec) return;
  r.rung = Math.min(2, maxRung(c)); // start at 360p, then climb
  if (!(await configureVideo(c))) { toast('video cannot be relayed on this browser; voice only'); return; }
  r.adaptTimer = setInterval(() => adaptVideo(c), 2000);
  c.timers.push(r.adaptTimer);
}

/** The best rung this call can use: fewer people and a sharper camera allow more. */
function maxRung(c) {
  const people = c.peers.size + 1, camera = $('callLocal').videoHeight || CALL.H;
  let top = people <= 2 ? 4 : people === 3 ? 3 : people <= 5 ? 2 : 1;
  while (top > 0 && LADDER[top].h > camera) top--;
  return top;
}

/** (Re)configures the encoder for the current rung; returns false when no rung is supported. */
async function configureVideo(c) {
  const r = c.relay;
  for (; r.rung >= 0; r.rung--) {
    const q = LADDER[r.rung];
    const config = { codec: VCODEC[r.vcodecId], width: q.w, height: q.h, bitrate: q.bps, framerate: q.fps, latencyMode: 'realtime' };
    if (r.vcodecId === VC.AVC) config.avc = { format: 'annexb' };
    if (!(await VideoEncoder.isConfigSupported(config).then((x) => x.supported, () => false))) continue;
    if (call !== c || !c.relay) return false;
    if (!r.venc || r.venc.state === 'closed') {
      const codec = r.vcodecId;
      r.venc = new VideoEncoder({ output: (chunk) => sendPacket(c, PKT.VIDEO, codec | (chunk.type === 'key' ? 0x80 : 0), chunk), error: () => {} });
    }
    r.venc.configure(config);
    r.forceKey = true; r.skipped = 0; r.calm = 0; r.frames = 0;
    r.canvas = h('canvas', { width: q.w, height: q.h });
    r.g = r.canvas.getContext('2d', { alpha: false });
    clearInterval(r.videoTimer);
    const local = $('callLocal'), venc = r.venc;
    r.videoTimer = setInterval(() => {
      if (call !== c || r.venc !== venc || venc.state !== 'configured' || !c.camOn || local.readyState < 2) return;
      // Congested: skip this frame before encoding (the stream stays decodable, voice goes first).
      if (venc.encodeQueueSize > 1 || !S.ws || S.ws.bufferedAmount > q.bps / 32) { r.skipped++; return; }
      r.g.drawImage(local, 0, 0, q.w, q.h);
      const vf = new VideoFrame(r.canvas, { timestamp: performance.now() * 1000 });
      const keyFrame = r.forceKey || r.frames++ % (q.fps * CALL.KEY_SECONDS) === 0;
      r.forceKey = false;
      venc.encode(vf, { keyFrame });
      vf.close();
    }, Math.round(1000 / q.fps));
    c.timers.push(r.videoTimer);
    return true;
  }
  r.rung = 0;
  return false;
}

/** Every 2 s: step down at once when frames had to be skipped, step up after 6 s without trouble. */
function adaptVideo(c) {
  const r = c.relay;
  if (call !== c || !r || !r.venc) return;
  const top = maxRung(c), was = r.rung;
  if ((r.skipped > 1 && r.rung > 0) || r.rung > top) r.rung--;
  else if (r.skipped === 0 && ++r.calm >= 3 && r.rung < top) r.rung++;
  r.skipped = 0;
  if (r.rung !== was) configureVideo(c);
}

/**
 * Packet: [4-byte sender tag][4-byte counter] then, encrypted with the sender key,
 * [type][codec/flags][4-byte time] + data. Encrypted once; the server copies it to everyone.
 */
function sendPacket(c, type, flags, data, time) {
  const r = c.relay;
  if (!r) return;
  const len = data.byteLength;
  const plain = new Uint8Array(6 + len);
  plain[0] = type; plain[1] = flags;
  new DataView(plain.buffer).setUint32(2, time == null ? nowMs() : time);
  if (data.copyTo) data.copyTo(plain.subarray(6)); else plain.set(data, 6);
  const head = new Uint8Array(8); head.set(r.sendTag); new DataView(head.buffer).setUint32(4, ++r.sendCtr);
  const key = r.sendKey;
  r.sendChain = r.sendChain.then(() => seal(key, head, plain)).then((sealed) => {
    if (call === c && S.ws && S.ws.readyState === 1) S.ws.send(frame(OP.CALL_MEDIA, 0, sealed));
  }).catch(() => {});
}

/** The server stamps each packet with who sent it; a packet is used only with that person's own key. */
function onCallMedia(stamped) {
  const c = call;
  if (!c || !c.relay || !stamped.length) return;
  const sender = unutf8.decode(stamped.subarray(1, 1 + stamped[0]));
  const bytes = stamped.subarray(1 + stamped[0]);
  if (bytes.length < 24) return;
  const p = c.byTag.get(b64(bytes.subarray(0, 4)));
  if (!p || !p.relay || p.user !== sender) return;
  const copy = bytes.slice(), r = p.relay;
  r.chain = r.chain.then(async () => {
    const ctr = new DataView(copy.buffer).getUint32(4);
    if (ctr <= r.lastCtr) return; // replayed or old
    const iv = new Uint8Array(12); iv.set(copy.subarray(0, 8));
    const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: copy.subarray(0, 8) }, r.key, copy.subarray(8)));
    r.lastCtr = ctr;
    onPacket(c, p, plain);
  }).catch(() => {});
}

function onPacket(c, p, data) {
  const r = c.relay, pr = p.relay, type = data[0], flags = data[1], time = new DataView(data.buffer).getUint32(2), body = data.subarray(6);
  if (type === PKT.PING) { sendPacket(c, PKT.PONG, 0, pr.tagBytes || (pr.tagBytes = unb64(pr.tag)), time); return; }
  if (type === PKT.PONG) { if (b64(body) === b64(r.sendTag)) showPing(u32(nowMs() - time)); return; }
  if (type === PKT.AUDIO) {
    if (!pr.play) { pr.play = new AudioWorkletNode(r.actx, 'ember-play', { outputChannelCount: [1] }); pr.play.connect(r.actx.destination); }
    if (flags === AC.OPUS && window.AudioDecoder) {
      if (!pr.adec) {
        pr.adec = new AudioDecoder({ output: (ad) => {
          const buf = new Float32Array(ad.numberOfFrames);
          ad.copyTo(buf, { planeIndex: 0, format: 'f32-planar' });
          const rate = ad.sampleRate; ad.close();
          const out = rate === r.rate ? buf : resample(buf, rate, r.rate);
          pr.play.port.postMessage(out, [out.buffer]);
        }, error: () => { pr.adec = null; } });
        pr.adec.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 });
      }
      pr.adec.decode(new EncodedAudioChunk({ type: 'key', timestamp: time * 1000, data: body }));
    } else if (flags === AC.ADPCM || flags === AC.ADPCM32) {
      const rate = flags === AC.ADPCM32 ? 32000 : 16000, decoded = adpcmDecode(body);
      if (decoded.length !== rate / 50) return; // wrong size for this codec: treated as lost
      if (c.v2 && (!pr.up || pr.upRate !== rate)) { pr.up = makeResampler(rate, r.rate); pr.upRate = rate; }
      const out = c.v2 ? pr.up(decoded) : resample(decoded, 16000, r.rate);
      pr.play.port.postMessage(out, [out.buffer]);
    } else if (flags === AC.MULAW) {
      const pcm = new Float32Array(body.length);
      for (let i = 0; i < body.length; i++) pcm[i] = mulawDecode(body[i]);
      const out = resample(pcm, 8000, r.rate);
      pr.play.port.postMessage(out, [out.buffer]);
    }
  } else if (type === PKT.VIDEO && window.VideoDecoder) {
    const codec = flags & 0x7f, key = !!(flags & 0x80);
    if (!pr.vdec || pr.vcodec !== codec) {
      if (!key) { askKeyframe(c, p); return; }
      if (pr.vdec) { try { pr.vdec.close(); } catch { /* closed */ } }
      pr.vcodec = codec;
      pr.vdec = new VideoDecoder({ output: (vf) => drawRemote(p, vf), error: () => { pr.vdec = null; askKeyframe(c, p); } });
      pr.vdec.configure({ codec: VCODEC[codec], optimizeForLatency: true });
    }
    if (pr.vdec.decodeQueueSize > 3 && !key) return; // behind: wait for the next key frame
    pr.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: time * 1000, data: body }));
  }
}

function askKeyframe(c, p) {
  if (Date.now() - p.relay.lastKfAsk < 2000) return;
  p.relay.lastKfAsk = Date.now();
  sendSignal(c, p, 'relay', { keyframe: true }).catch(() => {});
}

function drawRemote(p, vf) {
  const canvas = p.tile.canvas;
  if (canvas.width !== vf.displayWidth) { canvas.width = vf.displayWidth; canvas.height = vf.displayHeight; }
  canvas.getContext('2d').drawImage(vf, 0, 0);
  vf.close();
  canvas.hidden = false; p.tile.video.hidden = true;
}

function stopRelay(c) {
  const r = c.relay;
  if (!r) return;
  for (const codec of [r.aenc, r.venc]) { try { if (codec && codec.state !== 'closed') codec.close(); } catch { /* closed */ } }
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

/** G.711 mu-law: the voice codec when someone cannot use Opus (the desktop app; 64 kbit/s). */
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
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1, sampleRate: 48000 },
      video: video ? { width: { ideal: CALL.W }, height: { ideal: CALL.H }, frameRate: { ideal: CALL.FPS, max: CALL.FPS }, facingMode: 'user' } : false,
    });
    call.stream.getAudioTracks().forEach((t) => { t.contentHint = 'speech'; });
  } catch (e) {
    throw new Error(e.name === 'NotAllowedError' ? 'allow the microphone' + (video ? ' and camera' : '') + ' to call' : 'no microphone found');
  }
  $('callLocal').srcObject = call.stream;
  $('callLocal').hidden = !video;
}

function showCall(text, incoming) {
  $('callTitle').textContent = '▣ ' + call.title;
  $('callAccept').hidden = !incoming;
  $('callCam').hidden = !call.video;
  $('callStage').hidden = !call.video;
  $('callTiles').replaceChildren();
  $('callCode').replaceChildren();
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
  setCallStatus(clock(Date.now() - c.startedAt));
  $('callInfo').textContent = modeText(c);
}

const modeText = (c) => (c.mode === 'relay' ? 'relay · hidden IP' : 'direct');

async function directPing(c) {
  if (call !== c || c.mode !== 'direct') return;
  let best = null;
  for (const p of c.peers.values()) {
    if (!p.pc) continue;
    (await p.pc.getStats()).forEach((s) => {
      if (s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded' && s.currentRoundTripTime != null) {
        const ms = Math.round(s.currentRoundTripTime * 1000);
        best = best == null ? ms : Math.max(best, ms);
      }
    });
  }
  if (best != null) showPing(best);
}

function showPing(ms) {
  if (call) $('callInfo').textContent = modeText(call) + ' · ' + ms + ' ms';
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
  if (!c.camOn) {
    for (const p of c.peers.values()) if (p.pc && p.videoSender) await p.videoSender.replaceTrack(null);
    if (old) { old.stop(); c.stream.removeTrack(old); } // the camera light goes off
    return;
  }
  try {
    const fresh = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: CALL.W }, height: { ideal: CALL.H }, frameRate: { ideal: CALL.FPS, max: CALL.FPS }, facingMode: 'user' } });
    const track = fresh.getVideoTracks()[0];
    c.stream.addTrack(track);
    $('callLocal').srcObject = c.stream;
    for (const p of c.peers.values()) if (p.pc && p.videoSender) await p.videoSender.replaceTrack(track);
    if (c.relay) c.relay.forceKey = true;
  } catch { c.camOn = false; $('callCam').textContent = 'CAM OFF'; toast('camera unavailable'); }
}

function toggleMic() {
  if (!call) return;
  call.micOn = !call.micOn;
  call.stream.getAudioTracks().forEach((t) => { t.enabled = call.micOn; });
  $('callMic').textContent = call.micOn ? 'MIC ON' : 'MUTED';
}

function renderHideIp() {
  $('hideIpBtn').textContent = '🛡 HIDE MY IP IN CALLS: ' + (callPrefs.hideIp ? 'ON' : 'OFF');
  $('qualitySel').value = qualityFor(callPrefs.mode) === QUALITY[callPrefs.mode] ? callPrefs.mode : 'auto';
  $('callsV2Btn').textContent = 'CALLS V2: ' + (callPrefs.v2 ? 'ON' : 'OFF') + (S.callsV2 ? '' : ' (server off)');
}

$('callBtn').onclick = () => startCall(false);
$('videoBtn').onclick = () => startCall(true);
$('callAccept').onclick = acceptCall;
$('callEnd').onclick = hangUp;
$('callMic').onclick = toggleMic;
$('callCam').onclick = toggleCam;
$('hideIpBtn').onclick = () => { callPrefs.hideIp = !callPrefs.hideIp; store.set('ember.hideIp', callPrefs.hideIp ? '1' : null); renderHideIp(); };
$('qualitySel').onchange = (e) => {
  callPrefs.mode = QUALITY[e.target.value] ? e.target.value : 'auto';
  store.set('ember.quality', callPrefs.mode);
  applyMode(call); // takes effect in the current call right away
  toast('call quality: ' + qualityFor(callPrefs.mode).label);
};
$('callsV2Btn').onclick = () => { callPrefs.v2 = !callPrefs.v2; store.set('ember.callsV2', callPrefs.v2 ? null : '0'); renderHideIp(); };
renderHideIp();
addEventListener('pagehide', hangUp);
