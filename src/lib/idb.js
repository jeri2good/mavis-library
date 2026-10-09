// Minimal promise wrapper around IndexedDB. All app data that must survive a
// reload lives here; failures surface as errors the UI reports (never silent).

const DB_NAME = 'mavis-library';
const DB_VERSION = 3;

export const STORES = {
  shelf: 'shelf', //            id = `${owner}|${bookKey}`
  progress: 'progress', //      id = `${owner}|${bookKey}`
  annotations: 'annotations', // id = `${owner}|${uuid}`
  files: 'files', //            id = `${owner}|${bookKey}` → { blob, mime, size }
  cache: 'cache', //            device cache: epub locations etc. id = string
  kv: 'kv', //                  device settings
  audiobooks: 'audiobooks', //  id = `${owner}|${bookKey}` → downloaded-audio manifest (device only)
  audio: 'audio', //            id = `${owner}|${bookKey}|${chapter}|${chunk}` → { blob }
  plans: 'plans', //            id = `${owner}|${planId}` → reading-plan progress (synced)
  vocab: 'vocab', //            id = `${owner}|${word}` → word builder (synced)
};

let dbPromise = null;

export class StorageUnavailableError extends Error {
  constructor(message, cause) {
    super(message);
    this.name = 'StorageUnavailableError';
    this.cause = cause;
  }
}

export function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    if (!('indexedDB' in self)) {
      reject(new StorageUnavailableError('This browser does not provide offline storage (IndexedDB).'));
      return;
    }
    let req;
    try {
      req = indexedDB.open(DB_NAME, DB_VERSION);
    } catch (err) {
      reject(new StorageUnavailableError('Offline storage is blocked in this browser window.', err));
      return;
    }
    req.onupgradeneeded = () => {
      const db = req.result;
      for (const name of ['shelf', 'progress', 'annotations', 'plans', 'vocab']) {
        if (!db.objectStoreNames.contains(name)) {
          const s = db.createObjectStore(name, { keyPath: 'id' });
          s.createIndex('owner', 'owner');
          if (name === 'annotations') s.createIndex('owner_book', ['owner', 'bookKey']);
        }
      }
      if (!db.objectStoreNames.contains('files')) db.createObjectStore('files', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('cache')) db.createObjectStore('cache', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('audiobooks')) db.createObjectStore('audiobooks', { keyPath: 'id' }).createIndex('owner', 'owner');
      if (!db.objectStoreNames.contains('audio')) db.createObjectStore('audio', { keyPath: 'id' });
    };
    req.onsuccess = () => {
      const db = req.result;
      db.onversionchange = () => db.close();
      resolve(db);
    };
    req.onerror = () => reject(new StorageUnavailableError('Could not open offline storage.', req.error));
    req.onblocked = () => reject(new StorageUnavailableError('Offline storage is busy in another tab. Close other Mavis tabs and reload.'));
  });
  dbPromise.catch(() => { dbPromise = null; });
  return dbPromise;
}

function wrap(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function txDone(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error('Storage transaction aborted'));
  });
}

export function isQuotaError(err) {
  return !!err && (err.name === 'QuotaExceededError' || /quota/i.test(err.message || ''));
}

export async function get(store, id) {
  const db = await openDB();
  return wrap(db.transaction(store).objectStore(store).get(id));
}

export async function put(store, value) {
  const db = await openDB();
  const tx = db.transaction(store, 'readwrite');
  tx.objectStore(store).put(value);
  return txDone(tx);
}

export async function putMany(store, values) {
  if (!values.length) return;
  const db = await openDB();
  const tx = db.transaction(store, 'readwrite');
  const s = tx.objectStore(store);
  for (const v of values) s.put(v);
  return txDone(tx);
}

export async function del(store, id) {
  const db = await openDB();
  const tx = db.transaction(store, 'readwrite');
  tx.objectStore(store).delete(id);
  return txDone(tx);
}

export async function byOwner(store, owner) {
  const db = await openDB();
  return wrap(db.transaction(store).objectStore(store).index('owner').getAll(owner));
}

export async function annotationsFor(owner, bookKey) {
  const db = await openDB();
  return wrap(db.transaction('annotations').objectStore('annotations').index('owner_book').getAll([owner, bookKey]));
}

export async function keysWithPrefix(store, prefix) {
  const db = await openDB();
  const range = IDBKeyRange.bound(prefix, prefix + '￿');
  return wrap(db.transaction(store).objectStore(store).getAllKeys(range));
}

/** Delete every record whose key starts with prefix. */
export async function delPrefix(store, prefix) {
  const db = await openDB();
  const tx = db.transaction(store, 'readwrite');
  tx.objectStore(store).delete(IDBKeyRange.bound(prefix, `${prefix}￿`));
  return txDone(tx);
}

/** Count records whose key starts with prefix. */
export async function countPrefix(store, prefix) {
  const db = await openDB();
  return wrap(db.transaction(store).objectStore(store).count(IDBKeyRange.bound(prefix, `${prefix}￿`)));
}
