'use strict';
// Ember web client. Speaks the same binary frames as the desktop app, over a WebSocket.
// No framework, cached by the service worker, and chats are cached on the device so a return
// visit downloads only what changed.

// ---- wire protocol (mirrors FrameCodec / KvPayloadCodec / WireOpcodes) ----
const OP = {
  REGISTER: 0x01, LOGIN: 0x02, RESUME: 0x03, LOGOUT: 0x04, PING: 0x05,
  SEND: 0x10, RECEIPT: 0x11, REACT: 0x12, EDIT: 0x13, DELETE: 0x14, PIN: 0x15, STAR: 0x16, TYPING: 0x17,
  POLL_CREATE: 0x18, POLL_VOTE: 0x19, GROUP_CREATE: 0x20, GROUP_MEMBER: 0x21, GROUP_INFO: 0x23,
  STATUS_SET: 0x24, PUSH_SUBSCRIBE: 0x25, CALL: 0x26, CALL_MEDIA: 0x27, DEVICE_KEY: 0x28, KEYS_GET: 0x29,
  PROFILE_UPDATE: 0x30, FOLLOW: 0x31, MEDIA_UPLOAD: 0x32, POST_CREATE: 0x33, POST_INTERACT: 0x34,
  STORY_CREATE: 0x35, STORY_VIEW: 0x36, BLOCK: 0x37, REPORT: 0x38, FEED_PULL: 0x3A, PROFILE_GET: 0x3B,
  AUTH_OK: 0x40, PONG: 0x41, ACK: 0x42, DELIVER: 0x43, RECEIPT_UPDATE: 0x44, UPDATE: 0x45,
  TYPING_EVT: 0x46, PRESENCE: 0x47, SYNC_ITEM: 0x48, SYNC_DONE: 0x49, HISTORY: 0x4A,
  GROUP_UPDATE: 0x4B, MEDIA_OK: 0x50, FEED_PAGE: 0x52, PROFILE_DATA: 0x53, NOTIFY: 0x54,
  STORY_UPDATE: 0x55, POLL_UPDATE: 0x57, CALL_EVT: 0x58, CALL_MEDIA_EVT: 0x59, KEYS: 0x5A, KEYS_CHANGED: 0x5B, ERROR: 0x70,
};
const MAGIC = 0x454D4252, HEADER = 18;
const utf8 = new TextEncoder(), unutf8 = new TextDecoder();
const esc = (v) => String(v).replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/\r/g, '\\r');
const unesc = (v) => v.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c === 'r' ? '\r' : c));

function encode(op, corr, fields) {
  return frame(op, corr, utf8.encode(Object.entries(fields).map(([k, v]) => k + '=' + esc(v)).join('\n')));
}

/** One frame around raw bytes (call packets travel this way, unread by the server). */
function frame(op, corr, body) {
  const buf = new Uint8Array(HEADER + body.length), dv = new DataView(buf.buffer);
  dv.setUint32(0, MAGIC); dv.setUint8(4, 1); dv.setUint8(5, op);
  dv.setUint32(6, Math.floor(corr / 2 ** 32)); dv.setUint32(10, corr >>> 0);
  dv.setUint32(14, body.length); buf.set(body, HEADER);
  return buf;
}

function decode(buf) {
  const dv = new DataView(buf);
  if (dv.getUint32(0) !== MAGIC) throw new Error('bad frame');
  const fields = {};
  const text = unutf8.decode(new Uint8Array(buf, HEADER, dv.getUint32(14)));
  for (const line of text ? text.split('\n') : []) {
    const at = line.indexOf('=');
    fields[line.slice(0, at)] = unesc(line.slice(at + 1));
  }
  return { op: dv.getUint8(5), corr: dv.getUint32(6) * 2 ** 32 + dv.getUint32(10), f: fields };
}

// ---- state ----
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch { /* private mode */ } },
};
const S = {
  ws: null, corr: 0, me: null, token: store.get('ember.token'), lastSeq: 0, rev: 0, synced: false,
  convs: new Map(), // id -> {id, name, msgs: Map(clientMsgId -> msg), unread, members}
  presence: new Map(), // userId -> online | idle | away | dnd
  active: null, view: 'chat', viewArg: null, replyTo: null, ttl: 0, window: 150,
  retry: 0, pendingAuth: 0, typingSent: 0, typingTimers: new Map(), myReacts: new Map(),
  waiting: new Map(), profiles: new Map(), asked: new Set(), polls: new Map(), media: new Map(),
  stories: [], query: '', starredOnly: false, expanded: new Set(), pinIndex: 0,
  notify: store.get('ember.notify') === '1', vapidKey: null,
  myStatus: store.get('ember.status') || 'online', lastActive: Date.now(), pending: [],
};
const $ = (id) => document.getElementById(id);
const REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🔥'];
const STATUS = {
  online: { label: 'Online', icon: '●' }, idle: { label: 'Away (idle)', icon: '◐' },
  away: { label: 'Away', icon: '◐' }, dnd: { label: 'Do not disturb', icon: '⊘' },
  invisible: { label: 'Invisible', icon: '○' },
};
const READ_MORE_CHARS = 600, READ_MORE_LINES = 12;

function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k.includes('-')) el.setAttribute(k, v);
    else el[k] = v;
  }
  for (const kid of kids) if (kid != null && kid !== false) el.append(kid);
  return el;
}

function toast(text) {
  const t = $('toast');
  t.textContent = text; t.hidden = false;
  clearTimeout(toast.timer); toast.timer = setTimeout(() => { t.hidden = true; }, 3500);
}

function send(op, fields = {}) {
  const corr = ++S.corr;
  if (S.ws && S.ws.readyState === 1) S.ws.send(encode(op, corr, fields));
  return corr;
}

/** Sends a command and resolves with the first reply that carries its correlation id. */
function request(op, fields = {}) {
  return new Promise((resolve, reject) => {
    if (!S.ws || S.ws.readyState !== 1) return reject(new Error('offline'));
    S.waiting.set(send(op, fields), { resolve, reject });
  });
}

// ---- device cache (IndexedDB): instant start-up, and only changes are downloaded ----
const cache = {
  db: null,
  open() {
    if (!this.db) {
      this.db = new Promise((resolve, reject) => {
        const req = indexedDB.open('ember', 1);
        req.onupgradeneeded = () => req.result.createObjectStore('cache');
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      });
    }
    return this.db;
  },
  async run(mode, fn) {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('cache', mode);
      const req = fn(tx.objectStore('cache'));
      tx.oncomplete = () => resolve(req && req.result);
      tx.onerror = () => reject(tx.error);
    });
  },
  load(user) { return this.run('readonly', (s) => s.get(user)).catch(() => null); },
  clear(user) { return this.run('readwrite', (s) => s.delete(user)).catch(() => {}); },
  save() {
    if (!S.me || !S.rev) return;
    const now = Date.now();
    const convs = [...S.convs.values()].map((c) => ({ id: c.id, name: c.name, members: c.members,
      msgs: [...c.msgs.values()].filter((m) => !m.pending && !(m.expires > 1 && m.expires < now)) }));
    this.run('readwrite', (s) => s.put({ rev: S.rev, convs, profiles: [...S.profiles.values()] }, S.me)).catch(() => {});
  },
};
let saveTimer = 0;
function saveSoon() { clearTimeout(saveTimer); saveTimer = setTimeout(() => cache.save(), 3000); }
addEventListener('pagehide', () => cache.save());

