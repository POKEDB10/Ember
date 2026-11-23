'use strict';
// End-to-end encrypted chats. Loaded after app.js and uses its helpers.
//
// Every device (each browser, each desktop app) has its own key pair (ECDH P-256). The private
// key never leaves the device (in the browser it is stored as a non-extractable key). The server
// stores only the public keys and hands them out per conversation.
//
// Sending a message:
//   1. A fresh random message key encrypts the text (AES-256-GCM). The conversation, the sender
//      and the message id are bound in as associated data, so the server cannot move the text
//      into another chat or another message.
//   2. That message key is wrapped once for every device in the chat (the sender's own devices
//      too): ECDH between the sending device and each receiving device, then HKDF with a fresh
//      random salt, then AES-256-GCM. Only the sending device or that one receiving device can
//      compute that key, which also proves which device sent the message.
// The server stores and forwards only: e2e1.<sender device>.<salt>.<iv>.<ciphertext>.<wraps>.
//
// Trust: the first time we see a person's devices we remember them. If a new device appears
// later, the chat shows a notice, and the profile shows a safety number that both people can
// compare (same digits on both screens = no one, not even the server, is in between).

const E2E = { PREFIX: 'e2e1.', CACHE_MS: 60000 };
const e2e = { dev: null, ready: null, keys: new Map(), secrets: new Map(), pubs: new Map(), chain: Promise.resolve() };
const enc = (bytes) => b64url(bytes);
const dec = (text) => urlKey(text);
const rand = (n) => crypto.getRandomValues(new Uint8Array(n));

// ---- this device's key pair, kept in IndexedDB ----
function keyStore(mode, fn) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ember-keys', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('keys');
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const tx = req.result.transaction('keys', mode);
      const r = fn(tx.objectStore('keys'));
      tx.oncomplete = () => { req.result.close(); resolve(r && r.result); };
      tx.onerror = () => reject(tx.error);
    };
  });
}

/** Loads this browser's device key for the account, or makes one, and registers it with the server. */
function e2eStart() {
  e2e.ready = (async () => {
    let dev = await keyStore('readonly', (s) => s.get(S.me)).catch(() => null);
    if (!dev || !dev.priv) {
      const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
      dev = { id: enc(rand(16)), priv: pair.privateKey, pub: enc(await crypto.subtle.exportKey('raw', pair.publicKey)) };
      await keyStore('readwrite', (s) => s.put(dev, S.me));
    }
    e2e.dev = dev;
    await request(OP.DEVICE_KEY, { deviceId: dev.id, pub: dev.pub }).catch(() => {});
    return dev;
  })();
  e2e.ready.catch(() => toast('⚠ encryption is unavailable in this browser'));
}

/** On logout: the server stops encrypting for this device, and the key is deleted here. */
async function e2eRetire(user) {
  if (e2e.dev) send(OP.DEVICE_KEY, { deviceId: e2e.dev.id, action: 'retire' });
  await keyStore('readwrite', (s) => s.delete(user)).catch(() => {});
}

// ---- keys of the people in a conversation ----
async function keysFor(convId, fresh) {
  const hit = e2e.keys.get(convId);
  if (hit && !fresh && Date.now() - hit.at < E2E.CACHE_MS) return hit.list;
  const f = await request(OP.KEYS_GET, { convId });
  const list = Array.from({ length: +f.n || 0 }, (_, i) => ({ user: f['user.' + i], dev: f['dev.' + i], pub: f['pub.' + i], active: f['active.' + i] === 'true' }));
  e2e.keys.set(convId, { at: Date.now(), list });
  noticeNewDevices(convId, list);
  return list;
}

/** Remembers each person's devices; a device that appears later is announced in the chat. */
function noticeNewDevices(convId, list) {
  const key = 'ember.devices.' + S.me;
  let seen = {};
  try { seen = JSON.parse(store.get(key) || '{}'); } catch { seen = {}; }
  let changed = false;
  for (const k of list) {
    if (k.user === S.me || !k.active) continue;
    const known = seen[k.user];
    if (!known) { seen[k.user] = [k.dev]; changed = true; continue; }
    if (!known.includes(k.dev)) {
      known.push(k.dev); changed = true;
      toast('🔑 ' + name(k.user) + ' signed in on a new device. Compare the safety number in their profile.');
    }
  }
  if (changed) store.set(key, JSON.stringify(seen));
}

function markEncrypted(convId) {
  const key = 'ember.e2echats.' + S.me;
  let chats = [];
  try { chats = JSON.parse(store.get(key) || '[]'); } catch { chats = []; }
  if (!chats.includes(convId)) { chats.push(convId); store.set(key, JSON.stringify(chats)); }
}

function wasEncrypted(convId) {
  try { return JSON.parse(store.get('ember.e2echats.' + S.me) || '[]').includes(convId); } catch { return true; }
}

function everKeyed(userId) {
  try { return !!JSON.parse(store.get('ember.devices.' + S.me) || '{}')[userId]; } catch { return false; }
}

async function publicKey(pub) {
  if (!e2e.pubs.has(pub)) e2e.pubs.set(pub, crypto.subtle.importKey('raw', dec(pub), { name: 'ECDH', namedCurve: 'P-256' }, false, []));
  return e2e.pubs.get(pub);
}

/** ECDH between this device and another device; cached, since it never changes. */
async function sharedWith(pub) {
  if (!e2e.secrets.has(pub)) {
    e2e.secrets.set(pub, (async () => new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: await publicKey(pub) }, e2e.dev.priv, 256)))());
  }
  return e2e.secrets.get(pub);
}

