// The reader: EPUB rendering (epub.js), navigation, typography, annotations,
// dictionary, read-aloud, immersive mode, and a clearly labeled PDF viewer.

import { html, icon, toast, openDialog, confirmDialog, debounce, prefersReducedMotion, esc, $ } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { recallBook } from '../lib/catalog.js';
import { fontFaceCSS, READER_FONTS } from '../lib/fonts.js';
import { define, normalizeTerm } from '../lib/dictionary.js';
import * as vocab from '../lib/vocab.js';
import { trackReading, isKids } from '../lib/kids.js';
import { groupsForBook, myGroups } from '../lib/groups.js';
import { listen } from '../lib/voice-input.js';
import { ReadAloud, ttsSupported, whenVoicesReady, speakWord } from '../lib/tts.js';
import { stateBlock } from '../components.js';
import { Narrator, sentences } from '../lib/speech.js';
import { openCarMode } from '../lib/carmode.js';
import { openAssistant } from '../lib/assistant-ui.js';
import { loadFeatures, can, features } from '../lib/features.js';
import { copyQuote, shareQuote } from '../lib/quotes.js';
import { manifest as audiobookManifest, summarize as audiobookSummary } from '../lib/audiobook.js';

export const title = () => 'Reading';

const HL = { sun: '#e9c46a', mint: '#86c7a1', sky: '#8fb8e6', rose: '#e7a1a8' };
const THEMES = {
  light: { label: 'Paper', bg: '#fbf8f1', fg: '#1e1d1a', link: '#24583f' },
  sepia: { label: 'Sepia', bg: '#f3e7cf', fg: '#3b2f20', link: '#6b4a1c' },
  dark: { label: 'Night', bg: '#151916', fg: '#d9d3c4', link: '#9fd0b1' },
};
const DEFAULTS = { theme: 'light', font: 'literata', size: 100, lineHeight: 1.6, margin: 'normal', justify: false, motion: 'slide', spread: 'auto' };
const MARGINS = { narrow: '2%', normal: '7%', wide: '14%' };

// Kids mode keeps its own reader settings, starting bigger and airier.
const KIDS_DEFAULTS = { size: 130, lineHeight: 1.85, font: 'atkinson', margin: 'normal' };
const prefsKey = () => (isKids() ? 'reader-kids' : 'reader');
function readerPrefs() { return { ...DEFAULTS, ...(isKids() ? KIDS_DEFAULTS : {}), ...(store.getSetting(prefsKey(), {}) || {}) }; }
function saveReaderPrefs(p) { store.setSetting(prefsKey(), p); }

export async function render(root, route, { navigate }) {
  const key = route.segs[0] || '';
  let item = await store.getShelfItem(key).catch(() => null);
  const file = await store.getFile(key).catch(() => null);
  const meta = item || recallBook(key) || { key, title: 'Book', authors: [] };

  const close = () => ((window.__mavisNavDepth || 0) > 0 ? history.back() : navigate('/shelf'));

  if (!file) {
    root.innerHTML = String(html`<div class="reader" data-rtheme="${readerPrefs().theme}" role="dialog" aria-modal="true" aria-label="Reader">
      <header class="reader-bar top"><button class="icon-btn" type="button" data-close aria-label="Close book">${icon('back')}</button><div class="reader-title"><div class="bt">${meta.title}</div></div></header>
      <div class="reader-stage"><div class="reader-loading">${stateBlock({
        title: 'This book isn’t on this device',
        text: meta.source === 'import'
          ? 'Imported files stay on the device they were imported on. Import the file here to read it; your progress and notes will carry over.'
          : 'Download it to read here. Downloads are saved on this device for offline reading.',
        actions: meta.source === 'import'
          ? html`<a class="btn btn-primary btn-sm" href="#/shelf?import=1">Import the file</a>`
          : html`<a class="btn btn-primary btn-sm" href="#/book/${encodeURIComponent(key)}">Go to download</a>`,
      })}</div></div></div>`);
    root.querySelector('[data-close]').addEventListener('click', close);
    return;
  }
  if (!item) {
    // A downloaded file without a shelf record (shouldn't happen) — restore it.
    item = await store.saveToShelf({ ...meta, key }, { status: 'reading' });
  }
  if (file.mime === 'application/pdf' || item.format === 'pdf') return renderPdf(root, item, file, close);
  return renderEpub(root, item, file, close, route);
}

// ---------------------------------------------------------------- PDF ----

