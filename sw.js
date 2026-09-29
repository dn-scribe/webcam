// Service worker: precache the app shell, serve cache-first (offline), drop old caches.
importScripts('./version.js');

const CACHE = `webcam-${self.APP_VERSION}-${self.APP_BUILD}`;
const SHELL = [
  './', './index.html', './style.css', './app.js', './version.js',
  './manifest.webmanifest',
  './icons/icon-192.png', './icons/icon-512.png', './icons/maskable-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // cache:'reload' bypasses the HTTP cache so a new version never stores stale files
    await Promise.all(SHELL.map(async (u) => {
      const res = await fetch(new Request(u, { cache: 'reload' }));
      if (!res.ok) throw new Error(`precache ${u}: ${res.status}`);
      await cache.put(u, res);
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('message', (e) => {
  if (e.data === 'SKIP_WAITING') self.skipWaiting();
  if (e.data === 'GET_VERSION') e.source.postMessage({ version: self.APP_VERSION, build: self.APP_BUILD, cache: CACHE });
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req, { ignoreSearch: true });
    if (hit) return hit;
    try {
      return await fetch(req);
    } catch (_) {
      if (req.mode === 'navigate') return (await cache.match('./index.html')) || Response.error();
      return Response.error();
    }
  })());
});