async function restoreCache(user) {
  const saved = await cache.load(user);
  if (!saved) return false;
  const now = Date.now();
  S.rev = saved.rev || 0;
  for (const p of saved.profiles || []) S.profiles.set(p.userId, p);
  for (const c of saved.convs || []) {
    S.convs.set(c.id, { id: c.id, name: c.name, members: c.members, unread: 0,
      msgs: new Map(c.msgs.filter((m) => !(m.expires > 1 && m.expires < now)).map((m) => [m.id, m])) });
  }
  return true;
}

// ---- connection ----
function connect(onOpen) {
  if (S.ws) { S.ws.onclose = null; S.ws.close(); }
  setStatus('connecting…');
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/');
  ws.binaryType = 'arraybuffer';
  S.ws = ws;
  ws.onopen = () => { S.retry = 0; onOpen(); };
  ws.onmessage = (e) => {
    const dv = new DataView(e.data);
    if (dv.getUint8(5) === OP.CALL_MEDIA_EVT) onCallMedia(new Uint8Array(e.data, HEADER, dv.getUint32(14)));
    else inbound(decode(e.data)); // e2e.js: decrypts in order, then handle()
  };
  ws.onclose = () => {
    endLocal('call dropped: connection lost'); // the server ends it too
    for (const w of S.waiting.values()) w.reject(new Error('offline'));
    S.waiting.clear();
    setStatus(S.synced ? 'offline — showing saved chats' : 'offline — retrying');
    if (!S.me) return;
    setTimeout(() => connect(resume), Math.min(30000, 1000 * 2 ** S.retry++));
  };
}

function syncFields() { return S.rev ? { sinceRevision: S.rev } : {}; }

function resume() { S.pendingAuth = send(OP.RESUME, { token: S.token, lastGlobalSeq: S.lastSeq, calls: '1', ...syncFields() }); }

setInterval(() => { if (S.me) send(OP.PING); }, 20000); // server drops idle sockets after 45 s

function setStatus(text) { $('status').textContent = (S.me ? S.me + ' · ' : '') + text; }

// ---- inbound events ----
function handle({ op, corr, f }) {
  switch (op) {
    case OP.AUTH_OK: onAuth(f); break;
    case OP.SYNC_ITEM:
      if (f.deleted === 'true') { const c = S.convs.get(f.convId); if (c) c.msgs.delete(f.clientMsgId); } else addMsg(f, false);
      break;
    case OP.HISTORY: addMsg(f, false); break;
    case OP.SYNC_DONE:
      S.synced = true; S.rev = Math.max(S.rev, +f.upToRevision || 0);
      setStatus('online'); render(); saveSoon(); handleLaunchParams();
      break;
    case OP.DELIVER: onDeliver(addMsg(f, true)); saveSoon(); break;
    case OP.ACK: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.pending = false; m.seq = +f.convSeq; m.ts = +f.serverTs; m.expires = +f.expiresAtMs; bumpSeq(f.globalSeq); renderSoon(); saveSoon(); }
      break;
    }
    case OP.RECEIPT_UPDATE: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.read = f.read === f.total; renderSoon(); }
      break;
    }
    case OP.UPDATE: onUpdate(f); saveSoon(); break;
    case OP.TYPING_EVT: onTyping(f); break;
    case OP.PRESENCE: onPresence(f); break;
    case OP.GROUP_UPDATE: {
      const c = conv('grp:' + f.groupId);
      c.name = f.groupName;
      c.members = Array.from({ length: +f['member.n'] }, (_, i) => f['member.' + i]);
      renderSoon(); saveSoon();
      break;
    }
    case OP.POLL_UPDATE: S.polls.set(f.pollId, f); renderSoon(); break;
    case OP.PROFILE_DATA:
      if (f.userId && f.displayName != null) S.profiles.set(f.userId, f);
      if (f.userId === S.me && f['pending.n'] != null) setPending(f);
      renderSoon();
      break;
    case OP.NOTIFY: if (!corr) onNotice(f); break;
    case OP.CALL_EVT: if (!corr) onCallEvent(f); break; // replies go to the waiting request
    case OP.ERROR:
      if (corr && corr === S.pendingAuth) {
        if (S.me) { const user = S.me; S.me = null; S.token = null; store.set('ember.token', null); cache.clear(user); showLogin(); }
        $('loginError').textContent = f.message;
        $('loginBtn').disabled = false;
      } else if (!S.waiting.has(corr)) {
        toast('⚠ ' + f.message);
      }
      break;
  }
  const waiter = corr && S.waiting.get(corr);
  if (waiter) {
    S.waiting.delete(corr);
    op === OP.ERROR ? waiter.reject(new Error(f.message)) : waiter.resolve(f);
  }
}

function onAuth(f) {
  if (S.me && S.me !== f.userId) { S.convs.clear(); S.rev = 0; }
  if (f.syncReset === 'true') { S.convs.clear(); S.rev = 0; } // server data was reset: drop the cache
  S.me = f.userId; S.token = f.token; S.vapidKey = f.vapidKey || null;
  try { S.ice = JSON.parse(f.ice || '[]'); } catch { S.ice = []; }
  e2eStart();
  store.set('ember.token', f.token); store.set('ember.user', f.userId);
  showApp(); setStatus('syncing…');
  // The server remembers a status set on another device; otherwise restore this device's choice.
  if (f.status && f.status !== 'online' && f.status !== 'idle') setMyStatus(f.status, false);
  else if (S.myStatus !== 'online') send(OP.STATUS_SET, { status: S.myStatus });
  request(OP.PROFILE_GET, { userId: S.me }).catch(() => {});
  // Re-bind this browser to the signed-in user, or drop a subscription left by someone else.
  (S.notify ? subscribePush() : unsubscribePush()).catch(() => {});
}

function onDeliver(m) {
  if (m.from === S.me) return;
  const open = S.view === 'chat' && S.active === m.conv && !document.hidden;
  send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: open ? 'READ' : 'DELIVERED' });
  if (open) return;
  m.unreadPending = true;
  if (S.myStatus === 'dnd') return; // Do not disturb: badge only
  const title = name(m.from) + (m.conv.startsWith('grp:') ? ' · ' + title2(m.conv) : '');
  const body = m.expires ? '⏱ disappearing message' : m.body;
  if (document.hidden) systemNotify(title, body, m.conv);
  else showNotice({ title, body, conv: m.conv, reply: true });
}

function title2(convId) { const c = S.convs.get(convId); return c ? title(c) : 'group'; }

function onUpdate(f) {
  const m = findMsg(f.clientMsgId);
  if (f.state === 'DELETED' || f.state === 'EXPIRED') {
    const c = S.convs.get(f.convId);
    if (c) c.msgs.delete(f.clientMsgId);
  } else if (m && f.state === 'EDITED') {
    m.body = f.body; m.edited = true; m.e2e = f.e2e || '';
  } else if (m && f.state === 'REACTION') {
    m.reacts[f.emoji] = +f.count;
    if (f.userId === S.me) toggleMine(m.id, f.emoji, f.selected === 'true');
  } else if (m && f.state === 'PINNED') {
    m.pinned = f.pinned === 'true';
  } else if (m && f.state === 'STARRED') {
    m.starred = f.starred === 'true';
  }
  renderSoon();
}

function onTyping(f) {
  const key = f.convId + '|' + f.userId;
  clearTimeout(S.typingTimers.get(key));
  if (f.typing === 'true') S.typingTimers.set(key, setTimeout(() => { S.typingTimers.delete(key); renderTyping(); }, 5000));
  else S.typingTimers.delete(key);
  renderTyping();
}

function onPresence(f) {
  if (f.userId === S.me) {
    // Another of my devices changed my status, or went idle while this one is in use.
    const status = f.status || 'online';
    if (status === 'idle' && isActive()) send(OP.STATUS_SET, { status: 'online' });
    else if (status !== 'idle') setMyStatus(status, false);
    return;
  }
  if (f.online === 'true') S.presence.set(f.userId, f.status || 'online'); else S.presence.delete(f.userId);
  renderSoon();
}

