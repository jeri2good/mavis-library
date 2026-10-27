// CORS for the Android app: two allowed origins, no wildcard, preflight,
// and the existing cross-site POST refusal for every other origin.
//   node tests/functions/cors.test.mjs

import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { withCors } from '../../netlify/lib/cors.mjs';

globalThis.fetch = async (url) => {
  url = String(url);
  if (url.startsWith('https://openlibrary.org/search.json')) return new Response(JSON.stringify({ docs: [{ title: 'Emma', cover_i: 7 }] }), { headers: { 'content-type': 'application/json' } });
  throw new TypeError(`unmocked fetch ${url}`);
};

const features = (await import('../../netlify/functions/features.mjs')).default;
const tts = (await import('../../netlify/functions/tts.mjs')).default;
const covers = (await import('../../netlify/functions/covers.mjs')).default;
const ALLOWED = ['https://localhost', 'https://mavis-library.netlify.app'];
const REFUSED = ['https://evil.example', 'http://localhost', 'https://localhost:8443', 'https://mavis-library.netlify.app.evil.example', 'null'];
const HEADERS = 'authorization, content-type, x-mavis-access, x-mavis-kids, x-mavis-source, x-mavis-voice';
const ctx = (ip = '7.7.7.7') => ({ ip });
const get = (path, headers = {}) => new Request(`https://mavis.test${path}`, { headers });
const preflight = (path, origin) => new Request(`https://mavis.test${path}`, { method: 'OPTIONS', headers: { origin, 'access-control-request-method': 'POST', 'access-control-request-headers': 'content-type, x-mavis-access' } });
const post = (path, body, headers = {}) => new Request(`https://mavis.test${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`  ✓ ${name}`); }

await test('allowed origins get their own origin back (never *), Vary: Origin, methods, headers', async () => {
  for (const origin of ALLOWED) {
    const res = await features(get('/api/features', { origin }));
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('access-control-allow-origin'), origin);
    assert.match(res.headers.get('vary'), /\bOrigin\b/);
    assert.equal(res.headers.get('access-control-allow-methods'), 'GET, POST, OPTIONS');
    assert.equal(res.headers.get('access-control-allow-headers'), HEADERS);
    assert.equal(res.headers.get('access-control-expose-headers'), 'content-length');
    assert.equal(res.headers.get('access-control-allow-credentials'), null);
    assert.ok((await res.json()).cloudVoice !== undefined, 'body passes through');
  }
});

await test('refused origins and same-origin requests get no CORS headers', async () => {
  for (const origin of REFUSED) {
    const res = await features(get('/api/features', { origin }));
    assert.equal(res.status, 200, origin);
    assert.equal(res.headers.get('access-control-allow-origin'), null, origin);
    assert.equal(res.headers.get('access-control-allow-methods'), null, origin);
  }
  const res = await features(get('/api/features'));
  assert.equal(res.headers.get('access-control-allow-origin'), null);
});

await test('preflight: 204 with headers for allowed origins, 403 for refused ones, on every function', async () => {
  const files = readdirSync(new URL('../../netlify/functions/', import.meta.url)).filter((f) => f.endsWith('.mjs'));
  assert.ok(files.length >= 15);
  for (const f of files) {
    const mod = await import(`../../netlify/functions/${f}`);
    assert.equal(typeof mod.default, 'function', f);
    if (mod.config?.schedule) { assert.equal(mod.config.path, undefined, `${f} is schedule-only`); continue; }
    const paths = [].concat(mod.config?.path || []);
    assert.ok(paths.length && paths.every(path => path.startsWith('/api/')), `${f} keeps its config`);
    const m = [].concat(mod.config.method || []);
    if (m.length) assert.ok(m.includes('OPTIONS'), `${f}: config.method must let preflight through`);
    for (const origin of ALLOWED) {
      const res = await mod.default(preflight(paths[0], origin), ctx());
      assert.equal(res.status, 204, `${f} ${origin}`);
      assert.equal(res.headers.get('access-control-allow-origin'), origin);
      assert.match(res.headers.get('access-control-allow-methods'), /GET, (PUT, )?POST, (DELETE, )?OPTIONS/);
      assert.equal(res.headers.get('access-control-allow-headers'), HEADERS);
      assert.equal(res.headers.get('vary'), 'Origin');
    }
    for (const origin of REFUSED) {
      const res = await mod.default(preflight(paths[0], origin), ctx());
      assert.equal(res.status, 403, `${f} ${origin}`);
      assert.equal(res.headers.get('access-control-allow-origin'), null);
    }
  }
});

await test('OPTIONS without an Origin header still reaches the handler as before', async () => {
  const res = await features(new Request('https://mavis.test/api/features', { method: 'OPTIONS' }));
  assert.equal(res.status, 204);
  assert.equal(res.headers.get('allow'), 'GET');
  assert.equal(res.headers.get('access-control-allow-origin'), null);
});

await test('cross-site POSTs stay refused for other origins; the two app origins pass the origin check', async () => {
  process.env.MAVIS_ACCESS_CODE = 'right-code';
  for (const origin of REFUSED) {
    const res = await tts(post('/api/tts', { text: 'hi' }, { 'x-mavis-access': 'right-code', origin }), ctx('x1'));
    assert.equal(res.status, 403, origin);
    assert.equal((await res.json()).error, 'cross_origin');
    assert.equal(res.headers.get('access-control-allow-origin'), null);
  }
  for (const origin of ALLOWED) {
    // Past the origin check, the wrong access code is what stops it now.
    const res = await tts(post('/api/tts', { text: 'hi' }, { 'x-mavis-access': 'wrong', origin }), ctx('x2'));
    assert.equal(res.status, 401, origin);
    assert.equal(res.headers.get('access-control-allow-origin'), origin, 'errors carry CORS headers so the app can read them');
  }
  delete process.env.MAVIS_ACCESS_CODE;
});

await test('CDN-cached answers vary by Origin even without one, so web and app copies never mix', async () => {
  const plain = await covers(get('/api/covers?title=Emma&author=Jane%20Austen'), ctx('cv1'));
  assert.ok(plain.headers.get('netlify-cdn-cache-control'));
  assert.match(plain.headers.get('vary'), /\bOrigin\b/);
  assert.equal(plain.headers.get('access-control-allow-origin'), null);
  const app = await covers(get('/api/covers?title=Emma&author=Jane%20Austen', { origin: 'https://localhost' }), ctx('cv2'));
  assert.equal(app.headers.get('access-control-allow-origin'), 'https://localhost');
  assert.equal((app.headers.get('vary').match(/origin/gi) || []).length, 1, 'Origin listed once');
});

await test('responses with immutable headers are copied, not mutated', async () => {
  const wrapped = withCors(async () => Response.redirect('https://mavis.test/elsewhere', 302));
  const res = await wrapped(get('/x', { origin: 'https://localhost' }));
  assert.equal(res.status, 302);
  assert.equal(res.headers.get('location'), 'https://mavis.test/elsewhere');
  assert.equal(res.headers.get('access-control-allow-origin'), 'https://localhost');
});

console.log(`\n${passed} CORS checks passed.`);
