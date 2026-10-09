// Bible data: KJV (with Strong's numbers) and WEB, a Strong's lexicon,
// OpenBible cross-references, reference parsing ("1 Cor 13:4-7"), and
// concordance search. Files live in /bible/ and are cached for offline use.

const BASE = '/bible';
let indexP = null;
const books = new Map(); // `${tr}/${id}` → Promise<{chapters}>
const lex = new Map();
const xrefs = new Map();

export const TRANSLATIONS = ['kjv', 'web']; // bundled with the app (offline from the first visit)

/** Every translation Mavis can show. "remote" ones come through /api/bible-ext and are kept offline once read. */
export const ALL_TRANSLATIONS = [
  { id: 'kjv', short: 'KJV', name: 'King James Version', local: true },
  { id: 'web', short: 'WEB', name: 'World English Bible', local: true },
  { id: 'BSB', short: 'BSB', name: 'Berean Standard Bible', note: 'Berean Standard Bible: public domain (dedicated 2023), berean.bible.' },
  { id: 'eng_asv', short: 'ASV', name: 'American Standard Version (1901)', note: 'ASV (1901): public domain.' },
  { id: 'eng_ylt', short: 'YLT', name: 'Young’s Literal Translation', note: 'Young’s Literal Translation: public domain.' },
  { id: 'eng_gnv', short: 'Geneva', name: 'Geneva Bible (1599)', note: 'Geneva Bible (1599): public domain.' },
];
export const trInfo = (id) => ALL_TRANSLATIONS.find((t) => t.id === id) || ALL_TRANSLATIONS[0];

export const COMMENTARIES = [
  { id: 'matthew-henry', name: 'Matthew Henry' },
  { id: 'jamieson-fausset-brown', name: 'Jamieson, Fausset & Brown' },
  { id: 'john-gill', name: 'John Gill' },
  { id: 'adam-clarke', name: 'Adam Clarke' },
  { id: 'keil-delitzsch', name: 'Keil & Delitzsch', ot: true },
  { id: 'john-calvin', name: 'John Calvin' },
  { id: 'tyndale', name: 'Tyndale Open Study Notes' },
];
export const EXTRA_CREDITS = 'More translations and commentaries via the HelloAO Free Use Bible API (bible.helloao.org). Tyndale Open Study Notes © Tyndale House Publishers, CC BY-SA 4.0.';

const remote = new Map(); // url → Promise
function getRemote(url) {
  if (!remote.has(url)) {
    const p = (async () => {
      let r;
      try { r = await fetch(url); } catch { throw new Error('This isn’t saved on the device yet and you’re offline. Open it once with signal to keep it.'); }
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.message || `Couldn’t load it (${r.status}).`);
      return j;
    })();
    remote.set(url, p);
    p.catch(() => remote.delete(url));
  }
  return remote.get(url);
}

/**
 * One chapter in any translation:
 * { tr, verses: [plain text], raw?: [KJV token strings], headings: {v: [..]}, notes: {v: [..]}, subtitle? }
 */
export async function loadChapter(tr, book, chapter) {
  const info = trInfo(tr);
  if (info.local) {
    const data = await loadBook(info.id, book);
    const raw = data.chapters[chapter - 1] || [];
    return { tr: info.id, verses: info.id === 'kjv' ? raw.map(plain) : raw.slice(), raw: info.id === 'kjv' ? raw : null, headings: {}, notes: {} };
  }
  const j = await getRemote(`/api/bible-ext?t=${encodeURIComponent(info.id)}&b=${encodeURIComponent(book)}&c=${chapter}`);
  return { tr: info.id, verses: j.verses || [], raw: null, headings: j.headings || {}, notes: j.notes || {}, subtitle: j.subtitle, missing: j.missing };
}

export function loadCommentary(id, book, chapter) {
  return getRemote(`/api/bible-ext?cm=${encodeURIComponent(id)}&b=${encodeURIComponent(book)}&c=${chapter}`);
}

const il = new Map();
/** Word-by-word Hebrew/Greek for a chapter, in English verse numbering. */
export function loadInterlinear(book, chapter) {
  const k = `${book}/${chapter}`;
  if (!il.has(k)) { const p = getJSON(`${BASE}/il/${encodeURIComponent(book)}/${chapter}.json`); il.set(k, p); p.catch(() => il.delete(k)); }
  return il.get(k);
}

