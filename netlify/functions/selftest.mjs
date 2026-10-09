// TEMPORARY: probe the LibriVox API and archive.org audio headers.
import { json, env } from '../lib/shared.mjs';

export default async (req) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const title = u.searchParams.get('title') || 'Pride and Prejudice';
  const t0 = Date.now();
  const r = await fetch(`https://librivox.org/api/feed/audiobooks/?title=${encodeURIComponent(`^${title}`)}&format=json&extended=1&limit=5`, { headers: { 'user-agent': 'MavisLibrary/0.3 (+https://mavis-library.netlify.app)' } });
  const text = await r.text();
  let j = null; try { j = JSON.parse(text); } catch { /* not json */ }
  const books = (j?.books || []).map((b) => ({
    id: b.id, title: b.title, lang: b.language, source: b.url_text_source, total: b.totaltime, secs: b.totaltimesecs, authors: (b.authors || []).map((a) => `${a.first_name} ${a.last_name}`),
    nSections: (b.sections || []).length, s0: b.sections?.[0] ? { title: b.sections[0].title, listen: b.sections[0].listen_url, play: b.sections[0].playtime, readers: (b.sections[0].readers || []).map((x) => x.display_name) } : null,
    keys: Object.keys(b).slice(0, 30),
  }));
  let audio = null;
  const listen = books.find((b) => b.s0?.listen)?.s0.listen;
  if (listen) {
    const a = await fetch(listen, { method: 'HEAD', redirect: 'follow', headers: { origin: 'https://mavis-library.netlify.app' } });
    audio = { status: a.status, finalUrl: a.url, type: a.headers.get('content-type'), length: a.headers.get('content-length'), cors: a.headers.get('access-control-allow-origin'), ranges: a.headers.get('accept-ranges') };
  }
  return json({ http: r.status, ms: Date.now() - t0, books, audio, raw: j ? undefined : text.slice(0, 300) });
};

export const config = { path: '/api/selftest' };
