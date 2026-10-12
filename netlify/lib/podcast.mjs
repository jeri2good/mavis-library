import { createHmac, timingSafeEqual, randomUUID } from 'node:crypto';
import { env } from './shared.mjs';
import { subStore, problem } from './subs.mjs';
import { readForUpdate, writeIfUnchanged } from './accounts.mjs';

export const validJobId = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(id);
export const terminalStatus = (status) => ['completed', 'ready', 'failed', 'cancelled', 'canceled', 'deleted'].includes(status);
export const validStatus = (status) => ['pending', 'queued', 'running', 'processing', 'completed', 'ready', 'failed', 'cancelled', 'canceled', 'deleted'].includes(status);
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
  for (const [k, max] of [['text', 400000], ['url', 2000], ['query', 300], ['style', 80], ['language', 40], ['engine', 80]]) {
    if (b[k] != null && (typeof b[k] !== 'string' || !b[k].trim() || b[k].length > max || /[\u0000\u007f]/.test(b[k]))) bad(`Invalid ${k}.`);
  }
  if (b.length != null && !((typeof b.length === 'string' && /^[A-Za-z0-9_-]{1,40}$/.test(b.length)) || (Number.isFinite(b.length) && b.length > 0 && b.length <= 120))) bad('Invalid length.');
  if (b.gutenbergId != null && (!Number.isSafeInteger(b.gutenbergId) || b.gutenbergId < 1)) bad('Use a positive Gutenberg book ID.');
  if (b.url != null) {
    let u;
    try { u = new URL(b.url); } catch { bad('Use a valid HTTPS URL.'); }
    if (u.protocol !== 'https:' || u.username || u.password) bad('Use a HTTPS URL without credentials.');
  }
  if (b.kind === 'chapter' && !b.text) bad('Chapter podcasts need text.');
  if (b.kind === 'article' && !b.url) bad('Article podcasts need a HTTPS URL.');
  if (b.kind === 'book' && !b.url && !b.gutenbergId) bad('Book podcasts need a HTTPS URL or Gutenberg ID.');
  if (b.kind === 'topic' && !b.query) bad('Topic podcasts need a query under 300 characters.');
  if (b.text != null && b.kind !== 'chapter') bad('Text is only supported for chapter podcasts.');
  if (b.query != null && b.kind !== 'topic') bad('Query is only supported for topic podcasts.');
  if (b.url != null && !['article', 'book'].includes(b.kind)) bad('URLs are only supported for articles and books.');
  if (b.gutenbergId != null && b.kind !== 'book') bad('Gutenberg IDs are only supported for books.');
  return Object.fromEntries(fields.filter((k) => b[k] != null).map((k) => [k, b[k]]));
}
export async function studioJson(cfg, path, uid, { method = 'GET', body } = {}) {
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
    if (r.status === 404) throw problem(404, 'not_found', 'That podcast was not found.');
    if (!r.ok || !/^application\/json\b/i.test(r.headers.get('content-type') || '') || Number(r.headers.get('content-length')) > 256000) throw unavailable();
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
    return j;
  } catch (err) {
    if (err.response) throw err;
    throw unavailable();
  } finally { clearTimeout(timer); }
}
export function publicJob(j, id = j.id) {
  if (!validJobId(j.id) || j.id !== id || !validStatus(j.status)) throw problem(502, 'studio_unavailable', 'The podcast studio returned an invalid job. Please try again.');
  return { id, status: j.status, ...(typeof j.title === 'string' ? { title: j.title.slice(0, 300) } : {}) };
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
