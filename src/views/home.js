import { html, icon, toast } from '../lib/ui.js';
import { searchGutenberg } from '../lib/catalog.js';
import * as store from '../lib/store.js';
import { bookCard, cover, skeletonGrid, stateBlock, sourceBadges } from '../components.js';
import { LIBRARY_FINDERS } from '../lib/links.js';
import { listen, voiceInputSupported } from '../lib/voice-input.js';
import { recommendations } from '../lib/recommend.js';
import { bibleCover } from '../components.js';
import * as B from '../lib/bible.js';
import { isKids } from '../lib/kids.js';
import * as vocab from '../lib/vocab.js';

// Well-known verses for the "verse of the day" (rotates by date).
const DAILY = ['John.3.16', 'Ps.23.1', 'Prov.3.5', 'Isa.40.31', 'Phil.4.13', 'Rom.8.28', 'Jer.29.11', 'Josh.1.9', 'Matt.11.28', 'Ps.46.10', 'Rom.12.2', '1Cor.13.4', 'Heb.11.1', 'Gal.5.22', 'Ps.119.105', 'Matt.5.16', 'Isa.41.10', 'Mic.6.8', 'Lam.3.22', 'Eph.2.8', '2Tim.1.7', 'Ps.27.1', 'John.14.6', 'Rom.5.8', '1John.4.19', 'Ps.121.1', 'Prov.16.3', 'Col.3.23', 'Matt.6.33', 'Ps.37.4', 'John.16.33'];

// Hand-picked Project Gutenberg ids. Titles and covers are always loaded live
// from the catalog, never hard-coded.
const CLASSICS = [1342, 84, 2701, 11, 1661, 64317, 345, 1260, 174, 98, 768, 1184, 158, 120, 1400, 2554];

const SHELVES = [
  { topic: 'adventure', label: 'Adventure' },
  { topic: 'detective', label: 'Mystery & detective' },
  { topic: 'science fiction', label: 'Science fiction' },
  { topic: 'gothic', label: 'Gothic & ghost stories' },
  { topic: 'poetry', label: 'Poetry' },
  { topic: 'juvenile', label: 'For young readers' },
  { topic: 'philosophy', label: 'Philosophy' },
  { topic: 'love stories', label: 'Love stories' },
];

export const title = () => 'Discover';

