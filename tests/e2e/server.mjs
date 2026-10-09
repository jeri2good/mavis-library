// End-to-end test server.
//  * Serves a built dist/ folder with the same security headers as netlify.toml.
//  * Runs the real /api function handlers, with their upstream fetches answered
//    from fixtures (the build sandbox cannot reach Gutendex/Gutenberg/Open Library).
//  * With --accounts, runs the real account and sync functions against
//    @netlify/blobs' local Blobs server, so sign-up, sign-in, sync, conflicts,
//    and isolation are exercised end to end.
//
//   node tests/e2e/server.mjs --dist dist --port 4321 [--accounts]

import http from 'node:http';
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, extname, resolve } from 'node:path';
import { gutendexBook, openLibrarySearch, makeFixtureEpub, opdsSearchFeed, opdsBookFeed } from '../fixtures/fixtures.mjs';

const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith('--') ? [a.slice(2), arr[i + 1] && !arr[i + 1].startsWith('--') ? arr[i + 1] : true] : null).filter(Boolean));
const DIST = resolve(args.dist || 'dist');
const PORT = Number(args.port || 4321);
const WITH_ACCOUNTS = !!args.accounts;

// ---------- Fixture upstream ----------
const TITLES = ['Pride and Prejudice', 'Frankenstein; Or, The Modern Prometheus', 'Moby Dick; Or, The Whale', "Alice's Adventures in Wonderland", 'The Adventures of Sherlock Holmes', 'The Great Gatsby', 'Dracula', 'Jane Eyre: An Autobiography', 'The Picture of Dorian Gray', 'A Tale of Two Cities', 'Wuthering Heights', 'The Count of Monte Cristo', 'Emma', 'Treasure Island', 'Great Expectations', 'Crime and Punishment'];
const IDS = [1342, 84, 2701, 11, 1661, 64317, 345, 1260, 174, 98, 768, 1184, 158, 120, 1400, 2554];
const CATALOG = [];
for (let i = 0; i < 70; i++) {
  const id = IDS[i] || 9000 + i;
  CATALOG.push(gutendexBook(id, TITLES[i] || `Fixture Volume ${i + 1}`, {
    author: i % 3 ? 'Austen, Jane' : 'Shelley, Mary Wollstonecraft', downloads: 90000 - i * 1000, languages: i % 10 === 9 ? ['fr'] : ['en'],
    subjects: i % 2 ? ['Adventure stories', 'Sea stories'] : ['Love stories', 'England -- Fiction', 'Courtship -- Fiction'],
  }));
}
let failOnce = new Set();
let epubBytes = null;

// One second of silent MP3 (16 kHz mono) for cloud-voice tests.
const SILENT_MP3 = Buffer.from('//NIxAAAAANIAAAAAExBTUVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVMQU1FMy4xMDBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//NIxHwAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV', 'base64');

const TINY_PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgYGAAAAAEAAH2FzhVAAAAAElFTkSuQmCC';