function renderPdf(root, item, file, close) {
  const url = URL.createObjectURL(file.blob);
  root.innerHTML = String(html`<div class="reader" data-rtheme="light" role="dialog" aria-modal="true" aria-label="PDF viewer">
    <header class="reader-bar top">
      <button class="icon-btn" type="button" data-close aria-label="Close PDF">${icon('back')}</button>
      <div class="reader-title"><div class="bt">${item.title}</div><div class="ch">PDF · shown with your browser’s built-in viewer</div></div>
      <a class="btn btn-sm" href="${url}" target="_blank" rel="noopener">${icon('external', { size: 16 })} Open in new tab</a>
    </header>
    <div class="reader-stage"><iframe class="pdf-frame" src="${url}#view=FitH" title="${item.title} (PDF)"></iframe></div>
    <footer class="reader-bar bottom"><p class="small" style="color:var(--r-faint);flex:1">Bookmarks, highlights, read-aloud, and reading progress work with EPUB and text books. For PDFs they’re turned off here rather than imitated. If the PDF doesn’t appear (some phones can’t show PDFs inside a page), use Open in new tab.</p></footer>
  </div>`);
  root.querySelector('[data-close]').addEventListener('click', close);
  store.updateShelf(item.key, { lastOpenedAt: Date.now(), status: item.status === 'want' ? 'reading' : item.status }).catch(() => {});
  return () => setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// --------------------------------------------------------------- EPUB ----

async function renderEpub(root, item, file, close, route) {
  let prefs = readerPrefs();
  const key = item.key;
  root.innerHTML = String(html`
  <div class="reader" data-rtheme="${prefs.theme}" role="dialog" aria-modal="true" aria-label="Reading ${item.title}" style="grid-template-rows:auto 1fr auto auto">
    <header class="reader-bar top">
      <button class="icon-btn" type="button" data-act="close" aria-label="Close book">${icon('back')}</button>
      <div class="reader-title"><div class="bt">${item.title}</div><div class="ch" id="r-chapter">&nbsp;</div></div>
      <button class="icon-btn" type="button" data-act="toc" aria-label="Contents, bookmarks, and notes">${icon('toc')}</button>
      <button class="icon-btn" type="button" data-act="bookmark" aria-label="Bookmark this page" aria-pressed="false">${icon('bookmark')}</button>
      <button class="icon-btn" type="button" data-act="display" aria-label="Display settings">${icon('type')}</button>
      <button class="icon-btn" type="button" data-act="tts" aria-label="Read aloud" aria-pressed="false">${icon('headphones')}</button>
      <button class="icon-btn" type="button" data-act="lookup" aria-label="Look up a word">${icon('dict')}</button>
      <button class="icon-btn" type="button" data-act="ask" aria-label="Ask Mavis about this book">${icon('spark')}</button>
      <button class="icon-btn" type="button" data-act="companion" aria-label="Reading companion: picture this page, story so far, characters">${icon('present')}</button>
      <button class="icon-btn" type="button" data-act="club" aria-label="Book club: share and discuss" ${groupsForBook(key).length ? '' : 'hidden'}>${icon('user')}</button>
      <button class="icon-btn" type="button" data-act="immersive" aria-label="Focus mode (hide controls)">${icon('expand')}</button>
    </header>
    <div class="reader-stage" id="stage">
      <div class="reader-view" id="viewer"></div>
      <button class="page-zone prev" type="button" data-act="prev" aria-label="Previous page">${icon('chevronL', { size: 30 })}</button>
      <button class="page-zone next" type="button" data-act="next" aria-label="Next page">${icon('chevronR', { size: 30 })}</button>
      <div class="reader-loading" id="r-loading" role="status"><div style="display:grid;gap:10px;justify-items:center"><div class="skeleton" style="width:120px;height:4px"></div><span>Opening “${item.title}”…</span></div></div>
      <div class="sel-toolbar" id="sel" role="toolbar" aria-label="Selected text" hidden></div>
      <button class="exit-immersive" type="button" data-act="immersive" hidden>${icon('collapse', { size: 18 })} Exit focus mode</button>
    </div>
    <footer class="reader-bar bottom">
      <button class="icon-btn" type="button" data-act="prev" aria-label="Previous page">${icon('chevronL')}</button>
      <div class="progress-wrap">
        <label class="visually-hidden" for="r-progress">Position in book</label>
        <input type="range" id="r-progress" min="0" max="1000" value="0" disabled aria-valuetext="Calculating position" />
        <div class="progress-meta"><span id="r-page">&nbsp;</span><span id="r-pct" class="num">…</span></div>
      </div>
      <button class="icon-btn" type="button" data-act="next" aria-label="Next page">${icon('chevronR')}</button>
    </footer>
    <section class="tts-bar" id="tts" aria-label="Read aloud" hidden></section>
  </div>`);

  const readerEl = root.querySelector('.reader');
  const stage = root.querySelector('#stage');
  const viewer = root.querySelector('#viewer');
  const loading = root.querySelector('#r-loading');
  const sel = root.querySelector('#sel');
  const chapterEl = root.querySelector('#r-chapter');
  const pageEl = root.querySelector('#r-page');
  const pctEl = root.querySelector('#r-pct');
  const slider = root.querySelector('#r-progress');
  const bmBtn = root.querySelector('[data-act="bookmark"]');
  const ttsBar = root.querySelector('#tts');

  let destroyed = false;
  let book, rendition, tts = null, EpubCFI = null, saveProgress = null;
  let toc = [];
  let annotations = [];
  let lastLoc = null;
  let locationsReady = false;
  let immersive = false;
  const cleanups = [];

  function fail(title, text) {
    loading.hidden = false;
    loading.innerHTML = String(stateBlock({
      tone: 'error', title, text,
      actions: html`<button class="btn btn-sm" type="button" data-act="close">Back</button><a class="btn btn-sm btn-quiet" href="#/book/${encodeURIComponent(key)}">Book details</a>`,
    }));
  }

  // ---------- Open the book ----------
  try {
    const mod = await import('epubjs');
    const ePub = mod.default;
    EpubCFI = mod.EpubCFI;
    const buf = await file.blob.arrayBuffer();
    book = ePub();
    const opened = book.open(buf, 'binary');
    await Promise.race([opened, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), 25000))]);
    await book.ready;
  } catch (err) {
    console.error(err);
    if (!destroyed) fail('This book couldn’t be opened', err.message === 'timeout'
      ? 'The file took too long to open. It may be very large or damaged.'
      : 'The EPUB file looks damaged or uses a format Mavis can’t read. Try downloading or importing it again.');
    wireBasicControls();
    return () => { destroyed = true; try { book?.destroy(); } catch { /* ignore */ } };
  }
  if (destroyed) return;

  const lang = (book.packaging?.metadata?.language || item.languages?.[0] || 'en').slice(0, 5);

  rendition = book.renderTo(viewer, {
    width: '100%', height: '100%', flow: 'paginated', spread: prefs.spread === 'none' ? 'none' : 'auto', minSpreadWidth: 900,
    allowScriptedContent: false, allowPopups: false,
  });

  try {
    const nav = await book.loaded.navigation;
    toc = flattenToc(nav?.toc || []);
  } catch { toc = []; }

  // ---------- Content hooks: fonts, safety, input ----------
  rendition.hooks.content.register((contents) => {
    const doc = contents.document;
    const style = doc.createElement('style');
    style.setAttribute('data-mavis', 'fonts');
    style.textContent = `${fontFaceCSS()}\n::selection{background:rgba(217,164,65,.38)}\nimg,svg{max-width:100%;height:auto}`;
    doc.head.appendChild(style);
    // Defense in depth: content scripts can't run (sandboxed, CSP), but strip
    // them and inline handlers anyway.
    for (const s of doc.querySelectorAll('script, iframe, object, embed, form')) s.remove();
    for (const el of doc.querySelectorAll('*')) {
      for (const a of [...el.attributes]) if (/^on/i.test(a.name)) el.removeAttribute(a.name);
    }
    doc.addEventListener('click', (e) => {
      const a = e.target.closest?.('a[href]');
      if (!a) { onContentTap(e, contents); return; }
      const href = a.getAttribute('href') || '';
      if (/^(https?:|mailto:)/i.test(href) || href.includes('://')) {
        e.preventDefault(); e.stopPropagation();
        confirmExternal(href);
      }
    }, true);
    doc.addEventListener('keydown', onKey);
    let sx = 0, sy = 0, st = 0;
    doc.addEventListener('touchstart', (e) => { const t = e.changedTouches[0]; sx = t.screenX; sy = t.screenY; st = Date.now(); }, { passive: true });
    doc.addEventListener('touchend', (e) => {
      const t = e.changedTouches[0];
      const dx = t.screenX - sx, dy = t.screenY - sy;
      const selected = contents.window.getSelection()?.toString();
      if (selected) return;
      if (Math.abs(dx) > 50 && Math.abs(dy) < 60 && Date.now() - st < 700) turn(dx < 0 ? 'next' : 'prev');
    }, { passive: true });
  });

  applyStyles();

  // epub.js sometimes lands one page early when asked to show a CFI that sits
  // exactly at a page boundary; nudge forward until the CFI is on screen.
  async function displayCfi(target) {
    await rendition.display(target);
    if (!target || !String(target).startsWith('epubcfi(')) return;
    for (let i = 0; i < 4; i++) {
      await new Promise((r) => requestAnimationFrame(() => setTimeout(r, 40)));
      let loc = rendition.currentLocation();
      if (loc && typeof loc.then === 'function') loc = await loc;
      if (!loc?.end?.cfi) continue;
      const cmp = new EpubCFI();
      try {
        if (cmp.compare(target, loc.end.cfi) > 0 && !loc.atEnd) await rendition.next();
        else if (cmp.compare(target, loc.start.cfi) < 0 && !loc.atStart) await rendition.prev();
        else return;
      } catch { return; }
    }
  }

  // ---------- Restore position ----------
  const saved = await store.getProgress(key).catch(() => null);
  const jumpTo = route?.params?.get('at');
  try {
    await displayCfi((jumpTo && jumpTo.startsWith('epubcfi(') ? jumpTo : null) || saved?.cfi || undefined);
  } catch {
    try { await rendition.display(); } catch (err) { fail('This book couldn’t be displayed', err.message); return; }
  }
  if (destroyed) return;
  loading.hidden = true;
  await store.updateShelf(key, { lastOpenedAt: Date.now(), status: item.status === 'want' ? 'reading' : item.status }).catch(() => {});

  // ---------- Annotations ----------
  annotations = await store.listAnnotations(key).catch(() => []);
  for (const a of annotations) drawAnnotation(a);
  rendition.on('markClicked', (cfiRange, data) => {
    const a = annotations.find((x) => x.uid === data?.uid);
    if (a) editAnnotation(a);
  });

  // ---------- Locations (for percentages) ----------
  (async () => {
    const cacheId = `locations|${store.getOwner()}|${key}`;
    try {
      const cached = await store.getCache(cacheId);
      if (cached) book.locations.load(cached);
      else {
        await book.locations.generate(1200);
        if (destroyed) return;
        store.setCache(cacheId, book.locations.save());
      }
      locationsReady = true;
      slider.disabled = false;
      if (rendition.location) onRelocated(rendition.location);
    } catch (err) { console.warn('Locations unavailable', err); }
  })();

  // ---------- Relocation → UI + progress ----------
  saveProgress = debounce((loc) => {
    const percent = locationsReady ? book.locations.percentageFromCfi(loc.start.cfi) : null;
    store.setProgress(key, { cfi: loc.start.cfi, percent, chapter: chapterLabel(loc) }).catch((err) => toast(`Couldn't save your place: ${err.message}`, { tone: 'error' }));
  }, 800);

  function onRelocated(loc) {
    if (!loc?.start) return;
    lastLoc = loc;
    hideSelection();
    const ch = chapterLabel(loc);
    chapterEl.textContent = ch || ' ';
    const d = loc.start.displayed;
    const endPage = loc.end?.displayed?.page;
    const pages = endPage && endPage > d?.page && loc.end.index === loc.start.index ? `pages ${d.page}–${endPage}` : `page ${d?.page}`;
    pageEl.textContent = d?.total ? `${ch ? `${ch} · ` : ''}${pages} of ${d.total}` : (ch || '');
    if (locationsReady) {
      const pct = book.locations.percentageFromCfi(loc.start.cfi);
      pctEl.textContent = `${Math.round(pct * 100)}%`;
      slider.value = String(Math.round(pct * 1000));
      slider.setAttribute('aria-valuetext', `${Math.round(pct * 100)} percent${ch ? `, ${ch}` : ''}`);
    } else {
      pctEl.textContent = '…';
    }
    updateBookmarkButton();
    saveProgress(loc);
  }
  rendition.on('relocated', onRelocated);
  // Reading time (kept on this device): counts while the reader is turning pages or listening.
  const reading = trackReading();
  cleanups.push(() => reading.stop());
  rendition.on('relocated', () => reading.poke());
  rendition.on('click', () => reading.poke());
  const listenTick = setInterval(() => { try { if (narr?.state === 'playing' || tts?.state === 'playing') reading.poke(); } catch { /* not set up yet */ } }, 20_000);
  cleanups.push(() => clearInterval(listenTick));
  if (rendition.location) onRelocated(rendition.location);

  slider.addEventListener('change', async () => {
    if (!locationsReady) return;
    const cfi = book.locations.cfiFromPercentage(Number(slider.value) / 1000);
    await displayCfi(cfi);
  });
  slider.addEventListener('input', () => { pctEl.textContent = `${Math.round(Number(slider.value) / 10)}%`; });

  // ---------- Selection toolbar ----------
  let pendingSel = null;
  rendition.on('selected', (cfiRange, contents) => {
    const text = contents.window.getSelection()?.toString().trim();
    if (!text) return;
    pendingSel = { cfiRange, contents, text: text.slice(0, 2000) };
    showSelection();
  });

  function showSelection() {
    const { cfiRange, contents } = pendingSel;
    let rect;
    try {
      const range = contents.range(cfiRange);
      const r = range.getBoundingClientRect();
      const frame = contents.document.defaultView.frameElement.getBoundingClientRect();
      const st = stage.getBoundingClientRect();
      rect = { left: r.left + frame.left - st.left, top: r.top + frame.top - st.top, width: r.width, bottom: r.bottom + frame.top - st.top };
    } catch { rect = { left: stage.clientWidth / 2, top: 60, width: 0, bottom: 80 }; }
    sel.innerHTML = String(html`
      ${Object.keys(HL).map((c) => html`<button type="button" class="icon-btn" data-hl="${c}" aria-label="Highlight ${c}"><span class="dot" style="background:${HL[c]}"></span></button>`)}
      <span class="sep" aria-hidden="true"></span>
      <button type="button" class="icon-btn" data-sel="note" aria-label="Add a note">${icon('note', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="define" aria-label="Look up in dictionary">${icon('dict', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="quote" aria-label="Save as a quote">${icon('star', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="share" aria-label="Share quote">${icon('share', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="picture" aria-label="Picture this passage">${icon('present', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="film" aria-label="Make a scene film of this passage">${icon('play', { size: 20 })}</button>
      ${groupsForBook(key).length ? html`<button type="button" class="icon-btn" data-sel="club" aria-label="Share to book club">${icon('user', { size: 20 })}</button>` : ''}
      <button type="button" class="icon-btn" data-sel="copy" aria-label="Copy text">${icon('copy', { size: 20 })}</button>
      <button type="button" class="icon-btn" data-sel="close" aria-label="Close">${icon('close', { size: 18 })}</button>`);
    sel.hidden = false;
    const w = sel.offsetWidth, h = sel.offsetHeight;
    let left = rect.left + rect.width / 2 - w / 2;
    left = Math.max(8, Math.min(stage.clientWidth - w - 8, left));
    let top = rect.top - h - 10;
    if (top < 8) top = Math.min(stage.clientHeight - h - 8, rect.bottom + 10);
    sel.style.left = `${left}px`;
    sel.style.top = `${top}px`;
  }
  function hideSelection(clear = false) {
    sel.hidden = true;
    if (clear && pendingSel) { try { pendingSel.contents.window.getSelection().removeAllRanges(); } catch { /* ignore */ } }
    if (clear) pendingSel = null;
  }
  sel.addEventListener('click', async (e) => {
    const b = e.target.closest('button');
    if (!b || !pendingSel) return;
    const { cfiRange, text } = pendingSel;
    if (b.dataset.hl) {
      const a = await store.addAnnotation(key, { kind: 'highlight', cfi: cfiRange, text, color: b.dataset.hl, chapter: chapterLabel(lastLoc), percent: pct() });
      annotations.push(a); drawAnnotation(a);
      hideSelection(true);
      toast('Highlighted.', { action: { label: 'Add note', run: () => editAnnotation(a) } });
    } else if (b.dataset.sel === 'note') {
      hideSelection(true);
      const a = await store.addAnnotation(key, { kind: 'note', cfi: cfiRange, text, color: 'sky', chapter: chapterLabel(lastLoc), percent: pct() });
      annotations.push(a); drawAnnotation(a);
      editAnnotation(a, { isNew: true });
    } else if (b.dataset.sel === 'define') {
      let sentence = '';
      try { sentence = vocab.sentenceAround(pendingSel.contents.range(cfiRange)); } catch { /* keep going without it */ }
      hideSelection(true);
      openDictionary(text, { cfi: cfiRange, sentence });
    } else if (b.dataset.sel === 'quote') {
      const a = await store.addAnnotation(key, { kind: 'quote', cfi: cfiRange, text, color: 'sun', chapter: chapterLabel(lastLoc), percent: pct() });
      annotations.push(a); drawAnnotation(a);
      hideSelection(true);
      toast('Saved to your quotes.', { action: { label: 'View', run: () => { location.hash = '#/quotes'; } } });
    } else if (b.dataset.sel === 'club') {
      const { openShareToGroup } = await import('../lib/groups-ui.js');
      hideSelection(true);
      openShareToGroup({ groups: groupsForBook(key), quote: text.slice(0, 1200), cite: citeHere(), ref: { kind: 'book', key, cfi: cfiRange }, chapter: chapterLabel(lastLoc), percent: pct(), container: readerEl });
    } else if (b.dataset.sel === 'film') {
      hideSelection(true);
      const { openSceneFilm } = await import('../lib/scenefilm.js');
      openSceneFilm({ text, source: 'selection', title: item.title, author: (item.authors || []).join(', '), chapter: chapterLabel(lastLoc), citation: citeHere(), bookKey: key, container: readerEl });
    } else if (b.dataset.sel === 'picture') {
      openReadingCompanion('picture');
    } else if (b.dataset.sel === 'share') {
      hideSelection(true);
      shareQuote(text, citeHere());
    } else if (b.dataset.sel === 'copy') {
      hideSelection(true);
      copyQuote(text, citeHere());
      return;
    } else if (b.dataset.sel === 'copy-plain') {
      try { await navigator.clipboard.writeText(text); toast('Copied.'); }
      catch { toast('Copying isn’t allowed here. Use your device’s copy option instead.'); }
      hideSelection(true);
    } else hideSelection(true);
  });

  function drawAnnotation(a) {
    if (a.kind === 'bookmark' || a.deleted) return;
    const dark = prefs.theme === 'dark';
    try {
      rendition.annotations.remove(a.cfi, 'highlight');
      rendition.annotations.highlight(a.cfi, { uid: a.uid }, null, `hl-${a.color || 'sun'}`, {
        fill: HL[a.color] || HL.sun, 'fill-opacity': dark ? '0.28' : '0.42', 'mix-blend-mode': dark ? 'normal' : 'multiply',
      });
    } catch (err) { console.warn('Could not draw annotation', err); }
  }

  function editAnnotation(a, { isNew = false } = {}) {
    const d = openDialog({
      title: a.kind === 'note' || a.note ? 'Note' : 'Highlight',
      variant: 'sheet', container: readerEl,
      body: html`<blockquote class="ann-text" style="margin:0;font-family:var(--font-read);border-left:3px solid ${HL[a.color] || HL.sun};padding-left:12px">${a.text}</blockquote>
        <div class="field"><label for="note-text">Your note</label><textarea id="note-text" class="input" rows="4" style="padding:10px 12px;min-height:110px" maxlength="5000" ${isNew ? 'autofocus' : ''}>${a.note || ''}</textarea></div>
        <div class="opt-group"><span class="label">Color</span><div class="swatches">${Object.keys(HL).map((c) => html`<button type="button" class="swatch" style="width:48px;min-height:40px;background:${HL[c]}" data-color="${c}" aria-label="${c}" aria-pressed="${a.color === c}"></button>`)}</div></div>
        <div class="dialog-actions"><button type="button" class="btn btn-quiet" data-del>${icon('trash', { size: 18 })} Delete</button><button type="button" class="btn btn-primary" data-save>Save</button></div>`,
    });
    let color = a.color;
    d.body.addEventListener('click', async (e) => {
      const c = e.target.closest('[data-color]');
      if (c) { color = c.dataset.color; d.body.querySelectorAll('[data-color]').forEach((x) => x.setAttribute('aria-pressed', String(x === c))); }
      if (e.target.closest('[data-save]')) {
        const note = d.body.querySelector('#note-text').value.trim();
        const next = await store.updateAnnotation(a.uid, { note, color, kind: note ? 'note' : 'highlight' });
        Object.assign(a, next); drawAnnotation(a);
        d.close(); toast(note ? 'Note saved.' : 'Highlight saved.');
      }
      if (e.target.closest('[data-del]')) {
        await store.deleteAnnotation(a.uid);
        try { rendition.annotations.remove(a.cfi, 'highlight'); } catch { /* ignore */ }
        annotations = annotations.filter((x) => x.uid !== a.uid);
        d.close(); toast('Deleted.');
      }
    });
  }

  // ---------- Bookmarks ----------
  function bookmarksOnPage() {
    if (!lastLoc?.start) return [];
    const { start, end } = lastLoc;
    return annotations.filter((a) => a.kind === 'bookmark' && !a.deleted && inRange(a.cfi, start.cfi, end?.cfi));
  }
  function inRange(cfi, a, b) {
    try {
      const cmp = new EpubCFI();
      return cmp.compare(cfi, a) >= 0 && (!b || cmp.compare(cfi, b) <= 0);
    } catch { return cfi === a; }
  }
  function updateBookmarkButton() {
    const on = bookmarksOnPage().length > 0;
    bmBtn.setAttribute('aria-pressed', String(on));
    bmBtn.setAttribute('aria-label', on ? 'Remove bookmark from this page' : 'Bookmark this page');
    bmBtn.innerHTML = String(icon(on ? 'bookmarkFill' : 'bookmark'));
  }
  async function toggleBookmark() {
    const here = bookmarksOnPage();
    if (here.length) {
      for (const b of here) await store.deleteAnnotation(b.uid);
      annotations = annotations.filter((a) => !here.includes(a));
      toast('Bookmark removed.');
    } else if (lastLoc) {
      const excerpt = await pageExcerpt();
      const a = await store.addAnnotation(key, { kind: 'bookmark', cfi: lastLoc.start.cfi, text: excerpt, chapter: chapterLabel(lastLoc), percent: pct() });
      annotations.push(a);
      toast('Page bookmarked.');
    }
    updateBookmarkButton();
  }
  async function pageExcerpt() {
    try {
      const r = await book.getRange(lastLoc.start.cfi);
      return (r?.startContainer?.textContent || '').trim().slice(0, 160);
    } catch { return ''; }
  }
  const pct = () => (locationsReady && lastLoc ? book.locations.percentageFromCfi(lastLoc.start.cfi) : null);

  // ---------- Page turning with motion ----------
  let turning = false;
  async function turn(dir) {
    if (turning || !rendition) return;
    turning = true;
    hideSelection(true);
    const mode = motionMode();
    const outCls = mode === 'slide' ? `anim-out-${dir}` : 'fade-out';
    const inCls = mode === 'slide' ? `anim-in-${dir}` : 'fade-in';
    try {
      if (mode !== 'none') {
        viewer.classList.add(outCls);
        await wait(150);
        // epub.js measures the page geometry right after turning; keep the
        // view untransformed (just invisible) while it does.
        viewer.style.opacity = '0';
        viewer.classList.remove(outCls);
      }
      await (dir === 'next' ? rendition.next() : rendition.prev());
      await frames(3);
    } catch (err) { console.warn(err); }
    viewer.classList.remove(outCls);
    viewer.style.opacity = '';
    if (mode !== 'none') { viewer.classList.add(inCls); setTimeout(() => viewer.classList.remove(inCls), 260); }
    turning = false;
  }
  function motionMode() {
    const app = store.getSetting('motion', 'system');
    if (app === 'off' || prefersReducedMotion() && app !== 'on') return 'none';
    return prefs.motion;
  }

  // ---------- Taps, keys, chrome ----------
  function onContentTap(e, contents) {
    if (contents.window.getSelection()?.toString()) return;
    if (!sel.hidden) { hideSelection(true); return; }
    const frame = contents.document.defaultView.frameElement.getBoundingClientRect();
    handleTapAt(e.clientX + frame.left);
  }
  // Taps on the page margins (outside the book frame) count too.
  viewer.addEventListener('click', (e) => {
    if (e.target.tagName === 'IFRAME' || !rendition) return;
    if (!sel.hidden) { hideSelection(true); return; }
    handleTapAt(e.clientX);
  });
  function handleTapAt(x) {
    const w = stage.getBoundingClientRect();
    const rel = (x - w.left) / w.width;
    const coarse = matchMedia('(pointer: coarse)').matches;
    if (coarse && rel < 0.25) turn('prev');
    else if (coarse && rel > 0.75) turn('next');
    else if (immersive) peekChrome();
  }

  function onKey(e) {
    if (document.querySelector('.overlay.open')) return;
    if (e.target.closest?.('input, textarea, select')) return;
    if (e.key === ' ' && e.target.closest?.('button, a')) return;
    if (['ArrowRight', 'PageDown'].includes(e.key) || (e.key === ' ' && !e.shiftKey)) { e.preventDefault(); turn('next'); }
    else if (['ArrowLeft', 'PageUp'].includes(e.key) || (e.key === ' ' && e.shiftKey)) { e.preventDefault(); turn('prev'); }
    else if (e.key === 'Escape' && immersive) { e.preventDefault(); setImmersive(false); }
  }
  document.addEventListener('keydown', onKey);
  cleanups.push(() => document.removeEventListener('keydown', onKey));

  let peekTimer;
  function peekChrome() {
    root.querySelectorAll('.reader-bar').forEach((b) => b.classList.add('peek'));
    clearTimeout(peekTimer);
    peekTimer = setTimeout(() => root.querySelectorAll('.reader-bar').forEach((b) => b.classList.remove('peek')), 3500);
  }

  async function setImmersive(on) {
    immersive = on;
    readerEl.classList.toggle('immersive', on);
    root.querySelector('.exit-immersive').hidden = !on;
    const btn = root.querySelector('.reader-bar [data-act="immersive"]');
    btn.setAttribute('aria-label', on ? 'Exit focus mode' : 'Focus mode (hide controls)');
    btn.innerHTML = String(icon(on ? 'collapse' : 'expand'));
    if (on) {
      try { if (document.fullscreenEnabled && !document.fullscreenElement) await readerEl.requestFullscreen({ navigationUI: 'hide' }); } catch { /* optional */ }
      root.querySelector('.exit-immersive').focus({ preventScroll: true });
    } else {
      try { if (document.fullscreenElement) await document.exitFullscreen(); } catch { /* ignore */ }
      btn.focus({ preventScroll: true });
    }
    setTimeout(() => rendition?.resize(), 320);
  }
  const onFs = () => { if (!document.fullscreenElement && immersive) setImmersive(false); };
  document.addEventListener('fullscreenchange', onFs);
  cleanups.push(() => document.removeEventListener('fullscreenchange', onFs));

  function wireBasicControls() {
    root.addEventListener('click', (e) => { if (e.target.closest('[data-act="close"]')) close(); });
  }

  root.addEventListener('click', (e) => {
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (!act) return;
    if (act === 'close') close();
    if (act === 'next') turn('next');
    if (act === 'prev') turn('prev');
    if (act === 'toc') openContents();
    if (act === 'bookmark') toggleBookmark();
    if (act === 'display') openDisplay();
    if (act === 'tts') toggleTts();
    if (act === 'lookup') openDictionary('');
    if (act === 'club') openClub();
    if (act === 'ask') askMavis();
    if (act === 'companion') openReadingCompanion();
    if (act === 'immersive') setImmersive(!immersive);
  });

  // ---------- Styles ----------
  function applyStyles() {
    readerEl.dataset.rtheme = prefs.theme;
    const t = THEMES[prefs.theme] || THEMES.light;
    viewer.style.paddingInline = MARGINS[prefs.margin] || MARGINS.normal;
    const rules = {
      // Matching color-scheme keeps the book frame transparent over the reader.
      html: { 'color-scheme': prefs.theme === 'dark' ? 'dark' : 'light' },
      'html, body': { background: 'transparent !important', color: `${t.fg} !important` },
      body: { 'font-size': `${prefs.size}% !important` },
      'p, li, blockquote, dd, dt, div, span': { 'line-height': `${prefs.lineHeight} !important`, color: 'inherit !important' },
      'h1, h2, h3, h4, h5, h6': { color: 'inherit !important' },
      'a, a:visited': { color: `${t.link} !important` },
    };
    const stack = READER_FONTS[prefs.font]?.stack;
    if (stack) rules['body, p, div, span, li, blockquote, dd, dt, td, h1, h2, h3, h4, h5, h6'] = { 'font-family': `${stack} !important` };
    if (prefs.justify) rules.p = { 'text-align': 'justify !important', '-webkit-hyphens': 'auto', hyphens: 'auto' };
    if (prefs.theme === 'dark') rules['img'] = { filter: 'brightness(.9)' };
    rendition.themes.register('mavis', rules);
    rendition.themes.select('mavis');
    for (const a of annotations) drawAnnotation(a);
  }
  function updatePrefs(patch) {
    prefs = { ...prefs, ...patch };
    saveReaderPrefs(prefs);
    const cfi = lastLoc?.start?.cfi;
    applyStyles();
    if ('margin' in patch || 'spread' in patch) {
      if ('spread' in patch) rendition.spread(prefs.spread === 'none' ? 'none' : 'auto', 900);
      rendition.resize();
    }
    // Keep the reader on the same passage after reflow.
    if (cfi && ('size' in patch || 'font' in patch || 'lineHeight' in patch || 'margin' in patch || 'spread' in patch)) {
      setTimeout(() => displayCfi(cfi), 60);
    }
  }

  // ---------- Panels ----------
  function openContents() {
    const marks = annotations.filter((a) => a.kind === 'bookmark' && !a.deleted).sort(byPos);
    const notes = annotations.filter((a) => a.kind !== 'bookmark' && !a.deleted).sort(byPos);
    const currentHref = lastLoc?.start?.href?.split('#')[0];
    const d = openDialog({
      title: 'Contents', variant: 'side', container: readerEl,
      body: html`
        <div class="seg" role="tablist" aria-label="Panel">
          <button type="button" role="tab" aria-pressed="true" data-tab="toc">Chapters</button>
          <button type="button" role="tab" aria-pressed="false" data-tab="marks">Bookmarks <span class="num">${marks.length}</span></button>
          <button type="button" role="tab" aria-pressed="false" data-tab="notes">Notes <span class="num">${notes.length}</span></button>
        </div>
        <div data-pane="toc">${toc.length
          ? html`<ol class="toc-list">${toc.map((t) => html`<li><button type="button" class="lvl${Math.min(t.depth, 2)}" data-href="${t.href}" ${t.href.split('#')[0] === currentHref ? html`aria-current="true"` : ''}>${t.label}</button></li>`)}</ol>`
          : html`<p class="muted">This book doesn’t include a table of contents.</p>`}</div>
        <div data-pane="marks" hidden>${marks.length ? marks.map((a) => annItem(a)) : html`<p class="muted">No bookmarks yet. Tap the ribbon at the top to bookmark a page.</p>`}</div>
        <div data-pane="notes" hidden>${notes.length ? notes.map((a) => annItem(a)) : html`<p class="muted">No highlights or notes yet. Select text in the book to highlight it, add a note, or look up a word.</p>`}</div>`,
    });
    d.body.addEventListener('click', async (e) => {
      const tab = e.target.closest('[data-tab]');
      if (tab) {
        d.body.querySelectorAll('[data-tab]').forEach((t) => t.setAttribute('aria-pressed', String(t === tab)));
        d.body.querySelectorAll('[data-pane]').forEach((p) => { p.hidden = p.dataset.pane !== tab.dataset.tab; });
        return;
      }
      const go = e.target.closest('[data-href], [data-go]');
      if (go) {
        d.close();
        try { if (go.dataset.go) await displayCfi(go.dataset.go); else await rendition.display(go.dataset.href); } catch { toast('That part of the book couldn’t be opened.', { tone: 'error' }); }
        return;
      }
      const del = e.target.closest('[data-del]');
      if (del) {
        const a = annotations.find((x) => x.uid === del.dataset.del);
        if (!a) return;
        const ok = await confirmDialog({ title: `Delete this ${a.kind}?`, message: a.text ? `“${a.text.slice(0, 120)}${a.text.length > 120 ? '…' : ''}”` : 'This can’t be undone.', confirmLabel: 'Delete' });
        if (!ok) return;
        await store.deleteAnnotation(a.uid);
        if (a.kind !== 'bookmark') { try { rendition.annotations.remove(a.cfi, 'highlight'); } catch { /* ignore */ } }
        annotations = annotations.filter((x) => x !== a);
        del.closest('.ann-item').remove();
        updateBookmarkButton();
        toast('Deleted.');
      }
      const ed = e.target.closest('[data-edit]');
      if (ed) { const a = annotations.find((x) => x.uid === ed.dataset.edit); d.close(); if (a) editAnnotation(a); }
    });
  }
  function annItem(a) {
    return html`<div class="ann-item">
      ${a.text ? html`<div class="ann-text" style="--sw:${a.kind === 'bookmark' ? 'var(--accent)' : HL[a.color] || HL.sun}">${a.text}</div>` : ''}
      ${a.note ? html`<div class="ann-note">${a.note}</div>` : ''}
      <div class="ann-meta"><span>${a.chapter || ''}${a.percent != null ? ` · ${Math.round(a.percent * 100)}%` : ''}</span>
        <span class="ann-actions">
          <button type="button" class="btn btn-sm btn-quiet" data-go="${a.cfi}">Go</button>
          ${a.kind !== 'bookmark' ? html`<button type="button" class="icon-btn" data-edit="${a.uid}" aria-label="Edit">${icon('note', { size: 18 })}</button>` : ''}
          <button type="button" class="icon-btn" data-del="${a.uid}" aria-label="Delete">${icon('trash', { size: 18 })}</button>
        </span></div>
    </div>`;
  }
  function byPos(a, b) { return (a.percent ?? 0) - (b.percent ?? 0) || (a.createdAt || 0) - (b.createdAt || 0); }

  function openDisplay() {
    const reduced = prefersReducedMotion();
    const d = openDialog({
      title: 'Display', variant: 'side', container: readerEl,
      body: html`
        <div class="opt-group"><span class="label">Theme</span><div class="swatches">
          ${Object.entries(THEMES).map(([k, t]) => html`<button type="button" class="swatch" data-theme="${k}" aria-pressed="${prefs.theme === k}" style="background:${t.bg};color:${t.fg}">Aa<span class="visually-hidden"> ${t.label}</span></button>`)}
        </div></div>
        <div class="opt-group"><label class="label" for="r-font">Typeface</label>
          <select id="r-font" class="select">${Object.entries(READER_FONTS).map(([k, f]) => html`<option value="${k}" ${prefs.font === k ? 'selected' : ''}>${f.label}</option>`)}</select></div>
        <div class="opt-group"><span class="label" id="size-l">Text size</span>
          <div class="stepper" role="group" aria-labelledby="size-l"><button type="button" class="btn" data-size="-10" aria-label="Smaller text">A−</button><output id="size-o" class="num">${prefs.size}%</output><button type="button" class="btn" data-size="10" aria-label="Larger text">A+</button></div></div>
        <div class="opt-group"><span class="label">Line spacing</span><div class="seg" role="group" aria-label="Line spacing">
          ${[[1.35, 'Tight'], [1.6, 'Normal'], [1.85, 'Relaxed'], [2.1, 'Loose']].map(([v, l]) => html`<button type="button" data-lh="${v}" aria-pressed="${prefs.lineHeight === v}">${l}</button>`)}</div></div>
        <div class="opt-group"><span class="label">Margins</span><div class="seg" role="group" aria-label="Margins">
          ${Object.keys(MARGINS).map((m) => html`<button type="button" data-margin="${m}" aria-pressed="${prefs.margin === m}">${m[0].toUpperCase() + m.slice(1)}</button>`)}</div></div>
        <div class="opt-group"><span class="label">Alignment</span><div class="seg" role="group" aria-label="Alignment">
          <button type="button" data-justify="0" aria-pressed="${!prefs.justify}">Left</button><button type="button" data-justify="1" aria-pressed="${prefs.justify}">Justified</button></div></div>
        <div class="opt-group"><span class="label">Pages on wide screens</span><div class="seg" role="group" aria-label="Pages on wide screens">
          <button type="button" data-spread="auto" aria-pressed="${prefs.spread !== 'none'}">Two pages</button><button type="button" data-spread="none" aria-pressed="${prefs.spread === 'none'}">One page</button></div></div>
        <div class="opt-group"><span class="label">Page turn</span><div class="seg" role="group" aria-label="Page turn animation">
          ${[['slide', 'Slide'], ['fade', 'Fade'], ['none', 'None']].map(([v, l]) => html`<button type="button" data-motion="${v}" aria-pressed="${prefs.motion === v}">${l}</button>`)}</div>
          ${reduced ? html`<p class="hint">Your device asks for reduced motion, so pages turn without animation.</p>` : ''}</div>
        <p class="hint">These settings are saved on this device.</p>`,
    });
    d.body.addEventListener('click', (e) => {
      const b = e.target.closest('button');
      if (!b) return;
      const press = (attr) => d.body.querySelectorAll(`[${attr}]`).forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      if (b.dataset.theme) { press('data-theme'); updatePrefs({ theme: b.dataset.theme }); }
      if (b.dataset.size) {
        const size = Math.max(70, Math.min(220, prefs.size + Number(b.dataset.size)));
        d.body.querySelector('#size-o').textContent = `${size}%`;
        updatePrefs({ size });
      }
      if (b.dataset.lh) { press('data-lh'); updatePrefs({ lineHeight: Number(b.dataset.lh) }); }
      if (b.dataset.margin) { press('data-margin'); updatePrefs({ margin: b.dataset.margin }); }
      if (b.dataset.justify) { press('data-justify'); updatePrefs({ justify: b.dataset.justify === '1' }); }
      if (b.dataset.spread) { press('data-spread'); updatePrefs({ spread: b.dataset.spread }); }
      if (b.dataset.motion) { press('data-motion'); updatePrefs({ motion: b.dataset.motion }); }
    });
    d.body.querySelector('#r-font').addEventListener('change', (e) => updatePrefs({ font: e.target.value }));
  }

  // ---------- Dictionary (with voice lookup) ----------
  function openDictionary(term, found = {}) {
    const d = openDialog({
      title: 'Dictionary', variant: 'sheet', container: readerEl,
      body: html`<form class="searchbar" style="box-shadow:none" id="dict-form" role="search">
          <label class="visually-hidden" for="dict-q">Word to look up</label>
          <input id="dict-q" type="search" value="${term.split(/\s+/).slice(0, 3).join(' ')}" placeholder="Type a word" autocomplete="off" ${term ? '' : 'autofocus'} />
          <button type="button" class="icon-btn" data-mic aria-label="Say a word">${icon('mic')}</button>
          <button type="submit" class="icon-btn go" aria-label="Look up">${icon('search')}</button>
        </form>
        <div id="dict-out" aria-live="polite"></div>
        <p class="hint">English definitions from the Free Dictionary API (Wiktionary data). Only the word you look up is sent.</p>`,
    });
    const out = d.body.querySelector('#dict-out');
    const input = d.body.querySelector('#dict-q');
    let ctl = null;
    async function look(word) {
      ctl?.abort(); ctl = new AbortController();
      out.innerHTML = String(html`<p class="muted">Looking up “${word}”…</p>`);
      try {
        const r = await define(word, { signal: ctl.signal });
        if (r.error) {
          out.innerHTML = String(html`<p class="muted">${r.error}</p>${r.retry ? html`<button class="btn btn-sm" type="button" data-retry>Try again</button>` : ''}`);
          out.querySelector('[data-retry]')?.addEventListener('click', () => look(word));
          return;
        }
        const saved = await vocab.getWord(r.entries[0]?.word || word).catch(() => null);
        out.innerHTML = String(html`${r.entries.map((en, ei) => html`<div class="dict-entry">
          <div class="word-top"><div><span class="dict-word">${en.word}</span> <span class="faint">${en.phonetic}</span></div>
            ${ttsSupported ? html`<button type="button" class="btn btn-sm btn-quiet" data-say="${en.word}">${icon('headphones', { size: 16 })} Hear it</button>` : ''}</div>
          ${en.meanings.map((m, mi) => html`<div><div class="dict-pos">${m.partOfSpeech}</div><ol class="dict-def">${m.definitions.map((df, di) => html`<li>${df.definition}${df.example ? html`<span class="ex">“${df.example}”</span>` : ''}
              <button type="button" class="dict-save" data-vsave="${ei}.${mi}.${di}" aria-label="Save “${en.word}” (${m.partOfSpeech}) to Word builder with this meaning">${saved && saved.word === vocab.wordKey(en.word) && saved.definition === df.definition ? html`${icon('check', { size: 14 })} Saved` : html`${icon('plus', { size: 14 })} Save word`}</button></li>`)}</ol>
            ${m.synonyms.length ? html`<p class="small faint" style="margin-top:4px">Similar: ${m.synonyms.join(', ')}</p>` : ''}</div>`)}
        </div>`)}
        ${found.sentence ? html`<p class="small faint">Saved words keep this sentence: “${found.sentence}”</p>` : ''}`);
        out.onclick = async (e) => {
          const say = e.target.closest('[data-say]');
          if (say) { speakWord(say.dataset.say, { lang }); return; }
          const sv = e.target.closest('[data-vsave]');
          if (!sv) return;
          const [ei, mi, di] = sv.dataset.vsave.split('.').map(Number);
          const en = r.entries[ei]; const m = en.meanings[mi]; const df = m.definitions[di];
          try {
            await vocab.saveWord({ word: en.word, form: normalizeTerm(word), definition: df.definition, partOfSpeech: m.partOfSpeech, phonetic: en.phonetic, sentence: found.sentence || '', bookKey: key, bookTitle: item.title, cfi: found.cfi || '' });
            for (const b of out.querySelectorAll('[data-vsave]')) b.innerHTML = String(html`${icon('plus', { size: 14 })} Save word`);
            sv.innerHTML = String(html`${icon('check', { size: 14 })} Saved`);
            toast(`“${en.word}” is in your Word builder.`, { action: { label: 'Practice', run: () => { location.hash = '#/words'; } } });
          } catch (err) { toast(err.message, { tone: 'error' }); }
        };
      } catch (err) { if (err.name !== 'AbortError') out.innerHTML = String(html`<p class="muted">The dictionary couldn’t be reached.</p>`); }
    }
    d.body.querySelector('#dict-form').addEventListener('submit', (e) => { e.preventDefault(); if (input.value.trim()) look(input.value.trim()); });
    d.body.querySelector('[data-mic]').addEventListener('click', async () => {
      const said = await listen({ purpose: 'Say a word to look up', lang: lang.length === 2 ? undefined : lang });
      if (said) { input.value = said; look(said); } else input.focus();
    });
    if (term) look(term);
  }

  // ---------- Read aloud ----------
  // Two engines: the device voice reads the visible page and turns pages
  // (ReadAloud); the cloud voice reads the chapter as audio (Narrator), which
  // keeps playing with the screen off and responds to car/Bluetooth buttons.
  let voices = [];
  let narr = null;
  let carUI = null;
  const rateNow = () => Number(store.getSetting('ttsRate', 1)) || 1;
  const engineNow = () => (store.getSetting('voiceEngine', 'device') === 'cloud' && can('cloudVoice') ? 'cloud' : 'device');

  function toggleTts() {
    const show = ttsBar.hidden;
    ttsBar.hidden = !show;
    root.querySelector('[data-act="tts"]').setAttribute('aria-pressed', String(show));
    if (show) { paintTtsBar(); setTimeout(() => rendition.resize(), 50); }
    else { tts?.stop(); narr?.stop(); setTimeout(() => rendition.resize(), 50); }
  }

  async function paintTtsBar() {
    ttsBar.innerHTML = String(html`<p class="tts-sentence" id="tts-line">Loading voices…</p>`);
    await loadFeatures();
    voices = ttsSupported ? await whenVoicesReady() : [];
    const base = lang.slice(0, 2).toLowerCase();
    const matching = voices.filter((v) => v.lang?.toLowerCase().startsWith(base));
    const list = matching.length ? matching : voices;
    const saved = store.getSetting('ttsVoice', null);
    const chosen = list.find((v) => v.voiceURI === saved) || list.find((v) => v.default && v.localService) || list.find((v) => v.localService) || list[0];
    const rate = rateNow();
    const online = list.some((v) => !v.localService);
    const cloudOk = can('cloudVoice');
    const engine = engineNow();
    const f = features();
    ttsBar.innerHTML = String(html`
      <p class="tts-sentence" id="tts-line" aria-live="off">${engine === 'cloud' ? 'Cloud voice reads this chapter from your page onward, even with the screen off.' : voices.length ? 'Reads from the top of this page and turns pages for you.' : 'No voices are installed on this device. Add one in your device settings, or use the cloud voice.'}</p>
      <div class="tts-main">
        <button type="button" class="icon-btn" data-tts="back" aria-label="${engine === 'cloud' ? 'Back 15 seconds' : 'Previous sentence'}">${icon('skipB')}</button>
        <button type="button" class="icon-btn play" data-tts="play" aria-label="Start reading aloud" ${engine === 'device' && !voices.length ? 'disabled' : ''}>${icon('play', { size: 26 })}</button>
        <button type="button" class="icon-btn" data-tts="fwd" aria-label="${engine === 'cloud' ? 'Forward 15 seconds' : 'Next sentence'}">${icon('skipF')}</button>
        <button type="button" class="icon-btn" data-tts="stop" aria-label="Stop reading aloud">${icon('stop')}</button>
        <button type="button" class="btn btn-sm" data-tts="car">${icon('car', { size: 18 })} Car mode</button>
      </div>
      <div class="tts-opts">
        <label>Voice
          <select id="tts-engine" aria-label="Voice type">
            <option value="device" ${engine === 'device' ? 'selected' : ''}>This device</option>
            <option value="cloud" ${engine === 'cloud' ? 'selected' : ''} ${cloudOk ? '' : 'disabled'}>Cloud voice${cloudOk ? ` (${f.cloudVoice === 'fish' ? 'Fish Audio' : 'Google'})` : ' (not set up)'}</option>
          </select></label>
        <label ${engine === 'cloud' ? 'hidden' : ''} data-device-voice>Device voice <select id="tts-voice">${list.map((v) => html`<option value="${v.voiceURI}" ${v === chosen ? 'selected' : ''}>${v.name}${v.localService ? '' : ' (online)'}</option>`)}</select></label>
        <label>Speed <select id="tts-rate">${[0.75, 0.9, 1, 1.15, 1.3, 1.5, 1.75, 2].map((r) => html`<option value="${r}" ${Number(rate) === r ? 'selected' : ''}>${r}×</option>`)}</select></label>
        <label>${icon('timer', { size: 18 })}<span class="visually-hidden">Sleep timer</span> <select id="tts-sleep" aria-label="Sleep timer"><option value="0">No timer</option><option value="5">5 min</option><option value="15">15 min</option><option value="30">30 min</option><option value="60">60 min</option><option value="chapter">End of chapter</option></select></label>
        ${engine === 'cloud' && f.cloudVoice === 'fish' ? html`<label class="tts-cast"><input type="checkbox" id="tts-fullcast" ${store.getSetting('fullCast', false) ? 'checked' : ''} /> Full cast</label>
        <button type="button" class="btn btn-sm btn-quiet" data-tts="cast">${icon('user', { size: 16 })} Cast…</button>` : ''}
      </div>
      <p class="small" id="tts-status" role="status" style="text-align:center;color:var(--r-faint)">${engine === 'cloud' ? 'The cloud voice sends the text being read to the voice service and uses the site owner’s voice credit.' : online ? 'Voices marked “online” send the text being read to the voice provider.' : cloudOk ? '' : 'Tip: the device voice stops when your phone locks. For screen-off listening, the site owner can turn on a cloud voice.'}</p>`);
    const voiceSel = ttsBar.querySelector('#tts-voice');
    const rateSel = ttsBar.querySelector('#tts-rate');
    const voiceFn = () => voices.find((v) => v.voiceURI === voiceSel.value) || null;
    const rateFn = () => Number(rateSel.value) || 1;
    if (!tts && ttsSupported) {
      tts = new ReadAloud({ rendition, voice: voiceFn, rate: rateFn, lang, title: item.title, onState: onTtsState });
    } else if (tts) { tts.voice = voiceFn; tts.rate = rateFn; }
    ttsBar.querySelector('#tts-fullcast')?.addEventListener('change', (e) => {
      store.setSetting('fullCast', e.target.checked);
      const wasPlaying = narr && narr.state !== 'idle' && narr.state !== 'error';
      narr?.stop();
      toast(e.target.checked ? 'Full cast on: each character gets their own voice. Mavis works out who’s speaking as it reads.' : 'Full cast off: one narrator voice.');
      if (wasPlaying) playToggle();
    });
    ttsBar.querySelector('#tts-engine').addEventListener('change', (e) => {
      tts?.stop(); narr?.stop();
      store.setSetting('voiceEngine', e.target.value);
      paintTtsBar();
    });
    voiceSel.addEventListener('change', () => { store.setSetting('ttsVoice', voiceSel.value); tts?.restartSentence(); });
    rateSel.addEventListener('change', () => { store.setSetting('ttsRate', rateFn()); tts?.restartSentence(); });
    ttsBar.querySelector('#tts-sleep').addEventListener('change', (e) => {
      const v = e.target.value;
      if (engineNow() === 'cloud') narr?.setSleep(v === 'chapter' ? 0 : Number(v)), sleepChapter = v === 'chapter';
      else tts?.setSleep(v === 'chapter' ? 'chapter' : Number(v));
      toast(v === '0' ? 'Sleep timer off.' : v === 'chapter' ? 'Reading will stop at the end of this chapter.' : `Reading will stop in ${v} minutes.`);
    });
    ttsBar.onclick = (e) => {
      const a = e.target.closest('[data-tts]')?.dataset.tts;
      if (!a) return;
      if (a === 'play') playToggle();
      if (a === 'stop') { tts?.stop(); narr?.stop(); }
      if (a === 'fwd') (engineNow() === 'cloud' ? narr : tts)?.skip(1);
      if (a === 'back') (engineNow() === 'cloud' ? narr : tts)?.skip(-1);
      if (a === 'car') openCar();
      if (a === 'cast') openCastEditor();
    };
  }

  let sleepChapter = false;
  function playToggle() {
    if (engineNow() === 'cloud') {
      if (narr && narr.state !== 'idle' && narr.state !== 'error') { narr.toggle(); return; }
      tts?.stop();
      narr?.destroy();
      narr = new Narrator({ source: chapterSource(), engine: 'cloud', rate: rateNow, title: item.title, onState: onCloudState });
      narr.start();
      return;
    }
    if (!tts) { toast('This browser can’t read aloud with a device voice.', { tone: 'error' }); return; }
    narr?.stop();
    if (tts.state === 'playing' || tts.state === 'loading') tts.pause();
    else if (tts.state === 'paused') tts.resume();
    else tts.start().catch((err) => toast(err.message, { tone: 'error' }));
  }

  // Reads the book section by section from the current position, as text,
  // without needing the page to be on screen.
  function chapterSource() {
    let sIdx = lastLoc?.start?.index ?? 0;
    let startCfi = lastLoc?.start?.cfi || null;
    let served = 0;
    const BLOCKS = 'p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, dd, dt, figcaption';
    return {
      label: () => chapterNowPlaying || item.title,
      async next() {
        while (sIdx < book.spine.length) {
          const section = book.spine.get(sIdx);
          if (!section || section.linear === false) { sIdx++; startCfi = null; continue; }
          if (sleepChapter && served >= 1) { sleepChapter = false; return null; }
          await section.load(book.load.bind(book));
          const doc = section.document;
          let blocks = [...doc.querySelectorAll(BLOCKS)].filter((el) => !el.querySelector(BLOCKS) && el.textContent.trim());
          if (startCfi) {
            try {
              const r = new EpubCFI(startCfi).toRange(doc);
              const node = r?.startContainer;
              const el = (node?.nodeType === 1 ? node : node?.parentElement)?.closest?.(BLOCKS);
              const at = blocks.indexOf(el);
              if (at > 0) blocks = blocks.slice(at);
            } catch { /* start at the beginning of the section */ }
            startCfi = null;
          }
          const idx = sIdx;
          sIdx++;
          served++;
          const label = toc.find((t) => book.spine.get(t.href.split('#')[0])?.index === idx)?.label?.trim();
          const items = [];
          const withCfi = blocks.map((el) => { let cfi = null; try { cfi = section.cfiFromElement(el); } catch { /* ignore */ } return { el, cfi, text: el.textContent }; });
          let cast = null;
          if (store.getSetting('fullCast', false) && features().cloudVoice === 'fish') {
            try {
              const { castSection } = await import('../lib/fullcast.js');
              const ttsLine = ttsBar.querySelector('#tts-line');
              if (ttsLine) ttsLine.textContent = 'Working out who’s speaking in this chapter…';
              cast = await castSection({ key, title: item.title, author: (item.authors || []).join(', '), sectionIndex: idx, blocks: withCfi.map((b) => ({ text: b.text, cfi: b.cfi })) });
            } catch (err) { toast(`Full cast unavailable for this chapter: ${err.message}`, { tone: 'error' }); }
          }
          withCfi.forEach((b, bi) => {
            const segs = cast ? cast.blocks[bi].segments : [{ kind: 'narration', text: b.text }];
            for (const seg of segs) {
              const voice = seg.kind === 'quote' && seg.speaker && seg.speaker !== 'Narrator' ? cast?.cast[seg.speaker]?.id : undefined;
              for (const sentence of sentences(seg.text, lang)) {
                if (!/[\p{L}\p{N}]/u.test(sentence)) continue;
                items.push({ text: sentence, voice, speaker: seg.speaker, onStart: () => followAlong(b.cfi, label) });
              }
            }
          });
          if (items.length) return items;
        }
        return null;
      },
    };
  }

  const followAlong = debounce((cfi, label) => {
    if (label) chapterNowPlaying = label;
    if (!cfi) return;
    store.setProgress(key, { cfi, percent: locationsReady ? book.locations.percentageFromCfi(cfi) : null, chapter: label || '' }).catch(() => {});
    if (document.visibilityState !== 'visible' || carUI) return;
    try {
      const cmp = new EpubCFI();
      const loc = rendition.location;
      if (!loc?.end?.cfi || cmp.compare(cfi, loc.end.cfi) > 0 || cmp.compare(cfi, loc.start.cfi) < 0) displayCfi(cfi);
    } catch { /* ignore */ }
  }, 400);
  let chapterNowPlaying = '';

  function onCloudState(s) {
    const play = ttsBar.querySelector('[data-tts="play"]');
    const line = ttsBar.querySelector('#tts-line');
    const status = ttsBar.querySelector('#tts-status');
    carUI?.update({ ...s, label: chapterNowPlaying || s.label });
    if (!play) return;
    const playing = s.state === 'playing' || s.state === 'loading';
    play.innerHTML = String(icon(playing ? 'pause' : 'play', { size: 26 }));
    play.setAttribute('aria-label', playing ? 'Pause reading aloud' : s.state === 'paused' ? 'Resume reading aloud' : 'Start reading aloud');
    if (s.text) line.textContent = s.text;
    if (s.state === 'idle') line.textContent = s.reason === 'end' ? 'Reached the end of the book.' : s.reason === 'sleep' ? 'Sleep timer finished. Reading stopped.' : 'Stopped. Press play to read from this page.';
    status.textContent = s.error || (s.state === 'loading' ? 'Preparing audio…' : s.state === 'paused' ? 'Paused.' : s.state === 'playing' ? `Cloud voice · ${chapterNowPlaying || 'reading'}` : '');
  }

  async function openCar() {
    // A saved audiobook plays offline, with the screen off and chapter skipping.
    const saved = await audiobookManifest(key).catch(() => null);
    if (saved && audiobookSummary(saved).done) {
      tts?.stop(); narr?.stop();
      location.hash = `#/listen/${encodeURIComponent(key)}?play=1`;
      return;
    }
    carUI?.close();
    const cloud = engineNow() === 'cloud';
    carUI = openCarMode({
      title: item.title, subtitle: chapterLabel(lastLoc), cloud, container: readerEl,
      onToggle: playToggle,
      onBack: () => (cloud ? narr : tts)?.skip(-1),
      onForward: () => (cloud ? narr : tts)?.skip(1),
      onSleep: (m) => (cloud ? narr?.setSleep(m) : tts?.setSleep(m)),
      onExit: () => { carUI = null; },
    });
    const p = cloud ? narr : tts;
    if (p) carUI.update({ state: p.state, label: chapterLabel(lastLoc) });
    if (!p || p.state === 'idle') playToggle();
  }

  function onTtsState(s) {
    carUI?.update({ state: s.state, text: s.sentence, label: chapterLabel(lastLoc), error: s.error, sleepAt: s.sleepAt });
    const play = ttsBar.querySelector('[data-tts="play"]');
    const line = ttsBar.querySelector('#tts-line');
    const status = ttsBar.querySelector('#tts-status');
    if (!play) return;
    const playing = s.state === 'playing' || s.state === 'loading';
    play.innerHTML = String(icon(playing ? 'pause' : 'play', { size: 26 }));
    play.setAttribute('aria-label', playing ? 'Pause reading aloud' : s.state === 'paused' ? 'Resume reading aloud' : 'Start reading aloud');
    if (s.sentence && (s.state === 'playing' || s.state === 'paused')) line.textContent = s.sentence;
    if (s.state === 'idle') line.textContent = s.reason === 'end' ? 'Reached the end of the book.' : s.reason === 'sleep' ? 'Sleep timer finished. Reading stopped.' : 'Stopped. Press play to read from the top of this page.';
    if (s.error) status.textContent = s.error;
    else if (s.state === 'paused') status.textContent = 'Paused. Resuming repeats the current sentence.';
    else if (s.state === 'playing') status.textContent = s.sleepAt ? `Sentence ${s.index + 1} of ${s.total} on this page · stops at ${new Date(s.sleepAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : s.sleepChapter != null ? `Sentence ${s.index + 1} of ${s.total} on this page · stops at chapter end` : `Sentence ${s.index + 1} of ${s.total} on this page`;
    else if (s.state === 'idle' && s.reason !== 'user') status.textContent = '';
  }

  // ---------- Ask Mavis ----------
  function citeHere() {
    const ch = chapterLabel(lastLoc);
    return `${item.title}${item.authors?.[0] ? `, ${item.authors[0]}` : ''}${ch ? ` (${ch})` : ''}`;
  }
  async function chapterText() {
    try {
      const section = book.spine.get(lastLoc.start.index);
      await section.load(book.load.bind(book));
      return (section.document.body?.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24000);
    } catch { return ''; }
  }
  // ---------- Full-cast editor ----------
  async function openCastEditor() {
    const [{ getCast, setCast }, { castingPool, narratorVoice }] = await Promise.all([import('../lib/fullcast.js'), import('../lib/voices.js')]);
    const cast = await getCast(key);
    const names = Object.keys(cast);
    const d = openDialog({ title: 'Cast', variant: 'side', container: readerEl, body: html`<p class="muted">Loading voices…</p>` });
    let pool;
    try { pool = await castingPool(); } catch (err) { d.body.innerHTML = String(html`<p class="muted">${err.message}</p>`); return; }
    const all = [...pool.male, ...pool.female];
    const audio = new Audio();
    d.body.innerHTML = String(html`
      <p class="small muted">Narrator: <strong>${narratorVoice()?.title || 'the site’s default voice'}</strong> (change it in Settings → Voices).</p>
      ${names.length ? html`<ul class="cast-list">${names.map((n) => html`<li>
        <span class="cast-name">${n}</span>
        <select class="select" data-cast="${n}" aria-label="Voice for ${n}">${all.map((v) => html`<option value="${v.id}" ${cast[n].id === v.id ? 'selected' : ''}>${v.title} · ${v.tags?.includes('female') ? 'female' : 'male'}</option>`)}</select>
        <button type="button" class="icon-btn" data-play="${n}" aria-label="Hear the voice for ${n}">${icon('play', { size: 18 })}</button></li>`)}</ul>`
        : html`<p class="muted">No characters yet. Turn on <strong>Full cast</strong> and press play — Mavis finds the speakers as it reads.</p>`}
      <p class="small faint">Voices are from Fish Audio’s licensed voice library. Who-says-what is worked out by the AI from the text around each line, so the occasional line may go to the wrong voice.</p>`);
    d.body.addEventListener('change', async (e) => {
      const sel = e.target.closest('[data-cast]');
      if (!sel) return;
      const v = all.find((x) => x.id === sel.value);
      cast[sel.dataset.cast] = { ...cast[sel.dataset.cast], id: v.id, title: v.title, sample: v.sample };
      await setCast(key, cast);
      toast(`${sel.dataset.cast} will be read by ${v.title}.`);
    });
    d.body.addEventListener('click', (e) => {
      const n = e.target.closest('[data-play]')?.dataset.play;
      if (!n) return;
      const v = all.find((x) => x.id === cast[n].id);
      if (v?.sample) { audio.src = v.sample; audio.play().catch(() => toast('Couldn’t play the sample.')); }
    });
  }

  // ---------- Reading companion ----------
  function pageText() {
    try {
      const loc = rendition.location || lastLoc;
      const c = rendition.getContents().find((x) => x.sectionIndex === loc.start.index);
      if (!c) return '';
      const a = c.range(loc.start.cfi);
      const r = c.document.createRange();
      r.setStart(a.startContainer, a.startOffset);
      if (loc.end && loc.end.index === loc.start.index) { const b = c.range(loc.end.cfi); r.setEnd(b.endContainer, b.endOffset); }
      else r.setEndAfter(c.document.body.lastChild || c.document.body);
      return r.toString().replace(/\s+/g, ' ').trim().slice(0, 3500);
    } catch { return ''; }
  }
  function textSoFar() {
    try {
      const loc = rendition.location || lastLoc;
      const c = rendition.getContents().find((x) => x.sectionIndex === loc.start.index);
      if (!c) return '';
      const r = c.document.createRange();
      r.setStart(c.document.body, 0);
      const b = c.range((loc.end && loc.end.index === loc.start.index ? loc.end : loc.start).cfi);
      r.setEnd(b.endContainer, b.endOffset);
      return r.toString().replace(/\s+/g, ' ').trim().slice(-12000);
    } catch { return ''; }
  }
  async function previousSections() {
    const out = [];
    const upto = (rendition.location || lastLoc)?.start?.index ?? 0;
    for (let i = 0; i < upto && i < book.spine.length; i++) {
      const section = book.spine.get(i);
      if (!section || section.linear === false) continue;
      try {
        await section.load(book.load.bind(book));
        const text = (section.document.body?.textContent || '').replace(/\s+/g, ' ').trim();
        if (text.length < 400) continue; // title pages, contents, epigraphs
        const label = toc.find((t) => book.spine.get(t.href.split('#')[0])?.index === i)?.label?.trim() || `Part ${out.length + 1}`;
        out.push({ index: i, label, text: text.slice(0, 30000) });
      } catch { /* skip unreadable sections */ }
    }
    return out;
  }
  async function openReadingCompanion(tab) {
    const sel = pendingSel?.text || '';
    hideSelection(true);
    const { openCompanion } = await import('../lib/companion.js');
    openCompanion({
      key, title: item.title, author: (item.authors || []).join(', '), container: readerEl,
      chapter: () => chapterLabel(rendition.location || lastLoc), pageText, selectionText: () => sel, textSoFar, previousSections,
    }, { tab });
  }

  async function openClub() {
    const selection = pendingSel?.text || '';
    const selCfi = pendingSel?.cfiRange || '';
    hideSelection(true);
    const { openBookClubSheet } = await import('../lib/groups-ui.js');
    openBookClubSheet({
      groups: groupsForBook(key), title: item.title, author: (item.authors || []).join(', '), container: readerEl,
      chapter: () => chapterLabel(rendition.location || lastLoc), textSoFar, percent: pct, selection: () => selection,
      cite: citeHere, ref: () => ({ kind: 'book', key, cfi: selCfi || (rendition.location || lastLoc)?.start?.cfi || '' }),
    });
  }
  // Show the Book club button once the group list is known (it's cached between visits).
  myGroups().then(() => { const b = root.querySelector('[data-act="club"]'); if (b) b.hidden = !groupsForBook(key).length; }).catch(() => {});

  async function askMavis() {
    const selection = pendingSel?.text || '';
    hideSelection(true);
    const text = await chapterText();
    await openAssistant({
      container: readerEl,
      getContext: () => ({ title: item.title, author: (item.authors || []).join(', '), chapter: chapterLabel(lastLoc), text, selection }),
      actions: {
        read_aloud: async ({ from }) => {
          if (from === 'chapter_start') {
            const t = toc.find((x) => x.label.trim() === chapterLabel(lastLoc));
            if (t) await rendition.display(t.href);
          }
          if (ttsBar.hidden) toggleTts();
          setTimeout(playToggle, 400);
        },
        stop_reading: () => { tts?.stop(); narr?.stop(); },
        go_to: async ({ target }) => {
          const t = String(target || '').toLowerCase();
          const n = Number(t.replace(/\D+/g, ''));
          const hit = toc.find((x) => x.label.toLowerCase().includes(t)) || (n ? toc.filter((x) => x.depth === 0)[n - 1] : null);
          if (hit) await rendition.display(hit.href); else toast(`I couldn’t find “${target}” in this book.`);
        },
        car_mode: () => openCar(),
        define_word: ({ word }) => openDictionary(word || ''),
      },
    });
  }

  // ---------- Helpers ----------
  function chapterLabel(loc) {
    if (!loc?.start) return '';
    const href = (loc.start.href || '').split('#')[0];
    let best = '';
    for (const t of toc) if (t.href.split('#')[0] === href) { best = t.label; break; }
    if (!best) {
      const idx = loc.start.index;
      for (const t of toc) {
        const s = book.spine.get(t.href.split('#')[0]);
        if (s && s.index <= idx) best = t.label;
      }
    }
    return best.trim().slice(0, 120);
  }
  function confirmExternal(href) {
    let host = href;
    try { host = new URL(href).host || href; } catch { /* keep raw */ }
    const d = openDialog({
      title: 'Leave the book?', container: readerEl,
      body: html`<p class="dialog-text">This link goes to <strong>${host}</strong>, a website outside Mavis Library. It will open in a new tab.</p>
        <p class="small faint" style="overflow-wrap:anywhere">${href}</p>
        <div class="dialog-actions"><button type="button" class="btn btn-quiet" data-close>Stay here</button><a class="btn btn-primary" href="${/^https?:/i.test(href) ? href : '#'}" target="_blank" rel="noopener noreferrer" data-open>Open link</a></div>`,
    });
    d.body.querySelector('[data-open]').addEventListener('click', () => d.close());
  }

  return async () => {
    destroyed = true;
    if (lastLoc && saveProgress) saveProgress.flush(lastLoc);
    cleanups.forEach((f) => f());
    try { tts?.destroy(); } catch { /* ignore */ }
    try { narr?.destroy(); carUI?.close(); } catch { /* ignore */ }
    try { if (document.fullscreenElement) await document.exitFullscreen(); } catch { /* ignore */ }
    try { rendition?.destroy(); } catch { /* ignore */ }
    try { book?.destroy(); } catch { /* ignore */ }
  };
}

function flattenToc(items, depth = 0, out = []) {
  for (const it of items) {
    out.push({ label: (it.label || '').trim() || 'Untitled section', href: it.href, depth });
    if (it.subitems?.length) flattenToc(it.subitems, depth + 1, out);
  }
  return out;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const frames = (n) => new Promise((r) => { const step = () => (n-- <= 0 ? r() : requestAnimationFrame(step)); step(); });
export { esc, $ };
