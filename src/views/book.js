import { html, icon, toast, langName, formatBytes, confirmDialog, esc } from '../lib/ui.js';
import { recallBook, getGutenbergBook, getOpenLibraryWork, downloadGutenbergEpub, displayName } from '../lib/catalog.js';
import * as store from '../lib/store.js';
import { cover, stateBlock } from '../components.js';
import { lendingLinks, retailLinks } from '../lib/links.js';

export const title = (route) => recallBook(route.segs[0])?.title || 'Book';

export async function render(root, route, { navigate, token }) {
  const key = route.segs[0] || '';
  const [source, ...rest] = key.split(':');
  const sourceId = rest.join(':');
  if (!['gutenberg', 'openlibrary', 'import'].includes(source) || !sourceId) {
    root.innerHTML = String(html`<div class="page">${stateBlock({ title: 'Book not found', text: 'That link doesn’t point to a book Mavis knows how to open.', actions: html`<a class="btn btn-primary" href="#/search">Search books</a>` })}</div>`);
    return;
  }

  const ctl = new AbortController();
  let book = recallBook(key);
  const shelfItem = await store.getShelfItem(key).catch(() => null);
  if (!book && shelfItem) book = { ...shelfItem };

  async function loadAndRender() {
    root.innerHTML = String(html`<div class="page"><div class="detail"><div class="detail-cover"><div class="skeleton cover-sk"></div></div><div><div class="skeleton line" style="height:34px;width:70%"></div><div class="skeleton line short"></div><div class="skeleton line" style="margin-top:30px"></div><div class="skeleton line"></div></div></div></div>`);
    try {
      if (source === 'gutenberg' && (!book || book.summary === undefined)) {
        if (!/^\d{1,6}$/.test(sourceId)) throw new Error('That is not a valid Gutenberg id.');
        try {
          book = await getGutenbergBook(sourceId, { signal: ctl.signal });
        } catch (err) {
          // Offline but on the shelf: show what we have instead of an error.
          if (!book || err.name === 'AbortError') throw err;
          book.summary = null;
        }
      }
      if (source === 'import' && !book) throw Object.assign(new Error('This imported book is no longer on your shelf.'), { code: 'gone' });
      if (source === 'openlibrary' && !book) {
        const w = await getOpenLibraryWork(sourceId, { signal: ctl.signal });
        book = { key, source, sourceId, title: w.title, authors: [], subjects: w.subjects, description: w.description, sourceUrl: `https://openlibrary.org/works/${sourceId}` };
      }
    } catch (err) {
      if (err.name === 'AbortError' || !token()) return;
      root.innerHTML = String(html`<div class="page" style="padding-top:20px">${stateBlock({
        tone: 'error', title: err.code === 'gone' ? 'Not on your shelf' : 'This book couldn’t be loaded', text: err.message,
        actions: html`${err.code !== 'gone' ? html`<button class="btn btn-primary btn-sm" type="button" data-retry>Try again</button>` : ''}<a class="btn btn-sm" href="#/shelf">My shelf</a>`,
      })}</div>`);
      root.querySelector('[data-retry]')?.addEventListener('click', loadAndRender);
      return;
    }
    if (!token()) return;
    document.title = `${book.title} · Mavis Library`;
    await paint();
    if (source === 'openlibrary' && book.description === undefined) {
      getOpenLibraryWork(sourceId, { signal: ctl.signal }).then((w) => {
        if (!token()) return;
        book.description = w.description || null;
        if (w.subjects?.length && !book.subjects?.length) book.subjects = w.subjects;
        paint();
      }).catch(() => { book.description = null; });
    }
  }

  async function paint() {
    const item = await store.getShelfItem(key).catch(() => null);
    const hasFile = await store.hasFile(key).catch(() => false);
    const localCover = source === 'import' ? await store.getCache(`cover|${store.getOwner()}|${key}`) : null;
    const authors = book.authorDetails?.length
      ? book.authorDetails.map((a) => `${a.name}${a.birthYear || a.deathYear ? ` (${a.birthYear ?? '?'}–${a.deathYear ?? ''})` : ''}`)
      : (book.authors || []);
    const isFree = source === 'gutenberg';
    const fileOnOtherDevice = item && (item.source === 'import') && !hasFile;

    root.innerHTML = String(html`
      <div class="page">
        <div style="padding-top:8px"><button type="button" class="btn btn-quiet btn-sm" data-back>${icon('back', { size: 18 })} Back</button></div>
        <article class="detail">
          <div class="detail-cover">${cover({ ...book, coverUrl: book.coverUrl }, { localCover, eager: true })}</div>
          <div style="min-width:0">
            <div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px">
              ${isFree ? html`<span class="badge badge-ok">Free · Public domain in the USA</span>` : ''}
              ${source === 'openlibrary' ? html`<span class="badge">From Open Library</span>` : ''}
              ${source === 'import' ? html`<span class="badge badge-accent">Imported ${book.format === 'pdf' ? 'PDF' : 'book'}</span>` : ''}
              ${hasFile ? html`<span class="badge badge-ok">${icon('check', { size: 14 })} On this device</span>` : ''}
              ${item ? html`<span class="badge">${store.STATUSES[item.status]}</span>` : ''}
            </div>
            <h1>${book.title}</h1>
            ${authors.length ? html`<p class="byline">by ${authors.join(', ')}</p>` : ''}
            ${book.translators?.length ? html`<p class="small faint">Translated by ${book.translators.join(', ')}</p>` : ''}

            <div class="actions" id="actions">
              ${hasFile ? html`<a class="btn btn-primary" href="#/read/${encodeURIComponent(key)}">${icon('book', { size: 20 })} ${item?.status === 'reading' ? 'Continue reading' : 'Read'}</a>` : ''}
              ${isFree && !hasFile ? html`<button type="button" class="btn btn-primary" data-download>${icon('download', { size: 20 })} Download &amp; read</button>` : ''}
              ${source === 'openlibrary' && book.gutenbergIds?.length ? html`<a class="btn btn-primary" href="#/book/gutenberg:${book.gutenbergIds[0]}">${icon('book', { size: 20 })} Read the free edition</a>` : ''}
              ${item
                ? html`<label class="visually-hidden" for="status">Shelf status</label><select class="select" id="status" style="width:auto;border-radius:999px">${Object.entries(store.STATUSES).map(([k, v]) => html`<option value="${k}" ${item.status === k ? 'selected' : ''}>${v}</option>`)}</select>`
                : html`<button type="button" class="btn" data-want>${icon('plus', { size: 20 })} Want to read</button>`}
              ${hasFile && source !== 'import' ? html`<button type="button" class="btn btn-quiet" data-remove-file>${icon('trash', { size: 18 })} Remove download</button>` : ''}
              ${item ? html`<button type="button" class="btn btn-quiet" data-remove>Remove from shelf</button>` : ''}
            </div>
            <div id="dl"></div>
            ${fileOnOtherDevice ? html`<div class="notice" style="margin-top:14px">${icon('info')}<span>This book was imported on another device. Book files stay on the device they were imported on; import “${book.fileName || 'the file'}” here to read it. Your progress and notes will carry over.</span></div>` : ''}

            ${book.summary ? html`<div class="summary"><p>${book.summary}</p><span class="attrib">Summary from Project Gutenberg’s catalog (machine-generated by Project Gutenberg).</span></div>` : ''}
            ${book.description ? html`<div class="summary"><p>${book.description}</p><span class="attrib">Description from Open Library.</span></div>` : ''}

            <dl class="facts">
              ${book.languages?.length ? html`<div class="fact"><dt>Language</dt><dd>${book.languages.map((l) => langName(l.slice(0, 2)) || l).join(', ')}</dd></div>` : ''}
              ${isFree ? html`<div class="fact"><dt>Gutenberg eBook</dt><dd class="num">#${sourceId}</dd></div>` : ''}
              ${book.downloads != null ? html`<div class="fact"><dt>Recent downloads</dt><dd class="num">${Number(book.downloads).toLocaleString()}</dd></div>` : ''}
              ${book.year ? html`<div class="fact"><dt>First published</dt><dd class="num">${book.year}</dd></div>` : ''}
              ${book.editionCount ? html`<div class="fact"><dt>Editions</dt><dd class="num">${Number(book.editionCount).toLocaleString()}</dd></div>` : ''}
              ${book.isbn ? html`<div class="fact"><dt>ISBN</dt><dd class="num">${book.isbn}</dd></div>` : ''}
              ${book.fileSize ? html`<div class="fact"><dt>File</dt><dd>${book.fileName || ''} · ${formatBytes(book.fileSize)}</dd></div>` : ''}
            </dl>

            ${(book.subjects?.length || book.bookshelves?.length) ? html`<div style="margin-top:18px"><p class="eyebrow">Subjects</p>
              <div class="subject-list">${[...(book.bookshelves || []), ...(book.subjects || [])].slice(0, 14).map((s) => source === 'gutenberg'
                ? html`<a class="chip" href="#/search?src=free&topic=${encodeURIComponent(s.split(' -- ')[0].slice(0, 60))}">${s.split(' -- ')[0]}</a>`
                : html`<span class="chip" style="cursor:default">${s}</span>`)}</div></div>` : ''}

            ${isFree ? html`
              <div class="notice" style="margin-top:22px">${icon('globe')}<span>This book is public domain in the USA. If you live elsewhere, check your country’s copyright rules before downloading. <a href="${book.sourceUrl}" target="_blank" rel="noopener noreferrer">View on Project Gutenberg</a>${book.epubUrl ? html` · <a href="${book.epubUrl}" target="_blank" rel="noopener noreferrer">EPUB file at the source</a>` : ''}</span></div>` : ''}
            ${source === 'openlibrary' && book.sourceUrl ? html`<p class="small" style="margin-top:14px"><a href="${book.sourceUrl}" target="_blank" rel="noopener noreferrer">View on Open Library ${icon('external', { size: 14 })}</a></p>` : ''}

            ${source !== 'import' ? html`
            <section class="section" aria-labelledby="borrow-h" style="margin-top:34px">
              <h2 class="h-section" id="borrow-h">${isFree ? 'Prefer another edition?' : 'Borrow from a library'}</h2>
              ${isFree ? '' : html`<p class="small muted">Mavis doesn't connect to your library card or show live availability. These links search the lending service for this title; borrowing and reading happen there.</p>`}
              <div class="providers">
                ${lendingLinks(book).map((l) => html`<div class="provider">
                  <div class="provider-head"><span class="provider-name">${icon('library', { size: 20 })} ${l.name}</span>
                    <span style="display:flex;gap:8px;flex-wrap:wrap"><a class="btn btn-sm" href="${l.url}" target="_blank" rel="noopener noreferrer">${l.cta} ${icon('external', { size: 16 })}</a>
                    ${l.secondary ? html`<a class="btn btn-sm btn-quiet" href="${l.secondary.url}" target="_blank" rel="noopener noreferrer">${l.secondary.label}</a>` : ''}</span></div>
                  <p class="small muted">${l.handles}</p></div>`)}
              </div>
              <h2 class="h-section" style="margin-top:22px">Buy an ebook</h2>
              <p class="small muted">Prices and checkout are on the store’s site. Mavis doesn’t sell books or see your purchases.</p>
              <div class="providers">
                ${retailLinks(book).map((l) => html`<div class="provider">
                  <div class="provider-head"><span class="provider-name">${icon('cart', { size: 20 })} ${l.name}</span>
                    <a class="btn btn-sm" href="${l.url}" target="_blank" rel="noopener noreferrer">${l.cta} ${icon('external', { size: 16 })}</a></div>
                  <p class="small muted">${l.note}</p></div>`)}
              </div>
            </section>` : ''}
          </div>
        </article>
      </div>`);

    root.querySelector('[data-back]').addEventListener('click', () => (history.length > 1 ? history.back() : navigate('/')));
    root.querySelector('[data-download]')?.addEventListener('click', download);
    root.querySelector('[data-want]')?.addEventListener('click', async () => {
      try {
        await store.saveToShelf(book, { status: 'want' });
        toast(`Added “${book.title}” to Want to read.`);
        paint();
      } catch (err) { toast(`Couldn't save to your shelf: ${err.message}`, { tone: 'error' }); }
    });
    root.querySelector('#status')?.addEventListener('change', async (e) => {
      await store.updateShelf(key, { status: e.target.value });
      toast(`Moved to ${store.STATUSES[e.target.value]}.`);
    });
    root.querySelector('[data-remove-file]')?.addEventListener('click', async () => {
      const ok = await confirmDialog({ title: 'Remove the download?', message: 'The book file will be deleted from this device. It stays on your shelf with your progress and notes, and you can download it again.', confirmLabel: 'Remove download' });
      if (!ok) return;
      await store.deleteFile(key);
      toast('Download removed from this device.');
      paint();
    });
    root.querySelector('[data-remove]')?.addEventListener('click', async () => {
      const ok = await confirmDialog({ title: 'Remove from your shelf?', message: `“${book.title}” will be removed from your shelf${hasFile ? ' and its file deleted from this device' : ''}. Bookmarks and notes are kept in case you add it again.`, confirmLabel: 'Remove' });
      if (!ok) return;
      await store.removeFromShelf(key);
      toast(`Removed “${book.title}”.`, { action: { label: 'Undo', run: async () => { await store.saveToShelf(item, { status: item.status }); paint(); } } });
      if (source === 'import') navigate('/shelf'); else paint();
    });
  }

  let downloading = null;
  async function download() {
    if (downloading) return;
    const btn = root.querySelector('[data-download]');
    const dl = root.querySelector('#dl');
    downloading = new AbortController();
    btn.disabled = true;
    dl.innerHTML = String(html`<div class="dl-progress" role="status"><div class="dl-bar"><span></span></div><div class="small muted num" id="dl-text">Starting download…</div><div><button class="btn btn-sm btn-quiet" type="button" data-cancel>Cancel</button></div></div>`);
    const bar = dl.querySelector('.dl-bar span');
    const text = dl.querySelector('#dl-text');
    dl.querySelector('[data-cancel]').addEventListener('click', () => downloading?.abort());
    try {
      const blob = await downloadGutenbergEpub(sourceId, {
        signal: downloading.signal,
        onProgress: (got, total) => {
          if (total) { bar.style.width = `${Math.round((got / total) * 100)}%`; text.textContent = `${formatBytes(got)} of ${formatBytes(total)}`; }
          else { bar.style.width = '60%'; text.textContent = `${formatBytes(got)} downloaded`; }
        },
      });
      text.textContent = 'Saving to this device…';
      await store.saveToShelf({ ...book, format: 'epub', fileSize: blob.size }, { status: null });
      await store.saveFile(key, blob, 'application/epub+zip');
      bar.style.width = '100%';
      toast(`“${book.title}” is ready to read offline.`);
      navigate(`/read/${encodeURIComponent(key)}`);
    } catch (err) {
      if (err.name === 'AbortError') { dl.innerHTML = ''; btn.disabled = false; toast('Download cancelled.'); return; }
      dl.innerHTML = String(html`<div class="state error" style="margin-top:14px" role="alert"><h3>The download didn’t finish</h3><p>${err.message}</p>
        <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn btn-sm btn-primary" type="button" data-retry-dl>Try again</button>
        ${book.epubUrl ? html`<a class="btn btn-sm" href="${book.epubUrl}" target="_blank" rel="noopener noreferrer">Get the EPUB from Gutenberg</a><a class="btn btn-sm btn-quiet" href="#/shelf?import=1">Then import it</a>` : ''}</div></div>`);
      dl.querySelector('[data-retry-dl]').addEventListener('click', () => { dl.innerHTML = ''; btn.disabled = false; download(); });
    } finally {
      downloading = null;
    }
  }

  await loadAndRender();
  return () => { ctl.abort(); downloading?.abort(); };
}

export { esc, displayName };
