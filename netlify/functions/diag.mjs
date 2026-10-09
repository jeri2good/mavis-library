// TEMPORARY: inspects Project Gutenberg's OPDS feed. Remove after checks.
import { json, UA } from '../lib/shared.mjs';

async function get(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 7000);
  const t = Date.now();
  try {
    const r = await fetch(url, { headers: { 'user-agent': UA }, signal: ctl.signal });
    return { status: r.status, ms: Date.now() - t, text: await r.text() };
  } catch (err) { return { error: String(err), ms: Date.now() - t }; } finally { clearTimeout(timer); }
}

const strip = (s) => s.replace(/<[^>]+>/g, ' | ').replace(/\s+/g, ' ');

export default async (req) => {
  const sp = new URL(req.url).searchParams;
  const queries = sp.getAll('q').slice(0, 6);
  if (sp.get('book')) {
    const r = await get(`https://www.gutenberg.org/ebooks/${Number(sp.get('book'))}.opds`);
    const entry = (r.text || '').split('<entry')[1] || '';
    const content = entry.split('<content')[1]?.split('</content>')[0] || '';
    return json({ status: r.status, ms: r.ms, contentStripped: strip(content).slice(0, 2500), rights: (entry.match(/<rights>([\s\S]*?)<\/rights>/) || [])[1] });
  }
  const out = {};
  await Promise.all(queries.map(async (q) => {
    const r = await get(`https://www.gutenberg.org/ebooks/search.opds/?query=${encodeURIComponent(q)}&sort_order=downloads`);
    const entries = (r.text || '').split('<entry').slice(1);
    const books = entries.filter((e) => /<id>[^<]*ebooks\/\d+\.opds/.test(e));
    out[q] = {
      status: r.status, ms: r.ms, error: r.error, bookCount: books.length,
      hasNext: /rel="next"/.test(r.text || ''),
      first: books.slice(0, 4).map((e) => `${(e.match(/ebooks\/(\d+)\.opds/) || [])[1]} :: ${(e.match(/<title>([\s\S]*?)<\/title>/) || [])[1]} :: ${(e.match(/<content[^>]*>([\s\S]*?)<\/content>/) || [])[1]}`),
    };
  }));
  return json(out);
};

export const config = { path: '/api/diag' };
