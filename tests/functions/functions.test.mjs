// Unit tests for the server endpoints with upstream fetch mocked.
//   npm run test:functions

import assert from 'node:assert/strict';
import { gutendexBook, openLibrarySearch, makeFixtureEpub, opdsSearchFeed, opdsBookFeed } from '../fixtures/fixtures.mjs';
const xml = (body, status = 200) => new Response(body, { status, headers: { 'content-type': 'application/atom+xml; charset=UTF-8' } });

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

await test('catalog list reads Gutenberg search feeds with only validated params', async () => {
  const fr = { ...gutendexBook(13951, 'Les trois mousquetaires'), languages: ['fr'] };
  routes = [[(u) => u.startsWith('https://www.gutenberg.org/ebooks/search.opds/'), () => xml(opdsSearchFeed([gutendexBook(1342, 'Pride and Prejudice', { author: 'Austen, Jane' }), fr], { next: true, query: 'austen' }))]];
  const res = await catalog(get('/api/catalog?search=austen&topic=love&page=2&evil=1&sort=popular'), ctx());
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.results.length, 2, 'facet entries are skipped');
  assert.equal(body.results[0].id, 1342);
  assert.equal(body.results[0].title, 'Pride and Prejudice');
  assert.equal(body.results[0].authors[0].name, 'Jane Austen');
  assert.deepEqual(body.results[0].languages, ['en']);
  assert.equal(body.results[0].cover, 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg');
  assert.equal(body.results[1].title, 'Les trois mousquetaires');
  assert.deepEqual(body.results[1].languages, ['fr']);
  assert.equal(body.hasNext, true);
  assert.equal(body.hasPrev, true);
  assert.equal(body.count, null);
  const u = new URL(calls[0]);
  assert.equal(u.host, 'www.gutenberg.org');
  assert.equal(u.searchParams.get('query'), 'austen s.love');
  assert.equal(u.searchParams.get('sort_order'), 'downloads');
  assert.equal(u.searchParams.get('start_index'), '26');
  assert.equal([...u.searchParams.keys()].includes('evil'), false);
});

