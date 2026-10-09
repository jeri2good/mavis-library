// Builds the Bible study data that sits beside the texts in public/bible/:
//
//   il/<Book>/<chapter>.json   word-by-word Hebrew/Greek (interlinear), KJV verse numbers
//   naves/topics.json          Nave's Topical Bible: every subject, with entry counts
//   naves/<A-Z>.json           subjects → entries → references
//   naves/v/<Book>.json        verse → subjects (for "Topics for this verse")
//
//   node scripts/build-bible-extra.mjs <ohbDataDir> <tagntDataDir> <versificationJson> <navesCsv>
//
// Sources (all redistributable, credited in the app):
//  * Open Scriptures Hebrew Bible (WLC text + morphology), CC BY 4.0, via
//    @metaxia/scriptures-source-openscriptures-ohb.
//  * STEPBible TAGNT (Greek NT with English glosses), CC BY 4.0, via
//    @metaxia/scriptures-source-stepbible-tagnt-tr.
//  * STEPBible TVTMS versification (Hebrew → English verse numbers), CC BY 4.0,
//    via @metaxia/scriptures-source-stepbible-versification.
//  * Nave's Topical Bible (public domain), as structured by Brady Stephenson's
//    bible-data project, CC BY 4.0.

import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { BOOKS } from './bible-books.mjs';

const [ohbDir, tagntDir, versFile, navesCsv] = process.argv.slice(2).map((p) => resolve(p));
const OUT = resolve('public/bible');
const index = JSON.parse(readFileSync(join(OUT, 'index.json'), 'utf8'));
const verseCounts = new Map(index.books.map((b) => [b.id, b.verses]));
const NT_START = BOOKS.findIndex((b) => b[0] === 'Matt');
const mtToEng = JSON.parse(readFileSync(versFile, 'utf8'))['MT->English'];

const numSort = (a, b) => Number(a) - Number(b);
const listNums = (dir) => (existsSync(dir) ? readdirSync(dir).map((x) => x.replace(/\.json$/, '')).filter((x) => /^\d+$/.test(x)).sort(numSort) : []);
const normStrongs = (s) => { const m = /^([HG])0*(\d+)/.exec(s || ''); return m ? `${m[1]}${m[2]}` : ''; };

// ---------- interlinear ----------
let ilWords = 0, ilVerses = 0, unmapped = 0;
for (const [bi, [id, name]] of BOOKS.entries()) {
  const nt = bi >= NT_START;
  const src = join(nt ? tagntDir : ohbDir, id);
  if (!existsSync(src)) { console.warn(`interlinear: missing ${id}`); continue; }
  const counts = verseCounts.get(id);
  // English chapter → verse → { words, title? }
  const out = counts.map((n) => ({ verses: Array.from({ length: n }, () => []), title: null }));
  for (const c of listNums(src)) {
    for (const v of listNums(join(src, c))) {
      const d = JSON.parse(readFileSync(join(src, c, `${v}.json`), 'utf8'));
      let ec = Number(c), ev = Number(v);
      if (!nt) {
        const key = `${name} ${c}:${v}`;
        if (Object.hasOwn(mtToEng, key)) {
          const t = mtToEng[key];
          if (t === null) { // A Psalm title the English Bible doesn't number.
            const ch = out[Number(c) - 1];
            if (ch) ch.title = (ch.title || []).concat(words(d, nt));
            continue;
          }
          ec = t.chapter; ev = t.verse;
        }
      }
      const slot = out[ec - 1]?.verses[ev - 1];
      if (!slot) { unmapped++; continue; }
      slot.push(...words(d, nt));
    }
  }
  mkdirSync(join(OUT, 'il', id), { recursive: true });
  out.forEach((ch, i) => {
    ilVerses += ch.verses.length;
    const body = { book: id, chapter: i + 1, lang: nt ? 'grc' : 'hbo', verses: ch.verses };
    if (ch.title) body.title = ch.title;
    writeFileSync(join(OUT, 'il', id, `${i + 1}.json`), JSON.stringify(body));
  });
}

function words(d, nt) {
  return (d.words || []).map((w) => {
    ilWords++;
    const strongs = (w.strongs || []).map(normStrongs).filter(Boolean).join(',');
    if (!nt) return [w.text, strongs, w.morph || ''];
    const raw = String(w.source?.raw || '').split('\t');
    const translit = (/\(([^)]+)\)/.exec(raw[1] || '') || [])[1] || '';
    return [w.text, translit, w.translation || w.metadata?.gloss || '', strongs, String(w.morph || '').replace(/^robinson:/, '')];
  });
}

// ---------- Nave's Topical Bible ----------
const USFM = {
  GEN: 'Gen', EXO: 'Exod', LEV: 'Lev', NUM: 'Num', DEU: 'Deut', JOS: 'Josh', JDG: 'Judg', RUT: 'Ruth', '1SA': '1Sam', '2SA': '2Sam',
  '1KI': '1Kgs', '2KI': '2Kgs', '1CH': '1Chr', '2CH': '2Chr', EZR: 'Ezra', NEH: 'Neh', EST: 'Esth', JOB: 'Job', PSA: 'Ps', PRO: 'Prov',
  ECC: 'Eccl', SNG: 'Song', SOS: 'Song', SON: 'Song', ISA: 'Isa', JER: 'Jer', LAM: 'Lam', EZK: 'Ezek', EZE: 'Ezek', DAN: 'Dan', HOS: 'Hos', JOL: 'Joel',
  AMO: 'Amos', OBA: 'Obad', JON: 'Jonah', MIC: 'Mic', NAM: 'Nah', NAH: 'Nah', HAB: 'Hab', ZEP: 'Zeph', HAG: 'Hag', ZEC: 'Zech', MAL: 'Mal',
  MAT: 'Matt', MRK: 'Mark', LUK: 'Luke', JHN: 'John', ACT: 'Acts', ROM: 'Rom', '1CO': '1Cor', '2CO': '2Cor', GAL: 'Gal', EPH: 'Eph',
  PHP: 'Phil', COL: 'Col', '1TH': '1Thess', '2TH': '2Thess', '1TI': '1Tim', '2TI': '2Tim', TIT: 'Titus', PHM: 'Phlm', HEB: 'Heb',
  JAS: 'Jas', '1PE': '1Pet', '2PE': '2Pet', '1JN': '1John', '1JHN': '1John', '2JN': '2John', '3JN': '3John', JUD: 'Jude', REV: 'Rev',
};

