// Reading companion: "Picture this", "Story so far" (spoken recap), and
// "Characters" — all built only from what the reader has reached, so nothing
// is spoiled. Chapter summaries are cached on the device so recaps get faster
// and cheaper as you read.

import { html, icon, toast, openDialog } from './ui.js';
import * as store from './store.js';
import { loadFeatures, can, features, ownerPost, accessCode } from './features.js';
import { Narrator, sentences } from './speech.js';
import { ttsSupported, whenVoicesReady } from './tts.js';

export const STYLES = [['painterly', 'Painterly'], ['watercolor', 'Watercolor'], ['pencil', 'Pencil sketch'], ['storybook', 'Storybook'], ['stained glass', 'Stained glass']];

// ---------- pictures ----------
const picKey = (bookKey) => `pics|${store.getOwner()}|${bookKey}`;

export async function listPictures(bookKey) {
  return (await store.getCache(picKey(bookKey))) || [];
}

export async function savePicture(bookKey, pic) {
  const list = await listPictures(bookKey);
  list.unshift(pic);
  await store.setCache(picKey(bookKey), list.slice(0, 60));
}

async function deletePicture(bookKey, id) {
  const list = (await listPictures(bookKey)).filter((p) => p.id !== id);
  await store.setCache(picKey(bookKey), list);
}

function b64ToBlob(b64, type = 'image/webp') {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type });
}

/** Start a picture and wait for it (polling), reporting progress. */
export async function makePicture({ title, author, chapter, passage, style, scene, look, shape }, { onTick, signal } = {}) {
  const { id } = await ownerPost('/api/study', { task: 'picture', title, author, chapter, passage, style, scene, look, shape }, { signal });
  const started = Date.now();
  for (;;) {
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
    await new Promise((r) => setTimeout(r, 2500));
    onTick?.(Math.round((Date.now() - started) / 1000));
    const r = await fetch(`/api/study?picture=${encodeURIComponent(id)}`, { headers: { 'x-mavis-access': accessCode() }, signal, cache: 'no-store' });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.message || `The picture service answered ${r.status}.`);
    if (j.status === 'done') return { blob: b64ToBlob(j.image), revised: j.revised };
    if (j.status === 'failed') throw new Error(j.error || 'The picture couldn’t be made.');
    if (Date.now() - started > 180_000) throw new Error('The picture is taking too long. Try again in a minute.');
  }
}

// ---------- summaries, recap, characters ----------
const sumKey = (bookKey, i) => `sum|${store.getOwner()}|${bookKey}|${i}`;

