// שומר את קבצי האפליקציה בטלפון כדי שתעבוד גם בלי אינטרנט. לא נוגע בספרייה עצמה.
const CACHE = 'library-v1';
const FILES = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png'];

self.oninstall = e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
self.onactivate = e => e.waitUntil(caches.keys()
  .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
  .then(() => self.clients.claim()));
self.onfetch = e => e.respondWith(caches.match(e.request, { ignoreSearch: true }).then(r => r || fetch(e.request)));
