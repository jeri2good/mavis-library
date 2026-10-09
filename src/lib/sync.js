// Cross-device sync of shelf metadata, reading progress, and annotations.
// Book files are NOT synced; other devices show "file on another device".
//
// Push: dirty local rows are upserted. Pull: rows changed on the server since
// our cursor (server updated_at) are applied with last-writer-wins on the
// client timestamp. The database enforces the same rule, so an older device
// can never overwrite newer work.

import { getClient } from './auth.js';
import * as store from './store.js';
import { debounce } from './ui.js';

const TABLES = {
  shelf: 'shelf_items',
  progress: 'reading_progress',
  annotations: 'annotations',
};

let uid = null;
let timer = null;
let running = false;
let queued = false;
const state = { status: 'off', lastSyncedAt: null, error: null };
const listeners = new Set();
export function onSyncState(fn) { listeners.add(fn); fn(state); return () => listeners.delete(fn); }
function set(patch) { Object.assign(state, patch); for (const fn of listeners) fn(state); }
export function syncState() { return state; }

const httpsOrNull = (u) => (typeof u === 'string' && u.startsWith('https://') && u.length <= 500 ? u : null);

const toRemote = {
  shelf: (r) => ({
    user_id: uid, book_key: r.key, source: r.source, source_id: String(r.sourceId || ''),
    title: r.title, authors: r.authors || [], cover_url: httpsOrNull(r.coverUrl),
    languages: r.languages || [], subjects: r.subjects || [], format: r.format || null,
    file_name: r.fileName || null, file_size: r.fileSize || null, status: r.status,
    added_at: r.addedAt || null, last_opened_at: r.lastOpenedAt || null,
    deleted: !!r.deleted, client_updated_at: r.updatedAt,
  }),
  progress: (r) => ({
    user_id: uid, book_key: r.bookKey, cfi: r.cfi, percent: r.percent, chapter: r.chapter,
    deleted: !!r.deleted, client_updated_at: r.updatedAt,
  }),
  annotations: (r) => ({
    id: r.uid, user_id: uid, book_key: r.bookKey, kind: r.kind, cfi: r.cfi, text_excerpt: r.text || '',
    color: r.color || null, note: r.note || '', chapter: r.chapter || '', percent: r.percent ?? null,
    created_at: r.createdAt || null, deleted: !!r.deleted, client_updated_at: r.updatedAt,
  }),
};

const toLocal = {
  shelf: (x) => ({
    id: `${uid}|${x.book_key}`, key: x.book_key, source: x.source, sourceId: x.source_id, title: x.title,
    authors: x.authors || [], coverUrl: x.cover_url, languages: x.languages || [], subjects: x.subjects || [],
    format: x.format, fileName: x.file_name, fileSize: x.file_size, status: x.status,
    addedAt: x.added_at, lastOpenedAt: x.last_opened_at, deleted: x.deleted, updatedAt: Number(x.client_updated_at),
  }),
  progress: (x) => ({
    id: `${uid}|${x.book_key}`, bookKey: x.book_key, cfi: x.cfi, percent: x.percent, chapter: x.chapter,
    deleted: x.deleted, updatedAt: Number(x.client_updated_at),
  }),
  annotations: (x) => ({
    id: `${uid}|${x.id}`, uid: x.id, bookKey: x.book_key, kind: x.kind, cfi: x.cfi, text: x.text_excerpt,
    color: x.color, note: x.note, chapter: x.chapter, percent: x.percent, createdAt: x.created_at,
    deleted: x.deleted, updatedAt: Number(x.client_updated_at),
  }),
};

async function push(sb) {
  const dirty = await store.collectDirty(uid);
  let pushed = 0;
  for (const [local, table] of Object.entries(TABLES)) {
    const rows = dirty[local];
    for (let i = 0; i < rows.length; i += 200) {
      const batch = rows.slice(i, i + 200);
      const conflict = local === 'annotations' ? 'id' : 'user_id,book_key';
      const { error } = await sb.from(table).upsert(batch.map(toRemote[local]), { onConflict: conflict });
      if (error) throw error;
      await store.markClean(local, batch);
      pushed += batch.length;
    }
  }
  return pushed;
}

async function pull(sb) {
  let pulled = 0;
  for (const [local, table] of Object.entries(TABLES)) {
    const cursorKey = `sync-cursor|${uid}|${table}`;
    let cursor = store.getSetting(cursorKey, '1970-01-01T00:00:00Z');
    for (let page = 0; page < 20; page++) {
      const { data, error } = await sb.from(table).select('*').gte('updated_at', cursor).order('updated_at', { ascending: true }).limit(500);
      if (error) throw error;
      if (!data.length) break;
      pulled += await store.applyRemote(local, uid, data.map(toLocal[local]));
      const last = data[data.length - 1].updated_at;
      const advanced = last !== cursor;
      cursor = last;
      await store.setSetting(cursorKey, cursor);
      if (data.length < 500 || !advanced) break;
    }
  }
  return pulled;
}

export async function syncNow() {
  if (!uid) return;
  if (running) { queued = true; return; }
  if (!navigator.onLine) { set({ status: 'offline' }); return; }
  running = true;
  set({ status: 'syncing', error: null });
  try {
    const sb = await getClient();
    await push(sb);
    await pull(sb);
    set({ status: 'idle', lastSyncedAt: Date.now() });
  } catch (err) {
    console.warn('Sync failed', err);
    set({ status: 'error', error: err.message || 'Sync failed' });
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
  try { const sb = await getClient(); await push(sb); } catch (err) { console.warn('Final push failed', err); }
}
