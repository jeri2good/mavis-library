// TEMPORARY: investigate compare-and-swap behaviour on hosted Netlify Blobs.
import { json, env } from '../lib/shared.mjs';
import { kv, readForUpdate, writeIfUnchanged } from '../lib/accounts.mjs';

export default async (req) => {
  const url = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || url.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const store = kv('mavis-selftest');
  const key = `counter-${Date.now()}`;
  const log = [];
  const t0 = Date.now();
  const worker = async (w) => {
    for (let attempt = 0; attempt < 10; attempt++) {
      const cur = await readForUpdate(store, key);
      const doc = cur.data || { n: 0, by: [] };
      const next = { n: doc.n + 1, by: [...doc.by, w] };
      const ok = await writeIfUnchanged(store, key, next, cur);
      log.push({ w, attempt, read: cur.data ? cur.data.n : null, etag: (cur.etag || '').slice(0, 12), ok, t: Date.now() - t0 });
      if (ok) return;
      await new Promise((r) => setTimeout(r, 20 + Math.random() * 60));
    }
  };
  await Promise.all([1, 2, 3, 4, 5, 6].map(worker));
  const final = await store.get(key, { type: 'json' });
  const meta = await store.getWithMetadata(key, { type: 'json' });
  await store.delete(key);
  return json({ final, finalEtag: (meta?.etag || '').slice(0, 20), log });
};

export const config = { path: '/api/selftest' };
