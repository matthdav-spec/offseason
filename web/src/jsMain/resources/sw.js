// DBAP service worker: keeps the app working with no signal (e.g. in the garage).
// Network first, so testers always get the newest version when online; cache as the fallback.
// Bump VERSION when releasing so old caches are cleared.
const VERSION = 'offseason-1.0.0';
const FILES = ['./', 'index.html', 'offseason.js', 'styles.css', 'manifest.webmanifest', 'icon-180.png', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'logo.svg'];

self.addEventListener('install', (e) => {
    e.waitUntil(caches.open(VERSION).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
    e.waitUntil(
        caches.keys()
            // Only clear this app's own old caches: the main app and the preview share one website.
            .then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k.split('-').slice(0, -1).join('-') === VERSION.split('-').slice(0, -1).join('-')).map((k) => caches.delete(k))))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (e) => {
    if (e.request.method !== 'GET') return;
    e.respondWith(
        fetch(e.request)
            .then((res) => {
                const copy = res.clone();
                caches.open(VERSION).then((c) => c.put(e.request, copy));
                return res;
            })
            .catch(() => caches.match(e.request).then((r) => r || caches.match('index.html')))
    );
});
