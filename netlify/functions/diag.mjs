// TEMPORARY: inspect the HelloAO Free Bible API's shapes. Remove after checks.
import { json, env } from '../lib/shared.mjs';

const H = 'https://bible.helloao.org/api';
async function get(path) {
  const t = Date.now();
  try {
    const r = await fetch(`${H}${path}`, { headers: { 'user-agent': 'MavisLibrary/0.3 (+https://mavis-library.netlify.app)' } });
    const text = await r.text();
    let j = null; try { j = JSON.parse(text); } catch { /* not json */ }
    return { status: r.status, ms: Date.now() - t, cors: r.headers.get('access-control-allow-origin'), cache: r.headers.get('cache-control'), j, len: text.length };
  } catch (err) { return { error: String(err) }; }
}
const shape = (v, d = 0) => {
  if (Array.isArray(v)) return d > 3 ? '[…]' : [v.length, shape(v[0], d + 1)];
  if (v && typeof v === 'object') return d > 3 ? '{…}' : Object.fromEntries(Object.entries(v).slice(0, 25).map(([k, x]) => [k, shape(x, d + 1)]));
  return typeof v === 'string' ? `str:${v.slice(0, 60)}` : v;
};

export default async (req) => {
  const u = new URL(req.url);
  if (u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const w = u.searchParams.get('w') || 'lists';
  if (w === 'lists') {
    const [tr, cm, ds] = await Promise.all([get('/available_translations.json'), get('/available_commentaries.json'), get('/available_datasets.json')]);
    const eng = (tr.j?.translations || []).filter((t) => t.language === 'eng').map((t) => `${t.id}|${t.englishName || t.name}|${(t.licenseUrl || '').slice(0, 60)}`);
    return json({
      trStatus: tr.status, cors: tr.cors, cache: tr.cache, englishTranslations: eng,
      commentaries: (cm.j?.commentaries || []).map((c) => `${c.id}|${c.name}|${c.language}|books:${c.numberOfBooks}|${(c.licenseUrl || '').slice(0, 60)}`),
      datasets: (ds.j?.datasets || []).map((d) => `${d.id}|${d.name}|${(d.licenseUrl || '').slice(0, 60)}`),
      dsShape: shape(ds.j),
    });
  }
  if (w === 'bsb') {
    const books = await get('/BSB/books.json');
    const ch = await get('/BSB/JHN/3.json');
    return json({ booksStatus: books.status, translation: books.j?.translation, firstBook: books.j?.books?.[0], chStatus: ch.status, cors: ch.cors, chShape: shape(ch.j), v16: ch.j?.chapter?.content?.find((x) => x.number === 16), firstItems: ch.j?.chapter?.content?.slice(0, 3), footnotes: ch.j?.chapter?.footnotes?.slice(0, 2) });
  }
  if (w === 'comm') {
    const id = u.searchParams.get('id') || 'matthew-henry';
    const books = await get(`/c/${id}/books.json`);
    const ch = await get(`/c/${id}/JHN/3.json`);
    return json({ booksStatus: books.status, commentary: books.j?.commentary, nBooks: books.j?.books?.length, chStatus: ch.status, chShape: shape(ch.j), intro: String(ch.j?.chapter?.introduction || '').slice(0, 300), first: (ch.j?.chapter?.content || []).slice(0, 2).map((v) => ({ n: v.number, c: JSON.stringify(v.content).slice(0, 400) })) });
  }
  if (w === 'ds') {
    const id = u.searchParams.get('id');
    const books = await get(`/d/${id}/books.json`);
    const ch = await get(`/d/${id}/JHN/3.json`);
    return json({ booksStatus: books.status, dataset: books.j?.dataset, chStatus: ch.status, chShape: shape(ch.j), sample: JSON.stringify(ch.j?.chapter?.content?.[15] || ch.j?.chapter?.content?.[0]).slice(0, 700) });
  }
  return json({});
};

export const config = { path: '/api/diag' };
