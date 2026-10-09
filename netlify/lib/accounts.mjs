// Mavis accounts, stored in Netlify Blobs (no outside database).
//
//  * Passwords and recovery codes are hashed with scrypt; plain values are
//    never stored or logged.
//  * Sessions are stateless tokens signed with AUTH_SECRET (HMAC-SHA256).
//    Each user has a token version; bumping it signs out every device.
//  * Blobs are written with compare-and-swap (onlyIfMatch / onlyIfNew) so two
//    devices writing at once can't overwrite each other.

import { getStore } from '@netlify/blobs';
import { scrypt as scryptCb, randomBytes, timingSafeEqual, createHmac, createHash, randomUUID } from 'node:crypto';
import { promisify } from 'node:util';
import { env, fail } from './shared.mjs';

const scrypt = promisify(scryptCb);

/** A strongly consistent Blobs store. MAVIS_BLOBS_URL points tests at a local Blobs server. */
export function kv(name) {
  const local = env('MAVIS_BLOBS_URL');
  if (local) {
    return getStore({
      name, siteID: 'local-site', token: env('MAVIS_BLOBS_TOKEN') || 'local-token',
      edgeURL: local, uncachedEdgeURL: local, consistency: 'strong',
      fetch: globalThis.__mavisRealFetch || globalThis.fetch,
    });
  }
  return getStore({ name, consistency: 'strong' });
}

export const accountsEnabled = () => Boolean(env('AUTH_SECRET'));

/**
 * Read a JSON blob together with the ETag needed for a compare-and-swap write.
 * If the read doesn't carry an ETag, take it from a listing first and read the
 * data after: a write in between changes the ETag, so the later swap fails and
 * the caller retries instead of overwriting newer data.
 */
export async function readForUpdate(store, key) {
  const cur = await store.getWithMetadata(key, { type: 'json' });
  if (!cur || cur.data == null) return { data: null, etag: null };
  if (cur.etag) return { data: cur.data, etag: cur.etag };
  const { blobs } = await store.list({ prefix: key });
  const hit = blobs.find((b) => b.key === key);
  if (!hit?.etag) return { data: null, etag: null, unknown: true };
  const data = await store.get(key, { type: 'json' });
  return data == null ? { data: null, etag: null } : { data, etag: hit.etag };
}

/** Write after readForUpdate: only if unchanged (or still absent). */
export async function writeIfUnchanged(store, key, value, read) {
  if (read.unknown) return false;
  const res = read.data == null
    ? await store.setJSON(key, value, { onlyIfNew: true })
    : await store.setJSON(key, value, { onlyIfMatch: read.etag });
  return res.modified;
}

// ---------- hashing ----------
const b64u = (buf) => Buffer.from(buf).toString('base64url');
const SCRYPT = { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

export async function hashSecret(secret) {
  const salt = randomBytes(16);
  const key = await scrypt(String(secret).normalize('NFKC'), salt, 32, SCRYPT);
  return `s1$${b64u(salt)}$${b64u(key)}`;
}

export async function verifySecret(secret, stored) {
  const [v, salt, hash] = String(stored || '').split('$');
  if (v !== 's1' || !salt || !hash) return false;
  const key = await scrypt(String(secret).normalize('NFKC'), Buffer.from(salt, 'base64url'), 32, SCRYPT);
  const want = Buffer.from(hash, 'base64url');
  return want.length === key.length && timingSafeEqual(want, key);
}

export const normEmail = (e) => String(e || '').trim().toLowerCase().normalize('NFKC');
export const emailKey = (e) => createHash('sha256').update(normEmail(e)).digest('hex');
export const validEmail = (e) => /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/.test(normEmail(e)) && normEmail(e).length <= 254;

export function passwordProblem(pw) {
  pw = String(pw || '');
  if (pw.length < 8) return 'Use at least 8 characters for your password.';
  if (pw.length > 200) return 'That password is too long.';
  if (/^(.)\1+$/.test(pw) || /^(password|12345678|qwertyui)/i.test(pw)) return 'Choose a less common password.';
  return null;
}

// Recovery codes: 16 characters from an unambiguous alphabet (80 bits).
const ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
export function newRecoveryCode() {
  const bytes = randomBytes(16);
  let s = '';
  for (let i = 0; i < 16; i++) s += ALPHABET[bytes[i] % 32];
  return s.match(/.{4}/g).join('-');
}
export const normRecovery = (c) => String(c || '').toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/O/g, '0').replace(/I/g, '1');

// ---------- sessions ----------
const SESSION_DAYS = 180;

function secret() {
  const s = env('AUTH_SECRET');
  if (!s || s.length < 32) throw Object.assign(new Error('Accounts are not set up on this server.'), { status: 503 });
  return s;
}

