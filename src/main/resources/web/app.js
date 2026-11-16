'use strict';
// Ember web client. Speaks the same binary frames as the desktop app, over a WebSocket.

// ---- wire protocol (mirrors FrameCodec / KvPayloadCodec / WireOpcodes) ----
const OP = {
  REGISTER: 0x01, LOGIN: 0x02, RESUME: 0x03, LOGOUT: 0x04, PING: 0x05,
  SEND: 0x10, RECEIPT: 0x11, REACT: 0x12, EDIT: 0x13, DELETE: 0x14, TYPING: 0x17,
  GROUP_CREATE: 0x20, GROUP_MEMBER: 0x21, GROUP_INFO: 0x23,
  AUTH_OK: 0x40, PONG: 0x41, ACK: 0x42, DELIVER: 0x43, RECEIPT_UPDATE: 0x44, UPDATE: 0x45,
  TYPING_EVT: 0x46, PRESENCE: 0x47, SYNC_ITEM: 0x48, SYNC_DONE: 0x49, HISTORY: 0x4A,
  GROUP_UPDATE: 0x4B, ERROR: 0x70,
};
const MAGIC = 0x454D4252, HEADER = 18;
const utf8 = new TextEncoder(), unutf8 = new TextDecoder();
const esc = (v) => String(v).replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/\r/g, '\\r');
const unesc = (v) => v.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c === 'r' ? '\r' : c));

function encode(op, corr, fields) {
  const body = utf8.encode(Object.entries(fields).map(([k, v]) => k + '=' + esc(v)).join('\n'));
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
  ws: null, corr: 0, me: null, token: store.get('ember.token'), lastSeq: 0, synced: false,
  convs: new Map(), // id -> {id, name, msgs: Map(clientMsgId -> msg), unread, members}
  online: new Set(), active: null, replyTo: null, ttl: 0, window: 150, retry: 0,
  pendingAuth: 0, pendingGroup: 0, typingSent: 0, typingTimers: new Map(), myReacts: new Map(),
};
const $ = (id) => document.getElementById(id);
const REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '🔥'];

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

// ---- connection ----
function connect(onOpen) {
  if (S.ws) S.ws.onclose = null, S.ws.close();
  setStatus('connecting…');
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/');
  ws.binaryType = 'arraybuffer';
  S.ws = ws;
  ws.onopen = () => { S.retry = 0; onOpen(); };
  ws.onmessage = (e) => handle(decode(e.data));
  ws.onclose = () => {
    setStatus('offline — retrying');
    if (!S.me) return;
    const wait = Math.min(30000, 1000 * 2 ** S.retry++);
    setTimeout(() => connect(resume), wait);
  };
}

function resume() { S.pendingAuth = send(OP.RESUME, { token: S.token, lastGlobalSeq: S.lastSeq }); }

setInterval(() => { if (S.me) send(OP.PING); }, 20000); // server drops idle sockets after 45 s

function setStatus(text) { $('status').textContent = (S.me ? S.me + ' · ' : '') + text; }

// ---- inbound events ----
function handle({ op, corr, f }) {
  switch (op) {
    case OP.AUTH_OK:
      S.me = f.userId; S.token = f.token;
      store.set('ember.token', f.token); store.set('ember.user', f.userId);
      showApp(); setStatus('syncing…');
      break;
    case OP.SYNC_ITEM: case OP.HISTORY: addMsg(f, false); break;
    case OP.SYNC_DONE: S.synced = true; setStatus('online'); renderChats(); renderMessages(); break;
    case OP.DELIVER: {
      const m = addMsg(f, true);
      if (m.from !== S.me) {
        const open = S.active === m.conv && !document.hidden;
        send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: open ? 'READ' : 'DELIVERED' });
        if (!open) m.unreadPending = true;
      }
      break;
    }
    case OP.ACK: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.pending = false; m.seq = +f.convSeq; m.ts = +f.serverTs; m.expires = +f.expiresAtMs; bumpSeq(f.globalSeq); renderSoon(); }
      break;
    }
    case OP.RECEIPT_UPDATE: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.read = f.read === f.total; m.delivered = true; renderSoon(); }
      break;
    }
    case OP.UPDATE: onUpdate(f); break;
    case OP.TYPING_EVT: onTyping(f); break;
    case OP.PRESENCE:
      f.online === 'true' ? S.online.add(f.userId) : S.online.delete(f.userId);
      renderSoon();
      break;
    case OP.GROUP_UPDATE: {
      const c = conv('grp:' + f.groupId);
      c.name = f.groupName;
      c.members = Array.from({ length: +f['member.n'] }, (_, i) => f['member.' + i]);
      if (corr && corr === S.pendingGroup) openChat(c.id); else renderSoon();
      break;
    }
    case OP.ERROR:
      if (corr && corr === S.pendingAuth) {
        if (S.me) { S.me = null; S.token = null; store.set('ember.token', null); showLogin(); }
        $('loginError').textContent = f.message;
        $('loginBtn').disabled = false;
      } else {
        toast('⚠ ' + f.message);
      }
      break;
  }
}