function onNotice(f) {
  if (f.kind === 'FOLLOW_REQUEST') {
    request(OP.PROFILE_GET, { userId: S.me }).catch(() => {});
    if (S.myStatus === 'dnd') return;
    if (document.hidden) systemNotify(name(f.from), 'wants to follow you', '');
    else showNotice({ title: name(f.from), body: 'wants to follow you', follower: f.from });
  } else if (f.kind === 'FOLLOW_APPROVED') {
    toast(name(f.from) + ' accepted your follow request');
  }
}

// ---- model helpers ----
function conv(id) {
  let c = S.convs.get(id);
  if (!c) {
    c = { id, name: null, msgs: new Map(), unread: 0, members: null };
    S.convs.set(id, c);
  }
  if (id.startsWith('grp:') && S.me && !c.infoAsked) { c.infoAsked = true; send(OP.GROUP_INFO, { groupId: id.slice(4) }); }
  return c;
}

function bumpSeq(seq) { S.lastSeq = Math.max(S.lastSeq, +seq || 0); }

function addMsg(f, live) {
  const c = conv(f.convId);
  bumpSeq(f.globalSeq);
  let m = c.msgs.get(f.clientMsgId);
  if (!m) {
    m = { id: f.clientMsgId, conv: f.convId, from: f.senderUserId, reacts: {} };
    c.msgs.set(m.id, m);
    if (live && f.senderUserId !== S.me && !(S.view === 'chat' && S.active === c.id && !document.hidden)) c.unread++;
  }
  Object.assign(m, {
    body: f.body, ts: +f.serverTs, seq: +f.convSeq, expires: +f.expiresAtMs || 0,
    replyPreview: f.replyPreview || '', pending: false,
  });
  m.e2e = f.e2e || ''; m.replyTo = f.replyToId || '';
  if (f.pinned != null) m.pinned = f.pinned === 'true';
  if (f.starred != null) m.starred = f.starred === 'true';
  m.reacts = {};
  for (let i = 0; i < +(f['reaction.n'] || 0); i++) m.reacts[f['reaction.emoji.' + i]] = +f['reaction.count.' + i];
  if (live || S.synced) renderSoon();
  return m;
}

function findMsg(id) {
  for (const c of S.convs.values()) { const m = c.msgs.get(id); if (m) return m; }
  return null;
}

function toggleMine(id, emoji, on) {
  const set = S.myReacts.get(id) || new Set();
  on ? set.add(emoji) : set.delete(emoji);
  S.myReacts.set(id, set);
}

/** Display name, fetched once per user and then served from memory and the device cache. */
function name(userId) {
  const p = S.profiles.get(userId);
  if (!p && S.me && !S.asked.has(userId)) {
    S.asked.add(userId);
    request(OP.PROFILE_GET, { userId }).catch(() => {});
  }
  return p && p.displayName ? p.displayName : userId;
}

/** Loads display names before rendering a list, so it paints once with the right names. */
function ensureNames(ids) {
  const missing = [...new Set(ids)].filter((u) => u && !S.profiles.has(u) && !S.asked.has(u));
  missing.forEach((u) => S.asked.add(u));
  return Promise.all(missing.map((userId) => request(OP.PROFILE_GET, { userId }).catch(() => {})));
}

function peer(c) { return c.id.startsWith('dm:') ? c.id.slice(3).split(':').find((u) => u !== S.me) : null; }

function title(c) { return c.id.startsWith('dm:') ? name(peer(c)) : '#' + (c.name || 'group'); }

function sorted(c) { return [...c.msgs.values()].sort((a, b) => (a.seq || 1e15) - (b.seq || 1e15) || a.ts - b.ts); }

function lastMsg(c) { let last = null; for (const m of c.msgs.values()) if (!last || m.ts > last.ts) last = m; return last; }

function statusOf(userId) { return S.presence.get(userId) || 'offline'; }

function statusText(userId) {
  const s = statusOf(userId);
  return s === 'offline' ? '' : s === 'dnd' ? '⊘ do not disturb' : s === 'online' ? '● online' : '◐ away';
}

const time = (ms) => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const when = (ms) => (Date.now() - ms < 864e5 ? time(ms) : new Date(ms).toLocaleDateString());

/** Images load over plain HTTP so the browser caches each one forever (they never change). */
async function mediaUrl(id, full) {
  const key = id + (full ? ':full' : '');
  if (!S.media.has(key)) {
    S.media.set(key, fetch('/media/' + id + (full ? '?full=1' : ''), { headers: { Authorization: 'Bearer ' + S.token } })
      .then((r) => (r.ok ? r.blob() : Promise.reject(new Error('missing'))))
      .then((b) => URL.createObjectURL(b))
      .catch((e) => { S.media.delete(key); throw e; }));
  }
  return S.media.get(key);
}

function img(id, full, alt) {
  const el = h('img', { alt, loading: 'lazy', class: 'pic' });
  mediaUrl(id, full).then((u) => { el.src = u; }).catch(() => { el.alt = '[image unavailable]'; });
  return el;
}

/** Shrinks a photo in the browser before upload: at most 1600 px, JPEG ~82%. */
async function pickImage() {
  const file = await new Promise((resolve) => {
    const input = $('filePick');
    input.value = '';
    input.onchange = () => resolve(input.files[0]);
    input.click();
  });
  if (!file) return null;
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height));
  const canvas = h('canvas', { width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale) });
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.82));
  const dataUrl = await new Promise((r) => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(blob); });
  toast('uploading ' + Math.round(blob.size / 1024) + ' KB…');
  const ok = await request(OP.MEDIA_UPLOAD, { fileName: 'photo.jpg', data: dataUrl.slice(dataUrl.indexOf(',') + 1) });
  return ok.mediaId;
}

