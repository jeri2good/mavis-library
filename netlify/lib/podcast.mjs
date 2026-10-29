import { createHmac, timingSafeEqual, randomUUID, createHash } from 'node:crypto';
import { isIP } from 'node:net';
import { env } from './shared.mjs';
import { subStore, problem } from './subs.mjs';
import { localDate, nextMidnight } from './podsub-time.mjs';
import { readForUpdate, writeIfUnchanged } from './accounts.mjs';

export const validJobId = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id);
export const terminalStatus = (status) => ['ready', 'failed'].includes(status);
export const validStatus = (status) => ['queued', 'sourcing', 'writing', 'generating', 'ready', 'failed'].includes(status);
export const STYLES = Object.freeze(['deep-dive', 'brief', 'debate', 'critique']);
export const LENGTHS = Object.freeze(['short', 'default', 'long']);
export const ENGINES = Object.freeze(['auto', 'notebooklm', 'local']);
const safeText = (value, max) => value.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').slice(0, max);
const validAudioUid = (uid) => uid === 'owner' || (typeof uid === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(uid));
const withinLength = (value, max) => {
  let count = 0;
  for (const char of value) if (++count > max) return false;
  return true;
};
export function signAudio(secret, id, exp, uid) {
  return createHmac('sha256', secret).update(`${id}.${exp}.${uid}`, 'utf8').digest('hex');
}
export function verifyAudio(secret, id, exp, uid, sig, now = Date.now()) {
  if (!secret || !validJobId(id) || !validAudioUid(uid) || !/^\d{10}$/.test(String(exp)) || !/^[0-9a-f]{64}$/.test(String(sig))) return false;
  const t = Number(exp);
  if (t <= Math.floor(now / 1000) || t > Math.floor(now / 1000) + 600) return false;
  return timingSafeEqual(Buffer.from(signAudio(secret, id, exp, uid), 'hex'), Buffer.from(sig, 'hex'));
}
export function studioConfig() {
  const base = env('STUDIO_URL'), secret = env('STUDIO_SHARED_SECRET');
  if (!base || !secret) return null;
  try {
    const url = new URL(base);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) return null;
    return { base: url.href.replace(/\/$/, ''), secret };
  } catch { return null; }
}
export function validateCreate(b) {
  const bad = (message) => { throw problem(400, 'bad_request', message); };
  if (!b || typeof b !== 'object' || Array.isArray(b)) bad('Send a JSON object.');
  const fields = ['kind', 'title', 'text', 'url', 'gutenbergId', 'query', 'style', 'length', 'language', 'engine'];
  if (Object.keys(b).some((k) => !fields.includes(k))) bad('Unknown podcast field.');
  b = Object.fromEntries(Object.entries(b).map(([k, v]) => [k, typeof v === 'string' ? v.trim() : v]));
  if (!['book', 'chapter', 'article', 'topic'].includes(b.kind)) bad('Choose book, chapter, article, or topic.');
  if (typeof b.title !== 'string' || !b.title || !withinLength(b.title, 300)) bad('Give the podcast a title of at most 300 characters.');
  for (const [k, max] of [['text', 400000], ['url', 2048], ['query', 300]]) {
    if (b[k] != null && (typeof b[k] !== 'string' || !withinLength(b[k], max) || /[\u0000\u007f]/.test(b[k]))) bad(`Invalid ${k}.`);
  }
  for (const [k, values] of [['style', STYLES], ['length', LENGTHS], ['language', ['en']], ['engine', ENGINES]]) {
    if (Object.hasOwn(b, k) && !values.includes(b[k])) bad(`Choose ${values.join(', ')} for ${k}.`);
  }
  if (b.gutenbergId != null && (typeof b.gutenbergId !== 'string' || (b.gutenbergId && !/^[1-9][0-9]{0,6}$/.test(b.gutenbergId)))) bad('Use a Gutenberg book ID from 1 to 9999999 as text.');
  if (b.url) {
    let u;
    try { u = new URL(b.url); } catch { bad('Use a valid public HTTPS URL.'); }
    const host = u.hostname.replace(/\.$/, '');
    if (u.protocol !== 'https:' || u.username || u.password || u.port || /[\\\u0000-\u0020\u007f]/.test(b.url)
      || !/^https:\/\/[^/?#@]+(?:[/?#]|$)/i.test(b.url) || isIP(host)
      || !/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(host)
      || /(?:^|\.)(?:localhost|local|internal|lan)$/i.test(host)) bad('Use a public HTTPS hostname without credentials or a non-443 port.');
  }
  if (b.kind === 'chapter' && !b.text) bad('Chapter podcasts need text.');
  if (b.kind === 'article' && !b.url) bad('Article podcasts need a public HTTPS URL.');
  if (b.kind === 'book' && !b.url && !b.gutenbergId && !b.text) bad('Book podcasts need text, a public HTTPS URL, or a Gutenberg ID.');
  if (b.kind === 'topic' && !b.query) bad('Topic podcasts need a query of at most 300 characters.');
  const out = { style: 'deep-dive', length: 'default', language: 'en', engine: 'notebooklm', ...b };
  const maxBytes = b.kind === 'chapter' || b.text != null ? 450000 : 1000000;
  if (Buffer.byteLength(JSON.stringify(out), 'utf8') > maxBytes) bad('The encoded podcast source is too large. Please send a shorter excerpt.');
  return out;
}
export async function studioJson(cfg, path, uid, { method = 'GET', body, onAccepted, onRejected } = {}) {
  const ctl = new AbortController();
  // Interactive calls stay at 10 s; the scheduler may pass maxWait (the studio's queue admit can take ~10 s).
  const remaining = Math.min(cfg.maxWait ?? 10000, (cfg.deadline ?? Date.now() + 10000) - Date.now());
  if (remaining <= 0) throw problem(502, 'studio_unavailable', 'The podcast studio took too long. Please try again.');
  const timer = setTimeout(() => ctl.abort(), remaining);
  const unavailable = () => problem(502, 'studio_unavailable', 'The podcast studio is not available right now. Please try again.');
  try {
    const r = await fetch(`${cfg.base}/v1/podcasts${path}`, {
      method, headers: { 'X-Studio-Key': cfg.secret, 'X-Mavis-User': uid, accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), signal: ctl.signal, redirect: 'manual', credentials: 'omit',
    });
    if (method === 'POST') {
      if (r.status === 202) await onAccepted?.();
      else await onRejected?.(r.status);
    }
    if (r.status === 404) throw problem(404, 'not_found', 'That podcast was not found.');
    if (r.status === 429) {
      const hint = Number(r.headers.get('retry-after'));
      throw problem(429, 'studio_busy', 'The podcast studio is busy. Please try again shortly.', { retryAfter: Number.isSafeInteger(hint) && hint > 0 ? Math.min(hint, 3600) : 60 });
    }
    if (method === 'DELETE' && r.status === 204) return null;
    if ((r.status !== 422 && r.status !== (method === 'POST' ? 202 : 200)) || method === 'DELETE'
      || !/^application\/json\b/i.test(r.headers.get('content-type') || '') || Number(r.headers.get('content-length')) > 256000) throw unavailable();
    const reader = r.body?.getReader();
    if (!reader) throw unavailable();
    const chunks = [];
    let size = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 256000) { await reader.cancel(); throw unavailable(); }
      chunks.push(value);
    }
    const text = Buffer.concat(chunks).toString('utf8');
    const j = JSON.parse(text);
    if (!j || typeof j !== 'object' || Array.isArray(j)
      || JSON.stringify(j).includes(cfg.secret)
      || JSON.stringify(j, (key, value) => typeof value === 'string' ? safeText(value, value.length) : value).includes(cfg.secret)) throw unavailable();
    if (r.status === 422) {
      if (typeof j.detail !== 'string') throw unavailable();
      throw problem(400, 'bad_request', safeText(j.detail, 300).trim() || 'Please check the podcast source and options.');
    }
    return j;
  } catch (err) {
    if (err.response) throw err;
    throw unavailable();
  } finally { clearTimeout(timer); }
}
export function publicJob(j, id = j.id, { transcript = false } = {}) {
  if (!validJobId(j.id) || j.id !== id || !validStatus(j.status)) throw problem(502, 'studio_bad_response', 'The podcast studio returned an invalid job. Please try again.');
  const out = { id, status: j.status };
  for (const [key, max] of [['title', 300], ['stage', 300], ['error', 1000], ['createdAt', 100]]) {
    if (typeof j[key] === 'string') out[key] = safeText(j[key], max);
  }
  if (Number.isInteger(j.progress) && j.progress >= 0 && j.progress <= 100) out.progress = j.progress;
  if (Number.isSafeInteger(j.durationSec) && j.durationSec >= 0) out.durationSec = j.durationSec;
  if (['notebooklm', 'local'].includes(j.engine)) out.engine = j.engine;
  if (STYLES.includes(j.style)) out.style = j.style;
  if (['book', 'chapter', 'article', 'topic'].includes(j.kind)) out.kind = j.kind;
  if (Array.isArray(j.sources)) out.sources = j.sources.slice(0, 100).filter((s) => s && typeof s.title === 'string').map((s) => {
    const source = { title: safeText(s.title, 300) };
    if (typeof s.url === 'string' && s.url.length <= 2048) {
      try {
        const url = new URL(s.url);
        if (['http:', 'https:'].includes(url.protocol) && !url.username && !url.password) source.url = url.href;
      } catch {}
    }
    return source;
  });
  if (transcript && typeof j.transcript === 'string') out.transcript = j.transcript;
  return out;
}
export async function podcastUsage(uid, now = Date.now()) {
  const s = subStore();
  const doc = await s.get(`podsub/${uid}`, { type: 'json' });
  const timezone = doc?.schedule?.timezone || 'UTC';
  const date = localDate(now, timezone);
  const override = await s.get(`podlimit/${uid}`, { type: 'json' });
  const limit = Number.isInteger(override?.limit) && override.limit >= 1 && override.limit <= 50 ? override.limit : uid === 'owner' ? 30 : 1;
  const key = `use/${uid}/${date.replaceAll('-', '')}`;
  const data = await s.get(key, { type: 'json' });
  return { used: data?.podcast || 0, limit, date, key, timezone };
}
export async function reservePodcastQuota(uid, now = Date.now()) {
  const usage = await podcastUsage(uid, now);
  const s = subStore(), key = usage.key, token = randomUUID();
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key), data = cur.data || {};
    const pending = data.podcastReserved || [];
    if ((data.podcast || 0) + pending.length >= usage.limit) throw problem(429, 'daily_limit', 'You’ve reached today’s podcast limit. Come back after local midnight.', { resetsAt: nextMidnight(now, usage.timezone) });
    if (await writeIfUnchanged(s, key, { ...data, podcastReserved: [...pending, token] }, cur)) return { key, token };
  }
  throw problem(503, 'podcast_busy', 'Your podcast quota is busy. Try again.');
}
export async function settlePodcastQuota(reservation, accepted) {
  if (!reservation) return;
  const s = subStore(), { key, token } = reservation;
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key), data = cur.data || {};
    const pending = data.podcastReserved || [];
    if (!pending.includes(token)) return;
    const next = { ...data, podcastReserved: pending.filter((x) => x !== token), podcast: (data.podcast || 0) + (accepted ? 1 : 0) };
    if (await writeIfUnchanged(s, key, next, cur)) return;
  }
  throw problem(503, 'podcast_busy', 'Your podcast quota is busy. Try again.');
}
// A podcast that fails in the studio gives its slot back: refund once per job id.
export async function refundFailedPodcast(uid, jobId, createdAt, now = Date.now()) {
  const usage = await podcastUsage(uid, createdAt ?? now);
  const s = subStore(), key = usage.key;
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key), data = cur.data || {};
    const refunded = data.refunded || [];
    if (refunded.includes(jobId) || !(data.podcast > 0)) return false;
    if (await writeIfUnchanged(s, key, { ...data, podcast: data.podcast - 1, refunded: [...refunded, jobId] }, cur)) return true;
  }
  return false;
}
export async function changeSlots(uid, change) {
  const s = subStore(), key = `pod-active/${uid}`;
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key);
    const slots = cur.data?.slots || [];
    const next = change(slots);
    if (await writeIfUnchanged(s, key, { slots: next }, cur)) return;
  }
  throw problem(503, 'podcast_busy', 'Your podcast jobs are busy. Try again.');
}
export async function reserveSlot(uid, owner) {
  const reservation = `~reserve-${randomUUID()}`;
  await changeSlots(uid, (slots) => {
    if (!owner && slots.length >= 2) throw problem(429, 'active_limit', 'You already have two active podcasts. Wait for one to finish.');
    return [...slots, reservation];
  });
  return reservation;
}
export const releaseSlot = (uid, id) => changeSlots(uid, (slots) => slots.filter((x) => x !== id));