function onUpdate(f) {
  const m = findMsg(f.clientMsgId);
  if (f.state === 'DELETED' || f.state === 'EXPIRED') {
    const c = S.convs.get(f.convId);
    if (c) c.msgs.delete(f.clientMsgId);
  } else if (m && f.state === 'EDITED') {
    m.body = f.body; m.edited = true;
  } else if (m && f.state === 'REACTION') {
    m.reacts[f.emoji] = +f.count;
    if (f.userId === S.me) toggleMine(m.id, f.emoji, f.selected === 'true');
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

// ---- model helpers ----
function conv(id) {
  let c = S.convs.get(id);
  if (!c) {
    c = { id, name: null, msgs: new Map(), unread: 0, members: null };
    S.convs.set(id, c);
    if (id.startsWith('grp:') && S.me) send(OP.GROUP_INFO, { groupId: id.slice(4) });
  }
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
    if (live && f.senderUserId !== S.me && S.active !== c.id) c.unread++;
  }
  Object.assign(m, {
    body: f.body, ts: +f.serverTs, seq: +f.convSeq, expires: +f.expiresAtMs || 0,
    replyPreview: f.replyPreview || '', pending: false,
  });
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

function title(c) {
  if (c.id.startsWith('dm:')) return '@' + c.id.slice(3).split(':').find((u) => u !== S.me);
  return '#' + (c.name || 'group');
}

function peer(c) { return c.id.startsWith('dm:') ? c.id.slice(3).split(':').find((u) => u !== S.me) : null; }

function sorted(c) { return [...c.msgs.values()].sort((a, b) => (a.seq || 1e15) - (b.seq || 1e15) || a.ts - b.ts); }

function lastMsg(c) { let last = null; for (const m of c.msgs.values()) if (!last || m.ts > last.ts) last = m; return last; }

// ---- rendering (batched to one paint per frame, keeps CPU idle between events) ----
let renderQueued = false;
function renderSoon() {
  if (renderQueued || !S.synced) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; renderChats(); renderMessages(); });
}

function renderChats() {
  const q = $('filter').value.trim().toLowerCase();
  const list = [...S.convs.values()]
    .filter((c) => !q || title(c).toLowerCase().includes(q))
    .sort((a, b) => ((lastMsg(b) || {}).ts || 0) - ((lastMsg(a) || {}).ts || 0));
  const total = list.reduce((n, c) => n + c.unread, 0);
  document.title = (total ? '(' + total + ') ' : '') + 'Ember';
  $('chats').replaceChildren(...(list.length ? list.map((c) => {
    const last = lastMsg(c), p = peer(c);
    return h('li', { class: c.id === S.active ? 'active' : '', onclick: () => openChat(c.id), tabIndex: 0,
      onkeydown: (e) => { if (e.key === 'Enter') openChat(c.id); } },
    h('span', { class: 'name' + (p ? ' dot' + (S.online.has(p) ? ' on' : '') : '') }, title(c)),
    c.unread ? h('span', { class: 'badge' }, String(c.unread)) : h('span'),
    h('span', { class: 'last' }, last ? (last.from === S.me ? 'you: ' : '') + last.body : 'no messages yet'));
  }) : [h('li', { class: 'dim' }, q ? 'no match' : 'no chats — start one with + DM')]));
}

