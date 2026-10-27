import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHmac } from 'node:crypto';
import { BlobsServer } from '@netlify/blobs/server';

const dir = mkdtempSync(join(tmpdir(), 'mavis-subs-'));
const server = new BlobsServer({ directory: dir, token: 'local-token', port: 0 });
const put = server.put.bind(server), locks = new Map();
server.put = async (req) => {
  const key = new URL(req.url).pathname;
  const previous = locks.get(key) || Promise.resolve();
  const next = previous.then(() => put(req));
  const tail = next.catch(() => {});
  locks.set(key, tail);
  try { return await next; } finally { if (locks.get(key) === tail) locks.delete(key); }
};
const { port } = await server.start();
process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
process.env.MAVIS_BLOBS_TOKEN = 'local-token';
process.env.AUTH_SECRET = 'subs-test-secret-'.padEnd(48, 'x');
process.env.MAVIS_ACCESS_CODE = 'subs-owner-test';
process.env.STUDIO_URL = 'https://studio.example.test/gateway';
process.env.STUDIO_SHARED_SECRET = 'studio-test-secret-not-real';
const realFetch = globalThis.fetch;
globalThis.__mavisRealFetch = realFetch;
let studioMode = 'ok', serial = 0;
const jobs = new Map(), studioCalls = [], aiCalls = [];
const reply = (body, status = 200) => Response.json(body, { status });
globalThis.fetch = async (url, opts = {}) => {
  const u = String(url);
  if (u.startsWith(process.env.MAVIS_BLOBS_URL)) return realFetch(url, opts);
  if (u.startsWith(process.env.STUDIO_URL)) {
    studioCalls.push({ url: u, opts });
    if (studioMode === 'throw') throw new Error(process.env.STUDIO_SHARED_SECRET);
    if (studioMode === '500') return reply({ error: process.env.STUDIO_SHARED_SECRET }, 500);
    if (studioMode === 'redirect') return new Response(null, { status: 302, headers: { location: 'https://evil.example.test' } });
    if (studioMode === 'text') return new Response('not json');
    if (studioMode === 'malformed') return new Response('{', { headers: { 'content-type': 'application/json' } });
    if (studioMode === 'huge') return reply({ junk: 'x'.repeat(256001) });
    if (studioMode === 'secret') return reply({ id: 'j1', status: 'ready', title: process.env.STUDIO_SHARED_SECRET });
    if (studioMode === 'escaped-secret') return new Response(JSON.stringify({ id: 'j1', status: 'ready', title: process.env.STUDIO_SHARED_SECRET }).replaceAll('s', '\\u0073'), { headers: { 'content-type': 'application/json' } });
    if (studioMode === '422') return reply({ detail: 'Please\u0000 choose a source.\n' + 'x'.repeat(500) }, 422);
    if (studioMode === '429') return reply({ detail: 'Busy' }, 429);
    if (studioMode === 'duplicate') return reply({ id: 'j1', status: 'queued' }, 202);
    if (studioMode === 'wrong-create-status') return reply({ id: 'wrong', status: 'queued' });
    if (studioMode === 'accepted-malformed') return new Response('{', { status: 202, headers: { 'content-type': 'application/json' } });
    const parsed = new URL(u), parts = parsed.pathname.split('/');
    if (parts.at(-1) === 'audio') {
      const id = parts.at(-2), uid = parsed.searchParams.get('uid');
      if (!verifyAudio(process.env.STUDIO_SHARED_SECRET, id, parsed.searchParams.get('exp'), uid, parsed.searchParams.get('sig'))) return reply({ detail: 'Valid studio authorization required' }, 401);
      if (!jobs.has(id) || (uid !== 'owner' && jobs.get(id).uid !== uid)) return reply({ detail: 'Podcast not found' }, 404);
      if (jobs.get(id).status !== 'ready') return reply({ detail: 'Podcast audio is not ready' }, 409);
      return new Response(new Uint8Array([1, 2, 3]), { headers: { 'content-type': 'audio/mpeg' } });
    }
    const id = parts.at(-1);
    if (id === 'podcasts' && opts.method !== 'POST') {
      const items = [...jobs.values()].filter((j) => j.uid === opts.headers['X-Mavis-User']).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 100).map(({ transcript, ...j }) => j);
      return reply({ items });
    }
    if (opts.method === 'POST') {
      const input = JSON.parse(opts.body);
      const j = { id: `j${++serial}`, status: 'queued', progress: 0, stage: 'Queued', engine: input.engine === 'auto' ? 'local' : input.engine, title: input.title, kind: input.kind, style: input.style, createdAt: new Date().toISOString(), sources: [{ title: 'Source', url: 'https://example.test/source', internal: 'discarded' }], uid: opts.headers['X-Mavis-User'], private: 'discarded', transcript: 'Owner transcript' };
      jobs.set(j.id, j);
      return reply({ id: j.id, status: j.status }, 202);
    }
    if (!jobs.has(id) || jobs.get(id).uid !== opts.headers['X-Mavis-User']) return reply({}, 404);
    if (opts.method === 'DELETE') { jobs.delete(id); return new Response(null, { status: 204 }); }
    return reply(jobs.get(id));
  }
  if (u.includes('api.openai.com')) {
    aiCalls.push(JSON.parse(opts.body));
    return reply({ choices: [{ message: { content: '{"meaning":"A gentle walk","example":"We amble home.","questions":["Why?"]}' } }] });
  }
  if (u.includes('api.fish.audio/v1/tts')) return new Response(new Uint8Array([1, 2, 3]), { headers: { 'content-type': 'audio/mpeg' } });
  if (u.includes('api.edenai.run')) return reply({ answers: { c0: { score: 4 } } });
  throw new Error('Unexpected external fetch');
};
const account = (await import('../../netlify/functions/account.mjs')).default;
const subscription = (await import('../../netlify/functions/subscription.mjs')).default;
const podcast = (await import('../../netlify/functions/podcast.mjs')).default;
const features = (await import('../../netlify/functions/features.mjs')).default;
const assistant = (await import('../../netlify/functions/assistant.mjs')).default;
const tts = (await import('../../netlify/functions/tts.mjs')).default;
const rank = (await import('../../netlify/functions/rank.mjs')).default;
const study = (await import('../../netlify/functions/study.mjs')).default;
const voices = (await import('../../netlify/functions/voices.mjs')).default;
const groups = (await import('../../netlify/functions/groups.mjs')).default;
const { createUser, signToken, getUser, updateUser, kv } = await import('../../netlify/lib/accounts.mjs');
const { requireAccess, chargeAccess } = await import('../../netlify/lib/access.mjs');
const { transition, getSub, subStore, consume, usage, COOLDOWN_MS, DEFAULT_CAPS } = await import('../../netlify/lib/subs.mjs');
const { signAudio, verifyAudio, validateCreate, studioJson, reservePodcastQuota, settlePodcastQuota } = await import('../../netlify/lib/podcast.mjs');
let ip = 0, passed = 0;
const owner = { 'x-mavis-access': process.env.MAVIS_ACCESS_CODE };
function req(path, method = 'GET', body, token, extra = {}) {
  return new Request(`https://mavis.test${path}`, { method, headers: { ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...(token ? { authorization: `Bearer ${token}` } : {}), ...extra }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
}
async function call(fn, path, method = 'GET', body, token, extra = {}) {
  const res = await fn(req(path, method, body, token, extra), { ip: `subs-${++ip}` });
  const text = await res.text();
  assert.ok(!text.includes(process.env.STUDIO_SHARED_SECRET));
  return { status: res.status, body: JSON.parse(text), headers: res.headers };
}
const sub = (action, body, token, extra) => call(subscription, `/api/subscription/${action}`, body === undefined ? 'GET' : 'POST', body, token, extra);
const admin = (action, body) => sub(`admin/${action}`, body, null, owner);
const pod = (action, method, body, token, extra) => call(podcast, `/api/podcast/${action}`, method, body, token, extra);
const auth = (token, extra) => req('/api/assistant', 'GET', undefined, token, extra);
const access = async (token, extra) => requireAccess(auth(token, extra), { feature: 'assistant' });
const make = async (email) => {
  const { user } = await createUser(email, 'correct-horse-9');
  return { user, token: signToken(user) };
};
async function test(name, fn) { await fn(); passed++; console.log(`  ✓ ${name}`); }

try {
  const a = await make('subscriber-a@example.test'), b = await make('subscriber-b@example.test');
  await test('access: owner bypass is unlimited without auth, account store, or AUTH_SECRET', async () => {
    const old = process.env.AUTH_SECRET;
    delete process.env.AUTH_SECRET;
    const ctx = await access('garbage', owner);
    assert.equal(ctx.owner, true);
    assert.equal(await chargeAccess(ctx), null);
    process.env.AUTH_SECRET = old;
  });
  await test('access: no auth and garbage tokens get truthful 401 codes', async () => {
    for (const token of [null, 'garbage', `${a.token}.extra`]) {
      const out = await access(token);
      assert.equal(out.status, 401);
      assert.equal((await out.json()).code, 'sign_in_required');
    }
  });
  await test('access: correctly signed expired tokens are refused', async () => {
    const p = Buffer.from(JSON.stringify({ u: a.user.id, v: 0, i: 0, e: 1 })).toString('base64url');
    const t = `v1.${p}.${createHmac('sha256', process.env.AUTH_SECRET).update(p).digest('base64url')}`;
    assert.equal((await access(t)).status, 401);
  });
  await test('unapproved fresh signup never inherits an email someone would have whitelisted', async () => {
    const out = await call(account, '/api/account/signup', 'POST', { email: 'jermaine.hawthorne@gmail.com', password: 'correct-horse-9' });
    assert.equal(out.status, 200);
    const denied = await access(out.body.token);
    assert.equal(denied.status, 403);
    assert.deepEqual((await denied.json()).state, 'none');
  });
  await test('request is idempotent and audit contains no message text', async () => {
    const first = await sub('request', { message: 'private message not for logs' }, a.token);
    assert.equal(first.status, 201);
    const again = await sub('request', { message: 'different message' }, a.token);
    assert.equal(again.status, 200);
    assert.deepEqual(again.body, first.body);
    const logs = await subStore().list({ prefix: 'log/' });
    assert.equal(logs.blobs.length, 1);
    const log = await subStore().get(logs.blobs[0].key, { type: 'json' });
    assert.deepEqual(Object.keys(log).sort(), ['action', 'at', 'by', 'userId']);
    assert.ok(!JSON.stringify(log).includes('private message'));
    const response = await access(a.token);
    assert.equal((await response.json()).message, 'Your request is waiting for approval.');
  });
  await test('admin rejects missing, wrong, or subscriber-only credentials on every action', async () => {
    for (const action of ['list', 'approve', 'deny', 'revoke', 'add', 'create-account']) {
      for (const extra of [{}, { 'x-mavis-access': 'wrong' }]) {
        const out = await sub(`admin/${action}`, action === 'list' ? undefined : { email: a.user.email }, a.token, extra);
        assert.equal(out.status, 401);
      }
    }
  });
  await test('parallel approve and deny have exactly one winner and one audit entry', async () => {
    const before = (await subStore().list({ prefix: 'log/' })).blobs.length;
    const out = await Promise.all([admin('approve', { userId: a.user.id }), admin('deny', { userId: a.user.id })]);
    assert.deepEqual(out.map((x) => x.status).sort(), [200, 409]);
    assert.equal((await subStore().list({ prefix: 'log/' })).blobs.length, before + 1);
  });
  await test('denial cooldown expires after seven days; illegal transitions fail', async () => {
    await transition(b.user, 'request', { now: 1000 });
    await transition(b.user, 'deny', { now: 2000 });
    await assert.rejects(transition(b.user, 'request', { now: 2001 }), (e) => e.response.status === 429);
    await assert.rejects(transition(b.user, 'approve'), (e) => e.response.status === 409);
    await transition(b.user, 'request', { now: 2000 + COOLDOWN_MS });
    assert.equal((await getSub(b.user.id)).state, 'pending');
    const c = await make('denied-now@example.test');
    await transition(c.user, 'request'); await transition(c.user, 'deny');
    const out = await sub('request', {}, c.token);
    assert.equal(out.status, 429); assert.ok(out.body.retryAfter > 0);
  });
  await test('access matrix: each state, active expiry, malformed expiry, no state', async () => {
    const s = subStore();
    for (const state of ['pending', 'denied', 'revoked', 'active']) {
      await s.setJSON(`s/${a.user.id}`, { userId: a.user.id, state, expiresAt: null });
      const out = await access(a.token);
      if (state === 'active') assert.equal(out.user.id, a.user.id);
      else { assert.equal(out.status, 403); assert.equal((await out.json()).state, state); }
    }
    for (const expiresAt of [1, 'invalid']) {
      await s.setJSON(`s/${a.user.id}`, { state: 'active', expiresAt });
      assert.equal((await access(a.token)).status, 403);
    }
    await transition(a.user, 'add'); await transition(b.user, 'add');
  });
  await test('revoke applies on the next request; revoked can request or be approved', async () => {
    assert.equal((await access(a.token)).owner, false);
    assert.equal((await admin('revoke', { email: a.user.email })).status, 200);
    assert.equal((await access(a.token)).status, 403);
    await transition(a.user, 'approve');
    await transition(a.user, 'revoke'); await transition(a.user, 'request');
    await transition(a.user, 'approve');
  });
  await test('tokenVersion bump invalidates subscriber access immediately', async () => {
    await updateUser(a.user.id, (u) => ({ tokenVersion: u.tokenVersion + 1 }));
    assert.equal((await access(a.token)).status, 401);
    a.user = await getUser(a.user.id); a.token = signToken(a.user);
  });
  await test('account and subscription store errors fail closed with 503, owner bypass survives', async () => {
    globalThis.__mavisRealFetch = async () => { throw new Error('store down'); };
    assert.equal((await access(a.token)).status, 503);
    assert.equal((await access(null, owner)).owner, true);
    globalThis.__mavisRealFetch = async (url, opts) => {
      if (String(url).includes('mavis-subs')) throw new Error('sub store down');
      return realFetch(url, opts);
    };
    assert.equal((await access(a.token)).status, 503);
    globalThis.__mavisRealFetch = realFetch;
  });
  await test('request endpoint applies durable five-per-hour per-user rate limit', async () => {
    const c = await make('request-limit@example.test');
    for (let i = 0; i < 5; i++) assert.ok([200, 201].includes((await sub('request', {}, c.token)).status));
    const out = await sub('request', {}, c.token);
    assert.equal(out.status, 429); assert.equal(out.body.code, 'request_limit');
  });
  await test('status is private and returns today vs caps; admin pagination filters records', async () => {
    const status = await sub('status', undefined, a.token);
    assert.equal(status.status, 200);
    assert.equal(status.body.state, 'active');
    assert.equal(status.body.usage.assistant.cap, 150);
    assert.equal(status.body.signedIn, true); assert.equal(status.body.podcastEnabled, true);
    const studio = process.env.STUDIO_URL; delete process.env.STUDIO_URL;
    assert.equal((await sub('status', undefined, a.token)).body.podcastEnabled, false);
    process.env.STUDIO_URL = studio;
    assert.ok(!JSON.stringify(status.body).includes(b.user.email));
    const page = await admin('list?state=all&limit=1');
    assert.equal(page.status, 200); assert.equal(page.body.items.length, 1); assert.ok(page.body.cursor);
    const next = await admin(`list?state=all&limit=1&cursor=${encodeURIComponent(page.body.cursor)}`);
    assert.notEqual(next.body.items[0].userId, page.body.items[0].userId);
    assert.equal((await admin('list?state=bogus')).status, 400);
  });
  await test('manual add never pre-approves missing accounts; existing-account add accepts plan', async () => {
    const out = await admin('add', { email: 'unregistered@example.test' });
    assert.equal(out.status, 404); assert.match(out.body.message, /existing account/);
    assert.equal((await admin('add', { email: a.user.email, plan: 'family' })).body.plan, 'family');
    assert.equal((await admin('add', { email: a.user.email, plan: 'invented' })).status, 400);
  });
  await test('create-account returns working sign-in and recovery credentials once, never logs secrets', async () => {
    const logs = [], oldLog = console.log, oldError = console.error;
    console.log = (...x) => logs.push(x.join(' ')); console.error = (...x) => logs.push(x.join(' '));
    let created;
    try {
      created = await admin('create-account', { email: 'created@example.test', plan: 'family' });
      assert.equal(created.status, 201);
      assert.ok(created.body.tempPassword.length >= 32);
      const signin = await call(account, '/api/account/signin', 'POST', { email: created.body.email, password: created.body.tempPassword });
      assert.equal(signin.status, 200);
      assert.equal((await access(signin.body.token)).sub.plan, 'family');
      const recovered = await call(account, '/api/account/recover', 'POST', { email: created.body.email, recoveryCode: created.body.recoveryCode, password: 'new-password-9' });
      assert.equal(recovered.status, 200);
      const stored = await getUser(signin.body.user.id);
      assert.ok(!JSON.stringify(stored).includes(created.body.tempPassword));
      assert.equal((await admin('create-account', { email: created.body.email })).status, 409);
      const listed = await admin('list?state=all');
      assert.ok(!JSON.stringify(listed).includes(created.body.tempPassword));
    } finally { console.log = oldLog; console.error = oldError; }
    assert.ok(!logs.join(' ').includes(created.body.tempPassword));
    assert.ok(!logs.join(' ').includes(created.body.recoveryCode));
  });
  await test('CAS daily counters stop parallel requests exactly at the configured cap', async () => {
    process.env.SUB_CAP_ASSISTANT = '7';
    const now = Date.UTC(2027, 1, 2, 12);
    const out = await Promise.all(Array.from({ length: 20 }, () => consume(a.user.id, 'assistant', now)));
    assert.equal(out.filter((x) => x === null).length, 7);
    assert.equal(out.filter((x) => x?.status === 429).length, 13);
    assert.equal((await usage(a.user.id, now)).assistant.used, 7);
    delete process.env.SUB_CAP_ASSISTANT;
  });
  await test('caps reset at UTC rollover, every feature is capped, owner never consumes', async () => {
    const t = Date.UTC(2028, 0, 1, 23, 59, 59);
    for (const feature of Object.keys(DEFAULT_CAPS)) {
      const name = `SUB_CAP_${feature.toUpperCase()}`;
      process.env[name] = '1';
      assert.equal(await consume(b.user.id, feature, t), null);
      const denied = await consume(b.user.id, feature, t);
      assert.equal(denied.status, 429); assert.equal((await denied.json()).resetsAt, '2028-01-02T00:00:00.000Z');
      assert.equal(await consume(b.user.id, feature, t + 1000), null);
      assert.equal(await chargeAccess({ owner: true, feature }), null);
      delete process.env[name];
    }
  });
  await test('kids headers confer no access: anonymous 401, unapproved 403, approved retains kids rules', async () => {
    const c = await make('kids-unapproved@example.test');
    const extra = { 'x-mavis-kids': '1' };
    assert.equal((await access(null, extra)).status, 401);
    assert.equal((await access(c.token, extra)).status, 403);
    process.env.LLM_PROVIDER = 'openai'; process.env.LLM_API_KEY = 'test-key';
    process.env.LLM_BASE_URL = 'https://api.openai.com/v1';
    const out = await call(assistant, '/api/assistant', 'POST', { messages: [{ role: 'user', content: 'What does this mean?' }] }, a.token, extra);
    assert.equal(out.status, 200); assert.match(aiCalls.at(-1).messages[0].content, /kids mode/);
    assert.equal((await call(assistant, '/api/assistant', 'POST', { messages: [{ role: 'user', content: 'Hello' }] }, null, extra)).status, 401);
  });
  await test('AI usage endpoints accept subscribers; voices remain owner-only; study polling is gated', async () => {
    process.env.TTS_PROVIDER = 'fish'; process.env.FISH_AUDIO_API_KEY = 'test-fish';
    const audio = await tts(req('/api/tts', 'POST', { text: 'hello' }, a.token), { ip: `tts-${++ip}` });
    assert.equal(audio.status, 200);
    delete process.env.TYPESAFE_API_KEY; process.env.EDENAI_API_KEY = 'test-eden';
    assert.equal((await call(rank, '/api/rank', 'POST', { candidates: [{ id: 'a', title: 'A' }] }, a.token)).status, 200);
    assert.equal((await call(study, '/api/study', 'POST', { task: 'word', word: 'amble' }, a.token)).status, 200);
    assert.equal((await call(study, '/api/study?picture=x', 'GET', undefined, a.token)).status, 400);
    assert.equal((await call(study, '/api/study?picture=x')).status, 401);
    assert.equal((await call(voices, '/api/voices', 'GET', undefined, a.token)).status, 401);
  });
  await test('group questions accept subscribed members and charge the study bucket', async () => {
    const group = await call(groups, '/api/groups', 'POST', { action: 'create', name: 'Bible', kind: 'bible', displayName: 'A' }, a.token);
    const before = (await usage(a.user.id)).study.used;
    const out = await call(groups, '/api/groups', 'POST', { action: 'questions', gid: group.body.group.id, text: 'Text '.repeat(60) }, a.token);
    assert.equal(out.status, 200); assert.equal((await usage(a.user.id)).study.used, before + 1);
  });
  await test('delete account removes subscription while retaining audit history', async () => {
    const c = await make('delete-sub@example.test'); await transition(c.user, 'add');
    assert.equal((await call(account, '/api/account/delete', 'POST', { password: 'correct-horse-9' }, c.token)).status, 200);
    assert.equal(await getSub(c.user.id), null);
    const rows = await Promise.all((await subStore().list({ prefix: 'log/' })).blobs.map((x) => subStore().get(x.key, { type: 'json' })));
    assert.ok(rows.some((x) => x.userId === c.user.id && x.action === 'account_deleted'));
  });
  await test('subscription input, methods, same-origin, and CORS are enforced', async () => {
    assert.equal((await sub('request', { message: 'x'.repeat(201) }, a.token)).status, 400);
    assert.equal((await sub('request', { message: [] }, a.token)).status, 400);
    assert.equal((await sub('request', {}, a.token, { origin: 'https://evil.test' })).status, 403);
    const out = await sub('status', undefined, a.token, { origin: 'https://localhost' });
    assert.equal(out.headers.get('access-control-allow-origin'), 'https://localhost');
    assert.equal((await sub('unknown', {}, a.token)).status, 404);
  });
  await test('subscriber access still works with absent or incorrect owner code; active request is idempotent', async () => {
    const old = process.env.MAVIS_ACCESS_CODE;
    delete process.env.MAVIS_ACCESS_CODE;
    assert.equal((await access(a.token)).owner, false);
    process.env.MAVIS_ACCESS_CODE = old;
    assert.equal((await access(a.token, { 'x-mavis-access': 'wrong' })).owner, false);
    const before = (await subStore().list({ prefix: 'log/' })).blobs.length;
    assert.equal((await sub('request', {}, a.token)).body.state, 'active');
    assert.equal((await subStore().list({ prefix: 'log/' })).blobs.length, before);
  });
  await test('parallel initial subscription requests produce one transition and audit event', async () => {
    const c = await make('parallel-requests@example.test');
    const before = (await subStore().list({ prefix: 'log/' })).blobs.length;
    const out = await Promise.all(Array.from({ length: 8 }, () => transition(c.user, 'request')));
    assert.equal(out.filter((x) => x.changed).length, 1);
    assert.equal((await subStore().list({ prefix: 'log/' })).blobs.length, before + 1);
  });
  await test('AI endpoint daily limits prevent all provider calls, while study GET does not consume', async () => {
    const before = aiCalls.length;
    for (const [fn, path, feature, body] of [[assistant, '/api/assistant', 'assistant', { messages: [{ role: 'user', content: 'Hi' }] }], [rank, '/api/rank', 'rank', { candidates: [{ id: 'a', title: 'A' }] }], [tts, '/api/tts', 'tts', { text: 'Hello' }], [study, '/api/study', 'study', { task: 'word', word: 'amble' }]]) {
      const name = `SUB_CAP_${feature.toUpperCase()}`;
      process.env[name] = '0';
      const out = await call(fn, path, 'POST', body, a.token);
      assert.equal(out.status, 429); assert.equal(out.body.code, 'daily_limit');
      delete process.env[name];
    }
    assert.equal(aiCalls.length, before);
    const beforeUsage = (await usage(a.user.id)).study.used;
    assert.equal((await call(study, '/api/study?picture=x', 'GET', undefined, a.token)).status, 400);
    assert.equal((await usage(a.user.id)).study.used, beforeUsage);
  });
  const base = { kind: 'topic', title: 'Test podcast', query: 'Volcanoes' };
  await test('podcast requires subscriber access even when the studio is unconfigured', async () => {
    assert.equal((await pod('list', 'GET')).status, 401);
    const old = process.env.STUDIO_URL; delete process.env.STUDIO_URL;
    const out = await pod('list', 'GET', undefined, a.token);
    assert.equal(out.status, 503); assert.equal(out.body.code, 'studio_not_configured');
    process.env.STUDIO_URL = old;
    const secret = process.env.STUDIO_SHARED_SECRET; delete process.env.STUDIO_SHARED_SECRET;
    assert.equal((await pod('list', 'GET', undefined, a.token)).status, 503);
    process.env.STUDIO_SHARED_SECRET = secret;
  });
  await test('podcast validation table covers kinds, sizes, types, URLs, required sources, unknown fields', async () => {
    for (const data of [null, [], {}, { ...base, kind: 'bad' }, { ...base, title: '' }, { ...base, query: 'x'.repeat(301) }, { ...base, extra: true }, { ...base, engine: [] }, { ...base, length: -1 }, { kind: 'chapter', title: 'T' }, { kind: 'chapter', title: 'T', text: 'x'.repeat(400001) }, { kind: 'article', title: 'T', url: 'http://example.test' }, { kind: 'article', title: 'T', url: 'https://user:pass@example.test' }, { kind: 'book', title: 'T', gutenbergId: 0 }, { kind: 'book', title: 'T' }]) {
      assert.throws(() => validateCreate(data), (e) => e.response.status === 400);
    }
    for (const data of [base, { kind: 'chapter', title: 'T', text: 'x'.repeat(400000) }, { kind: 'article', title: 'T', url: 'https://example.test/a' }, { kind: 'book', title: 'T', gutenbergId: '84' }, { kind: 'book', title: 'T', text: 'Book text' }]) assert.deepEqual(validateCreate(data), { ...data, style: 'deep-dive', length: 'default', language: 'en', engine: 'auto' });
    for (const field of ['style', 'length', 'engine', 'language']) {
      for (const value of ['unknown', '', null, 1]) assert.throws(() => validateCreate({ ...base, [field]: value }), (e) => e.response.status === 400);
    }
    for (const gutenbergId of [84, '0', '-1', '1.5', 'abc']) assert.throws(() => validateCreate({ kind: 'book', title: 'T', gutenbergId }), (e) => e.response.status === 400);
    for (const style of ['deep-dive', 'brief', 'debate', 'critique']) for (const length of ['short', 'default', 'long']) for (const engine of ['auto', 'notebooklm', 'local']) assert.equal(validateCreate({ ...base, style, length, engine }).engine, engine);
    assert.equal((await pod('create', 'POST', { ...base, query: 'x'.repeat(301) }, a.token)).status, 400);
  });
  await test('published studio schema: trimming, null sources, irrelevant fields, Gutenberg range, URL rules, and UTF-8 envelope', async () => {
    const normalized = validateCreate({ kind: ' topic ', title: ' Title ', query: ' Query ', style: ' brief ', length: ' short ', language: ' en ', engine: ' local ', text: null, url: null, gutenbergId: null });
    assert.deepEqual(normalized, { kind: 'topic', title: 'Title', query: 'Query', style: 'brief', length: 'short', language: 'en', engine: 'local', text: null, url: null, gutenbergId: null });
    assert.equal(validateCreate({ ...base, title: '\u{10400}'.repeat(300) }).title.length, 600);
    assert.equal(validateCreate({ ...base, text: 'Extra source', url: 'https://example.test/', gutenbergId: '9999999' }).gutenbergId, '9999999');
    assert.equal(validateCreate({ ...base, text: '', url: '', gutenbergId: '' }).query, base.query);
    assert.throws(() => validateCreate({ ...base, gutenbergId: '10000000' }), (e) => e.response.status === 400);
    const prefix = 'https://example.test/';
    assert.equal(validateCreate({ kind: 'article', title: 'T', url: prefix + 'x'.repeat(2048 - prefix.length) }).url.length, 2048);
    assert.throws(() => validateCreate({ kind: 'article', title: 'T', url: prefix + 'x'.repeat(2049 - prefix.length) }), (e) => e.response.status === 400);
    for (const url of ['https://127.0.0.1', 'https://8.8.8.8', 'https://[::1]', 'https://localhost', 'https://host.local', 'https://a.lan', 'https://host.internal', 'https://example.test:8080', 'https://@example.test', 'https://example..test', 'https://example.test\\@evil.test']) assert.throws(() => validateCreate({ kind: 'article', title: 'T', url }), (e) => e.response.status === 400);
    assert.equal(validateCreate({ kind: 'article', title: 'T', url: 'https://example.test:443' }).url, 'https://example.test:443');
    assert.equal(validateCreate({ kind: 'chapter', title: 'T', text: 'é'.repeat(200000) }).text.length, 200000);
    for (const text of ['é'.repeat(300000), 'x' + '\n'.repeat(225001) + 'x']) assert.throws(() => validateCreate({ kind: 'chapter', title: 'T', text }), (e) => e.response.status === 400);
  });
  let jobA;
  await test('podcast create forwards only validated JSON and correct private upstream headers', async () => {
    const out = await pod('create', 'POST', base, a.token);
    assert.equal(out.status, 201); jobA = out.body.id;
    const last = studioCalls.at(-1);
    assert.equal(last.opts.headers['X-Mavis-User'], a.user.id);
    assert.equal(last.opts.headers['X-Studio-Key'], process.env.STUDIO_SHARED_SECRET);
    assert.equal(last.opts.redirect, 'manual'); assert.equal(last.opts.credentials, 'omit');
    assert.deepEqual(JSON.parse(last.opts.body), { ...base, style: 'deep-dive', length: 'default', language: 'en', engine: 'auto' });
    assert.ok(last.opts.signal instanceof AbortSignal);
    assert.deepEqual(Object.keys(out.body).sort(), ['id', 'status']);
  });
  await test('podcast ownership isolation gives 404 without upstream calls for other users', async () => {
    const before = studioCalls.length;
    for (const [action, method] of [[`job/${jobA}`, 'GET'], [`job/${jobA}`, 'DELETE'], [`audio/${jobA}`, 'GET']]) {
      assert.equal((await pod(action, method, undefined, b.token)).status, 404);
    }
    assert.equal(studioCalls.length, before);
    assert.deepEqual((await pod('list', 'GET', undefined, b.token)).body.jobs, []);
    assert.equal((await pod(`job/${jobA}`, 'GET', undefined, a.token)).status, 200);
    assert.equal((await pod(`job/${jobA}`, 'GET', undefined, null, owner)).status, 200);
    assert.equal((await pod('list', 'GET', undefined, null, owner)).body.jobs.length, 1);
  });
  await test('parallel creates admit one daily job by default', async () => {
    const out = await Promise.all(Array.from({ length: 6 }, () => pod('create', 'POST', base, b.token)));
    assert.equal(out.filter((x) => x.status === 201).length, 1);
    assert.ok(out.filter((x) => x.status === 429).every((x) => x.body.code === 'daily_limit'));
  });
  await test('completed jobs remain charged and a per-user override raises the cap', async () => {
    for (const job of jobs.values()) if (job.uid === b.user.id) job.status = 'ready';
    await subStore().setJSON(`podlimit/${b.user.id}`, { limit: 2 });
    assert.equal((await pod('create', 'POST', base, b.token)).status, 201);
    for (const job of jobs.values()) if (job.uid === b.user.id) job.status = 'ready';
    const out = await pod('create', 'POST', base, b.token);
    assert.equal(out.status, 429); assert.equal(out.body.code, 'daily_limit'); assert.ok(out.body.resetsAt);
  });
  await test('owner has a separate default 30 daily limit independent of legacy caps', async () => {
    process.env.SUB_CAP_PODCAST = '0';
    for (let i = 0; i < 4; i++) assert.equal((await pod('create', 'POST', base, null, owner)).status, 201);
    delete process.env.SUB_CAP_PODCAST;
  });
  await test('exact studio lifecycle forwards allowlisted metadata, owner transcript, and releases terminal slots', async () => {
    for (const [i, status] of ['queued', 'sourcing', 'writing', 'generating', 'ready'].entries()) {
      Object.assign(jobs.get(jobA), { status, progress: i * 25, stage: status, engine: 'local', durationSec: 123 });
      const out = await pod(`job/${jobA}`, 'GET', undefined, a.token);
      assert.equal(out.status, 200); assert.equal(out.body.status, status);
      assert.equal(out.body.progress, i * 25); assert.equal(out.body.stage, status);
      assert.equal(out.body.engine, 'local'); assert.equal(out.body.durationSec, 123);
      assert.equal(out.body.transcript, 'Owner transcript'); assert.equal(out.body.kind, 'topic');
      assert.deepEqual(out.body.sources, [{ title: 'Source', url: 'https://example.test/source' }]);
      assert.ok(!Object.hasOwn(out.body, 'uid')); assert.ok(!Object.hasOwn(out.body, 'private'));
      const slots = (await subStore().get(`pod-active/${a.user.id}`, { type: 'json' })).slots;
      assert.equal(slots.includes(jobA), status !== 'ready');
    }
    const rogue = { ...jobs.get(jobA), id: 'unindexed', uid: a.user.id };
    jobs.set(rogue.id, rogue);
    const upstream = await studioJson({ base: process.env.STUDIO_URL, secret: process.env.STUDIO_SHARED_SECRET }, '', a.user.id);
    assert.ok(upstream.items.some((j) => j.id === rogue.id));
    assert.ok(upstream.items.every((j) => !Object.hasOwn(j, 'transcript')));
    const out = await pod('list', 'GET', undefined, a.token);
    assert.deepEqual(out.body.jobs.map((j) => j.id), [jobA]);
    assert.ok(!Object.hasOwn(out.body.jobs[0], 'transcript'));
  });
  await test('unknown and guessed upstream statuses fail with studio_bad_response', async () => {
    for (const status of ['pending', 'running', 'processing', 'completed', 'cancelled', 'canceled', 'deleted', 'surprise']) {
      jobs.get(jobA).status = status;
      const out = await pod(`job/${jobA}`, 'GET', undefined, a.token);
      assert.equal(out.status, 502); assert.equal(out.body.code, 'studio_bad_response');
    }
    jobs.get(jobA).status = 'ready';
  });
  await test('upstream ownership 404 remains 404 on indexed job, list, delete, and audio', async () => {
    jobs.get(jobA).uid = b.user.id;
    for (const [action, method] of [[`job/${jobA}`, 'GET'], [`job/${jobA}`, 'DELETE'], ['list', 'GET'], [`audio/${jobA}`, 'GET']]) assert.equal((await pod(action, method, undefined, a.token)).status, 404);
    jobs.get(jobA).uid = a.user.id;
  });
  await test('audio not ready returns friendly 409 without signing or fetching MP3', async () => {
    jobs.get(jobA).status = 'generating';
    const out = await pod(`audio/${jobA}`, 'GET', undefined, a.token);
    assert.equal(out.status, 409); assert.equal(out.body.code, 'audio_not_ready');
    assert.ok(!Object.hasOwn(out.body, 'url'));
    const exp = Math.floor(Date.now() / 1000) + 600;
    const signed = new URL(`${process.env.STUDIO_URL}/v1/podcasts/${jobA}/audio`);
    signed.search = new URLSearchParams({ exp: String(exp), uid: a.user.id, sig: signAudio(process.env.STUDIO_SHARED_SECRET, jobA, exp, a.user.id) }).toString();
    assert.equal((await fetch(signed)).status, 409);
    jobs.get(jobA).status = 'ready';
  });
  await test('audio URL is studio-hosted, ten-minute signed, and never proxies bytes', async () => {
    const before = studioCalls.length;
    const out = await pod(`audio/${jobA}`, 'GET', undefined, a.token);
    assert.equal(out.status, 200); assert.equal(studioCalls.length, before + 1);
    assert.ok(!studioCalls.at(-1).url.includes('/audio'));
    const u = new URL(out.body.url);
    assert.equal(u.host, 'studio.example.test'); assert.match(u.pathname, /\/v1\/podcasts\/j\d+\/audio$/);
    assert.equal(u.searchParams.get('uid'), a.user.id);
    assert.equal(verifyAudio(process.env.STUDIO_SHARED_SECRET, jobA, u.searchParams.get('exp'), a.user.id, u.searchParams.get('sig')), true);
    assert.equal(Date.parse(out.body.expiresAt), Number(u.searchParams.get('exp')) * 1000);
  });
  await test('audio pure signing matches an independent HMAC vector and rejects tampering/expiry/wrong uid', async () => {
    const now = 1800000000000, exp = 1800000600, id = 'job-vector', uid = 'owner', secret = 'vector-secret';
    const want = '92eff03c6dda51ba238460e571ec6c47632852c48812f32afbc67167c3dde4ce';
    assert.equal(createHmac('sha256', secret).update('job-vector.1800000600.owner').digest('hex'), want);
    const sig = signAudio(secret, id, exp, uid);
    assert.equal(sig, want); assert.equal(verifyAudio(secret, id, exp, uid, sig, now), true);
    assert.equal(verifyAudio(secret, 'other', exp, uid, sig, now), false);
    assert.equal(verifyAudio(secret, id, exp, a.user.id, sig, now), false);
    assert.equal(verifyAudio(secret, id, exp, uid, '0'.repeat(64), now), false);
    assert.equal(verifyAudio(secret, id, exp, uid, sig, exp * 1000), false);
    assert.equal(verifyAudio(secret, id, exp + 1, uid, signAudio(secret, id, exp + 1, uid), now), false);
    assert.equal(verifyAudio(secret, '../bad', exp, uid, sig, now), false);
    for (const badUid of ['OWNER', '-'.repeat(36), '00000000-0000-0000-0000-000000000000']) assert.equal(verifyAudio(secret, id, exp, badUid, signAudio(secret, id, exp, badUid), now), false);
    const upperUid = a.user.id.toUpperCase();
    assert.equal(verifyAudio(secret, id, exp, upperUid, signAudio(secret, id, exp, upperUid), now), true);
  });
  await test('studio failures, redirects, invalid JSON, oversize responses, secret echoes map to friendly 502', async () => {
    for (const mode of ['throw', '500', 'redirect', 'text', 'malformed', 'huge', 'secret', 'escaped-secret']) {
      studioMode = mode;
      const out = await pod(`job/${jobA}`, 'GET', undefined, a.token);
      assert.equal(out.status, 502, mode); assert.equal(out.body.code, 'studio_unavailable');
    }
    studioMode = 'ok';
    await assert.rejects(studioJson({ base: process.env.STUDIO_URL, secret: process.env.STUDIO_SHARED_SECRET, deadline: Date.now() - 1 }, '', 'owner'), (e) => e.response.status === 502);
  });
  await test('podcast delete confirms cancellation, drops index, and frees its active slot', async () => {
    const out = await pod(`job/${jobA}`, 'DELETE', undefined, a.token);
    assert.equal(out.status, 200); assert.deepEqual(out.body, { id: jobA, status: 'deleted' });
    assert.equal((await pod(`job/${jobA}`, 'GET', undefined, a.token)).status, 404);
    assert.ok(!(await subStore().get(`pod-active/${a.user.id}`, { type: 'json' })).slots.includes(jobA));
  });
  await test('studio duplicate IDs cannot transfer another subscriber job into a new ownership index', async () => {
    const c = await make('pod-duplicate@example.test'); await transition(c.user, 'add');
    studioMode = 'duplicate';
    assert.equal((await pod('create', 'POST', base, c.token)).status, 502);
    studioMode = 'ok';
    assert.equal((await pod('job/j1', 'GET', undefined, c.token)).status, 404);
    assert.equal(await subStore().get(`pod/${c.user.id}/j1`, { type: 'json' }), null);
  });
  await test('422 detail is sanitized, 429 has retry hint, and rejected creates do not charge or occupy slots', async () => {
    const c = await make('pod-rejections@example.test'); await transition(c.user, 'add');
    for (const [mode, status, code] of [['422', 400, 'bad_request'], ['429', 429, 'studio_busy'], ['wrong-create-status', 502, 'studio_unavailable']]) {
      studioMode = mode;
      const out = await pod('create', 'POST', base, c.token);
      assert.equal(out.status, status); assert.equal(out.body.code, code);
      if (mode === '422') { assert.match(out.body.message, /^Please choose a source/); assert.ok(out.body.message.length <= 300); assert.ok(!/[\u0000-\u001f\u007f-\u009f]/.test(out.body.message)); }
      if (mode === '429') assert.ok(out.body.retryAfter > 0);
      assert.equal((await usage(c.user.id)).podcast.used, 0);
      assert.deepEqual((await subStore().get(`pod-active/${c.user.id}`, { type: 'json' })).slots, []);
    }
    studioMode = 'ok';
    const before = studioCalls.length;
    assert.equal((await pod('create', 'POST', { ...base, style: 'unknown' }, c.token)).status, 400);
    assert.equal(studioCalls.length, before); assert.equal((await usage(c.user.id)).podcast.used, 0);
    assert.equal((await pod('create', 'POST', base, c.token)).status, 201);
    assert.equal((await usage(c.user.id)).podcast.used, 1);
  });
  await test('ambiguous upstream failures do not charge; 202 charges even with malformed response', async () => {
    for (const mode of ['500', 'throw', 'malformed', 'accepted-malformed']) {
      const c = await make(`pod-failure-${mode}@example.test`); await transition(c.user, 'add');
      studioMode = mode;
      assert.equal((await pod('create', 'POST', base, c.token)).status, 502);
      assert.equal((await usage(c.user.id)).podcast.used, mode === 'accepted-malformed' ? 1 : 0);
    }
    studioMode = 'ok';
  });
  await test('concurrent daily reservations stop dispatch at cap and failed/list terminal observations free slots', async () => {
    const c = await make('pod-daily-race@example.test'); await transition(c.user, 'add');
    process.env.SUB_CAP_PODCAST = '1';
    const before = studioCalls.filter((x) => x.opts.method === 'POST').length;
    const out = await Promise.all(Array.from({ length: 8 }, () => pod('create', 'POST', base, c.token)));
    assert.equal(out.filter((x) => x.status === 201).length, 1);
    assert.equal(studioCalls.filter((x) => x.opts.method === 'POST').length, before + 1);
    assert.equal((await usage(c.user.id)).podcast.used, 1);
    delete process.env.SUB_CAP_PODCAST;
    const id = out.find((x) => x.status === 201).body.id;
    Object.assign(jobs.get(id), { status: 'failed', error: 'Generation failed.' });
    const list = await pod('list', 'GET', undefined, c.token);
    assert.equal(list.body.jobs[0].error, 'Generation failed.');
    assert.deepEqual((await subStore().get(`pod-active/${c.user.id}`, { type: 'json' })).slots, []);
    await subStore().setJSON(`podlimit/${c.user.id}`, { limit: 2 });
    const next = await pod('create', 'POST', base, c.token);
    assert.equal(next.status, 201);
    const deleted = await pod(`job/${next.body.id}`, 'DELETE', undefined, c.token);
    assert.deepEqual(deleted.body, { id: next.body.id, status: 'deleted' });
    assert.deepEqual((await subStore().get(`pod-active/${c.user.id}`, { type: 'json' })).slots, []);
    assert.equal((await usage(c.user.id)).podcast.used, 2);
  });
  await test('quota reservations are uncharged, CAS-bounded, idempotently settled, and pinned to the UTC dispatch day', async () => {
    const c = await make('pod-reservation@example.test'), now = Date.UTC(2029, 0, 1, 23, 59, 59);
    process.env.SUB_CAP_PODCAST = '1';
    const first = await reservePodcastQuota(c.user.id, now);
    assert.equal((await usage(c.user.id, now)).podcast.used, 0);
    await assert.rejects(reservePodcastQuota(c.user.id, now), (e) => e.response.status === 429);
    await settlePodcastQuota(first, false); await settlePodcastQuota(first, false);
    assert.equal((await usage(c.user.id, now)).podcast.used, 0);
    const second = await reservePodcastQuota(c.user.id, now);
    await Promise.all([settlePodcastQuota(second, true), settlePodcastQuota(second, true)]);
    assert.equal((await usage(c.user.id, now)).podcast.used, 1);
    assert.equal((await usage(c.user.id, now + 1000)).podcast.used, 0);
    assert.deepEqual((await subStore().get(second.key, { type: 'json' })).podcastReserved, []);
    delete process.env.SUB_CAP_PODCAST;
  });
  await test('sanitizing upstream strings cannot reconstruct a secret in errors or public job metadata', async () => {
    const saved = globalThis.fetch;
    const hidden = process.env.STUDIO_SHARED_SECRET.split('').join('\u0000');
    try {
      for (const [status, body] of [[422, { detail: hidden }], [200, { id: 'safe-id', status: 'ready', stage: hidden }]]) {
        globalThis.fetch = async () => reply(body, status);
        await assert.rejects(studioJson({ base: process.env.STUDIO_URL, secret: process.env.STUDIO_SHARED_SECRET }, '/safe-id', 'owner'), (e) => e.response.status === 502);
      }
    } finally { globalThis.fetch = saved; }
  });
  await test('upstream deadline aborts a stalled fetch and maps to studio_unavailable', async () => {
    const saved = globalThis.fetch;
    globalThis.fetch = async (url, opts) => new Promise((resolve, reject) => {
      opts.signal.addEventListener('abort', () => reject(new Error('Aborted')), { once: true });
    });
    try {
      await assert.rejects(studioJson({ base: process.env.STUDIO_URL, secret: process.env.STUDIO_SHARED_SECRET, deadline: Date.now() + 20 }, '', 'owner'), (e) => e.response.status === 502);
    } finally { globalThis.fetch = saved; }
  });
  await test('podcast same-origin, unsupported methods, invalid config, and CORS boundaries', async () => {
    assert.equal((await pod('create', 'POST', base, a.token, { origin: 'https://evil.test' })).status, 403);
    assert.equal((await pod('create', 'GET', undefined, a.token)).status, 405);
    assert.equal((await pod('list', 'POST', {}, a.token)).status, 405);
    const response = await podcast(req('/api/podcast/list', 'OPTIONS', undefined, null, { origin: 'https://localhost' }));
    assert.equal(response.status, 204); assert.match(response.headers.get('access-control-allow-methods'), /DELETE/);
    const old = process.env.STUDIO_URL;
    process.env.STUDIO_URL = 'http://studio.example.test';
    assert.equal((await pod('list', 'GET', undefined, a.token)).body.code, 'studio_not_configured');
    process.env.STUDIO_URL = old;
  });
  await test('features preserves owner semantics and toggles subscription/podcast flags without secrets', async () => {
    const out = await call(features, '/api/features', 'GET', undefined, null, owner);
    assert.equal(out.body.owner, true); assert.equal(out.body.subscriptions, true); assert.equal(out.body.podcast, true);
    delete process.env.STUDIO_URL;
    assert.equal((await call(features, '/api/features')).body.podcast, false);
  });
  console.log(`\n${passed} subscription and podcast checks passed.`);
} finally {
  globalThis.fetch = realFetch;
  delete globalThis.__mavisRealFetch;
  await server.stop();
  rmSync(dir, { recursive: true, force: true });
}
