import { listDownloaded, summarize } from '../lib/audiobook.js';
import { isKids, kidsBook } from '../lib/kids.js';
import { html, icon, toast, openDialog, confirmDialog, formatBytes, debounce, timeAgo } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { bookCard, stateBlock } from '../components.js';
import { inspectFile, ACCEPT, ImportError } from '../lib/importer.js';
import { currentUser, authConfigured } from '../lib/auth.js';
import { downloadGutenbergEpub } from '../lib/catalog.js';

export const title = () => 'My shelf';

const TABS = [['all', 'All'], ['reading', 'Reading'], ['want', 'Want to read'], ['finished', 'Finished']];
const SORTS = { opened: 'Recently opened', added: 'Recently added', title: 'Title', author: 'Author', progress: 'Progress' };
const FILTERS = { any: 'All books', device: 'On this device', imported: 'Imported files', missing: 'Not downloaded' };

export async function importFiles(files, { onDone } = {}) {
  const results = [];
  for (const file of files) {
    try {
      const info = await inspectFile(file);
      // Re-importing a file whose record synced from another device attaches
      // the file to that record, keeping progress and notes.
      const shelf = await store.listShelf();
      const twin = shelf.find((b) => b.source === 'import' && b.fileName === file.name && b.fileSize === file.size);
      if (twin && await store.hasFile(twin.key)) { toast(`“${info.title}” is already on your shelf.`); continue; }
      const id = twin ? twin.sourceId : store.uuid();
      const key = twin ? twin.key : `import:${id}`;
      await store.saveFile(key, info.blob, info.mime);
      await store.saveToShelf({
        key, source: 'import', sourceId: id, title: info.title, authors: info.authors, languages: info.languages,
        format: info.format, fileName: file.name, fileSize: file.size, coverUrl: null,
      }, { status: twin ? null : 'want' });
      if (info.cover) await store.setCache(`cover|${store.getOwner()}|${key}`, info.cover);
      results.push({ key, title: info.title, converted: info.converted });
    } catch (err) {
      const msg = err instanceof ImportError ? err.message : `“${file.name}” couldn't be imported: ${err.message}`;
      toast(msg, { tone: 'error', timeout: 9000 });
    }
  }
  if (results.length === 1) {
    const r = results[0];
    toast(`Imported “${r.title}”${r.converted === 'txt' ? ' (converted from plain text)' : ''}.`, { action: { label: 'Read now', run: () => { location.hash = `#/read/${encodeURIComponent(r.key)}`; } } });
  } else if (results.length > 1) toast(`Imported ${results.length} books.`);
  onDone?.(results);
  return results;
}