function renderMessages() {
  const box = $('messages'), c = S.convs.get(S.active);
  if (!c) {
    box.replaceChildren(h('div', { class: 'empty' }, '┌────────────────────┐\n│ pick a chat, or    │\n│ start one: + DM    │\n└────────────────────┘'));
    return;
  }
  const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const all = sorted(c);
  // Only the newest slice is in the DOM, so huge histories stay light on memory.
  const shown = all.slice(-S.window);
  const nodes = shown.map((m) => {
    const mine = m.from === S.me, reacts = Object.entries(m.reacts).filter(([, n]) => n > 0);
    const myR = S.myReacts.get(m.id) || new Set();
    return h('article', { class: 'msg' + (mine ? ' mine' : '') + (m.pending ? ' pending' : ''),
      onclick: (e) => { if (e.target.tagName !== 'BUTTON') e.currentTarget.classList.toggle('sel'); } },
    !mine && c.id.startsWith('grp:') ? h('div', { class: 'who' }, m.from) : null,
    m.replyPreview ? h('div', { class: 'quote' }, '↳ ' + m.replyPreview) : null,
    h('div', { class: 'body' }, m.body),
    reacts.length ? h('div', { class: 'reacts' }, ...reacts.map(([e, n]) => h('span', {}, e + n))) : null,
    h('div', { class: 'meta' }, (m.expires ? '⏱ ' : '') + (m.edited ? 'edited · ' : '')
      + new Date(m.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      + (mine ? (m.read ? ' ✓✓' : ' ✓') : '')),
    h('div', { class: 'actions' },
      h('button', { onclick: () => startReply(m) }, 'reply'),
      ...REACTIONS.map((e) => h('button', { onclick: () => send(OP.REACT, { clientMsgId: m.id, emoji: e, remove: String(myR.has(e)) }),
        'aria-pressed': String(myR.has(e)), title: 'react ' + e }, e)),
      mine ? h('button', { onclick: () => editMsg(m) }, 'edit') : null,
      mine ? h('button', { onclick: () => { if (confirm('Delete this message for everyone?')) send(OP.DELETE, { clientMsgId: m.id }); } }, 'del') : null));
  });
  if (all.length > shown.length) {
    nodes.unshift(h('button', { class: 'more', onclick: () => { S.window += 150; renderMessages(); } }, '▲ earlier (' + (all.length - shown.length) + ')'));
  }
  if (!nodes.length) nodes.push(h('div', { class: 'empty' }, 'no messages yet.\nsay something_'));
  box.replaceChildren(...nodes);
  if (stick) box.scrollTop = box.scrollHeight;
  for (const m of shown) {
    if (m.unreadPending && !document.hidden) { m.unreadPending = false; send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: 'READ' }); }
  }
}

function renderTyping() {
  const who = [...S.typingTimers.keys()].filter((k) => k.startsWith(S.active + '|')).map((k) => k.split('|')[1]);
  $('typing').textContent = who.length ? who.join(', ') + ' typing…' : '';
}

// ---- actions ----
function openChat(id) {
  S.active = id; S.window = 150; S.replyTo = null; $('replyBar').hidden = true;
  const c = conv(id);
  c.unread = 0;
  $('chatTitle').textContent = '▣ ' + title(c);
  const p = peer(c);
  $('chatSub').textContent = p ? (S.online.has(p) ? 'online' : '') : (c.members ? c.members.length + ' members' : '');
  $('addMember').hidden = !id.startsWith('grp:');
  $('input').disabled = $('sendBtn').disabled = false;
  $('app').classList.add('open');
  renderChats(); renderMessages(); renderTyping();
  $('messages').scrollTop = $('messages').scrollHeight;
  if (matchMedia('(min-width: 721px)').matches) $('input').focus();
}