export async function render(root, _route, ctx) {
  if (isKids()) return (await import('./kids-home.js')).render(root, _route, ctx);
  const { navigate, token } = ctx;
  const shelf = await store.listShelf().catch(() => []);
  const progress = await store.allProgress().catch(() => new Map());
  const reading = shelf
    .filter((b) => b.status === 'reading' && b.lastOpenedAt)
    .sort((a, b) => b.lastOpenedAt - a.lastOpenedAt);
  const ws = vocab.stats(await vocab.listWords().catch(() => []));

  root.innerHTML = String(html`
    <div class="page">
      <section class="hero" aria-labelledby="hero-title">
        <p class="eyebrow">Mavis Library</p>
        <h1 class="display" id="hero-title">Your books. Your library. <em>Your imagination.</em></h1>
        <p class="lede">Read thousands of free classics, borrow from your public library, and keep everything you're reading on one calm shelf.</p>
        <form class="searchbar" role="search" id="home-search" aria-label="Search books">
          ${icon('search')}
          <label class="visually-hidden" for="home-q">Search by title or author</label>
          <input id="home-q" name="q" type="search" placeholder="Search by title or author" autocomplete="off" enterkeyhint="search" />
          <button type="button" class="icon-btn" data-mic aria-label="Search by voice" ${voiceInputSupported ? '' : html`title="Voice search isn't supported in this browser"`}>${icon('mic')}</button>
          <button type="submit" class="icon-btn go" aria-label="Search">${icon('chevronR')}</button>
        </form>
      </section>

      ${reading.length ? html`
        <section class="section" aria-labelledby="cont-h">
          <div class="section-head"><h2 class="h-section" id="cont-h">Continue reading</h2><a class="btn btn-quiet btn-sm" href="#/shelf?tab=reading">All in progress</a></div>
          <div class="feature-cards">
            ${reading.slice(0, 3).map((b) => {
              const p = progress.get(b.key);
              return html`<a class="continue" href="${b.key === 'bible' ? '#/bible' : `#/read/${encodeURIComponent(b.key)}`}">
                ${cover(b)}
                <div style="display:grid;gap:6px;min-width:0">
                  <span class="title">${b.title}</span>
                  <span class="small muted">${(b.authors || []).join(', ')}</span>
                  <div class="progress-line"><span style="width:${Math.round((p?.percent || 0) * 100)}%"></span></div>
                  <span class="small faint num">${p?.percent != null ? `${Math.round(p.percent * 100)}% read` : 'Started'}${p?.chapter ? ` · ${p.chapter}` : ''}</span>
                </div>
              </a>`;
            })}
          </div>
        </section>` : ''}

      ${ws.due ? html`<section class="section"><a class="kids-words" href="#/words/practice">
        <span class="kids-words-icon" aria-hidden="true">Aa</span>
        <span><b>${ws.due} word${ws.due === 1 ? '' : 's'} to practice today</b><span class="small muted">Word builder · ${ws.total} saved, ${ws.mastered} mastered</span></span>
        ${icon('chevronR')}</a></section>` : ''}

      <section class="section" aria-labelledby="bible-h">
        <a class="bible-feature" href="#/bible">
          ${bibleCover()}
          <div style="min-width:0">
            <p class="eyebrow" id="bible-h">Holy Bible · built in</p>
            <h3>Read, search, and study the Bible</h3>
            <p class="verse-of-day" id="votd">Verse lookup, Strong’s concordance, cross-references, highlights, and saved quotes in KJV and WEB.</p>
          </div>
        </a>
      </section>

      <section class="section" aria-labelledby="recs-h" id="recs-section" hidden>
        <div class="section-head"><h2 class="h-section" id="recs-h">Picked for you</h2><span class="small faint" id="recs-note"></span></div>
        <div id="recs"></div>
      </section>

      <section class="section" aria-labelledby="classics-h">
        <div class="section-head">
          <h2 class="h-section" id="classics-h">Curated classics</h2>
          <span class="small faint">Free, public domain in the USA · Project Gutenberg</span>
        </div>
        <div id="classics">${skeletonGrid(8)}</div>
      </section>

      <section class="section" aria-labelledby="topics-h">
        <div class="section-head"><h2 class="h-section" id="topics-h">Browse by shelf</h2></div>
        <div class="chips" role="list">
          ${SHELVES.map((s) => html`<a role="listitem" class="chip" href="#/search?src=free&topic=${encodeURIComponent(s.topic)}">${s.label}</a>`)}
        </div>
        <div id="topic-rows" style="display:grid;gap:30px;margin-top:8px">
          ${SHELVES.slice(0, 4).map((s) => html`
            <div class="topic-row" data-topic="${s.topic}">
              <div class="section-head" style="margin-bottom:10px"><h3 style="font-size:1.05rem;font-weight:700">${s.label}</h3>
              <a class="btn btn-quiet btn-sm" href="#/search?src=free&topic=${encodeURIComponent(s.topic)}">See all ${icon('chevronR', { size: 16 })}</a></div>
              <div class="rowbox">${skeletonGrid(6)}</div>
            </div>`)}
        </div>
      </section>

      <section class="section" aria-labelledby="more-h">
        <h2 class="h-section" id="more-h">More ways to read</h2>
        <div class="feature-cards">
          <div class="feature">
            <h3>${icon('library')} Borrow from your library</h3>
            <p class="muted">Many public libraries lend ebooks through Libby (OverDrive) and hoopla. Your card, holds, and loans stay with your library's app; Mavis links you to the right title.</p>
            <div class="actions">${LIBRARY_FINDERS.map((l) => html`<a class="btn btn-sm" href="${l.url}" target="_blank" rel="noopener noreferrer">${l.name.replace(/^See if your library offers /, 'Check ').replace(/^Find a library that uses /, 'Find ')} ${icon('external', { size: 16 })}</a>`)}</div>
          </div>
          <div class="feature">
            <h3>${icon('upload')} Bring your own books</h3>
            <p class="muted">Import DRM-free EPUB, plain text, or PDF files. They're stored privately on this device and work offline.</p>
            <div class="actions"><a class="btn btn-sm btn-primary" href="#/shelf?import=1">Import a book</a></div>
          </div>
          <div class="feature">
            <h3>${icon('headphones')} Listen while you read</h3>
            <p class="muted">Read-aloud uses the voices built into your device, follows along sentence by sentence, and turns the pages for you.</p>
          </div>
        </div>
      </section>
    </div>`);

  const form = root.querySelector('#home-search');
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const q = form.q.value.trim();
    navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search');
  });
  root.querySelector('[data-mic]').addEventListener('click', async () => {
    const text = await listen({ purpose: 'Search books by voice' });
    if (text) navigate(`/search?q=${encodeURIComponent(text)}&voice=1`);
    else form.q.focus();
  });

  const controllers = [];
  async function loadInto(el, fetcher, emptyText) {
    const ctl = new AbortController();
    controllers.push(ctl);
    el.innerHTML = String(skeletonGrid(6));
    try {
      const data = await fetcher(ctl.signal);
      if (!token()) return;
      if (!data.results.length) { el.innerHTML = String(stateBlock({ title: 'Nothing here yet', text: emptyText })); return; }
      el.innerHTML = String(html`<div class="row">${data.results.map((b) => bookCard(b, { badges: sourceBadges(b).slice(1) }))}</div>`);
    } catch (err) {
      if (err.name === 'AbortError' || !token()) return;
      el.innerHTML = String(stateBlock({
        tone: 'error', title: "Couldn't load these books", text: err.message,
        actions: html`<button type="button" class="btn btn-sm" data-retry>${icon('refresh', { size: 18 })} Try again</button>`,
      }));
      el.querySelector('[data-retry]').addEventListener('click', () => loadInto(el, fetcher, emptyText));
    }
  }

  // Verse of the day.
  (async () => {
    try {
      const day = Math.floor(Date.now() / 86400000);
      const r = B.parseOsis(DAILY[day % DAILY.length]);
      const [p] = await B.passageText('kjv', r);
      const el = root.querySelector('#votd');
      if (el && p && token()) el.textContent = `“${p.text}” — ${await B.label(r)}`;
      root.querySelector('.bible-feature')?.setAttribute('href', `#/bible/${r.book}/${r.chapter}?v=${r.verse}`);
    } catch { /* keep the description */ }
  })();

  // Picked for you, from the genres on your shelf.
  (async () => {
    const ctl = new AbortController();
    controllers.push(ctl);
    try {
      const recs = await recommendations({ signal: ctl.signal });
      if (!recs || !recs.items.length || !token()) return;
      const sec = root.querySelector('#recs-section');
      sec.hidden = false;
      root.querySelector('#recs-note').textContent = recs.ranker === 'jev' ? 'Ranked by Jev from your reading' : `From your shelf: ${recs.topics.slice(0, 2).join(', ')}`;
      root.querySelector('#recs').innerHTML = String(html`<div class="row">${recs.items.map((r) => bookCard(r.book, {
        extra: r.because.length ? html`<span class="why">Because you like ${r.because[0]}</span>` : '',
      }))}</div>`);
    } catch { /* recommendations are optional */ }
  })();

  loadInto(root.querySelector('#classics'), (signal) => searchGutenberg({ ids: CLASSICS.join(',') }, { signal }), 'The classics shelf is empty right now.');

  const io = 'IntersectionObserver' in window ? new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      const topic = e.target.dataset.topic;
      loadInto(e.target.querySelector('.rowbox'), (signal) => searchGutenberg({ topic, languages: 'en' }, { signal }), 'No books on this shelf.');
    }
  }, { rootMargin: '300px' }) : null;
  for (const row of root.querySelectorAll('.topic-row')) {
    if (io) io.observe(row);
    else loadInto(row.querySelector('.rowbox'), (signal) => searchGutenberg({ topic: row.dataset.topic, languages: 'en' }, { signal }), 'No books on this shelf.');
  }

  return () => { controllers.forEach((c) => c.abort()); io?.disconnect(); };
}

export { toast };
