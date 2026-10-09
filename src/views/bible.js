// The Holy Bible: KJV with Strong's numbers and the World English Bible,
// with verse lookup, concordance search, lexicon, cross-references,
// highlights, notes, saved quotes, read-aloud, car mode, church display mode,
// and Ask Mavis.

import { html, icon, toast, openDialog, debounce, esc, raw } from '../lib/ui.js';
import * as store from '../lib/store.js';
import * as B from '../lib/bible.js';
import { copyQuote, shareQuote } from '../lib/quotes.js';
import { Narrator } from '../lib/speech.js';
import { whenVoicesReady, ttsSupported } from '../lib/tts.js';
import { openCarMode } from '../lib/carmode.js';
import { openAssistant } from '../lib/assistant-ui.js';
import { loadFeatures, can } from '../lib/features.js';
import { listen } from '../lib/voice-input.js';
import { stateBlock } from '../components.js';

const KEY = 'bible';
const HL = { sun: '#e9c46a', mint: '#86c7a1', sky: '#8fb8e6', rose: '#e7a1a8' };
const TR_LABEL = { kjv: 'KJV', web: 'WEB', both: 'Both' };

export const title = (route) => (route.segs[0] === 'search' ? 'Search the Bible' : 'Bible');

export async function ensureOnShelf() {
  const item = await store.getShelfItem(KEY).catch(() => null);
  if (!item) {
    await store.saveToShelf({
      key: KEY, source: 'bible', sourceId: 'kjv-web', title: 'Holy Bible', authors: ['King James Version · World English Bible'],
      languages: ['en'], subjects: ['Bible', 'Christianity', 'Scripture'], format: null, coverUrl: null,
    }, { status: 'reading' });
  }
}

export async function render(root, route, { navigate, token }) {
  let idx;
  try { idx = await B.loadIndex(); }
  catch (err) {
    root.innerHTML = String(html`<div class="page">${stateBlock({ tone: 'error', title: 'The Bible couldn’t be opened', text: `${err.message} If you’re offline, open the Bible once while online so it’s saved on this device.` })}</div>`);
    return;
  }
  await ensureOnShelf().catch(() => {});
  // Save the whole Bible for offline use the first time it's opened.
  if (!store.getSetting('bibleCached', false) && 'caches' in window) {
    setTimeout(() => B.prefetchAll().then((ok) => { if (ok) store.setSetting('bibleCached', true); }), 1500);
  }
  if (route.segs[0] === 'search') return renderSearch(root, route, { idx, navigate, token });
  return renderChapter(root, route, { idx, navigate, token });
}

// ===================================================================== chapter

