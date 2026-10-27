import { randomUUID } from 'node:crypto';
import { subStore, problem, getSub } from './subs.mjs';
import { readForUpdate, writeIfUnchanged, getUser } from './accounts.mjs';
import { isOwnerEmail } from './access.mjs';
import { createPodcast, podcastUsage, studioConfig, studioJson, publicJob, terminalStatus, releaseSlot } from './podcast.mjs';
import { fetchFeed, feedUrl } from './podsub-feed.mjs';
import { DAYS, nextRunAt, localDate } from './podsub-time.mjs';
import { env } from './shared.mjs';

const bad = () => { throw problem(400, 'bad_request', 'Invalid podcast subscription document.'); };
const object = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).every(k => keys.includes(k));
const str = (v, max) => typeof v === 'string' && v.length > 0 && v.length <= max && !/[\u0000-\u001f\u007f]/.test(v);
export const validUid = uid => uid === 'owner' || typeof uid === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(uid);
export function validatePodsub(b) {
  if (!object(b, ['version', 'sites', 'schedule', 'engine', 'rank', 'keepDays', 'notify']) || b.version !== 1 || !Array.isArray(b.sites) || b.sites.length > 12) bad();
  const ids = new Set();
  for (const site of b.sites) {
    if (!object(site, ['id', 'name', 'feedUrl', 'siteUrl', 'mode', 'count', 'delivery', 'priority'])
      || !str(site.id, 100) || !/^[\w-]+$/.test(site.id) || ids.has(site.id) || !str(site.name, 200)
      || !['off', 'top'].includes(site.mode) || !['briefing', 'separate'].includes(site.delivery) || !Number.isSafeInteger(site.priority)
      || (site.mode === 'top' && (!Number.isInteger(site.count) || site.count < 1 || site.count > 5))
      || (site.count !== undefined && (!Number.isInteger(site.count) || site.count < 1 || site.count > 5))) bad();
    try { feedUrl(site.feedUrl); feedUrl(site.siteUrl); } catch { bad(); }
    ids.add(site.id);
  }
  const s = b.schedule;
  if (!object(s, ['enabled', 'readyBy', 'days', 'timezone']) || typeof s.enabled !== 'boolean' || typeof s.readyBy !== 'string' || !/^([01]\d|2[0-3]):[0-5]\d$/.test(s.readyBy)
    || !Array.isArray(s.days) || s.days.some(d => !DAYS.includes(d)) || new Set(s.days).size !== s.days.length || (s.enabled && !s.days.length)
    || !str(s.timezone, 100) || !/^[A-Za-z_]+(?:\/[A-Za-z0-9_+.-]+)*$/.test(s.timezone) || !['auto', 'notebooklm'].includes(b.engine) || !['newest', 'important'].includes(b.rank)
    || (b.keepDays !== undefined && (!Number.isInteger(b.keepDays) || b.keepDays < 1 || b.keepDays > 30))
    || !object(b.notify, ['telegram']) || typeof b.notify.telegram !== 'boolean') bad();
  try { new Intl.DateTimeFormat('en', { timeZone: s.timezone }).format(); } catch { bad(); }
  return structuredClone({ ...b, keepDays: b.keepDays ?? 7 });
}
export function defaultPodsub() {
  return { version: 1, sites: [], schedule: { enabled: false, readyBy: '08:00', days: ['mon', 'tue', 'wed', 'thu', 'fri'], timezone: 'UTC' }, engine: 'auto', rank: 'newest', keepDays: 7, notify: { telegram: false }, nextRunAt: null, updatedAt: null };
}
export async function keys(prefix) {
  const out = [];
  for await (const page of subStore().list({ prefix, paginate: true })) out.push(...page.blobs.map(b => b.key));
  return out.sort();
}
export async function acquireLock(uid, now = Date.now()) {
  const s = subStore(), key = `podsub-lock/${uid}`, cur = await readForUpdate(s, key);
  if (cur.data?.expiresAt > now) return null;
  const token = randomUUID();
  return await writeIfUnchanged(s, key, { token, expiresAt: now + 600000 }, cur) ? token : null;
}
export async function releaseLock(uid, token) {
  const s = subStore(), key = `podsub-lock/${uid}`, cur = await readForUpdate(s, key);
  // CAS tombstone avoids deleting a successor's lock after lease expiry.
  if (cur.data?.token === token) await writeIfUnchanged(s, key, { token: null, expiresAt: 0 }, cur);
}
async function writeDoc(uid, doc) {
  const s = subStore();
  // Publish the new index first: a stale index is harmless (tick verifies the doc).
  if (doc.nextRunAt) await s.setJSON(`podsub-due/${doc.nextRunAt}/${uid}`, { uid });
  await s.setJSON(`podsub/${uid}`, doc);
  for (const key of await keys('podsub-due/')) if (key.endsWith(`/${uid}`) && key !== `podsub-due/${doc.nextRunAt}/${uid}`) await s.delete(key);
  return doc;
}
export async function savePodsub(uid, input, now = Date.now()) {
  const doc = validatePodsub(input), token = await acquireLock(uid);
  if (!token) throw problem(409, 'podsub_busy', 'A podcast subscription operation is already running.');
  try { return await writeDoc(uid, { ...doc, nextRunAt: nextRunAt(doc.schedule, now), updatedAt: new Date(now).toISOString() }); }
  finally { await releaseLock(uid, token); }
}
export async function runs(uid) {
  const list = (await keys(`podsub-runs/${uid}/`)).slice(-30).reverse();
  return Promise.all(list.map(k => subStore().get(k, { type: 'json' })));
}
export async function limitRunNow(uid, now = Date.now()) {
  const s = subStore(), key = `podsub-rate/${uid}`;
  for (let i = 0; i < 40; i++) {
    const cur = await readForUpdate(s, key), hits = (cur.data?.hits || []).filter(t => now - t < 3600000);
    if (hits.length >= 3) throw problem(429, 'rate_limited', 'Run now is limited to three requests per hour.');
    if (await writeIfUnchanged(s, key, { hits: [...hits, now] }, cur)) return;
  }
  throw problem(503, 'podsub_busy', 'Please try again.');
}
async function eligible(uid, now) {
  if (uid === 'owner') return true;
  const user = await getUser(uid);
  if (!user) return false;
  if (isOwnerEmail(user.email)) return true;
  const sub = await getSub(uid);
  return sub?.state === 'active' && (sub.expiresAt == null || Number.isFinite(sub.expiresAt) && sub.expiresAt > now);
}
function episodesFor(sites) {
  const episodes = [], briefing = { title: 'Daily briefing', sites: [], posts: [] };
  for (const site of sites) {
    if (!site.posts.length) continue;
    if (site.delivery === 'briefing') {
      if (!briefing.sites.length) episodes.push(briefing);
      briefing.sites.push(site); briefing.posts.push(...site.posts);
    } else for (const post of site.posts) episodes.push({ title: post.title, sites: [site], posts: [post] });
  }
  return episodes;
}
async function notify(uid, doc, run, now, deadline) {
  const token = env('TELEGRAM_BOT_TOKEN'), chat = env(`TELEGRAM_CHAT_ID_${uid === 'owner' ? 'OWNER' : uid}`);
  if (!doc.notify.telegram || !token || !chat || !run.episodes.length || Date.now() >= deadline) return;
  try {
    await fetch(`https://api.telegram.org/bot${token}/sendMessage`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ chat_id: chat, text: `Your ${run.episodes.length} podcasts for ${localDate(now, doc.schedule.timezone)} are being made, ready by ${doc.schedule.readyBy}` }), signal: AbortSignal.timeout(Math.max(1, Math.min(1500, deadline - Date.now()))), redirect: 'error' });
  } catch { /* Optional notification failure never fails generation. */ }
}
export async function runSubscription(uid, { now = Date.now(), dueKey, deadline = Date.now() + 24000, feed = fetchFeed, cfg = studioConfig(), checkAccess = eligible } = {}) {
  const token = await acquireLock(uid);
  if (!token) throw problem(409, 'podsub_busy', 'A podcast subscription operation is already running.');
  const s = subStore();
  let doc;
  try {
    doc = await s.get(`podsub/${uid}`, { type: 'json' }) || defaultPodsub();
    if (dueKey && (!doc.schedule.enabled || !doc.nextRunAt || `podsub-due/${doc.nextRunAt}/${uid}` !== dueKey || Date.parse(doc.nextRunAt) > now)) { await s.delete(dueKey); return null; }
    const run = { startedAt: new Date(now).toISOString(), finishedAt: null, status: 'ok', episodes: [], skipped: [] };
    const skip = (siteId, reason) => { if (!run.skipped.some(v => v.siteId === siteId && v.reason === reason)) run.skipped.push({ siteId, reason }); };
    try {
      if (!await checkAccess(uid, now)) throw new Error('Access no longer active');
      const enabled = doc.sites.filter(site => site.mode === 'top').sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id));
      const sites = await Promise.all(enabled.map(async site => {
        try {
          const [posts, record] = await Promise.all([feed(site.feedUrl, { timeout: Math.max(1, Math.min(6000, deadline - Date.now())) }), s.get(`podsub-seen/${uid}/${site.id}`, { type: 'json' })]);
          const seen = record?.seen || [];
          // No relevance ranker configured: important deliberately falls back to newest.
          return { ...site, seen, posts: posts.sort((a, b) => b.publishedAt - a.publishedAt).slice(0, site.count).filter(p => !seen.includes(p.id)) };
        } catch { skip(site.id, 'feed_failed'); return { ...site, posts: [] }; }
      }));
      const planned = episodesFor(sites);
      for (const episode of planned) {
        const usage = await podcastUsage(uid, now);
        if (usage.used >= usage.limit) { episode.sites.forEach(site => skip(site.id, 'skipped_limit')); continue; }
        if (Date.now() >= deadline - 1500) { episode.sites.forEach(site => skip(site.id, 'skipped_budget')); continue; }
        try {
          // Give every selected post a share of the source budget before marking it seen.
          const perPost = Math.floor(85000 / episode.posts.length);
          const text = episode.posts.map(p => `${p.title}\n${p.link || ''}\n${p.text || p.title}`.slice(0, perPost)).join('\n\n');
          const job = await createPodcast(uid, { kind: 'chapter', title: episode.title, text, engine: doc.engine, style: 'brief' }, { cfg: cfg ? { ...cfg, deadline: Math.min(deadline - 1000, Date.now() + 6000) } : null, now });
          run.episodes.push({ jobId: job.id, title: episode.title, siteIds: episode.sites.map(site => site.id) });
          for (const site of episode.sites) {
            const ids = episode.posts.filter(p => site.posts.includes(p)).map(p => p.id);
            site.seen = [...new Set([...site.seen, ...ids])].slice(-300);
            await s.setJSON(`podsub-seen/${uid}/${site.id}`, { seen: site.seen });
          }
        } catch (err) {
          const code = err.response ? (await err.response.clone().json()).code : '';
          episode.sites.forEach(site => skip(site.id, code === 'daily_limit' ? 'skipped_limit' : 'create_failed'));
        }
      }
      run.status = run.skipped.length ? (run.episodes.length || run.skipped.every(v => v.reason === 'skipped_limit') ? 'partial' : 'failed') : run.episodes.length ? 'ok' : 'skipped_empty';
    } catch { run.status = 'failed'; }
    run.finishedAt = new Date().toISOString();
    // A lease serializes writes. Millisecond suffix remains a valid ISO timestamp.
    let stamp = now;
    while (await s.get(`podsub-runs/${uid}/${new Date(stamp).toISOString()}`, { type: 'json' })) stamp++;
    await s.setJSON(`podsub-runs/${uid}/${new Date(stamp).toISOString()}`, run);
    for (const key of (await keys(`podsub-runs/${uid}/`)).slice(0, -30)) await s.delete(key);
    await writeDoc(uid, { ...doc, nextRunAt: nextRunAt(doc.schedule, Math.max(now, Date.now())), updatedAt: new Date().toISOString() });
    await notify(uid, doc, run, now, deadline);
    return run;
  } finally { await releaseLock(uid, token); }
}
export async function tick({ now = Date.now(), deadline = Date.now() + 25000, ...deps } = {}) {
  const due = (await keys('podsub-due/')).filter(key => Date.parse(key.split('/')[1]) <= now);
  const users = new Set(), selected = due.filter(key => { const uid = key.split('/')[2]; if (!validUid(uid) || users.has(uid)) return false; users.add(uid); return true; }).slice(0, 10);
  // Each user has a separate lease and budget. No waiting for generated audio.
  return Promise.all(selected.map(async dueKey => {
    if (Date.now() >= deadline - 7000) return null;
    try { return await runSubscription(dueKey.split('/')[2], { ...deps, now, deadline, dueKey }); } catch { return null; }
  }));
}
export async function todayEpisodes(uid, now = Date.now()) {
  const usage = await podcastUsage(uid, now), cfg = studioConfig();
  const rows = (await Promise.all((await keys(`pod/${uid}/`)).map(k => subStore().get(k, { type: 'json' })))).filter(row => row && localDate(row.createdAt, usage.timezone) === usage.date).sort((a, b) => b.createdAt - a.createdAt);
  return Promise.all(rows.map(async row => {
    const job = cfg ? publicJob(await studioJson({ ...cfg, deadline: Date.now() + 6000 }, `/${row.id}`, uid), row.id) : { id: row.id, status: row.status || 'queued', title: row.title };
    if (uid !== 'owner' && terminalStatus(job.status)) await releaseSlot(uid, row.id);
    return { ...job, createdAt: new Date(row.createdAt).toISOString() };
  }));
}
