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
// One key for the whole account: the first device that signs in with the password makes a key
// pair, seals the private half with a key derived from the password (PBKDF2-SHA256, 600,000
// rounds, AES-GCM) and stores only that sealed copy on the server. Every later sign-in with the
// password opens it, so all of the account's browsers and desktop apps read the same messages. The
// key this browser used before is kept, to read what was sealed for it.
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

const KB = { PREFIX: 'kb1.', ROUNDS: 600000 };
const ECDH = { name: 'ECDH', namedCurve: 'P-256' };

async function backupKey(password, salt) {
  const base = await crypto.subtle.importKey('raw', utf8.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: KB.ROUNDS }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
const backupData = (dev, pub) => utf8.encode('ember-keybackup-v1|' + S.me + '|' + dev + '|' + pub);
const BACKUP_RE = /^kb1\.[A-Za-z0-9_-]{16,43}\.[A-Za-z0-9_-]{87}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{100,400}$/;

/** Opens the account key backup with the password (throws when the password does not fit it). */
async function openBackup(password, backup) {
  if (!BACKUP_RE.test(backup)) throw new Error('not a key backup');
  const [id, pub, salt, iv, sealed] = backup.slice(KB.PREFIX.length).split('.');
  const pkcs8 = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: dec(iv), additionalData: backupData(id, pub) },
    await backupKey(password, dec(salt)), dec(sealed));
  return { id, pub, priv: await crypto.subtle.importKey('pkcs8', pkcs8, ECDH, false, ['deriveBits']), shared: true };
}

/** A new account key: sealed with the password and stored on the server (replacing an old one only after a reset). */
async function newAccountKey(password, replace) {
  const pair = await crypto.subtle.generateKey(ECDH, true, ['deriveBits']);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  const id = enc(rand(16)), pub = enc(await crypto.subtle.exportKey('raw', pair.publicKey)), salt = rand(16), iv = rand(12);
  const sealed = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: backupData(id, pub) }, await backupKey(password, salt), pkcs8);
  await request(OP.DEVICE_KEY, { deviceId: id, action: 'backup', replace: replace ? '1' : '0',
    backup: KB.PREFIX + [id, pub, enc(salt), enc(iv), enc(new Uint8Array(sealed))].join('.') });
  const dev = { id, pub, priv: await crypto.subtle.importKey('pkcs8', pkcs8, ECDH, false, ['deriveBits']), shared: true };
  pkcs8.fill(0);
  return dev;
}

/** Switches to the account key; this browser's previous key is kept for older messages and retired on the server. */
async function adopt(old, account) {
  account.olds = (old && old.olds) || [];
  if (old && old.priv && old.id !== account.id) {
    account.olds = [...account.olds, { id: old.id, pub: old.pub, priv: old.priv }];
    send(OP.DEVICE_KEY, { deviceId: old.id, action: 'retire' });
  }
  await keyStore('readwrite', (st) => st.put(account, S.me));
  return account;
}

/**
 * Loads this browser's key, or adopts the account key with the password just typed. Without a
 * password (a resumed session) a browser that does not have the account key yet keeps its own
 * key, and the profile offers to unlock the account key.
 */
function e2eStart(password, backup) {
  e2e.backup = backup || '';
  e2e.ready = (async () => {
    let dev = await keyStore('readonly', (st) => st.get(S.me)).catch(() => null);
    if (dev && !dev.priv) dev = null;
    if (password && !(dev && dev.shared && (!backup || backup.startsWith(KB.PREFIX + dev.id + '.')))) {
      let account = null;
      if (backup) account = await openBackup(password, backup).catch(() => null);
      // No backup yet: this is the account's first device. A backup the password cannot open was
      // sealed before a password reset: a new account key replaces it.
      if (!account) account = await newAccountKey(password, !!backup).catch(() => null);
      if (account) { const before = dev; dev = await adopt(dev, account); e2e.backup = ''; if (before && S.rev) e2e.resync = true; }
    }
    if (!dev) {
      const pair = await crypto.subtle.generateKey(ECDH, false, ['deriveBits']);
      dev = { id: enc(rand(16)), priv: pair.privateKey, pub: enc(await crypto.subtle.exportKey('raw', pair.publicKey)) };
      await keyStore('readwrite', (st) => st.put(dev, S.me));
    }
    e2e.dev = dev;
    e2e.olds = dev.olds || [];
    await request(OP.DEVICE_KEY, { deviceId: dev.id, pub: dev.pub }).catch(() => {});
    if (e2e.resync) { e2e.resync = false; setTimeout(e2eResync, 0); }
    return dev;
  })();
  e2e.ready.catch(() => toast('⚠ encryption is unavailable in this browser'));
}

/** True when this browser could read more after unlocking the account key with the password. */
const e2eCanUnlock = () => !!(e2e.backup && e2e.dev && !(e2e.dev.shared && e2e.backup.startsWith(KB.PREFIX + e2e.dev.id + '.')));

