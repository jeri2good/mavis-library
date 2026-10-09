// Small UI toolkit: safe HTML templating, icons, toasts, accessible dialogs.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

class Raw { constructor(s) { this.s = s; } toString() { return this.s; } }
export const raw = (s) => new Raw(String(s));

/** Tagged template that escapes every interpolation unless wrapped in raw(). */
export function html(strings, ...values) {
  let out = '';
  strings.forEach((str, i) => {
    out += str;
    if (i < values.length) out += render(values[i]);
  });
  return raw(out);
}
function render(v) {
  if (v == null || v === false) return '';
  if (v instanceof Raw) return v.s;
  if (Array.isArray(v)) return v.map(render).join('');
  return esc(v);
}

export function $(sel, root = document) { return root.querySelector(sel); }
export function $$(sel, root = document) { return [...root.querySelectorAll(sel)]; }

// ---------- Icons (one consistent 24px, 1.6 stroke family) ----------

const P = {
  home: '<path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z"/>',
  compass: '<circle cx="12" cy="12" r="8.5"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>',
  shelf: '<path d="M5 4v16M9.5 4v16M14 5l4.5 14.5"/><path d="M3 20h18"/>',
  user: '<circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20c1.3-3.6 4.1-5.4 7.5-5.4s6.2 1.8 7.5 5.4"/>',
  settings: '<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2.2"/><circle cx="9" cy="17" r="2.2"/>',
  mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  back: '<path d="M15 5 8 12l7 7"/>',
  chevronR: '<path d="m9 5 7 7-7 7"/>',
  chevronL: '<path d="M15 5 8 12l7 7"/>',
  toc: '<path d="M8 6.5h12M8 12h12M8 17.5h12"/><circle cx="4.5" cy="6.5" r=".9"/><circle cx="4.5" cy="12" r=".9"/><circle cx="4.5" cy="17.5" r=".9"/>',
  bookmark: '<path d="M7 3.5h10a1 1 0 0 1 1 1V21l-6-4-6 4V4.5a1 1 0 0 1 1-1z"/>',
  bookmarkFill: '<path fill="currentColor" d="M7 3.5h10a1 1 0 0 1 1 1V21l-6-4-6 4V4.5a1 1 0 0 1 1-1z"/>',
  type: '<path d="M4 19 9 5l5 14M5.8 14h6.4M15 19l3-8 3 8M15.9 16.6h4.2"/>',
  headphones: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3.5" y="14" width="4" height="6" rx="1.4"/><rect x="16.5" y="14" width="4" height="6" rx="1.4"/>',
  expand: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  collapse: '<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>',
  play: '<path fill="currentColor" stroke="none" d="M8 5.5v13a.8.8 0 0 0 1.2.7l10.3-6.5a.8.8 0 0 0 0-1.4L9.2 4.8A.8.8 0 0 0 8 5.5z"/>',
  pause: '<path fill="currentColor" stroke="none" d="M7 5h3.2v14H7zM13.8 5H17v14h-3.2z"/>',
  stop: '<rect fill="currentColor" stroke="none" x="6.5" y="6.5" width="11" height="11" rx="1.5"/>',
  skipF: '<path d="M5 6l8 6-8 6zM15 6v12M19 6v12"/>',
  skipB: '<path d="M19 6l-8 6 8 6zM9 6v12M5 6v12"/>',
  download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 19.5h14"/>',
  upload: '<path d="M12 16V5M7 9.5l5-5 5 5M5 19.5h14"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M4.5 7h15M10 4h4M6.5 7l1 13h9l1-13M10 11v6M14 11v6"/>',
  external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>',
  library: '<path d="M3.5 9 12 4.5 20.5 9M5 9.5V18M9.7 9.5V18M14.3 9.5V18M19 9.5V18M3.5 20h17"/>',
  cart: '<path d="M3.5 4.5h2.2l2 11h10.5l2-7.8H6.6"/><circle cx="9" cy="19.2" r="1.2"/><circle cx="17" cy="19.2" r="1.2"/>',
  highlight: '<path d="m14.5 4.5 5 5-8.5 8.5H6v-5z"/><path d="M4 20.5h16"/>',
  note: '<path d="M5 4.5h14v10l-5 5H5z"/><path d="M14 19.5v-5h5M8.5 9h7M8.5 12.5h4"/>',
  book: '<path d="M4.5 5.5c2.6-1 5.2-1 7.5.6 2.3-1.6 4.9-1.6 7.5-.6V19c-2.6-1-5.2-1-7.5.6-2.3-1.6-4.9-1.6-7.5-.6z"/><path d="M12 6.1v13.5"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="1.5"/><path d="M15.5 8.5V5a1 1 0 0 0-1-1h-9.5a1 1 0 0 0-1 1v9.5a1 1 0 0 0 1 1h3.5"/>',
  dict: '<path d="M6 4.5h11.5V20H6.8A1.8 1.8 0 0 1 5 18.2V5.5a1 1 0 0 1 1-1z"/><path d="M5 17.5c0-1 .8-1.6 1.8-1.6h10.7M9 8.5h5"/>',
  wifiOff: '<path d="M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 12.8a10 10 0 0 1 4.2-2.6M14.8 10.2A10 10 0 0 1 19 12.8M2 9.3a15 15 0 0 1 4.2-2.7M11 5.5a15 15 0 0 1 11 3.8"/><circle cx="12" cy="20" r=".6"/>',
  more: '<circle cx="5.5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="18.5" cy="12" r="1.3"/>',
  refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4"/>',
  timer: '<circle cx="12" cy="13" r="7.5"/><path d="M12 9v4.2l2.6 1.6M9.5 3h5"/>',
  globe: '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.3 2.4 3.4 5.2 3.4 8.5s-1.1 6.1-3.4 8.5c-2.3-2.4-3.4-5.2-3.4-8.5s1.1-6.1 3.4-8.5z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"/>',
  moon: '<path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z"/>',
  filter: '<path d="M4 6h16M7 12h10M10 18h4"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.2"/>',
  file: '<path d="M6 3.5h8l4.5 4.5v12.5H6z"/><path d="M14 3.5V8h4.5"/>',
  logout: '<path d="M14 4.5h4.5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H14M10 8l-4 4 4 4M6 12h10"/>',
};
export function icon(name, { size = 22, label = '' } = {}) {
  const a11y = label ? `role="img" aria-label="${esc(label)}"` : 'aria-hidden="true" focusable="false"';
  return raw(`<svg class="icon" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" ${a11y}>${P[name] || ''}</svg>`);
}

