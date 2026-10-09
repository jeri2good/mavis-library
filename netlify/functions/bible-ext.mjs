// GET /api/bible-ext — extra Bible translations and commentaries from the
// HelloAO Free Use Bible API (bible.helloao.org), which doesn't allow
// browsers to call it directly. Only fixed ids are accepted; answers are
// cached on Netlify's CDN for a month and kept offline by the app.
//
//   ?tr=BSB&b=John&c=3           → { verses: [...], headings: {v: [..]}, notes: {v: [..]}, subtitle }
//   ?cm=matthew-henry&b=John&c=3 → { intro, sections: [{ v, text }] }

import { json, fail, onlyGet, upstream, softLimit, clientKey, clean } from '../lib/shared.mjs';

const BASE = 'https://bible.helloao.org/api';

export const TRANSLATIONS = {
  BSB: { name: 'Berean Standard Bible', short: 'BSB', note: 'Public domain (dedicated 2023).' },
  eng_asv: { name: 'American Standard Version (1901)', short: 'ASV', note: 'Public domain.' },
  eng_ylt: { name: 'Young’s Literal Translation', short: 'YLT', note: 'Public domain.' },
  eng_gnv: { name: 'Geneva Bible (1599)', short: 'Geneva', note: 'Public domain.' },
};
export const COMMENTARIES = {
  'matthew-henry': { name: 'Matthew Henry', note: 'Public domain.' },
  'jamieson-fausset-brown': { name: 'Jamieson, Fausset & Brown', note: 'Public domain.' },
  'john-gill': { name: 'John Gill', note: 'Public domain.' },
  'adam-clarke': { name: 'Adam Clarke', note: 'Public domain.' },
  'keil-delitzsch': { name: 'Keil & Delitzsch (Old Testament)', note: 'Public domain.' },
  'john-calvin': { name: 'John Calvin', note: 'Public domain.' },
  tyndale: { name: 'Tyndale Open Study Notes', note: 'CC BY-SA 4.0, Tyndale House Publishers.' },
};

export const USFM = {
  Gen: 'GEN', Exod: 'EXO', Lev: 'LEV', Num: 'NUM', Deut: 'DEU', Josh: 'JOS', Judg: 'JDG', Ruth: 'RUT', '1Sam': '1SA', '2Sam': '2SA',
  '1Kgs': '1KI', '2Kgs': '2KI', '1Chr': '1CH', '2Chr': '2CH', Ezra: 'EZR', Neh: 'NEH', Esth: 'EST', Job: 'JOB', Ps: 'PSA', Prov: 'PRO',
  Eccl: 'ECC', Song: 'SNG', Isa: 'ISA', Jer: 'JER', Lam: 'LAM', Ezek: 'EZK', Dan: 'DAN', Hos: 'HOS', Joel: 'JOL', Amos: 'AMO',
  Obad: 'OBA', Jonah: 'JON', Mic: 'MIC', Nah: 'NAM', Hab: 'HAB', Zeph: 'ZEP', Hag: 'HAG', Zech: 'ZEC', Mal: 'MAL',
  Matt: 'MAT', Mark: 'MRK', Luke: 'LUK', John: 'JHN', Acts: 'ACT', Rom: 'ROM', '1Cor': '1CO', '2Cor': '2CO', Gal: 'GAL', Eph: 'EPH',
  Phil: 'PHP', Col: 'COL', '1Thess': '1TH', '2Thess': '2TH', '1Tim': '1TI', '2Tim': '2TI', Titus: 'TIT', Phlm: 'PHM', Heb: 'HEB',
  Jas: 'JAS', '1Pet': '1PE', '2Pet': '2PE', '1John': '1JN', '2John': '2JN', '3John': '3JN', Jude: 'JUD', Rev: 'REV',
};

const textOf = (parts) => (Array.isArray(parts) ? parts : [parts]).map((p) => {
  if (typeof p === 'string') return p;
  if (p && typeof p === 'object') {
    if (p.lineBreak) return '\n';
    if (typeof p.text === 'string') return p.text;
    if (typeof p.heading === 'string') return p.heading;
  }
  return '';
}).join('').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n');