// ---- notifications ----
/** System notification with Reply / Mark read buttons (shown by the service worker). */
async function systemNotify(title, body, conv) {
  if (!S.notify || !('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const reg = await navigator.serviceWorker.ready;
    reg.showNotification(title, { body: body.slice(0, 160), tag: conv || 'ember', renotify: true,
      icon: '/icon.svg', badge: '/icon.svg', data: { conv },
      actions: conv ? [{ action: 'reply', title: 'Reply', type: 'text', placeholder: 'Type a reply…' }, { action: 'read', title: 'Mark read' }] : [] });
  } catch { new Notification(title, { body, icon: '/icon.svg' }); }
}

/** In-page card for a new message in another chat: reply inline, open, or dismiss. */
function showNotice({ title, body, conv: convId, reply, follower }) {
  const box = $('notices');
  const close = () => { card.classList.add('leaving'); setTimeout(() => card.remove(), 180); };
  const input = reply ? h('input', { placeholder: 'reply…', 'aria-label': 'Quick reply to ' + title }) : null;
  const actions = h('div', { class: 'row gap tight' });
  if (reply) {
    actions.append(h('button', { type: 'submit' }, 'SEND'), h('button', { type: 'button', onclick: () => { close(); openChat(convId); } }, 'OPEN'));
  }
  if (follower) {
    actions.append(h('button', { type: 'button', onclick: () => { approve(follower, true); close(); } }, 'ACCEPT'),
      h('button', { type: 'button', onclick: () => { approve(follower, false); close(); } }, 'DECLINE'));
  }
  const card = h('form', { class: 'notice', onsubmit: (e) => {
    e.preventDefault();
    if (input && input.value.trim()) { sendText(convId, input.value.trim()); toast('sent'); close(); }
  } },
  h('div', { class: 'notice-head' }, h('strong', {}, title), h('button', { type: 'button', class: 'icon', 'aria-label': 'Dismiss', onclick: close }, '×')),
  h('p', {}, body.length > 140 ? body.slice(0, 140) + '…' : body), input, actions);
  let timer = setTimeout(close, 9000);
  const hold = () => clearTimeout(timer), release = () => { if (!card.contains(document.activeElement)) timer = setTimeout(close, 5000); };
  card.addEventListener('pointerenter', hold); card.addEventListener('focusin', hold);
  card.addEventListener('pointerleave', release); card.addEventListener('focusout', release);
  box.prepend(card);
  while (box.children.length > 3) box.lastChild.remove();
}

function urlKey(base64) {
  const raw = atob((base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Registers this browser for Web Push, so messages arrive even when Ember is closed. */
async function subscribePush() {
  if (!S.vapidKey || !('PushManager' in window)) return false;
  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  const key = urlKey(S.vapidKey);
  if (sub && sub.options.applicationServerKey && b64url(sub.options.applicationServerKey) !== S.vapidKey) { await sub.unsubscribe(); sub = null; }
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await request(OP.PUSH_SUBSCRIBE, { endpoint: sub.endpoint, p256dh: b64url(sub.getKey('p256dh')), auth: b64url(sub.getKey('auth')) });
  return true;
}

async function unsubscribePush() {
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (!sub) return;
  await request(OP.PUSH_SUBSCRIBE, { endpoint: sub.endpoint, p256dh: b64url(sub.getKey('p256dh')), auth: b64url(sub.getKey('auth')), active: 'false' }).catch(() => {});
  await sub.unsubscribe();
}

/** Replies and "open" clicks from notifications arrive here (or as ?open=&reply= on a cold start). */
function handleLaunch(convId, text) {
  if (!convId) return;
  if (text) { sendText(convId, text); toast('reply sent'); }
  openChat(convId);
}
/** A URL is untrusted (anyone can send a link), so it may only open a chat and pre-fill the
 *  composer; the user presses Send. Direct replies come from the service worker by postMessage. */
function handleLaunchParams() {
  const q = new URLSearchParams(location.search);
  if (!q.has('open')) return;
  openChat(q.get('open'));
  if (q.get('reply')) { $('input').value = q.get('reply'); $('input').focus(); toast('press SEND to send your reply'); }
  history.replaceState(null, '', '/');
}
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', (e) => {
    const d = e.data || {};
    if (d.type === 'open') handleLaunch(d.conv, d.reply);
    if (d.type === 'read' && d.conv) { const c = S.convs.get(d.conv); if (c) { c.unread = 0; renderChats(); } }
  });
}

// ---- status (online / away / do not disturb / invisible) and automatic idle ----
function setMyStatus(status, tell) {
  S.myStatus = status;
  store.set('ember.status', status === 'online' || status === 'idle' ? null : status);
  $('myDot').className = 'sdot ' + status;
  $('statusBtn').title = 'Status: ' + STATUS[status].label + ' (click to change)';
  if (tell) send(OP.STATUS_SET, { status });
}
function isActive() { return !document.hidden && Date.now() - S.lastActive < 5 * 60000; }
for (const ev of ['pointerdown', 'keydown', 'focus']) addEventListener(ev, () => {
  S.lastActive = Date.now();
  if (S.myStatus === 'idle') setMyStatus('online', true);
}, { passive: true });
setInterval(() => { if (S.me && S.myStatus === 'online' && !isActive()) setMyStatus('idle', true); }, 30000);

// ---- popover menus (status and themes) ----
function openMenu(anchor, items, label) {
  closeMenu();
  const menu = h('div', { class: 'menu window', role: 'menu', 'aria-label': label },
    ...items.map((it) => h('button', { role: 'menuitemradio', 'aria-checked': String(!!it.checked), class: 'menu-item', onclick: () => { closeMenu(); it.onpick(); } },
      it.icon || '', it.label)));
  document.body.append(menu);
  const r = anchor.getBoundingClientRect();
  menu.style.left = Math.max(8, Math.min(r.left, innerWidth - menu.offsetWidth - 8)) + 'px';
  menu.style.top = (r.bottom + 6) + 'px';
  menu.querySelector('[aria-checked="true"], button').focus();
  openMenu.current = menu;
  setTimeout(() => addEventListener('pointerdown', outside), 0);
}
function outside(e) { if (openMenu.current && !openMenu.current.contains(e.target)) closeMenu(); }
function closeMenu() { if (openMenu.current) { openMenu.current.remove(); openMenu.current = null; removeEventListener('pointerdown', outside); } }
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { closeMenu(); if (!$('overlay').hidden) closeOverlay(); }
  const menu = openMenu.current;
  if (menu && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
    const items = [...menu.querySelectorAll('button')], i = items.indexOf(document.activeElement);
    items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length].focus(); e.preventDefault();
  }
});

const THEMES = [
  ['amber', 'Amber terminal'], ['green', 'Phosphor green'], ['paper', 'Paper'], ['c64', 'C64 blue'],
  ['synth', 'Synthwave'], ['pocket', 'Pocket handheld'], ['mono', 'Mono CRT'],
];
function applyTheme(t) {
  if (!THEMES.some(([id]) => id === t)) t = 'amber';
  document.documentElement.dataset.theme = t;
  document.querySelector('meta[name="theme-color"]').content = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim();
}
$('themeBtn').onclick = (e) => openMenu(e.currentTarget, THEMES.map(([id, label]) => ({
  label, checked: document.documentElement.dataset.theme === id, icon: h('span', { class: 'swatch', 'data-swatch': id }),
  onpick: () => { applyTheme(id); store.set('ember.theme', id); },
})), 'Themes');
$('statusBtn').onclick = (e) => openMenu(e.currentTarget, ['online', 'away', 'dnd', 'invisible'].map((s) => ({
  label: STATUS[s].label, checked: S.myStatus === s || (s === 'online' && S.myStatus === 'idle'),
  icon: h('span', { class: 'sdot ' + s }), onpick: () => { setMyStatus(s, true); toast('status: ' + STATUS[s].label.toLowerCase()); },
})), 'Status');

// ---- rendering (batched to one paint per frame, keeps CPU idle between events) ----
let renderQueued = false;
function renderSoon() {
  if (renderQueued || !S.synced) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; render(); });
}

function render() {
  renderChats();
  if (S.view === 'chat') renderMessages();
  renderHeader();
}

function renderHeader() {
  const c = S.view === 'chat' && S.active ? S.convs.get(S.active) : null;
  const group = !!(c && c.id.startsWith('grp:'));
  if (c) {
    $('chatTitle').textContent = '▣ ' + title(c);
    const p = peer(c);
    $('chatSub').textContent = p ? (statusText(p) || '@' + p) : (c.members ? c.members.length + ' members' : '');
  }
  $('searchBtn').hidden = $('starBtn').hidden = !c;
  $('pollBtn').hidden = $('addMember').hidden = !group;
  $('callBtn').hidden = $('videoBtn').hidden = !c; // direct and group calls
  $('composer').hidden = !c;
  if (!c) { $('searchBar').hidden = true; $('pinBar').hidden = true; $('polls').replaceChildren(); $('typing').textContent = ''; $('replyBar').hidden = true; }
}

