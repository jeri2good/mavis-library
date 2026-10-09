// End-to-end test server.
//  * Serves a built dist/ folder with the same security headers as netlify.toml.
//  * Runs the real /api function handlers, with their upstream fetches answered
//    from fixtures (the build sandbox cannot reach Gutendex/Gutenberg/Open Library).
//  * Optionally emulates the slice of Supabase Auth (GoTrue) and PostgREST that
//    Mavis uses, backed by PGlite running the real migration with row-level
//    security, so sign-up, sign-in, sync, and isolation are exercised for real.
//
//   node tests/e2e/server.mjs --dist dist --port 4321 [--supabase]

import http from 'node:http';
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, extname, resolve } from 'node:path';
import { randomUUID, createHmac } from 'node:crypto';
import { gutendexBook, gutendexList, openLibrarySearch, makeFixtureEpub } from '../fixtures/fixtures.mjs';

const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith('--') ? [a.slice(2), arr[i + 1] && !arr[i + 1].startsWith('--') ? arr[i + 1] : true] : null).filter(Boolean));
const DIST = resolve(args.dist || 'dist');
const PORT = Number(args.port || 4321);
const WITH_SUPABASE = !!args.supabase;

// ---------- Fixture upstream ----------
const TITLES = ['Pride and Prejudice', 'Frankenstein; Or, The Modern Prometheus', 'Moby Dick; Or, The Whale', "Alice's Adventures in Wonderland", 'The Adventures of Sherlock Holmes', 'The Great Gatsby', 'Dracula', 'Jane Eyre: An Autobiography', 'The Picture of Dorian Gray', 'A Tale of Two Cities', 'Wuthering Heights', 'The Count of Monte Cristo', 'Emma', 'Treasure Island', 'Metamorphosis', 'Crime and Punishment'];
const IDS = [1342, 84, 2701, 11, 1661, 64317, 345, 1260, 174, 98, 768, 1184, 158, 120, 5200, 2554];
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

async function fixtureFetch(url, opts = {}) {
  url = String(url);
  const u = new URL(url);
  const json = (b, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { 'content-type': 'application/json' } });
  if (u.host === 'gutendex.com') {
    if (u.pathname.startsWith('/books/')) {
      const id = Number(u.pathname.split('/')[2]);
      const b = CATALOG.find((x) => x.id === id);
      return b ? json(b) : json({ detail: 'Not found.' }, 404);
    }
    const search = (u.searchParams.get('search') || '').toLowerCase();
    if (search.includes('failonce') && !failOnce.has(search)) { failOnce.add(search); return json({ detail: 'busy' }, 503); }
    if (search.includes('alwaysfail')) return json({ detail: 'busy' }, 503);
    let list = CATALOG;
    if (u.searchParams.get('ids')) { const ids = u.searchParams.get('ids').split(',').map(Number); list = list.filter((b) => ids.includes(b.id)); }
    if (search && !search.includes('failonce')) list = list.filter((b) => `${b.title} ${b.authors[0].name}`.toLowerCase().includes(search.split(' ')[0]));
    if (search.includes('zzzz')) list = [];
    if (u.searchParams.get('languages')) list = list.filter((b) => u.searchParams.get('languages').split(',').some((l) => b.languages.includes(l)));
    if (u.searchParams.get('topic')) list = list.slice(5, 17);
    const page = Number(u.searchParams.get('page') || 1);
    const slice = list.slice((page - 1) * 32, page * 32);
    return json(gutendexList(slice, { next: page * 32 < list.length, prev: page > 1, count: list.length }));
  }
  if (u.host === 'www.gutenberg.org') {
    epubBytes ||= await makeFixtureEpub();
    const r = new Response(epubBytes, { status: 200, headers: { 'content-type': 'application/epub+zip', 'content-length': String(epubBytes.length) } });
    Object.defineProperty(r, 'url', { value: url });
    return r;
  }
  if (u.host === 'openlibrary.org') {
    if (u.pathname.startsWith('/works/')) return json({ title: 'Dune', description: { value: 'A desert planet, a noble family, and a spice that bends the future.' }, subjects: ['Science fiction', 'Ecology'] });
    return json(openLibrarySearch());
  }
  if (u.host === 'covers.openlibrary.org') return new Response('', { status: u.pathname.includes('/isbn/') ? 200 : 404 });
  if (u.host === 'www.googleapis.com') return json({ items: [{ volumeInfo: { title: 'Pride and Prejudice', imageLinks: { thumbnail: 'http://books.google.com/books/content?id=fixture&printsec=frontcover&img=1&zoom=1&edge=curl' } } }] });
  if (u.host === 'api.fish.audio' || u.host === 'texttospeech.googleapis.com') {
    globalThis.__ttsCalls = (globalThis.__ttsCalls || 0) + 1;
    return new Response(SILENT_MP3, { status: 200, headers: { 'content-type': 'audio/mpeg' } });
  }
  if (u.host === 'api.anthropic.com') {
    const req = JSON.parse(opts.body);
    const q = req.messages[req.messages.length - 1].content.toLowerCase();
    globalThis.__lastAssistant = req;
    if (q.includes('read')) return json({ content: [{ type: 'text', text: 'Starting read-aloud for you.' }, { type: 'tool_use', id: 't1', name: 'read_aloud', input: { from: 'here' } }] });
    return json({ content: [{ type: 'text', text: `Fixture answer about ${req.system.includes('Holy Bible') ? 'the Bible' : 'the book'}.` }] });
  }
  if (u.host === 'api.edenai.run') {
    const req = JSON.parse(opts.body);
    const answers = Object.fromEntries(Object.keys(req.questions).map((k, i) => [k, { type: 'score', score: (i % 5), confidence: 0.8 }]));
    return json({ model: 'typesafe/jev-fixture', answers });
  }
  throw new TypeError(`fixture fetch: no route for ${url}`);
}
globalThis.fetch = fixtureFetch;

