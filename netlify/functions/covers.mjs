// GET /api/covers?title=&author=&isbn=  — find a real published cover.
// Order: Open Library by ISBN, Open Library search, Google Books. Returns
// { cover, source } or { cover: null }. Answers are cached on Netlify's CDN.

import { json, fail, onlyGet, upstream, softLimit, clientKey, clean, env } from '../lib/shared.mjs';

export const norm = (s) => String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();
export const shortTitle = (t) => clean(String(t || '').split(/[;:(\[]|, or,? /i)[0], 120);
const surname = (a) => norm(a).split(' ').filter(Boolean).pop() || '';

export function titleMatches(candidate, wanted) {
  const c = norm(shortTitle(candidate)), w = norm(shortTitle(wanted));
  if (!c || !w) return false;
  return c === w || c.startsWith(w) || w.startsWith(c);
}

// Netlify stops a function after about 10 seconds, so every lookup shares one
// budget. A lookup that fails (rather than finding nothing) marks the answer
// as provisional so a "no cover" result is not cached for long.
const BUDGET_MS = 8500;
function remaining(start, cap) { return Math.max(0, Math.min(cap, BUDGET_MS - (Date.now() - start))); }

async function openLibraryIsbn(isbn, ms, st) {
  const url = `https://covers.openlibrary.org/b/isbn/${isbn}-L.jpg?default=false`;
  try {
    const r = await upstream(url, { accept: 'image/*', timeoutMs: ms });
    r.body?.cancel();
    if (!r.ok && r.status !== 404) st.failed = true;
    return r.ok ? `https://covers.openlibrary.org/b/isbn/${isbn}-L.jpg` : null;
  } catch { st.failed = true; return null; }
}

async function openLibrarySearch(title, author, ms, st) {
  const p = new URLSearchParams({ title: shortTitle(title), limit: '8', fields: 'title,author_name,cover_i,edition_count' });
  if (author) p.set('author', surname(author));
  try {
    const r = await upstream(`https://openlibrary.org/search.json?${p}`, { timeoutMs: ms });
    if (!r.ok) { st.failed = true; return null; }
    const d = await r.json();
    const docs = (d.docs || []).filter((x) => x.cover_i && titleMatches(x.title, title));
    docs.sort((a, b) => (b.edition_count || 0) - (a.edition_count || 0));
    return docs[0] ? `https://covers.openlibrary.org/b/id/${Number(docs[0].cover_i)}-L.jpg` : null;
  } catch { st.failed = true; return null; }
}

async function googleBooks(title, author, isbn, ms, st) {
  const q = isbn ? `isbn:${isbn}` : `intitle:${shortTitle(title)}${author ? ` inauthor:${surname(author)}` : ''}`;
  const p = new URLSearchParams({ q, maxResults: '8', printType: 'books' });
  const key = env('GOOGLE_BOOKS_API_KEY');
  if (key) p.set('key', key);
  try {
    const r = await upstream(`https://www.googleapis.com/books/v1/volumes?${p}`, { timeoutMs: ms });
    if (!r.ok) { st.failed = true; return null; }
    const d = await r.json();
    for (const item of d.items || []) {
      const v = item.volumeInfo || {};
      if (!isbn && !titleMatches(v.title, title)) continue;
      const img = v.imageLinks?.thumbnail || v.imageLinks?.smallThumbnail;
      if (img) return img.replace(/^http:/, 'https:').replace(/&edge=curl/, '');
    }
    return null;
  } catch { st.failed = true; return null; }
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`cov:${clientKey(req, context)}`, { limit: 240 })) return fail(429, 'rate_limited', 'Too many cover lookups. Try again in a minute.');
  const sp = new URL(req.url).searchParams;
  const title = clean(sp.get('title'), 200);
  const author = clean(sp.get('author'), 120);
  const isbn = (sp.get('isbn') || '').replace(/[^0-9X]/gi, '');
  if (!title && !isbn) return fail(400, 'bad_request', 'Give a title or an ISBN.');
  if (isbn && !/^(\d{9}[\dX]|\d{13})$/i.test(isbn)) return fail(400, 'bad_request', 'ISBN must have 10 or 13 digits.');

  const start = Date.now();
  const st = { failed: false };
  let cover = null, source = null;
  if (isbn) { cover = await openLibraryIsbn(isbn, remaining(start, 3000), st); source = cover && 'Open Library'; }
  if (!cover && title && remaining(start, 6000) > 1500) { cover = await openLibrarySearch(title, author, remaining(start, 6000), st); source = cover && 'Open Library'; }
  if (!cover && remaining(start, 5000) > 1500) { cover = await googleBooks(title, author, isbn, remaining(start, 5000), st); source = cover && 'Google Books'; }
  if (!cover && remaining(start, 5000) <= 1500) st.failed = true;
  return json({ cover, source }, {
    cache: cover ? 'public, max-age=86400' : st.failed ? 'no-store' : 'public, max-age=86400',
    cdn: cover ? 'public, s-maxage=2592000, stale-while-revalidate=2592000' : st.failed ? 'public, s-maxage=600' : 'public, s-maxage=604800',
  });
};

export const config = {
  path: '/api/covers',
  rateLimit: { windowLimit: 300, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
