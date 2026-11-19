// Keeps the app's own files on the phone so it works offline. Never touches the library itself.
// Network first, always asking the server for the newest version (no stale copies); offline it uses the saved copy.
// "shared" briefly holds HTML files shared into the app, until the library is unlocked and imports them.
const CACHE = 'library-v4';

self.oninstall = () => self.skipWaiting();
self.onactivate = e => e.waitUntil(caches.keys()
  .then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== 'shared').map(k => caches.delete(k))))
  .then(() => self.clients.claim()));
self.onfetch = e => {
  if (e.request.method === 'POST' && new URL(e.request.url).searchParams.has('share')) return e.respondWith((async () => {
    const files = (await e.request.formData()).getAll('files'), c = await caches.open('shared');
    await Promise.all(files.map((f, i) => c.put(`shared/${Date.now()}-${i}`, new Response(f))));
    return Response.redirect(self.registration.scope, 303);
  })());
  if (e.request.method === 'GET') e.respondWith(fetch(e.request, { cache: 'no-cache' })
    .then(r => { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); return r; })
    .catch(() => caches.match(e.request, { ignoreSearch: true })));
};