function renderChats() {
  const q = $('filter').value.trim().toLowerCase();
  const list = [...S.convs.values()]
    .filter((c) => !q || title(c).toLowerCase().includes(q))
    .sort((a, b) => ((lastMsg(b) || {}).ts || 0) - ((lastMsg(a) || {}).ts || 0));
  const total = list.reduce((n, c) => n + c.unread, 0);
  document.title = (total ? '(' + total + ') ' : '') + 'Ember';
  if (navigator.setAppBadge) (total ? navigator.setAppBadge(total) : navigator.clearAppBadge()).catch(() => {});
  $('chats').replaceChildren(...(list.length ? list.map((c) => {
    const last = lastMsg(c), p = peer(c);
    return h('li', { class: c.id === S.active && S.view === 'chat' ? 'active' : '', onclick: () => openChat(c.id), tabIndex: 0,
      onkeydown: (e) => { if (e.key === 'Enter') openChat(c.id); } },
    h('span', { class: 'name' }, p ? h('span', { class: 'sdot ' + statusOf(p), title: statusText(p) || 'offline' }) : '', p ? ' ' : '', title(c)),
    c.unread ? h('span', { class: 'badge' }, String(c.unread)) : h('span'),
    h('span', { class: 'last' }, last ? (last.from === S.me ? 'you: ' : '') + last.body : 'no messages yet'));
  }) : [h('li', { class: 'dim' }, q ? 'no match' : 'no chats — start one with + DM')]));
}

function renderMessages() {
  const box = $('content'), c = S.convs.get(S.active);
  if (!c) {
    box.replaceChildren(h('div', { class: 'empty' }, '┌────────────────────┐\n│ pick a chat, or    │\n│ start one: + DM    │\n└────────────────────┘'));
    return;
  }
  renderPolls(c);
  renderPins(c);
  const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const q = S.query.toLowerCase();
  const all = sorted(c).filter((m) => (!q || m.body.toLowerCase().includes(q)) && (!S.starredOnly || m.starred));
  // Only the newest slice is in the DOM, so huge histories stay light on memory.
  const shown = all.slice(-S.window);
  const nodes = shown.map((m) => messageNode(c, m));
  if (all.length > shown.length) {
    nodes.unshift(h('button', { class: 'more', onclick: () => { S.window += 150; renderMessages(); } }, '▲ earlier (' + (all.length - shown.length) + ')'));
  }
  if (!nodes.length) {
    nodes.push(h('div', { class: 'empty' }, S.starredOnly ? 'no starred messages here' : q ? 'nothing matches "' + S.query + '"' : 'no messages yet.\nsay something_'));
  }
  box.replaceChildren(...nodes);
  if (stick && !q && !S.starredOnly) box.scrollTop = box.scrollHeight;
  for (const m of shown) {
    if (m.unreadPending && !document.hidden) { m.unreadPending = false; send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: 'READ' }); }
  }
}

/** Long messages show one short segment and a single "Read more" button. */
function bodyNode(m) {
  const lines = m.body.split('\n');
  const long = m.body.length > READ_MORE_CHARS || lines.length > READ_MORE_LINES;
  if (!long || S.expanded.has(m.id)) return h('div', { class: 'body' }, m.body);
  let cut = lines.slice(0, READ_MORE_LINES).join('\n').slice(0, READ_MORE_CHARS);
  const space = cut.lastIndexOf(' ');
  if (space > READ_MORE_CHARS * 0.8) cut = cut.slice(0, space);
  return h('div', { class: 'body' }, cut + '… ', h('button', { class: 'link readmore', onclick: (e) => {
    e.stopPropagation(); S.expanded.add(m.id); e.currentTarget.closest('.body').replaceWith(h('div', { class: 'body' }, m.body));
  } }, 'Read more'));
}

function messageNode(c, m) {
  const mine = m.from === S.me, reacts = Object.entries(m.reacts).filter(([, n]) => n > 0);
  const myR = S.myReacts.get(m.id) || new Set();
  return h('article', { class: 'msg' + (mine ? ' mine' : '') + (m.pending ? ' pending' : '') + (m.pinned ? ' pinned' : ''), id: 'm-' + m.id,
    onclick: (e) => { if (e.target.tagName !== 'BUTTON') e.currentTarget.classList.toggle('sel'); } },
  !mine && c.id.startsWith('grp:') ? h('button', { class: 'who link', onclick: () => openProfile(m.from) }, name(m.from)) : null,
  replyText(c, m) ? h('div', { class: 'quote' }, '↳ ' + replyText(c, m)) : null,
  bodyNode(m),
  reacts.length ? h('div', { class: 'reacts' }, ...reacts.map(([e, n]) => h('span', {}, e + n))) : null,
  h('div', { class: 'meta', title: m.e2e === 'true' ? 'end-to-end encrypted' : m.e2e ? '' : 'not end-to-end encrypted (sent before encryption was set up)' },
    (m.e2e === 'true' || m.pending ? '' : m.e2e ? '' : '🔓 ') + (m.pinned ? '📌 ' : '') + (m.starred ? '★ ' : '') + (m.expires ? '⏱ ' : '') + (m.edited ? 'edited · ' : '') + when(m.ts)
    + (mine ? (m.read ? ' ✓✓' : ' ✓') : '')),
  h('div', { class: 'actions' },
    h('button', { onclick: () => startReply(m) }, 'reply'),
    ...REACTIONS.map((e) => h('button', { onclick: () => send(OP.REACT, { clientMsgId: m.id, emoji: e, remove: String(myR.has(e)) }),
      'aria-pressed': String(myR.has(e)), title: 'react ' + e }, e)),
    h('button', { onclick: () => send(OP.PIN, { clientMsgId: m.id, pinned: String(!m.pinned) }), title: m.pinned ? 'unpin' : 'pin for everyone' }, m.pinned ? 'unpin' : '📌'),
    h('button', { onclick: () => send(OP.STAR, { clientMsgId: m.id, starred: String(!m.starred) }), title: m.starred ? 'unstar' : 'star (only you see it)' }, m.starred ? '☆' : '★'),
    mine ? h('button', { onclick: () => editMsg(m) }, 'edit') : null,
    mine ? h('button', { onclick: () => { if (confirm('Delete this message for everyone?')) send(OP.DELETE, { clientMsgId: m.id }); } }, 'del') : null,
    mine ? null : h('button', { onclick: () => report('message', m.id), title: 'report' }, '⚑')));
}

/** The quoted text of a reply: from the server, or (for encrypted chats) from our own copy. */
function replyText(c, m) {
  if (m.replyPreview) return m.replyPreview;
  const parent = m.replyTo && c.msgs.get(m.replyTo);
  return parent ? parent.body.slice(0, 100) : '';
}

/** Pinned messages: a bar at the top; each click jumps to the next pin. */
function renderPins(c) {
  const pins = sorted(c).filter((m) => m.pinned);
  const bar = $('pinBar');
  bar.hidden = !pins.length;
  if (!pins.length) return;
  const m = pins[S.pinIndex % pins.length];
  bar.textContent = '📌 ' + (pins.length > 1 ? (S.pinIndex % pins.length + 1) + '/' + pins.length + ' · ' : '') + m.body.slice(0, 90);
  bar.onclick = () => {
    const el = $('m-' + m.id);
    if (el) { el.scrollIntoView({ block: 'center' }); el.classList.add('flash'); setTimeout(() => el.classList.remove('flash'), 1200); }
    S.pinIndex++; renderPins(c);
  };
}

function renderPolls(c) {
  const polls = [...S.polls.values()].filter((p) => 'grp:' + p.groupId === c.id);
  $('polls').replaceChildren(...polls.map((p) => {
    const members = Array.from({ length: +p['member.n'] }, (_, i) => p['member.' + i]);
    const myVote = p['vote.' + members.indexOf(S.me)];
    const totals = Array.from({ length: +p['option.n'] }, (_, i) => +p['total.' + i]);
    const sum = totals.reduce((a, b) => a + b, 0) || 1;
    return h('div', { class: 'poll' }, h('div', { class: 'q' }, '📊 ' + p.question),
      ...totals.map((n, i) => {
        const bar = h('span', { class: 'bar' });
        bar.style.width = Math.round((100 * n) / sum) + '%';
        return h('button', { class: 'opt' + (myVote === String(i) ? ' chosen' : ''),
          onclick: () => send(OP.POLL_VOTE, { pollId: p.pollId, option: i }) }, bar,
        h('span', { class: 'lbl' }, p['option.' + i]), h('span', { class: 'n' }, String(n)));
      }));
  }));
}

