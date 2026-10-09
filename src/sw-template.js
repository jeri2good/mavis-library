/* Mavis Library service worker (generated at build from src/sw-template.js).
 *
 * Caches:
 *  - shell:   the built app (HTML, JS, CSS, bundled fonts, icons) — precached
 *  - catalog: public catalog API responses — network first, offline fallback
 *  - covers:  public cover images — cache first, capped
 *  - bible:   Bible text, lexicon, cross-references — cache first, kept offline
 * Never cached here: /api/epub (book bytes go to IndexedDB instead), account
 * and sync traffic (Supabase is cross-origin and never intercepted), the
 * dictionary, and any non-GET request.
 */
const VERSION = __BUILD_VERSION__;
const PRECACHE = __PRECACHE_LIST__;
const SHELL = `mavis-shell-${VERSION}`;
const CATALOG = 'mavis-catalog-v1';
const COVERS = 'mavis-covers-v1';
const COVER_HOSTS = new Set(['www.gutenberg.org', 'gutenberg.org', 'covers.openlibrary.org', 'books.google.com']);

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL);
    await cache.addAll(PRECACHE.map((p) => new Request(p, { cache: 'reload' })));
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((k) => k.startsWith('mavis-shell-') && k !== SHELL).map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

async function trim(cacheName, max) {
  const cache = await caches.open(cacheName);
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - max; i++) await cache.delete(keys[i]);
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // App navigations: network first so updates arrive, cached shell offline.
  if (req.mode === 'navigate' && url.origin === self.location.origin) {
    event.respondWith((async () => {
      try {
        const res = await fetch(req);
        return res;
      } catch {
        const cache = await caches.open(SHELL);
        return (await cache.match('index.html')) || (await cache.match('./')) || Response.error();
      }
    })());
    return;
  }

  if (url.origin === self.location.origin) {
    if (url.pathname.startsWith('/api/epub')) return; // stored in IndexedDB by the app
    if (url.pathname.startsWith('/api/catalog') || url.pathname.startsWith('/api/openlibrary')) {
      event.respondWith((async () => {
        const cache = await caches.open(CATALOG);
        try {
          const res = await fetch(req);
          if (res.ok) { cache.put(req, res.clone()); trim(CATALOG, 120); }
          return res;
        } catch {
          const hit = await cache.match(req);
          if (hit) return hit;
          return new Response(JSON.stringify({ error: 'offline', message: "You're offline and this search isn't saved on this device." }), { status: 503, headers: { 'content-type': 'application/json' } });
        }
      })());
      return;
    }
    if (url.pathname.startsWith('/bible/')) {
      // Bible text, lexicon, and cross-references: cache first, kept offline.
      event.respondWith((async () => {
        const cache = await caches.open('mavis-bible-v1');
        const hit = await cache.match(req, { ignoreSearch: true });
        if (hit) return hit;
        const res = await fetch(req);
        if (res.ok) cache.put(req, res.clone());
        return res;
      })());
      return;
    }
    // Precached build files: cache first.
    event.respondWith((async () => {
      const hit = await caches.match(req, { ignoreSearch: false });
      return hit || fetch(req);
    })());
    return;
  }

  if (COVER_HOSTS.has(url.host) && req.destination === 'image') {
    event.respondWith((async () => {
      const cache = await caches.open(COVERS);
      const hit = await cache.match(req);
      if (hit) return hit;
      try {
        const res = await fetch(req);
        if (res.ok || res.type === 'opaque') { cache.put(req, res.clone()); trim(COVERS, 600); }
        return res;
      } catch {
        return Response.error();
      }
    })());
  }
});