// ---------- Toasts ----------

export function toast(message, { tone = 'info', action = null, timeout = 4500 } = {}) {
  const host = document.getElementById('toasts');
  if (!host) return;
  const el = document.createElement('div');
  el.className = `toast toast-${tone}`;
  el.innerHTML = String(html`<span class="toast-msg">${message}</span>${action ? html`<button type="button" class="toast-action">${action.label}</button>` : ''}<button type="button" class="toast-close" aria-label="Dismiss">${icon('close', { size: 16 })}</button>`);
  const remove = () => { el.classList.add('leaving'); setTimeout(() => el.remove(), 220); };
  el.querySelector('.toast-close').addEventListener('click', remove);
  if (action) el.querySelector('.toast-action').addEventListener('click', () => { remove(); action.run(); });
  host.appendChild(el);
  // Keep the stack short: drop the oldest when more than three are showing.
  const live = [...host.querySelectorAll('.toast:not(.leaving)')];
  for (const old of live.slice(0, Math.max(0, live.length - 3))) { old.classList.add('leaving'); setTimeout(() => old.remove(), 220); }
  if (timeout) setTimeout(remove, action ? timeout + 3000 : timeout);
  return remove;
}

// ---------- Dialogs / sheets with focus management ----------

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
const openStack = [];

/**
 * Open a modal dialog or side/bottom sheet. Returns { el, close }.
 * Focus moves into the dialog, is trapped there, Escape closes it, and focus
 * returns to the element that opened it.
 */
