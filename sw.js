// Keeps the app's own files on the phone so it works offline. Never touches the library itself.
// Network first: when online it picks up new versions automatically; offline it uses the saved copy.
const CACHE = 'library-v2';

self.oninstall = () => self.skipWaiting();
self.onactivate = e => e.waitUntil(caches.keys()
  .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
  .then(() => self.clients.claim()));
self.onfetch = e => e.request.method === 'GET' && e.respondWith(
  fetch(e.request)
    .then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r; })
    .catch(() => caches.match(e.request, { ignoreSearch: true })));