/** Asks for the password and opens the account key (from the profile, after a resumed session). */
async function e2eUnlock() {
  const v = await ask({ title: 'UNLOCK ALL MESSAGES', ok: 'UNLOCK',
    text: 'Your password opens the key your other devices use, so this browser can read the same chats. It never leaves this browser.',
    fields: [{ name: 'pass', label: 'PASSWORD', type: 'password', max: 128, required: true }],
    validate: async (x) => { try { e2e.unlocked = await openBackup(x.pass, e2e.backup); return null; } catch { return 'wrong password'; } } });
  if (!v || !e2e.unlocked) return;
  e2e.dev = await adopt(e2e.dev, e2e.unlocked);
  e2e.olds = e2e.dev.olds; e2e.unlocked = null; e2e.backup = '';
  e2e.ready = Promise.resolve(e2e.dev);
  await request(OP.DEVICE_KEY, { deviceId: e2e.dev.id, pub: e2e.dev.pub }).catch(() => {});
  e2e.secrets.clear();
  toast('🔑 unlocked');
  e2eResync();
}

/**
 * Messages saved on this device as unreadable are fetched again now that a new key can open them:
 * the saved copy is dropped and the connection restarts with a full sync.
 */
function e2eResync() {
  cache.clear(S.me);
  S.rev = 0; S.lastSeq = 0;
  if (S.ws) S.ws.close(); // reconnects by itself and syncs everything
}

/** On logout: the key is deleted here. A browser's own key is retired; the shared account key stays active. */
async function e2eRetire(user) {
  if (e2e.dev && !e2e.dev.shared) send(OP.DEVICE_KEY, { deviceId: e2e.dev.id, action: 'retire' });
  await keyStore('readwrite', (st) => st.delete(user)).catch(() => {});
}

// ---- keys of the people in a conversation ----
async function keysFor(convId, fresh) {
  const hit = e2e.keys.get(convId);
  if (hit && !fresh && Date.now() - hit.at < E2E.CACHE_MS) return hit.list;
  const f = await request(OP.KEYS_GET, { convId });
  // Only well-formed keys of people actually in this chat are used; anything else is ignored.
  const members = chatMembers(convId);
  const list = Array.from({ length: +f.n || 0 }, (_, i) => ({ user: f['user.' + i], dev: f['dev.' + i], pub: f['pub.' + i], active: f['active.' + i] === 'true' }))
    .filter((k) => /^[A-Za-z0-9_-]{16,43}$/.test(k.dev) && /^[A-Za-z0-9_-]{87}$/.test(k.pub) && (!members || members.includes(k.user)))
    .filter((k) => {
      // Anything claiming to be this device must be exactly this device's key; anything else is an impostor.
      const claims = e2e.dev && k.dev === e2e.dev.id;
      if (claims && (k.user !== S.me || k.pub !== e2e.dev.pub)) { toast('⚠ the server listed a fake copy of this device. It was ignored.'); return false; }
      return true;
    });
  e2e.keys.set(convId, { at: Date.now(), list });
  noticeNewDevices(convId, list);
  return list;
}

/** The people in a chat: both names of a direct chat, or the group's members when known. */
function chatMembers(convId) {
  if (convId.startsWith('dm:')) return convId.slice(3).split(':');
  const c = S.convs.get(convId);
  return c && c.members ? [...c.members, S.me] : null;
}

/** Remembers each person's devices; a device that appears later is announced in the chat. */
function noticeNewDevices(convId, list) {
  const key = 'ember.devices.' + S.me;
  let seen = {};
  try { seen = JSON.parse(store.get(key) || '{}'); } catch { seen = {}; }
  let changed = false;
  for (const k of list) {
    if (!k.active || (k.user === S.me && e2e.dev && k.dev === e2e.dev.id && k.pub === e2e.dev.pub)) continue;
    const known = seen[k.user], pin = k.dev + ':' + k.pub; // the key is pinned, not just the device id
    if (!known) { seen[k.user] = [pin]; changed = true; continue; }
    if (!known.includes(pin)) {
      known.push(pin); changed = true;
      // A device added to your own account also receives your messages: warn about that too.
      toast(k.user === S.me ? '🔑 A new device was added to YOUR account. If it is not yours, change your password and check the device codes in your profile.'
        : '🔑 ' + name(k.user) + ' signed in on a new device. Compare the device codes in their profile.');
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

/** ECDH between one of this browser's keys and another device; cached, since it never changes. */
async function sharedWith(pub, me = e2e.dev) {
  const key = me.id + '|' + pub;
  if (!e2e.secrets.has(key)) {
    e2e.secrets.set(key, (async () => new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: await publicKey(pub) }, me.priv, 256)))());
  }
  return e2e.secrets.get(key);
}