export function openDialog({ title, body, variant = 'dialog', onClose, labelledBy, className = '', dismissible = true, container = document.body }) {
  const opener = document.activeElement;
  const openerKey = (() => {
    if (!opener || opener === document.body) return null;
    if (opener.id) return `#${CSS.escape(opener.id)}`;
    const d = [...opener.attributes].find((a) => a.name.startsWith('data-') && a.value);
    return d ? `[${d.name}="${CSS.escape(d.value)}"]` : null;
  })();
  const wrap = document.createElement('div');
  wrap.className = `overlay overlay-${variant}`;
  const titleId = labelledBy || `dlg-${Math.random().toString(36).slice(2, 8)}`;
  wrap.innerHTML = String(html`
    <div class="scrim" data-close></div>
    <div class="dialog dialog-${variant} ${className}" role="dialog" aria-modal="true" aria-labelledby="${titleId}" tabindex="-1">
      <header class="dialog-head">
        <h2 class="dialog-title" id="${titleId}">${title}</h2>
        ${dismissible ? html`<button type="button" class="icon-btn" data-close aria-label="Close">${icon('close')}</button>` : ''}
      </header>
      <div class="dialog-body"></div>
    </div>`);
  const bodyEl = wrap.querySelector('.dialog-body');
  if (typeof body === 'string' || body instanceof Raw) bodyEl.innerHTML = String(body);
  else if (body instanceof Node) bodyEl.appendChild(body);
  container.appendChild(wrap);
  document.body.classList.add('has-overlay');
  const dlg = wrap.querySelector('.dialog');
  requestAnimationFrame(() => wrap.classList.add('open'));

  let closed = false;
  const close = (reason) => {
    if (closed) return;
    closed = true;
    wrap.classList.remove('open');
    wrap.removeEventListener('keydown', onKey);
    const idx = openStack.indexOf(api);
    if (idx >= 0) openStack.splice(idx, 1);
    setTimeout(() => {
      wrap.remove();
      if (!openStack.length) document.body.classList.remove('has-overlay');
    }, 200);
    // Return focus to the opener, or to its replacement if the screen re-rendered.
    let back = opener && document.contains(opener) ? opener : null;
    if (!back && openerKey) back = document.querySelector(openerKey);
    back?.focus({ preventScroll: true });
    onClose?.(reason);
  };
  const onKey = (e) => {
    if (e.key === 'Escape' && dismissible) { e.stopPropagation(); close('escape'); return; }
    if (e.key !== 'Tab') return;
    const items = [...dlg.querySelectorAll(FOCUSABLE)].filter((x) => x.offsetParent !== null || x === document.activeElement);
    if (!items.length) { e.preventDefault(); dlg.focus(); return; }
    const first = items[0], last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  wrap.addEventListener('keydown', onKey);
  wrap.addEventListener('click', (e) => { if (dismissible && e.target.closest('[data-close]')) close('dismiss'); });
  const api = { el: dlg, body: bodyEl, close, dismissible };
  openStack.push(api);
  setTimeout(() => {
    if (closed) return; // closed before focus moved in (e.g. a quick Escape)
    const target = dlg.querySelector('[autofocus]') || dlg.querySelector('.dialog-body ' + FOCUSABLE) || dlg;
    target.focus({ preventScroll: true });
  }, 30);
  return api;
}

// Escape closes the top dialog even if focus hasn't moved into it yet
// (for example when focus is inside the reader's book frame).
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape' || !openStack.length) return;
  const top = openStack[openStack.length - 1];
  if (top.el.contains(document.activeElement)) return; // handled by the dialog itself
  if (top.dismissible) { e.preventDefault(); top.close('escape'); }
});

export function closeAllDialogs() { [...openStack].forEach((d) => d.close('route')); }
export function hasOpenDialog() { return openStack.length > 0; }

/** In-page confirmation (native confirm() is avoided on purpose). */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', tone = 'danger', cancelLabel = 'Cancel' }) {
  return new Promise((resolve) => {
    let result = false;
    const d = openDialog({
      title,
      body: html`<p class="dialog-text">${message}</p>
        <div class="dialog-actions">
          <button type="button" class="btn btn-quiet" data-act="cancel">${cancelLabel}</button>
          <button type="button" class="btn ${tone === 'danger' ? 'btn-danger' : 'btn-primary'}" data-act="ok" autofocus>${confirmLabel}</button>
        </div>`,
      onClose: () => resolve(result),
    });
    d.body.addEventListener('click', (e) => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'ok') { result = true; d.close('ok'); }
      if (act === 'cancel') d.close('cancel');
    });
  });
}

// ---------- Misc ----------

export function debounce(fn, ms) {
  let t;
  const d = (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  d.flush = (...args) => { clearTimeout(t); fn(...args); };
  d.cancel = () => clearTimeout(t);
  return d;
}

export function formatBytes(n) {
  if (!Number.isFinite(n)) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

export function timeAgo(ms) {
  if (!ms) return '';
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return 'just now';
  const m = Math.round(s / 60); if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60); if (h < 24) return `${h} hr ago`;
  const d = Math.round(h / 24); if (d < 30) return `${d} day${d === 1 ? '' : 's'} ago`;
  return new Date(ms).toLocaleDateString();
}

export function prefersReducedMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

export const LANGS = {
  en: 'English', fr: 'French', de: 'German', es: 'Spanish', it: 'Italian', pt: 'Portuguese', nl: 'Dutch',
  fi: 'Finnish', sv: 'Swedish', da: 'Danish', no: 'Norwegian', la: 'Latin', el: 'Greek', ru: 'Russian',
  zh: 'Chinese', ja: 'Japanese', pl: 'Polish', hu: 'Hungarian', eo: 'Esperanto', ca: 'Catalan', tl: 'Tagalog',
  cy: 'Welsh', ga: 'Irish', he: 'Hebrew', ar: 'Arabic', cs: 'Czech', ko: 'Korean', af: 'Afrikaans',
};
export const langName = (code) => LANGS[code] || (code ? code.toUpperCase() : '');
