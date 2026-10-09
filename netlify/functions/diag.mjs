// TEMPORARY: reports whether the server can reach the public book services.
// Returns only status codes and timings. Remove after deployment checks.
import { json, UA } from '../lib/shared.mjs';

const TARGETS = {
  gutendex: 'https://gutendex.com/books/1342',
  gutendexList: 'https://gutendex.com/books?search=pride&copyright=false&mime_type=application%2Fepub',
  gutenbergEpub: 'https://www.gutenberg.org/cache/epub/1342/pg1342-images-3.epub',
  openlibrary: 'https://openlibrary.org/search.json?q=pride+and+prejudice&limit=1',
  olCovers: 'https://covers.openlibrary.org/b/isbn/9780141439518-M.jpg?default=false',
  googleBooks: 'https://www.googleapis.com/books/v1/volumes?q=intitle:pride+inauthor:austen&maxResults=1',
};

async function probe(url, ua) {
  const t = Date.now();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 4000);
  try {
    const r = await fetch(url, { method: 'GET', headers: ua ? { 'user-agent': ua } : {}, signal: ctl.signal, redirect: 'follow' });
    const body = (await r.text()).slice(0, 120).replace(/\s+/g, ' ');
    return { status: r.status, ms: Date.now() - t, body: r.ok ? body.slice(0, 40) : body };
  } catch (err) {
    return { error: `${err.name}: ${String(err.message).slice(0, 120)}`, cause: String(err.cause?.code || err.cause?.message || '').slice(0, 120), ms: Date.now() - t };
  } finally { clearTimeout(timer); }
}

export default async () => {
  const out = {};
  await Promise.all(Object.entries(TARGETS).map(async ([k, u]) => {
    [out[k], out[`${k}_noUA`]] = await Promise.all([probe(u, UA), probe(u, null)]);
  }));
  return json(out);
};

export const config = { path: '/api/diag' };
