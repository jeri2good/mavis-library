// "Report this" endpoint against a real local Netlify Blobs server.
//   node tests/functions/report.test.mjs

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BlobsServer } from '@netlify/blobs/server';

const dir = mkdtempSync(join(tmpdir(), 'mavis-reports-'));
const server = new BlobsServer({ directory: dir, token: 'local-token', port: 0 });
const { port } = await server.start();
process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
process.env.MAVIS_BLOBS_TOKEN = 'local-token';

const report = (await import('../../netlify/functions/report.mjs')).default;
const { config } = await import('../../netlify/functions/report.mjs');
const { kv } = await import('../../netlify/lib/accounts.mjs');

let ip = 0;
const ctx = () => ({ ip: `10.9.0.${++ip}` });
const post = (body, headers = {}) => new Request('https://mavis.test/api/report', {
  method: 'POST',
  headers: { 'content-type': 'application/json', ...headers },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`  ✓ ${name}`); }

try {
  await test('config keeps the /api/report path and lets preflight through', async () => {
    assert.equal(config.path, '/api/report');
    assert.deepEqual(config.method, ['POST', 'OPTIONS']);
    assert.ok(config.rateLimit.windowLimit > 0);
  });

  await test('a report is stored in the reports store with only what, ref, text, time, and version', async () => {
    const res = await report(post({ what: 'picture', ref: 'Emma · Chapter 3', text: 'Wrong\u0000 era\n\nclothes', version: '0.4.0+12', email: 'x@y.z', uid: 'u1' }, { origin: 'https://mavis.test', authorization: 'Bearer abc' }), ctx());
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.match(body.message, /sent/);
    const { blobs } = await kv('reports').list({ prefix: 'r/' });
    assert.equal(blobs.length, 1);
    assert.equal(blobs[0].key, `r/${body.id}`);
    const note = await kv('reports').get(blobs[0].key, { type: 'json' });
    assert.deepEqual(Object.keys(note).sort(), ['at', 'ref', 'text', 'version', 'what']);
    assert.equal(note.what, 'picture');
    assert.equal(note.ref, 'Emma · Chapter 3');
    assert.equal(note.text, 'Wrong era clothes', 'control characters and runs of spaces are cleaned');
    assert.equal(note.version, '0.4.0+12');
    assert.ok(!Number.isNaN(Date.parse(note.at)));
    assert.ok(!JSON.stringify(note).includes('10.9.0'), 'no IP stored');
  });

  await test('fields are length-capped; unknown kinds and odd versions are refused or dropped', async () => {
    const res = await report(post({ what: 'answer', ref: 'r'.repeat(900), text: 't'.repeat(1500), version: '<script>' }), ctx());
    assert.equal(res.status, 200);
    const note = await kv('reports').get(`r/${(await res.json()).id}`, { type: 'json' });
    assert.equal(note.ref.length, 300);
    assert.equal(note.text.length, 1000);
    assert.equal(note.version, '');
    assert.equal((await report(post({ what: 'everything' }), ctx())).status, 400);
    assert.equal((await report(post({}), ctx())).status, 400);
    assert.equal((await report(post('not json'), ctx())).status, 400);
  });

  await test('oversize bodies are refused with 413 and nothing is stored', async () => {
    const before = (await kv('reports').list({ prefix: 'r/' })).blobs.length;
    const res = await report(post({ what: 'film', text: 'x'.repeat(5000) }), ctx());
    assert.equal(res.status, 413);
    assert.equal((await kv('reports').list({ prefix: 'r/' })).blobs.length, before);
  });

  await test('refused origins get 403 with no CORS headers; the app origin is accepted with CORS', async () => {
    const before = (await kv('reports').list({ prefix: 'r/' })).blobs.length;
    let res = await report(post({ what: 'summary' }, { origin: 'https://evil.example' }), ctx());
    assert.equal(res.status, 403);
    assert.equal(res.headers.get('access-control-allow-origin'), null);
    res = await report(new Request('https://mavis.test/api/report', { method: 'OPTIONS', headers: { origin: 'https://evil.example' } }), ctx());
    assert.equal(res.status, 403);
    assert.equal((await kv('reports').list({ prefix: 'r/' })).blobs.length, before);
    res = await report(new Request('https://mavis.test/api/report', { method: 'OPTIONS', headers: { origin: 'https://localhost', 'access-control-request-method': 'POST' } }), ctx());
    assert.equal(res.status, 204);
    res = await report(post({ what: 'summary', ref: 'Frankenstein · Letter 1' }, { origin: 'https://localhost' }), ctx());
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('access-control-allow-origin'), 'https://localhost');
  });

  await test('only JSON POSTs; bursts from one client are rate-limited', async () => {
    assert.equal((await report(new Request('https://mavis.test/api/report'), ctx())).status, 405);
    assert.equal((await report(new Request('https://mavis.test/api/report', { method: 'POST', body: 'what=answer' }), ctx())).status, 415);
    let last;
    for (let i = 0; i < 12; i++) last = await report(post({ what: 'answer' }), { ip: '10.9.9.9' });
    assert.equal(last.status, 429);
  });

  console.log(`\n${passed} report checks passed.`);
} finally {
  await server.stop();
  rmSync(dir, { recursive: true, force: true });
}
