import { createHmac, timingSafeEqual, randomUUID } from 'node:crypto';
import { env } from './shared.mjs';
import { subStore, problem, caps, utcDay, resetsAt } from './subs.mjs';
import { readForUpdate, writeIfUnchanged } from './accounts.mjs';

export const validJobId = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id);
export const terminalStatus = (status) => ['ready', 'failed'].includes(status);
export const validStatus = (status) => ['queued', 'sourcing', 'writing', 'generating', 'ready', 'failed'].includes(status);
export const STYLES = Object.freeze(['deep-dive', 'brief', 'debate', 'critique']);
export const LENGTHS = Object.freeze(['short', 'default', 'long']);
export const ENGINES = Object.freeze(['auto', 'notebooklm', 'local']);
const safeText = (value, max) => value.replace(/[\u0000-\u001f\u007f-\u009f]/g, '').slice(0, max);
export function signAudio(secret, id, exp, uid) {
  return createHmac('sha256', secret).update(`${id}.${exp}.${uid}`, 'utf8').digest('hex');
}
export function verifyAudio(secret, id, exp, uid, sig, now = Date.now()) {
  if (!secret || !validJobId(id) || !/^(owner|[0-9a-f-]{36})$/.test(uid) || !/^\d{10}$/.test(String(exp)) || !/^[0-9a-f]{64}$/.test(String(sig))) return false;
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
  if (!['book', 'chapter', 'article', 'topic'].includes(b.kind)) bad('Choose book, chapter, article, or topic.');
  if (typeof b.title !== 'string' || !b.title.trim() || b.title.length > 300) bad('Give the podcast a title under 300 characters.');
  for (const [k, max] of [['text', 400000], ['url', 2000], ['query', 300]]) {
    if (Object.hasOwn(b, k) && (typeof b[k] !== 'string' || !b[k].trim() || b[k].length > max || /[\u0000\u007f]/.test(b[k]))) bad(`Invalid ${k}.`);
  }
  for (const [k, values] of [['style', STYLES], ['length', LENGTHS], ['language', ['en']], ['engine', ENGINES]]) {
    if (Object.hasOwn(b, k) && !values.includes(b[k])) bad(`Choose ${values.join(', ')} for ${k}.`);
  }
  if (Object.hasOwn(b, 'gutenbergId') && (typeof b.gutenbergId !== 'string' || !/^[1-9][0-9]*$/.test(b.gutenbergId) || b.gutenbergId.length > 20)) bad('Use a positive Gutenberg book ID as text.');
  if (b.url != null) {
    let u;
    try { u = new URL(b.url); } catch { bad('Use a valid HTTPS URL.'); }
    if (u.protocol !== 'https:' || u.username || u.password) bad('Use a HTTPS URL without credentials.');
  }
  if (b.kind === 'chapter' && !b.text) bad('Chapter podcasts need text.');
  if (b.kind === 'article' && !b.url) bad('Article podcasts need a HTTPS URL.');
  if (b.kind === 'book' && !b.url && !b.gutenbergId && !b.text) bad('Book podcasts need text, a HTTPS URL, or a Gutenberg ID.');
  if (b.kind === 'topic' && !b.query) bad('Topic podcasts need a query under 300 characters.');
  if (b.text != null && !['chapter', 'book'].includes(b.kind)) bad('Text is only supported for chapter and book podcasts.');
  if (b.query != null && b.kind !== 'topic') bad('Query is only supported for topic podcasts.');
  if (b.url != null && !['article', 'book'].includes(b.kind)) bad('URLs are only supported for articles and books.');
  if (b.gutenbergId != null && b.kind !== 'book') bad('Gutenberg IDs are only supported for books.');
  return { style: 'deep-dive', length: 'default', language: 'en', engine: 'auto', ...Object.fromEntries(fields.filter((k) => b[k] != null).map((k) => [k, b[k]])) };
}
export async function studioJson(cfg, path, uid, { method = 'GET', body, onAccepted, onRejected } = {}) {
  const ctl = new AbortController();
  const remaining = Math.min(10000, (cfg.deadline ?? Date.now() + 10000) - Date.now());
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
    if (!j || typeof j !== 'object' || Array.isArray(j) || JSON.stringify(j).includes(cfg.secret)) throw unavailable();
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
  for (const key of ['progress', 'durationSec']) if (Number.isFinite(j[key]) && j[key] >= 0) out[key] = j[key];
  if (ENGINES.includes(j.engine)) out.engine = j.engine;
  if (STYLES.includes(j.style)) out.style = j.style;
  if (['book', 'chapter', 'article', 'topic'].includes(j.kind)) out.kind = j.kind;
  if (Array.isArray(j.sources)) out.sources = j.sources.slice(0, 100).filter((s) => s && typeof s.title === 'string').map((s) => {
    const source = { title: safeText(s.title, 300) };
    if (typeof s.url === 'string' && s.url.length <= 2000) {
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
export async function reservePodcastQuota(uid, now = Date.now()) {
  const s = subStore(), key = `use/${uid}/${utcDay(now)}`, token = randomUUID();
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key), data = cur.data || {};
    const pending = data.podcastReserved || [];
    if ((data.podcast || 0) + pending.length >= caps().podcast) throw problem(429, 'daily_limit', 'You’ve reached today’s fair-use limit. Come back after midnight UTC.', { resetsAt: resetsAt(now) });
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
