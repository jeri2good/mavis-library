// Unit tests for the server endpoints with upstream fetch mocked.
//   npm run test:functions

import assert from 'node:assert/strict';
import { gutendexBook, gutendexList, openLibrarySearch, makeFixtureEpub } from '../fixtures/fixtures.mjs';

const calls = [];
let routes = [];
globalThis.fetch = async (url, opts = {}) => {
  url = String(url);
  calls.push(url);
  for (const [match, handler] of routes) if (match(url)) return handler(url, opts);
  throw new TypeError(`unmocked fetch ${url}`);
};
const respond = (body, init = {}) => new Response(typeof body === 'string' || body instanceof Uint8Array ? body : JSON.stringify(body), {
  status: init.status || 200,
  headers: init.headers || { 'content-type': 'application/json' },
});
function withUrl(res, url) { Object.defineProperty(res, 'url', { value: url }); return res; }

const catalog = (await import('../../netlify/functions/catalog.mjs')).default;
const openlibrary = (await import('../../netlify/functions/openlibrary.mjs')).default;
const epub = (await import('../../netlify/functions/epub.mjs')).default;
const ctx = (ip = '1.2.3.4') => ({ ip });
const get = (path) => new Request(`https://mavis.test${path}`);

let passed = 0;
async function test(name, fn) {
  calls.length = 0;
  routes = [];
  await fn();
  passed++;
  console.log(`  ✓ ${name}`);
}

await test('catalog list forwards only validated params and forces public-domain EPUBs', async () => {
  routes = [[(u) => u.startsWith('https://gutendex.com/books?'), () => respond(gutendexList([gutendexBook(1342, 'Pride and Prejudice')], { next: true }))]];
  const res = await catalog(get('/api/catalog?search=austen&languages=en&page=2&evil=1&sort=popular'), ctx());
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.results[0].title, 'Pride and Prejudice');
  assert.equal(body.results[0].epub, true);
  assert.equal(body.hasNext, true);
  const u = new URL(calls[0]);
  assert.equal(u.searchParams.get('copyright'), 'false');
  assert.equal(u.searchParams.get('mime_type'), 'application/epub');
  assert.equal(u.searchParams.get('page'), '2');
  assert.equal(u.searchParams.has('evil'), false);
});

await test('catalog rejects malformed parameters without calling upstream', async () => {
  for (const q of ['languages=english', 'page=0', 'page=abc', 'ids=1;drop', 'sort=random', 'id=../etc']) {
    const res = await catalog(get(`/api/catalog?${q}`), ctx());
    assert.equal(res.status, 400, q);
  }
  assert.equal(calls.length, 0);
});

await test('catalog reports upstream failures as a retryable error', async () => {
  routes = [[() => true, () => respond('oops', { status: 503 })]];
  const res = await catalog(get('/api/catalog?search=x'), ctx());
  assert.equal(res.status, 502);
  assert.match((await res.json()).message, /503/);
});

await test('catalog rejects non-GET methods', async () => {
  const res = await catalog(new Request('https://mavis.test/api/catalog', { method: 'POST', body: '{}' }), ctx());
  assert.equal(res.status, 405);
});

await test('open library search shapes results and validates input', async () => {
  routes = [[(u) => u.startsWith('https://openlibrary.org/search.json'), () => respond(openLibrarySearch())]];
  const res = await openlibrary(get('/api/openlibrary?q=dune&page=1'), ctx());
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.equal(body.results[0].title, 'Dune');
  assert.match(body.results[0].cover, /^https:\/\/covers\.openlibrary\.org\/b\/id\/\d+-M\.jpg$/);
  assert.equal((await openlibrary(get('/api/openlibrary?q='), ctx())).status, 400);
  assert.equal((await openlibrary(get('/api/openlibrary?work=../../x'), ctx())).status, 400);
});

await test('epub endpoint refuses non-numeric ids', async () => {
  for (const id of ['abc', '1342.epub', 'https://evil.example/x', '1234567']) {
    const res = await epub(get(`/api/epub?id=${encodeURIComponent(id)}`), ctx());
    assert.equal(res.status, 400, id);
  }
  assert.equal(calls.length, 0);
});

await test('epub endpoint refuses books not marked public domain', async () => {
  routes = [[(u) => u.includes('gutendex.com/books/77'), () => respond({ ...gutendexBook(77, 'Modern Book'), copyright: true })]];
  const res = await epub(get('/api/epub?id=77'), ctx('5.5.5.5'));
  assert.equal(res.status, 451);
  assert.equal(calls.length, 1);
});

await test('epub endpoint streams a real EPUB from gutenberg.org', async () => {
  const bytes = await makeFixtureEpub();
  routes = [
    [(u) => u.includes('gutendex.com/books/1342'), () => respond(gutendexBook(1342, 'Pride and Prejudice'))],
    [(u) => u.startsWith('https://www.gutenberg.org/'), (u) => withUrl(respond(bytes, { headers: { 'content-type': 'application/epub+zip', 'content-length': String(bytes.length) } }), 'https://www.gutenberg.org/cache/epub/1342/pg1342-images-3.epub')],
  ];
  const res = await epub(get('/api/epub?id=1342'), ctx('6.6.6.6'));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/epub+zip');
  const out = new Uint8Array(await res.arrayBuffer());
  assert.equal(out.length, bytes.length);
  assert.equal(out[0], 0x50); assert.equal(out[1], 0x4b); // "PK"
  assert.ok(calls.every((u) => u.startsWith('https://gutendex.com/') || u.startsWith('https://www.gutenberg.org/')));
});

await test('epub endpoint ignores redirects to other hosts and HTML rate-limit pages', async () => {
  routes = [
    [(u) => u.includes('gutendex.com/books/84'), () => respond(gutendexBook(84, 'Frankenstein'))],
    [(u) => u.endsWith('.epub3.images'), (u) => withUrl(respond('x', { headers: { 'content-type': 'application/epub+zip' } }), 'https://evil.example/file.epub')],
    [(u) => u.endsWith('.epub.noimages'), (u) => withUrl(respond('<html>slow down</html>', { headers: { 'content-type': 'text/html' } }), u)],
  ];
  const res = await epub(get('/api/epub?id=84'), ctx('7.7.7.7'));
  assert.equal(res.status, 502);
  assert.match((await res.json()).message, /import the EPUB/);
});

await test('epub endpoint skips oversized editions and falls back to a smaller one', async () => {
  const bytes = await makeFixtureEpub();
  routes = [
    [(u) => u.includes('gutendex.com/books/2701'), () => respond(gutendexBook(2701, 'Moby Dick'))],
    [(u) => u.endsWith('.epub3.images'), (u) => withUrl(respond('x', { headers: { 'content-type': 'application/epub+zip', 'content-length': String(50 * 1024 * 1024) } }), u)],
    [(u) => u.endsWith('.epub.noimages'), (u) => withUrl(respond(bytes, { headers: { 'content-type': 'application/epub+zip' } }), u)],
  ];
  const res = await epub(get('/api/epub?id=2701'), ctx('8.8.8.8'));
  assert.equal(res.status, 200);
  assert.equal((await res.arrayBuffer()).byteLength, bytes.length);
});

await test('epub endpoint rate-limits bursts from one client', async () => {
  routes = [[() => true, () => respond({}, { status: 404 })]];
  let last;
  for (let i = 0; i < 14; i++) last = await epub(get('/api/epub?id=1'), ctx('9.9.9.9'));
  assert.equal(last.status, 429);
});

console.log(`\n${passed} endpoint checks passed.`);
