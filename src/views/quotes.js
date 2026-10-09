// Saved quotes, highlights, and notes from every book and the Bible.
import { html, icon, toast, confirmDialog, timeAgo } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { copyQuote, shareQuote } from '../lib/quotes.js';
import { stateBlock } from '../components.js';

export const title = () => 'Saved quotes';

const TABS = [['quote', 'Quotes'], ['highlight', 'Highlights'], ['note', 'Notes']];
const SW = { sun: '#e9c46a', mint: '#86c7a1', sky: '#8fb8e6', rose: '#e7a1a8' };

export async function render(root, route) {
  let tab = TABS.some(([k]) => k === route.params.get('tab')) ? route.params.get('tab') : 'quote';
  let q = '';
  root.innerHTML = String(html`<div class="page">
    <div class="shelf-head">
      <h1>Saved quotes</h1>
      <p class="small muted">Everything you’ve saved, highlighted, or noted, from every book and the Bible. Tap a citation to jump back to it.</p>
      <div class="tabs" role="tablist" id="qtabs"></div>
      <div class="shelf-tools"><label class="visually-hidden" for="qfilter">Filter</label><input class="input" id="qfilter" type="search" placeholder="Filter by words or book" autocomplete="off" /></div>
    </div>
    <div id="qlist" style="margin-top:16px"></div></div>`);

  const shelfTitles = new Map((await store.listShelf().catch(() => [])).map((b) => [b.key, b]));
  const citeOf = (a) => {
    if (a.bookKey === 'bible') return a.chapter || a.cfi.replace(/\./g, ' ');
    const b = shelfTitles.get(a.bookKey);
    return `${b?.title || 'Untitled'}${b?.authors?.[0] ? `, ${b.authors[0]}` : ''}${a.chapter ? ` · ${a.chapter}` : ''}`;
  };
  const hrefOf = (a) => {
    if (a.bookKey === 'bible') {
      const m = /^([1-3]?[A-Za-z]+)\.(\d+)\.(\d+)(?:-[1-3]?[A-Za-z]+\.\d+\.(\d+))?/.exec(a.cfi);
      return m ? `#/bible/${m[1]}/${m[2]}?v=${m[3]}${m[4] ? `&ve=${m[4]}` : ''}` : '#/bible';
    }
    return `#/read/${encodeURIComponent(a.bookKey)}?at=${encodeURIComponent(a.cfi)}`;
  };

  async function paint() {
    const all = await store.listAllAnnotations();
    const counts = Object.fromEntries(TABS.map(([k]) => [k, all.filter((a) => (k === 'note' ? a.note : a.kind === k)).length]));
    root.querySelector('#qtabs').innerHTML = String(html`${TABS.map(([k, v]) => html`<button role="tab" type="button" aria-selected="${k === tab}" data-tab="${k}">${v} <span class="faint num">${counts[k]}</span></button>`)}`);
    let list = all.filter((a) => (tab === 'note' ? a.note : a.kind === tab));
    if (q) list = list.filter((a) => `${a.text} ${a.note} ${citeOf(a)}`.toLowerCase().includes(q));
    const el = root.querySelector('#qlist');
    if (!list.length) {
      el.innerHTML = String(stateBlock({
        title: q ? 'Nothing matches' : tab === 'quote' ? 'No saved quotes yet' : tab === 'note' ? 'No notes yet' : 'No highlights yet',
        text: q ? `Nothing here matches “${q}”.` : 'Select text in any book, or tap verses in the Bible, then choose Save quote, a highlight color, or Note.',
        actions: q ? '' : html`<a class="btn btn-sm btn-primary" href="#/bible">Open the Bible</a><a class="btn btn-sm" href="#/shelf">My shelf</a>`,
      }));
      return;
    }
    el.innerHTML = String(html`<ul class="quote-list">${list.map((a) => html`
      <li class="quote-card" style="--sw:${SW[a.color] || SW.sun}">
        ${a.text ? html`<blockquote>${a.text}</blockquote>` : ''}
        ${a.note ? html`<p class="ann-note">${a.note}</p>` : ''}
        <div class="quote-meta">
          <a href="${hrefOf(a)}">${icon(a.bookKey === 'bible' ? 'cross' : 'book', { size: 16 })} ${citeOf(a)}</a>
          <span class="faint small">${timeAgo(a.createdAt)}</span>
        </div>
        <div class="quote-actions">
          <button type="button" class="btn btn-sm btn-quiet" data-copy="${a.uid}">${icon('copy', { size: 16 })} Copy</button>
          <button type="button" class="btn btn-sm btn-quiet" data-share="${a.uid}">${icon('share', { size: 16 })} Share</button>
          ${a.text ? html`<button type="button" class="btn btn-sm btn-quiet" data-video="${a.uid}">${icon('present', { size: 16 })} Video</button>` : ''}
          <button type="button" class="icon-btn" data-del="${a.uid}" aria-label="Delete">${icon('trash', { size: 18 })}</button>
        </div>
      </li>`)}</ul>`);
    el.onclick = async (e) => {
      const id = e.target.closest('[data-copy],[data-share],[data-del],[data-video]');
      if (!id) return;
      const a = list.find((x) => x.uid === (id.dataset.copy || id.dataset.share || id.dataset.del || id.dataset.video));
      if (!a) return;
      if (id.dataset.video) {
        const { openVideoMaker } = await import('../lib/video.js');
        openVideoMaker({ text: a.text, citation: citeOf(a), bookKey: a.bookKey, title: citeOf(a), defaultStyle: a.bookKey === 'bible' ? 'stained glass' : 'painterly' });
        return;
      }
      if (id.dataset.copy) copyQuote(a.text, citeOf(a));
      if (id.dataset.share) shareQuote(a.text, citeOf(a));
      if (id.dataset.del) {
        if (!await confirmDialog({ title: 'Delete this?', message: a.text ? `“${a.text.slice(0, 140)}${a.text.length > 140 ? '…' : ''}”` : 'This can’t be undone.', confirmLabel: 'Delete' })) return;
        await store.deleteAnnotation(a.uid); toast('Deleted.'); paint();
      }
    };
  }
  root.querySelector('#qtabs').addEventListener('click', (e) => { const t = e.target.closest('[data-tab]'); if (t) { tab = t.dataset.tab; history.replaceState(null, '', `#/quotes?tab=${tab}`); paint(); } });
  root.querySelector('#qfilter').addEventListener('input', (e) => { q = e.target.value.trim().toLowerCase(); paint(); });
  const unsub = store.onChange((e) => { if (['annotations', 'remote', 'owner'].includes(e.type)) paint(); });
  await paint();
  return () => unsub();
}
