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
const covers = (await import('../../netlify/functions/covers.mjs')).default;
const tts = (await import('../../netlify/functions/tts.mjs')).default;
const assistant = (await import('../../netlify/functions/assistant.mjs')).default;
const rank = (await import('../../netlify/functions/rank.mjs')).default;
const featuresFn = (await import('../../netlify/functions/features.mjs')).default;
const ctx = (ip = '1.2.3.4') => ({ ip });
const post = (path, body, headers = {}) => new Request(`https://mavis.test${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });
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

await test('covers: Open Library search first, Google Books fallback, title must match', async () => {
  routes = [
    [(u) => u.startsWith('https://openlibrary.org/search.json'), () => respond({ docs: [{ title: 'Some Other Book', cover_i: 1 }, { title: 'Frankenstein', cover_i: 42, edition_count: 300 }] })],
  ];
  let res = await covers(get('/api/covers?title=Frankenstein%3B%20Or%2C%20The%20Modern%20Prometheus&author=Mary%20Shelley'), ctx('c1'));
  let body = await res.json();
  assert.equal(body.cover, 'https://covers.openlibrary.org/b/id/42-L.jpg');
  assert.match(res.headers.get('netlify-cdn-cache-control'), /s-maxage=2592000/);
  routes = [
    [(u) => u.startsWith('https://openlibrary.org/search.json'), () => respond({ docs: [] })],
    [(u) => u.startsWith('https://www.googleapis.com/books/v1/volumes'), () => respond({ items: [{ volumeInfo: { title: 'Wrong', imageLinks: { thumbnail: 'http://x/1' } } }, { volumeInfo: { title: 'Dracula', imageLinks: { thumbnail: 'http://books.google.com/books/content?id=a&img=1&edge=curl' } } }] })],
  ];
  body = await (await covers(get('/api/covers?title=Dracula&author=Bram%20Stoker'), ctx('c2'))).json();
  assert.equal(body.cover, 'https://books.google.com/books/content?id=a&img=1');
  assert.equal(body.source, 'Google Books');
  assert.equal((await covers(get('/api/covers?isbn=12'), ctx('c3'))).status, 400);
});

await test('paid endpoints stay off without an owner access code', async () => {
  delete process.env.MAVIS_ACCESS_CODE;
  process.env.FISH_AUDIO_API_KEY = 'k'; process.env.LLM_API_KEY = 'k'; process.env.EDENAI_API_KEY = 'k';
  for (const [fn, path] of [[tts, '/api/tts'], [assistant, '/api/assistant'], [rank, '/api/rank']]) {
    const res = await fn(post(path, { text: 'hi', messages: [{ role: 'user', content: 'hi' }], candidates: [{ id: 'a', title: 'A' }] }), ctx('p0'));
    assert.equal(res.status, 403, path);
  }
  assert.equal(calls.length, 0);
});

await test('paid endpoints reject a wrong code and cross-site posts', async () => {
  process.env.MAVIS_ACCESS_CODE = 'right-code';
  let res = await tts(post('/api/tts', { text: 'hi' }, { 'x-mavis-access': 'wrong' }), ctx('p1'));
  assert.equal(res.status, 401);
  res = await tts(post('/api/tts', { text: 'hi' }, { 'x-mavis-access': 'right-code', origin: 'https://evil.example' }), ctx('p1'));
  assert.equal(res.status, 403);
  res = await tts(new Request('https://mavis.test/api/tts', { method: 'GET' }), ctx('p1'));
  assert.equal(res.status, 405);
  assert.equal(calls.length, 0);
});

await test('tts: Fish Audio request shape, size cap, and MP3 response', async () => {
  process.env.MAVIS_ACCESS_CODE = 'right-code'; process.env.TTS_PROVIDER = 'fish'; process.env.FISH_AUDIO_VOICE_ID = 'voice-1';
  let sent;
  routes = [[(u) => u === 'https://api.fish.audio/v1/tts', (u, o) => { sent = { headers: o.headers, body: JSON.parse(o.body) }; return respond(new Uint8Array([0xff, 0xfb, 0x90, 0x00]), { headers: { 'content-type': 'audio/mpeg' } }); }]];
  const res = await tts(post('/api/tts', { text: 'In the beginning.', speed: 1.25 }, { 'x-mavis-access': 'right-code' }), ctx('t1'));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'audio/mpeg');
  assert.equal(sent.headers.authorization, 'Bearer k');
  assert.deepEqual([sent.body.text, sent.body.format, sent.body.reference_id, sent.body.prosody.speed], ['In the beginning.', 'mp3', 'voice-1', 1.25]);
  const long = await tts(post('/api/tts', { text: 'x'.repeat(5000) }, { 'x-mavis-access': 'right-code' }), ctx('t2'));
  assert.equal(long.status, 413);
});

await test('assistant: sends passage + tools to Anthropic and returns reply and actions', async () => {
  process.env.LLM_PROVIDER = 'anthropic';
  let sent;
  routes = [[(u) => u === 'https://api.anthropic.com/v1/messages', (u, o) => { sent = { headers: o.headers, body: JSON.parse(o.body) }; return respond({ content: [{ type: 'text', text: 'Here is a summary.' }, { type: 'tool_use', name: 'read_aloud', input: { from: 'here' } }, { type: 'tool_use', name: 'delete_everything', input: {} }] }); }]];
  const res = await assistant(post('/api/assistant', { messages: [{ role: 'user', content: 'Summarize and read it' }], context: { title: 'Emma', chapter: 'Chapter 1', text: 'Emma Woodhouse, handsome, clever, and rich…' } }, { 'x-mavis-access': 'right-code' }), ctx('a1'));
  const body = await res.json();
  assert.equal(res.status, 200);
  assert.equal(body.reply, 'Here is a summary.');
  assert.deepEqual(body.actions, [{ name: 'read_aloud', input: { from: 'here' } }]);
  assert.equal(sent.headers['x-api-key'], 'k');
  assert.match(sent.body.system, /Emma Woodhouse/);
  assert.ok(sent.body.tools.some((t) => t.name === 'go_to' && t.input_schema));
});

await test('assistant: OpenAI provider sends max_completion_tokens and maps tool calls', async () => {
  process.env.LLM_PROVIDER = 'openai'; process.env.LLM_MODEL = 'gpt-test';
  let sent;
  routes = [[(u) => u === 'https://api.openai.com/v1/chat/completions', (u, o) => { sent = { headers: o.headers, body: JSON.parse(o.body) }; return respond({ choices: [{ message: { content: 'Sure.', tool_calls: [{ function: { name: 'car_mode', arguments: '{}' } }] }, finish_reason: 'tool_calls' }] }); }]];
  const res = await assistant(post('/api/assistant', { messages: [{ role: 'user', content: 'Car mode please' }], context: { title: 'Emma' } }, { 'x-mavis-access': 'right-code' }), ctx('a2'));
  const body = await res.json();
  assert.equal(sent.headers.authorization, 'Bearer k');
  assert.equal(sent.body.model, 'gpt-test');
  assert.equal(sent.body.max_completion_tokens, 4000);
  assert.equal(sent.body.max_tokens, undefined);
  assert.equal(sent.body.messages[0].role, 'system');
  assert.deepEqual(body.actions, [{ name: 'car_mode', input: {} }]);
  assert.equal(body.reply, 'Sure.');
  process.env.LLM_PROVIDER = 'anthropic'; delete process.env.LLM_MODEL;
});

await test('rank: builds Jev score questions and maps scores to candidates', async () => {
  let sent;
  routes = [[(u) => u === 'https://api.edenai.run/v3/alpha/decisions', (u, o) => { sent = JSON.parse(o.body); return respond({ model: 'typesafe/jev-1', answers: { c0: { score: 4 }, c1: { score: 1 } } }); }]];
  const res = await rank(post('/api/rank', { profile: { genres: ['adventure'] }, candidates: [{ id: 'g:1', title: 'Treasure Island' }, { id: 'g:2', title: 'Emma' }] }, { 'x-mavis-access': 'right-code' }), ctx('r1'));
  const body = await res.json();
  assert.equal(sent.model, 'typesafe/jev-latest');
  assert.equal(sent.questions.c0.type, 'score');
  assert.deepEqual(body.scores, { 'g:1': 1, 'g:2': 0.25 });
});

await test('rank: prefers TypeSafe’s own API when TYPESAFE_API_KEY is set', async () => {
  process.env.TYPESAFE_API_KEY = 'apikey_test';
  let sent, auth;
  routes = [[(u) => u === 'https://api.typesafe.ai/v1/systemone', (u, o) => { sent = JSON.parse(o.body); auth = o.headers.authorization; return respond({ model: 'jev-1.13.0', answers: { c0: { type: 'score', score: 2 } } }); }]];
  const res = await rank(post('/api/rank', { profile: { genres: ['poetry'] }, candidates: [{ id: 'g:9', title: 'Leaves of Grass' }] }, { 'x-mavis-access': 'right-code' }), ctx('r2'));
  const body = await res.json();
  assert.equal(auth, 'Bearer apikey_test');
  assert.equal(sent.model, 'jev-latest');
  assert.deepEqual(body.scores, { 'g:9': 0.5 });
  assert.equal(body.via, 'typesafe');
  const f = await (await featuresFn(get('/api/features'))).json();
  assert.equal(f.jev, 'typesafe');
  delete process.env.TYPESAFE_API_KEY;
});

await test('features: reports what is on without exposing keys, and verifies the code', async () => {
  let res = await featuresFn(get('/api/features'));
  let body = await res.json();
  assert.equal(body.cloudVoice, 'fish');
  assert.ok(!JSON.stringify(body).includes('right-code') && !JSON.stringify(body).includes('"k"'));
  res = await featuresFn(new Request('https://mavis.test/api/features', { headers: { 'x-mavis-access': 'right-code' } }));
  body = await res.json();
  assert.equal(body.owner, true);
  assert.equal(res.headers.get('cache-control'), 'no-store');
});

console.log(`\n${passed} endpoint checks passed.`);