async function fixtureFetch(url, opts = {}) {
  url = String(url);
  const u = new URL(url);
  const json = (b, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { 'content-type': 'application/json' } });
  const atom = (b, st = 200) => new Response(b, { status: st, headers: { 'content-type': 'application/atom+xml; charset=UTF-8' } });
  if (u.host === 'www.gutenberg.org' && /^\/ebooks\/\d+\.opds$/.test(u.pathname)) {
    const id = Number(u.pathname.match(/(\d+)\.opds$/)[1]);
    const b = CATALOG.find((x) => x.id === id);
    return b ? atom(opdsBookFeed(b)) : new Response('Not found', { status: 404 });
  }
  if (u.host === 'www.gutenberg.org' && u.pathname.startsWith('/ebooks/search.opds')) {
    const query = (u.searchParams.get('query') || '').toLowerCase();
    const words = query.split(/\s+/).filter((w) => w && !/^(s|l|bs)\./.test(w));
    const lang = (query.match(/(?:^|\s)l\.([a-z]{2})/) || [])[1];
    const topic = /(?:^|\s)s\./.test(query);
    const search = words.join(' ');
    if (search.includes('failonce') && !failOnce.has(search)) { failOnce.add(search); return atom('busy', 503); }
    if (search.includes('alwaysfail')) return atom('busy', 503);
    let list = CATALOG;
    if (search && !search.includes('failonce')) list = list.filter((b) => `${b.title} ${b.authors[0].name}`.toLowerCase().includes(words[0]));
    if (search.includes('zzzz')) list = [];
    if (lang) list = list.filter((b) => b.languages.includes(lang));
    if (topic) list = list.slice(5, 17);
    const start = Number(u.searchParams.get('start_index') || 1);
    const slice = list.slice(start - 1, start - 1 + 25);
    return atom(opdsSearchFeed(slice, { next: start - 1 + 25 < list.length, query }));
  }
  if (u.host === 'www.gutenberg.org') {
    epubBytes ||= await makeFixtureEpub();
    const r = new Response(epubBytes, { status: 200, headers: { 'content-type': 'application/epub+zip', 'content-length': String(epubBytes.length) } });
    Object.defineProperty(r, 'url', { value: url });
    return r;
  }
  if (u.host === 'bible.helloao.org') {
    // Shaped like the HelloAO Free Use Bible API (checked against the live service).
    const m = /^\/api\/(?:c\/([^/]+)|([^/]+))\/([1-3]?[A-Z]{2,3})\/(\d+)\.json$/.exec(u.pathname);
    if (!m) return json({ error: 'not found' }, 404);
    const [, commentary, id, book, ch] = m;
    if (commentary) {
      return json({ commentary: { id: commentary }, chapter: { number: Number(ch), introduction: 'Fixture introduction.', content: [
        { type: 'verse', number: 1, content: [`${commentary} fixture comment on ${book} ${ch}:1-21. Nicodemus came by night.\n\nA second paragraph.`] },
        { type: 'verse', number: 22, content: [`${commentary} fixture comment on ${book} ${ch}:22-36.`] },
      ] } });
    }
    const n = id === 'BSB' && book === 'JHN' && ch === '3' ? 36 : 20;
    const content = [{ type: 'heading', content: ['Jesus and Nicodemus'] }];
    for (let v = 1; v <= n; v++) {
      if (book === 'JHN' && ch === '3' && v === 16) content.push({ type: 'verse', number: 16, content: ['For God so loved the world that He gave His one and only', { noteId: 1 }, 'Son, that everyone who believes in Him shall not perish but have eternal life.'] });
      else content.push({ type: 'verse', number: v, content: [`${id} fixture text of ${book} ${ch}:${v}.`] });
      if (v === 21) content.push({ type: 'heading', content: ['John the Baptist’s Testimony'] });
    }
    return json({ translation: { id }, chapter: { number: Number(ch), content, footnotes: [{ noteId: 1, caller: '+', text: 'Or his only begotten', reference: { chapter: 3, verse: 16 } }] } });
  }
  if (u.host === 'openlibrary.org') {
    if (u.pathname.startsWith('/works/')) return json({ title: 'Dune', description: { value: 'A desert planet, a noble family, and a spice that bends the future.' }, subjects: ['Science fiction', 'Ecology'] });
    return json(openLibrarySearch());
  }
  if (u.host === 'covers.openlibrary.org') return new Response('', { status: u.pathname.includes('/isbn/') ? 200 : 404 });
  if (u.host === 'www.googleapis.com') return json({ items: [{ volumeInfo: { title: 'Pride and Prejudice', imageLinks: { thumbnail: 'http://books.google.com/books/content?id=fixture&printsec=frontcover&img=1&zoom=1&edge=curl' } } }] });
  if (u.host === 'api.fish.audio' && u.pathname === '/model' && (opts.method || 'GET') === 'GET') {
    const v = (id, title, tags) => ({ _id: id, type: 'tts', title, tags, languages: ['en'], state: 'trained', samples: [{ audio: `https://platform.r2.fish.audio/samples/${id}.mp3` }] });
    if (u.searchParams.get('self') === 'true') return json({ total: globalThis.__fishMine?.length || 0, items: globalThis.__fishMine || [] });
    const tags = u.searchParams.getAll('tag');
    const all = [
      v('a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1', 'Warm Narrator', ['male', 'middle-aged', 'narration', 'warm']),
      v('b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2', 'Ferry Captain', ['male', 'old', 'narration', 'deep']),
      v('c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3', 'Calm Reflective Voice', ['female', 'middle-aged', 'narration', 'calm']),
      v('d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4', 'Bright Young Reader', ['female', 'young', 'narration', 'bright']),
    ];
    const items = all.filter((x) => tags.every((t) => x.tags.includes(t)));
    return json({ total: items.length, items });
  }
  if (u.host === 'api.fish.audio' && u.pathname === '/model' && opts.method === 'POST') {
    const fd = opts.body;
    const file = fd.get('voices');
    globalThis.__fishCreate = { type: fd.get('type'), visibility: fd.get('visibility'), title: fd.get('title'), trainMode: fd.get('train_mode'), bytes: file?.size || 0 };
    const model = { _id: 'e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5', type: 'tts', title: fd.get('title'), state: 'trained', visibility: 'private', tags: [], samples: [] };
    globalThis.__fishMine = [model];
    return json(model, 201);
  }
  if (u.host === 'api.fish.audio' && u.pathname.startsWith('/model/') && opts.method === 'DELETE') {
    globalThis.__fishMine = (globalThis.__fishMine || []).filter((m) => !u.pathname.endsWith(m._id));
    return new Response(null, { status: 204 });
  }
  if (u.host === 'api.fish.audio' || u.host === 'texttospeech.googleapis.com') {
    globalThis.__ttsCalls = (globalThis.__ttsCalls || 0) + 1;
    if (u.host === 'api.fish.audio') { try { (globalThis.__fishVoices ||= []).push(JSON.parse(opts.body).reference_id || null); } catch { /* ignore */ } }
    return new Response(SILENT_MP3, { status: 200, headers: { 'content-type': 'audio/mpeg' } });
  }
  if (u.host === 'api.anthropic.com') {
    const req = JSON.parse(opts.body);
    const q = req.messages[req.messages.length - 1].content.toLowerCase();
    globalThis.__lastAssistant = req;
    if (q.includes('read')) return json({ content: [{ type: 'text', text: 'Starting read-aloud for you.' }, { type: 'tool_use', id: 't1', name: 'read_aloud', input: { from: 'here' } }] });
    return json({ content: [{ type: 'text', text: `Fixture answer about ${req.system.includes('Holy Bible') ? 'the Bible' : 'the book'}.` }] });
  }
  if (u.host === 'api.openai.com') {
    globalThis.__openaiCalls = (globalThis.__openaiCalls || 0) + 1;
    // Background picture: start → id; poll → finished image.
    if (u.pathname === '/v1/responses' && opts.method === 'POST') {
      globalThis.__lastImagePrompt = JSON.parse(opts.body).input;
      return json({ id: 'resp_fixture0123456789', status: 'queued' });
    }
    if (u.pathname.startsWith('/v1/responses/')) {
      return json({ id: 'resp_fixture0123456789', status: 'completed', output: [{ type: 'image_generation_call', status: 'completed', result: TINY_PNG_B64, revised_prompt: 'A fixture illustration.' }] });
    }
    if (u.pathname === '/v1/chat/completions') {
      const req = JSON.parse(opts.body);
      const sys = req.messages[0].content;
      const q = req.messages[req.messages.length - 1].content.toLowerCase();
      const say = (content, extra = {}) => json({ choices: [{ message: { content, ...extra }, finish_reason: 'stop' }] });
      if (/summarize one chapter/i.test(sys)) { globalThis.__summaries = (globalThis.__summaries || 0) + 1; return say('Fixture chapter summary: the keeper climbs the steps and finds a letter.'); }
      if (/story so far/i.test(sys)) return say('So far, the lantern keeper has climbed the ninety-one steps and found an old letter in the fog.');
      if (/attribute lines of dialogue/i.test(sys)) {
        const n = (q.match(/^#\d+ /gm) || []).length;
        const lines = Array.from({ length: n }, (_, i) => ({ i, speaker: i === 0 ? 'The Ferryman' : 'The Keeper' }));
        return say(JSON.stringify({ speakers: { 'The Ferryman': { gender: 'male', age: 'old' }, 'The Keeper': { gender: 'female', age: 'adult' } }, lines }));
      }
      if (/character list/i.test(sys)) return say(JSON.stringify({ characters: [
        { name: 'The Keeper', aka: ['Mara'], role: 'Lighthouse keeper', description: 'Tends the lantern each night.', firstSeen: 'The Ninety-One Steps', importance: 3, relations: [{ to: 'The Ferryman', relation: 'old friend' }] },
        { name: 'The Ferryman', aka: [], role: 'Brings supplies', description: 'Crosses the harbor at dawn.', firstSeen: 'Fog Over the Harbor', importance: 2, relations: [{ to: 'The Keeper', relation: 'old friend' }] },
      ] }));
      globalThis.__lastAssistant = { system: sys, messages: req.messages };
      if (req.tools && q.includes('read')) return say('Starting read-aloud for you.', { tool_calls: [{ id: 't1', type: 'function', function: { name: 'read_aloud', arguments: JSON.stringify({ from: 'here' }) } }] });
      return say(`Fixture answer about ${sys.includes('Holy Bible') ? 'the Bible' : 'the book'}.`);
    }
    return json({ error: { message: `fixture: no route ${u.pathname}` } }, 404);
  }
  if (u.host === 'api.edenai.run') {
    const req = JSON.parse(opts.body);
    const answers = Object.fromEntries(Object.keys(req.questions).map((k, i) => [k, { type: 'score', score: (i % 5), confidence: 0.8 }]));
    return json({ model: 'typesafe/jev-fixture', answers });
  }
  throw new TypeError(`fixture fetch: no route for ${url}`);
}
globalThis.__mavisRealFetch = globalThis.fetch; // Blobs client talks to the real local server
globalThis.fetch = fixtureFetch;

const handlers = [];
for (const f of readdirSync(resolve('netlify/functions')).filter((x) => x.endsWith('.mjs'))) {
  const mod = await import(`../../netlify/functions/${f}`);
  const names = [];
  const re = new RegExp(`^${mod.config.path.replace(/:([a-z]+)/gi, (_, n) => { names.push(n); return '([^/]+)'; })}$`);
  handlers.push({ re, names, fn: mod.default });
}
function route(pathname) {
  for (const h of handlers) {
    const m = h.re.exec(pathname);
    if (m) return { fn: h.fn, params: Object.fromEntries(h.names.map((n, i) => [n, decodeURIComponent(m[i + 1])])) };
  }
  return null;
}

// ---------- Security headers (from netlify.toml) ----------
const toml = readFileSync(resolve('netlify.toml'), 'utf8');
let CSP = /Content-Security-Policy = "([^"]+)"/.exec(toml)[1];
CSP = CSP.replace(' upgrade-insecure-requests', '').replace(/;\s*$/, ''); // plain-http test server
const HEADERS = {
  'content-security-policy': CSP,
  'referrer-policy': 'strict-origin-when-cross-origin',
  'x-content-type-options': 'nosniff',
  'permissions-policy': 'microphone=(self), camera=(), geolocation=(), payment=()',
};
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };

