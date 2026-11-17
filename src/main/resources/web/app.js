'use strict';
// Ember web client. Speaks the same binary frames as the desktop app, over a WebSocket.
// No framework: a few hundred lines, cached by the service worker, so it costs almost no bandwidth.

// ---- wire protocol (mirrors FrameCodec / KvPayloadCodec / WireOpcodes) ----
const OP = {
  REGISTER: 0x01, LOGIN: 0x02, RESUME: 0x03, LOGOUT: 0x04, PING: 0x05,
  SEND: 0x10, RECEIPT: 0x11, REACT: 0x12, EDIT: 0x13, DELETE: 0x14, TYPING: 0x17,
  POLL_CREATE: 0x18, POLL_VOTE: 0x19, GROUP_CREATE: 0x20, GROUP_MEMBER: 0x21, GROUP_INFO: 0x23,
  PROFILE_UPDATE: 0x30, FOLLOW: 0x31, MEDIA_UPLOAD: 0x32, POST_CREATE: 0x33, POST_INTERACT: 0x34,
  STORY_CREATE: 0x35, STORY_VIEW: 0x36, BLOCK: 0x37, REPORT: 0x38, FEED_PULL: 0x3A, PROFILE_GET: 0x3B,
  AUTH_OK: 0x40, PONG: 0x41, ACK: 0x42, DELIVER: 0x43, RECEIPT_UPDATE: 0x44, UPDATE: 0x45,
  TYPING_EVT: 0x46, PRESENCE: 0x47, SYNC_ITEM: 0x48, SYNC_DONE: 0x49, HISTORY: 0x4A,
  GROUP_UPDATE: 0x4B, MEDIA_OK: 0x50, FEED_PAGE: 0x52, PROFILE_DATA: 0x53, NOTIFY: 0x54,
  STORY_UPDATE: 0x55, POLL_UPDATE: 0x57, ERROR: 0x70,
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
  online: new Set(), active: null, view: 'chat', viewArg: null, replyTo: null, ttl: 0, window: 150,
  retry: 0, pendingAuth: 0, typingSent: 0, typingTimers: new Map(), myReacts: new Map(),
  waiting: new Map(), profiles: new Map(), asked: new Set(), polls: new Map(), media: new Map(),
  stories: [], query: '', notify: store.get('ember.notify') === '1',
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

/** Sends a command and resolves with the first reply that carries its correlation id. */
function request(op, fields = {}) {
  return new Promise((resolve, reject) => {
    if (!S.ws || S.ws.readyState !== 1) return reject(new Error('offline'));
    S.waiting.set(send(op, fields), { resolve, reject });
  });
}

// ---- connection ----
function connect(onOpen) {
  if (S.ws) { S.ws.onclose = null; S.ws.close(); }
  setStatus('connecting…');
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/');
  ws.binaryType = 'arraybuffer';
  S.ws = ws;
  ws.onopen = () => { S.retry = 0; onOpen(); };
  ws.onmessage = (e) => handle(decode(e.data));
  ws.onclose = () => {
    for (const w of S.waiting.values()) w.reject(new Error('offline'));
    S.waiting.clear();
    setStatus('offline — retrying');
    if (!S.me) return;
    setTimeout(() => connect(resume), Math.min(30000, 1000 * 2 ** S.retry++));
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
    case OP.SYNC_DONE: S.synced = true; setStatus('online'); render(); break;
    case OP.DELIVER: onDeliver(addMsg(f, true)); break;
    case OP.ACK: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.pending = false; m.seq = +f.convSeq; m.ts = +f.serverTs; m.expires = +f.expiresAtMs; bumpSeq(f.globalSeq); renderSoon(); }
      break;
    }
    case OP.RECEIPT_UPDATE: {
      const m = findMsg(f.clientMsgId);
      if (m) { m.read = f.read === f.total; renderSoon(); }
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
      renderSoon();
      break;
    }
    case OP.POLL_UPDATE: S.polls.set(f.pollId, f); renderSoon(); break;
    case OP.PROFILE_DATA:
      if (f.userId && f.displayName != null) S.profiles.set(f.userId, f);
      renderSoon();
      break;
    case OP.ERROR:
      if (corr && corr === S.pendingAuth) {
        if (S.me) { S.me = null; S.token = null; store.set('ember.token', null); showLogin(); }
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

function onDeliver(m) {
  if (m.from === S.me) return;
  const open = S.view === 'chat' && S.active === m.conv && !document.hidden;
  send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: open ? 'READ' : 'DELIVERED' });
  if (!open) m.unreadPending = true;
  if (S.notify && document.hidden && 'Notification' in window && Notification.permission === 'granted') {
    new Notification(name(m.from), { body: m.body.slice(0, 120), tag: m.conv, icon: '/icon.svg' });
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
    if (live && f.senderUserId !== S.me && !(S.view === 'chat' && S.active === c.id)) c.unread++;
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

/** Display name, fetched once per user and then served from memory. */
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
    $('chatSub').textContent = p ? (S.online.has(p) ? '● online' : '@' + p) : (c.members ? c.members.length + ' members' : '');
  }
  $('searchBtn').hidden = !c;
  $('pollBtn').hidden = $('addMember').hidden = !group;
  $('composer').hidden = !c;
  if (!c) { $('searchBar').hidden = true; $('polls').replaceChildren(); $('typing').textContent = ''; $('replyBar').hidden = true; }
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
    return h('li', { class: c.id === S.active && S.view === 'chat' ? 'active' : '', onclick: () => openChat(c.id), tabIndex: 0,
      onkeydown: (e) => { if (e.key === 'Enter') openChat(c.id); } },
    h('span', { class: 'name' + (p ? ' dot' + (S.online.has(p) ? ' on' : '') : '') }, title(c)),
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
  const stick = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const q = S.query.toLowerCase();
  const all = sorted(c).filter((m) => !q || m.body.toLowerCase().includes(q));
  // Only the newest slice is in the DOM, so huge histories stay light on memory.
  const shown = all.slice(-S.window);
  const nodes = shown.map((m) => messageNode(c, m));
  if (all.length > shown.length) {
    nodes.unshift(h('button', { class: 'more', onclick: () => { S.window += 150; renderMessages(); } }, '▲ earlier (' + (all.length - shown.length) + ')'));
  }
  if (!nodes.length) nodes.push(h('div', { class: 'empty' }, q ? 'nothing matches "' + S.query + '"' : 'no messages yet.\nsay something_'));
  box.replaceChildren(...nodes);
  if (stick && !q) box.scrollTop = box.scrollHeight;
  for (const m of shown) {
    if (m.unreadPending && !document.hidden) { m.unreadPending = false; send(OP.RECEIPT, { convId: m.conv, clientMsgId: m.id, status: 'READ' }); }
  }
}

function messageNode(c, m) {
  const mine = m.from === S.me, reacts = Object.entries(m.reacts).filter(([, n]) => n > 0);
  const myR = S.myReacts.get(m.id) || new Set();
  return h('article', { class: 'msg' + (mine ? ' mine' : '') + (m.pending ? ' pending' : ''),
    onclick: (e) => { if (e.target.tagName !== 'BUTTON') e.currentTarget.classList.toggle('sel'); } },
  !mine && c.id.startsWith('grp:') ? h('button', { class: 'who link', onclick: () => openProfile(m.from) }, name(m.from)) : null,
  m.replyPreview ? h('div', { class: 'quote' }, '↳ ' + m.replyPreview) : null,
  h('div', { class: 'body' }, m.body),
  reacts.length ? h('div', { class: 'reacts' }, ...reacts.map(([e, n]) => h('span', {}, e + n))) : null,
  h('div', { class: 'meta' }, (m.expires ? '⏱ ' : '') + (m.edited ? 'edited · ' : '') + when(m.ts)
    + (mine ? (m.read ? ' ✓✓' : ' ✓') : '')),
  h('div', { class: 'actions' },
    h('button', { onclick: () => startReply(m) }, 'reply'),
    ...REACTIONS.map((e) => h('button', { onclick: () => send(OP.REACT, { clientMsgId: m.id, emoji: e, remove: String(myR.has(e)) }),
      'aria-pressed': String(myR.has(e)), title: 'react ' + e }, e)),
    mine ? h('button', { onclick: () => editMsg(m) }, 'edit') : null,
    mine ? h('button', { onclick: () => { if (confirm('Delete this message for everyone?')) send(OP.DELETE, { clientMsgId: m.id }); } }, 'del') : null,
    mine ? null : h('button', { onclick: () => report('message', m.id), title: 'report' }, '⚑')));
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

// ---- profiles ----
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
    h('p', { class: 'dim' }, '@' + userId + (p && p.privateAccount === 'true' ? ' · 🔒 private' : '')),
    p ? h('p', {}, p.bio || '') : h('p', { class: 'dim' }, 'This account is private. Follow to request access.'),
    p ? h('p', { class: 'dim' }, p.followerCount + ' followers · ' + p.followingCount + ' following'
      + (isMe && +p.pendingCount ? ' · ' + p.pendingCount + ' follow requests (approve in the desktop app)' : '')) : null,
    tools));
  if (!p) return;
  try {
    const posts = postsFrom(await request(OP.FEED_PULL, { ownerUserId: userId, limit: 10 }));
    if (S.view === 'profile' && S.viewArg === userId) box.append(...posts.map(postNode));
  } catch { /* no visible posts */ }
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
  $('notifyBtn').textContent = '🔔 DESKTOP ALERTS: ' + (S.notify ? 'ON' : 'OFF');
}

