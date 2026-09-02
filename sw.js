/* ΑΙΑΣ — offline cache */
const CACHE = 'aias-v4';
const ASSETS = [
  './',
  './index.html',
  './v4.html',
  './road.html',
  './bars.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    /* Κάθε αρχείο χωριστά. Με addAll, ένα και μόνο αρχείο που λείπει
       —π.χ. το road.html σε κλαδί που δεν το έχει— ακυρώνει ολόκληρη
       την εγκατάσταση του service worker. */
    caches.open(CACHE)
      .then((c) => Promise.all(ASSETS.map((u) => c.add(u).catch(() => {}))))
      .then(() => self.skipWaiting())
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