function startReply(m) {
  S.replyTo = m.id; $('replyBar').hidden = false;
  $('replyText').textContent = '↳ ' + m.body.slice(0, 100);
  $('input').focus();
}

function editMsg(m) {
  const body = prompt('Edit message', m.body);
  if (body && body.trim() && body !== m.body) send(OP.EDIT, { clientMsgId: m.id, newBody: body });
}

function sendCurrent() {
  const input = $('input'), body = input.value.trim();
  if (!body || !S.active) return;
  const id = crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(36).slice(2);
  const c = conv(S.active), parent = S.replyTo && c.msgs.get(S.replyTo);
  c.msgs.set(id, { id, conv: c.id, from: S.me, body, ts: Date.now(), seq: 0, reacts: {}, pending: true,
    replyPreview: parent ? parent.body.slice(0, 100) : '', expires: S.ttl ? 1 : 0 });
  send(OP.SEND, { clientMsgId: id, convId: c.id, body, ttlSec: S.ttl, replyToId: S.replyTo || '' });
  send(OP.TYPING, { convId: c.id, typing: 'false' }); S.typingSent = 0;
  input.value = ''; input.style.height = '';
  S.replyTo = null; $('replyBar').hidden = true;
  renderChats(); renderMessages();
  $('messages').scrollTop = $('messages').scrollHeight;
}

function showLogin() { $('app').hidden = true; $('login').hidden = false; $('user').focus(); }
function showApp() { $('login').hidden = true; $('app').hidden = false; $('me').textContent = '▣ ' + S.me; }

// ---- wiring ----
let registering = false;
$('modeBtn').onclick = () => {
  registering = !registering;
  $('nameRow').hidden = !registering;
  $('loginBtn').textContent = registering ? '> CREATE ACCOUNT' : '> LOG IN';
  $('modeBtn').textContent = registering ? 'Have an account? Log in' : 'New here? Create an account';
  $('pass').autocomplete = registering ? 'new-password' : 'current-password';
};
$('loginForm').onsubmit = (e) => {
  e.preventDefault();
  $('loginError').textContent = ''; $('loginBtn').disabled = true;
  const user = $('user').value.trim(), pass = $('pass').value;
  connect(() => {
    S.pendingAuth = registering
      ? send(OP.REGISTER, { user, pass, displayName: $('displayName').value.trim() || user })
      : send(OP.LOGIN, { user, pass });
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
$('back').onclick = () => { $('app').classList.remove('open'); S.active = null; renderChats(); };
$('newDm').onclick = () => {
  const u = (prompt('Username to message') || '').trim().toLowerCase();
  if (!u) return;
  if (!/^[a-z0-9_]{3,20}$/.test(u)) return toast('usernames are 3–20 letters, digits or _');
  if (u === S.me) return toast('that is you');
  openChat('dm:' + [S.me, u].sort().join(':'));
};
$('newGroup').onclick = () => {
  const name = (prompt('Group name') || '').trim();
  if (name) S.pendingGroup = send(OP.GROUP_CREATE, { name, desc: '', 'member.n': 0 });
};
$('addMember').onclick = () => {
  const u = (prompt('Username to add') || '').trim().toLowerCase();
  if (u && S.active) send(OP.GROUP_MEMBER, { groupId: S.active.slice(4), targetUser: u, action: 'ADD' });
};
$('logoutBtn').onclick = () => {
  send(OP.LOGOUT);
  store.set('ember.token', null);
  location.reload();
};
const THEMES = ['amber', 'green', 'paper'];
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  document.querySelector('meta[name="theme-color"]').content = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim();
}
$('themeBtn').onclick = () => {
  const next = THEMES[(THEMES.indexOf(document.documentElement.dataset.theme) + 1) % THEMES.length];
  store.set('ember.theme', next); applyTheme(next); toast('theme: ' + next);
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) renderSoon(); });

applyTheme(store.get('ember.theme') || (matchMedia('(prefers-color-scheme: light)').matches ? 'paper' : 'amber'));
if (S.token) { S.me = store.get('ember.user'); showApp(); connect(resume); } else showLogin();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
