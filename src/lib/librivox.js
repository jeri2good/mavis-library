// LibriVox: free, human-read audiobooks of public-domain books (volunteer
// readers; recordings are public domain). Recordings stream from archive.org
// and can be saved on the device for offline listening. They play in the same
// player as Mavis's own audiobooks, so position, speed, sleep timer, and car
// controls all work the same.

import { html, icon, toast, formatBytes, confirmDialog } from './ui.js';
import * as idb from './idb.js';
import * as store from './store.js';
import * as ab from './audiobook.js';

export const lvKey = (bookKey) => `lv:${bookKey}`;
const mid = (key) => `${store.getOwner()}|${key}`;

export async function findVersions(book) {
  const p = new URLSearchParams({ title: book.title || '' });
  if (book.source === 'gutenberg' && book.sourceId) p.set('gid', String(book.sourceId));
  const a = (book.authors || [])[0];
  if (a) p.set('author', typeof a === 'string' ? a : a.name || '');
  let r;
  try { r = await fetch(`/api/librivox?${p}`); } catch { throw new Error('Couldn’t reach LibriVox. Check your connection.'); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.message || `LibriVox answered ${r.status}.`);
  return j.versions || [];
}

export const hms = (secs) => {
  const h = Math.floor(secs / 3600), m = Math.round((secs % 3600) / 60);
  return h ? `${h} hr ${m} min` : `${m} min`;
};

/** Make (or update) the player manifest for a LibriVox version, keeping the position if it's the same recording. */
export async function useVersion(bookKey, version, title) {
  const key = lvKey(bookKey);
  const cur = await ab.manifest(key);
  if (cur && cur.versionId === version.id) return cur;
  const m = {
    bookKey: key, title, source: 'librivox', versionId: version.id, versionTitle: version.title, readers: version.readers,
    createdAt: Date.now(), bytes: 0, position: { ch: 0, n: 0, t: 0 },
    chapters: version.sections.map((s, i) => ({ index: i, title: s.title, chunks: [{ url: s.url, secs: s.secs, chars: Math.max(1, s.secs) * 15, reader: s.reader, text: '', done: false }] })),
  };
  if (cur) await idb.delPrefix('audio', `${mid(key)}|`); // a different recording: drop the old files
  await idb.put('audiobooks', { ...m, id: mid(key), owner: store.getOwner() });
  return m;
}

const jobs = new Map();
export const isSaving = (bookKey) => jobs.has(bookKey);

/** Save every part of the chosen recording on the device. */
export function saveOffline(bookKey, { onProgress } = {}) {
  if (jobs.has(bookKey)) return jobs.get(bookKey).promise;
  const key = lvKey(bookKey);
  let cancelled = false;
  const job = { cancel: () => { cancelled = true; } };
  job.promise = (async () => {
    const m = await ab.manifest(key);
    if (!m) throw new Error('Choose a recording first.');
    const queue = m.chapters.filter((c) => !c.chunks[0].done).map((c) => c);
    const total = m.chapters.length;
    let done = total - queue.length;
    onProgress?.(done, total, m.bytes || 0);
    const worker = async () => {
      while (queue.length && !cancelled) {
        const c = queue.shift();
        const k = c.chunks[0];
        let blob = null;
        for (let attempt = 0; attempt < 3 && !blob && !cancelled; attempt++) {
          try {
            const r = await fetch(k.url, { mode: 'cors' });
            if (!r.ok) throw new Error(`archive.org answered ${r.status}`);
            blob = await r.blob();
          } catch (err) {
            if (!navigator.onLine) throw new Error('You went offline. Saving picks up where it left off.');
            await new Promise((res) => setTimeout(res, 1500 * (attempt + 1)));
          }
        }
        if (cancelled) return;
        if (!blob) throw new Error(`Couldn’t download “${c.title}”. Try again later; saved parts are kept.`);
        try { await idb.put('audio', { id: `${mid(key)}|${c.index}|0`, blob }); }
        catch (err) { if (idb.isQuotaError(err)) throw new Error('This device is out of storage space.'); throw err; }
        k.done = true;
        m.bytes = (m.bytes || 0) + blob.size;
        await idb.put('audiobooks', { ...m, id: mid(key), owner: store.getOwner() });
        onProgress?.(++done, total, m.bytes);
      }
    };
    await Promise.all([worker(), worker()]);
    return { done, total };
  })().finally(() => jobs.delete(bookKey));
  jobs.set(bookKey, job);
  return job.promise;
}

export function stopSaving(bookKey) { jobs.get(bookKey)?.cancel(); }

