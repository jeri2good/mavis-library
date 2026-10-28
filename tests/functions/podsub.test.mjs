import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import https from 'node:https';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { randomUUID } from 'node:crypto';
import { BlobsServer } from '@netlify/blobs/server';
import { subStore, transition, usage } from '../../netlify/lib/subs.mjs';
import { createUser, signToken } from '../../netlify/lib/accounts.mjs';
import { createPodcast, podcastUsage, reservePodcastQuota, settlePodcastQuota, refundFailedPodcast } from '../../netlify/lib/podcast.mjs';
import { localDate, nextRunAt } from '../../netlify/lib/podsub-time.mjs';
import { parseFeed, fetchFeed, publicAddress, feedUrl, pinnedDispatcher } from '../../netlify/lib/podsub-feed.mjs';
import { validatePodsub, savePodsub, runSubscription, runs, tick, acquireLock, releaseLock, keys, todayEpisodes } from '../../netlify/lib/podsub.mjs';
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
    const invalid = [null, [], {}, { ...doc(), version: 2 }, { ...doc(), nextRunAt: null }, { ...doc(), updatedAt: '' }, { ...doc(), engine: 'local' }, { ...doc(), rank: 'random' }, { ...doc(), keepDays: 31 }, { ...doc(), notify: { telegram: 1 } }, { ...doc(), schedule: { ...doc().schedule, timezone: 'Mars' } }, { ...doc(), schedule: { ...doc().schedule, days: [] } }, { ...doc(), schedule: { ...doc().schedule, readyBy: '24:00' } }, { ...doc(), schedule: { ...doc().schedule, readyBy: ['08:00'] } }, { ...doc(), schedule: { ...doc().schedule, timezone: '+01:00' } }, doc([site('a', { count: 0 })]), doc([site('../a')]), doc([site('a'), site('a')]), doc([site('a', { feedUrl: 'http://127.0.0.1' })]), doc(Array.from({ length: 13 }, (_, i) => site('s' + i)))];
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
    for (const ip of ['127.0.0.1', '10.1.2.3', '100.64.0.1', '169.254.169.254', '172.16.0.1', '192.168.1.1', '::1', '::ffff:127.0.0.1', 'fc00::1', 'fe80::1', '2001:2::1', '2001:0000::1', '2001:db8::1', '2002:7f00:1::1']) assert.equal(publicAddress(ip), false, ip);
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

  await test('owner creates exactly 30 jobs before the shared daily gate stops studio dispatch', async () => {
    const now = Date.parse('2030-01-02T12:00Z'), before = calls.length;
    for (let i = 0; i < 30; i++) await createPodcast('owner', { kind: 'topic', title: `Owner ${i}`, query: 'News' }, { now });
    await assert.rejects(createPodcast('owner', { kind: 'topic', title: 'Excess', query: 'News' }, { now }), e => e.response.status === 429);
    assert.equal(calls.length - before, 30); assert.equal((await podcastUsage('owner', now)).used, 30);
  });
  await test('subscription status usage reports the same local-day counter and per-user cap', async () => {
    const uid = await setup(doc()), now = Date.parse('2030-01-02T03:00Z');
    await subStore().setJSON(`podlimit/${uid}`, { limit: 6 });
    const reservation = await reservePodcastQuota(uid, now); await settlePodcastQuota(reservation, true);
    assert.deepEqual((await usage(uid, now)).podcast, { used: 1, cap: 6 });
  });
  await test('tick selects at most ten unique users in oldest-first order', async () => {
    for (const k of await keys('podsub-due/')) await subStore().delete(k);
    const now = Date.now(), users = [];
    for (let i = 0; i < 12; i++) {
      const uid = randomUUID(), at = new Date(now - (12 - i) * 1000).toISOString(); users.push(uid);
      await subStore().setJSON(`podsub/${uid}`, { ...doc(), nextRunAt: at, updatedAt: at });
      await subStore().setJSON(`podsub-due/${at}/${uid}`, {});
    }
    await tick({ ...deps, now });
    for (let i = 0; i < users.length; i++) assert.equal((await runs(users[i])).length, i < 10 ? 1 : 0);
  });
  await test('scheduled execution rechecks expired access without touching feeds or studio', async () => {
    const { user } = await createUser('expired-scheduler@example.test', 'long-password-123');
    await transition(user, 'add');
    const sub = await subStore().get(`s/${user.id}`, { type: 'json' });
    await subStore().setJSON(`s/${user.id}`, { ...sub, expiresAt: Date.now() - 1 });
    await savePodsub(user.id, doc([site('blocked')]));
    const before = calls.length;
    const out = await runSubscription(user.id, { feed: () => assert.fail('expired access cannot fetch') });
    assert.equal(out.status, 'failed'); assert.equal(calls.length, before);
  });
  await test('run history retains 30 records and seen IDs retain 300', async () => {
    const uid = await setup(doc([site('retention')]));
    const seen = Array.from({ length: 300 }, (_, i) => 'previous-' + i);
    await subStore().setJSON(`podsub-seen/${uid}/retention`, { seen });
    for (let i = 0; i < 32; i++) await subStore().setJSON(`podsub-runs/${uid}/${new Date(Date.now() - 3600000 + i).toISOString()}`, { startedAt: new Date(Date.now() - 3600000 + i).toISOString() });
    await run(uid); const records = await runs(uid);
    assert.equal(records.length, 30); assert.equal(records[0].status, 'ok'); assert.equal((await keys(`podsub-runs/${uid}/`)).length, 30);
    const stored = (await subStore().get(`podsub-seen/${uid}/retention`, { type: 'json' })).seen;
    assert.equal(stored.length, 300); assert.equal(stored[0], 'previous-1'); assert.equal(stored.at(-1), 'one');
  });
  await test('saving rewrites due indexes and disabling removes recurring work', async () => {
    const uid = await setup(doc());
    await savePodsub(uid, doc([], { schedule: { ...doc().schedule, readyBy: '18:00' } }));
    let indexes = (await keys('podsub-due/')).filter(k => k.endsWith('/' + uid)); assert.equal(indexes.length, 1);
    const saved = await savePodsub(uid, doc([], { schedule: { ...doc().schedule, enabled: false } }));
    assert.equal(saved.nextRunAt, null); indexes = (await keys('podsub-due/')).filter(k => k.endsWith('/' + uid)); assert.equal(indexes.length, 0);
  });
  await test('deadline exhaustion is recorded, rescheduled and does not consume quota', async () => {
    const uid = await setup(doc([site('budget')]));
    const out = await run(uid, { deadline: Date.now() - 1 });
    assert.equal(out.episodes.length, 0); assert.equal(out.skipped[0].reason, 'skipped_budget');
    assert.equal((await podcastUsage(uid)).used, 0);
    assert.ok(Date.parse((await subStore().get(`podsub/${uid}`, { type: 'json' })).nextRunAt) > Date.now());
  });
  await test('unavailable feeds and rejected studio requests do not mark posts seen or use quota', async () => {
    const uid = await setup(doc([site('fail')]));
    const failedFeed = await run(uid, { feed: async () => { throw new Error('Feed failed'); } });
    assert.equal(failedFeed.status, 'failed'); assert.equal(failedFeed.skipped[0].reason, 'feed_failed');
    const saved = globalThis.fetch;
    globalThis.fetch = async () => Response.json({ detail: 'Unavailable' }, { status: 503 });
    try { const out = await run(uid); assert.equal(out.status, 'failed'); assert.equal(out.skipped[0].reason, 'create_failed'); }
    finally { globalThis.fetch = saved; }
    assert.equal((await podcastUsage(uid)).used, 0);
    assert.equal(await subStore().get(`podsub-seen/${uid}/fail`, { type: 'json' }), null);
  });
  await test('a scheduled run whose studio is asleep retries in 5 minutes, then stops before ready-by', async () => {
    const uid = await setup(doc([site('sleepy')]));
    const ready = Date.now() + 60 * 60000;
    const start = new Date(ready - 60 * 60000).toISOString();
    await subStore().setJSON(`podsub/${uid}`, { ...(await subStore().get(`podsub/${uid}`, { type: 'json' })), nextRunAt: start });
    const saved = globalThis.fetch;
    globalThis.fetch = async () => Response.json({ detail: 'Unavailable' }, { status: 503 });
    try {
      const out = await run(uid, { dueKey: `podsub-due/${start}/${uid}`, now: Date.parse(start) });
      assert.equal(out.status, 'retrying');
      const after = await subStore().get(`podsub/${uid}`, { type: 'json' });
      assert.ok(Math.abs(Date.parse(after.nextRunAt) - (Date.now() + 5 * 60000)) < 5000);
      assert.equal(after.retryReadyAt, new Date(ready).toISOString());
      // Last chance window closed: falls back to the next normal day.
      await subStore().setJSON(`podsub/${uid}`, { ...after, retryReadyAt: new Date(Date.now() + 9 * 60000).toISOString() });
      const late = await run(uid, { dueKey: `podsub-due/${after.nextRunAt}/${uid}`, now: Date.parse(after.nextRunAt) });
      assert.equal(late.status, 'failed');
      const final = await subStore().get(`podsub/${uid}`, { type: 'json' });
      assert.equal(final.retryReadyAt, undefined);
    } finally { globalThis.fetch = saved; }
    assert.equal((await podcastUsage(uid)).used, 0);
  });
  await test('large briefing budgets source text across every selected post', async () => {
    const uid = await setup(doc(Array.from({ length: 12 }, (_, i) => site('site' + i, { count: 5, delivery: 'briefing' }))));
    const out = await run(uid, { feed: async url => Array.from({ length: 5 }, (_, i) => ({ ...post(url + '-' + i, i), text: 'x'.repeat(16000) })) });
    assert.equal(out.episodes.length, 1);
    const source = calls.filter(c => c.uid === uid)[0].body.text;
    assert.ok(source.length < 90000); assert.match(source, /site0.xml-0/); assert.match(source, /site11.xml-0/);
  });
  await test('built-in fetch uses pinned transport lookup while preserving original TLS hostname', async () => {
    const savedRequest = https.request;
    let checked = false;
    https.request = (url, opts) => {
      assert.equal(url.hostname, 'example.com'); assert.equal(opts.agent, false);
      opts.lookup(url.hostname, { all: true }, (_err, addresses) => { assert.deepEqual(addresses, [{ address: '93.184.216.34', family: 4 }]); checked = true; });
      const req = new EventEmitter(); req.destroy = () => {};
      req.end = () => queueMicrotask(() => {
        const res = Readable.from([Buffer.from('<rss><channel/></rss>')]);
        Object.assign(res, { statusCode: 200, statusMessage: 'OK', rawHeaders: ['Content-Type', 'application/xml'], rawTrailers: [] }); req.emit('response', res);
      });
      return req;
    };
    try {
      const res = await realFetch('https://example.com/feed', { dispatcher: pinnedDispatcher('93.184.216.34', 4) });
      assert.equal(await res.text(), '<rss><channel/></rss>'); assert.ok(checked);
    } finally { https.request = savedRequest; }
  });
  await test('a podcast that fails in the studio is refunded exactly once', async () => {
    const uid = 'refund-' + randomUUID(), now = Date.now();
    const q = await reservePodcastQuota(uid, now); await settlePodcastQuota(q, true);
    assert.equal((await podcastUsage(uid, now)).used, 1);
    assert.equal(await refundFailedPodcast(uid, 'job-x', now), true);
    assert.equal(await refundFailedPodcast(uid, 'job-x', now), false);
    assert.equal((await podcastUsage(uid, now)).used, 0);
    assert.equal(await refundFailedPodcast(uid, 'job-y', now), false, 'never goes below zero');
    passed++;
  });
  await test('Telegram sends one optional bounded message and missing configuration silently skips', async () => {
    const uid = await setup(doc([site('notify')], { notify: { telegram: true } }));
    process.env.TELEGRAM_BOT_TOKEN = 'test-only-telegram-token'; process.env[`TELEGRAM_CHAT_ID_${uid}`] = 'test-chat';
    const saved = globalThis.fetch, messages = [];
    globalThis.fetch = async (url, options) => {
      if (String(url).startsWith('https://api.telegram.org/')) { messages.push(JSON.parse(options.body)); assert.ok(options.signal); return Response.json({ ok: true }); }
      return saved(url, options);
    };
    try {
      await run(uid); assert.equal(messages.length, 1); assert.match(messages[0].text, /^Your 1 podcasts for .* are being made, ready by 08:00$/);
      const other = await setup(doc([site('no-config')], { notify: { telegram: true } })); await run(other); assert.equal(messages.length, 1);
    } finally { globalThis.fetch = saved; delete process.env.TELEGRAM_BOT_TOKEN; delete process.env[`TELEGRAM_CHAT_ID_${uid}`]; }
  });
  await test('60-second duplicate window isolates users and content, and expires at the boundary', async () => {
    const uid = await setup(doc()), other = await setup(doc());
    await subStore().setJSON(`podlimit/${uid}`, { limit: 10 });
    const input = { kind: 'chapter', title: 'Double tap', text: 'Source text' }, now = Date.now();
    const first = await createPodcast(uid, input, { now });
    const count = calls.length;
    const repeat = await createPodcast(uid, input, { now: now + 59999 });
    assert.equal(repeat.id, first.id); assert.equal(repeat.reused, true); assert.equal(calls.length, count);
    assert.notEqual((await createPodcast(uid, input, { now: now + 60000 })).id, first.id);
    assert.notEqual((await createPodcast(other, input, { now })).id, first.id);
    assert.notEqual((await createPodcast(uid, { ...input, text: 'Different source' }, { now })).id, first.id);
    assert.notEqual((await createPodcast(uid, { ...input, title: 'Different title' }, { now })).id, first.id);
    assert.equal(calls.at(-1).body.engine, 'notebooklm');
    await createPodcast(uid, { ...input, title: 'Subscription auto', engine: 'auto' }, { now });
    assert.equal(calls.at(-1).body.engine, 'auto');
  });
  await test('Today hides missing rows persistently, reports queue estimates, and preserves last-known rows on outage', async () => {
    const uid = await setup(doc());
    const now = Date.now();
    const rows = [
      { id: 'queue-first', status: 'generating', createdAt: now - 3000 },
      { id: 'queue-second', status: 'queued', createdAt: now - 2000 },
      { id: 'queue-ready', status: 'ready', createdAt: now - 1000 },
      { id: 'queue-gone', status: 'queued', createdAt: now },
    ].map(row => ({ ...row, userId: uid, title: row.id }));
    for (const row of rows) await subStore().setJSON(`pod/${uid}/${row.id}`, row);
    const saved = globalThis.fetch;
    let offline = false, missingCalls = 0;
    globalThis.fetch = async (url, opts = {}) => {
      if (String(url).startsWith(process.env.MAVIS_BLOBS_URL)) return saved(url, opts);
      assert.ok(String(url).startsWith(process.env.STUDIO_URL));
      if (offline) throw new Error('studio offline');
      const id = String(url).split('/').at(-1);
      if (id === 'podcasts') return Response.json({ items: rows.slice(0, 3).map(row => ({ ...row, createdAt: new Date(row.createdAt).toISOString() })) });
      if (id === 'queue-gone') { missingCalls++; return Response.json({}, { status: 404 }); }
      return Response.json({ ...rows.find(row => row.id === id), stage: 'Last studio stage' });
    };
    try {
      const list = await todayEpisodes(uid, now);
      assert.equal(list.length, 3);
      const first = list.find(j => j.id === 'queue-first'), second = list.find(j => j.id === 'queue-second');
      assert.ok(first.queuePosition >= 1); assert.equal(second.queuePosition, first.queuePosition + 1);
      assert.equal(first.etaMin, first.queuePosition * 5); assert.equal(second.etaMin, second.queuePosition * 5);
      assert.equal(list.find(j => j.id === 'queue-ready').queuePosition, null);
      assert.equal(await subStore().get(`pod/${uid}/queue-gone`, { type: 'json' }), null);
      assert.equal((await subStore().get('pod-id/queue-gone', { type: 'json' })).status, 'deleted');
      await todayEpisodes(uid, now); assert.equal(missingCalls, 1);
      offline = true;
      const fallback = await todayEpisodes(uid, now);
      assert.equal(fallback.length, 3);
      assert.equal(fallback.find(j => j.id === 'queue-first').stage, 'Last studio stage');
      assert.equal(fallback.find(j => j.id === 'queue-second').etaMin, second.etaMin);
    } finally { globalThis.fetch = saved; }
  });
  console.log(`\n${passed} podcast subscription checks passed.`);
} finally {
  globalThis.fetch = realFetch; delete globalThis.__mavisRealFetch;
  for (const key of Object.keys(process.env)) if (!(key in savedEnv)) delete process.env[key];
  Object.assign(process.env, savedEnv);
  await server.stop(); rmSync(dir, { recursive: true, force: true });
}