export function signToken(user) {
  const payload = b64u(JSON.stringify({ u: user.id, v: user.tokenVersion || 0, i: Date.now(), e: Date.now() + SESSION_DAYS * 864e5 }));
  const sig = b64u(createHmac('sha256', secret()).update(payload).digest());
  return `v1.${payload}.${sig}`;
}

export function readToken(token) {
  const [v, payload, sig] = String(token || '').split('.');
  if (v !== 'v1' || !payload || !sig) return null;
  const want = createHmac('sha256', secret()).update(payload).digest();
  const got = Buffer.from(sig, 'base64url');
  if (got.length !== want.length || !timingSafeEqual(got, want)) return null;
  try {
    const p = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!p.u || typeof p.e !== 'number' || p.e < Date.now()) return null;
    return p;
  } catch { return null; }
}

// ---------- user records ----------
//   users:  u/<id>      → { id, email, pw, rc, tokenVersion, createdAt, updatedAt }
//           e/<sha256>  → { id }              (email index, written onlyIfNew)
//           t/<sha256>  → { fails, until }    (sign-in throttle)

export async function getUser(id) {
  if (!/^[0-9a-f-]{36}$/.test(String(id))) return null;
  return (await kv('mavis-users').get(`u/${id}`, { type: 'json' })) || null;
}

export async function findByEmail(email) {
  const idx = await kv('mavis-users').get(`e/${emailKey(email)}`, { type: 'json' });
  return idx?.id ? getUser(idx.id) : null;
}

export async function createUser(email, password) {
  const store = kv('mavis-users');
  const id = randomUUID();
  const claim = await store.setJSON(`e/${emailKey(email)}`, { id }, { onlyIfNew: true });
  if (!claim.modified) return { exists: true };
  const recoveryCode = newRecoveryCode();
  const user = {
    id, email: normEmail(email), pw: await hashSecret(password), rc: await hashSecret(normRecovery(recoveryCode)),
    tokenVersion: 0, createdAt: Date.now(), updatedAt: Date.now(),
  };
  await store.setJSON(`u/${id}`, user);
  return { user, recoveryCode };
}

/** Read-modify-write a user with compare-and-swap. */
export async function updateUser(id, change) {
  const store = kv('mavis-users');
  for (let attempt = 0; attempt < 5; attempt++) {
    const cur = await readForUpdate(store, `u/${id}`);
    if (!cur.data) return null;
    const next = { ...cur.data, ...(await change(cur.data)), updatedAt: Date.now() };
    if (await writeIfUnchanged(store, `u/${id}`, next, cur)) return next;
  }
  throw Object.assign(new Error('Your account is busy. Try again.'), { status: 409 });
}

export async function deleteUser(user) {
  const users = kv('mavis-users');
  const data = kv('mavis-data');
  const { blobs } = await data.list({ prefix: `${user.id}/` });
  for (const b of blobs) await data.delete(b.key);
  await users.delete(`e/${emailKey(user.email)}`);
  await users.delete(`u/${user.id}`);
}

// ---------- sign-in throttle (per email) ----------
export async function throttleCheck(email) {
  const t = await kv('mavis-users').get(`t/${emailKey(email)}`, { type: 'json' });
  return t && t.until > Date.now() ? Math.ceil((t.until - Date.now()) / 1000) : 0;
}
export async function throttleFail(email) {
  const store = kv('mavis-users');
  const key = `t/${emailKey(email)}`;
  const t = (await store.get(key, { type: 'json' })) || { fails: 0, until: 0 };
  const fails = t.fails + 1;
  const lock = fails >= 5 ? Math.min(15 * 60, 30 * 2 ** (fails - 5)) * 1000 : 0;
  await store.setJSON(key, { fails, until: lock ? Date.now() + lock : 0 });
}
export async function throttleClear(email) {
  await kv('mavis-users').delete(`t/${emailKey(email)}`);
}

// ---------- request auth ----------
export const publicUser = (u) => ({ id: u.id, email: u.email, createdAt: u.createdAt });

/** Returns { user } or { response } (an error to send). */
export async function requireUser(req) {
  if (!accountsEnabled()) return { response: fail(503, 'accounts_off', 'Accounts are not switched on for this site.') };
  const m = /^Bearer\s+(\S+)$/.exec(req.headers.get('authorization') || '');
  const p = m && readToken(m[1]);
  if (!p) return { response: fail(401, 'signed_out', 'Your session has ended. Sign in again.') };
  const user = await getUser(p.u);
  if (!user || (user.tokenVersion || 0) !== p.v) return { response: fail(401, 'signed_out', 'Your session has ended. Sign in again.') };
  return { user, token: p };
}
