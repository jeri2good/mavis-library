// GET /api/epub?id=1342 — stream one public-domain EPUB from Project Gutenberg.
//
// Constraints (this is not an open proxy):
//  * the only input is a numeric Gutenberg id;
//  * the book must be marked public domain in the USA by the catalog and
//    must list an EPUB format;
//  * the file URL must be on gutenberg.org (or the configured mirror host);
//  * responses are capped below Netlify's streaming limit and must be
//    application/epub+zip;
//  * one upstream request per user download; repeat downloads of the same id
//    are answered from Netlify's CDN cache so Gutenberg is not hit again.

import { fail, onlyGet, upstream, softLimit, clientKey, UA } from '../lib/shared.mjs';
import { fetchBook } from '../lib/gutenberg.mjs';

const MIRROR = process.env.GUTENBERG_MIRROR || ''; // e.g. https://mirror.example.org (must contain /cache/epub/)
const MAX_BYTES = 19 * 1024 * 1024;

const allowedHosts = new Set(['www.gutenberg.org', 'gutenberg.org']);
if (MIRROR) { try { allowedHosts.add(new URL(MIRROR).host); } catch { /* ignore bad config */ } }

export function isAllowed(u) {
  try {
    const url = new URL(u);
    return url.protocol === 'https:' && allowedHosts.has(url.host);
  } catch { return false; }
}

export function candidates(id, listedUrl) {
  const list = [];
  if (MIRROR) {
    list.push(`${MIRROR.replace(/\/$/, '')}/cache/epub/${id}/pg${id}-images-3.epub`);
    list.push(`${MIRROR.replace(/\/$/, '')}/cache/epub/${id}/pg${id}.epub`);
  }
  list.push(`https://www.gutenberg.org/ebooks/${id}.epub3.images`);
  if (listedUrl && isAllowed(listedUrl.replace(/^http:/, 'https:'))) list.push(listedUrl.replace(/^http:/, 'https:'));
  list.push(`https://www.gutenberg.org/ebooks/${id}.epub.noimages`);
  return [...new Set(list)];
}

function capStream(body, max) {
  let total = 0;
  return body.pipeThrough(new TransformStream({
    transform(chunk, ctl) {
      total += chunk.byteLength;
      if (total > max) ctl.error(new Error('EPUB exceeds size limit'));
      else ctl.enqueue(chunk);
    },
  }));
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`epub:${clientKey(req, context)}`, { limit: 12 })) {
    return fail(429, 'rate_limited', 'Too many downloads in a short time. Wait a minute and try again.');
  }
  const id = new URL(req.url).searchParams.get('id') || '';
  if (!/^\d{1,6}$/.test(id)) return fail(400, 'bad_request', 'id must be a numeric Gutenberg id.');

  // 1. Confirm public-domain status and an EPUB format in Gutenberg's own record.
  let listed = null;
  try {
    const book = await fetchBook(Number(id), 6000);
    if (!book) return fail(404, 'not_found', 'That book is not in the Project Gutenberg catalog.');
    if (book.copyright !== false) {
      return fail(451, 'not_public_domain', 'This book is not marked public domain in the USA, so Mavis will not download it.');
    }
    if (!book.epub) return fail(404, 'no_epub', 'Project Gutenberg does not offer an EPUB for this book.');
    listed = book.epubUrl;
  } catch (err) {
    return fail(502, err?.status === 502 ? 'upstream_error' : 'upstream_unreachable', 'The Gutenberg catalog could not confirm this book right now.');
  }

  // 2. Fetch the file from an allowed host, trying smaller editions if needed.
  let lastProblem = 'Project Gutenberg did not return an EPUB file.';
  for (const url of candidates(id, listed)) {
    let res;
    try {
      res = await upstream(url, { timeoutMs: 7000, accept: 'application/epub+zip' });
    } catch {
      lastProblem = 'Project Gutenberg took too long to send the file.';
      continue;
    }
    if (!isAllowed(res.url || url)) { res.body?.cancel(); lastProblem = 'The file redirected to an unexpected host.'; continue; }
    if (!res.ok || !res.body) { res.body?.cancel(); lastProblem = `Project Gutenberg answered ${res.status}.`; continue; }
    const type = (res.headers.get('content-type') || '').toLowerCase();
    if (!type.includes('application/epub+zip') && !type.includes('application/octet-stream')) {
      res.body.cancel(); lastProblem = 'Project Gutenberg returned something other than an EPUB (possibly a rate-limit page).'; continue;
    }
    const len = Number(res.headers.get('content-length') || 0);
    if (len && len > MAX_BYTES) { res.body.cancel(); lastProblem = 'The illustrated edition is too large; trying a smaller edition.'; continue; }
    const headers = {
      'content-type': 'application/epub+zip',
      'content-disposition': `attachment; filename="pg${id}.epub"`,
      'cache-control': 'public, max-age=86400',
      'netlify-cdn-cache-control': 'public, s-maxage=2592000, stale-while-revalidate=604800',
      'x-content-type-options': 'nosniff',
      'x-mavis-source': new URL(res.url || url).host,
    };
    if (len) headers['content-length'] = String(len);
    return new Response(capStream(res.body, MAX_BYTES), { status: 200, headers });
  }
  return fail(502, 'download_failed', `${lastProblem} You can open the book on Project Gutenberg and import the EPUB instead.`);
};

export const config = {
  path: '/api/epub',
  rateLimit: { windowLimit: 20, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};

export { UA };