function renderTyping() {
  const who = [...S.typingTimers.keys()].filter((k) => k.startsWith(S.active + '|')).map((k) => name(k.split('|')[1]));
  $('typing').textContent = S.view === 'chat' && who.length ? who.join(', ') + ' typing…' : '';
}

// ---- Campus Pulse ----
async function openPulse() {
  setView('pulse', null);
  $('chatTitle').textContent = '▣ CAMPUS PULSE'; $('chatSub').textContent = 'latest posts';
  $('content').replaceChildren(h('div', { class: 'empty' }, 'loading…'));
  try {
    const posts = postsFrom(await request(OP.FEED_PULL, { ownerUserId: '', limit: 10 }));
    await ensureNames(posts.map((p) => p.owner));
    if (S.view === 'pulse') {
      $('content').replaceChildren(...(posts.length ? posts.map(postNode) : [h('div', { class: 'empty' }, 'no posts yet.\nshare one with + POST')]));
    }
  } catch (e) { toast('⚠ ' + e.message); }
  loadStories();
}

function postsFrom(f) {
  return Array.from({ length: +f['post.n'] || 0 }, (_, i) => ({
    id: f['post.' + i], owner: f['owner.' + i], media: f['media.' + i], caption: f['caption.' + i],
    created: +f['created.' + i], likes: +f['likes.' + i], comments: +f['comments.' + i], liked: f['liked.' + i] === 'true',
  }));
}

function postNode(p) {
  const likeBtn = h('button', { 'aria-pressed': String(p.liked) }, '♥ ' + p.likes);
  const commentBox = h('div', { class: 'comments', hidden: true });
  likeBtn.onclick = async () => {
    try {
      const [u] = postsFrom(await request(OP.POST_INTERACT, { postId: p.id, action: p.liked ? 'unlike' : 'like', text: '' }));
      Object.assign(p, u); likeBtn.textContent = '♥ ' + p.likes; likeBtn.setAttribute('aria-pressed', String(p.liked));
    } catch (e) { toast('⚠ ' + e.message); }
  };
  const showComments = async () => {
    commentBox.hidden = !commentBox.hidden;
    if (commentBox.hidden) return;
    const f = await request(OP.POST_INTERACT, { postId: p.id, action: 'comments', text: '' });
    await ensureNames(Array.from({ length: +f['comment.n'] || 0 }, (_, i) => f['comment.author.' + i]));
    const input = h('input', { placeholder: 'add a comment…', maxLength: 500, 'aria-label': 'Comment' });
    const form = h('form', { class: 'row gap', onsubmit: async (e) => {
      e.preventDefault();
      if (!input.value.trim()) return;
      await request(OP.POST_INTERACT, { postId: p.id, action: 'comment', text: input.value.trim() }).catch((x) => toast('⚠ ' + x.message));
      commentBox.hidden = true; showComments();
    } }, input, h('button', {}, 'POST'));
    commentBox.replaceChildren(...Array.from({ length: +f['comment.n'] || 0 }, (_, i) => h('p', {},
      h('button', { class: 'link', onclick: () => openProfile(f['comment.author.' + i]) }, name(f['comment.author.' + i])),
      ' ' + f['comment.text.' + i])), form);
  };
  return h('article', { class: 'post' },
    h('header', {}, h('button', { class: 'link', onclick: () => openProfile(p.owner) }, name(p.owner)), h('span', { class: 'dim' }, when(p.created))),
    h('button', { class: 'picbtn', onclick: () => viewImage(p.media, p.caption), 'aria-label': 'Open image' }, img(p.media, false, p.caption)),
    p.caption ? h('p', {}, p.caption) : null,
    h('div', { class: 'row gap tight' }, likeBtn, h('button', { onclick: showComments }, '💬 ' + p.comments),
      p.owner !== S.me ? h('button', { onclick: () => report('post', p.id), title: 'report' }, '⚑') : h('span')),
    commentBox);
}

async function loadStories() {
  try {
    const f = await request(OP.STORY_VIEW);
    S.stories = Array.from({ length: +f['story.n'] || 0 }, (_, i) => ({
      owner: f['owner.' + i], media: f['media.' + i], caption: f['caption.' + i], created: +f['created.' + i] }));
    const owners = [...new Set(S.stories.map((s) => s.owner))];
    await ensureNames(owners);
    $('stories').replaceChildren(...(owners.length ? owners.map((o) => h('li', { tabIndex: 0, onclick: () => playStories(o),
      onkeydown: (e) => { if (e.key === 'Enter') playStories(o); } },
    h('span', { class: 'name' }, '◉ ' + name(o)), h('span', { class: 'badge' }, String(S.stories.filter((s) => s.owner === o).length))))
      : [h('li', { class: 'dim' }, 'no stories right now')]));
  } catch { /* offline: keep the old list */ }
}

function playStories(owner) {
  const list = S.stories.filter((s) => s.owner === owner);
  let i = 0;
  const next = () => {
    if (i >= list.length || ($('overlay').hidden && i > 0)) { closeOverlay(); return; }
    viewImage(list[i].media, name(owner) + (list[i].caption ? ' — ' + list[i].caption : ''));
    i++;
    clearTimeout(playStories.timer); playStories.timer = setTimeout(next, 5000);
  };
  next();
}

function viewImage(mediaId, caption) {
  $('overlayImg').removeAttribute('src');
  mediaUrl(mediaId, true).then((u) => { $('overlayImg').src = u; }).catch(() => toast('image unavailable'));
  $('overlayCap').textContent = caption || '';
  $('overlay').hidden = false;
  $('overlayClose').focus();
}

function closeOverlay() { clearTimeout(playStories.timer); $('overlay').hidden = true; }

// ---- profiles and follow requests ----
async function openProfile(userId) {
  setView('profile', userId);
  $('chatTitle').textContent = '▣ ' + name(userId); $('chatSub').textContent = '@' + userId;
  const box = $('content');
  box.replaceChildren(h('div', { class: 'empty' }, 'loading…'));
  let p = null;
  try { p = await request(OP.PROFILE_GET, { userId }); } catch { /* private or missing */ }
  if (S.view !== 'profile' || S.viewArg !== userId) return;
  if (p) $('chatTitle').textContent = '▣ ' + p.displayName;
  const isMe = userId === S.me;
  const following = !!(p && p.following === 'true');
  const tools = h('div', { class: 'row gap wrap' });
  if (isMe) {
    tools.append(h('button', { onclick: editProfile }, 'EDIT PROFILE'));
  } else {
    tools.append(h('button', { onclick: () => openChat('dm:' + [S.me, userId].sort().join(':')) }, 'MESSAGE'),
      h('button', { onclick: () => follow(userId, !following) }, following ? 'UNFOLLOW' : 'FOLLOW'),
      h('button', { onclick: () => block(userId) }, 'BLOCK'),
      h('button', { onclick: () => report('profile', userId) }, 'REPORT'));
  }
  box.replaceChildren(h('section', { class: 'profile' },
    h('h2', {}, p ? p.displayName : userId),
    h('p', { class: 'dim' }, '@' + userId + (p && p.privateAccount === 'true' ? ' · 🔒 private' : '') + (statusText(userId) ? ' · ' + statusText(userId) : '')),
    p ? h('p', {}, p.bio || '') : h('p', { class: 'dim' }, 'This account is private. Follow to request access.'),
    p ? h('p', { class: 'dim' }, p.followerCount + ' followers · ' + p.followingCount + ' following') : null,
    isMe ? null : safetyNode(userId),
    isMe ? requestsNode() : null,
    tools));
  if (!p) return;
  try {
    const posts = postsFrom(await request(OP.FEED_PULL, { ownerUserId: userId, limit: 10 }));
    if (S.view === 'profile' && S.viewArg === userId) box.append(...posts.map(postNode));
  } catch { /* no visible posts */ }
}

