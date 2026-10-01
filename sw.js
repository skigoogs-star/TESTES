// Retro Cam used to live at the site root and registered a service worker
// here, which controlled every folder on the site. It now lives in
// retro-cam/ with its own worker. Phones that installed the old version
// still check this URL for updates, so this file replaces the old worker
// with one that removes itself. It has no fetch handler, so nothing is
// served from cache while it runs. The old app reloads itself when this
// activates and lands on the root page, which sends home-screen launches
// on to retro-cam/.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => {
  e.waitUntil(self.registration.unregister());
});