/** The "Human-read audiobook" panel on a free book's page. */
export async function mountLibriVoxPanel(el, { key, book }) {
  let versions = null;
  let busy = false;
  async function paint(msg = '') {
    const m = await ab.manifest(lvKey(key));
    const saved = m ? m.chapters.filter((c) => c.chunks[0].done).length : 0;
    if (!versions) {
      el.innerHTML = String(html`<section class="ab-panel"><h2 class="h-section">${icon('headphones', { size: 20 })} Human-read audiobook</h2>
        <p class="small muted">LibriVox volunteers have recorded many public-domain books. Free to listen, no account needed.</p>
        ${m ? html`<div class="ab-actions"><a class="btn btn-sm btn-primary" href="#/listen/${encodeURIComponent(lvKey(key))}">${icon('play', { size: 18 })} Continue listening</a></div>` : ''}
        <div class="ab-actions"><button type="button" class="btn btn-sm" data-lv="find">${icon('search', { size: 18 })} Find recordings</button></div>${msg ? html`<p class="small muted">${msg}</p>` : ''}</section>`);
      return;
    }
    if (!versions.length) {
      el.innerHTML = String(html`<section class="ab-panel"><h2 class="h-section">${icon('headphones', { size: 20 })} Human-read audiobook</h2><p class="small muted">LibriVox doesn’t have a recording of this book yet.</p></section>`);
      return;
    }
    const cur = m?.versionId || versions[0].id;
    el.innerHTML = String(html`<section class="ab-panel"><h2 class="h-section">${icon('headphones', { size: 20 })} Human-read audiobook</h2>
      <div class="field"><label for="lv-v">Recording</label><select class="select" id="lv-v">${versions.map((v) => html`<option value="${v.id}" ${v.id === cur ? 'selected' : ''}>${v.readers.join(', ')}${v.moreReaders ? ` +${v.moreReaders} more` : ''} · ${hms(v.totalSecs)}${v.readers.length > 1 || v.moreReaders ? ' (group)' : ' (solo)'}</option>`)}</select></div>
      <div class="ab-actions">
        <button type="button" class="btn btn-sm btn-primary" data-lv="listen">${icon('play', { size: 18 })} ${m && m.versionId === cur ? 'Continue listening' : 'Listen'}</button>
        ${m && m.versionId === cur && saved === m.chapters.length ? html`<span class="badge badge-ok">${icon('check', { size: 14 })} Saved for offline · ${formatBytes(m.bytes || 0)}</span>`
          : html`<button type="button" class="btn btn-sm" data-lv="save" ${busy ? 'disabled' : ''}>${icon('download', { size: 18 })} ${busy ? 'Saving…' : saved ? `Save the rest for offline (${saved}/${m.chapters.length})` : 'Save for offline'}</button>`}
        ${busy ? html`<button type="button" class="btn btn-sm btn-quiet" data-lv="stop">Stop</button>` : ''}
        ${m ? html`<button type="button" class="btn btn-sm btn-quiet" data-lv="remove">${icon('trash', { size: 18 })} Remove</button>` : ''}
      </div>
      <p class="small muted" id="lv-msg">${msg}</p>
      <p class="small faint">Recordings by LibriVox volunteers (public domain), streamed from the Internet Archive. ${versions.find((v) => v.id === cur)?.url ? html`<a href="${versions.find((v) => v.id === cur).url}" target="_blank" rel="noopener noreferrer">About this recording</a>` : ''}</p></section>`);
  }
  el.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-lv]')?.dataset.lv;
    if (!a) return;
    try {
      if (a === 'find') {
        e.target.closest('button').disabled = true;
        versions = await findVersions({ ...book, key });
        paint();
      }
      const pick = () => versions.find((v) => v.id === el.querySelector('#lv-v')?.value) || versions[0];
      if (a === 'listen') {
        await useVersion(key, pick(), book.title);
        location.hash = `#/listen/${encodeURIComponent(lvKey(key))}?play=1`;
      }
      if (a === 'save') {
        await useVersion(key, pick(), book.title);
        busy = true; paint('Saving… keep Mavis open until it finishes.');
        await saveOffline(key, { onProgress: (n, t, bytes) => { const m = el.querySelector('#lv-msg'); if (m) m.textContent = `Saved ${n} of ${t} parts · ${formatBytes(bytes)}`; } });
        busy = false; toast(`“${book.title}” is saved for offline listening.`); paint();
      }
      if (a === 'stop') { stopSaving(key); busy = false; paint('Stopped. Saved parts are kept.'); }
      if (a === 'remove') {
        const ok = await confirmDialog({ title: 'Remove this recording?', message: 'Saved audio and your listening position for this recording are removed from this device.', confirmLabel: 'Remove' });
        if (ok) { stopSaving(key); await ab.remove(lvKey(key)); paint(); }
      }
    } catch (err) {
      busy = false;
      paint(err.message);
    }
  });
  await paint();
}
