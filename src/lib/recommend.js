// "Picked for you": recommendations from the genres on your shelf.
// Free by default (genre overlap + popularity). When the owner has added an
// Eden AI key, Jev re-ranks the candidates.

import * as store from './store.js';
import { searchGutenberg } from './catalog.js';
import { can, ownerPost, loadFeatures } from './features.js';

const STOP = new Set(['fiction', 'juvenile fiction', 'literature', 'english', 'american', 'texts', 'novels', 'short stories', 'bible', 'christianity', 'scripture', 'test fixtures']);
const WEIGHT = { finished: 3, reading: 2, want: 1 };

export function genreOf(subject) {
  return String(subject || '').split(' -- ')[0].replace(/^Browsing:\s*/, '').replace(/\s*\(.*\)$/, '').trim().toLowerCase();
}

export function buildProfile(shelf) {
  const weights = new Map();
  for (const b of shelf) {
    if (b.source === 'bible') continue;
    const w = WEIGHT[b.status] || 1;
    for (const s of b.subjects || []) {
      const g = genreOf(s);
      if (!g || STOP.has(g) || g.length > 60) continue;
      weights.set(g, (weights.get(g) || 0) + w);
    }
  }
  const genres = [...weights.entries()].sort((a, b) => b[1] - a[1]);
  return { genres, recent: shelf.filter((b) => b.source !== 'bible').sort((a, b) => (b.lastOpenedAt || b.addedAt || 0) - (a.lastOpenedAt || a.addedAt || 0)).slice(0, 6).map((b) => b.title) };
}

export function scoreLocal(book, profile) {
  const map = new Map(profile.genres);
  let s = 0;
  const hits = [];
  for (const sub of [...(book.subjects || []), ...(book.bookshelves || [])]) {
    const g = genreOf(sub);
    if (map.has(g)) { s += map.get(g); hits.push(g); }
  }
  s += Math.log10((book.downloads || 0) + 10) * 0.4;
  return { score: s, because: [...new Set(hits)].slice(0, 2) };
}

/**
 * Returns { items: [{ book, because, score }], ranker: 'jev'|'genre' } or null
 * when there isn't enough on the shelf yet.
 */
export async function recommendations({ signal } = {}) {
  const shelf = await store.listShelf();
  const profile = buildProfile(shelf);
  if (!profile.genres.length) return null;
  const cacheId = `recs|${store.getOwner()}|${profile.genres.slice(0, 4).map((g) => g[0]).join('|')}`;
  const cached = await store.getCache(cacheId);
  if (cached && Date.now() - cached.at < 6 * 3600e3) return cached.value;

  const owned = new Set(shelf.map((b) => b.key));
  const topics = profile.genres.slice(0, 3).map(([g]) => g);
  const pools = await Promise.all(topics.map((t) => searchGutenberg({ topic: t, languages: 'en' }, { signal }).then((r) => r.results).catch(() => [])));
  const seen = new Set();
  let items = [];
  for (const b of pools.flat()) {
    if (owned.has(b.key) || seen.has(b.key)) continue;
    seen.add(b.key);
    const { score, because } = scoreLocal(b, profile);
    items.push({ book: b, score, because });
  }
  items.sort((a, b) => b.score - a.score);
  items = items.slice(0, 18);
  let ranker = 'genre';

  await loadFeatures();
  if (can('jev') && items.length > 2) {
    try {
      const { scores } = await ownerPost('/api/rank', {
        profile: { genres: profile.genres.slice(0, 10).map((g) => g[0]), recent: profile.recent },
        candidates: items.map((i) => ({ id: i.book.key, title: i.book.title, authors: i.book.authors, subjects: i.book.subjects })),
      }, { signal });
      const max = Math.max(...items.map((i) => i.score), 1);
      for (const i of items) {
        if (scores[i.book.key] != null) i.score = 0.7 * scores[i.book.key] + 0.3 * (i.score / max);
        else i.score = 0.3 * (i.score / max);
      }
      items.sort((a, b) => b.score - a.score);
      ranker = 'jev';
    } catch { /* fall back to genre ranking */ }
  }
  const value = { items: items.slice(0, 12), ranker, topics };
  await store.setCache(cacheId, { at: Date.now(), value });
  return value;
}
