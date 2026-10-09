// GET /api/librivox?gid=1342&title=Pride%20and%20Prejudice&author=Austen
// Finds human-read LibriVox recordings of a public-domain book. Recordings that
// name the same Project Gutenberg text are matched exactly; otherwise title and
// author must agree. Audio is streamed from archive.org by the app.
//   → { versions: [{ id, title, readers, totalSecs, url, sections: [{ n, title, url, secs, reader }] }] }

import { json, fail, onlyGet, upstream, softLimit, clientKey, clean } from '../lib/shared.mjs';

const norm = (s) => String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();
const shortTitle = (t) => String(t || '').split(/[;:(\[]|, or,? /i)[0].trim();
const https = (u) => (typeof u === 'string' && /^https?:\/\/(www\.)?archive\.org\//.test(u) ? u.replace(/^http:/, 'https:') : null);

export function matches(b, { gid, title, author }) {
  const src = String(b.url_text_source || '');
  if (gid && new RegExp(`gutenberg\\.org/(?:etext|ebooks|files)/${gid}(?:\\D|$)`).test(src)) return 2;
  const tWant = norm(shortTitle(title));
  const tHave = norm(shortTitle(String(b.title || '').replace(/\(version \d+\)/i, '')));
  const surname = norm(author).split(' ').filter(Boolean).pop();
  const authorOk = !surname || (b.authors || []).some((a) => norm(a.last_name) === surname);
  return tWant && tHave === tWant && authorOk ? 1 : 0;
}

export function shapeVersion(b) {
  const sections = (b.sections || []).slice(0, 400).map((s, i) => ({
    n: Number(s.section_number) || i + 1,
    title: clean(s.title, 160) || `Part ${i + 1}`,
    url: https(s.listen_url),
    secs: Number(s.playtime) || 0,
    reader: clean((s.readers || [])[0]?.display_name, 80),
  })).filter((s) => s.url);
  const readers = [...new Set(sections.map((s) => s.reader).filter(Boolean))];
  return {
    id: String(b.id), title: clean(b.title, 200), language: clean(b.language, 40),
    readers: readers.slice(0, 3), moreReaders: Math.max(0, readers.length - 3),
    totalSecs: Number(b.totaltimesecs) || sections.reduce((a, s) => a + s.secs, 0),
    url: typeof b.url_librivox === 'string' && b.url_librivox.startsWith('https://librivox.org/') ? b.url_librivox : null,
    sections,
  };
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`lv:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many requests. Wait a minute.');
  const sp = new URL(req.url).searchParams;
  const gid = sp.get('gid') && /^\d{1,6}$/.test(sp.get('gid')) ? sp.get('gid') : '';
  const title = clean(sp.get('title'), 200);
  const author = clean(sp.get('author'), 120);
  if (!title) return fail(400, 'bad_request', 'Give a title.');
  const q = new URLSearchParams({ title: `^${shortTitle(title)}`, format: 'json', extended: '1', limit: '20' });
  try {
    const r = await upstream(`https://librivox.org/api/feed/audiobooks/?${q}`, { timeoutMs: 8500 });
    if (r.status === 404) return json({ versions: [] }, { cache: 'public, max-age=86400', cdn: 'public, s-maxage=604800' });
    if (!r.ok) return fail(502, 'upstream_error', `LibriVox answered ${r.status}.`);
    const j = await r.json().catch(() => ({}));
    const books = (j.books || []).map((b) => ({ b, score: matches(b, { gid, title, author }) })).filter((x) => x.score > 0);
    const english = (x) => /english/i.test(x.b.language || '') ? 1 : 0;
    const shaped = books.map((x) => ({ ...x, v: shapeVersion(x.b) })).filter((x) => x.v.sections.length);
    // Exact text first, then English, then one reader the whole way through (easier on long drives), then oldest.
    const solo = (x) => (x.v.readers.length === 1 && !x.v.moreReaders ? 1 : 0);
    shaped.sort((a, b) => b.score - a.score || english(b) - english(a) || solo(b) - solo(a) || Number(a.b.id) - Number(b.b.id));
    const versions = shaped.map((x) => x.v).slice(0, 8);
    return json({ versions }, { cache: 'public, max-age=86400', cdn: 'public, s-maxage=604800, stale-while-revalidate=604800' });
  } catch (err) {
    return fail(err?.name === 'AbortError' ? 504 : 502, 'upstream_unreachable', 'LibriVox didn’t answer. Try again in a moment.');
  }
};

export const config = {
  path: '/api/librivox',
  rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