// ---- navigation and actions ----
function setView(view, arg) {
  S.view = view; S.viewArg = arg; S.query = ''; $('search').value = '';
  if (view !== 'chat') $('polls').replaceChildren();
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
  S.active = id; S.window = 150; S.replyTo = null; $('replyBar').hidden = true;
  const c = conv(id);
  c.unread = 0;
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
  S.query = ''; $('search').value = '';
  renderChats(); renderMessages();
  $('content').scrollTop = $('content').scrollHeight;
}

function showLogin() { $('app').hidden = true; $('login').hidden = false; $('user').focus(); }
function showApp() { $('login').hidden = true; $('app').hidden = false; $('me').textContent = '▣ ' + S.me; renderHeader(); }

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
$('searchBtn').onclick = () => {
  $('searchBar').hidden = !$('searchBar').hidden;
  if ($('searchBar').hidden) { S.query = ''; $('search').value = ''; renderMessages(); } else $('search').focus();
};
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
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('overlay').hidden) closeOverlay(); });
$('notifyBtn').onclick = async () => {
  if (!S.notify && 'Notification' in window && (await Notification.requestPermission()) !== 'granted') { toast('alerts blocked by the browser'); return; }
  S.notify = !S.notify; store.set('ember.notify', S.notify ? '1' : null); renderMe();
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