// ---------- Nave's Topical Bible ----------
let navesList = null;
const navesShards = new Map();
const navesVerse = new Map();
export function navesTopics() {
  if (!navesList) { navesList = getJSON(`${BASE}/naves/topics.json`); navesList.catch(() => { navesList = null; }); }
  return navesList;
}
export async function navesTopic(id) {
  const { topics } = await navesTopics();
  const t = topics[id];
  if (!t) return null;
  const letter = /^[A-Z]/.test(t[0]) ? t[0][0] : '_';
  if (!navesShards.has(letter)) { const p = getJSON(`${BASE}/naves/${letter}.json`); navesShards.set(letter, p); p.catch(() => navesShards.delete(letter)); }
  const shard = await navesShards.get(letter);
  const e = shard[id];
  return e ? { id, subject: e.s, entries: e.e.map(([label, ...refs]) => ({ label, refs })) } : null;
}
export async function navesForVerse(book, chapter, verse) {
  if (!navesVerse.has(book)) { const p = getJSON(`${BASE}/naves/v/${encodeURIComponent(book)}.json`).catch(() => ({})); navesVerse.set(book, p); }
  const map = await navesVerse.get(book);
  const ids = map[`${chapter}:${verse}`] || [];
  const { topics } = await navesTopics();
  return ids.map((id) => ({ id, subject: topics[id]?.[0], count: topics[id]?.[1] }));
}
/** "Ps 23:1-4" (the format Nave's data uses) → { book, chapter, verse, verseEnd } */
export function parseShortRef(s) {
  const m = /^([1-3]?[A-Za-z]+) (\d+)(?::(\d+)(?:-(\d+))?)?$/.exec(String(s || ''));
  return m ? { book: m[1], chapter: Number(m[2]), verse: m[3] ? Number(m[3]) : null, verseEnd: m[4] ? Number(m[4]) : null } : null;
}
export const titleCase = (s) => String(s || '').toLowerCase().replace(/(^|[\s(,'-])(\p{L})/gu, (m, a, b) => a + b.toUpperCase());

async function getJSON(path) {
  const r = await fetch(path);
  if (!r.ok) throw new Error(r.status === 404 ? 'That part of the Bible is missing.' : `Couldn't load the Bible (${r.status}).`);
  return r.json();
}

export function loadIndex() {
  if (!indexP) {
    indexP = getJSON(`${BASE}/index.json`).then((idx) => {
      idx.byId = new Map(idx.books.map((b, i) => [b.id, { ...b, order: i }]));
      return idx;
    });
    indexP.catch(() => { indexP = null; });
  }
  return indexP;
}

export function loadBook(tr, id) {
  const k = `${tr}/${id}`;
  if (!books.has(k)) {
    const p = getJSON(`${BASE}/${tr}/${encodeURIComponent(id)}.json`);
    books.set(k, p);
    p.catch(() => books.delete(k));
  }
  return books.get(k);
}

export function loadLexicon(prefix) {
  if (!lex.has(prefix)) { const p = getJSON(`${BASE}/lex/${prefix}.json`); lex.set(prefix, p); p.catch(() => lex.delete(prefix)); }
  return lex.get(prefix);
}

export async function strongsEntry(num) {
  const m = /^([HG])(\d+)$/i.exec(num || '');
  if (!m) return null;
  const k = `${m[1].toUpperCase()}${Number(m[2])}`;
  const d = await loadLexicon(k[0]);
  const e = d[k];
  return e ? { id: k, lemma: e[0], translit: e[1], gloss: e[2], definition: e[3], morph: e[4] } : null;
}

export function loadXrefs(id) {
  if (!xrefs.has(id)) { const p = getJSON(`${BASE}/xref/${encodeURIComponent(id)}.json`); xrefs.set(id, p); p.catch(() => xrefs.delete(id)); }
  return xrefs.get(id);
}

// ---------- KJV token format: "In the beginning{H7225} God{H430} …" ----------

export function tokens(verse) {
  const out = [];
  const re = /(\s?)([^{}]*?)\{([HG0-9,]*)\}/g;
  let m;
  while ((m = re.exec(String(verse || '')))) {
    out.push({ text: (out.length ? ' ' : '') + m[2], strongs: m[3] ? m[3].split(',') : null });
  }
  return out;
}

export const plain = (verse) => String(verse || '').replace(/\{[HG0-9,]*\}/g, '');

// ---------- references ----------

const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
let aliasMap = null;
async function aliases() {
  if (aliasMap) return aliasMap;
  const idx = await loadIndex();
  aliasMap = new Map();
  for (const b of idx.books) {
    aliasMap.set(norm(b.name), b.id);
    aliasMap.set(norm(b.id), b.id);
    for (const a of b.abbr) aliasMap.set(norm(a), b.id);
  }
  aliasMap.set('psalm', 'Ps'); aliasMap.set('songofsongs', 'Song');
  return aliasMap;
}

/**
 * Parse "John 3:16", "jn 3 16", "1 Cor 13:4-7", "Psalm 23", "Rev 22".
 * Returns { book, chapter, verse, verseEnd } or null.
 */
export async function parseRef(input) {
  const s = String(input || '').trim().replace(/[–—]/g, '-').replace(/\s+/g, ' ');
  const m = /^((?:[1-3]|i{1,3})\s?[a-z][a-z .]*?|[a-z][a-z .]*?)\.?\s*(\d{1,3})(?:\s*[:. ]\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?)?$/i.exec(s);
  if (!m) return null;
  let bookKey = norm(m[1].replace(/^iii/i, '3').replace(/^ii/i, '2').replace(/^i(?=\s|[a-z]{2})/i, '1'));
  const map = await aliases();
  let id = map.get(bookKey);
  if (!id) {
    // Unique prefix match ("revel", "philipp").
    const hits = [...new Set([...map.entries()].filter(([k]) => k.startsWith(bookKey) && bookKey.length >= 3).map(([, v]) => v))];
    if (hits.length === 1) id = hits[0];
  }
  if (!id) return null;
  const idx = await loadIndex();
  const b = idx.byId.get(id);
  const chapter = Number(m[2]);
  if (chapter < 1 || chapter > b.verses.length) return null;
  const verse = m[3] ? Number(m[3]) : null;
  const max = b.verses[chapter - 1];
  if (verse && (verse < 1 || verse > max)) return null;
  const verseEnd = m[4] ? Math.min(max, Math.max(verse, Number(m[4]))) : null;
  return { book: id, chapter, verse, verseEnd };
}

/** "John.3.16" or "John.3.16-John.3.18" → { book, chapter, verse, verseEnd } */
export function parseOsis(s) {
  const [a, b] = String(s || '').split('-');
  const m = /^([1-3]?[A-Za-z]+)\.(\d+)(?:\.(\d+))?$/.exec(a || '');
  if (!m) return null;
  const r = { book: m[1], chapter: Number(m[2]), verse: m[3] ? Number(m[3]) : null, verseEnd: null };
  const n = b && /^([1-3]?[A-Za-z]+)\.(\d+)\.(\d+)$/.exec(b);
  if (n && n[1] === r.book && Number(n[2]) === r.chapter) r.verseEnd = Number(n[3]);
  else if (n) r.toChapter = Number(n[2]), r.verseEnd = Number(n[3]);
  return r;
}

export const osis = (r) => `${r.book}.${r.chapter}${r.verse ? `.${r.verse}` : ''}${r.verseEnd && r.verseEnd !== r.verse ? `-${r.book}.${r.chapter}.${r.verseEnd}` : ''}`;

export async function label(r) {
  const idx = await loadIndex();
  const name = idx.byId.get(r.book)?.name || r.book;
  if (!r.verse) return `${name} ${r.chapter}`;
  return `${name} ${r.chapter}:${r.verse}${r.verseEnd && r.verseEnd !== r.verse ? `${r.toChapter ? `–${r.toChapter}:` : '–'}${r.verseEnd}` : ''}`;
}

export function labelSync(idx, r) {
  const name = idx.byId.get(r.book)?.name || r.book;
  if (!r.verse) return `${name} ${r.chapter}`;
  return `${name} ${r.chapter}:${r.verse}${r.verseEnd && r.verseEnd !== r.verse ? `–${r.verseEnd}` : ''}`;
}

export async function passageText(tr, r) {
  const ch = (await loadChapter(tr, r.book, r.chapter)).verses;
  const from = r.verse || 1;
  const to = r.verseEnd || r.verse || ch.length;
  const out = [];
  for (let v = from; v <= to && v <= ch.length; v++) out.push({ v, text: ch[v - 1] || '' });
  return out;
}

// ---------- concordance search ----------

/**
 * query: words (all must appear), "an exact phrase", or a Strong's number (H430, G26).
 * scope: 'all' | 'OT' | 'NT' | book id.
 * Returns { total, results: [{ book, chapter, verse, text, marks: [[start,end]] }], byBook: {id: count} }
 */
export async function search(query, { tr = 'kjv', scope = 'all', limit = 300, onProgress } = {}) {
  const q = String(query || '').trim();
  if (!q) return { total: 0, results: [], byBook: {} };
  const idx = await loadIndex();
  const strongs = /^[HG]\d{1,4}$/i.test(q) ? `${q[0].toUpperCase()}${Number(q.slice(1))}` : null;
  const phrase = /^".+"$/.test(q) ? q.slice(1, -1).toLowerCase() : null;
  const words = !strongs && !phrase ? q.toLowerCase().split(/\s+/).filter((w) => w.length > 0).map((w) => w.replace(/[^\p{L}\p{N}'’-]/gu, '')).filter(Boolean) : [];
  const wordRes = words.map((w) => new RegExp(`\\b${w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`, 'iu'));
  const strongsRe = strongs ? new RegExp(`[{,]${strongs}[,}]`) : null;
  const translation = strongs ? 'kjv' : tr;
  const list = idx.books.filter((b) => scope === 'all' || b.testament === scope || b.id === scope);
  const results = [];
  const byBook = {};
  let total = 0;
  for (let bi = 0; bi < list.length; bi++) {
    const b = list[bi];
    const data = await loadBook(translation, b.id);
    data.chapters.forEach((ch, ci) => ch.forEach((raw, vi) => {
      let hit = false;
      let text = translation === 'kjv' ? plain(raw) : raw;
      const marks = [];
      if (strongsRe) {
        if (strongsRe.test(raw)) {
          hit = true;
          // Mark the English words carrying this number.
          let pos = 0;
          for (const t of tokens(raw)) {
            const len = t.text.length;
            if (t.strongs?.includes(strongs)) marks.push([pos + (t.text.startsWith(' ') ? 1 : 0), pos + len]);
            pos += len;
          }
          text = tokens(raw).map((t) => t.text).join('');
        }
      } else if (phrase) {
        const i = text.toLowerCase().indexOf(phrase);
        if (i >= 0) { hit = true; marks.push([i, i + phrase.length]); }
      } else if (wordRes.every((re) => re.test(text))) {
        hit = true;
        for (const w of words) {
          const re = new RegExp(`\\b${w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\p{L}'’]*`, 'giu');
          let m; while ((m = re.exec(text))) marks.push([m.index, m.index + m[0].length]);
        }
      }
      if (hit) {
        total++;
        byBook[b.id] = (byBook[b.id] || 0) + 1;
        if (results.length < limit) results.push({ book: b.id, chapter: ci + 1, verse: vi + 1, text, marks: marks.sort((x, y) => x[0] - y[0]) });
      }
    }));
    onProgress?.((bi + 1) / list.length);
  }
  return { total, results, byBook, strongs, translation };
}

/** Warm the offline cache with every Bible file (runs once, in the background). */
export async function prefetchAll() {
  try {
    const idx = await loadIndex();
    const paths = [];
    for (const b of idx.books) for (const tr of TRANSLATIONS) paths.push(`${BASE}/${tr}/${b.id}.json`);
    paths.push(`${BASE}/lex/H.json`, `${BASE}/lex/G.json`);
    for (const b of idx.books) paths.push(`${BASE}/xref/${b.id}.json`);
    const cache = await caches.open('mavis-bible-v1');
    for (let i = 0; i < paths.length; i += 6) {
      await Promise.all(paths.slice(i, i + 6).map(async (p) => {
        if (await cache.match(p)) return;
        const r = await fetch(p);
        if (r.ok) await cache.put(p, r);
      }));
    }
    return true;
  } catch { return false; }
}
