// Domain store: shelf, reading progress, annotations, local book files, and
// device settings. Every record is scoped to an owner ('guest' or the signed-in
// user's id) so two people sharing a device never see each other's shelf.
//
// Sync model: records carry `updatedAt` (client ms) and `dirty`. The sync
// engine pushes dirty records for signed-in owners and applies remote rows
// with last-writer-wins on `updatedAt`. Book *files* never leave the device.

import * as idb from './idb.js';

const listeners = new Set();
let owner = 'guest';

export const GUEST = 'guest';
/** Stores whose rows belong to an owner and sync to their account. */
export const SYNCED_STORES = ['shelf', 'progress', 'annotations'];

export function onChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function emit(detail) {
  for (const fn of listeners) {
    try { fn(detail); } catch (err) { console.error(err); }
  }
}

export function getOwner() { return owner; }
export function setOwner(next) {
  if (next === owner) return;
  owner = next || GUEST;
  emit({ type: 'owner' });
}

const rid = (key, who = owner) => `${who}|${key}`;
const now = () => Date.now();

export function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// ---------- Shelf ----------

export const STATUSES = {
  reading: 'Reading',
  want: 'Want to read',
  finished: 'Finished',
};

function pickBookMeta(book) {
  return {
    key: book.key,
    source: book.source,
    sourceId: String(book.sourceId ?? ''),
    title: String(book.title || 'Untitled').slice(0, 300),
    authors: (book.authors || []).map(String).slice(0, 8),
    coverUrl: book.coverUrl || null,
    languages: (book.languages || []).slice(0, 6),
    subjects: (book.subjects || []).slice(0, 6),
    format: book.format || null,
    fileName: book.fileName || null,
    fileSize: book.fileSize || null,
  };
}

export async function listShelf() {
  const rows = await idb.byOwner('shelf', owner);
  return rows.filter((r) => !r.deleted);
}

export async function getShelfItem(key) {
  const row = await idb.get('shelf', rid(key));
  return row && !row.deleted ? row : null;
}

export async function saveToShelf(book, { status = 'want' } = {}) {
  const existing = await idb.get('shelf', rid(book.key));
  const t = now();
  const row = {
    ...(existing && !existing.deleted ? existing : { addedAt: t, lastOpenedAt: null }),
    ...pickBookMeta({ ...(existing || {}), ...book }),
    id: rid(book.key),
    owner,
    status: existing && !existing.deleted && !status ? existing.status : (status || 'want'),
    deleted: false,
    updatedAt: t,
    dirty: true,
  };
  await idb.put('shelf', row);
  emit({ type: 'shelf', key: book.key });
  return row;
}

export async function updateShelf(key, patch) {
  const row = await idb.get('shelf', rid(key));
  if (!row) return null;
  const next = { ...row, ...patch, updatedAt: now(), dirty: true };
  await idb.put('shelf', next);
  emit({ type: 'shelf', key });
  return next;
}

export async function removeFromShelf(key, { keepFile = false } = {}) {
  const row = await idb.get('shelf', rid(key));
  if (row) {
    await idb.put('shelf', { ...row, deleted: true, updatedAt: now(), dirty: true });
  }
  if (!keepFile) await deleteFile(key);
  emit({ type: 'shelf', key });
}

// ---------- Progress ----------

export async function getProgress(key) {
  const row = await idb.get('progress', rid(key));
  return row && !row.deleted ? row : null;
}

export async function setProgress(key, { cfi, percent, chapter }) {
  const t = now();
  const row = {
    id: rid(key), owner, bookKey: key,
    cfi: cfi ? String(cfi).slice(0, 2000) : null,
    percent: Number.isFinite(percent) ? Math.max(0, Math.min(1, percent)) : null,
    chapter: chapter ? String(chapter).slice(0, 300) : null,
    deleted: false, updatedAt: t, dirty: true,
  };
  await idb.put('progress', row);
  const shelf = await idb.get('shelf', rid(key));
  if (shelf && !shelf.deleted) {
    const patch = { lastOpenedAt: t };
    if (shelf.status === 'want') patch.status = 'reading';
    if (row.percent != null && row.percent >= 0.995 && shelf.status !== 'finished') patch.status = 'finished';
    await idb.put('shelf', { ...shelf, ...patch, updatedAt: t, dirty: true });
  }
  emit({ type: 'progress', key });
  return row;
}

