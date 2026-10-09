// GET /api/catalog — Project Gutenberg catalog via Gutendex.
//   ?search=&topic=&languages=en,fr&page=1&sort=popular   → list
//   ?ids=1342,84                                           → specific books
//   ?id=1342                                               → one book
// Only these parameters are forwarded, after validation, to the fixed host.

import { json, fail, onlyGet, upstream, softLimit, clientKey, clean } from '../lib/shared.mjs';

const BASE = process.env.GUTENDEX_BASE || 'https://gutendex.com';
const PAGE_SIZE = 32; // Gutendex's fixed page size

export function shapeBook(b) {
  const formats = b.formats || {};
  const epubUrl = Object.entries(formats).find(([k]) => k.startsWith('application/epub+zip'))?.[1] || null;
  const cover = Object.entries(formats).find(([k]) => k.startsWith('image/'))?.[1] || null;
  const htmlUrl = Object.entries(formats).find(([k]) => k.startsWith('text/html'))?.[1] || null;
  return {
    id: b.id,
    title: clean(b.title, 400),
    authors: (b.authors || []).slice(0, 6).map((a) => ({ name: clean(a.name, 160), birthYear: a.birth_year ?? null, deathYear: a.death_year ?? null })),
    translators: (b.translators || []).slice(0, 4).map((a) => clean(a.name, 160)),
    subjects: (b.subjects || []).slice(0, 12).map((s) => clean(s, 200)),
    bookshelves: (b.bookshelves || []).slice(0, 8).map((s) => clean(s, 120).replace(/^Browsing:\s*/, '')),
    languages: (b.languages || []).slice(0, 6),
    summary: clean((b.summaries || [])[0] || '', 3000) || null,
    copyright: b.copyright ?? null,
    mediaType: b.media_type || null,
    downloads: b.download_count ?? null,
    cover: safeUrl(cover),
    epub: !!epubUrl,
    epubUrl: safeUrl(epubUrl),
    htmlUrl: safeUrl(htmlUrl),
    sourceUrl: `https://www.gutenberg.org/ebooks/${b.id}`,
  };
}

function safeUrl(u) {
  try {
    const url = new URL(u);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href.replace(/^http:/, 'https:') : null;
  } catch { return null; }
}

export function parseListParams(sp) {
  const out = new URLSearchParams();
  const search = clean(sp.get('search'), 120);
  const topic = clean(sp.get('topic'), 60);
  const languages = (sp.get('languages') || '').toLowerCase();
  const sort = sp.get('sort') || 'popular';
  const page = Number(sp.get('page') || '1');
  const ids = sp.get('ids');
  if (languages && !/^[a-z]{2}(,[a-z]{2}){0,4}$/.test(languages)) return { error: 'Language codes must be two letters, comma separated.' };
  if (!['popular', 'ascending', 'descending'].includes(sort)) return { error: 'Unknown sort order.' };
  if (!Number.isInteger(page) || page < 1 || page > 2000) return { error: 'Page must be a whole number from 1 to 2000.' };
  if (ids != null) {
    if (!/^\d{1,6}(,\d{1,6}){0,31}$/.test(ids)) return { error: 'ids must be up to 32 numeric Gutenberg ids.' };
    out.set('ids', ids);
  }
  if (search) out.set('search', search);
  if (topic) out.set('topic', topic);
  if (languages) out.set('languages', languages);
  out.set('sort', sort);
  if (page > 1) out.set('page', String(page));
  // Mavis only lists books it can legally offer: public domain in the USA.
  out.set('copyright', 'false');
  out.set('mime_type', 'application/epub');
  return { params: out, page };
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`cat:${clientKey(req, context)}`, { limit: 90 })) return fail(429, 'rate_limited', 'Too many catalog requests. Wait a minute and try again.');

  const sp = new URL(req.url).searchParams;
  try {
    if (sp.has('id')) {
      const id = sp.get('id');
      if (!/^\d{1,6}$/.test(id)) return fail(400, 'bad_request', 'id must be a numeric Gutenberg id.');
      const res = await upstream(`${BASE}/books/${id}`);
      if (res.status === 404) return fail(404, 'not_found', 'That book is not in the Project Gutenberg catalog.');
      if (!res.ok) return fail(502, 'upstream_error', `The Gutenberg catalog answered ${res.status}.`);
      const book = shapeBook(await res.json());
      return json({ book }, { cache: 'public, max-age=600', cdn: 'public, s-maxage=86400, stale-while-revalidate=604800' });
    }
    const parsed = parseListParams(sp);
    if (parsed.error) return fail(400, 'bad_request', parsed.error);
    const res = await upstream(`${BASE}/books?${parsed.params}`);
    if (!res.ok) return fail(502, 'upstream_error', `The Gutenberg catalog answered ${res.status}.`);
    const data = await res.json();
    const results = (data.results || []).map(shapeBook);
    return json({
      count: data.count ?? results.length,
      page: parsed.page,
      pageSize: PAGE_SIZE,
      hasNext: !!data.next,
      hasPrev: !!data.previous,
      results,
    }, { cache: 'public, max-age=300', cdn: 'public, s-maxage=3600, stale-while-revalidate=86400' });
  } catch (err) {
    const timeout = err?.name === 'AbortError';
    return fail(timeout ? 504 : 502, timeout ? 'upstream_timeout' : 'upstream_unreachable',
      timeout ? 'The Gutenberg catalog took too long to answer.' : 'Could not reach the Gutenberg catalog.');
  }
};

export const config = {
  path: '/api/catalog',
  rateLimit: { windowLimit: 120, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
