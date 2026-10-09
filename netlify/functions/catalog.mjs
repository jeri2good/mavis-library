// GET /api/catalog — the Project Gutenberg catalog, read from Gutenberg's own
// OPDS feeds (Gutendex, used before, blocks requests from Netlify's servers).
//   ?search=&topic=&languages=en&page=1&sort=popular   → list (25 per page)
//   ?ids=1342,84                                        → specific books
//   ?id=1342                                            → one book, full record
// Only these parameters are accepted, after validation; requests go only to
// www.gutenberg.org.

import { json, fail, onlyGet, softLimit, clientKey, clean } from '../lib/shared.mjs';
import { fetchSearch, fetchBook, buildQuery, OPDS_PAGE_SIZE } from '../lib/gutenberg.mjs';

export function parseListParams(sp) {
  const search = clean(sp.get('search'), 120);
  const topic = clean(sp.get('topic'), 60);
  const languages = (sp.get('languages') || '').toLowerCase();
  const sort = sp.get('sort') || 'popular';
  const page = Number(sp.get('page') || '1');
  const ids = sp.get('ids');
  if (languages && !/^[a-z]{2}(,[a-z]{2}){0,4}$/.test(languages)) return { error: 'Language codes must be two letters, comma separated.' };
  if (!['popular', 'ascending', 'descending'].includes(sort)) return { error: 'Unknown sort order.' };
  if (!Number.isInteger(page) || page < 1 || page > 2000) return { error: 'Page must be a whole number from 1 to 2000.' };
  if (ids != null && !/^\d{1,6}(,\d{1,6}){0,31}$/.test(ids)) return { error: 'ids must be up to 32 numeric Gutenberg ids.' };
  return { search, topic, languages, sort, page, ids: ids ? [...new Set(ids.split(',').map(Number))].slice(0, 24) : null };
}

const LIST_CACHE = { cache: 'public, max-age=300', cdn: 'public, s-maxage=3600, stale-while-revalidate=86400' };

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`cat:${clientKey(req, context)}`, { limit: 90 })) return fail(429, 'rate_limited', 'Too many catalog requests. Wait a minute and try again.');

  const sp = new URL(req.url).searchParams;
  try {
    if (sp.has('id')) {
      const id = sp.get('id');
      if (!/^\d{1,6}$/.test(id)) return fail(400, 'bad_request', 'id must be a numeric Gutenberg id.');
      const book = await fetchBook(Number(id));
      if (!book) return fail(404, 'not_found', 'That book is not in the Project Gutenberg catalog.');
      return json({ book }, { cache: 'public, max-age=600', cdn: 'public, s-maxage=86400, stale-while-revalidate=604800' });
    }
    const q = parseListParams(sp);
    if (q.error) return fail(400, 'bad_request', q.error);
    if (q.ids) {
      const books = await Promise.all(q.ids.map((id) => fetchBook(id, 6500).catch(() => undefined)));
      if (books.every((b) => b === undefined)) return fail(502, 'upstream_error', 'The Gutenberg catalog did not answer.');
      const results = books.filter((b) => b && b.copyright !== true);
      return json({ count: results.length, page: 1, pageSize: Math.max(results.length, 1), hasNext: false, hasPrev: false, results }, LIST_CACHE);
    }
    const langs = q.languages ? q.languages.split(',') : [];
    const out = await fetchSearch({
      query: buildQuery(q), sort: q.sort, page: q.page,
      language: langs.length === 1 ? langs[0] : '',
    });
    let results = out.results;
    if (langs.length > 1) results = results.filter((b) => b.languages.some((l) => langs.includes(l)));
    return json({
      count: null, // Gutenberg's feed does not report a total
      page: q.page,
      pageSize: OPDS_PAGE_SIZE,
      hasNext: out.hasNext,
      hasPrev: q.page > 1,
      results,
    }, LIST_CACHE);
  } catch (err) {
    const timeout = err?.name === 'AbortError';
    if (err?.status === 502) return fail(502, 'upstream_error', err.message);
    return fail(timeout ? 504 : 502, timeout ? 'upstream_timeout' : 'upstream_unreachable',
      timeout ? 'The Gutenberg catalog took too long to answer.' : 'Could not reach the Gutenberg catalog.');
  }
};

export const config = {
  path: '/api/catalog',
  rateLimit: { windowLimit: 120, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
