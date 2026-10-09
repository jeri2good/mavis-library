// Builds the compact Bible data Mavis ships in public/bible/.
//
//   node scripts/build-bible.mjs <kjvDataDir> <webJsonDir> <lexiconDataDir> <crossRefsTxt>
//
// Sources (all redistributable):
//  * KJV with Strong's numbers — CrossWire Bible Society KJV module, via
//    @metaxia/scriptures-source-crosswire-kjv (data/crosswire-KJV). Public domain
//    (Crown copyright applies in the United Kingdom).
//  * World English Bible — public domain, via the world-english-bible package.
//  * Strong's lexicon — STEPBible TBESH/TBESG (Tyndale House, Cambridge), CC BY 4.0.
//  * Cross-references — OpenBible.info, CC BY.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { BOOKS } from './bible-books.mjs';

const [kjvDir, webDir, lexDir, xrefFile] = process.argv.slice(2).map((p) => resolve(p));
const OUT = resolve('public/bible');
for (const d of ['', 'kjv', 'web', 'lex', 'xref']) mkdirSync(join(OUT, d), { recursive: true });

const NT_START = BOOKS.findIndex((b) => b[0] === 'Matt');
const index = { version: 1, books: [], translations: {} };
let kjvVerses = 0, webVerses = 0, strongsTagged = 0;

const normStrongs = (s) => {
  const m = /^([HG])0*(\d+)/.exec(s);
  return m ? `${m[1]}${m[2]}` : null;
};

for (const [bi, [id, name, abbr, webFile]] of BOOKS.entries()) {
  // ---- KJV with Strong's ----
  const chapters = [];
  for (let c = 1; ; c++) {
    const cdir = join(kjvDir, id, String(c));
    if (!existsSync(cdir)) break;
    const verses = [];
    for (let v = 1; ; v++) {
      const f = join(cdir, `${v}.json`);
      if (!existsSync(f)) break;
      const d = JSON.parse(readFileSync(f, 'utf8'));
      const words = d.words?.length ? d.words : d.text.split(/\s+/).map((t) => ({ text: t }));
      const parts = [];
      let group = [], key = '';
      const flush = () => {
        if (!group.length) return;
        parts.push(`${group.join(' ')}{${key}}`); // untagged runs end in {}
        if (key) strongsTagged++;
        group = [];
      };
      for (const w of words) {
        const k = (w.strongs || []).map(normStrongs).filter(Boolean).join(',');
        const text = String(w.text).replace(/[{}]/g, '');
        if (k !== key) { flush(); key = k; }
        group.push(text);
      }
      flush();
      verses.push(parts.join(' '));
      kjvVerses++;
    }
    chapters.push(verses);
  }
  if (!chapters.length) throw new Error(`No KJV data for ${id}`);
  writeFileSync(join(OUT, 'kjv', `${id}.json`), JSON.stringify({ id, chapters }));

  // ---- WEB ----
  const web = JSON.parse(readFileSync(join(webDir, `${webFile}.json`), 'utf8'));
  const wch = [];
  for (const item of web) {
    if (!item.chapterNumber || !item.verseNumber || typeof item.value !== 'string') continue;
    const c = item.chapterNumber - 1, v = item.verseNumber - 1;
    wch[c] ||= [];
    wch[c][v] = ((wch[c][v] || '') + ' ' + item.value).replace(/\s+/g, ' ').trim();
  }
  for (let c = 0; c < wch.length; c++) {
    wch[c] = wch[c] || [];
    for (let v = 0; v < wch[c].length; v++) { wch[c][v] = wch[c][v] || ''; webVerses++; }
  }
  writeFileSync(join(OUT, 'web', `${id}.json`), JSON.stringify({ id, chapters: wch }));

  index.books.push({
    id, name, abbr, testament: bi < NT_START ? 'OT' : 'NT',
    verses: chapters.map((ch) => ch.length),
  });
}

// ---- Lexicon ----
function cleanDef(html) {
  return String(html || '')
    .replace(/<ref='[^']*'>([^<]*)<\/ref>/g, '$1')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/__/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s+/g, '\n')
    .trim();
}
let lexCount = 0;
for (const [file, prefix] of [['stepbible-tbesh.json', 'H'], ['stepbible-tbesg.json', 'G']]) {
  const src = JSON.parse(readFileSync(join(lexDir, file), 'utf8'));
  const out = {};
  for (const e of Object.values(src)) {
    const k = normStrongs(e.strongsExtended || '');
    if (!k || out[k] || !/^[HG]\d+$/.test(k) || k[0] !== prefix) continue;
    let def = cleanDef(e.definition);
    if (def.length > 900) def = def.slice(0, def.lastIndexOf(' ', 880)) + ' …';
    out[k] = [e.lemma || '', e.transliteration || '', e.gloss || '', def, e.morphology || ''];
    lexCount++;
  }
  writeFileSync(join(OUT, 'lex', `${prefix}.json`), JSON.stringify(out));
}

// ---- Cross-references (top-voted per verse) ----
const byBook = Object.fromEntries(BOOKS.map(([id]) => [id, {}]));
let xrefCount = 0;
for (const line of readFileSync(xrefFile, 'utf8').split('\n').slice(1)) {
  const [from, to, votesStr] = line.split('\t');
  const votes = Number(votesStr);
  if (!from || !to || !(votes >= 3)) continue;
  const [b, c, v] = from.split('.');
  if (!byBook[b]) continue;
  const key = `${c}:${v}`;
  (byBook[b][key] ||= []).push([to, votes]);
}
for (const [id, map] of Object.entries(byBook)) {
  for (const k of Object.keys(map)) {
    map[k] = map[k].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([to]) => to);
    xrefCount += map[k].length;
  }
  writeFileSync(join(OUT, 'xref', `${id}.json`), JSON.stringify(map));
}

index.translations = {
  kjv: { name: 'King James Version', short: 'KJV', year: 1769, strongs: true, license: 'Public domain (Crown copyright in the United Kingdom). Text and Strong’s tagging from the CrossWire Bible Society KJV module.' },
  web: { name: 'World English Bible', short: 'WEB', year: 2020, strongs: false, license: 'Public domain. eBible.org.' },
};
index.credits = [
  'Strong’s lexicon: STEPBible.org (Tyndale House, Cambridge) TBESH and TBESG, CC BY 4.0.',
  'Cross-references: OpenBible.info, CC BY.',
];
writeFileSync(join(OUT, 'index.json'), JSON.stringify(index));
console.log({ books: index.books.length, kjvVerses, webVerses, strongsTagged, lexCount, xrefCount });