async function renderChapter(root, route, { idx, navigate, token }) {
  const prog = await store.getProgress(KEY).catch(() => null);
  let ref = null;
  if (route.segs[0]) {
    const b = idx.byId.get(route.segs[0]);
    if (b) ref = { book: b.id, chapter: Math.min(b.verses.length, Math.max(1, Number(route.segs[1]) || 1)) };
  }
  if (!ref && prog?.cfi) ref = B.parseOsis(prog.cfi);
  if (!ref || !idx.byId.get(ref.book)) ref = { book: 'John', chapter: 1 };
  const focusVerse = Number(route.params.get('v')) || null;
  const focusEnd = Number(route.params.get('ve')) || focusVerse;
  let tr = ['kjv', 'web', 'both'].includes(route.params.get('t')) ? route.params.get('t') : store.getSetting('bibleTr', 'kjv');
  let strongsOn = store.getSetting('bibleStrongs', false);
  const book = idx.byId.get(ref.book);
  const chLabel = `${book.name} ${ref.chapter}`;
  const go = (b, c, extra = '') => navigate(`/bible/${encodeURIComponent(b)}/${c}${extra}`);

  root.innerHTML = String(html`
    <div class="page bible">
      <div class="bible-bar">
        <button type="button" class="btn bible-pick" data-act="pick" aria-haspopup="dialog">${icon('book', { size: 20 })} <span>${chLabel}</span> ${icon('chevronR', { size: 16 })}</button>
        <div class="seg" role="group" aria-label="Translation">
          ${['kjv', 'web', 'both'].map((t) => html`<button type="button" data-tr="${t}" aria-pressed="${tr === t}">${TR_LABEL[t]}</button>`)}
        </div>
        <span class="bible-tools">
        <button type="button" class="icon-btn" data-act="strongs" aria-pressed="${strongsOn}" aria-label="Show Strong’s numbers (tap a word for its Hebrew or Greek)" title="Strong’s concordance">${icon('dict')}</button>
        <a class="icon-btn" href="#/bible/search" aria-label="Search the Bible">${icon('search')}</a>
        <button type="button" class="icon-btn" data-act="listen" aria-label="Listen to this chapter">${icon('headphones')}</button>
        <button type="button" class="icon-btn" data-act="ask" aria-label="Ask Mavis about this chapter">${icon('spark')}</button>
        </span>
      </div>
      <form class="searchbar bible-lookup" role="search" id="lookup">
        ${icon('search')}
        <label class="visually-hidden" for="lookup-q">Go to a verse or search words</label>
        <input id="lookup-q" type="search" placeholder="John 3:16, Psalm 23, or words" autocomplete="off" enterkeyhint="go" />
        <button type="button" class="icon-btn" data-act="mic" aria-label="Say a verse or words">${icon('mic')}</button>
        <button type="submit" class="icon-btn go" aria-label="Go">${icon('chevronR')}</button>
      </form>
      <article class="chapter" aria-labelledby="ch-title">
        <h1 id="ch-title" class="bible-title"><span class="bk">${book.name}</span> <span class="cn">${ref.chapter}</span></h1>
        <div class="verses ${strongsOn ? 'strongs-on' : ''}" id="verses" lang="en" data-ref="${ref.book}.${ref.chapter}"><div class="skeleton line"></div><div class="skeleton line"></div><div class="skeleton line short"></div></div>
      </article>
      <nav class="chapter-nav" aria-label="Chapters">
        <button type="button" class="btn" data-act="prev">${icon('chevronL', { size: 18 })} Previous</button>
        <button type="button" class="btn" data-act="next">Next ${icon('chevronR', { size: 18 })}</button>
      </nav>
      <p class="credits small faint">${idx.translations.kjv.license} WEB: ${idx.translations.web.license} ${idx.credits.join(' ')}</p>
    </div>
    <div class="verse-bar" id="verse-bar" role="toolbar" aria-label="Selected verses" hidden></div>
    <section class="listen-bar" id="listen-bar" aria-label="Listening" hidden></section>`);

  const versesEl = root.querySelector('#verses');
  const bar = root.querySelector('#verse-bar');
  const listenBar = root.querySelector('#listen-bar');
  let annotations = [];
  let kjv, web;
  let selected = new Set();
  let narrator = null;
  let carUI = null;

  // ---------- render verses ----------
  async function paint() {
    try {
      [kjv, web] = await Promise.all([
        tr !== 'web' ? B.loadBook('kjv', ref.book) : null,
        tr !== 'kjv' ? B.loadBook('web', ref.book) : null,
      ]);
    } catch (err) {
      versesEl.innerHTML = String(stateBlock({ tone: 'error', title: 'This chapter couldn’t load', text: err.message, actions: html`<button class="btn btn-sm" type="button" data-retry>Try again</button>` }));
      versesEl.querySelector('[data-retry]').onclick = paint;
      return;
    }
    if (!token()) return;
    annotations = await store.listAnnotations(KEY).catch(() => []);
    const marks = chapterMarks();
    const kv = kjv?.chapters[ref.chapter - 1] || [];
    const wv = web?.chapters[ref.chapter - 1] || [];
    const n = Math.max(kv.length, wv.length);
    const out = [];
    for (let v = 1; v <= n; v++) {
      const m = marks.get(v) || {};
      const cls = ['v', m.color ? `hl hl-${m.color}` : '', selected.has(v) ? 'sel' : '', v >= (focusVerse || 0) && v <= (focusEnd || 0) ? 'focus' : ''].filter(Boolean).join(' ');
      const kText = kv[v - 1] != null ? (strongsOn ? strongsHtml(kv[v - 1]) : esc(B.plain(kv[v - 1]))) : '';
      const wText = wv[v - 1] != null ? esc(wv[v - 1]) : '';
      out.push(`<p class="${cls}" id="v${v}" data-v="${v}" tabindex="0" ${m.color ? `style="--hl:${HL[m.color]}"` : ''}><sup class="vn" aria-hidden="true">${v}</sup><span class="visually-hidden">Verse ${v}. </span>${
        tr === 'both' ? `<span class="pair"><span class="tr-k"><b class="trl">KJV</b> ${kText}</span><span class="tr-w"><b class="trl">WEB</b> ${wText}</span></span>` : (tr === 'web' ? wText : kText)
      }${m.quote ? `<span class="v-ico" title="Saved quote">${icon('starFill', { size: 14 })}</span>` : ''}${m.note ? `<button type="button" class="v-ico v-note" data-note="${m.note.uid}" aria-label="Note on verse ${v}">${icon('note', { size: 14 })}</button>` : ''}</p>`);
    }
    versesEl.innerHTML = out.join('');
    if (focusVerse) {
      const el = versesEl.querySelector(`#v${focusVerse}`);
      el?.scrollIntoView({ block: 'center' });
      setTimeout(() => versesEl.querySelectorAll('.focus').forEach((x) => x.classList.remove('focus')), 2600);
    }
  }

  function strongsHtml(raw) {
    return B.tokens(raw).map((t) => {
      const lead = t.text.startsWith(' ') ? ' ' : '';
      const word = esc(t.text.trimStart());
      return t.strongs ? `${lead}<span class="w" data-s="${t.strongs.join(',')}" role="button" tabindex="0">${word}</span>` : `${lead}${word}`;
    }).join('');
  }

  function chapterMarks() {
    const map = new Map();
    for (const a of annotations) {
      const r = B.parseOsis(a.cfi);
      if (!r || r.book !== ref.book || r.chapter !== ref.chapter || !r.verse) continue;
      for (let v = r.verse; v <= (r.verseEnd || r.verse); v++) {
        const m = map.get(v) || {};
        if (a.kind === 'highlight' || (a.kind === 'note' && a.color)) m.color = a.color || 'sun';
        if (a.kind === 'note') m.note = a;
        if (a.kind === 'quote') m.quote = a;
        if (a.kind === 'highlight') m.hl = a;
        map.set(v, m);
      }
    }
    return map;
  }

  const verseText = (v, t = tr === 'web' ? 'web' : 'kjv') => {
    const src = t === 'web' ? web || null : kjv;
    if (!src) return '';
    const raw = src.chapters[ref.chapter - 1]?.[v - 1] || '';
    return t === 'kjv' ? B.plain(raw) : raw;
  };
  const selRange = () => {
    const vs = [...selected].sort((a, b) => a - b);
    return { book: ref.book, chapter: ref.chapter, verse: vs[0], verseEnd: vs[vs.length - 1] };
  };
  const selText = () => [...selected].sort((a, b) => a - b).map((v) => verseText(v)).join(' ');
  const trShort = () => (tr === 'web' ? 'WEB' : 'KJV');
  const citation = (r) => `${B.labelSync(idx, r)} (${trShort()})`;

  // ---------- selection bar ----------
  function paintBar() {
    if (!selected.size) { bar.hidden = true; return; }
    const r = selRange();
    const single = selected.size === 1;
    const marks = chapterMarks();
    const anyQuote = [...selected].some((v) => marks.get(v)?.quote);
    bar.innerHTML = String(html`
      <div class="vb-head"><strong>${B.labelSync(idx, r)}</strong><button type="button" class="icon-btn" data-vb="clear" aria-label="Clear selection">${icon('close', { size: 18 })}</button></div>
      <div class="vb-row">
        ${Object.keys(HL).map((c) => html`<button type="button" class="icon-btn" data-hl="${c}" aria-label="Highlight ${c}"><span class="dot" style="background:${HL[c]}"></span></button>`)}
        <button type="button" class="icon-btn" data-vb="unhl" aria-label="Remove highlight">${icon('close', { size: 18 })}</button>
        <span class="sep" aria-hidden="true"></span>
        <button type="button" class="vb-btn" data-vb="quote" aria-pressed="${anyQuote}">${icon(anyQuote ? 'starFill' : 'star', { size: 20 })}<span>${anyQuote ? 'Saved' : 'Save quote'}</span></button>
        <button type="button" class="vb-btn" data-vb="note">${icon('note', { size: 20 })}<span>Note</span></button>
        <button type="button" class="vb-btn" data-vb="copy">${icon('copy', { size: 20 })}<span>Copy</span></button>
        <button type="button" class="vb-btn" data-vb="share">${icon('share', { size: 20 })}<span>Share</span></button>
        ${single ? html`<button type="button" class="vb-btn" data-vb="xref">${icon('link', { size: 20 })}<span>Cross-refs</span></button>` : ''}
        <button type="button" class="vb-btn" data-vb="compare">${icon('compare', { size: 20 })}<span>Compare</span></button>
        <button type="button" class="vb-btn" data-vb="listen">${icon('headphones', { size: 20 })}<span>Listen</span></button>
        <button type="button" class="vb-btn" data-vb="present">${icon('present', { size: 20 })}<span>Display</span></button>
        <button type="button" class="vb-btn" data-vb="ask">${icon('spark', { size: 20 })}<span>Ask</span></button>
      </div>`);
    bar.hidden = false;
  }

  function toggleVerse(v, additive) {
    if (!additive && selected.size && !selected.has(v)) {
      // Extend a contiguous run if adjacent; otherwise start over.
      const vs = [...selected].sort((a, b) => a - b);
      if (v === vs[0] - 1 || v === vs[vs.length - 1] + 1) selected.add(v);
      else selected = new Set([v]);
    } else if (selected.has(v)) selected.delete(v);
    else selected.add(v);
    versesEl.querySelectorAll('.v').forEach((el) => el.classList.toggle('sel', selected.has(Number(el.dataset.v))));
    paintBar();
  }

  async function setHighlight(color) {
    for (const v of selected) {
      const existing = annotations.filter((a) => a.kind === 'highlight' && a.cfi === `${ref.book}.${ref.chapter}.${v}`);
      for (const a of existing) await store.deleteAnnotation(a.uid);
      if (color) await store.addAnnotation(KEY, { kind: 'highlight', cfi: `${ref.book}.${ref.chapter}.${v}`, text: verseText(v), color, chapter: chLabel });
    }
    toast(color ? 'Highlighted.' : 'Highlight removed.');
    selected.clear(); paintBar(); await paint();
  }

  async function toggleQuote() {
    const marks = chapterMarks();
    const existing = [...new Set([...selected].map((v) => marks.get(v)?.quote).filter(Boolean))];
    if (existing.length) {
      for (const q of existing) await store.deleteAnnotation(q.uid);
      toast('Removed from saved quotes.');
    } else {
      const r = selRange();
      await store.addAnnotation(KEY, { kind: 'quote', cfi: B.osis(r), text: selText(), color: 'sun', chapter: citation(r) });
      toast('Saved to your quotes.', { action: { label: 'View', run: () => navigate('/quotes') } });
    }
    selected.clear(); paintBar(); await paint();
  }

  function editNote(existing) {
    const r = existing ? B.parseOsis(existing.cfi) : selRange();
    const d = openDialog({
      title: `Note · ${B.labelSync(idx, r)}`, variant: 'sheet',
      body: html`<blockquote class="ann-text" style="margin:0">${existing ? existing.text : selText()}</blockquote>
        <div class="field"><label for="bnote">Your note</label><textarea id="bnote" class="input" rows="5" style="padding:10px 12px" maxlength="5000" autofocus>${existing?.note || ''}</textarea></div>
        <div class="dialog-actions">${existing ? html`<button type="button" class="btn btn-quiet" data-del>${icon('trash', { size: 18 })} Delete</button>` : ''}<button type="button" class="btn btn-primary" data-save>Save note</button></div>`,
    });
    d.body.addEventListener('click', async (e) => {
      if (e.target.closest('[data-save]')) {
        const note = d.body.querySelector('#bnote').value.trim();
        if (existing) await store.updateAnnotation(existing.uid, { note });
        else if (note) await store.addAnnotation(KEY, { kind: 'note', cfi: B.osis(r), text: selText(), note, chapter: citation(r) });
        d.close(); toast('Note saved.'); selected.clear(); paintBar(); paint();
      }
      if (e.target.closest('[data-del]')) { await store.deleteAnnotation(existing.uid); d.close(); toast('Note deleted.'); paint(); }
    });
  }

  async function showXrefs(v) {
    const d = openDialog({ title: `Cross-references · ${book.name} ${ref.chapter}:${v}`, variant: 'side', body: html`<p class="muted">Loading…</p>` });
    try {
      const map = await B.loadXrefs(ref.book);
      const list = map[`${ref.chapter}:${v}`] || [];
      if (!list.length) { d.body.innerHTML = String(html`<p class="muted">No cross-references for this verse.</p>`); return; }
      const items = [];
      for (const o of list) {
        const r = B.parseOsis(o);
        if (!r) continue;
        const passage = await B.passageText(tr === 'web' ? 'web' : 'kjv', r.toChapter ? { ...r, verseEnd: null } : r).catch(() => []);
        items.push({ r, text: passage.map((p) => p.text).join(' ').slice(0, 320) });
      }
      d.body.innerHTML = String(html`<p class="hint">Ranked by OpenBible.info readers.</p>${items.map(({ r, text }) => html`
        <a class="xref" href="#/bible/${encodeURIComponent(r.book)}/${r.chapter}?v=${r.verse || 1}${r.verseEnd && !r.toChapter ? `&ve=${r.verseEnd}` : ''}"><strong>${B.labelSync(idx, r)}</strong><span>${text}</span></a>`)}`);
      d.body.addEventListener('click', (e) => { if (e.target.closest('a')) d.close(); });
    } catch (err) { d.body.innerHTML = String(html`<p class="muted">${err.message}</p>`); }
  }

  async function compare() {
    const r = selRange();
    const [k, w] = await Promise.all([B.passageText('kjv', r), B.passageText('web', r)]);
    openDialog({
      title: `Compare · ${B.labelSync(idx, r)}`, variant: 'sheet',
      body: html`<div class="compare">
        <section><h3>King James Version</h3>${k.map((p) => html`<p><sup>${p.v}</sup> ${p.text}</p>`)}</section>
        <section><h3>World English Bible</h3>${w.map((p) => html`<p><sup>${p.v}</sup> ${p.text}</p>`)}</section></div>`,
    });
  }

  async function showStrongs(nums) {
    // Show the content word before the Greek article (G3588) when both are tagged.
    const list = nums.split(',').sort((a, b) => (a === 'G3588') - (b === 'G3588'));
    const d = openDialog({ title: 'Strong’s concordance', variant: 'sheet', body: html`<p class="muted">Loading…</p>` });
    try {
      const entries = (await Promise.all(list.map((n) => B.strongsEntry(n)))).filter(Boolean);
      if (!entries.length) { d.body.innerHTML = String(html`<p class="muted">No lexicon entry for ${nums}.</p>`); return; }
      d.body.innerHTML = String(html`${entries.map((e) => html`
        <section class="lex">
          <div class="lex-head"><span class="lex-lemma" lang="${e.id[0] === 'H' ? 'he' : 'el'}">${e.lemma}</span><span class="lex-id">${e.id}</span></div>
          <p><em>${e.translit}</em> · <strong>${e.gloss}</strong></p>
          <p class="lex-def">${e.definition}</p>
          <button type="button" class="btn btn-sm" data-occ="${e.id}">${icon('search', { size: 16 })} Every verse with ${e.id}</button>
        </section>`)}
        <p class="hint">Lexicon: STEPBible.org (Tyndale House, Cambridge), CC BY 4.0.</p>`);
      d.body.addEventListener('click', (e) => {
        const occ = e.target.closest('[data-occ]')?.dataset.occ;
        if (occ) { d.close(); navigate(`/bible/search?q=${occ}`); }
      });
    } catch (err) { d.body.innerHTML = String(html`<p class="muted">${err.message}</p>`); }
  }

  function present() {
    const vs = [...selected].sort((a, b) => a - b);
    let i = 0;
    const el = document.createElement('div');
    el.className = 'present';
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-label', 'Display mode');
    const draw = () => {
      const v = vs[i];
      el.innerHTML = String(html`<button type="button" class="present-exit" data-x>${icon('close', { size: 22 })} Exit</button>
        <div class="present-body"><p class="present-text">${verseText(v)}</p><p class="present-ref">${book.name} ${ref.chapter}:${v} · ${trShort()}</p></div>
        <div class="present-nav"><button type="button" data-p="-1" aria-label="Previous verse" ${i === 0 && vs[0] <= 1 ? 'disabled' : ''}>${icon('chevronL', { size: 30 })}</button><button type="button" data-p="1" aria-label="Next verse">${icon('chevronR', { size: 30 })}</button></div>`);
    };
    const n = (kjv || web).chapters[ref.chapter - 1].length;
    const step = (d) => {
      if (i + d >= 0 && i + d < vs.length) i += d;
      else { const nv = vs[i] + d; if (nv >= 1 && nv <= n) { vs.splice(0, vs.length, nv); i = 0; } }
      draw();
    };
    const close = () => { document.removeEventListener('keydown', onKey, true); try { if (document.fullscreenElement) document.exitFullscreen(); } catch { /* ignore */ } el.remove(); };
    const onKey = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); close(); }
      if (['ArrowRight', 'ArrowDown', 'PageDown', ' '].includes(e.key)) { e.preventDefault(); step(1); }
      if (['ArrowLeft', 'ArrowUp', 'PageUp'].includes(e.key)) { e.preventDefault(); step(-1); }
    };
    el.addEventListener('click', (e) => {
      if (e.target.closest('[data-x]')) close();
      const p = e.target.closest('[data-p]')?.dataset.p;
      if (p) step(Number(p));
    });
    document.addEventListener('keydown', onKey, true);
    draw();
    document.body.appendChild(el);
    try { el.requestFullscreen?.().catch(() => {}); } catch { /* optional */ }
    el.querySelector('[data-p="1"]').focus();
  }

  // ---------- listening ----------
  async function startListening(fromVerse = 1, { car = false } = {}) {
    await loadFeatures();
    const engine = store.getSetting('voiceEngine', 'device') === 'cloud' && can('cloudVoice') ? 'cloud' : 'device';
    if (engine === 'device' && !ttsSupported) { toast('This browser can’t read aloud. Turn on the cloud voice in Settings.', { tone: 'error' }); return; }
    const voices = engine === 'device' ? await whenVoicesReady() : [];
    const savedVoice = store.getSetting('ttsVoice', null);
    const voice = () => voices.find((v) => v.voiceURI === savedVoice) || voices.find((v) => v.lang?.startsWith('en') && v.localService) || null;
    const rate = () => Number(store.getSetting('ttsRate', 1)) || 1;
    let cur = { ...ref };
    let nextVerse = fromVerse;
    let firstBatch = true;
    const source = {
      label: () => `${idx.byId.get(cur.book).name} ${cur.chapter}`,
      async next() {
        const bk = idx.byId.get(cur.book);
        let data = await B.loadBook(tr === 'web' ? 'web' : 'kjv', cur.book);
        let ch = data.chapters[cur.chapter - 1];
        if (!firstBatch && nextVerse > ch.length) {
          // Move on to the next chapter (and book).
          if (cur.chapter < bk.verses.length) cur = { book: cur.book, chapter: cur.chapter + 1 };
          else {
            const nb = idx.books[bk.order + 1];
            if (!nb) return null;
            cur = { book: nb.id, chapter: 1 };
          }
          nextVerse = 1;
          data = await B.loadBook(tr === 'web' ? 'web' : 'kjv', cur.book);
          ch = data.chapters[cur.chapter - 1];
          if (document.visibilityState === 'visible' && !document.querySelector('.carmode')) go(cur.book, cur.chapter, '?listen=1');
          store.setProgress(KEY, { cfi: `${cur.book}.${cur.chapter}`, percent: progressOf(cur), chapter: `${idx.byId.get(cur.book).name} ${cur.chapter}` }).catch(() => {});
        }
        firstBatch = false;
        const here = { ...cur };
        const items = [];
        const heading = nextVerse === 1 ? [{ text: `${idx.byId.get(here.book).name}, chapter ${here.chapter}.` }] : [];
        for (let v = nextVerse; v <= ch.length; v++) {
          const text = tr === 'web' ? ch[v - 1] : B.plain(ch[v - 1]);
          items.push({
            text,
            onStart: () => {
              if (document.getElementById('verses')?.dataset.ref !== `${here.book}.${here.chapter}`) return;
              const el = document.getElementById(`v${v}`);
              el?.classList.add('speaking');
              if (el && document.visibilityState === 'visible') el.scrollIntoView({ block: 'center', behavior: 'smooth' });
            },
            onEnd: () => document.getElementById(`v${v}`)?.classList.remove('speaking'),
          });
        }
        nextVerse = ch.length + 1;
        return [...heading, ...items];
      },
    };
    narrator?.destroy();
    narrator = new Narrator({ source, engine, voice, rate, title: 'Holy Bible', onState });
    window.__mavisNarrator = narrator;
    paintListenBar();
    if (car) openCar();
    narrator.start();
  }

  function onState(s) {
    const play = listenBar.querySelector('[data-l="toggle"]');
    if (play) {
      const playing = s.state === 'playing' || s.state === 'loading';
      play.innerHTML = String(icon(playing ? 'pause' : 'play', { size: 24 }));
      play.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    }
    const st = listenBar.querySelector('[data-l-status]');
    if (st) st.textContent = s.error || (s.state === 'idle' ? (s.reason === 'end' ? 'Finished.' : 'Stopped.') : s.label);
    carUI?.update(s);
  }

  function paintListenBar() {
    listenBar.innerHTML = String(html`
      <div class="lb-main">
        <button type="button" class="icon-btn" data-l="back" aria-label="Back">${icon('skipB')}</button>
        <button type="button" class="icon-btn lb-play" data-l="toggle" aria-label="Pause">${icon('pause', { size: 24 })}</button>
        <button type="button" class="icon-btn" data-l="fwd" aria-label="Forward">${icon('skipF')}</button>
        <span class="lb-status" data-l-status role="status">Starting…</span>
        <button type="button" class="btn btn-sm" data-l="car">${icon('car', { size: 18 })} Car mode</button>
        <button type="button" class="icon-btn" data-l="stop" aria-label="Stop listening">${icon('stop')}</button>
      </div>`);
    listenBar.hidden = false;
  }
  listenBar.addEventListener('click', (e) => {
    const a = e.target.closest('[data-l]')?.dataset.l;
    if (!a || !narrator) return;
    if (a === 'toggle') narrator.toggle();
    if (a === 'back') narrator.skip(-1);
    if (a === 'fwd') narrator.skip(1);
    if (a === 'stop') { narrator.stop(); listenBar.hidden = true; }
    if (a === 'car') openCar();
  });
  function openCar() {
    carUI?.close();
    carUI = openCarMode({
      title: 'Holy Bible', subtitle: chLabel, cloud: narrator?.engine === 'cloud',
      onToggle: () => narrator?.toggle(), onBack: () => narrator?.skip(-1), onForward: () => narrator?.skip(1),
      onSleep: (m) => narrator?.setSleep(m), onExit: () => { carUI = null; window.__mavisCar = null; },
    });
    window.__mavisCar = carUI;
    if (narrator) carUI.update({ state: narrator.state, label: narrator.source.label() });
  }

  const progressOf = (r) => {
    const b = idx.byId.get(r.book);
    return Math.min(1, (b.order + (r.chapter - 1) / b.verses.length) / idx.books.length);
  };

  // ---------- events ----------
  root.addEventListener('click', async (e) => {
    const w = e.target.closest('.strongs-on .w');
    if (w) { e.stopPropagation(); showStrongs(w.dataset.s); return; }
    const note = e.target.closest('[data-note]');
    if (note) { const a = annotations.find((x) => x.uid === note.dataset.note); if (a) editNote(a); return; }
    const vEl = e.target.closest('.v');
    if (vEl && !e.target.closest('a')) { toggleVerse(Number(vEl.dataset.v), e.shiftKey || e.metaKey || e.ctrlKey); return; }
    const trb = e.target.closest('[data-tr]');
    if (trb) {
      tr = trb.dataset.tr; store.setSetting('bibleTr', tr);
      root.querySelectorAll('[data-tr]').forEach((b) => b.setAttribute('aria-pressed', String(b === trb)));
      await paint(); return;
    }
    const hl = e.target.closest('[data-hl]');
    if (hl) { setHighlight(hl.dataset.hl); return; }
    const vb = e.target.closest('[data-vb]')?.dataset.vb;
    if (vb) {
      const r = selRange();
      if (vb === 'clear') { selected.clear(); versesEl.querySelectorAll('.sel').forEach((x) => x.classList.remove('sel')); paintBar(); }
      if (vb === 'unhl') setHighlight(null);
      if (vb === 'quote') toggleQuote();
      if (vb === 'note') editNote(null);
      if (vb === 'copy') copyQuote(selText(), citation(r));
      if (vb === 'share') shareQuote(selText(), citation(r));
      if (vb === 'xref') showXrefs(r.verse);
      if (vb === 'compare') compare();
      if (vb === 'listen') { const v = r.verse; selected.clear(); paintBar(); paint(); startListening(v); }
      if (vb === 'present') present();
      if (vb === 'ask') ask();
      return;
    }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'pick') openPicker();
    if (act === 'prev' || act === 'next') stepChapter(act === 'next' ? 1 : -1);
    if (act === 'strongs') {
      strongsOn = !strongsOn; store.setSetting('bibleStrongs', strongsOn);
      e.target.closest('[data-act]').setAttribute('aria-pressed', String(strongsOn));
      versesEl.classList.toggle('strongs-on', strongsOn);
      if (strongsOn && tr === 'web') toast('Strong’s numbers are on the KJV text. Switch to KJV to tap words.');
      await paint();
    }
    if (act === 'listen') startListening(1);
    if (act === 'ask') ask();
    if (act === 'mic') {
      const said = await listen({ purpose: 'Say a verse or words' });
      if (said) { root.querySelector('#lookup-q').value = said; lookup(said); }
    }
  });
  root.addEventListener('keydown', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('.w')) { e.preventDefault(); showStrongs(e.target.dataset.s); }
    else if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('.v')) { e.preventDefault(); toggleVerse(Number(e.target.dataset.v), true); }
  });
  root.querySelector('#lookup').addEventListener('submit', (e) => { e.preventDefault(); lookup(root.querySelector('#lookup-q').value); });

  // Swipe between chapters on touch screens.
  let sx = 0, sy = 0;
  versesEl.addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  versesEl.addEventListener('touchend', (e) => {
    const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy;
    if (Math.abs(dx) > 80 && Math.abs(dy) < 50) stepChapter(dx < 0 ? 1 : -1);
  }, { passive: true });

  async function lookup(text) {
    const t = String(text || '').trim();
    if (!t) return;
    const r = await B.parseRef(t);
    if (r) { go(r.book, r.chapter, r.verse ? `?v=${r.verse}${r.verseEnd ? `&ve=${r.verseEnd}` : ''}` : ''); return; }
    navigate(`/bible/search?q=${encodeURIComponent(t)}`);
  }

  function stepChapter(d) {
    if (d > 0) {
      if (ref.chapter < book.verses.length) go(ref.book, ref.chapter + 1);
      else { const nb = idx.books[book.order + 1]; if (nb) go(nb.id, 1); }
    } else if (ref.chapter > 1) go(ref.book, ref.chapter - 1);
    else { const pb = idx.books[book.order - 1]; if (pb) go(pb.id, pb.verses.length); }
  }

  function openPicker() {
    const d = openDialog({
      title: 'Choose a book', variant: 'side',
      body: html`
        <div class="seg" role="group" aria-label="Testament"><button type="button" data-tm="OT" aria-pressed="${book.testament === 'OT'}">Old Testament</button><button type="button" data-tm="NT" aria-pressed="${book.testament === 'NT'}">New Testament</button></div>
        <div class="book-grid" id="bgrid"></div>
        <div id="cgrid" hidden></div>`,
    });
    const bgrid = d.body.querySelector('#bgrid');
    const cgrid = d.body.querySelector('#cgrid');
    const showBooks = (tm) => {
      cgrid.hidden = true; bgrid.hidden = false;
      bgrid.innerHTML = String(html`${idx.books.filter((b) => b.testament === tm).map((b) => html`<button type="button" class="book-chip ${b.id === book.id ? 'cur' : ''}" data-b="${b.id}">${b.name}</button>`)}`);
    };
    const showChapters = (id) => {
      const b = idx.byId.get(id);
      bgrid.hidden = true; cgrid.hidden = false;
      cgrid.innerHTML = String(html`<button type="button" class="btn btn-quiet btn-sm" data-back>${icon('back', { size: 16 })} All books</button>
        <h3 class="pick-title">${b.name}</h3>
        <div class="ch-grid">${b.verses.map((_, i) => html`<button type="button" class="ch-chip ${b.id === ref.book && i + 1 === ref.chapter ? 'cur' : ''}" data-c="${i + 1}" data-cb="${b.id}">${i + 1}</button>`)}</div>`);
      cgrid.querySelector('[data-c]')?.focus();
    };
    showBooks(book.testament);
    d.body.addEventListener('click', (e) => {
      const tm = e.target.closest('[data-tm]');
      if (tm) { d.body.querySelectorAll('[data-tm]').forEach((x) => x.setAttribute('aria-pressed', String(x === tm))); showBooks(tm.dataset.tm); }
      const b = e.target.closest('[data-b]');
      if (b) { const bk = idx.byId.get(b.dataset.b); if (bk.verses.length === 1) { d.close(); go(bk.id, 1); } else showChapters(b.dataset.b); }
      if (e.target.closest('[data-back]')) showBooks(idx.byId.get(cgrid.querySelector('[data-cb]').dataset.cb).testament);
      const c = e.target.closest('[data-c]');
      if (c) { d.close(); go(c.dataset.cb, Number(c.dataset.c)); }
    });
  }

  async function ask() {
    const vs = [...selected].sort((a, b) => a - b);
    await openAssistant({
      getContext: () => ({
        title: `Holy Bible (${trShort()})`, chapter: chLabel, bible: true,
        selection: vs.length ? `${B.labelSync(idx, selRange())}: ${selText()}` : '',
        text: ((tr === 'web' ? web : kjv)?.chapters[ref.chapter - 1] || []).map((raw, i) => `${i + 1} ${tr === 'web' ? raw : B.plain(raw)}`).join('\n'),
      }),
      actions: {
        read_aloud: ({ from }) => startListening(from === 'here' && vs[0] ? vs[0] : 1),
        stop_reading: () => narrator?.stop(),
        go_to: async ({ target }) => { const r = await B.parseRef(target); if (r) go(r.book, r.chapter, r.verse ? `?v=${r.verse}` : ''); else toast(`I couldn’t find “${target}”.`); },
        car_mode: () => startListening(1, { car: true }),
        define_word: ({ word }) => navigate(`/bible/search?q=${encodeURIComponent(word)}`),
      },
    });
  }

  await paint();
  store.setProgress(KEY, { cfi: `${ref.book}.${ref.chapter}`, percent: progressOf(ref), chapter: chLabel }).catch(() => {});
  if (route.params.get('listen') === '1' && window.__mavisNarrator && window.__mavisNarrator.state !== 'idle') {
    // Reading continued into this chapter; keep the player attached.
    narrator = window.__mavisNarrator;
    narrator.onState = onState;
    if (window.__mavisCar?.el?.isConnected) carUI = window.__mavisCar;
    paintListenBar();
    onState({ state: narrator.state, label: chLabel });
    return cleanup;
  }
  function cleanup() {
    // Keep reading (and car mode) if read-aloud moved on to the next chapter.
    if (location.hash.includes('listen=1') && narrator && narrator.state !== 'idle') return;
    narrator?.destroy();
    carUI?.close();
  }
  return cleanup;
}