const handlers = {};
for (const f of readdirSync(resolve('netlify/functions')).filter((x) => x.endsWith('.mjs'))) {
  const mod = await import(`../../netlify/functions/${f}`);
  handlers[mod.config.path] = mod.default;
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

// ---------- Supabase emulation (Auth + PostgREST subset) ----------
let pg = null;
let queue = Promise.resolve();
const users = new Map(); // email → { id, email, password }
const refresh = new Map(); // refresh token → user id
const SECRET = 'test-only-secret';
const b64u = (s) => Buffer.from(s).toString('base64url');
function jwt(user) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = b64u(JSON.stringify({ sub: user.id, email: user.email, role: 'authenticated', aud: 'authenticated', exp: now + 3600, iat: now, session_id: randomUUID() }));
  const sig = createHmac('sha256', SECRET).update(`${head}.${body}`).digest('base64url');
  return `${head}.${body}.${sig}`;
}
function verify(token) {
  try {
    const [h, b, s] = token.split('.');
    if (createHmac('sha256', SECRET).update(`${h}.${b}`).digest('base64url') !== s) return null;
    const p = JSON.parse(Buffer.from(b, 'base64url').toString());
    return p.exp > Date.now() / 1000 ? p : null;
  } catch { return null; }
}
const userJson = (u) => ({ id: u.id, aud: 'authenticated', role: 'authenticated', email: u.email, email_confirmed_at: new Date().toISOString(), app_metadata: { provider: 'email' }, user_metadata: {}, identities: [{ id: u.id, provider: 'email' }], created_at: new Date().toISOString() });
function session(u) {
  const rt = randomUUID();
  refresh.set(rt, u.id);
  return { access_token: jwt(u), token_type: 'bearer', expires_in: 3600, expires_at: Math.floor(Date.now() / 1000) + 3600, refresh_token: rt, user: userJson(u) };
}

async function initPg() {
  const { PGlite } = await import('@electric-sql/pglite');
  pg = new PGlite();
  await pg.exec(`create schema auth; create table auth.users (id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
    create role anon nologin; create role authenticated nologin;
    grant usage on schema public to anon, authenticated; grant usage on schema auth to anon, authenticated;
    grant execute on function auth.uid() to anon, authenticated;`);
  await pg.exec(readFileSync(resolve('supabase/migrations/0001_mavis_library.sql'), 'utf8'));
  await pg.exec(readFileSync(resolve('supabase/migrations/0002_bible_and_quotes.sql'), 'utf8'));
}

function asUser(sub, fn) {
  const run = queue.then(async () => {
    await pg.exec(`reset role; select set_config('request.jwt.claim.sub', '${sub.replace(/[^0-9a-f-]/g, '')}', false); set role authenticated;`);
    try { return await fn(); } finally { await pg.exec('reset role;'); }
  });
  queue = run.catch(() => {});
  return run;
}

const TABLE_COLS = {
  shelf_items: ['user_id', 'book_key', 'source', 'source_id', 'title', 'authors', 'cover_url', 'languages', 'subjects', 'format', 'file_name', 'file_size', 'status', 'added_at', 'last_opened_at', 'deleted', 'client_updated_at'],
  reading_progress: ['user_id', 'book_key', 'cfi', 'percent', 'chapter', 'deleted', 'client_updated_at'],
  annotations: ['id', 'user_id', 'book_key', 'kind', 'cfi', 'text_excerpt', 'color', 'note', 'chapter', 'percent', 'created_at', 'deleted', 'client_updated_at'],
};
const ARRAYS = new Set(['authors', 'languages', 'subjects']);
export const supabaseLog = [];