// Keep deletion ownership outside the visible index so polling never recontacts the studio.
export async function deletePodcastRow(row) {
  const s = subStore();
  await s.setJSON(`pod-id/${row.id}`, { ...row, status: 'deleted' });
  await s.delete(`pod/${row.userId}/${row.id}`);
  if (row.userId !== 'owner') await releaseSlot(row.userId, row.id);
}
export async function rememberPodcast(row, job) {
  const s = subStore(), key = `pod/${row.userId}/${row.id}`;
  const cur = await readForUpdate(s, key);
  // Never resurrect a concurrently deleted row.
  if (cur.data && cur.data.status !== 'deleted') await writeIfUnchanged(s, key, { ...cur.data, status: job.status, lastKnown: job }, cur);
}
export function lastKnownPodcast(row) {
  return { id: row.id, title: row.title || 'Podcast', kind: row.kind || 'chapter', style: row.style || 'deep-dive',
    status: row.status || 'queued', createdAt: new Date(row.createdAt).toISOString(), ...row.lastKnown, ...withMeta(row) };
}
// Briefing context saved at creation: which sites and stories an episode covers, plus when it was made.
export function withMeta(row) {
  const out = Number.isFinite(row?.createdAt) ? { createdAt: new Date(row.createdAt).toISOString() } : {};
  if (row?.meta) { out.sites = row.meta.sites; out.stories = row.meta.stories; if (row.title) out.title = row.title; }
  return out;
}
const inQueue = job => ['queued', 'sourcing', 'writing', 'generating'].includes(job.status);
export async function podcastQueue(cfg) {
  const rows = [];
  for await (const page of subStore().list({ prefix: 'pod/', paginate: true })) {
    rows.push(...await Promise.all(page.blobs.map(b => subStore().get(b.key, { type: 'json' }))));
  }
  const known = new Map(rows.filter(Boolean).map(row => [row.id, row]));
  try {
    const list = await studioJson(cfg, '', 'owner');
    if (!Array.isArray(list.items)) throw new Error('Invalid studio list');
    for (const item of list.items) {
      const row = known.get(item.id);
      if (row) known.set(item.id, { ...row, ...publicJob(item), createdAt: row.createdAt });
    }
  } catch { /* Queue estimates remain available from last-known indexed state. */ }
  return new Map([...known.values()].filter(inQueue).sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id)).map((row, i) => [row.id, i + 1]));
}
export function withQueue(job, queue) {
  const queuePosition = inQueue(job) ? queue.get(job.id) ?? null : null;
  return { ...job, queuePosition, etaMin: queuePosition == null ? null : queuePosition * 5 };
}