// ---------- Accounts: a real local Netlify Blobs server ----------
// With --accounts, account and sync functions run against @netlify/blobs'
// own local server.
let blobsServer = null;
export const syncLog = [];
async function startBlobs() {
  const { BlobsServer } = await import('@netlify/blobs/server');
  const { mkdtempSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  blobsServer = new BlobsServer({ directory: mkdtempSync(join(tmpdir(), 'mavis-e2e-blobs-')), token: 'local-token', port: 0 });
  const { port } = await blobsServer.start();
  process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
  process.env.MAVIS_BLOBS_TOKEN = 'local-token';
  process.env.AUTH_SECRET ||= 'e2e-only-secret-'.padEnd(48, 'x');
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  let body = '';
  for await (const chunk of req) body += chunk;
  try {
    if (url.pathname === '/__test/reset-failures') { failOnce = new Set(); res.end('ok'); return; }
    if (url.pathname === '/__test/fish') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ voices: globalThis.__fishVoices || [], create: globalThis.__fishCreate || null })); return; }
    if (url.pathname === '/__test/openai') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ calls: globalThis.__openaiCalls || 0, summaries: globalThis.__summaries || 0, imagePrompt: globalThis.__lastImagePrompt || '' })); return; }
    if (url.pathname === '/__test/tts-calls') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(String(globalThis.__ttsCalls || 0)); return; }
    if (url.pathname === '/__test/sync-log') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(syncLog)); return; }
    const h = route(url.pathname);
    if (h) {
      if (url.pathname === '/api/sync') syncLog.push({ method: req.method, bytes: body.length });
      const init = { method: req.method, headers: Object.fromEntries(Object.entries(req.headers).filter(([k]) => typeof req.headers[k] === 'string')) };
      if (!['GET', 'HEAD'].includes(req.method)) init.body = body;
      const r = await h.fn(new Request(url, init), { ip: req.socket.remoteAddress + Math.random(), params: h.params });
      const headers = Object.fromEntries(r.headers);
      res.writeHead(r.status, headers);
      if (r.body) { for await (const c of r.body) res.write(c); }
      res.end();
      return;
    }
    let file = join(DIST, decodeURIComponent(url.pathname));
    if (!file.startsWith(DIST)) { res.writeHead(400); res.end(); return; }
    if (existsSync(file) && statSync(file).isDirectory()) file = join(file, 'index.html');
    if (!existsSync(file)) { res.writeHead(404, HEADERS); res.end('not found'); return; }
    const type = TYPES[extname(file)] || 'application/octet-stream';
    res.writeHead(200, { ...HEADERS, 'content-type': type, 'cache-control': url.pathname === '/sw.js' ? 'no-cache' : 'no-cache' });
    res.end(readFileSync(file));
  } catch (err) {
    console.error(err);
    res.writeHead(500); res.end(String(err));
  }
});
if (WITH_ACCOUNTS) await startBlobs();
else delete process.env.AUTH_SECRET;
server.listen(PORT, () => console.log(`e2e server on http://localhost:${PORT} serving ${DIST}${WITH_ACCOUNTS ? ' with accounts (local Blobs)' : ''}`));
process.on('SIGTERM', async () => { await blobsServer?.stop(); process.exit(0); });
