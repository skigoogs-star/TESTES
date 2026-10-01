// Minimal service worker: precache the app shell so Retro Cam installs as a
// PWA and opens offline. Bump VERSION when files change.
const VERSION = 'retrocam-v18';
const ASSETS = [
  './',
  './index.html',
  './style.css',
  './app.js',
  './filters.js',
  './mp4-recorder.js',
  './mp4-worker.js',
  './mp4-muxer.mjs',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  // cache: 'reload' bypasses the HTTP cache so a new version never ships
  // with stale copies of the app files
  e.waitUntil(
    caches
      .open(VERSION)
      .then((c) => c.addAll(ASSETS.map((u) => new Request(u, { cache: 'reload' }))))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  // This worker is registered at the site root, so it also sees requests for
  // the training app in /athletic-cut/. Leave those to the network: caching
  // them here, cache-first and never revalidated, would freeze that app at
  // whatever version a phone first loaded.
  if (new URL(e.request.url).pathname.includes('/athletic-cut/')) return;
  e.respondWith(
    caches.match(e.request).then(
      (hit) =>
        hit ||
        fetch(e.request).then((res) => {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(e.request, copy));
          return res;
        })
    )
  );
});