async function handleSupabase(req, res, url, body) {
  const send = (status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
  const path = url.pathname.replace(/^\/sb/, '');
  const bearer = (req.headers.authorization || '').replace(/^Bearer /, '');
  if (path === '/auth/v1/signup' && req.method === 'POST') {
    const { email, password } = JSON.parse(body || '{}');
    if (!email || !password || password.length < 8) return send(422, { code: 422, error_code: 'weak_password', msg: 'Password should be at least 8 characters.' });
    if (users.has(email)) return send(200, { ...userJson(users.get(email)), identities: [] });
    const u = { id: randomUUID(), email, password };
    users.set(email, u);
    await queue.then(() => pg.query('insert into auth.users values ($1)', [u.id]));
    if (email.includes('+confirm')) return send(200, userJson(u)); // confirmation required: no session
    return send(200, session(u));
  }
  if (path === '/auth/v1/token' && req.method === 'POST') {
    const grant = url.searchParams.get('grant_type');
    const b = JSON.parse(body || '{}');
    if (grant === 'password') {
      const u = users.get(b.email);
      if (!u || u.password !== b.password) return send(400, { code: 400, error_code: 'invalid_credentials', msg: 'Invalid login credentials' });
      return send(200, session(u));
    }
    if (grant === 'refresh_token') {
      const id = refresh.get(b.refresh_token);
      const u = [...users.values()].find((x) => x.id === id);
      if (!u) return send(400, { code: 400, error_code: 'refresh_token_not_found', msg: 'Invalid Refresh Token' });
      return send(200, session(u));
    }
  }
  if (path === '/auth/v1/logout') return send(204);
  if (path === '/auth/v1/user') {
    const p = verify(bearer);
    const u = p && [...users.values()].find((x) => x.id === p.sub);
    return u ? send(200, userJson(u)) : send(401, { code: 401, msg: 'invalid JWT' });
  }
  if (path === '/auth/v1/recover') return send(200, {});

  const m = /^\/rest\/v1\/(shelf_items|reading_progress|annotations)$/.exec(path);
  if (m) {
    const table = m[1];
    const claims = verify(bearer);
    if (!claims) return send(401, { code: 'PGRST301', message: 'JWT invalid' });
    supabaseLog.push({ method: req.method, table, user: claims.sub });
    try {
      if (req.method === 'POST') {
        const rows = [].concat(JSON.parse(body || '[]'));
        const cols = TABLE_COLS[table];
        const conflict = (url.searchParams.get('on_conflict') || (table === 'annotations' ? 'id' : 'user_id,book_key')).split(',').filter((c) => cols.includes(c));
        await asUser(claims.sub, async () => {
          for (const r of rows) {
            const vals = cols.map((c) => (r[c] === undefined ? null : r[c]));
            const ph = cols.map((c, i) => (ARRAYS.has(c) ? `coalesce($${i + 1}::text[], '{}')` : `$${i + 1}`)).join(',');
            const upd = cols.filter((c) => !conflict.includes(c)).map((c) => `${c} = excluded.${c}`).join(', ');
            await pg.query(`insert into public.${table} (${cols.join(',')}) values (${ph}) on conflict (${conflict.join(',')}) do update set ${upd}`, vals);
          }
        });
        return send(201);
      }
      if (req.method === 'GET') {
        const gte = (url.searchParams.get('updated_at') || '').replace(/^gte\./, '');
        const limit = Math.min(1000, Number(url.searchParams.get('limit') || 1000));
        const r = await asUser(claims.sub, () => pg.query(`select to_jsonb(t) as j from public.${table} t where updated_at >= $1::timestamptz order by updated_at asc limit ${limit}`, [gte || '1970-01-01']));
        return send(200, r.rows.map((x) => x.j));
      }
    } catch (err) {
      return send(403, { code: '42501', message: err.message });
    }
  }
  return send(404, { message: `emulator: no route ${req.method} ${path}` });
}

// ---------- HTTP ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  let body = '';
  for await (const chunk of req) body += chunk;
  try {
    if (url.pathname === '/__test/reset-failures') { failOnce = new Set(); res.end('ok'); return; }
    if (url.pathname === '/__test/tts-calls') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(String(globalThis.__ttsCalls || 0)); return; }
    if (url.pathname === '/__test/supabase-log') { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(supabaseLog)); return; }
    if (url.pathname.startsWith('/sb/')) {
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
      if (!pg) await initPg();
      await handleSupabase(req, res, url, body);
      return;
    }
    const h = handlers[url.pathname];
    if (h) {
      const init = { method: req.method, headers: Object.fromEntries(Object.entries(req.headers).filter(([k]) => typeof req.headers[k] === 'string')) };
      if (!['GET', 'HEAD'].includes(req.method)) init.body = body;
      const r = await h(new Request(url, init), { ip: req.socket.remoteAddress + Math.random() });
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
if (WITH_SUPABASE) await initPg();
server.listen(PORT, () => console.log(`e2e server on http://localhost:${PORT} serving ${DIST}${WITH_SUPABASE ? ' with Supabase emulation' : ''}`));
