// Accounts and sync endpoints against a real local Netlify Blobs server.
//   node tests/functions/accounts.test.mjs

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BlobsServer } from '@netlify/blobs/server';

const dir = mkdtempSync(join(tmpdir(), 'mavis-blobs-'));
const server = new BlobsServer({ directory: dir, token: 'local-token', port: 0 });
const { port } = await server.start();
process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
// No write serialization here on purpose: sync never rewrites shared blobs,
// so concurrent devices are safe even on a store without atomic If-Match.
process.env.MAVIS_BLOBS_TOKEN = 'local-token';
process.env.AUTH_SECRET = 'test-secret-'.padEnd(48, 'x');

const account = (await import('../../netlify/functions/account.mjs')).default;
const sync = (await import('../../netlify/functions/sync.mjs')).default;
const features = (await import('../../netlify/functions/features.mjs')).default;
const { readToken } = await import('../../netlify/lib/accounts.mjs');

let ip = 0;
const ctx = (action) => ({ ip: `10.0.0.${++ip % 250}`, params: { action } });
const post = (path, body, token) => new Request(`https://mavis.test${path}`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', origin: 'https://mavis.test', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  body: JSON.stringify(body),
});
const get = (path, token) => new Request(`https://mavis.test${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} });
const act = async (action, body, token) => {
  const res = await account(body === undefined ? get(`/api/account/${action}`, token) : post(`/api/account/${action}`, body, token), ctx(action));
  return { status: res.status, body: await res.json() };
};
const doSync = async (token, changes = {}, cursors = {}) => {
  const res = await sync(post('/api/sync', { changes, cursors }, token), { ip: '10.1.1.1' });
  return { status: res.status, body: await res.json() };
};

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`  ✓ ${name}`); }

let ada; // { token, recoveryCode, id }

try {
  await test('features reports accounts on when AUTH_SECRET is set', async () => {
    const j = await (await features(new Request('https://mavis.test/api/features'))).json();
    assert.equal(j.accounts, true);
  });

  await test('sign-up validates input, returns a session and a one-time recovery code', async () => {
    assert.equal((await act('signup', { email: 'not-an-email', password: 'long-enough-1' })).status, 400);
    assert.equal((await act('signup', { email: 'ada@example.test', password: 'short' })).status, 400);
    assert.equal((await act('signup', { email: 'ada@example.test', password: 'password123' })).status, 400);
    const r = await act('signup', { email: 'Ada@Example.test ', password: 'correct-horse-9' });
    assert.equal(r.status, 200);
    assert.equal(r.body.user.email, 'ada@example.test');
    assert.match(r.body.recoveryCode, /^[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}$/);
    assert.ok(readToken(r.body.token));
    ada = { token: r.body.token, recoveryCode: r.body.recoveryCode, id: r.body.user.id };
    const again = await act('signup', { email: 'ada@example.test', password: 'another-pass-7' });
    assert.equal(again.status, 409);
  });

  await test('passwords and codes are stored only as scrypt hashes', async () => {
    const { getUser } = await import('../../netlify/lib/accounts.mjs');
    const u = await getUser(ada.id);
    assert.match(u.pw, /^s1\$/);
    assert.match(u.rc, /^s1\$/);
    assert.ok(!JSON.stringify(u).includes('correct-horse-9'));
    assert.ok(!JSON.stringify(u).includes(ada.recoveryCode));
  });

  await test('sign-in: right password works, wrong one gets one generic message, then locks', async () => {
    const ok = await act('signin', { email: 'ADA@example.test', password: 'correct-horse-9' });
    assert.equal(ok.status, 200);
    const wrong = await act('signin', { email: 'ada@example.test', password: 'nope-nope-1' });
    assert.equal(wrong.status, 401);
    const nobody = await act('signin', { email: 'nobody@example.test', password: 'nope-nope-1' });
    assert.equal(nobody.status, 401);
    assert.equal(wrong.body.message, nobody.body.message, 'same message whether or not the account exists');
    for (let i = 0; i < 4; i++) await act('signin', { email: 'nobody@example.test', password: 'nope-nope-1' });
    const locked = await act('signin', { email: 'nobody@example.test', password: 'nope-nope-1' });
    assert.equal(locked.status, 429);
  });

  await test('me needs a valid token; forged or tampered tokens are refused', async () => {
    assert.equal((await act('me', undefined, ada.token)).status, 200);
    assert.equal((await act('me', undefined)).status, 401);
    const [v, p, s] = ada.token.split('.');
    const forged = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(p, 'base64url')), u: '00000000-0000-0000-0000-000000000000' })).toString('base64url');
    assert.equal((await act('me', undefined, `${v}.${forged}.${s}`)).status, 401);
  });

  await test('sync: push rows, pull them on another device, newer change wins, cursors are opaque', async () => {
    const t1 = Date.now() - 10_000;
    const push = await doSync(ada.token, {
      shelf: [{ k: 'gutenberg:84', title: 'Frankenstein', status: 'reading', updatedAt: t1 }],
      annotations: [{ k: 'a1', bookKey: 'gutenberg:84', kind: 'highlight', text: 'It was on a dreary night', updatedAt: t1 }],
      bogus: [{ k: 'x', updatedAt: t1 }],
    });
    assert.equal(push.status, 200);
    assert.equal(push.body.rows.shelf.length, 1);
    assert.equal(push.body.rows.bogus, undefined);
    assert.match(push.body.cursors.shelf, /^\d{13}-[0-9a-f]{10}$/);
    const cursors = push.body.cursors;
    // Device 2 starts from nothing and gets both rows.
    const d2 = await doSync(ada.token);
    assert.equal(d2.body.rows.shelf[0].title, 'Frankenstein');
    assert.equal(d2.body.rows.annotations[0].text, 'It was on a dreary night');
    // An older change is ignored; a newer one wins.
    await doSync(ada.token, { shelf: [{ k: 'gutenberg:84', title: 'Frankenstein', status: 'finished', updatedAt: t1 - 5000 }] });
    let now = await doSync(ada.token);
    assert.equal(now.body.rows.shelf.length, 1);
    assert.equal(now.body.rows.shelf[0].status, 'reading');
    await doSync(ada.token, { shelf: [{ k: 'gutenberg:84', title: 'Frankenstein', status: 'finished', updatedAt: t1 + 5000 }] });
    now = await doSync(ada.token, {}, cursors);
    assert.equal(now.body.rows.shelf[0].status, 'finished', 'a device with a cursor gets the newer change');
    assert.ok(now.body.cursors.shelf > cursors.shelf);
    // Garbage cursors are treated as "from the start".
    const g = await doSync(ada.token, {}, { shelf: '../../etc', annotations: 42 });
    assert.equal(g.status, 200);
    assert.equal(g.body.rows.annotations.length, 1);
  });

  await test('sync: bad rows are dropped (no key, far-future time, oversized); server fields are stripped', async () => {
    const r = await doSync(ada.token, { annotations: [
      { updatedAt: Date.now() },
      { k: 'future', updatedAt: Date.now() + 10 * 864e5 },
      { k: 'huge', note: 'x'.repeat(20_000), updatedAt: Date.now() },
      { k: 'ok', owner: 'someone-else', dirty: true, id: 'evil|ok', note: 'fine', updatedAt: Date.now() },
    ] });
    const keys = r.body.rows.annotations.map((x) => x.k);
    assert.ok(keys.includes('ok'));
    assert.ok(!keys.includes('future') && !keys.includes('huge'));
    const ok = r.body.rows.annotations.find((x) => x.k === 'ok');
    assert.equal(ok.owner, undefined); assert.equal(ok.dirty, undefined); assert.equal(ok.id, undefined);
  });

  await test('sync: six devices pushing at once all land (append-only batches)', async () => {
    const base = Date.now();
    const jobs = [];
    for (let i = 0; i < 12; i++) jobs.push(doSync(ada.token, { progress: [{ k: `book-${i}`, percent: i / 10, updatedAt: base + i }] }));
    const results = await Promise.all(jobs);
    assert.ok(results.every((r) => r.status === 200), results.map((r) => r.status).join(','));
    const all = await doSync(ada.token);
    assert.equal(all.body.rows.progress.length, 12);
  });

  await test('sync: old batches fold into a snapshot without losing rows; devices with old cursors still catch up', async () => {
    const { kv } = await import('../../netlify/lib/accounts.mjs');
    const { batchName } = await import('../../netlify/functions/sync.mjs');
    const store = kv('mavis-data');
    const old = Date.now() - 60 * 60_000;
    const firstName = batchName(old - 1000);
    for (let i = 0; i < 45; i++) {
      await store.setJSON(`${ada.id}/vocab/b/${batchName(old + i * 1000)}`, { rows: [{ k: `w${i % 30}`, word: `word ${i}`, updatedAt: old + i * 1000 }] });
    }
    const r = await doSync(ada.token);
    assert.equal(r.body.rows.vocab.length, 30);
    assert.equal(r.body.rows.vocab.find((x) => x.k === 'w0').word, 'word 30', 'newest version of each word');
    const { blobs } = await store.list({ prefix: `${ada.id}/vocab/` });
    assert.ok(blobs.some((b) => b.key.includes('/s/')), 'a snapshot was written');
    assert.ok(blobs.filter((b) => b.key.includes('/b/')).length < 45, 'old batches were removed');
    const fromOld = await doSync(ada.token, {}, { vocab: firstName });
    assert.equal(fromOld.body.rows.vocab.length, 30, 'a long-offline device gets the snapshot');
    const fresh = await doSync(ada.token, { vocab: [{ k: 'w1', word: 'newer', updatedAt: Date.now() }] }, { vocab: r.body.cursors.vocab });
    assert.equal(fresh.body.rows.vocab.find((x) => x.k === 'w1').word, 'newer');
  });

  await test('another account sees none of this user’s data', async () => {
    const g = await act('signup', { email: 'grace@example.test', password: 'grace-hopper-1' });
    const r = await doSync(g.body.token);
    for (const rows of Object.values(r.body.rows)) assert.equal(rows.length, 0);
  });

  await test('recovery code resets the password, rotates the code, and signs out old sessions', async () => {
    const bad = await act('recover', { email: 'ada@example.test', recoveryCode: 'AAAA-BBBB-CCCC-DDDD', password: 'brand-new-pass-2' });
    assert.equal(bad.status, 401);
    const r = await act('recover', { email: 'ada@example.test', recoveryCode: ada.recoveryCode.toLowerCase().replace(/-/g, ' '), password: 'brand-new-pass-2' });
    assert.equal(r.status, 200);
    assert.notEqual(r.body.recoveryCode, ada.recoveryCode);
    assert.equal((await act('me', undefined, ada.token)).status, 401, 'old session revoked');
    assert.equal((await doSync(ada.token)).status, 401);
    assert.equal((await act('signin', { email: 'ada@example.test', password: 'correct-horse-9' })).status, 401);
    const s = await act('signin', { email: 'ada@example.test', password: 'brand-new-pass-2' });
    assert.equal(s.status, 200);
    const reuse = await act('recover', { email: 'ada@example.test', recoveryCode: ada.recoveryCode, password: 'another-one-3' });
    assert.equal(reuse.status, 401, 'old recovery code no longer works');
    ada = { ...ada, token: s.body.token, recoveryCode: r.body.recoveryCode };
  });

  await test('change password and sign out everywhere revoke other sessions', async () => {
    const other = (await act('signin', { email: 'ada@example.test', password: 'brand-new-pass-2' })).body.token;
    const c = await act('password', { currentPassword: 'brand-new-pass-2', password: 'third-pass-word-4' }, ada.token);
    assert.equal(c.status, 200);
    assert.equal((await act('me', undefined, other)).status, 401);
    assert.equal((await act('me', undefined, c.body.token)).status, 200);
    assert.equal((await act('signout-all', {}, c.body.token)).status, 200);
    assert.equal((await act('me', undefined, c.body.token)).status, 401);
    ada.token = (await act('signin', { email: 'ada@example.test', password: 'third-pass-word-4' })).body.token;
  });

  await test('delete account needs the password and removes the user and their data', async () => {
    assert.equal((await act('delete', { password: 'wrong-pass-1' }, ada.token)).status, 401);
    assert.equal((await act('delete', { password: 'third-pass-word-4' }, ada.token)).status, 200);
    assert.equal((await act('signin', { email: 'ada@example.test', password: 'third-pass-word-4' })).status, 401);
    const again = await act('signup', { email: 'ada@example.test', password: 'fresh-start-5' });
    assert.equal(again.status, 200, 'email is free again');
    const r = await doSync(again.body.token);
    for (const rows of Object.values(r.body.rows)) assert.equal(rows.length, 0, 'no data left behind');
  });

  await test('cross-site posts and non-JSON bodies are refused', async () => {
    const res = await account(new Request('https://mavis.test/api/account/signin', { method: 'POST', headers: { 'content-type': 'application/json', origin: 'https://evil.example' }, body: '{}' }), ctx('signin'));
    assert.equal(res.status, 403);
    const res2 = await account(new Request('https://mavis.test/api/account/signin', { method: 'POST', headers: { 'content-type': 'text/plain' }, body: 'x' }), ctx('signin'));
    assert.equal(res2.status, 415);
    assert.equal((await act('nope', {})).status, 404);
  });

  console.log(`\n${passed} account and sync checks passed.`);
} finally {
  await server.stop();
  rmSync(dir, { recursive: true, force: true });
}