// A CAS lease also covers simultaneous taps on different function instances.
export async function createPodcast(uid, input, { cfg = studioConfig(), now = Date.now(), meta = null } = {}) {
  if (!cfg) throw problem(503, 'studio_not_configured', 'The podcast studio is not configured yet.');
  const body = validateCreate(input);
  const hash = createHash('sha256').update(JSON.stringify([body.title, body.text ?? '', body.text ? '' : [body.url, body.query, body.gutenbergId]])).digest('hex');
  const s = subStore(), key = `pod-dedupe/${uid}/${hash}`;
  const deadline = Math.min(cfg.deadline ?? Date.now() + 10000, Date.now() + 10000);
  let claim;
  while (Date.now() < deadline) {
    const cur = await readForUpdate(s, key);
    if (cur.data?.id && now - cur.data.createdAt < 60000) {
      const row = await s.get(`pod/${uid}/${cur.data.id}`, { type: 'json' });
      if (row && row.status !== 'deleted') return { id: row.id, status: row.status, reused: true };
    }
    if (cur.data?.pendingUntil > Date.now()) {
      await new Promise(resolve => setTimeout(resolve, 50));
      continue;
    }
    claim = { token: randomUUID(), pendingUntil: Date.now() + 60000 };
    if (!await writeIfUnchanged(s, key, claim, cur)) continue;
    try {
      const job = await dispatchPodcast(uid, body, { cfg, now, meta });
      await s.setJSON(key, { id: job.id, createdAt: now });
      return job;
    } catch (err) {
      const current = await readForUpdate(s, key);
      if (current.data?.token === claim.token) await writeIfUnchanged(s, key, { pendingUntil: 0 }, current);
      throw err;
    }
  }
  throw problem(409, 'podcast_pending', 'This podcast is already being submitted. Please check again shortly.');
}

