// The "Listen offline" panel on a book's page: estimate, download with
// progress, pause/resume, listen, and remove.

import { html, icon, toast, formatBytes, confirmDialog } from './ui.js';
import * as ab from './audiobook.js';
import { loadFeatures, can } from './features.js';
import * as store from './store.js';

const plans = new Map(); // key → plan (parsing a whole book takes a moment)

export async function mountAudioPanel(el, { key, book }) {
  let off = null;
  let disposed = false;

  async function paint(live = null) {
    if (disposed || !el.isConnected) return;
    const m = await ab.manifest(key);
    const s = ab.summarize(m);
    const downloading = ab.isDownloading(key);
    const f = await loadFeatures();
    const cloud = can('cloudVoice');

    let body;
    if (!m && !cloud) {
      body = html`<p class="small muted">Save this book as audio to listen with no signal — on the road, on a flight, anywhere. ${f.cloudVoice ? 'Enter the owner’s access code in Settings to use the cloud voice on this device.' : 'This needs the cloud voice, which the site owner hasn’t switched on.'}</p>
        ${f.cloudVoice ? html`<a class="btn btn-sm" href="#/settings">${icon('settings', { size: 18 })} Open settings</a>` : ''}`;
    } else if (!m) {
      const p = plans.get(key);
      body = p ? choosePanel(p) : html`<p class="small muted">Save this book as audio in the cloud voice so it plays with no signal and with the screen off. Your car’s buttons work, and it remembers where you stopped.</p>
        <div><button type="button" class="btn btn-sm" data-ab="plan">${icon('headphones', { size: 18 })} Get it as audio…</button></div>`;
    } else {
      const pct = s.chars ? Math.round((s.doneChars / s.chars) * 100) : 0;
      const phase = live?.phase;
      body = html`
        <div class="ab-status">
          <div class="dl-bar" aria-hidden="true"><span style="width:${pct}%"></span></div>
          <p class="small num" role="status">${s.complete
            ? `Whole book saved · ${ab.durationLabel(s.chars)} · ${formatBytes(s.bytes)}`
            : `${pct}% saved · ${ab.durationLabel(s.doneChars)} of ${ab.durationLabel(s.chars)} · ${formatBytes(s.bytes)}${downloading ? ' · downloading…' : ''}`}</p>
          ${phase === 'error' ? html`<p class="form-error" role="alert">${live.error}</p>` : ''}
          ${downloading ? html`<p class="small faint">Keep Mavis open with the screen on until it finishes. You can start listening now.</p>` : ''}
        </div>
        <div class="ab-actions">
          ${s.done ? html`<a class="btn btn-sm btn-primary" href="#/listen/${encodeURIComponent(key)}">${icon('headphones', { size: 18 })} Listen${s.complete ? '' : ' to what’s saved'}</a>` : ''}
          ${downloading ? html`<button type="button" class="btn btn-sm" data-ab="pause">${icon('pause', { size: 18 })} Pause download</button>`
            : !s.complete ? html`<button type="button" class="btn btn-sm" data-ab="resume" ${cloud ? '' : 'disabled'}>${icon('download', { size: 18 })} ${s.done ? 'Resume download' : 'Start download'}</button>` : ''}
          <button type="button" class="btn btn-sm btn-quiet" data-ab="remove">${icon('trash', { size: 18 })} Remove audio</button>
        </div>`;
    }
    el.innerHTML = String(html`<section class="ab-panel" aria-labelledby="ab-h"><h2 class="h-section" id="ab-h">${icon('headphones', { size: 20 })} Listen offline</h2>${body}</section>`);
  }

  function choosePanel(p) {
    const minutes = ab.minutesFor(p.totalChars);
    return html`
      <p class="small">${p.chapters.length} parts · about <strong>${ab.durationLabel(p.totalChars)}</strong> of audio · ${p.totalChars.toLocaleString()} characters.</p>
      <p class="small faint">The cloud voice is billed to the site owner’s Fish Audio account by the character, so you can save just part of the book.</p>
      <div class="field"><label for="ab-scope">What to save</label>
        <select class="select" id="ab-scope">
          <option value="all">Whole book (${ab.durationLabel(p.totalChars)})</option>
          ${p.chapters.length > 3 ? html`<option value="next3">Next 3 parts from where I am</option>` : ''}
          ${p.chapters.length > 1 ? html`<option value="from">From where I am to the end</option>` : ''}
        </select></div>
      <div class="ab-actions"><button type="button" class="btn btn-sm btn-primary" data-ab="start">${icon('download', { size: 18 })} Save audio</button>
        <button type="button" class="btn btn-sm btn-quiet" data-ab="cancel-plan">Not now</button></div>
      ${minutes > 600 ? html`<p class="small faint">That’s a long book. Saving takes a while; you can listen as parts finish.</p>` : ''}`;
  }

  async function currentPart(p) {
    const prog = await store.getProgress(key).catch(() => null);
    if (!prog?.cfi) return 0;
    const spine = Number(/^epubcfi\(\/6\/(\d+)/.exec(prog.cfi)?.[1]);
    if (!spine) return 0;
    const spineIndex = spine / 2 - 1;
    const hit = [...p.chapters].reverse().find((c) => c.spine <= spineIndex);
    return hit ? hit.index : 0;
  }

  el.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-ab]')?.dataset.ab;
    if (!a) return;
    try {
      if (a === 'plan') {
        e.target.closest('button').disabled = true;
        e.target.closest('button').textContent = 'Reading the book…';
        plans.set(key, await ab.plan(key));
        paint();
      }
      if (a === 'cancel-plan') { plans.delete(key); paint(); }
      if (a === 'start') {
        const p = plans.get(key);
        const scope = el.querySelector('#ab-scope')?.value || 'all';
        const here = scope === 'all' ? 0 : await currentPart(p);
        const upTo = scope === 'next3' ? here + 2 : Infinity;
        ab.download(key, { title: book.title, from: here, upTo, voice: 'cloud' }).catch(() => {});
        plans.delete(key);
        setTimeout(paint, 300);
      }
      if (a === 'resume') { ab.download(key, { title: book.title }).catch(() => {}); setTimeout(paint, 300); }
      if (a === 'pause') { ab.pauseDownload(key); toast('Download paused. Saved parts are kept.'); }
      if (a === 'remove') {
        const ok = await confirmDialog({ title: 'Remove the audio?', message: 'The saved audio for this book will be deleted from this device. The book itself stays.', confirmLabel: 'Remove audio' });
        if (ok) { await ab.remove(key); toast('Audio removed.'); paint(); }
      }
    } catch (err) {
      toast(err.message, { tone: 'error', timeout: 8000 });
      paint();
    }
  });

  let last = 0;
  off = ab.onAudiobook((ev) => {
    if (ev.key !== key) return;
    const now = Date.now();
    if (ev.phase === 'downloading' && now - last < 700) return;
    last = now;
    if (ev.phase === 'done') toast(`“${book.title}” is saved as audio.`);
    paint(ev);
  });
  await paint();
  return () => { disposed = true; off?.(); };
}
