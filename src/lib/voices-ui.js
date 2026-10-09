// Settings → Voices: pick the narrator from Fish Audio's licensed voice
// library, or record your own voice and make a private clone of it.

import { html, icon, toast, openDialog, confirmDialog } from './ui.js';
import * as V from './voices.js';
import { can, loadFeatures, features } from './features.js';

const READ_ALOUD = 'It was a bright, cold morning, and the light came in low across the kitchen table. I poured a cup of coffee, opened the book to where I had left off, and began to read aloud. Some stories ask to be heard, not just seen: the rise of a question, the pause before an answer, the quiet that follows a good line. Reading to someone you love is one of the oldest gifts there is. So here is my voice, steady and clear, ready to tell a story from the beginning to the very last page.';

export async function mountVoicesPanel(el) {
  await loadFeatures();
  const f = features();
  async function paint() {
    if (f.cloudVoice !== 'fish') {
      el.innerHTML = String(html`<h2>Voices</h2><p class="muted">Choosing voices and making your own needs the Fish Audio cloud voice${f.cloudVoice ? ' (this site uses Google)' : ''}.</p>`);
      return;
    }
    if (!can('cloudVoice')) {
      el.innerHTML = String(html`<h2>Voices</h2><p class="muted">Enter the owner access code above to choose voices.</p>`);
      return;
    }
    const nv = V.narratorVoice();
    let mine = [];
    try { mine = (await V.myVoices()).items || []; } catch { /* shown below */ }
    el.innerHTML = String(html`
      <h2>Voices</h2>
      <div class="setting-row"><span>Narrator</span><span><strong>${nv?.title || 'The site’s default voice'}</strong></span></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button type="button" class="btn btn-sm" data-v="browse">${icon('headphones', { size: 18 })} Choose a narrator…</button>
        ${nv ? html`<button type="button" class="btn btn-sm btn-quiet" data-v="default">Use the default voice</button>` : ''}
      </div>
      <h3 class="h-section" style="margin-top:14px">Your own voice</h3>
      ${mine.length ? html`<ul class="my-voices">${mine.map((v) => html`<li><span>${icon('user', { size: 16 })} ${v.title}${v.state && v.state !== 'trained' ? html` <span class="faint small">(${v.state})</span>` : ''}</span>
        <span><button type="button" class="btn btn-sm" data-use="${v.id}" data-title="${v.title}">${nv?.id === v.id ? 'In use' : 'Use as narrator'}</button>
        <button type="button" class="icon-btn" data-del="${v.id}" aria-label="Delete ${v.title}">${icon('trash', { size: 18 })}</button></span></li>`)}</ul>` : ''}
      <p class="hint">Record yourself reading for about a minute and Mavis makes a private copy of your voice in the owner’s Fish Audio account — handy for bedtime stories or devotionals in your own voice. Only use your own voice.</p>
      <div><button type="button" class="btn btn-sm" data-v="record">${icon('mic', { size: 18 })} ${mine.length ? 'Record another' : 'Record my voice…'}</button></div>`);
  }

  el.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-v]')?.dataset.v;
    if (a === 'browse') browse();
    if (a === 'default') { await V.setNarratorVoice(null); toast('Using the default voice.'); paint(); }
    if (a === 'record') record();
    const use = e.target.closest('[data-use]');
    if (use) { await V.setNarratorVoice({ id: use.dataset.use, title: use.dataset.title }); toast(`${use.dataset.title} is now your narrator.`); paint(); }
    const del = e.target.closest('[data-del]')?.dataset.del;
    if (del) {
      const ok = await confirmDialog({ title: 'Delete this voice?', message: 'The private voice model is deleted from the Fish Audio account. Audiobooks you already saved keep their audio.', confirmLabel: 'Delete voice' });
      if (!ok) return;
      try { await V.deleteVoice(del); if (V.narratorVoice()?.id === del) await V.setNarratorVoice(null); toast('Voice deleted.'); paint(); }
      catch (err) { toast(err.message, { tone: 'error' }); }
    }
  });

  function browse() {
    const audio = new Audio();
    const d = openDialog({
      title: 'Choose a narrator', variant: 'side', onClose: () => audio.pause(),
      body: html`<div class="seg" role="group" aria-label="Voice type">
          ${[['', 'All'], ['female', 'Female'], ['male', 'Male']].map(([g, l]) => html`<button type="button" data-g="${g}" aria-pressed="${g === ''}">${l}</button>`)}</div>
        <ul class="voice-list" id="vlist"><li class="muted">Loading voices…</li></ul>
        <p class="hint">Voices licensed by Fish Audio. Tap play to hear a sample.</p>`,
    });
    const list = d.body.querySelector('#vlist');
    let items = [];
    async function load(gender) {
      list.innerHTML = String(html`<li class="muted">Loading voices…</li>`);
      try {
        items = (await V.libraryVoices({ gender })).items;
        const cur = V.narratorVoice()?.id;
        list.innerHTML = String(html`${items.map((v) => html`<li class="voice-item">
          <button type="button" class="icon-btn" data-sample="${v.id}" aria-label="Hear ${v.title}" ${v.sample ? '' : 'disabled'}>${icon('play', { size: 18 })}</button>
          <span class="voice-meta"><strong>${v.title}</strong><span class="small faint">${(v.tags || []).filter((t) => !['narration', 'educational', 'entertainment', 'social-media'].includes(t)).slice(0, 4).join(' · ')}</span></span>
          <button type="button" class="btn btn-sm ${cur === v.id ? 'btn-primary' : ''}" data-pick="${v.id}">${cur === v.id ? 'Chosen' : 'Choose'}</button></li>`)}`);
      } catch (err) { list.innerHTML = String(html`<li class="muted">${err.message}</li>`); }
    }
    d.body.addEventListener('click', async (e) => {
      const g = e.target.closest('[data-g]');
      if (g) { d.body.querySelectorAll('[data-g]').forEach((x) => x.setAttribute('aria-pressed', String(x === g))); load(g.dataset.g); }
      const s = e.target.closest('[data-sample]')?.dataset.sample;
      if (s) { const v = items.find((x) => x.id === s); audio.src = v.sample; audio.play().catch(() => toast('Couldn’t play the sample.')); }
      const p = e.target.closest('[data-pick]')?.dataset.pick;
      if (p) { const v = items.find((x) => x.id === p); await V.setNarratorVoice(v); toast(`${v.title} is now your narrator.`); d.close(); paint(); }
    });
    load('');
  }

  function record() {
    let rec = null, chunks = [], stream = null, timer = null, started = 0, blob = null;
    const d = openDialog({
      title: 'Record your voice', variant: 'sheet', onClose: () => { try { rec?.state === 'recording' && rec.stop(); } catch { /* ignore */ } stream?.getTracks().forEach((t) => t.stop()); clearInterval(timer); },
      body: html`
        <p class="muted">Find a quiet room. Hold the phone about a hand’s width away, press record, and read this aloud at a natural pace (about a minute):</p>
        <blockquote class="read-this">${READ_ALOUD}</blockquote>
        <div class="rec-row"><button type="button" class="btn btn-primary" data-r="start">${icon('mic', { size: 18 })} Start recording</button><span class="num rec-time" id="rtime">0:00</span></div>
        <audio id="rprev" controls hidden></audio>
        <p class="small faint">Or <label class="linklike" for="rfile" style="display:inline">upload a recording</label> (MP3, M4A, WAV, or WebM, 20 seconds to 2 minutes).<input type="file" id="rfile" accept="audio/*" hidden /></p>
        <div class="field"><label for="rtitle">Name for this voice</label><input class="input" id="rtitle" value="My voice" maxlength="60" /></div>
        <label class="consent"><input type="checkbox" id="rconsent" /> <span>This recording is <strong>my own voice</strong>, and I agree to the site owner’s Fish Audio account storing it to create a private voice. I won’t use it to imitate anyone else.</span></label>
        <p class="form-error" id="rerr" role="alert"></p>
        <div class="dialog-actions"><button type="button" class="btn btn-primary" data-r="make" disabled>Create my voice</button></div>`,
    });
    const $ = (s) => d.body.querySelector(s);
    const err = $('#rerr');
    const refresh = () => { $('[data-r="make"]').disabled = !(blob && $('#rconsent').checked); };
    $('#rconsent').addEventListener('change', refresh);
    const setBlob = (b) => {
      blob = b;
      const prev = $('#rprev');
      prev.src = URL.createObjectURL(b); prev.hidden = false;
      refresh();
    };
    $('#rfile').addEventListener('change', (e) => {
      const f = e.target.files?.[0];
      if (!f) return;
      if (!/^audio\//.test(f.type)) { err.textContent = 'That file isn’t audio.'; return; }
      if (f.size > 12 * 1024 * 1024) { err.textContent = 'That file is too large. Keep it under 2 minutes.'; return; }
      err.textContent = '';
      setBlob(f);
    });
    d.body.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-r]')?.dataset.r;
      if (a === 'start') {
        if (rec?.state === 'recording') { rec.stop(); return; }
        err.textContent = '';
        try { stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } }); }
        catch { err.textContent = 'Mavis couldn’t use the microphone. Allow microphone access for this site and try again.'; return; }
        const type = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm'].find((t) => window.MediaRecorder?.isTypeSupported?.(t)) || '';
        rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
        chunks = [];
        rec.ondataavailable = (ev) => ev.data.size && chunks.push(ev.data);
        rec.onstop = () => {
          clearInterval(timer);
          stream.getTracks().forEach((t) => t.stop());
          const secs = (Date.now() - started) / 1000;
          e.target.closest('[data-r]').innerHTML = String(html`${icon('mic', { size: 18 })} Record again`);
          if (secs < 20) { err.textContent = 'That was a bit short. Read the whole passage (at least 20 seconds).'; return; }
          setBlob(new Blob(chunks, { type: rec.mimeType || 'audio/webm' }));
        };
        rec.start(1000);
        started = Date.now();
        e.target.closest('[data-r]').innerHTML = String(html`${icon('stop', { size: 18 })} Stop`);
        timer = setInterval(() => {
          const s = Math.floor((Date.now() - started) / 1000);
          $('#rtime').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
          if (s >= 110) rec.stop();
        }, 250);
      }
      if (a === 'make') {
        const btn = e.target.closest('[data-r]');
        btn.disabled = true; btn.textContent = 'Creating your voice…';
        try {
          const v = await V.cloneMyVoice({ blob, title: $('#rtitle').value.trim() || 'My voice', text: blob instanceof File ? '' : READ_ALOUD });
          await V.setNarratorVoice(v);
          d.close();
          toast(`“${v.title}” is ready and set as your narrator.`);
          paint();
        } catch (e2) { err.textContent = e2.message; btn.disabled = false; btn.textContent = 'Create my voice'; }
      }
    });
  }

  await paint();
}
