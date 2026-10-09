import { html, icon, LANGS } from '../lib/ui.js';
import { searchGutenberg, searchOpenLibrary } from '../lib/catalog.js';
import { bookCard, skeletonGrid, stateBlock, sourceBadges } from '../components.js';
import { listen, voiceInputSupported } from '../lib/voice-input.js';

export const title = (route) => (route.params.get('q') ? `“${route.params.get('q')}”` : 'Search');

const LANG_CHOICES = ['en', 'fr', 'de', 'es', 'it', 'pt', 'nl', 'fi', 'sv', 'la', 'el', 'zh'];

export async function render(root, route, { navigate, token }) {
  const p = route.params;
  const q = (p.get('q') || '').slice(0, 120);
  const topic = (p.get('topic') || '').slice(0, 60);
  const src = p.get('src') === 'all' && !topic ? 'all' : 'free';
  const lang = /^[a-z]{2}$/.test(p.get('lang') || '') ? p.get('lang') : '';
  const sort = ['popular', 'descending', 'ascending'].includes(p.get('sort')) ? p.get('sort') : 'popular';
  const page = Math.max(1, Math.min(2000, parseInt(p.get('page') || '1', 10) || 1));
  const fromVoice = p.get('voice') === '1';

  const link = (patch) => {
    const n = new URLSearchParams({ ...(q && { q }), ...(topic && { topic }), src, ...(lang && { lang }), ...(sort !== 'popular' && { sort }), ...patch });
    for (const [k, v] of [...n]) if (v === '' || v == null) n.delete(k);
    if (n.get('page') === '1') n.delete('page');
    return `/search?${n}`;
  };

  root.innerHTML = String(html`
    <div class="page">
      <div class="search-head">
        <h1 class="h-section" style="font-size:var(--step-3)">${topic ? html`Shelf: ${topic.replace(/\b\w/g, (c) => c.toUpperCase())}` : 'Find a book'}</h1>
        <form class="searchbar" role="search" id="search-form">
          ${icon('search')}
          <label class="visually-hidden" for="q">Search by title or author</label>
          <input id="q" name="q" type="search" value="${q}" placeholder="Title or author" autocomplete="off" enterkeyhint="search" />
          <button type="button" class="icon-btn" data-mic aria-label="Search by voice">${icon('mic')}</button>
          <button type="submit" class="icon-btn go" aria-label="Search">${icon('chevronR')}</button>
        </form>
        ${fromVoice && q ? html`<p class="small muted">${icon('mic', { size: 16 })} Heard “${q}”. Not right? Edit the text above.</p>` : ''}
        <div class="tabs" role="tablist" aria-label="Where to search">
          <button role="tab" type="button" aria-selected="${src === 'free'}" data-src="free">Free to read</button>
          <button role="tab" type="button" aria-selected="${src === 'all'}" data-src="all" ${topic ? 'disabled' : ''}>All books</button>
        </div>
        <div class="toolbar">
          <p class="small muted" style="max-width:60ch">${src === 'free'
            ? 'Public-domain ebooks from Project Gutenberg. Download and read them right here.'
            : 'Every book Open Library knows about. Borrow from your library or buy from a store; most are not free to read here.'}</p>
          ${src === 'free' ? html`<div style="display:flex;gap:8px;flex-wrap:wrap">
            <label class="visually-hidden" for="lang">Language</label>
            <select class="select" id="lang">
              <option value="">Any language</option>
              ${LANG_CHOICES.map((c) => html`<option value="${c}" ${c === lang ? 'selected' : ''}>${LANGS[c]}</option>`)}
            </select>
            <label class="visually-hidden" for="sort">Sort</label>
            <select class="select" id="sort">
              <option value="popular" ${sort === 'popular' ? 'selected' : ''}>Most downloaded</option>
              <option value="descending" ${sort === 'descending' ? 'selected' : ''}>Newest to Gutenberg</option>
              <option value="ascending" ${sort === 'ascending' ? 'selected' : ''}>Oldest to Gutenberg</option>
            </select>
          </div>` : ''}
        </div>
      </div>
      <div id="results" aria-live="polite"></div>
    </div>`);

  const form = root.querySelector('#search-form');
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const nq = form.q.value.trim();
    navigate(link({ q: nq, topic: '', page: '1' }).replace(/[?&]voice=1/, ''));
  });
  root.querySelector('[data-mic]').addEventListener('click', async () => {
    const text = await listen({ purpose: 'Search books by voice' });
    if (text) navigate(link({ q: text, topic: '', page: '1', voice: '1' }));
    else form.q.focus();
  });
  if (!voiceInputSupported) root.querySelector('[data-mic]').title = "Voice search isn't supported in this browser";
  for (const t of root.querySelectorAll('[data-src]')) t.addEventListener('click', () => navigate(link({ src: t.dataset.src, page: '1' })));
  root.querySelector('#lang')?.addEventListener('change', (e) => navigate(link({ lang: e.target.value, page: '1' })));
  root.querySelector('#sort')?.addEventListener('change', (e) => navigate(link({ sort: e.target.value, page: '1' })));

  const results = root.querySelector('#results');
  if (!q && !topic && src === 'all') {
    results.innerHTML = String(stateBlock({ title: 'Search everything', text: 'Type a title or author to search Open Library’s catalog of millions of books.' }));
    return;
  }

  const ctl = new AbortController();
  async function load() {
    results.innerHTML = String(skeletonGrid(12, 'grid'));
    try {
      const data = src === 'free'
        ? await searchGutenberg({ search: q, topic, languages: lang, page, sort }, { signal: ctl.signal })
        : await searchOpenLibrary({ q, page }, { signal: ctl.signal });
      if (!token()) return;
      const pages = Math.max(1, Math.ceil((data.count || 0) / data.pageSize));
      if (!data.results.length) {
        results.innerHTML = String(stateBlock({
          title: page > 1 ? 'No more results' : 'No books found',
          text: src === 'free'
            ? (q ? `Project Gutenberg has no free book matching “${q}”${lang ? ` in ${LANGS[lang]}` : ''}. Try fewer words, or search All books to borrow or buy it.` : 'Nothing on this shelf matches your filters.')
            : `Open Library found nothing for “${q}”. Check the spelling or try just the author's last name.`,
          actions: src === 'free' && q ? html`<a class="btn btn-sm" href="#${link({ src: 'all', page: '1' })}">Search all books</a>` : '',
        }));
        return;
      }
      results.innerHTML = String(html`
        <p class="result-count num" style="margin:4px 0 14px">${(data.count || 0).toLocaleString()} ${data.count === 1 ? 'book' : 'books'} · page ${page} of ${pages.toLocaleString()}</p>
        <div class="grid">${data.results.map((b) => bookCard(b, { badges: sourceBadges(b) }))}</div>
        <nav class="pager" aria-label="Pages">
          ${data.hasPrev || page > 1 ? html`<a class="btn" href="#${link({ page: String(page - 1) })}" rel="prev">${icon('chevronL', { size: 18 })} Previous</a>` : html`<span class="btn" aria-disabled="true" style="opacity:.4">${icon('chevronL', { size: 18 })} Previous</span>`}
          <span class="small faint num">Page ${page}</span>
          ${data.hasNext ? html`<a class="btn" href="#${link({ page: String(page + 1) })}" rel="next">Next ${icon('chevronR', { size: 18 })}</a>` : html`<span class="btn" aria-disabled="true" style="opacity:.4">Next ${icon('chevronR', { size: 18 })}</span>`}
        </nav>`);
    } catch (err) {
      if (err.name === 'AbortError' || !token()) return;
      results.innerHTML = String(stateBlock({
        tone: 'error',
        title: err.code === 'offline' ? "You're offline" : 'The search didn’t go through',
        text: err.message,
        actions: html`<button type="button" class="btn btn-sm btn-primary" data-retry>${icon('refresh', { size: 18 })} Try again</button><a class="btn btn-sm" href="#/shelf">Open my shelf</a>`,
      }));
      results.querySelector('[data-retry]').addEventListener('click', load);
    }
  }
  load();
  return () => ctl.abort();
}