async function wrapKey(pub, salt, from, to) {
  const ikm = await crypto.subtle.importKey('raw', await sharedWith(pub), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: utf8.encode('ember-msg-v1|' + from + '|' + to) },
    ikm, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

const aad = (convId, sender, msgId) => utf8.encode(convId + '\n' + sender + '\n' + msgId);
const ZERO_IV = new Uint8Array(12);

// ---- encrypt before sending ----
/** Returns the body to send: encrypted for every device in the chat, or refuses. */
async function sealBody(convId, msgId, text) {
  await e2e.ready;
  const list = (await keysFor(convId)).filter((k) => k.active);
  const others = new Set(list.filter((k) => k.user !== S.me).map((k) => k.user));
  const c = S.convs.get(convId);
  const people = convId.startsWith('dm:') ? [peer({ id: convId })] : (c && c.members ? c.members.filter((u) => u !== S.me) : [...others]);
  const missing = people.filter((u) => !others.has(u));
  if (!others.size) {
    // Nobody else has encryption set up yet (they have not opened the new app). Plain text only for
    // a chat that never used encryption, with people never seen with keys: once seen, never downgrade.
    const all = await keysFor(convId);
    if (wasEncrypted(convId) || all.some((k) => k.user !== S.me) || missing.some(everKeyed)) {
      throw new Error('cannot encrypt: their devices are missing. Not sent.');
    }
    return text;
  }
  markEncrypted(convId);
  if (convId.startsWith('dm:') && missing.length) throw new Error('cannot encrypt for this chat. Not sent.');
  if (!list.some((k) => k.dev === e2e.dev.id)) list.push({ user: S.me, dev: e2e.dev.id, pub: e2e.dev.pub, active: true });
  const msgKeyRaw = rand(32), iv = rand(12), salt = rand(16);
  const msgKey = await crypto.subtle.importKey('raw', msgKeyRaw, 'AES-GCM', false, ['encrypt']);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad(convId, S.me, msgId) }, msgKey, utf8.encode(text));
  const wraps = await Promise.all(list.map(async (k) => {
    const w = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: ZERO_IV }, await wrapKey(k.pub, salt, e2e.dev.id, k.dev), msgKeyRaw);
    return k.dev + ':' + enc(w);
  }));
  return E2E.PREFIX + [e2e.dev.id, enc(salt), enc(iv), enc(ct), wraps.join(',')].join('.');
}

// ---- decrypt on arrival ----
async function openBody(convId, sender, msgId, body) {
  const parts = body.slice(E2E.PREFIX.length).split('.');
  if (parts.length !== 5) throw new Error('bad');
  const [from, salt, iv, ct, wraps] = parts;
  await e2e.ready;
  const mine = wraps.split(',').find((w) => w.startsWith(e2e.dev.id + ':'));
  if (!mine) throw new Error('not for this device');
  let list = await keysFor(convId);
  let senderKey = list.find((k) => k.user === sender && k.dev === from);
  if (!senderKey) { list = await keysFor(convId, true); senderKey = list.find((k) => k.user === sender && k.dev === from); }
  if (!senderKey) throw new Error('unknown sender device');
  const raw = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ZERO_IV }, await wrapKey(senderKey.pub, dec(salt), from, e2e.dev.id), dec(mine.split(':')[1]));
  const msgKey = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
  return unutf8.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: dec(iv), additionalData: aad(convId, sender, msgId) }, msgKey, dec(ct)));
}

const LOCKED = '🔒 This message cannot be read on this device (it was sent before this device was set up, or it was tampered with).';

/** Replaces an encrypted body in a frame with its text, in place. */
async function decryptFrame(f) {
  if (!f.body || !f.body.startsWith(E2E.PREFIX)) return;
  try {
    const conv = f.convId || (findMsg(f.clientMsgId) || {}).conv;
    f.body = await openBody(conv, f.senderUserId || (findMsg(f.clientMsgId) || {}).from, f.clientMsgId, f.body);
    f.e2e = 'true';
    markEncrypted(conv);
  } catch {
    f.body = LOCKED; f.e2e = 'locked';
  }
}

/** Frames are handled in arrival order; ones carrying encrypted text wait for their decryption. */
function inbound(msg) {
  const needs = msg.f.body && msg.f.body.startsWith(E2E.PREFIX);
  if (msg.op === OP.KEYS_CHANGED) { e2e.keys.clear(); return; }
  // Key replies (and errors for waiting requests) skip the queue: a decryption may be waiting for them.
  if (msg.op === OP.KEYS || (msg.op === OP.ERROR && S.waiting.has(msg.corr))) { handle(msg); return; }
  if (!needs && !e2e.busy) { handle(msg); return; }
  e2e.busy = (e2e.busy || 0) + 1;
  e2e.chain = e2e.chain.then(() => (needs ? decryptFrame(msg.f) : null)).then(() => handle(msg)).catch(() => {}).finally(() => { e2e.busy--; });
}

// ---- safety number, shown on profiles ----
async function safetyNumber(userId) {
  const convId = 'dm:' + [S.me, userId].sort().join(':');
  const list = (await keysFor(convId, true)).filter((k) => k.active);
  if (!list.some((k) => k.user === userId)) return '';
  const all = list.map((k) => k.user + ':' + k.pub).sort().join('|');
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', utf8.encode(all)));
  const groups = [];
  for (let i = 0; i < 10; i += 2) groups.push(String(((hash[i] << 8) | hash[i + 1]) % 100000).padStart(5, '0'));
  return groups.join(' ');
}
