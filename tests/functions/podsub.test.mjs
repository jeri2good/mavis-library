import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { BlobsServer } from '@netlify/blobs/server';
import { subStore, transition } from '../../netlify/lib/subs.mjs';
import { createUser, signToken } from '../../netlify/lib/accounts.mjs';
import { createPodcast, podcastUsage, reservePodcastQuota, settlePodcastQuota } from '../../netlify/lib/podcast.mjs';
import { localDate, nextRunAt } from '../../netlify/lib/podsub-time.mjs';
import { parseFeed, fetchFeed, publicAddress, feedUrl } from '../../netlify/lib/podsub-feed.mjs';
import { validatePodsub, savePodsub, runSubscription, runs, tick, acquireLock, releaseLock, keys } from '../../netlify/lib/podsub.mjs';
import api from '../../netlify/functions/podsub.mjs';
import { config as tickConfig } from '../../netlify/functions/podsub-tick.mjs';
const dir = mkdtempSync(join(tmpdir(), 'podsub-'));
const server = new BlobsServer({ directory: dir, token: 'local-token', port: 0 });
const put = server.put.bind(server), queues = new Map();
server.put = async req => {
  const key = new URL(req.url).pathname, previous = queues.get(key) || Promise.resolve();
  const next = previous.then(() => put(req)), tail = next.catch(() => {}); queues.set(key, tail);
  try { return await next; } finally { if (queues.get(key) === tail) queues.delete(key); }
};
const { port } = await server.start(), realFetch = globalThis.fetch;
const savedEnv = { ...process.env };
process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
process.env.MAVIS_BLOBS_TOKEN = 'local-token';
process.env.AUTH_SECRET = 'podsub-test-secret'.padEnd(48, 'x');
process.env.MAVIS_ACCESS_CODE = 'podsub-owner-test';
process.env.STUDIO_URL = 'https://studio.example.test';
process.env.STUDIO_SHARED_SECRET = 'podsub-studio-test-secret';
delete process.env.TELEGRAM_BOT_TOKEN;
globalThis.__mavisRealFetch = realFetch;
const calls = [], jobs = new Map();
let serial = 0, passed = 0;
globalThis.fetch = async (url, opts = {}) => {
  if (String(url).startsWith(process.env.MAVIS_BLOBS_URL)) return realFetch(url, opts);
  assert.ok(String(url).startsWith(process.env.STUDIO_URL), 'no live external requests');
  if (opts.method === 'POST') {
    const body = JSON.parse(opts.body); calls.push({ body, uid: opts.headers['X-Mavis-User'] });
    const job = { id: `ps${++serial}`, status: 'queued', title: body.title }; jobs.set(job.id, job);
    return Response.json(job, { status: 202 });
  }
  return Response.json(jobs.get(String(url).split('/').at(-1)));
};
const site = (id, more = {}) => ({ id, name: id, feedUrl: `https://example.com/${id}.xml`, siteUrl: 'https://example.com', mode: 'top', count: 1, delivery: 'separate', priority: 0, ...more });
const doc = (sites = [], more = {}) => ({ version: 1, sites, schedule: { enabled: true, readyBy: '08:00', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'], timezone: 'America/New_York' }, engine: 'notebooklm', rank: 'newest', keepDays: 7, notify: { telegram: false }, ...more });
const post = (id, time = 1) => ({ id, title: id, text: `Content for ${id}`, link: `https://example.com/${id}`, publishedAt: time });
const deps = { feed: async () => [post('one')], checkAccess: async () => true };
const run = (uid, more = {}) => runSubscription(uid, { ...deps, ...more });
const setup = async d => { const uid = randomUUID(); await savePodsub(uid, d); return uid; };
const test = async (name, fn) => { await fn(); passed++; console.log(`  ✓ ${name}`); };
const request = async (action = '', method = 'GET', body, token, owner = true, origin) => {
  const res = await api(new Request(`https://mavis.test/api/podsub${action ? '/' + action : ''}`, { method, headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(owner ? { 'x-mavis-access': process.env.MAVIS_ACCESS_CODE } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}), ...(origin ? { origin } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) }));
  return { status: res.status, body: res.status === 204 ? null : await res.json(), headers: res.headers };
};
try {
  await test('strict subscription validation rejects malformed and server-owned fields', async () => {
    const invalid = [null, [], {}, { ...doc(), version: 2 }, { ...doc(), nextRunAt: null }, { ...doc(), updatedAt: '' }, { ...doc(), engine: 'local' }, { ...doc(), rank: 'random' }, { ...doc(), keepDays: 31 }, { ...doc(), notify: { telegram: 1 } }, { ...doc(), schedule: { ...doc().schedule, timezone: 'Mars' } }, { ...doc(), schedule: { ...doc().schedule, days: [] } }, { ...doc(), schedule: { ...doc().schedule, readyBy: '24:00' } }, doc([site('a', { count: 0 })]), doc([site('../a')]), doc([site('a'), site('a')]), doc([site('a', { feedUrl: 'http://127.0.0.1' })]), doc(Array.from({ length: 13 }, (_, i) => site('s' + i)))];
    for (const b of invalid) assert.throws(() => validatePodsub(b));
    assert.equal(validatePodsub({ ...doc(), keepDays: undefined }).keepDays, 7);
  });
  await test('NY ready by 08:00 starts 07:00 and is strictly future', () => {
    assert.equal(nextRunAt(doc().schedule, Date.parse('2026-02-02T10:00:00Z')), '2026-02-02T12:00:00.000Z');
    assert.equal(nextRunAt(doc().schedule, Date.parse('2026-02-02T12:00:00Z')), '2026-02-03T12:00:00.000Z');
  });
  await test('DST spring and fall use the correct offsets; gap shifts and fold chooses first occurrence', () => {
    assert.equal(nextRunAt(doc().schedule, Date.parse('2026-03-08T04:00Z')), '2026-03-08T11:00:00.000Z');
    assert.equal(nextRunAt(doc().schedule, Date.parse('2026-11-01T04:00Z')), '2026-11-01T12:00:00.000Z');
    assert.equal(nextRunAt({ ...doc().schedule, readyBy: '02:30' }, Date.parse('2026-03-08T04:00Z')), '2026-03-08T06:30:00.000Z');
    assert.equal(nextRunAt({ ...doc().schedule, readyBy: '01:30' }, Date.parse('2026-11-01T03:00Z')), '2026-11-01T04:30:00.000Z');
  });
  await test('Friday evening weekdays schedule advances to Monday; midnight lead crosses date', () => {
    assert.equal(nextRunAt({ ...doc().schedule, days: ['mon', 'tue', 'wed', 'thu', 'fri'] }, Date.parse('2026-03-06T23:00Z')), '2026-03-09T11:00:00.000Z');
    assert.equal(nextRunAt({ ...doc().schedule, readyBy: '00:30', days: ['mon'] }, Date.parse('2026-03-08T20:00Z')), '2026-03-09T03:30:00.000Z');
  });
  await test('quota defaults to member 1 and owner 30; local midnight resets shared reservations', async () => {
    const uid = await setup(doc()); assert.equal((await podcastUsage(uid)).limit, 1); assert.equal((await podcastUsage('owner')).limit, 30);
    const now = Date.parse('2026-03-09T03:59:59Z');
    const q = await reservePodcastQuota(uid, now); await settlePodcastQuota(q, true);
    assert.equal((await podcastUsage(uid, now)).date, '2026-03-08');
    assert.equal((await podcastUsage(uid, now + 1000)).used, 0);
    await assert.rejects(reservePodcastQuota(uid, now), e => e.response.status === 429);
  });
  await test('priority fills the limit and records every skipped site', async () => {
    const uid = await setup(doc([site('low', { priority: 9 }), site('high', { priority: 1 })]));
    const out = await run(uid);
    assert.deepEqual(out.episodes[0].siteIds, ['high']); assert.equal(out.status, 'partial');
    assert.deepEqual(out.skipped, [{ siteId: 'low', reason: 'skipped_limit' }]);
    assert.equal((await podcastUsage(uid)).used, 1);
  });
  await test('empty day creates nothing and uses no quota', async () => {
    const uid = await setup(doc([site('empty')])), before = calls.length;
    assert.equal((await run(uid, { feed: async () => [] })).status, 'skipped_empty');
    assert.equal((await podcastUsage(uid)).used, 0); assert.equal(calls.length, before);
  });
  await test('top N newest with seen tracking never backfills older posts on repeat run', async () => {
    const uid = await setup(doc([site('top', { count: 2 })])); await subStore().setJSON(`podlimit/${uid}`, { limit: 10 });
    const feed = async () => [post('old', 1), post('newest', 3), post('second', 2)];
    const out = await run(uid, { feed }); assert.deepEqual(out.episodes.map(e => e.title), ['newest', 'second']);
    assert.equal((await run(uid, { feed })).status, 'skipped_empty');
    assert.equal((await podcastUsage(uid)).used, 2);
    assert.equal((await run(uid, { feed: async () => [post('brand-new', 4), ...await feed()] })).episodes.length, 1);
  });
  await test('briefing combines sites, separate makes per-post jobs, notebooklm passes through', async () => {
    const uid = await setup(doc([site('a', { delivery: 'briefing', count: 2 }), site('b', { delivery: 'briefing' }), site('c', { count: 2 })], { rank: 'important' }));
    await subStore().setJSON(`podlimit/${uid}`, { limit: 10 });
    const out = await run(uid, { feed: async url => [post(url + '2', 2), post(url + '1', 1)] });
    assert.equal(out.status, 'ok'); assert.equal(out.episodes.length, 3); assert.deepEqual(out.episodes[0].siteIds, ['a', 'b']);
    const sent = calls.filter(c => c.uid === uid); assert.ok(sent.every(c => c.body.engine === 'notebooklm')); assert.match(sent[0].body.text, /a.xml2/); assert.match(sent[0].body.text, /b.xml2/);
  });
  await test('manual and subscription creation consume the same daily quota', async () => {
    const uid = await setup(doc([site('manual')]));
    await createPodcast(uid, { kind: 'topic', title: 'Manual', query: 'Science' });
    const out = await run(uid); assert.equal(out.episodes.length, 0); assert.equal(out.skipped[0].reason, 'skipped_limit');
  });
  await test('CAS lock prevents simultaneous runs and safely releases expired leases', async () => {
    const uid = await setup(doc([site('lock')])); let unblock;
    const held = new Promise(resolve => { unblock = resolve; });
    let entered; const started = new Promise(resolve => { entered = resolve; });
    const first = run(uid, { feed: async () => { entered(); await held; return [post('lock')]; } }); await started;
    await assert.rejects(run(uid), e => e.response.status === 409); unblock(); await first;
    const old = await acquireLock(uid, Date.now() - 700000), current = await acquireLock(uid);
    assert.ok(old && current); await releaseLock(uid, old); assert.equal(await acquireLock(uid), null); await releaseLock(uid, current);
  });
  await test('tick processes only due users, reschedules, and deletes stale indexes', async () => {
    // Clear prior fixture schedules so selection is isolated.
    for (const k of await keys('podsub-due/')) await subStore().delete(k);
    const now = Date.now(), dueUid = await setup(doc([site('due')])), futureUid = await setup(doc([site('future')]));
    const stored = await subStore().get(`podsub/${dueUid}`, { type: 'json' });
    const at = new Date(now - 1000).toISOString();
    await subStore().setJSON(`podsub/${dueUid}`, { ...stored, nextRunAt: at }); await subStore().setJSON(`podsub-due/${at}/${dueUid}`, {});
    await tick({ ...deps, now });
    assert.equal((await runs(dueUid)).length, 1); assert.equal((await runs(futureUid)).length, 0);
    assert.ok(Date.parse((await subStore().get(`podsub/${dueUid}`, { type: 'json' })).nextRunAt) > now);
    assert.equal(await subStore().get(`podsub-due/${at}/${dueUid}`, { type: 'json' }), null);
    assert.equal(tickConfig.schedule, '*/5 * * * *');
  });
  await test('RSS and Atom parse GUID/link IDs, CDATA, dates, entities and alternate links', () => {
    const rss = parseFeed('<rss><channel><item><guid>g</guid><title>A &amp; B</title><description><![CDATA[<p>Hello</p>]]></description><pubDate>Tue, 27 Oct 2026 08:00:00 GMT</pubDate></item><item><link>/fallback</link><title>Old</title></item></channel></rss>', 'https://example.com/feed');
    assert.equal(rss[0].id, 'g'); assert.equal(rss[0].title, 'A & B'); assert.equal(rss[0].text, 'Hello'); assert.equal(rss[1].id, 'https://example.com/fallback');
    assert.equal(parseFeed('<feed><entry><id>atom</id><title>T</title><link rel="self" href="/self"/><link href="/article"/><summary>Body</summary></entry></feed>', 'https://example.com')[0].link, 'https://example.com/article');
    assert.throws(() => parseFeed('<!DOCTYPE rss><rss/>', 'https://example.com')); assert.throws(() => parseFeed('<rss><item></rss>', 'https://example.com'));
  });
  await test('SSRF rejects local literal IPs, private DNS, redirects and oversized feeds', async () => {
    for (const ip of ['127.0.0.1', '10.1.2.3', '100.64.0.1', '169.254.169.254', '172.16.0.1', '192.168.1.1', '::1', '::ffff:127.0.0.1', 'fc00::1', 'fe80::1']) assert.equal(publicAddress(ip), false, ip);
    for (const url of ['file:///etc/passwd', 'http://2130706433', 'http://[::1]', 'https://u:p@example.com']) assert.throws(() => feedUrl(url));
    const resolve = async () => [{ address: '93.184.216.34', family: 4 }];
    await assert.rejects(fetchFeed('https://example.com', { resolve: async () => [{ address: '127.0.0.1', family: 4 }], fetcher: () => assert.fail('must not fetch') }));
    await assert.rejects(fetchFeed('https://example.com', { resolve, fetcher: async () => new Response(null, { status: 302, headers: { location: 'http://127.0.0.1/' } }) }));
    await assert.rejects(fetchFeed('https://example.com', { resolve, fetcher: async () => new Response('x'.repeat(2 * 1024 * 1024 + 1)) }));
    const out = await fetchFeed('https://example.com', { resolve, fetcher: async (_url, opts) => { assert.equal(opts.redirect, 'manual'); assert.ok(opts.dispatcher); return new Response('<rss><channel/></rss>'); } }); assert.deepEqual(out, []);
  });
  await test('feed timeout includes DNS lookup and never makes a connection after abort', async () => {
    await assert.rejects(fetchFeed('https://example.com', { timeout: 10, resolve: () => new Promise(() => {}), fetcher: () => assert.fail() }), /timeout/);
  });
  await test('HTTP authenticates, validates, supports CORS, and restricts limit writes to owner', async () => {
    assert.equal((await request('', 'GET', undefined, null, false)).status, 401);
    assert.equal((await request('', 'PUT', doc(), null, true, 'https://evil.com')).status, 403);
    assert.equal((await request('', 'PUT', { ...doc(), engine: 'local' })).status, 400);
    assert.equal((await request('', 'PUT', doc())).status, 200);
    const get = await request(); assert.equal(get.body.today.limit, 30); assert.equal(get.body.lastRun, null);
    assert.equal((await request('', 'OPTIONS', undefined, null, false, 'https://localhost')).headers.get('access-control-allow-methods'), 'GET, PUT, POST, OPTIONS');
    const { user } = await createUser('podsub-test@example.test', 'password-long-123'); await transition(user, 'add'); const token = signToken(user);
    assert.equal((await request('limit', 'PUT', { uid: user.id, limit: 4 }, token, false)).status, 403);
    assert.equal((await request('limit', 'PUT', { uid: user.id, limit: 4 })).status, 200);
    assert.equal((await request('', 'GET', undefined, token, false)).body.today.limit, 4);
    for (const limit of [0, 51, 1.5, '4']) assert.equal((await request('limit', 'PUT', { uid: user.id, limit })).status, 400);
    assert.equal((await request('runs', 'POST')).status, 405);
  });
  await test('run-now rate is persisted at 3/hour, runs return newest and today isolates the owner', async () => {
    for (let i = 0; i < 3; i++) assert.equal((await request('run-now', 'POST')).body.status, 'skipped_empty');
    assert.equal((await request('run-now', 'POST')).status, 429);
    assert.equal((await request('runs')).body.length, 3);
    await createPodcast('owner', { kind: 'topic', title: 'Owner today', query: 'News' });
    const out = await request('today'); assert.equal(out.body.length, 1); assert.equal(out.body[0].title, 'Owner today'); assert.equal(out.body[0].status, 'queued');
  });
  console.log(`\n${passed} podcast subscription checks passed.`);
} finally {
  globalThis.fetch = realFetch; delete globalThis.__mavisRealFetch;
  for (const key of Object.keys(process.env)) if (!(key in savedEnv)) delete process.env[key];
  Object.assign(process.env, savedEnv);
  await server.stop(); rmSync(dir, { recursive: true, force: true });
}