async function wrapKey(pub, salt, from, to, me) {
  const ikm = await crypto.subtle.importKey('raw', await sharedWith(pub, me), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info: utf8.encode('ember-msg-v1|' + from + '|' + to) },
    ikm, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

const aad = (convId, sender, msgId) => utf8.encode(convId + '\n' + sender + '\n' + msgId);
const ZERO_IV = new Uint8Array(12);

/**
 * What each key wrap is bound to: SHA-256 of chat, sender, message id, iv and ciphertext. Another
 * recipient who learns the message key still cannot pass new text off under the sender's wrap.
 */
async function binding(convId, sender, msgId, iv, ct) {
  const head = aad(convId, sender, msgId);
  const all = new Uint8Array(head.length + iv.length + ct.length);
  all.set(head); all.set(iv, head.length); all.set(ct, head.length + iv.length);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', all));
}

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
  const bind = await binding(convId, S.me, msgId, iv, new Uint8Array(ct));
  const wraps = await Promise.all(list.map(async (k) => {
    const w = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: ZERO_IV, additionalData: bind }, await wrapKey(k.pub, salt, e2e.dev.id, k.dev), msgKeyRaw);
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
  // The account key first, then keys this browser used before (older messages were sealed for those).
  let me = null, mine = null;
  for (const k of [e2e.dev, ...(e2e.olds || [])]) {
    mine = wraps.split(',').find((w) => w.startsWith(k.id + ':'));
    if (mine) { me = k; break; }
  }
  if (!mine) throw new Error('not for this device');
  let list = await keysFor(convId);
  let senderKey = list.find((k) => k.user === sender && k.dev === from);
  if (!senderKey) { list = await keysFor(convId, true); senderKey = list.find((k) => k.user === sender && k.dev === from); }
  if (!senderKey) throw new Error('unknown sender device');
  const bind = await binding(convId, sender, msgId, dec(iv), dec(ct));
  const raw = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ZERO_IV, additionalData: bind }, await wrapKey(senderKey.pub, dec(salt), from, me.id, me), dec(mine.split(':')[1]));
  const msgKey = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
  return unutf8.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: dec(iv), additionalData: aad(convId, sender, msgId) }, msgKey, dec(ct)));
}

const LOCKED = '🔒 This message cannot be read here: it was sealed for another device before the account key existed (or it was tampered with).';

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
  delete msg.f.e2e; // never trust an "encrypted" marker from the wire: only a local decryption sets it
  const needs = msg.f.body && msg.f.body.startsWith(E2E.PREFIX);
  // An update changes a message we already have: judge it by that message's own chat, and drop
  // updates that claim another chat (the server must not move text between chats).
  const known = msg.op === OP.UPDATE && msg.f.clientMsgId ? findMsg(msg.f.clientMsgId) : null;
  if (known && ((msg.f.convId && msg.f.convId !== known.conv) || (msg.f.senderUserId && msg.f.senderUserId !== known.from))) return;
  if (known) { msg.f.convId = known.conv; msg.f.senderUserId = known.from; } // decrypt as the stored message only
  const chat = known ? known.conv : msg.f.convId;
  if (!needs && msg.f.body && chat && (wasEncrypted(chat) || (known && known.e2e === 'true'))) {
    msg.f.body = '⚠ unencrypted message in an encrypted chat (not verified): ' + msg.f.body;
  }
  if (msg.op === OP.KEYS_CHANGED) { e2e.keys.clear(); return; }
  // Key replies (and errors for waiting requests) skip the queue: a decryption may be waiting for them.
  if (msg.op === OP.KEYS || (msg.op === OP.ERROR && S.waiting.has(msg.corr))) { handle(msg); return; }
  if (!needs && !e2e.busy) { handle(msg); return; }
  e2e.busy = (e2e.busy || 0) + 1;
  e2e.chain = e2e.chain.then(() => (needs ? decryptFrame(msg.f) : null)).then(() => handle(msg)).catch(() => {}).finally(() => { e2e.busy--; });
}

// ---- safety number, shown on profiles ----
/** A device's code: 8 groups of 5 digits from 128 bits of its key's hash (the desktop computes the same). */
async function deviceCode(pub) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', dec(pub)));
  return Array.from({ length: 8 }, (_, i) => String(((hash[2 * i] << 8) | hash[2 * i + 1]) % 100000).padStart(5, '0')).join(' ');
}

/**
 * Device codes for checking a chat. This device's code comes from its own key, never from the
 * server; the other person's codes are what the server says their devices are. The other person
 * checks that their own device code is in that list and that no code is unknown to them.
 */
async function safetyNumber(userId) {
  await e2e.ready;
  const convId = 'dm:' + [S.me, userId].sort().join(':');
  const list = (await keysFor(convId, true)).filter((k) => k.active);
  const theirs = list.filter((k) => k.user === userId);
  if (!theirs.length) return null;
  const myOthers = list.filter((k) => k.user === S.me && !(k.dev === e2e.dev.id && k.pub === e2e.dev.pub));
  return { mine: await deviceCode(e2e.dev.pub), myOthers: await Promise.all(myOthers.map((k) => deviceCode(k.pub))),
    theirs: await Promise.all(theirs.map((k) => deviceCode(k.pub))) };
}