export async function allProgress() {
  const rows = await idb.byOwner('progress', owner);
  const map = new Map();
  for (const r of rows) if (!r.deleted) map.set(r.bookKey, r);
  return map;
}

// ---------- Annotations (bookmarks, highlights, notes) ----------

export async function listAnnotations(key) {
  const rows = await idb.annotationsFor(owner, key);
  return rows.filter((r) => !r.deleted).sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
}

export async function listAllAnnotations() {
  const rows = await idb.byOwner('annotations', owner);
  return rows.filter((r) => !r.deleted).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}

export async function addAnnotation(key, { kind, cfi, text = '', color = null, note = '', chapter = '', percent = null }) {
  const id = uuid();
  const t = now();
  const row = {
    id: rid(id), uid: id, owner, bookKey: key, kind,
    cfi: String(cfi).slice(0, 2000),
    text: String(text).slice(0, 2000),
    color, note: String(note).slice(0, 5000), chapter: String(chapter || '').slice(0, 300),
    percent, createdAt: t, updatedAt: t, deleted: false, dirty: true,
  };
  await idb.put('annotations', row);
  emit({ type: 'annotations', key });
  return row;
}

export async function updateAnnotation(uid, patch) {
  const row = await idb.get('annotations', rid(uid));
  if (!row) return null;
  const next = { ...row, ...patch, updatedAt: now(), dirty: true };
  await idb.put('annotations', next);
  emit({ type: 'annotations', key: row.bookKey });
  return next;
}

export async function deleteAnnotation(uid) {
  const row = await idb.get('annotations', rid(uid));
  if (!row) return;
  await idb.put('annotations', { ...row, deleted: true, updatedAt: now(), dirty: true });
  emit({ type: 'annotations', key: row.bookKey });
}

// ---------- Files (device-local only) ----------

export async function saveFile(key, blob, mime) {
  try {
    await idb.put('files', { id: rid(key), owner, bookKey: key, blob, mime, size: blob.size, savedAt: now() });
  } catch (err) {
    if (idb.isQuotaError(err)) {
      throw new Error('This device is out of storage space for books. Remove a downloaded book, then try again.');
    }
    throw err;
  }
  // Ask the browser to keep downloaded books from being evicted.
  try { await navigator.storage?.persist?.(); } catch { /* optional */ }
  emit({ type: 'files', key });
}

export async function getFile(key) {
  return idb.get('files', rid(key));
}

export async function hasFile(key) {
  const keys = await idb.keysWithPrefix('files', rid(key));
  return keys.includes(rid(key));
}

export async function fileKeysForOwner() {
  const keys = await idb.keysWithPrefix('files', `${owner}|`);
  return new Set(keys.map((k) => k.slice(owner.length + 1)));
}

export async function deleteFile(key) {
  await idb.del('files', rid(key));
  await idb.del('cache', `locations|${owner}|${key}`).catch(() => {});
  emit({ type: 'files', key });
}

// ---------- Device cache ----------

export async function getCache(id) { const r = await idb.get('cache', id); return r ? r.value : null; }
export async function setCache(id, value) { return idb.put('cache', { id, value }).catch(() => {}); }

// ---------- Device settings ----------

