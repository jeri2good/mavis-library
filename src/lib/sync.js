// Cross-device sync of shelf metadata, reading progress, annotations (and
// any other synced stores) through Mavis's own /api/sync endpoint.
// Book files are NOT synced; other devices show "file on another device".
//
// One request pushes this device's unsynced rows and pulls everything changed
// since our last cursor. The newer change wins (by the device clock when it was
// made); the server applies the same rule, so an older device can never
// overwrite newer work.

import { sessionToken, sessionExpired } from './auth.js';
import * as store from './store.js';
import { debounce } from './ui.js';

export const SYNCED = store.SYNCED_STORES;

let uid = null;
let timer = null;
let running = false;
let queued = false;
const state = { status: 'off', lastSyncedAt: null, error: null };
const listeners = new Set();
export function onSyncState(fn) { listeners.add(fn); fn(state); return () => listeners.delete(fn); }
function set(patch) { Object.assign(state, patch); for (const fn of listeners) fn(state); }
export function syncState() { return state; }

const suffix = (id) => id.slice(uid.length + 1);
const toRemote = (r) => {
  const { id, owner, dirty, ...rest } = r; // eslint-disable-line no-unused-vars
  return { ...rest, k: suffix(id) };
};
const toLocal = (x) => {
  const { _rev, ...rest } = x; // eslint-disable-line no-unused-vars
  return { ...rest, id: `${uid}|${x.k}` };
};

class SyncError extends Error { constructor(m, status) { super(m); this.status = status; } }

async function exchange(changes) {
  const cursorKey = `sync-cursors|${uid}`;
  const cursors = store.getSetting(cursorKey, {});
  let r;
  try {
    r = await fetch('/api/sync', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${sessionToken()}` },
      body: JSON.stringify({ changes, cursors }),
      cache: 'no-store',
    });
  } catch { throw new SyncError('Could not reach Mavis', 0); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new SyncError(j.message || `Sync answered ${r.status}`, r.status);
  let pulled = 0;
  for (const table of SYNCED) {
    const rows = j.rows?.[table] || [];
    if (rows.length) pulled += await store.applyRemote(table, uid, rows.map(toLocal));
  }
  await store.setSetting(cursorKey, { ...cursors, ...(j.cursors || {}) });
  return { pulled, more: !!j.more };
}

async function pushAndPull() {
  const dirty = await store.collectDirty(uid);
  const changes = {};
  for (const table of SYNCED) changes[table] = (dirty[table] || []).map(toRemote);
  const out = await exchange(changes);
  for (const table of SYNCED) if (dirty[table]?.length) await store.markClean(table, dirty[table]);
  // Keep pulling if the server had more than one response's worth.
  for (let i = 0; out.more && i < 20; i++) Object.assign(out, await exchange({}));
}

export async function syncNow() {
  if (!uid) return;
  if (running) { queued = true; return; }
  if (!navigator.onLine) { set({ status: 'offline' }); return; }
  running = true;
  set({ status: 'syncing', error: null });
  try {
    await pushAndPull();
    set({ status: 'idle', lastSyncedAt: Date.now() });
  } catch (err) {
    console.warn('Sync failed', err);
    if (err.status === 401) { set({ status: 'off', error: null }); sessionExpired(); return; }
    set({ status: err.status === 0 ? 'offline' : 'error', error: err.message || 'Sync failed' });
  } finally {
    running = false;
    if (queued) { queued = false; setTimeout(syncNow, 500); }
  }
}

const schedule = debounce(syncNow, 2500);
let unsub = null;
const onVisible = () => { if (document.visibilityState === 'visible') syncNow(); };
const onOnline = () => syncNow();

export function startSync(userId) {
  stopSync();
  uid = userId;
  set({ status: 'idle', error: null });
  unsub = store.onChange((e) => {
    if (e.type === 'remote' || e.type === 'settings' || e.type === 'files' || e.type === 'owner') return;
    schedule();
  });
  document.addEventListener('visibilitychange', onVisible);
  window.addEventListener('online', onOnline);
  timer = setInterval(syncNow, 60000);
  return syncNow();
}

export function stopSync() {
  if (unsub) unsub();
  unsub = null;
  schedule.cancel();
  clearInterval(timer);
  document.removeEventListener('visibilitychange', onVisible);
  window.removeEventListener('online', onOnline);
  uid = null;
  set({ status: 'off' });
}

/** Push anything pending before sign-out so nothing is lost. */
export async function flushBeforeSignOut() {
  if (!uid || !navigator.onLine) return;
  schedule.cancel();
  try { await pushAndPull(); } catch (err) { console.warn('Final push failed', err); }
}