async function dispatchPodcast(uid, input, { cfg = studioConfig(), now = Date.now(), meta = null } = {}) {
  if (!cfg) throw problem(503, 'studio_not_configured', 'The podcast studio is not configured yet.');
  const body = validateCreate(input);
  // Active rows remain for lifecycle compatibility; the daily quota is the gate.
  const reservation = uid === 'owner' ? null : await reserveSlot(uid, true);
  let quota, dispatched = false, accepted = false;
  try {
    quota = await reservePodcastQuota(uid, now);
    dispatched = true;
    const result = await studioJson(cfg, '', uid, {
      method: 'POST', body,
      onAccepted: async () => { accepted = true; await settlePodcastQuota(quota, true); },
      onRejected: async (status) => { if (reservation && status < 500 && status !== 408 && status !== 202) await releaseSlot(uid, reservation); },
    });
    const out = publicJob(result);
    if (out.status !== 'queued') throw problem(502, 'studio_bad_response', 'The studio did not return a queued podcast. Please contact the owner.');
    const row = { id: out.id, userId: uid, createdAt: now, title: body.title, status: out.status, ...(meta ? { meta } : {}) };
    const claimed = await subStore().setJSON(`pod-id/${out.id}`, row, { onlyIfNew: true });
    if (!claimed.modified) throw problem(502, 'studio_unavailable', 'The studio returned a duplicate job. Please contact the owner.');
    await subStore().setJSON(`pod/${uid}/${out.id}`, row, { onlyIfNew: true });
    if (reservation) await changeSlots(uid, (slots) => slots.map((x) => x === reservation ? out.id : x).slice(-100));
    return { id: out.id, status: out.status };
  } finally {
    if (!accepted) await settlePodcastQuota(quota, false);
    if (!dispatched && reservation) await releaseSlot(uid, reservation);
  }
}
