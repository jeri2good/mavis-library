// TEMPORARY: live check of accounts + sync on hosted Netlify Blobs.
// Requires ?code=<owner access code>. Creates a throwaway account, syncs from
// several "devices" at once, verifies nothing was lost, then deletes it.
import { json, env } from '../lib/shared.mjs';
import { kv } from '../lib/accounts.mjs';
import account from './account.mjs';
import sync from './sync.mjs';

export default async (req) => {
  const url = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || url.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const t0 = Date.now();
  const post = (path, body, token) => new Request(`${url.origin}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  const out = {};
  try {
    const probe = kv('mavis-selftest');
    await probe.setJSON('p', { at: Date.now() });
    const meta = await probe.getWithMetadata('p', { type: 'json' });
    out.getEtag = Boolean(meta?.etag);
    out.casStale = (await probe.setJSON('p', { x: 1 }, { onlyIfMatch: '"stale"' })).modified === false;
    await probe.delete('p');

    const email = `selftest-${Date.now()}@example.invalid`;
    const su = await account(post('/api/account/signup', { email, password: 'selftest-pass-9' }), { params: { action: 'signup' } });
    const sj = await su.json();
    out.signup = su.status;
    const token = sj.token;
    const base = Date.now();
    const pushes = await Promise.all([0, 1, 2, 3, 4, 5].map((i) => sync(post('/api/sync', { changes: { progress: [{ k: `b${i}`, percent: i / 10, updatedAt: base + i }] } }, token), {})));
    out.pushStatuses = pushes.map((r) => r.status);
    const all = await (await sync(post('/api/sync', { cursors: {} }, token), {})).json();
    out.rowsAfterConcurrentPush = all.rows?.progress?.length;
    const del = await account(post('/api/account/delete', { password: 'selftest-pass-9' }, token), { params: { action: 'delete' } });
    out.deleted = del.status;
  } catch (err) {
    out.error = `${err.name}: ${err.message}`.slice(0, 300);
  }
  out.ms = Date.now() - t0;
  return json(out);
};

export const config = { path: '/api/selftest' };
