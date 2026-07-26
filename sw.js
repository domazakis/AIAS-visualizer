/* ΑΙΑΣ — offline cache */
const CACHE = 'aias-v2';
const ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

/* αποθηκεύουμε ΜΟΝΟ επιτυχημένες απαντήσεις —
   αλλιώς ένα 404 κολλάει στην cache και δεν ξεκολλάει */
function keep(request, response) {
  if (!response || !response.ok || response.type === 'opaque') return response;
  const copy = response.clone();
  caches.open(CACHE).then((c) => c.put(request, copy)).catch(() => {});
  return response;
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;

  /* Η σελίδα: δίκτυο πρώτα, cache ως δικλείδα.
     Έτσι κάθε νέα εκδοχή φαίνεται αμέσως, και εκτός δικτύου
     η εφαρμογή ανοίγει κανονικά. */
  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request)
        .then((resp) => keep(e.request, resp))
        .catch(() => caches.match(e.request, { ignoreSearch: true })
          .then((cached) => cached || caches.match('./index.html')))
    );
    return;
  }

  /* Εικονίδια και στατικά: cache πρώτα, είναι πιο γρήγορο */
  e.respondWith(
    caches.match(e.request, { ignoreSearch: true }).then((cached) => {
      if (cached) return cached;
      return fetch(e.request)
        .then((resp) => keep(e.request, resp))
        .catch(() => cached);
    })
  );
});
