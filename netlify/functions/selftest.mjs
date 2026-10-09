// TEMPORARY: live check of accounts + sync on hosted Netlify Blobs.
// Requires ?code=<owner access code>. Creates a throwaway account, syncs from
// twelve "devices" at once, verifies nothing was lost, then deletes it.
import { json, env } from '../lib/shared.mjs';
import account from './account.mjs';
import sync from './sync.mjs';

export default async (req) => {
  const url = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || url.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const t0 = Date.now();
  const post = (path, body, token) => new Request(`${url.origin}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  const out = {};
  try {
    const email = `selftest-${Date.now()}@example.invalid`;
    const su = await account(post('/api/account/signup', { email, password: 'selftest-pass-9' }), { params: { action: 'signup' } });
    const { token } = await su.json();
    out.signup = su.status;
    const base = Date.now();
    const pushes = await Promise.all(Array.from({ length: 12 }, (_, i) => sync(post('/api/sync', { changes: { progress: [{ k: `b${i}`, percent: i / 20, updatedAt: base + i }] } }, token), {})));
    out.pushStatuses = [...new Set(pushes.map((r) => r.status))];
    const first = await (await sync(post('/api/sync', {}, token), {})).json();
    out.rowsAfter12ConcurrentPushes = first.rows?.progress?.length;
    const again = await (await sync(post('/api/sync', { changes: { progress: [{ k: 'b3', percent: 0.99, updatedAt: Date.now() }] }, cursors: first.cursors }, token), {})).json();
    out.newerWins = again.rows?.progress?.find((r) => r.k === 'b3')?.percent === 0.99;
    const del = await account(post('/api/account/delete', { password: 'selftest-pass-9' }, token), { params: { action: 'delete' } });
    out.deleted = del.status;
    const gone = await account(post('/api/account/signin', { email, password: 'selftest-pass-9' }), { params: { action: 'signin' } });
    out.signinAfterDelete = gone.status;
  } catch (err) {
    out.error = `${err.name}: ${err.message}`.slice(0, 300);
  }
  out.ms = Date.now() - t0;
  return json(out);
};

export const config = { path: '/api/selftest' };
