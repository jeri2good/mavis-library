// Downloaded audiobooks: a book read by the cloud voice, saved on this device
// as MP3s so it plays with no signal (driving, flights, dead zones).
//
//  plan(key)                 → chapters and chunks of text, with character counts
//  download(key, { from })   → starts/resumes a background download (one at a time per book)
//  manifest(key)             → what is saved, and the listening position
//  chunkBlob(key, ch, n)     → the MP3 for one chunk (null if not downloaded)
//  remove(key)               → deletes the audio for a book
//
// Text comes from the EPUB already stored on the device. Each chunk keeps the
// location (CFI) of its first paragraph, so listening also moves your reading
// position forward.

import * as idb from './idb.js';
import * as store from './store.js';
import { sentences } from './speech.js';
import { ownerPost } from './features.js';

const CHUNK_CHARS = 1500;
const PARALLEL = 3;
export const CHARS_PER_MINUTE = 900; // ≈150 spoken words a minute

const BLOCKS = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, dd, dt, figcaption';
const mid = (key) => `${store.getOwner()}|${key}`;
const cid = (key, ch, n) => `${store.getOwner()}|${key}|${ch}|${n}`;

const listeners = new Set();
export function onAudiobook(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function emit(key, detail) { for (const fn of listeners) { try { fn({ key, ...detail }); } catch (e) { console.error(e); } } }

/** Split the stored EPUB into chapters of speakable chunks. */
export async function plan(key) {
  const file = await store.getFile(key);
  if (!file?.blob) throw new Error('Download the book first; the audio is made from the book on this device.');
  if (file.mime && !/epub/.test(file.mime)) throw new Error('Audio downloads work for EPUB books.');
  const { default: ePub } = await import('epubjs');
  const book = ePub();
  await book.open(await file.blob.arrayBuffer(), 'binary');
  await book.ready;
  const lang = (book.packaging?.metadata?.language || 'en').slice(0, 5);
  const toc = flatToc(book.navigation?.toc || []);
  const chapters = [];
  try {
    for (let i = 0; i < book.spine.length; i++) {
      const section = book.spine.get(i);
      if (!section || section.linear === false) continue;
      await section.load(book.load.bind(book));
      const doc = section.document;
      const blocks = [...doc.querySelectorAll(BLOCKS)].filter((el) => !el.querySelector(BLOCKS) && el.textContent.trim());
      const chunks = [];
      let cur = null;
      for (const el of blocks) {
        let cfi = null;
        try { cfi = section.cfiFromElement(el); } catch { /* ignore */ }
        for (const s of sentences(el.textContent, lang)) {
          if (!/[\p{L}\p{N}]/u.test(s)) continue;
          if (!cur || cur.text.length + s.length + 1 > CHUNK_CHARS) { cur = { text: '', cfi }; chunks.push(cur); }
          cur.text = cur.text ? `${cur.text} ${s}` : s;
        }
      }
      section.unload?.();
      if (!chunks.length) continue;
      const label = toc.find((t) => book.spine.get(t.href.split('#')[0])?.index === i)?.label?.trim();
      chapters.push({ index: chapters.length, spine: i, title: label || `Part ${chapters.length + 1}`, chunks: chunks.map((c) => ({ text: c.text, cfi: c.cfi, chars: c.text.length })) });
    }
  } finally {
    try { book.destroy(); } catch { /* ignore */ }
  }
  if (!chapters.length) throw new Error('This book has no readable text to turn into audio.');
  const totalChars = chapters.reduce((a, c) => a + c.chunks.reduce((b, k) => b + k.chars, 0), 0);
  return { chapters, totalChars, lang };
}

function flatToc(items, out = []) {
  for (const t of items) { out.push(t); if (t.subitems?.length) flatToc(t.subitems, out); }
  return out;
}

export async function manifest(key) {
  return (await idb.get('audiobooks', mid(key))) || null;
}

async function saveManifest(m) {
  await idb.put('audiobooks', { ...m, id: mid(m.bookKey), owner: store.getOwner() });
}

export async function chunkBlob(key, ch, n) {
  const r = await idb.get('audio', cid(key, ch, n));
  return r?.blob || null;
}

export async function listDownloaded() {
  try { return (await idb.byOwner('audiobooks', store.getOwner())) || []; } catch { return []; }
}

export function summarize(m) {
  if (!m) return { chunks: 0, done: 0, chars: 0, doneChars: 0, bytes: 0 };
  let chunks = 0, done = 0, chars = 0, doneChars = 0;
  for (const c of m.chapters) for (const k of c.chunks) { chunks++; chars += k.chars; if (k.done) { done++; doneChars += k.chars; } }
  return { chunks, done, chars, doneChars, bytes: m.bytes || 0, complete: chunks > 0 && done === chunks };
}

export const minutesFor = (chars) => Math.max(1, Math.round(chars / CHARS_PER_MINUTE));
export function durationLabel(chars) {
  const m = minutesFor(chars);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} hr ${m % 60 ? `${m % 60} min` : ''}`.trim();
}

// ---------- downloading ----------
const jobs = new Map(); // key → { cancel, promise }
export function isDownloading(key) { return jobs.has(key); }

/**
 * Start (or resume) downloading audio for a book.
 * from: chapter index to start at; upTo: last chapter index to include.
 */
export function download(key, { title = '', from = 0, upTo = Infinity, voice = '' } = {}) {
  if (jobs.has(key)) return jobs.get(key).promise;
  let cancelled = false;
  let wake = null;
  const job = { cancel: () => { cancelled = true; } };
  job.promise = (async () => {
    try { if ('wakeLock' in navigator) wake = await navigator.wakeLock.request('screen'); } catch { /* optional */ }
    let m = await manifest(key);
    if (!m) {
      emit(key, { phase: 'planning' });
      const p = await plan(key);
      m = { bookKey: key, title, lang: p.lang, voice, createdAt: Date.now(), bytes: 0, position: { ch: 0, n: 0, t: 0 },
        chapters: p.chapters.map((c) => ({ ...c, chunks: c.chunks.map((k) => ({ ...k, done: false })) })) };
      await saveManifest(m);
    }
    const queue = [];
    for (const c of m.chapters) {
      if (c.index < from || c.index > upTo) continue;
      c.chunks.forEach((k, n) => { if (!k.done) queue.push({ c, n, k }); });
    }
    emit(key, { phase: 'downloading', ...summarize(m) });
    let failures = 0;
    let lastSave = Date.now();
    const worker = async () => {
      while (queue.length && !cancelled) {
        const job2 = queue.shift();
        let blob = null;
        for (let attempt = 0; attempt < 4 && !cancelled; attempt++) {
          try {
            blob = await ownerPost('/api/tts', { text: job2.k.text, speed: 1 }, { as: 'blob' });
            if (!blob || blob.size < 200) throw new Error('The voice service sent an empty file.');
            break;
          } catch (err) {
            blob = null;
            if (/access code|not set up|switched on/i.test(err.message)) throw err;
            if (!navigator.onLine) throw new Error('You went offline. The download will pick up where it left off.');
            await new Promise((r) => setTimeout(r, /too many|429/i.test(err.message) ? 30000 : 1500 * (attempt + 1)));
          }
        }
        if (cancelled) return;
        if (!blob) { failures++; if (failures > 5) throw new Error('The voice service kept failing. Try again later; finished parts are kept.'); continue; }
        try {
          await idb.put('audio', { id: cid(key, job2.c.index, job2.n), blob });
        } catch (err) {
          if (idb.isQuotaError(err)) throw new Error('This device is out of storage space. Remove some downloads, then resume.');
          throw err;
        }
        job2.k.done = true;
        m.bytes = (m.bytes || 0) + blob.size;
        if (Date.now() - lastSave > 1500 || !queue.length) { lastSave = Date.now(); await saveManifest(m); }
        emit(key, { phase: 'downloading', ...summarize(m) });
      }
    };
    await Promise.all(Array.from({ length: PARALLEL }, worker));
    await saveManifest(m);
    const s = summarize(m);
    emit(key, { phase: cancelled ? 'paused' : 'done', ...s });
    return s;
  })().catch(async (err) => {
    emit(key, { phase: 'error', error: err.message, ...summarize(await manifest(key)) });
    throw err;
  }).finally(() => {
    jobs.delete(key);
    try { wake?.release(); } catch { /* ignore */ }
  });
  jobs.set(key, job);
  job.promise.catch(() => {});
  return job.promise;
}

export function pauseDownload(key) { jobs.get(key)?.cancel(); }

export async function remove(key) {
  pauseDownload(key);
  await idb.delPrefix('audio', `${mid(key)}|`);
  await idb.del('audiobooks', mid(key));
  emit(key, { phase: 'removed' });
}

export async function savePosition(key, position) {
  const m = await manifest(key);
  if (!m) return;
  m.position = { ...position, at: Date.now() };
  await saveManifest(m);
}

/** Remove all audio for an owner (used when wiping an account from a device). */
export async function removeAllFor(owner) {
  await idb.delPrefix('audio', `${owner}|`);
  await idb.delPrefix('audiobooks', `${owner}|`);
}