/** Safety number for a chat: the same digits on both people's screens mean nobody is in between. */
function safetyNode(userId) {
  const el = h('p', { class: 'dim small' }, '🔒 checking encryption…');
  safetyNumber(userId).then((n) => {
    el.textContent = !n ? '🔓 no encryption keys yet (they have not opened the new app)'
      : '🔒 this device: ' + n.mine + (n.myOthers.length ? ' · your other devices: ' + n.myOthers.join(' / ') : '')
        + ' · ' + name(userId) + "'s devices: " + n.theirs.join(' / ')
        + '. Check together: each of you finds your own device codes on the other screen, and nobody sees a code they do not recognise.';
  })
    .catch(() => { el.textContent = ''; });
  return el;
}

function setPending(f) {
  S.pending = Array.from({ length: +f['pending.n'] || 0 }, (_, i) => f['pending.' + i]);
  $('meBadge').textContent = String(S.pending.length);
  $('meBadge').hidden = !S.pending.length;
  ensureNames(S.pending).then(() => { $('requests').replaceChildren(requestsNode()); });
}

function requestsNode() {
  if (!S.pending.length) return h('p', { class: 'dim small' }, 'NO FOLLOW REQUESTS');
  return h('div', { class: 'requests' }, h('p', { class: 'small' }, 'FOLLOW REQUESTS · ' + S.pending.length),
    ...S.pending.map((u) => h('div', { class: 'request' },
      h('button', { class: 'link', onclick: () => openProfile(u) }, name(u)),
      h('span', { class: 'row gap tight' },
        h('button', { onclick: () => approve(u, true) }, 'ACCEPT'),
        h('button', { onclick: () => approve(u, false) }, 'DECLINE')))));
}

async function approve(follower, yes) {
  try {
    await request(OP.FOLLOW, { targetUserId: follower, action: 'APPROVE', approve: String(yes) });
    toast(yes ? name(follower) + ' can now follow you' : 'request declined');
    await request(OP.PROFILE_GET, { userId: S.me });
    if (S.view === 'profile' && S.viewArg === S.me) openProfile(S.me);
  } catch (e) { toast('⚠ ' + e.message); }
}

async function editProfile() {
  const current = S.profiles.get(S.me) || {};
  const displayName = prompt('Display name (1–40 characters)', current.displayName || S.me);
  if (displayName == null) return;
  const bio = prompt('Bio (up to 280 characters)', current.bio || '');
  if (bio == null) return;
  const privateAccount = confirm('Make your account private? (OK = private, Cancel = public)');
  try {
    S.profiles.set(S.me, await request(OP.PROFILE_UPDATE, { userId: S.me, displayName: displayName.trim(), bio, privateAccount: String(privateAccount) }));
    toast('profile saved'); renderMe(); openProfile(S.me);
  } catch (e) { toast('⚠ ' + e.message); }
}

async function follow(userId, on) {
  try {
    const f = await request(OP.FOLLOW, { targetUserId: userId, follow: String(on) });
    toast(f.followStatus === 'PENDING' ? 'follow request sent' : on ? 'following' : 'unfollowed');
    openProfile(userId);
  } catch (e) { toast('⚠ ' + e.message); }
}

async function block(userId) {
  if (!confirm('Block ' + name(userId) + '? You will not see each other\'s messages or posts.')) return;
  try { await request(OP.BLOCK, { targetUserId: userId, blocked: 'true' }); toast('blocked'); } catch (e) { toast('⚠ ' + e.message); }
}

async function report(targetType, targetId) {
  const reason = (prompt('Why are you reporting this? (sent to the moderators)') || '').trim();
  if (!reason) return;
  try { await request(OP.REPORT, { targetType, targetId, reason: reason.slice(0, 300) }); toast('report sent — thank you'); } catch (e) { toast('⚠ ' + e.message); }
}

function renderMe() {
  const p = S.profiles.get(S.me);
  $('meCard').replaceChildren(h('p', {}, h('strong', {}, p ? p.displayName : S.me)), h('p', { class: 'dim' }, '@' + S.me),
    p && p.bio ? h('p', {}, p.bio) : null, h('button', { onclick: () => openProfile(S.me) }, 'VIEW MY PROFILE'));
  $('requests').replaceChildren(requestsNode());
  const blocked = 'Notification' in window && Notification.permission === 'denied';
  $('notifyBtn').textContent = '🔔 NOTIFICATIONS: ' + (blocked ? 'BLOCKED IN BROWSER' : S.notify ? 'ON' : 'OFF');
}

// ---- navigation and actions ----
function setView(view, arg) {
  S.view = view; S.viewArg = arg; S.query = ''; $('search').value = '';
  if (view !== 'chat') { $('polls').replaceChildren(); $('pinBar').hidden = true; }
  $('app').classList.add('open');
  renderHeader();
  renderChats();
}

function setTab(tab) {
  for (const b of document.querySelectorAll('.tabs button')) b.setAttribute('aria-selected', String(b.dataset.tab === tab));
  for (const t of ['chats', 'pulse', 'me']) $('tab-' + t).hidden = t !== tab;
  if (tab === 'pulse') openPulse();
  if (tab === 'me') { renderMe(); request(OP.PROFILE_GET, { userId: S.me }).then(renderMe).catch(() => {}); }
}

function openChat(id) {
  S.active = id; S.window = 150; S.replyTo = null; S.starredOnly = false; S.pinIndex = 0;
  $('starBtn').setAttribute('aria-pressed', 'false'); $('replyBar').hidden = true;
  const c = conv(id);
  c.unread = 0;
  setTab('chats');
  setView('chat', id);
  renderMessages(); renderTyping();
  $('content').scrollTop = $('content').scrollHeight;
  if (matchMedia('(min-width: 721px)').matches) $('input').focus();
}

function startReply(m) {
  S.replyTo = m.id; $('replyBar').hidden = false;
  $('replyText').textContent = '↳ ' + m.body.slice(0, 100);
  $('input').focus();
}

function editMsg(m) {
  const body = prompt('Edit message', m.body);
  if (!body || !body.trim() || body === m.body) return;
  sealBody(m.conv, m.id, body).then((sealed) => send(OP.EDIT, { clientMsgId: m.id, newBody: sealed }))
    .catch((e) => toast('⚠ ' + e.message));
}

/** Sends a message to any chat (the composer, notification replies and quick replies). */
function sendText(convId, body, replyTo = '', ttl = 0) {
  const id = crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(36).slice(2);
  const c = conv(convId), parent = replyTo && c.msgs.get(replyTo);
  const m = { id, conv: c.id, from: S.me, body, ts: Date.now(), seq: 0, reacts: {}, pending: true,
    replyPreview: parent ? parent.body.slice(0, 100) : '', expires: ttl ? 1 : 0, e2e: 'true' };
  c.msgs.set(id, m);
  // Encrypted on this device first; the server only ever sees the sealed text.
  sealBody(c.id, id, body).then((sealed) => {
    if (!sealed.startsWith('e2e1.')) m.e2e = '';
    send(OP.SEND, { clientMsgId: id, convId: c.id, body: sealed, ttlSec: ttl, replyToId: replyTo });
  }).catch((e) => { c.msgs.delete(id); renderSoon(); toast('⚠ ' + e.message); });
  renderSoon();
}

