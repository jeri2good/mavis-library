// TEMPORARY: inspects Project Gutenberg's OPDS feed shape. Remove after checks.
import { json, UA } from '../lib/shared.mjs';

async function get(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 7000);
  const t = Date.now();
  try {
    const r = await fetch(url, { headers: { 'user-agent': UA, accept: 'application/atom+xml, */*' }, signal: ctl.signal });
    const text = await r.text();
    return { status: r.status, ms: Date.now() - t, ctype: r.headers.get('content-type'), len: text.length, text };
  } catch (err) { return { error: String(err) }; } finally { clearTimeout(timer); }
}

export default async (req) => {
  const which = new URL(req.url).searchParams.get('w') || 'search';
  const urls = {
    search: 'https://www.gutenberg.org/ebooks/search.opds/?query=pride&sort_order=downloads',
    popular: 'https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads',
    book: 'https://www.gutenberg.org/ebooks/1342.opds',
    subject: 'https://www.gutenberg.org/ebooks/search.opds/?query=s.mystery&sort_order=downloads',
    page2: 'https://www.gutenberg.org/ebooks/search.opds/?query=pride&sort_order=downloads&start_index=26',
  };
  const r = await get(urls[which]);
  if (r.text) {
    // Strip the text to tag skeleton: element names and attributes, values masked.
    const entries = r.text.split('<entry').length - 1;
    const firstEntry = r.text.slice(r.text.indexOf('<entry'), r.text.indexOf('</entry>') + 8);
    const head = r.text.slice(0, r.text.indexOf('<entry') > 0 ? r.text.indexOf('<entry') : 1500);
    return json({ status: r.status, ms: r.ms, ctype: r.ctype, len: r.len, entries, head: head.slice(0, 3000), firstEntry: firstEntry.slice(0, 4000) });
  }
  return json(r);
};

export const config = { path: '/api/diag' };