export function normalizeChapter(j) {
  const verses = [];
  const headings = {};
  const notes = {};
  const footnotes = new Map((j?.chapter?.footnotes || []).map((f) => [f.noteId, clean(f.text, 600)]));
  let subtitle = '';
  let pending = [];
  for (const item of j?.chapter?.content || []) {
    if (item.type === 'heading') { pending.push(clean(textOf(item.content), 200)); continue; }
    if (item.type === 'hebrew_subtitle') { subtitle = clean(textOf(item.content), 400); continue; }
    if (item.type !== 'verse' || !Number.isInteger(item.number)) continue;
    const v = item.number;
    if (pending.length) { headings[v] = pending; pending = []; }
    let s = '';
    for (const p of item.content || []) {
      if (p && typeof p === 'object' && p.noteId != null) {
        const t = footnotes.get(p.noteId);
        if (t) (notes[v] ||= []).push(t);
        continue;
      }
      const piece = textOf(p);
      // Text on either side of a footnote marker arrives as separate strings.
      if (s && piece && !/\s$/.test(s) && !/^[\s,.;:!?’”)\]]/.test(piece)) s += ' ';
      s += piece;
    }
    s = s.replace(/\s+/g, ' ').trim();
    verses[v - 1] = verses[v - 1] ? `${verses[v - 1]} ${s}` : s;
  }
  for (let i = 0; i < verses.length; i++) if (verses[i] == null) verses[i] = '';
  return { verses, headings, notes, subtitle: subtitle || undefined };
}

// Like clean(), but keeps paragraph breaks.
const cleanProse = (s, max) => String(s || '').replace(/\r/g, '').replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, ' ')
  .replace(/[ \t]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, max);

export function normalizeCommentary(j) {
  const sections = [];
  for (const item of j?.chapter?.content || []) {
    if (item.type !== 'verse') continue;
    const text = cleanProse(textOf(item.content), 200_000);
    if (text) sections.push({ v: item.number, text });
  }
  const intro = cleanProse(textOf(j?.chapter?.introduction || ''), 20_000);
  return { intro: intro || undefined, sections };
}

export default async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`bx:${clientKey(req, context)}`, { limit: 240 })) return fail(429, 'rate_limited', 'Too many requests. Wait a minute.');
  const sp = new URL(req.url).searchParams;
  const book = USFM[sp.get('b') || ''];
  const chapter = Number(sp.get('c'));
  if (!book || !Number.isInteger(chapter) || chapter < 1 || chapter > 150) return fail(400, 'bad_request', 'Give a book (like John) and a chapter number.');
  const pick = (obj, v) => Object.keys(obj).find((k) => k.toLowerCase() === String(v || '').toLowerCase()) || null;
  const t = pick(TRANSLATIONS, sp.get('tr'));
  const cm = pick(COMMENTARIES, sp.get('cm'));
  let url, kind;
  if (t) { url = `${BASE}/${t}/${book}/${chapter}.json`; kind = 'tr'; }
  else if (cm) { url = `${BASE}/c/${cm}/${book}/${chapter}.json`; kind = 'cm'; }
  else return fail(400, 'bad_request', 'Unknown translation or commentary.');
  try {
    const r = await upstream(url, { timeoutMs: 8000 });
    if (r.status === 404) {
      return json(kind === 'tr' ? { verses: [], headings: {}, notes: {}, missing: true } : { sections: [], missing: true },
        { cache: 'public, max-age=86400', cdn: 'public, s-maxage=2592000' });
    }
    if (!r.ok) return fail(502, 'upstream_error', `The Bible service answered ${r.status}.`);
    const j = await r.json();
    const body = kind === 'tr'
      ? { id: t, ...TRANSLATIONS[t], ...normalizeChapter(j) }
      : { id: cm, ...COMMENTARIES[cm], ...normalizeCommentary(j) };
    return json(body, { cache: 'public, max-age=604800', cdn: 'public, s-maxage=2592000, stale-while-revalidate=2592000' });
  } catch (err) {
    return fail(err?.name === 'AbortError' ? 504 : 502, 'upstream_unreachable', 'The Bible service didn’t answer. Try again in a moment.');
  }
};

export const config = {
  path: '/api/bible-ext',
  rateLimit: { windowLimit: 300, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