function sendCurrent() {
  const input = $('input'), body = input.value.trim();
  if (!body || !S.active) return;
  sendText(S.active, body, S.replyTo || '', S.ttl);
  send(OP.TYPING, { convId: S.active, typing: 'false' }); S.typingSent = 0;
  input.value = ''; input.style.height = '';
  S.replyTo = null; $('replyBar').hidden = true;
  S.query = ''; $('search').value = '';
  renderChats(); renderMessages();
  $('content').scrollTop = $('content').scrollHeight;
}

function showLogin() { $('app').hidden = true; $('login').hidden = false; $('user').focus(); }
function showApp() { $('login').hidden = true; $('app').hidden = false; $('me').textContent = S.me; setMyStatus(S.myStatus, false); renderHeader(); }

// ---- wiring ----
let registering = false;
$('modeBtn').onclick = () => {
  registering = !registering;
  $('nameRow').hidden = !registering;
  $('loginBtn').textContent = registering ? '> CREATE ACCOUNT' : '> LOG IN';
  $('modeBtn').textContent = registering ? 'Have an account? Log in' : 'New here? Create an account';
  $('pass').autocomplete = registering ? 'new-password' : 'current-password';
};
$('loginForm').onsubmit = async (e) => {
  e.preventDefault();
  $('loginError').textContent = ''; $('loginBtn').disabled = true;
  const user = $('user').value.trim(), pass = $('pass').value;
  // A returning user's saved chats load now; the server then sends only what changed.
  if (!registering && await restoreCache(user.toLowerCase())) { S.synced = true; }
  connect(() => {
    S.pendingAuth = registering
      ? send(OP.REGISTER, { user, pass, calls: '1', displayName: $('displayName').value.trim() || user })
      : send(OP.LOGIN, { user, pass, calls: '1', ...syncFields() });
    $('pass').value = ''; $('loginBtn').disabled = false;
  });
};
$('composer').onsubmit = (e) => { e.preventDefault(); sendCurrent(); };
$('input').onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendCurrent(); } };
$('input').oninput = (e) => {
  const t = e.target; t.style.height = ''; t.style.height = t.scrollHeight + 'px';
  if (S.active && Date.now() - S.typingSent > 3000) { S.typingSent = Date.now(); send(OP.TYPING, { convId: S.active, typing: 'true' }); }
};
$('ttlBtn').onclick = (e) => { S.ttl = S.ttl ? 0 : 15; e.currentTarget.setAttribute('aria-pressed', String(!!S.ttl)); toast(S.ttl ? 'next messages vanish after 15 s' : 'messages stay'); };
$('replyCancel').onclick = () => { S.replyTo = null; $('replyBar').hidden = true; };
$('filter').oninput = renderChats;
$('searchBtn').onclick = () => {
  $('searchBar').hidden = !$('searchBar').hidden;
  if ($('searchBar').hidden) { S.query = ''; $('search').value = ''; renderMessages(); } else $('search').focus();
};
$('starBtn').onclick = (e) => { S.starredOnly = !S.starredOnly; e.currentTarget.setAttribute('aria-pressed', String(S.starredOnly)); renderMessages(); };
$('search').oninput = (e) => { S.query = e.target.value.trim(); renderMessages(); };
$('back').onclick = () => { $('app').classList.remove('open'); S.active = null; S.view = 'chat'; renderHeader(); renderChats(); };
$('chatTitle').onclick = () => {
  const c = S.view === 'chat' && S.convs.get(S.active), p = c && peer(c);
  if (p) openProfile(p); else if (c && c.members) toast('members: ' + c.members.map(name).join(', '));
};
for (const b of document.querySelectorAll('.tabs button')) b.onclick = () => setTab(b.dataset.tab);
$('newDm').onclick = () => {
  const u = (prompt('Username to message') || '').trim().toLowerCase();
  if (!u) return;
  if (!/^[a-z0-9_]{3,20}$/.test(u)) { toast('usernames are 3–20 letters, digits or _'); return; }
  if (u === S.me) { toast('that is you'); return; }
  openChat('dm:' + [S.me, u].sort().join(':'));
};
$('newGroup').onclick = async () => {
  const groupName = (prompt('Group name') || '').trim();
  if (!groupName) return;
  try { const g = await request(OP.GROUP_CREATE, { name: groupName, desc: '', 'member.n': 0 }); openChat('grp:' + g.groupId); } catch (e) { toast('⚠ ' + e.message); }
};
$('addMember').onclick = () => {
  const u = (prompt('Username to add') || '').trim().toLowerCase();
  if (u && S.active) send(OP.GROUP_MEMBER, { groupId: S.active.slice(4), targetUser: u, action: 'ADD' });
};
$('pollBtn').onclick = () => {
  const question = (prompt('Poll question') || '').trim();
  if (!question) return;
  const options = (prompt('2–6 options, separated by commas') || '').split(',').map((o) => o.trim()).filter(Boolean);
  if (options.length < 2 || options.length > 6) { toast('a poll needs 2–6 options'); return; }
  const fields = { groupId: S.active.slice(4), question, 'option.n': options.length };
  options.forEach((o, i) => { fields['option.' + i] = o; });
  send(OP.POLL_CREATE, fields);
};
async function share(kind) {
  try {
    const mediaId = await pickImage();
    if (!mediaId) return;
    const caption = (prompt(kind === 'post' ? 'Caption (optional)' : 'Story caption (optional)') || '').trim();
    if (kind === 'post') { await request(OP.POST_CREATE, { mediaId, caption }); openPulse(); } else { await request(OP.STORY_CREATE, { mediaId, caption }); loadStories(); }
    toast(kind + ' shared');
  } catch (e) { toast('⚠ ' + e.message); }
}
$('newPost').onclick = () => share('post');
$('newStory').onclick = () => share('story');
$('overlayClose').onclick = closeOverlay;
$('overlay').onclick = (e) => { if (e.target === $('overlay')) closeOverlay(); };
$('notifyBtn').onclick = async () => {
  try {
    if (S.notify) {
      S.notify = false; store.set('ember.notify', null); await unsubscribePush();
    } else {
      if (!('Notification' in window)) { toast('this browser has no notifications'); return; }
      if ((await Notification.requestPermission()) !== 'granted') { toast('notifications are blocked in the browser settings'); renderMe(); return; }
      S.notify = true; store.set('ember.notify', '1');
      toast((await subscribePush()) ? 'notifications on — even when Ember is closed' : 'notifications on while Ember is open');
    }
  } catch (e) { toast('⚠ ' + e.message); }
  renderMe();
};
$('logoutBtn').onclick = () => {
  hangUp();
  e2eRetire(S.me); // this device stops receiving encrypted messages and forgets its key
  send(OP.LOGOUT);
  cache.clear(S.me); // a shared computer keeps no copy of the chats
  unsubscribePush().catch(() => {});
  store.set('ember.token', null);
  setTimeout(() => location.reload(), 300);
};
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) { S.lastActive = Date.now(); if (S.myStatus === 'idle') setMyStatus('online', true); renderSoon(); }
});

applyTheme(store.get('ember.theme') || (matchMedia('(prefers-color-scheme: light)').matches ? 'paper' : 'amber'));
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
(async () => {
  if (!S.token) { showLogin(); return; }
  S.me = store.get('ember.user');
  showApp();
  if (await restoreCache(S.me)) { S.synced = true; render(); setStatus('showing saved chats · connecting…'); }
  connect(resume);
})();
