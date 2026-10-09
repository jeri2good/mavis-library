import { html, icon, esc, langName } from './lib/ui.js';

const CLOTH = ['#2c4a3c', '#6b2e2a', '#26395a', '#7a5a1e', '#4b3557', '#3c4a4f', '#5a3e2b', '#2f5550', '#4f2f3d'];
function hash(s) { let h = 2166136261; for (const c of String(s)) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0; }

export function generatedCover(book) {
  const cloth = CLOTH[hash(book.key || book.title) % CLOTH.length];
  return html`<div class="cover-gen" style="--cloth:${cloth}" aria-hidden="true">
      <div class="ct">${book.title}</div>
      <div><div class="rule"></div><div class="ca">${(book.authors || [])[0] || ''}</div></div>
    </div>`;
}

/**
 * Cover image with a typographic fallback. Broken images are swapped for the
 * fallback by a document-level error listener (inline handlers are blocked by
 * the content security policy).
 */
export function cover(book, { localCover = null, eager = false } = {}) {
  const src = localCover || book.coverUrl;
  return html`<div class="cover">${src
    ? html`<img src="${src}" alt="" ${eager ? '' : html`loading="lazy"`} decoding="async" referrerpolicy="no-referrer" data-cover-fallback>${generatedCover(book)}`
    : generatedCover(book)}</div>`;
}

export function installCoverFallback() {
  document.addEventListener('error', (e) => {
    const img = e.target;
    if (img instanceof HTMLImageElement && img.hasAttribute('data-cover-fallback')) img.remove();
  }, true);
  // Images are stacked above their fallback; reveal fallback only when broken.
}

export function bookCard(book, { href, badges = [], progress = null, localCover = null, extra = '' } = {}) {
  const author = (book.authors || []).join(', ');
  return html`<a class="book-card" href="${href || `#/book/${encodeURIComponent(book.key)}`}" aria-label="${book.title}${author ? `, by ${author}` : ''}">
    ${cover(book, { localCover })}
    ${progress != null ? html`<div class="progress-line" aria-hidden="true"><span style="width:${Math.round(progress * 100)}%"></span></div>` : ''}
    <div class="meta">
      <span class="t">${book.title}</span>
      ${author ? html`<span class="a">${author}</span>` : ''}
      ${badges.length ? html`<span class="tags">${badges.map((b) => html`<span class="badge ${b.tone ? `badge-${b.tone}` : ''}">${b.label}</span>`)}</span>` : ''}
    </div>
    ${extra}
  </a>`;
}

export function sourceBadges(book) {
  const out = [];
  if (book.source === 'gutenberg') out.push({ label: 'Free · Gutenberg', tone: 'ok' });
  if (book.source === 'openlibrary') out.push({ label: 'Open Library' });
  const lang = (book.languages || [])[0];
  if (lang && lang !== 'en' && lang !== 'eng') out.push({ label: langName(lang.slice(0, 2)) || lang });
  return out;
}

export function skeletonGrid(n = 8, layout = 'row') {
  const item = html`<div aria-hidden="true"><div class="skeleton cover-sk"></div><div class="skeleton line"></div><div class="skeleton line short"></div></div>`;
  return html`<div class="${layout}" role="status" aria-label="Loading books">${Array.from({ length: n }, () => item)}</div>`;
}

export function stateBlock({ title, text, actions = '', tone = '' }) {
  return html`<div class="state ${tone}" ${tone === 'error' ? html`role="alert"` : ''}>
    ${tone === 'error' ? icon('info') : ''}
    <h3>${title}</h3>
    ${text ? html`<p>${text}</p>` : ''}
    ${actions ? html`<div class="actions" style="display:flex;gap:8px;flex-wrap:wrap">${actions}</div>` : ''}
  </div>`;
}

export function brandMark() {
  // Open book with a bookmark ribbon — the app icon in miniature.
  return html`<svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
    <rect x="1" y="1" width="30" height="30" rx="8" fill="var(--primary)"/>
    <path d="M7 10.2c3-1.2 6-1.1 9 .8 3-1.9 6-2 9-.8v12.4c-3-1.1-6-1-9 .8-3-1.8-6-1.9-9-.8z" fill="none" stroke="var(--on-primary)" stroke-width="1.6" stroke-linejoin="round"/>
    <path d="M16 11v12.4" stroke="var(--on-primary)" stroke-width="1.6"/>
    <path d="M20 9.6v6l1.6-1.2 1.6 1.2v-6.6" fill="var(--accent)" stroke="none"/>
  </svg>`;
}

export const escapeAttr = esc;
