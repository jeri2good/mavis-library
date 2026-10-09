// POST /api/sync — push local changes and pull everything changed elsewhere.
//   (Bearer) { changes: { shelf: [row…], … }, cursors: { shelf: 12, … } }
//   → { cursors: { shelf: 15, … }, rows: { shelf: [row…], … }, more: false }
// Each row carries `k` (its id within the table) and `updatedAt` (the device
// clock when it last changed). The newer updatedAt wins; ties keep the stored
// row. Each table is one Blob per user, written with compare-and-swap.

import { json, fail, onlyPost, readJson, softLimit, clientKey } from '../lib/shared.mjs';
import { kv, requireUser, readForUpdate, writeIfUnchanged } from '../lib/accounts.mjs';

export const TABLES = ['shelf', 'progress', 'annotations', 'vocab', 'plans', 'prefs'];
const MAX_ROW_BYTES = 16_000;
const MAX_ROWS_PER_TABLE = 20_000;
const MAX_PULL = 1500;

export function cleanRow(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
  const k = typeof row.k === 'string' ? row.k : '';
  if (!k || k.length > 300) return null;
  const updatedAt = Number(row.updatedAt);
  if (!Number.isFinite(updatedAt) || updatedAt <= 0 || updatedAt > Date.now() + 864e5) return null;
  const { owner, dirty, id, _rev, ...rest } = row; // eslint-disable-line no-unused-vars
  const out = { ...rest, k, updatedAt };
  if (JSON.stringify(out).length > MAX_ROW_BYTES) return null;
  return out;
}

async function mergeTable(store, key, incoming) {
  for (let attempt = 0; attempt < 8; attempt++) {
    const cur = await readForUpdate(store, key);
    const doc = cur.data || { rev: 0, rows: {} };
    let rev = doc.rev;
    let accepted = 0;
    for (const row of incoming) {
      const have = doc.rows[row.k];
      if (have && have.updatedAt >= row.updatedAt) continue;
      if (!have && Object.keys(doc.rows).length >= MAX_ROWS_PER_TABLE) continue;
      rev += 1;
      doc.rows[row.k] = { ...row, _rev: rev };
      accepted += 1;
    }
    if (!accepted) return doc;
    doc.rev = rev;
    if (await writeIfUnchanged(store, key, doc, cur)) return doc;
    await new Promise((r) => setTimeout(r, 20 + Math.random() * 80 * (attempt + 1)));
  }
  throw Object.assign(new Error('Another device is syncing right now. Try again in a moment.'), { status: 409 });
}

export default async (req, context) => {
  const bad = onlyPost(req);
  if (bad) return bad;
  const a = await requireUser(req);
  if (a.response) return a.response;
  if (softLimit(`sync:${a.user.id}:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Syncing too often. Wait a minute.');
  let body;
  try { body = await readJson(req, 4_000_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  if (!body || typeof body !== 'object') return fail(400, 'bad_request', 'Send a JSON object.');
  const store = kv('mavis-data');
  const cursors = {};
  const rows = {};
  let more = false;
  let budget = MAX_PULL;
  try {
    for (const table of TABLES) {
      const incoming = (Array.isArray(body.changes?.[table]) ? body.changes[table] : []).slice(0, 5000).map(cleanRow).filter(Boolean);
      const key = `${a.user.id}/${table}`;
      const doc = incoming.length ? await mergeTable(store, key, incoming) : ((await store.get(key, { type: 'json' })) || { rev: 0, rows: {} });
      const since = Math.max(0, Number(body.cursors?.[table]) || 0);
      const changed = Object.values(doc.rows).filter((r) => r._rev > since).sort((x, y) => x._rev - y._rev);
      const take = changed.slice(0, Math.max(0, budget));
      budget -= take.length;
      if (take.length < changed.length) more = true;
      rows[table] = take;
      cursors[table] = take.length < changed.length ? (take.length ? take[take.length - 1]._rev : since) : doc.rev;
    }
  } catch (err) {
    return fail(err.status || 502, 'sync_failed', err.status ? err.message : 'Sync storage is unavailable right now.');
  }
  return json({ cursors, rows, more });
};

export const config = {
  path: '/api/sync',
  method: 'POST',
  rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