// ====================================================================== search

async function renderSearch(root, route, { idx, navigate, token }) {
  const q = (route.params.get('q') || '').slice(0, 120);
  const scope = route.params.get('scope') || 'all';
  const tr = route.params.get('t') === 'web' ? 'web' : 'kjv';
  let limit = 200;
  root.innerHTML = String(html`
    <div class="page bible">
      <div style="padding-top:8px"><a class="btn btn-quiet btn-sm" href="#/bible">${icon('back', { size: 18 })} Back to reading</a></div>
      <h1 class="h-section" style="font-size:var(--step-3);margin:10px 0 14px">Search the Bible</h1>
      <form class="searchbar" role="search" id="bs-form">
        ${icon('search')}
        <label class="visually-hidden" for="bs-q">Words, a "phrase", a reference, or a Strong’s number</label>
        <input id="bs-q" type="search" value="${q}" placeholder="faith hope, &quot;living water&quot;, John 4:10, G26" autocomplete="off" enterkeyhint="search" />
        <button type="submit" class="icon-btn go" aria-label="Search">${icon('chevronR')}</button>
      </form>
      <div class="toolbar" style="margin:14px 0">
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <label class="visually-hidden" for="bs-scope">Where</label>
          <select class="select" id="bs-scope" style="width:auto">
            <option value="all" ${scope === 'all' ? 'selected' : ''}>Whole Bible</option>
            <option value="OT" ${scope === 'OT' ? 'selected' : ''}>Old Testament</option>
            <option value="NT" ${scope === 'NT' ? 'selected' : ''}>New Testament</option>
            ${idx.books.map((b) => html`<option value="${b.id}" ${scope === b.id ? 'selected' : ''}>${b.name}</option>`)}
          </select>
          <label class="visually-hidden" for="bs-tr">Translation</label>
          <select class="select" id="bs-tr" style="width:auto"><option value="kjv" ${tr === 'kjv' ? 'selected' : ''}>KJV</option><option value="web" ${tr === 'web' ? 'selected' : ''}>WEB</option></select>
        </div>
        <p class="small muted">Tip: quotes find an exact phrase; H or G numbers (H430, G26) find every use of a Hebrew or Greek word.</p>
      </div>
      <div id="bs-out" aria-live="polite"></div>
    </div>`);
  const form = root.querySelector('#bs-form');
  const link = (patch) => { const p = new URLSearchParams({ q, scope, t: tr, ...patch }); return `/bible/search?${p}`; };
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const t = form.querySelector('#bs-q').value.trim();
    const r = await B.parseRef(t);
    if (r) navigate(`/bible/${r.book}/${r.chapter}${r.verse ? `?v=${r.verse}${r.verseEnd ? `&ve=${r.verseEnd}` : ''}` : ''}`);
    else navigate(link({ q: t }));
  });
  root.querySelector('#bs-scope').addEventListener('change', (e) => navigate(link({ scope: e.target.value })));
  root.querySelector('#bs-tr').addEventListener('change', (e) => navigate(link({ t: e.target.value })));
  const out = root.querySelector('#bs-out');
  if (!q) { out.innerHTML = String(stateBlock({ title: 'Search every verse', text: 'Find words anywhere in Scripture, an exact phrase in quotes, or every verse that uses a Hebrew or Greek word by its Strong’s number.' })); return; }

  async function run() {
    out.innerHTML = String(html`<div class="dl-progress" role="status"><div class="dl-bar"><span id="bs-bar"></span></div><span class="small muted">Searching…</span></div>`);
    const res = await B.search(q, { tr, scope, limit, onProgress: (p) => { const b = out.querySelector('#bs-bar'); if (b) b.style.width = `${Math.round(p * 100)}%`; } });
    if (!token()) return;
    let lexHead = '';
    if (res.strongs) {
      const e = await B.strongsEntry(res.strongs).catch(() => null);
      if (e) lexHead = html`<section class="lex lex-banner"><div class="lex-head"><span class="lex-lemma" lang="${e.id[0] === 'H' ? 'he' : 'el'}">${e.lemma}</span><span class="lex-id">${e.id}</span></div><p><em>${e.translit}</em> · <strong>${e.gloss}</strong></p></section>`;
    }
    if (!res.total) { out.innerHTML = String(html`${lexHead}${stateBlock({ title: 'No verses found', text: `Nothing in ${scope === 'all' ? 'the Bible' : scope === 'OT' ? 'the Old Testament' : scope === 'NT' ? 'the New Testament' : idx.byId.get(scope)?.name} matches “${q}”. Try fewer words or another translation.` })}`); return; }
    const byBook = Object.entries(res.byBook).map(([id, n]) => ({ id, n, name: idx.byId.get(id).name }));
    out.innerHTML = String(html`${lexHead}
      <p class="result-count num">${res.total.toLocaleString()} verse${res.total === 1 ? '' : 's'}${res.strongs ? ` use ${res.strongs}` : ''} · ${res.translation.toUpperCase()}</p>
      <div class="chips" style="margin:8px 0 12px">${byBook.slice(0, 40).map((b) => html`<a class="chip" href="#${link({ scope: b.id })}">${b.name} <span class="faint num">${b.n}</span></a>`)}</div>
      <ol class="hits">${res.results.map((r) => html`<li><a href="#/bible/${encodeURIComponent(r.book)}/${r.chapter}?v=${r.verse}&t=${res.translation}"><strong>${idx.byId.get(r.book).name} ${r.chapter}:${r.verse}</strong> <span>${markText(r.text, r.marks)}</span></a></li>`)}</ol>
      ${res.total > res.results.length ? html`<div class="pager"><button type="button" class="btn" data-more>Show more (${(res.total - res.results.length).toLocaleString()} left)</button></div>` : ''}`);
    out.querySelector('[data-more]')?.addEventListener('click', () => { limit += 400; run(); });
  }
  run();
}

function markText(text, marks) {
  let out = '', pos = 0;
  for (const [s, e] of marks) {
    if (s < pos) continue;
    out += esc(text.slice(pos, s)) + `<mark>${esc(text.slice(s, e))}</mark>`;
    pos = e;
  }
  return raw(out + esc(text.slice(pos)));
}

export { debounce };