export async function render(root, route, { navigate, token }) {
  const params = route.params;
  let tab = TABS.some(([k]) => k === params.get('tab')) ? params.get('tab') : 'all';
  let sort = store.getSetting('shelfSort', 'opened');
  let filter = 'any';
  let query = '';

  root.innerHTML = String(html`
    <div class="page">
      <div class="shelf-head">
        <div class="section-head">
          <h1>My shelf</h1>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <a class="btn" href="#/quotes">${icon('quote', { size: 20 })} Saved quotes</a>
            <a class="btn" href="#/words">${icon('dict', { size: 20 })} Word builder</a>
            ${isKids() ? '' : html`<a class="btn" href="#/groups">${icon('user', { size: 20 })} Book clubs</a>`}
            <label class="btn btn-primary" for="import-input" style="cursor:pointer">${icon('upload', { size: 20 })} Import a book</label>
            <input id="import-input" type="file" accept="${ACCEPT}" multiple class="visually-hidden" />
          </div>
        </div>
        <p class="small muted" id="scope-note"></p>
        <div class="tabs" role="tablist" aria-label="Collections" id="tabs"></div>
        <div class="shelf-tools">
          <label class="visually-hidden" for="shelf-q">Filter your shelf</label>
          <input class="input" id="shelf-q" type="search" placeholder="Filter by title or author" autocomplete="off" />
          <label class="visually-hidden" for="shelf-filter">Show</label>
          <select class="select" id="shelf-filter" style="width:auto">${Object.entries(FILTERS).map(([k, v]) => html`<option value="${k}">${v}</option>`)}</select>
          <label class="visually-hidden" for="shelf-sort">Sort by</label>
          <select class="select" id="shelf-sort" style="width:auto">${Object.entries(SORTS).map(([k, v]) => html`<option value="${k}" ${k === sort ? 'selected' : ''}>${v}</option>`)}</select>
        </div>
      </div>
      <div id="import-panel" ${params.get('import') ? '' : 'hidden'} style="margin:14px 0">
        <div class="dropzone" id="dropzone">
          ${icon('upload', { size: 28 })}
          <strong>Drop EPUB, TXT, or PDF files here</strong>
          <span class="small">or <label for="import-input" style="text-decoration:underline;cursor:pointer;color:var(--link)">choose files</label>. EPUB up to 60 MB, text up to 10 MB, PDF up to 100 MB. DRM-protected files can't be opened.</span>
        </div>
      </div>
      <div id="shelf-body" style="margin-top:18px" aria-live="polite"></div>
      <div class="storage" id="storage"></div>
    </div>`);

  const body = root.querySelector('#shelf-body');
  const scopeNote = root.querySelector('#scope-note');
  const user = currentUser();
  scopeNote.innerHTML = String(user
    ? html`Synced to your account (${user.email}). Book files stay on each device.`
    : html`Saved on this device only.${authConfigured ? html` <a href="#/account">Create an account</a> to sync your shelf, progress, and notes across devices.` : ''}`);

  const input = root.querySelector('#import-input');
  input.addEventListener('change', async () => {
    const files = [...input.files];
    input.value = '';
    if (files.length) { toast(`Importing ${files.length === 1 ? files[0].name : `${files.length} files`}…`, { timeout: 2000 }); await importFiles(files); }
  });
  const dz = root.querySelector('#dropzone');
  const page = root.querySelector('.page');
  page.addEventListener('dragover', (e) => { if ([...(e.dataTransfer?.types || [])].includes('Files')) { e.preventDefault(); root.querySelector('#import-panel').hidden = false; dz.classList.add('drag'); } });
  page.addEventListener('dragleave', (e) => { if (!page.contains(e.relatedTarget)) dz.classList.remove('drag'); });
  page.addEventListener('drop', async (e) => {
    if (!e.dataTransfer?.files?.length) return;
    e.preventDefault(); dz.classList.remove('drag');
    await importFiles([...e.dataTransfer.files]);
  });

  root.querySelector('#shelf-q').addEventListener('input', debounce((e) => { query = e.target.value.trim().toLowerCase(); paint(); }, 120));
  root.querySelector('#shelf-filter').addEventListener('change', (e) => { filter = e.target.value; paint(); });
  root.querySelector('#shelf-sort').addEventListener('change', (e) => { sort = e.target.value; store.setSetting('shelfSort', sort); paint(); });

  let painting = false, again = false;
  async function paint() {
    if (painting) { again = true; return; }
    painting = true;
    try {
      const [all, progress, files, audio] = await Promise.all([store.listShelf(), store.allProgress(), store.fileKeysForOwner(), listDownloaded()]);
      const items = isKids() ? all.filter((b) => b.key === 'bible' || kidsBook(b)) : all;
      // Saved audio (Mavis voice or LibriVox recording) → listen button on the book's card.
      const listenKey = new Map(audio.filter((m) => summarize(m).done > 0).map((m) => [m.bookKey.replace(/^lv:/, ''), m.bookKey]));
      const audioKeys = new Set(listenKey.keys());
      if (!token()) return;
      const counts = { all: items.length, reading: 0, want: 0, finished: 0 };
      for (const b of items) counts[b.status] = (counts[b.status] || 0) + 1;
      root.querySelector('#tabs').innerHTML = String(html`${TABS.map(([k, v]) => html`<button role="tab" type="button" aria-selected="${k === tab}" data-tab="${k}">${v} <span class="faint num">${counts[k] || 0}</span></button>`)}`);
      for (const b of root.querySelectorAll('[data-tab]')) b.onclick = () => { tab = b.dataset.tab; history.replaceState(null, '', `#/shelf?tab=${tab}`); paint(); };

      if (!items.length) {
        body.innerHTML = String(stateBlock({
          title: 'Your shelf is ready for its first book',
          text: 'Download a free classic, save a book you want to borrow, or import an EPUB you already own.',
          actions: html`<a class="btn btn-primary btn-sm" href="#/">Discover books</a><label class="btn btn-sm" for="import-input" style="cursor:pointer">Import a book</label>`,
        }));
        return;
      }

      let list = items.filter((b) => tab === 'all' || b.status === tab);
      if (query) list = list.filter((b) => `${b.title} ${(b.authors || []).join(' ')}`.toLowerCase().includes(query));
      if (filter === 'device') list = list.filter((b) => files.has(b.key));
      if (filter === 'imported') list = list.filter((b) => b.source === 'import');
      if (filter === 'missing') list = list.filter((b) => !files.has(b.key));
      const by = {
        opened: (a, b) => (b.lastOpenedAt || b.addedAt || 0) - (a.lastOpenedAt || a.addedAt || 0),
        added: (a, b) => (b.addedAt || 0) - (a.addedAt || 0),
        title: (a, b) => a.title.localeCompare(b.title),
        author: (a, b) => ((a.authors || [])[0] || '~').localeCompare((b.authors || [])[0] || '~'),
        progress: (a, b) => (progress.get(b.key)?.percent || 0) - (progress.get(a.key)?.percent || 0),
      };
      list.sort(by[sort] || by.opened);

      if (!list.length) {
        body.innerHTML = String(stateBlock({ title: 'No books match', text: query ? `Nothing on this shelf matches “${query}”.` : 'Nothing in this collection yet.' }));
        return;
      }
      const covers = new Map();
      await Promise.all(list.filter((b) => b.source === 'import').map(async (b) => covers.set(b.key, await store.getCache(`cover|${store.getOwner()}|${b.key}`))));

      body.innerHTML = String(html`<div class="grid">${list.map((b) => {
        const p = progress.get(b.key);
        const onDevice = files.has(b.key);
        const isBible = b.key === 'bible';
        const badges = [];
        if (p?.percent != null && b.status !== 'finished') badges.push({ label: `${Math.round(p.percent * 100)}%`, tone: 'accent' });
        if (b.status === 'finished') badges.push({ label: 'Finished', tone: 'ok' });
        if (onDevice || isBible) badges.push({ label: isBible ? 'Built in · offline' : 'On device', tone: 'ok' });
        if (audioKeys.has(b.key)) badges.push({ label: 'Audio saved', tone: 'ok' });
        if (b.source === 'import') badges.push({ label: b.format === 'pdf' ? 'PDF' : 'Imported' });
        if (b.source === 'import' && !onDevice) badges.push({ label: 'File on another device', tone: 'warn' });
        const href = isBible ? '#/bible' : onDevice ? `#/read/${encodeURIComponent(b.key)}` : `#/book/${encodeURIComponent(b.key)}`;
        return html`<div class="shelf-card">
          ${bookCard(b, { href, badges, progress: p?.percent ?? null, localCover: covers.get(b.key) })}
          <button type="button" class="icon-btn menu-btn" data-menu="${b.key}" aria-label="Options for ${b.title}">${icon('more', { size: 20 })}</button>
        </div>`;
      })}</div>`);
      for (const k of audioKeys) {
        const card = [...body.querySelectorAll('[data-menu]')].find((x) => x.dataset.menu === k)?.closest('.shelf-card');
        if (card) card.insertAdjacentHTML('beforeend', String(html`<a class="icon-btn listen-btn" href="#/listen/${encodeURIComponent(listenKey.get(k))}" aria-label="Listen to the saved audio">${icon('headphones', { size: 20 })}</a>`));
      }
      for (const btn of body.querySelectorAll('[data-menu]')) btn.addEventListener('click', () => openMenu(items.find((x) => x.key === btn.dataset.menu), files.has(btn.dataset.menu)));
    } catch (err) {
      body.innerHTML = String(stateBlock({ tone: 'error', title: "Your shelf couldn't be opened", text: `${err.message} If you're in a private window, your browser may block offline storage.` }));
    } finally {
      painting = false;
      if (again) { again = false; paint(); }
    }
    paintStorage();
  }

  async function paintStorage() {
    const est = await store.storageEstimate();
    const el = root.querySelector('#storage');
    if (!el || !est) return;
    el.innerHTML = String(html`<span>${icon('download', { size: 16 })} Books and data on this device use <strong class="num">${formatBytes(est.usage)}</strong>${est.quota ? html` of about ${formatBytes(est.quota)} available` : ''}.</span>
      <span>${est.persisted ? 'Protected from automatic cleanup.' : 'Your browser may clear downloads if the device runs low on space.'}</span>`);
  }

  function openMenu(item, onDevice) {
    if (!item) return;
    const d = openDialog({
      title: item.title,
      variant: 'sheet',
      body: html`<div class="menu-list">
        ${item.key === 'bible' ? html`<a class="menu-item" href="#/bible">${icon('book')} Open the Bible</a>` : ''}
        ${onDevice ? html`<a class="menu-item" href="#/read/${encodeURIComponent(item.key)}">${icon('book')} Read</a>` : ''}
        ${item.key !== 'bible' ? html`<a class="menu-item" href="#/book/${encodeURIComponent(item.key)}">${icon('info')} Book details</a>` : ''}
        ${Object.entries(store.STATUSES).filter(([k]) => k !== item.status).map(([k, v]) => html`<button type="button" class="menu-item" data-status="${k}">${icon(k === 'finished' ? 'check' : 'bookmark')} Move to ${v}</button>`)}
        ${item.source === 'gutenberg' && !onDevice ? html`<button type="button" class="menu-item" data-dl>${icon('download')} Download for offline reading</button>` : ''}
        ${onDevice && item.source !== 'import' ? html`<button type="button" class="menu-item" data-rmfile>${icon('trash')} Remove download <span class="sub">keeps it on your shelf</span></button>` : ''}
        <button type="button" class="menu-item danger" data-remove>${icon('trash')} Remove from shelf</button>
        ${item.lastOpenedAt ? html`<p class="small faint" style="padding:8px 12px">Last opened ${timeAgo(item.lastOpenedAt)}</p>` : ''}
      </div>`,
    });
    d.body.addEventListener('click', async (e) => {
      const t = e.target.closest('button, a');
      if (!t) return;
      if (t.tagName === 'A') { d.close(); return; }
      if (t.dataset.status) { await store.updateShelf(item.key, { status: t.dataset.status }); d.close(); toast(`Moved to ${store.STATUSES[t.dataset.status]}.`); paint(); }
      if (t.hasAttribute('data-dl')) {
        d.close();
        const stop = toast(`Downloading “${item.title}”…`, { timeout: 0 });
        try {
          const blob = await downloadGutenbergEpub(item.sourceId);
          await store.saveFile(item.key, blob, 'application/epub+zip');
          await store.updateShelf(item.key, { format: 'epub', fileSize: blob.size });
          stop(); toast(`“${item.title}” is on this device.`);
        } catch (err) { stop(); toast(`Download failed: ${err.message}`, { tone: 'error', timeout: 9000 }); }
        paint();
      }
      if (t.hasAttribute('data-rmfile')) {
        d.close();
        if (await confirmDialog({ title: 'Remove the download?', message: 'The file will be deleted from this device. The book stays on your shelf with your progress and notes.', confirmLabel: 'Remove download' })) {
          await store.deleteFile(item.key); toast('Download removed.'); paint();
        }
      }
      if (t.hasAttribute('data-remove')) {
        d.close();
        const imported = item.source === 'import';
        const ok = await confirmDialog({
          title: 'Remove from your shelf?',
          message: imported
            ? `“${item.title}” and its imported file will be deleted from this device${currentUser() ? ' and removed from your shelf on other devices' : ''}. This can't be undone; you'd need to import the file again.`
            : `“${item.title}” will be removed from your shelf${onDevice ? ' and its download deleted' : ''}. Bookmarks and notes are kept in case you add it back.`,
          confirmLabel: 'Remove',
        });
        if (!ok) return;
        await store.removeFromShelf(item.key);
        if (imported) await store.setCache(`cover|${store.getOwner()}|${item.key}`, null);
        toast(`Removed “${item.title}”.`, imported ? {} : { action: { label: 'Undo', run: async () => { await store.saveToShelf(item, { status: item.status }); paint(); } } });
        paint();
      }
    });
  }

  const unsub = store.onChange((e) => { if (['shelf', 'files', 'remote', 'owner', 'progress'].includes(e.type)) paint(); });
  await paint();
  return () => unsub();
}
