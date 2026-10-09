// POST /api/sync — push local changes and pull everything changed elsewhere.
//   (Bearer) { changes: { shelf: [row…], … }, cursors: { shelf: "<cursor>", … } }
//   → { cursors: { … }, rows: { shelf: [row…], … }, more: false }
//
// Storage never rewrites shared data, so devices syncing at the same moment
// can't overwrite each other (hosted Blobs' conditional writes proved unreliable
// under bursts). Each push is a new immutable "batch" blob:
//     <user>/<table>/b/<13-digit server ms>-<random>
// Pull lists the batches after the device's cursor (re-reading the last two
// minutes, in case a batch became visible late) and the newest snapshot:
//     <user>/<table>/s/<name of the newest batch it includes>
// Old batches are folded into a snapshot now and then. Rows carry `k` (id in
// the table) and `updatedAt`; the newest updatedAt wins, here and on devices.

import { randomBytes } from 'node:crypto';
import { json, fail, onlyPost, readJson, softLimit, clientKey } from '../lib/shared.mjs';
import { kv, requireUser } from '../lib/accounts.mjs';

export const TABLES = ['shelf', 'progress', 'annotations', 'vocab', 'plans', 'prefs'];
const MAX_ROW_BYTES = 16_000;
const MAX_PUSH_ROWS = 5000;
const MAX_BATCH_READS = 300;
const OVERLAP_MS = 2 * 60_000;
const COMPACT_AFTER = 40; // batches
const COMPACT_AGE_MS = 10 * 60_000;

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

const pad = (ms) => String(Math.max(0, Math.floor(ms))).padStart(13, '0');
export const batchName = (ms = Date.now()) => `${pad(ms)}-${randomBytes(5).toString('hex')}`;
const nameTime = (name) => Number(String(name || '').slice(0, 13)) || 0;
const validCursor = (c) => (typeof c === 'string' && /^\d{13}-[0-9a-f]{10}$/.test(c) ? c : '');

/** Newest updatedAt wins; ties keep the first seen. */
export function mergeRows(into, rows) {
  for (const r of rows || []) {
    if (!r?.k) continue;
    const have = into.get(r.k);
    if (!have || r.updatedAt > have.updatedAt) into.set(r.k, r);
  }
  return into;
}

async function listTable(store, prefix) {
  const { blobs } = await store.list({ prefix });
  const batches = [];
  const snaps = [];
  for (const b of blobs) {
    const rest = b.key.slice(prefix.length);
    if (rest.startsWith('b/')) batches.push(rest.slice(2));
    else if (rest.startsWith('s/')) snaps.push(rest.slice(2));
  }
  batches.sort();
  snaps.sort();
  return { batches, snaps };
}

async function pullTable(store, prefix, since) {
  const { batches, snaps } = await listTable(store, prefix);
  const latest = snaps[snaps.length - 1] || '';
  const merged = new Map();
  let cursor = since;
  let complete = true;
  let from;
  if (latest && (!since || since <= latest)) {
    const snap = await store.get(`${prefix}s/${latest}`, { type: 'json' });
    if (snap) { mergeRows(merged, Object.values(snap.rows || {})); cursor = latest; from = latest; }
    else complete = false;
  } else {
    from = since ? `${pad(nameTime(since) - OVERLAP_MS)}` : '';
  }
  const todo = batches.filter((n) => n > (from || ''));
  const take = todo.slice(0, MAX_BATCH_READS);
  const docs = await Promise.all(take.map((n) => store.get(`${prefix}b/${n}`, { type: 'json' }).catch(() => null)));
  take.forEach((n, i) => {
    if (!docs[i]) { complete = false; return; } // folded into a snapshot mid-read; next sync catches it
    mergeRows(merged, docs[i].rows);
    if (complete && n > cursor) cursor = n;
  });
  return { rows: [...merged.values()], cursor: complete ? cursor : since, more: !complete || todo.length > take.length, batches, snaps };
}

/** Fold old batches into a snapshot. Safe to run concurrently: it only adds a snapshot, then deletes what it covered. */
async function compact(store, prefix, batches, snaps) {
  const cutoff = pad(Date.now() - COMPACT_AGE_MS);
  const old = batches.filter((n) => n < cutoff);
  if (batches.length < COMPACT_AFTER || old.length < COMPACT_AFTER / 2) return false;
  const merged = new Map();
  const latest = snaps[snaps.length - 1];
  if (latest) {
    const snap = await store.get(`${prefix}s/${latest}`, { type: 'json' });
    if (!snap) return false;
    mergeRows(merged, Object.values(snap.rows || {}));
  }
  const docs = await Promise.all(old.map((n) => store.get(`${prefix}b/${n}`, { type: 'json' })));
  if (docs.some((d) => !d)) return false; // another compaction is running
  docs.forEach((d) => mergeRows(merged, d.rows));
  const name = old[old.length - 1];
  if (latest && latest >= name) return false;
  await store.setJSON(`${prefix}s/${name}`, { rows: Object.fromEntries(merged) });
  for (const n of old) await store.delete(`${prefix}b/${n}`);
  for (const s of snaps) if (s < name) await store.delete(`${prefix}s/${s}`);
  return true;
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
  try {
    // 1. Push: one new immutable batch per table with changes.
    await Promise.all(TABLES.map(async (table) => {
      const incoming = (Array.isArray(body.changes?.[table]) ? body.changes[table] : []).slice(0, MAX_PUSH_ROWS).map(cleanRow).filter(Boolean);
      if (incoming.length) await store.setJSON(`${a.user.id}/${table}/b/${batchName()}`, { rows: incoming });
    }));
    // 2. Pull everything after each cursor.
    const pulled = await Promise.all(TABLES.map((table) => pullTable(store, `${a.user.id}/${table}/`, validCursor(body.cursors?.[table]))));
    TABLES.forEach((table, i) => {
      rows[table] = pulled[i].rows;
      cursors[table] = pulled[i].cursor;
      if (pulled[i].more) more = true;
    });
    // 3. Now and then, tidy up (best effort).
    await Promise.all(TABLES.map((table, i) => compact(store, `${a.user.id}/${table}/`, pulled[i].batches, pulled[i].snaps).catch(() => false)));
  } catch (err) {
    console.error('sync error', err?.message);
    return fail(err.status || 502, 'sync_failed', err.status ? err.message : 'Sync storage is unavailable right now.');
  }
  return json({ cursors, rows, more });
};

export const config = {
  path: '/api/sync',
  method: 'POST',
  rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