/** Make sure every finished chapter before the reader's position has a cached summary. */
export async function ensureSummaries(ctx, { onProgress, signal } = {}) {
  const sections = await ctx.previousSections();
  const out = new Array(sections.length);
  const todo = [];
  for (let i = 0; i < sections.length; i++) {
    const cached = await store.getCache(sumKey(ctx.key, sections[i].index));
    if (cached != null) out[i] = { chapter: sections[i].label, summary: cached };
    else todo.push(i);
  }
  let done = sections.length - todo.length;
  onProgress?.(done, sections.length);
  const worker = async () => {
    while (todo.length) {
      if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
      const i = todo.shift();
      const s = sections[i];
      const { summary } = await ownerPost('/api/study', { task: 'summarize', title: ctx.title, author: ctx.author, chapter: s.label, text: s.text }, { signal });
      // Cache empty answers too (a very short section, or one the spoiler guard blanked) so they aren't asked for again.
      await store.setCache(sumKey(ctx.key, s.index), summary || '');
      out[i] = { chapter: s.label, summary: summary || '' };
      onProgress?.(++done, sections.length);
    }
  };
  await Promise.all([worker(), worker(), worker()]);
  const list = out.filter((x) => x?.summary);
  // The words of the chapters already read, so the server's spoiler guard knows
  // who the reader has met even when a summary leaves them out.
  const seen = new Set();
  for (const s of sections) for (const w of String(s.text || '').match(/\p{L}[\p{L}'’-]{2,30}/gu) || []) { if (seen.size >= 12000) break; seen.add(w.toLowerCase()); }
  list.seen = [...seen];
  return list;
}

// ---------- the panel ----------
/**
 * ctx: { key, title, author, chapter(), pageText(), selectionText(), textSoFar(), previousSections(), container }
 */
export async function openCompanion(ctx, { tab = 'picture', only = null, defaultStyle = null } = {}) {
  await loadFeatures();
  const f = features();
  const ready = can('assistant');
  const pics = ready && !!f.pictures;
  let ctl = null;
  let narrator = null;
  const cancel = () => { ctl?.abort(); ctl = null; narrator?.destroy(); narrator = null; };
  const d = openDialog({
    title: only === 'picture' ? 'Picture this passage' : 'Reading companion', variant: 'side', container: ctx.container, className: 'companion', onClose: cancel,
    body: html`
      <div class="seg" role="tablist" aria-label="Companion" ${only ? 'hidden' : ''}>
        <button type="button" role="tab" data-tab="picture" aria-selected="${tab === 'picture'}">${icon('present', { size: 16 })} Picture</button>
        <button type="button" role="tab" data-tab="recap" aria-selected="${tab === 'recap'}">${icon('headphones', { size: 16 })} Story so far</button>
        <button type="button" role="tab" data-tab="people" aria-selected="${tab === 'people'}">${icon('user', { size: 16 })} Characters</button>
      </div>
      <div id="cp-body"></div>`,
  });
  const body = d.body.querySelector('#cp-body');

  const notReady = () => html`<div class="state"><h3>The reading companion needs Ask Mavis</h3>
    <p>${!f.assistant ? 'The site owner hasn’t connected an AI provider yet.' : 'Enter the owner’s access code in Settings on this device.'}</p>
    <a class="btn btn-sm" href="#/settings">${icon('settings', { size: 18 })} Open settings</a></div>`;

  async function showPicture() {
    const gallery = await listPictures(ctx.key);
    const sel = ctx.selectionText();
    body.innerHTML = String(html`
      ${!ready ? notReady() : !pics ? html`<p class="muted">Pictures need an OpenAI key on the server (the AI provider here is ${f.assistant?.provider || 'not set'}).</p>` : html`
        <p class="muted small">Mavis paints the ${sel ? 'passage you selected' : 'page you’re on'} as an illustration. It takes about half a minute and uses a little of the site owner’s OpenAI credit.</p>
        <div class="field"><label for="cp-style">Style</label><select class="select" id="cp-style">${STYLES.map(([v, l]) => html`<option value="${v}" ${(defaultStyle || store.getSetting('pictureStyle', 'painterly')) === v ? 'selected' : ''}>${l}</option>`)}</select></div>
        <div class="ab-actions"><button type="button" class="btn btn-primary" data-cp="draw">${icon('spark', { size: 18 })} Picture this ${sel ? 'passage' : 'page'}</button>
          <button type="button" class="btn" data-cp="film">${icon('play', { size: 18 })} Film this ${sel ? 'passage' : 'page'}</button></div>
        <p class="small faint">A scene film paints a few shots that follow the text and narrates it, as a video you can save or share.</p>
        <div id="cp-out" aria-live="polite"></div>`}
      ${gallery.length ? html`<h3 class="h-section" style="margin-top:18px">Your pictures from this book</h3>
        <ul class="pic-grid">${gallery.map((p) => html`<li><button type="button" class="pic-thumb" data-pic="${p.id}" aria-label="${p.chapter || 'Picture'}"><img alt="" data-blob="${p.id}" /></button></li>`)}</ul>` : ''}`);
    // Attach thumbnails from the stored blobs.
    for (const img of body.querySelectorAll('img[data-blob]')) {
      const p = gallery.find((x) => x.id === img.dataset.blob);
      if (p?.blob) { img.src = URL.createObjectURL(p.blob); img.onload = () => URL.revokeObjectURL(img.src); }
    }
  }

  function showImage(p, { fresh = false } = {}) {
    const url = URL.createObjectURL(p.blob);
    const v = openDialog({
      title: p.chapter || 'Picture', variant: 'sheet', className: 'pic-view', container: ctx.container,
      body: html`<figure class="pic-figure"><img src="${url}" alt="Illustration of a passage from ${ctx.title}" />
        <figcaption class="small faint">${fresh ? 'Saved to this book’s pictures on this device. ' : ''}Made with AI from ${p.chapter ? `“${p.chapter}” in ` : ''}${ctx.title}. ${STYLES.find((s) => s[0] === p.style)?.[1] || ''}</figcaption></figure>
        <div class="dialog-actions" style="justify-content:flex-start">
          <button type="button" class="btn btn-sm" data-pv="share">${icon('share', { size: 18 })} Share</button>
          <a class="btn btn-sm" href="${url}" download="${(ctx.title || 'mavis').replace(/[^\w-]+/g, '-').slice(0, 40)}-picture.webp">${icon('download', { size: 18 })} Save image</a>
          <button type="button" class="btn btn-sm btn-quiet" data-pv="delete">${icon('trash', { size: 18 })} Delete</button>
        </div>`,
      onClose: () => setTimeout(() => URL.revokeObjectURL(url), 1000),
    });
    v.body.addEventListener('click', async (e) => {
      const a = e.target.closest('[data-pv]')?.dataset.pv;
      if (a === 'share') {
        const file = new File([p.blob], 'mavis-picture.webp', { type: p.blob.type || 'image/webp' });
        try {
          if (navigator.canShare?.({ files: [file] })) await navigator.share({ files: [file], title: ctx.title, text: `A scene from ${ctx.title}` });
          else toast('Sharing pictures isn’t supported here. Use “Save image” instead.');
        } catch { /* cancelled */ }
      }
      if (a === 'delete') { await deletePicture(ctx.key, p.id); v.close(); toast('Picture deleted.'); showPicture(); }
    });
  }

  async function draw() {
    const out = body.querySelector('#cp-out');
    const style = body.querySelector('#cp-style').value;
    store.setSetting('pictureStyle', style);
    const passage = ctx.selectionText() || ctx.pageText();
    if (!passage || passage.length < 30) { out.innerHTML = String(html`<p class="form-error">There isn’t enough text on this page to picture. Turn to a page with more story, or select a passage.</p>`); return; }
    const btn = body.querySelector('[data-cp="draw"]');
    btn.disabled = true;
    out.innerHTML = String(html`<div class="cp-wait"><div class="pulse-dot" aria-hidden="true"></div><p>Painting… <span class="num" id="cp-secs">0</span>s</p><button type="button" class="btn btn-sm btn-quiet" data-cp="stop">Cancel</button></div>`);
    ctl = new AbortController();
    try {
      const { blob, revised } = await makePicture({ title: ctx.title, author: ctx.author, chapter: ctx.chapter(), passage, style }, {
        signal: ctl.signal, onTick: (s) => { const el = body.querySelector('#cp-secs'); if (el) el.textContent = String(s); },
      });
      const pic = { id: store.uuid(), blob, style, chapter: ctx.chapter(), passage: passage.slice(0, 300), revised: (revised || '').slice(0, 400), createdAt: Date.now() };
      await savePicture(ctx.key, pic);
      await showPicture();
      showImage(pic, { fresh: true });
    } catch (err) {
      if (err.name === 'AbortError') { out.innerHTML = ''; } else out.innerHTML = String(html`<p class="form-error" role="alert">${err.message}</p>`);
    } finally {
      ctl = null;
      if (btn.isConnected) btn.disabled = false;
    }
  }

  async function showRecap() {
    body.innerHTML = String(!ready ? notReady() : html`
      <p class="muted small">A short, spoken recap of everything up to where you are — nothing past it. Chapter notes are saved on this device, so later recaps are quick.</p>
      <button type="button" class="btn btn-primary" data-cp="recap">${icon('headphones', { size: 18 })} Catch me up</button>
      <div id="cp-out" aria-live="polite"></div>`);
  }

  async function runRecap() {
    const out = body.querySelector('#cp-out');
    ctl = new AbortController();
    out.innerHTML = String(html`<p class="muted" id="cp-prog">Reading your progress…</p>`);
    try {
      const summaries = await ensureSummaries(ctx, { signal: ctl.signal, onProgress: (n, t) => { const el = body.querySelector('#cp-prog'); if (el && t) el.textContent = `Making chapter notes… ${n} of ${t}`; } });
      const el = body.querySelector('#cp-prog'); if (el) el.textContent = 'Writing your recap…';
      const { recap } = await ownerPost('/api/study', { task: 'recap', title: ctx.title, author: ctx.author, summaries, seen: summaries.seen, current: { chapter: ctx.chapter(), text: ctx.textSoFar() } }, { signal: ctl.signal });
      out.innerHTML = String(html`<div class="recap">${recap.split(/\n+/).map((p) => html`<p>${p}</p>`)}</div>
        <div class="dialog-actions" style="justify-content:flex-start"><button type="button" class="btn btn-sm btn-primary" data-cp="speak">${icon('play', { size: 18 })} Listen</button><button type="button" class="btn btn-sm btn-quiet" data-cp="hush" hidden>${icon('stop', { size: 18 })} Stop</button></div>`);
      out.dataset.recap = recap;
    } catch (err) {
      if (err.name !== 'AbortError') out.innerHTML = String(html`<p class="form-error" role="alert">${err.message}</p>`);
    } finally { ctl = null; }
  }

  async function speakRecap() {
    const text = body.querySelector('#cp-out')?.dataset.recap;
    if (!text) return;
    narrator?.destroy();
    const engine = store.getSetting('voiceEngine', 'device') === 'cloud' && can('cloudVoice') ? 'cloud' : 'device';
    if (engine === 'device' && !ttsSupported) { toast('This browser can’t read aloud. Turn on the cloud voice in Settings.', { tone: 'error' }); return; }
    const voices = engine === 'device' ? await whenVoicesReady() : [];
    const saved = store.getSetting('ttsVoice', null);
    let served = false;
    narrator = new Narrator({
      source: { label: () => 'Story so far', next: async () => (served ? null : (served = true, sentences(text).map((s) => ({ text: s })))) },
      engine, title: ctx.title,
      voice: () => voices.find((v) => v.voiceURI === saved) || voices.find((v) => v.lang?.startsWith('en')) || null,
      rate: () => Number(store.getSetting('ttsRate', 1)) || 1,
      onState: (s) => {
        const hush = body.querySelector('[data-cp="hush"]');
        if (hush) hush.hidden = !(s.state === 'playing' || s.state === 'loading');
      },
    });
    narrator.start();
  }

  async function showPeople() {
    const cacheId = `chars|${store.getOwner()}|${ctx.key}`;
    const cached = await store.getCache(cacheId);
    body.innerHTML = String(!ready ? notReady() : html`
      <p class="muted small">Who’s who so far, and how they’re connected — built only from what you’ve read.</p>
      <button type="button" class="btn btn-primary" data-cp="people">${icon('user', { size: 18 })} ${cached ? 'Update for where I am now' : 'Build my character list'}</button>
      <div id="cp-out" aria-live="polite"></div>`);
    if (cached) paintPeople(cached);
  }

  function paintPeople(data) {
    const out = body.querySelector('#cp-out');
    const list = data.characters || [];
    if (!list.length) { out.innerHTML = String(html`<p class="muted">No characters yet — read a little further.</p>`); return; }
    out.innerHTML = String(html`<p class="small faint">As of ${data.chapter || 'your current chapter'}.</p>
      ${relationMap(list)}
      <ul class="people">${list.map((c, i) => html`<li class="person imp-${c.importance}" id="p-${i}">
        <h3>${c.name}${c.aka?.length ? html` <span class="small faint">(${c.aka.join(', ')})</span>` : ''}</h3>
        ${c.role ? html`<p class="person-role">${c.role}</p>` : ''}
        ${c.description ? html`<p>${c.description}</p>` : ''}
        ${c.relations?.length ? html`<p class="rels">${c.relations.map((r) => {
          const j = list.findIndex((x) => x.name === r.to);
          return j >= 0 ? html`<a class="chip" href="#p-${j}" data-jump="p-${j}">${r.relation}: ${r.to}</a>` : html`<span class="chip">${r.relation}: ${r.to}</span>`;
        })}</p>` : ''}
        ${c.firstSeen ? html`<p class="small faint">First appears: ${c.firstSeen}</p>` : ''}</li>`)}</ul>`);
  }

  async function runPeople() {
    const out = body.querySelector('#cp-out');
    ctl = new AbortController();
    out.innerHTML = String(html`<p class="muted" id="cp-prog">Reading your progress…</p>`);
    try {
      const summaries = await ensureSummaries(ctx, { signal: ctl.signal, onProgress: (n, t) => { const el = body.querySelector('#cp-prog'); if (el && t) el.textContent = `Making chapter notes… ${n} of ${t}`; } });
      const el = body.querySelector('#cp-prog'); if (el) el.textContent = 'Finding the characters…';
      const res = await ownerPost('/api/study', { task: 'characters', title: ctx.title, author: ctx.author, summaries, seen: summaries.seen, current: { chapter: ctx.chapter(), text: ctx.textSoFar() } }, { signal: ctl.signal });
      const data = { characters: res.characters || [], chapter: ctx.chapter(), at: Date.now() };
      await store.setCache(`chars|${store.getOwner()}|${ctx.key}`, data);
      paintPeople(data);
    } catch (err) {
      if (err.name !== 'AbortError') out.innerHTML = String(html`<p class="form-error" role="alert">${err.message}</p>`);
    } finally { ctl = null; }
  }

  const tabs = { picture: showPicture, recap: showRecap, people: showPeople };
  d.body.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-tab]');
    if (t) {
      cancel();
      d.body.querySelectorAll('[data-tab]').forEach((b) => b.setAttribute('aria-selected', String(b === t)));
      tabs[t.dataset.tab]();
      return;
    }
    const jump = e.target.closest('[data-jump]');
    if (jump) { e.preventDefault(); d.body.querySelector(`#${jump.dataset.jump}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); return; }
    const pic = e.target.closest('[data-pic]');
    if (pic) { const p = (await listPictures(ctx.key)).find((x) => x.id === pic.dataset.pic); if (p) showImage(p); return; }
    const a = e.target.closest('[data-cp]')?.dataset.cp;
    if (a === 'draw') draw();
    if (a === 'film') {
      const sel = ctx.selectionText();
      const text = sel || ctx.pageText();
      const chapter = ctx.chapter();
      d.close();
      const { openSceneFilm } = await import('./scenefilm.js');
      openSceneFilm({ text, source: sel ? 'selection' : 'page', title: ctx.title, author: ctx.author, chapter, citation: `${ctx.title}${ctx.author ? `, ${ctx.author}` : ''}${chapter ? ` · ${chapter}` : ''}`, bookKey: ctx.key, container: ctx.container, defaultStyle: store.getSetting('pictureStyle', 'painterly') });
    }
    if (a === 'stop') cancel();
    if (a === 'recap') runRecap();
    if (a === 'speak') speakRecap();
    if (a === 'hush') narrator?.stop();
    if (a === 'people') runPeople();
  });
  tabs[tab]();
  return d;
}

/** A small radial map of the main characters and their connections. */
function relationMap(list) {
  const main = list.slice(0, 10);
  if (main.length < 2) return '';
  const W = 320, H = 260, cx = W / 2, cy = H / 2, R = 96;
  const pos = main.map((c, i) => (i === 0 ? { x: cx, y: cy } : { x: cx + R * Math.cos((2 * Math.PI * (i - 1)) / (main.length - 1) - Math.PI / 2), y: cy + R * Math.sin((2 * Math.PI * (i - 1)) / (main.length - 1) - Math.PI / 2) }));
  const edges = [];
  main.forEach((c, i) => (c.relations || []).forEach((r) => {
    const j = main.findIndex((x) => x.name === r.to);
    if (j >= 0 && j !== i && !edges.some((e) => (e[0] === j && e[1] === i))) edges.push([i, j]);
  }));
  const short = (n) => (n.length > 14 ? `${n.slice(0, 13)}…` : n);
  return html`<svg class="relmap" viewBox="0 0 ${W} ${H}" role="img" aria-label="How the main characters are connected">
    ${edges.map(([a, b]) => html`<line x1="${pos[a].x}" y1="${pos[a].y}" x2="${pos[b].x}" y2="${pos[b].y}" />`)}
    ${main.map((c, i) => html`<g class="node imp-${c.importance}"><circle cx="${pos[i].x}" cy="${pos[i].y}" r="${i === 0 ? 26 : 18 + c.importance * 2}" /><text x="${pos[i].x}" y="${pos[i].y + (i === 0 ? 40 : 34)}" text-anchor="middle">${short(c.name)}</text></g>`)}
  </svg>`;
}
