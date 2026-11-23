// Offline app shell (cache first, refreshed in the background) plus Web Push notifications.
const CACHE = 'ember-v4';
const SHELL = ['/', '/app.js', '/app.css', '/manifest.json', '/icon.svg', '/call.js', '/call-worklet.js'];
self.addEventListener('install', (e) => e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', (e) => e.waitUntil(caches.keys()
  .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  .then(() => self.clients.claim())));
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || !SHELL.includes(url.pathname)) return;
  e.respondWith(caches.open(CACHE).then(async (cache) => {
    const cached = await cache.match(e.request);
    const fresh = fetch(e.request).then((res) => { if (res.ok) cache.put(e.request, res.clone()); return res; });
    if (cached) { e.waitUntil(fresh.catch(() => {})); return cached; }
    return fresh;
  }));
});

// The server pushes only when no Ember tab is connected, so every push becomes a notification.
self.addEventListener('push', (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch { d = { title: 'Ember', body: e.data ? e.data.text() : '' }; }
  const conv = d.conv || '';
  e.waitUntil(self.registration.showNotification(d.title || 'Ember', {
    body: d.body || '', tag: conv || 'ember', renotify: true, icon: '/icon.svg', badge: '/icon.svg', data: { conv },
    actions: conv ? [{ action: 'reply', title: 'Reply', type: 'text', placeholder: 'Type a reply…' }, { action: 'read', title: 'Mark read' }] : [],
  }));
});

// Reply (typed inline where the browser supports it), Mark read, or a plain click to open the chat.
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const conv = (e.notification.data || {}).conv || '';
  const reply = e.action === 'reply' && e.reply ? e.reply : '';
  e.waitUntil((async () => {
    const tabs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (e.action === 'read') { tabs.forEach((t) => t.postMessage({ type: 'read', conv })); return; }
    const tab = tabs[0];
    if (tab) {
      tab.postMessage({ type: 'open', conv, reply });
      return tab.focus();
    }
    const q = conv ? '?open=' + encodeURIComponent(conv) + (reply ? '&reply=' + encodeURIComponent(reply) : '') : '';
    return self.clients.openWindow('/' + q);
  })());
});
