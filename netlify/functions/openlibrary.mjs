// GET /api/openlibrary — broad book discovery (any book, not only free ones)
//   ?q=dune&page=1[&lang=eng]   → search
//   ?work=OL45804W              → one work's description and subjects
// Open Library data is used for discovery and for building borrow/buy links.
// It never implies the book is free or available.

import { json, fail, onlyGet, upstream, softLimit, clientKey, clean } from '../lib/shared.mjs';

const BASE = 'https://openlibrary.org';
const PAGE_SIZE = 20;
const FIELDS = 'key,title,author_name,first_publish_year,cover_i,edition_count,language,isbn,subject,ebook_access,id_project_gutenberg';

export function shapeDoc(d) {
  return {
    id: String(d.key || '').replace('/works/', ''),
    title: clean(d.title, 400),
    authors: (d.author_name || []).slice(0, 6).map((a) => clean(a, 160)),
    firstPublishYear: d.first_publish_year ?? null,
    cover: d.cover_i ? `https://covers.openlibrary.org/b/id/${Number(d.cover_i)}-M.jpg` : null,
    editionCount: d.edition_count ?? null,
    languages: (d.language || []).slice(0, 6),
    isbn: (d.isbn || []).find((x) => /^97[89]\d{10}$/.test(x)) || (d.isbn || [])[0] || null,
    subjects: (d.subject || []).slice(0, 10).map((s) => clean(s, 120)),
    ebookAccess: d.ebook_access || null,
    gutenbergIds: (d.id_project_gutenberg || []).filter((x) => /^\d{1,6}$/.test(x)).slice(0, 3),
    sourceUrl: d.key ? `https://openlibrary.org${d.key}` : null,
  };
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`ol:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many search requests. Wait a minute and try again.');
  const sp = new URL(req.url).searchParams;
  try {
    if (sp.has('work')) {
      const work = sp.get('work');
      if (!/^OL\d{1,10}W$/.test(work)) return fail(400, 'bad_request', 'work must look like OL45804W.');
      const res = await upstream(`${BASE}/works/${work}.json`);
      if (res.status === 404) return fail(404, 'not_found', 'Open Library has no record of that work.');
      if (!res.ok) return fail(502, 'upstream_error', `Open Library answered ${res.status}.`);
      const w = await res.json();
      const desc = typeof w.description === 'string' ? w.description : w.description?.value;
      return json({
        work: {
          id: work,
          title: clean(w.title, 400),
          description: clean(desc, 4000) || null,
          subjects: (w.subjects || []).slice(0, 14).map((s) => clean(s, 120)),
          firstPublishDate: clean(w.first_publish_date, 40) || null,
        },
      }, { cache: 'public, max-age=3600', cdn: 'public, s-maxage=86400' });
    }
    const q = clean(sp.get('q'), 120);
    const page = Number(sp.get('page') || '1');
    const lang = (sp.get('lang') || '').toLowerCase();
    if (!q) return fail(400, 'bad_request', 'Enter a title or author to search.');
    if (!Number.isInteger(page) || page < 1 || page > 100) return fail(400, 'bad_request', 'Page must be from 1 to 100.');
    if (lang && !/^[a-z]{3}$/.test(lang)) return fail(400, 'bad_request', 'lang must be a three-letter code like eng.');
    const params = new URLSearchParams({ q, page: String(page), limit: String(PAGE_SIZE), fields: FIELDS });
    if (lang) params.set('lang', lang);
    const res = await upstream(`${BASE}/search.json?${params}`, { timeoutMs: 15000 });
    if (!res.ok) return fail(502, 'upstream_error', `Open Library answered ${res.status}.`);
    const data = await res.json();
    const results = (data.docs || []).map(shapeDoc);
    const count = data.numFound ?? data.num_found ?? results.length;
    return json({ count, page, pageSize: PAGE_SIZE, hasNext: page * PAGE_SIZE < count, hasPrev: page > 1, results },
      { cache: 'public, max-age=300', cdn: 'public, s-maxage=3600, stale-while-revalidate=86400' });
  } catch (err) {
    const timeout = err?.name === 'AbortError';
    return fail(timeout ? 504 : 502, timeout ? 'upstream_timeout' : 'upstream_unreachable',
      timeout ? 'Open Library took too long to answer.' : 'Could not reach Open Library.');
  }
};

export const config = {
  path: '/api/openlibrary',
  rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