function parseCsv(text) {
  const rows = [];
  let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) {
      if (ch === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (ch === '"') q = false;
      else field += ch;
    } else if (ch === '"') q = true;
    else if (ch === ',') { row.push(field); field = ''; }
    else if (ch === '\n') { row.push(field.replace(/\r$/, '')); rows.push(row); row = []; field = ''; }
    else field += ch;
  }
  if (field || row.length) { row.push(field); rows.push(row); }
  return rows;
}

/** "EXO 6:16-20; JOS 21:4,10; 23:13" → [{b,c,v,ve}] (book carries forward). */
export function parseRefs(s) {
  const out = [];
  let book = null;
  for (const part of String(s).split(';')) {
    const m = /^\s*(?:([1-3]?[A-Z]{2,4})\s+)?(\d+)(?::([\d,\-\s]+))?\s*$/.exec(part.replace(/\.$/, ''));
    if (!m) continue;
    if (m[1]) { book = USFM[m[1]] || null; }
    if (!book) continue;
    const c = Number(m[2]);
    if (!m[3]) { out.push({ b: book, c }); continue; }
    for (const piece of m[3].split(',')) {
      const r = /^\s*(\d+)(?:\s*-\s*(\d+))?\s*$/.exec(piece);
      if (r) out.push({ b: book, c, v: Number(r[1]), ve: r[2] ? Number(r[2]) : undefined });
    }
  }
  return out;
}

const refStr = (r) => `${r.b} ${r.c}${r.v ? `:${r.v}${r.ve ? `-${r.ve}` : ''}` : ''}`;

let topics = 0, refs = 0;
if (navesCsv && existsSync(navesCsv)) {
  const rows = parseCsv(readFileSync(navesCsv, 'utf8').replace(/^﻿/, ''));
  const head = rows.shift().map((h) => h.trim());
  const iSec = head.indexOf('section'), iSub = head.indexOf('subject'), iEnt = head.indexOf('entry');
  const shards = {};
  const list = [];
  const byVerse = {}; // book → "c:v" → [topic ids]
  for (const r of rows) {
    const subject = (r[iSub] || '').trim();
    if (!subject) continue;
    const letter = /^[A-Z]/.test(subject) ? subject[0] : '#';
    const id = list.length;
    const entries = [];
    const seen = new Set();
    for (const raw of String(r[iEnt] || '').split('\n')) {
      const line = raw.replace(/^\s*-\s*/, '').trim();
      if (!line) continue;
      // Split "Label text REFS" at the first book code that starts the references.
      const m = /^(.*?)(?:\s|^)((?:[1-3]?[A-Z]{2,4}) \d[\s\S]*)$/.exec(line);
      const label = m ? m[1].replace(/[,:;]\s*$/, '').trim() : line;
      const parsed = m ? parseRefs(m[2]) : [];
      if (!parsed.length && /\bSee\b/i.test(line)) { entries.push([line]); continue; }
      if (!parsed.length && !label) continue;
      entries.push([label, ...parsed.map(refStr)]);
      refs += parsed.length;
      for (const p of parsed) {
        if (!p.v) continue;
        const end = Math.min(p.ve || p.v, p.v + 40);
        for (let v = p.v; v <= end; v++) {
          const k = `${p.c}:${v}`;
          const dedupe = `${p.b}|${k}`;
          if (seen.has(dedupe)) continue;
          seen.add(dedupe);
          ((byVerse[p.b] ||= {})[k] ||= []).push(id);
        }
      }
    }
    list.push([subject, entries.reduce((a, e) => a + Math.max(0, e.length - 1), 0)]);
    (shards[letter] ||= {})[id] = { s: subject, e: entries };
    topics++;
  }
  mkdirSync(join(OUT, 'naves', 'v'), { recursive: true });
  writeFileSync(join(OUT, 'naves', 'topics.json'), JSON.stringify({ source: "Nave's Topical Bible (Orville J. Nave, 1896; public domain), structured by Brady Stephenson's bible-data (CC BY 4.0)", topics: list }));
  for (const [letter, obj] of Object.entries(shards)) writeFileSync(join(OUT, 'naves', `${letter === '#' ? '_' : letter}.json`), JSON.stringify(obj));
  for (const [book, map] of Object.entries(byVerse)) writeFileSync(join(OUT, 'naves', 'v', `${book}.json`), JSON.stringify(map));
}

// ---------- index ----------
index.extras = {
  interlinear: { ot: 'Open Scriptures Hebrew Bible (WLC), CC BY 4.0', nt: 'STEPBible TAGNT, CC BY 4.0', versification: 'STEPBible TVTMS, CC BY 4.0' },
  naves: topics ? { topics } : null,
};
writeFileSync(join(OUT, 'index.json'), JSON.stringify(index));
console.log(`interlinear: ${ilWords} words over ${ilVerses} verses (${unmapped} Hebrew verses had no English slot)`);
console.log(`Nave's: ${topics} subjects, ${refs} references`);