await test('catalog language filter and single-book record', async () => {
  routes = [
    [(u) => u.startsWith('https://www.gutenberg.org/ebooks/search.opds/'), () => xml(opdsSearchFeed([gutendexBook(1342, 'Pride and Prejudice')]))],
    [(u) => u === 'https://www.gutenberg.org/ebooks/1342.opds', () => xml(opdsBookFeed(gutendexBook(1342, 'Pride & Prejudice', { author: 'Austen, Jane', subjects: ['Love stories', 'England -- Fiction'], downloads: 191414 })))],
  ];
  const list = await (await catalog(get('/api/catalog?search=pride&languages=en'), ctx())).json();
  assert.equal(new URL(calls[0]).searchParams.get('query'), 'pride l.en');
  assert.deepEqual(list.results[0].languages, ['en']);
  const res = await catalog(get('/api/catalog?id=1342'), ctx());
  assert.equal(res.status, 200);
  const { book } = await res.json();
  assert.equal(book.title, 'Pride & Prejudice');
  assert.equal(book.authors[0].name, 'Austen, Jane');
  assert.equal(book.copyright, false);
  assert.equal(book.downloads, 191414);
  assert.deepEqual(book.subjects, ['Love stories', 'England -- Fiction']);
  assert.deepEqual(book.languages, ['en']);
  assert.equal(book.summary, 'A test summary for Pride & Prejudice.');
  assert.equal(book.epub, true);
  assert.equal(book.cover, 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg');
});

await test('catalog ids list fetches each record and drops copyrighted ones', async () => {
  routes = [[(u) => /\/ebooks\/\d+\.opds$/.test(u), (u) => {
    const id = Number(u.match(/(\d+)\.opds$/)[1]);
    return xml(opdsBookFeed({ ...gutendexBook(id, `Book ${id}`), copyright: id !== 77 ? false : true }));
  }]];
  const body = await (await catalog(get('/api/catalog?ids=84,77,11'), ctx())).json();
  assert.deepEqual(body.results.map((b) => b.id), [84, 11]);
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

await test('bible-ext: allowlisted translations and commentaries, normalized headings, notes, and spacing', async () => {
  const bx = (await import('../../netlify/functions/bible-ext.mjs')).default;
  routes = [[(u) => u.startsWith('https://bible.helloao.org/api/BSB/PSA/23.json'), () => respond({ chapter: { content: [
    { type: 'hebrew_subtitle', content: ['A Psalm of David.'] },
    { type: 'heading', content: ['The LORD Is My Shepherd'] },
    { type: 'verse', number: 1, content: ['The LORD is my shepherd;', { lineBreak: true }, { text: 'I shall not want.', poem: 2 }] },
    { type: 'verse', number: 2, content: ['He makes me lie down', { noteId: 7 }, 'in green pastures'] },
  ], footnotes: [{ noteId: 7, text: 'A translator note' }] } })]];
  const res = await bx(get('/api/bible-ext?tr=bsb&b=Ps&c=23'), ctx());
  const j = await res.json();
  assert.equal(res.status, 200);
  assert.equal(j.subtitle, 'A Psalm of David.');
  assert.deepEqual(j.headings, { 1: ['The LORD Is My Shepherd'] });
  assert.equal(j.verses[0], 'The LORD is my shepherd; I shall not want.');
  assert.equal(j.verses[1], 'He makes me lie down in green pastures');
  assert.deepEqual(j.notes, { 2: ['A translator note'] });
  assert.equal((await bx(get('/api/bible-ext?tr=NIV&b=Ps&c=23'), ctx())).status, 400, 'only allowlisted translations');
  assert.equal((await bx(get('/api/bible-ext?cm=../../etc&b=Ps&c=23'), ctx())).status, 400);
  assert.equal((await bx(get('/api/bible-ext?tr=BSB&b=Psalms&c=23'), ctx())).status, 400, 'book ids only');
  assert.equal((await bx(get('/api/bible-ext?tr=BSB&b=Ps&c=999'), ctx())).status, 400);
  assert.ok(calls.every((u) => u.startsWith('https://bible.helloao.org/api/')));
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
  routes = [[(u) => u.endsWith('/ebooks/77.opds'), () => xml(opdsBookFeed({ ...gutendexBook(77, 'Modern Book'), copyright: true }))]];
  const res = await epub(get('/api/epub?id=77'), ctx('5.5.5.5'));
  assert.equal(res.status, 451);
  assert.equal(calls.length, 1);
});

await test('epub endpoint streams a real EPUB from gutenberg.org', async () => {
  const bytes = await makeFixtureEpub();
  routes = [
    [(u) => u.endsWith('/ebooks/1342.opds'), () => xml(opdsBookFeed(gutendexBook(1342, 'Pride and Prejudice')))],
    [(u) => u.startsWith('https://www.gutenberg.org/'), (u) => withUrl(respond(bytes, { headers: { 'content-type': 'application/epub+zip', 'content-length': String(bytes.length) } }), 'https://www.gutenberg.org/cache/epub/1342/pg1342-images-3.epub')],
  ];
  const res = await epub(get('/api/epub?id=1342'), ctx('6.6.6.6'));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/epub+zip');
  const out = new Uint8Array(await res.arrayBuffer());
  assert.equal(out.length, bytes.length);
  assert.equal(out[0], 0x50); assert.equal(out[1], 0x4b); // "PK"
  assert.ok(calls.every((u) => u.startsWith('https://www.gutenberg.org/')));
});

await test('epub endpoint ignores redirects to other hosts and HTML rate-limit pages', async () => {
  routes = [
    [(u) => u.endsWith('/ebooks/84.opds'), () => xml(opdsBookFeed(gutendexBook(84, 'Frankenstein')))],
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
    [(u) => u.endsWith('/ebooks/2701.opds'), () => xml(opdsBookFeed(gutendexBook(2701, 'Moby Dick')))],
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
  assert.equal(sent.body.reasoning_effort, undefined);
  process.env.LLM_PROVIDER = 'anthropic'; delete process.env.LLM_MODEL;
});

await test('assistant: GPT-5 models get reasoning off so tools work; others retry once if asked', async () => {
  process.env.LLM_PROVIDER = 'openai'; process.env.LLM_MODEL = 'gpt-5.6-luna';
  const bodies = [];
  const ok = () => respond({ choices: [{ message: { content: 'Jane Austen wrote it.' }, finish_reason: 'stop' }] });
  routes = [[(u) => u === 'https://api.openai.com/v1/chat/completions', (u, o) => { bodies.push(JSON.parse(o.body)); return ok(); }]];
  let res = await assistant(post('/api/assistant', { messages: [{ role: 'user', content: 'Who wrote this?' }], context: { title: 'Emma' } }, { 'x-mavis-access': 'right-code' }), ctx('a3'));
  assert.equal(res.status, 200);
  assert.equal(bodies[0].reasoning_effort, 'none');
  assert.equal(bodies[0].max_completion_tokens, 1200);
  assert.ok(Array.isArray(bodies[0].tools));

  process.env.LLM_MODEL = 'o-future';
  bodies.length = 0;
  routes = [[(u) => u === 'https://api.openai.com/v1/chat/completions', (u, o) => {
    const b = JSON.parse(o.body); bodies.push(b);
    return b.reasoning_effort ? ok() : respond({ error: { message: 'Function tools with reasoning_effort are not supported for o-future in /v1/chat/completions. Set reasoning_effort to none.' } }, { status: 400 });
  }]];
  res = await assistant(post('/api/assistant', { messages: [{ role: 'user', content: 'Who wrote this?' }], context: { title: 'Emma' } }, { 'x-mavis-access': 'right-code' }), ctx('a4'));
  assert.equal(res.status, 200);
  assert.equal(bodies.length, 2);
  assert.equal(bodies[0].reasoning_effort, undefined);
  assert.equal(bodies[1].reasoning_effort, 'none');
  assert.equal((await res.json()).reply, 'Jane Austen wrote it.');
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

await test('study: chapter summaries, spoiler-safe recap, cleaned character JSON, and background pictures', async () => {
  const study = (await import('../../netlify/functions/study.mjs')).default;
  process.env.LLM_PROVIDER = 'openai'; process.env.LLM_MODEL = 'gpt-5.6-luna';
  const sent = [];
  routes = [
    [(u) => u === 'https://api.openai.com/v1/chat/completions', (u, o) => {
      const b = JSON.parse(o.body); sent.push(b);
      if (b.response_format) return respond({ choices: [{ message: { content: JSON.stringify({ characters: [{ name: 'Emma', importance: 9, relations: [{ to: 'Mr. Knightley', relation: 'friend' }, { relation: 'no target' }] }, { role: 'nameless' }] }) } }] });
      return respond({ choices: [{ message: { content: 'A short summary.' } }] });
    }],
    [(u) => u === 'https://api.openai.com/v1/responses', () => respond({ id: 'resp_abc123def456', status: 'queued' })],
    [(u) => u === 'https://api.openai.com/v1/responses/resp_abc123def456', () => respond({ status: 'completed', output: [{ type: 'image_generation_call', result: 'AAAA', revised_prompt: 'x' }] })],
  ];
  const H = { 'x-mavis-access': 'right-code' };
  assert.equal((await study(post('/api/study', { task: 'summarize', text: 'x'.repeat(100) }), ctx('s0'))).status, 401, 'needs the access code');
  let r = await study(post('/api/study', { task: 'summarize', title: 'Emma', chapter: 'Chapter 1', text: 'Emma Woodhouse, handsome, clever, and rich… '.repeat(5) }, H), ctx('s1'));
  assert.equal((await r.json()).summary, 'A short summary.');
  assert.equal(sent[0].reasoning_effort, 'none');
  r = await study(post('/api/study', { task: 'recap', title: 'Emma', summaries: [{ chapter: 'Chapter 1', summary: 'Emma meets Harriet.' }], current: { chapter: 'Chapter 2', text: 'Up to here.' } }, H), ctx('s2'));
  assert.equal((await r.json()).recap, 'A short summary.');
  assert.match(sent[1].messages[0].content, /never hint at what happens next/);
  assert.match(sent[1].messages[1].content, /Emma meets Harriet/);
  r = await study(post('/api/study', { task: 'characters', title: 'Emma', summaries: [], current: { text: 'Emma.' } }, H), ctx('s3'));
  const { characters } = await r.json();
  assert.equal(characters.length, 1, 'nameless entries dropped');
  assert.equal(characters[0].importance, 3, 'importance clamped');
  assert.deepEqual(characters[0].relations, [{ to: 'Mr. Knightley', relation: 'friend' }]);
  r = await study(post('/api/study', { task: 'picture', title: 'Emma', passage: 'Emma walked through the garden at Hartfield in the morning light.' }, H), ctx('s4'));
  assert.equal((await r.json()).id, 'resp_abc123def456');
  const poll = await study(new Request('https://mavis.test/api/study?picture=resp_abc123def456', { headers: H }), ctx('s5'));
  const pj = await poll.json();
  assert.equal(pj.status, 'done'); assert.equal(pj.image, 'AAAA');
  assert.equal((await study(new Request('https://mavis.test/api/study?picture=../../x', { headers: H }), ctx('s6'))).status, 400);
  assert.equal((await study(new Request('https://mavis.test/api/study?picture=resp_abc123def456'), ctx('s7'))).status, 401);
  assert.equal((await study(post('/api/study', { task: 'shell' }, H), ctx('s8'))).status, 400);
  process.env.LLM_PROVIDER = 'anthropic'; delete process.env.LLM_MODEL;
});

await test('voices: licensed library, private cloning only with consent, deletes, and a chosen voice reaches Fish', async () => {
  const voices = (await import('../../netlify/functions/voices.mjs')).default;
  const tts = (await import('../../netlify/functions/tts.mjs')).default;
  process.env.MAVIS_ACCESS_CODE = 'right-code'; process.env.FISH_AUDIO_API_KEY = 'k'; process.env.TTS_PROVIDER = 'fish';
  let created = null; const ttsBodies = [];
  routes = [
    [(u) => u.startsWith('https://api.fish.audio/model?'), (u) => respond({ total: 1, items: [{ _id: 'a'.repeat(32), type: 'tts', title: 'Warm', tags: ['male'], samples: [{ audio: 'https://platform.r2.fish.audio/x.mp3' }], query: u }] })],
    [(u) => u === 'https://api.fish.audio/model', (u, o) => { created = Object.fromEntries([...o.body.entries()].map(([k, v]) => [k, typeof v === 'string' ? v : `file:${v.size}`])); return respond({ _id: 'b'.repeat(32), type: 'tts', title: o.body.get('title'), state: 'trained' }); }],
    [(u) => u.startsWith('https://api.fish.audio/model/'), () => new Response(null, { status: 204 })],
    [(u) => u === 'https://api.fish.audio/v1/tts', (u, o) => { ttsBodies.push(JSON.parse(o.body)); return new Response(new Uint8Array(600), { headers: { 'content-type': 'audio/mpeg' } }); }],
  ];
  const H = { 'x-mavis-access': 'right-code' };
  assert.equal((await voices(get('/api/voices?list=library'), ctx('v0'))).status, 401);
  let r = await voices(new Request('https://mavis.test/api/voices?list=library&gender=female&age=bogus', { headers: H }), ctx('v1'));
  const lib = await r.json();
  assert.equal(lib.items[0].sample, 'https://platform.r2.fish.audio/x.mp3');
  const q = new URL(calls[calls.length - 1]).searchParams;
  assert.equal(q.get('licensed'), 'true'); assert.deepEqual(q.getAll('tag'), ['female']);
  const form = (fields) => { const fd = new FormData(); for (const [k, v] of Object.entries(fields)) fd.append(k, v); return fd; };
  const audio = new File([new Uint8Array(60_000)], 'me.webm', { type: 'audio/webm' });
  const postForm = (fd, extra = {}) => new Request('https://mavis.test/api/voices', { method: 'POST', headers: { ...H, ...extra }, body: fd });
  assert.equal((await voices(postForm(form({ title: 'Me', audio })), ctx('v2'))).status, 400, 'consent is required');
  assert.equal((await voices(postForm(form({ consent: 'yes', audio: new File([new Uint8Array(500)], 'x.webm', { type: 'audio/webm' }) })), ctx('v3'))).status, 400, 'too short');
  assert.equal((await voices(postForm(form({ consent: 'yes', audio }), { origin: 'https://evil.example' }), ctx('v4'))).status, 403);
  r = await voices(postForm(form({ consent: 'yes', title: 'Me', audio })), ctx('v5'));
  assert.equal(r.status, 200);
  assert.equal(created.type, 'tts'); assert.equal(created.visibility, 'private'); assert.equal(created.train_mode, 'fast'); assert.equal(created.voices, 'file:60000');
  assert.equal((await voices(new Request('https://mavis.test/api/voices?id=../../x', { method: 'DELETE', headers: H }), ctx('v6'))).status, 400);
  assert.equal((await voices(new Request(`https://mavis.test/api/voices?id=${'b'.repeat(32)}`, { method: 'DELETE', headers: H }), ctx('v7'))).status, 200);
  await tts(post('/api/tts', { text: 'Hello', voice: 'c'.repeat(32) }, H), ctx('v8'));
  await tts(post('/api/tts', { text: 'Hello', voice: 'not a voice; drop table' }, H), ctx('v9'));
  assert.equal(ttsBodies[0].reference_id, 'c'.repeat(32));
  assert.equal(ttsBodies[1].reference_id, process.env.FISH_AUDIO_VOICE_ID, 'invalid voice ids fall back to the default');
});

await test('study cast: speaker lines are cleaned and indexed', async () => {
  const study = (await import('../../netlify/functions/study.mjs')).default;
  process.env.LLM_PROVIDER = 'openai'; process.env.LLM_MODEL = 'gpt-5.6-luna';
  routes = [[(u) => u === 'https://api.openai.com/v1/chat/completions', () => respond({ choices: [{ message: { content: JSON.stringify({ speakers: { Ann: { gender: 'female', age: 'weird' }, '': { gender: 'x' } }, lines: [{ i: 0, speaker: 'Ann' }, { i: 7, speaker: 'Ghost' }, { i: 1 }] }) } }] })]];
  const r = await study(post('/api/study', { task: 'cast', title: 'T', quotes: [{ text: '“Hi.”' }, { text: '“Bye.”' }] }, { 'x-mavis-access': 'right-code' }), ctx('c1'));
  const j = await r.json();
  assert.deepEqual(j.speakers, { Ann: { gender: 'female', age: 'adult' } });
  assert.deepEqual(j.lines, [{ i: 0, speaker: 'Ann' }, { i: 1, speaker: 'Narrator' }]);
  process.env.LLM_PROVIDER = 'anthropic'; delete process.env.LLM_MODEL;
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

await test('librivox: exact Gutenberg match first, solo readers ahead of groups, only archive.org audio', async () => {
  const lv = (await import('../../netlify/functions/librivox.mjs')).default;
  const sec = (id, n, reader, host = 'https://www.archive.org') => ({ section_number: String(n), title: `Chapter ${n}`, listen_url: `${host}/download/${id}/${n}.mp3`, playtime: '600', readers: [{ display_name: reader }] });
  let seen = '';
  routes = [[(u) => u.startsWith('https://librivox.org/api/feed/audiobooks/'), (u) => { seen = u; return respond({ books: [
    { id: '253', title: 'Pride and Prejudice', language: 'English', url_text_source: 'http://www.gutenberg.org/etext/1342', totaltimesecs: '1200', authors: [{ last_name: 'Austen' }], sections: [sec('a', 1, 'Chris'), sec('a', 2, 'Dana')] },
    { id: '4023', title: 'Pride and Prejudice (version 3)', language: 'English', url_text_source: 'https://www.gutenberg.org/ebooks/1342', totaltimesecs: '1200', authors: [{ last_name: 'Austen' }], url_librivox: 'https://librivox.org/pp3/', sections: [sec('b', 1, 'Klett'), sec('b', 2, 'Klett')] },
    { id: '9', title: 'Pride and Prejudice', language: 'French', url_text_source: '', authors: [{ last_name: 'Austen' }], sections: [sec('c', 1, 'Fr')] },
    { id: '10', title: 'Pride and Prejudice and Zombies', language: 'English', authors: [{ last_name: 'Grahame-Smith' }], sections: [sec('d', 1, 'Z')] },
    { id: '11', title: 'Pride and Prejudice', language: 'English', authors: [{ last_name: 'Austen' }], sections: [sec('e', 1, 'Evil', 'https://evil.example')] },
  ] }); }]];
  const r = await lv(get('/api/librivox?gid=1342&title=Pride%20and%20Prejudice&author=Jane%20Austen'), ctx('lv1'));
  assert.equal(r.status, 200);
  const { versions } = await r.json();
  assert.equal(new URL(seen).searchParams.get('title'), '^Pride and Prejudice');
  assert.deepEqual(versions.map((v) => v.id), ['4023', '253', '9'], 'exact text, solo first; zombies and non-archive audio dropped');
  assert.deepEqual(versions[0].readers, ['Klett']);
  assert.ok(versions[0].sections.every((s) => s.url.startsWith('https://www.archive.org/')));
  assert.equal(versions[0].url, 'https://librivox.org/pp3/');
  assert.equal((await lv(get('/api/librivox'), ctx('lv2'))).status, 400);
  routes = [[(u) => u.startsWith('https://librivox.org/'), () => new Response('nope', { status: 404 })]];
  assert.deepEqual(await (await lv(get('/api/librivox?title=Nothing'), ctx('lv3'))).json(), { versions: [] });
});

console.log(`\n${passed} endpoint checks passed.`);