const settingsCache = new Map();
export async function loadSettings() {
  try {
    const db = await idb.openDB();
    const all = await new Promise((res, rej) => {
      const r = db.transaction('kv').objectStore('kv').getAll();
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    });
    for (const row of all) settingsCache.set(row.id, row.value);
  } catch (err) {
    console.warn('Settings unavailable', err);
  }
}
export function getSetting(name, fallback) {
  return settingsCache.has(name) ? settingsCache.get(name) : fallback;
}
export async function setSetting(name, value) {
  settingsCache.set(name, value);
  emit({ type: 'settings', name });
  await idb.put('kv', { id: name, value });
}

// ---------- Sync helpers ----------

export async function collectDirty(who) {
  const out = {};
  for (const store of SYNCED_STORES) {
    out[store] = (await idb.byOwner(store, who)).filter((r) => r.dirty);
  }
  return out;
}

export async function markClean(store, rows) {
  const fresh = [];
  for (const r of rows) {
    const cur = await idb.get(store, r.id);
    // Only clear the flag if nothing changed while the push was in flight.
    if (cur && cur.updatedAt === r.updatedAt) fresh.push({ ...cur, dirty: false });
  }
  await idb.putMany(store, fresh);
}

/** Apply remote rows (already mapped to local shape) using last-writer-wins. */
export async function applyRemote(store, who, rows) {
  const writes = [];
  for (const r of rows) {
    const cur = await idb.get(store, r.id);
    if (cur && cur.updatedAt > r.updatedAt) continue; // local is newer; it will push
    if (cur && cur.dirty && cur.updatedAt === r.updatedAt) continue;
    writes.push({ ...(cur || {}), ...r, owner: who, dirty: false });
  }
  await idb.putMany(store, writes);
  if (writes.length && who === owner) emit({ type: 'remote', store });
  return writes.length;
}

/** Move everything the guest saved on this device into an account. */
export async function migrateGuest(toOwner) {
  let moved = 0;
  for (const store of SYNCED_STORES) {
    const rows = await idb.byOwner(store, GUEST);
    const writes = [];
    for (const r of rows) {
      if (r.deleted) continue;
      const suffix = r.id.slice(GUEST.length + 1);
      const target = `${toOwner}|${suffix}`;
      const existing = await idb.get(store, target);
      if (existing && !existing.deleted && existing.updatedAt >= r.updatedAt) continue;
      writes.push({ ...r, id: target, owner: toOwner, updatedAt: Math.max(r.updatedAt, now()), dirty: true });
    }
    await idb.putMany(store, writes);
    for (const r of rows) await idb.del(store, r.id);
    if (store === 'shelf') moved = writes.length;
  }
  const fileKeys = await idb.keysWithPrefix('files', `${GUEST}|`);
  for (const k of fileKeys) {
    const f = await idb.get('files', k);
    const suffix = k.slice(GUEST.length + 1);
    await idb.put('files', { ...f, id: `${toOwner}|${suffix}`, owner: toOwner });
    await idb.del('files', k);
  }
  emit({ type: 'owner' });
  return moved;
}

export async function guestItemCount() {
  try {
    const rows = await idb.byOwner('shelf', GUEST);
    return rows.filter((r) => !r.deleted).length;
  } catch { return 0; }
}

/** Remove one account's data from this device (used on shared devices). */
export async function clearOwnerData(who) {
  for (const store of SYNCED_STORES) {
    const rows = await idb.byOwner(store, who);
    for (const r of rows) await idb.del(store, r.id);
  }
  for (const k of await idb.keysWithPrefix('files', `${who}|`)) await idb.del('files', k);
  await idb.delPrefix('audio', `${who}|`);
  await idb.delPrefix('audiobooks', `${who}|`);
  // Forget how far this device had synced, so signing in again pulls everything back.
  await setSetting(`sync-cursors|${who}`, {});
  emit({ type: 'owner' });
}

export async function storageEstimate() {
  try {
    const est = await navigator.storage?.estimate?.();
    const persisted = await navigator.storage?.persisted?.();
    return est ? { usage: est.usage || 0, quota: est.quota || 0, persisted: !!persisted } : null;
  } catch { return null; }
}
