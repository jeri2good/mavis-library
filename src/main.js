import './styles/app.css';
import { installAppFonts } from './lib/fonts.js';
import * as store from './lib/store.js';
import { initAuth, onAuth, currentUser } from './lib/auth.js';
import { startSync, stopSync, onSyncState, syncNow, syncState as syncStateNow } from './lib/sync.js';
import { html, icon, toast, closeAllDialogs, $, confirmDialog } from './lib/ui.js';
import { brandMark, installCoverFallback } from './components.js';
import { enhanceCovers } from './lib/covers.js';
import { StorageUnavailableError } from './lib/idb.js';
import { isKids, applyKidsClass, onKids, grownUpUnlocked, askPin, kidsName } from './lib/kids.js';
import { watchProgress, forgetCache as forgetGroups } from './lib/groups.js';

const routes = {
  '': () => import('./views/home.js'),
  discover: () => import('./views/home.js'),
  search: () => import('./views/search.js'),
  book: () => import('./views/book.js'),
  shelf: () => import('./views/shelf.js'),
  read: () => import('./views/reader.js'),
  account: () => import('./views/account.js'),
  bible: () => import('./views/bible.js'),
  quotes: () => import('./views/quotes.js'),
  listen: () => import('./views/listen.js'),
  settings: () => import('./views/settings.js'),
  words: () => import('./views/words.js'),
  groups: () => import('./views/groups.js'),
};

const NAV = [
  { id: 'discover', label: 'Discover', icon: 'compass', href: '#/' },
  { id: 'search', label: 'Search', icon: 'search', href: '#/search' },
  { id: 'bible', label: 'Bible', icon: 'cross', href: '#/bible' },
  { id: 'shelf', label: 'My shelf', icon: 'shelf', href: '#/shelf' },
  { id: 'account', label: 'Account', icon: 'user', href: '#/account' },
];
// Kids mode swaps Account for the word builder; grown-up screens need the PIN.
const KIDS_NAV = [
  { id: 'discover', label: 'Home', icon: 'home', href: '#/' },
  { id: 'search', label: 'Find books', icon: 'search', href: '#/search' },
  { id: 'words', label: 'My words', icon: 'dict', href: '#/words' },
  { id: 'bible', label: 'Bible', icon: 'cross', href: '#/bible' },
  { id: 'shelf', label: 'My books', icon: 'shelf', href: '#/shelf' },
];
const GROWN_UP = new Set(['settings', 'account', 'groups']);

export function parseRoute(hash = location.hash) {
  const raw = hash.replace(/^#\/?/, '');
  const [pathPart, query = ''] = raw.split('?');
  const segs = pathPart.split('/').filter(Boolean).map((s) => { try { return decodeURIComponent(s); } catch { return s; } });
  return { name: segs[0] || '', segs: segs.slice(1), params: new URLSearchParams(query) };
}

export function navigate(path, { replace = false } = {}) {
  const target = path.startsWith('#') ? path : `#${path}`;
  if (replace) { history.replaceState(null, '', target); renderRoute(); }
  else location.hash = target;
}

// ---------- Theme ----------
export function applyAppTheme(pref = store.getSetting('appTheme', 'system')) {
  const root = document.documentElement;
  if (pref === 'light' || pref === 'dark') root.dataset.theme = pref;
  else delete root.dataset.theme;
  const motion = store.getSetting('motion', 'system');
  root.classList.toggle('no-motion', motion === 'off');
}

// ---------- Shell ----------
function renderShell() {
  const app = document.getElementById('app');
  app.innerHTML = String(html`
    <div class="shell">
      <header class="topbar" id="topbar">
        <a class="brand" href="#/" aria-label="Mavis Library home">${brandMark()}<span class="brand-name">${isKids() ? (kidsName() ? `${kidsName()}’s Library` : 'Mavis Kids') : 'Mavis Library'}</span></a>
        <span class="spacer"></span>
        <span id="sync-pill"></span>
        ${isKids()
          ? html`<a class="btn btn-sm btn-quiet grownups" href="#/settings" aria-label="Grown-ups: settings (PIN needed)">${icon('settings', { size: 18 })} Grown-ups</a>`
          : html`<a class="icon-btn" href="#/settings" aria-label="Settings">${icon('settings')}</a>`}
      </header>
      <nav class="tabbar" aria-label="Main">
        ${(isKids() ? KIDS_NAV : NAV).map((n) => html`<a class="tab" data-nav="${n.id}" href="${n.href}">${icon(n.icon)}<span>${n.label}</span></a>`)}
      </nav>
      <main class="main" id="main" tabindex="-1">
        <div id="offline" class="offline-banner" hidden>${icon('wifiOff', { size: 18 })}<span>You're offline. Books saved on this device still open.</span></div>
        <div id="view"></div>
      </main>
    </div>
    <div id="reader-root"></div>`);
  setOnline();
  renderSyncPill(syncStateNow());
  if (shellWired) return;
  // Window listeners are added once; the shell can be redrawn (kids mode on/off).
  shellWired = true;
  window.addEventListener('scroll', () => $('#topbar')?.classList.toggle('scrolled', window.scrollY > 4), { passive: true });
  window.addEventListener('online', () => { setOnline(); toast('Back online.'); });
  window.addEventListener('offline', setOnline);
  onSyncState(renderSyncPill);
}
let shellWired = false;
function setOnline() { const off = $('#offline'); if (off) off.hidden = navigator.onLine; }

function renderSyncPill(s) {
  const el = $('#sync-pill');
  if (!el) return;
  if (!currentUser()) { el.innerHTML = ''; return; }
  const map = {
    syncing: ['busy', 'Syncing'],
    idle: ['ok', 'Synced'],
    error: ['err', 'Sync paused'],
    offline: ['', 'Offline'],
    off: ['', ''],
  };
  const [cls, label] = map[s.status] || ['', ''];
  el.innerHTML = label ? String(html`<button type="button" class="sync-pill ${cls}" data-sync title="${s.error || ''}"><span class="dot"></span>${label}</button>`) : '';
  el.querySelector('[data-sync]')?.addEventListener('click', () => {
    if (s.status === 'error') toast(`Sync paused: ${s.error}. Retrying now.`);
    syncNow();
  });
}

// ---------- Router ----------
let current = { cleanup: null, name: null, token: 0 };

export async function renderRoute() {
  const route = parseRoute();
  const token = ++current.token;
  closeAllDialogs();
  const loader = routes[route.name];
  if (!loader) { navigate('/', { replace: true }); return; }
  if (isKids() && GROWN_UP.has(route.name) && !grownUpUnlocked()) {
    const ok = await askPin({ message: 'Settings and accounts are for grown-ups. Enter the 4-digit PIN to continue.' });
    if (token !== current.token) return;
    if (!ok) { if (current.name) history.back(); else navigate('/', { replace: true }); return; }
  }

  for (const a of document.querySelectorAll('[data-nav]')) {
    const active = a.dataset.nav === (route.name || 'discover') || (route.name === 'book' && a.dataset.nav === 'search') || ((route.name === 'quotes' || route.name === 'listen') && a.dataset.nav === 'shelf') || ((route.name === 'words' || route.name === 'groups') && a.dataset.nav === 'shelf' && !isKids());
    if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  }

  try { await current.cleanup?.(); } catch (err) { console.error(err); }
  current.cleanup = null;

  const isReader = route.name === 'read';
  document.querySelector('.shell').toggleAttribute('inert', isReader);
  document.querySelector('.shell').setAttribute('aria-hidden', isReader ? 'true' : 'false');
  // Give every screen a fresh container so listeners from the previous
  // screen can never fire twice.
  const fresh = (id) => { const old = document.getElementById(id); const el = old.cloneNode(false); old.replaceWith(el); return el; };
  const target = isReader ? fresh('reader-root') : fresh('view');
  if (!isReader) fresh('reader-root');

  let mod;
  try { mod = await loader(); }
  catch (err) {
    if (token !== current.token) return;
    target.innerHTML = String(errorView('This part of Mavis failed to load', navigator.onLine ? 'Reload the page to get the latest version.' : "You're offline and this screen wasn't saved for offline use yet."));
    return;
  }
  if (token !== current.token) return;
  try {
    const cleanup = await mod.render(target, route, { navigate, token: () => current.token === token });
    if (token !== current.token) { cleanup?.(); return; }
    current.cleanup = cleanup || null;
    current.name = route.name;
    if (!isReader) {
      document.title = mod.title?.(route) ? `${mod.title(route)} · Mavis Library` : 'Mavis Library';
      if (!route.params.has('keepScroll') && !route.params.has('v')) window.scrollTo(0, 0);
      target.classList.add('view-enter');
      setTimeout(() => target.classList.remove('view-enter'), 600);
      const h1 = target.querySelector('h1');
      if (document.activeElement === document.body || !document.activeElement) (h1 || document.getElementById('main')).focus?.({ preventScroll: true });
    }
  } catch (err) {
    console.error(err);
    target.innerHTML = String(errorView('Something went wrong on this screen', err.message));
  }
}

function errorView(title, text) {
  return html`<div class="page" style="padding-top:24px"><div class="state error" role="alert"><h3>${title}</h3><p>${text || ''}</p>
    <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn btn-primary" type="button" data-reload>Reload</button><a class="btn" href="#/shelf">Go to my shelf</a></div></div></div>`;
}
document.addEventListener('click', (e) => { if (e.target.closest('[data-reload]')) location.reload(); });

// ---------- Accounts ----------
async function onSignedIn(user, { quiet = false } = {}) {
  const prev = store.getOwner();
  store.setOwner(user.id);
  startSync(user.id);
  if (prev === store.GUEST && !quiet) {
    const n = await store.guestItemCount();
    if (n > 0) {
      const ok = await confirmDialog({
        title: 'Bring your guest shelf along?',
        message: `You saved ${n} book${n === 1 ? '' : 's'} on this device before signing in. Move them, with progress, bookmarks, and notes, into your account so they sync to your other devices? Downloaded files stay on this device.`,
        confirmLabel: 'Move to my account', cancelLabel: 'Not now', tone: 'primary',
      });
      if (ok) {
        try {
          const moved = await store.migrateGuest(user.id);
          toast(`Moved ${moved} book${moved === 1 ? '' : 's'} into your account.`);
          syncNow();
        } catch (err) { toast(`Couldn't move your guest shelf: ${err.message}`, { tone: 'error' }); }
      }
    }
  }
}

// ---------- Boot ----------
async function boot() {
  installAppFonts();
  installCoverFallback();
  // Upgrade covers to real published artwork as they appear on screen.
  let coverTimer;
  new MutationObserver(() => { clearTimeout(coverTimer); coverTimer = setTimeout(() => enhanceCovers(document), 60); })
    .observe(document.getElementById('app'), { childList: true, subtree: true });
  try { await store.loadSettings(); }
  catch (err) { console.warn(err); }
  applyAppTheme();
  applyKidsClass();
  renderShell();
  onKids(() => { renderShell(); navigate('/', { replace: true }); });
  watchProgress();

  window.addEventListener('error', (e) => {
    if (e.message && !/ResizeObserver/.test(e.message)) console.error(e.error || e.message);
  });
  window.addEventListener('unhandledrejection', (e) => {
    const err = e.reason;
    if (err?.name === 'AbortError') return;
    console.error(err);
    if (err instanceof StorageUnavailableError) toast(err.message, { tone: 'error', timeout: 9000 });
  });

  onAuth(async ({ user: u, event }) => {
    if (event?.error) { toast(event.error, { tone: 'error', timeout: 9000 }); return; }
    if (event === 'SIGNED_IN' && u && store.getOwner() !== u.id) { await onSignedIn(u); renderRoute(); }
    if (event === 'SIGNED_OUT') { stopSync(); forgetGroups(); store.setOwner(store.GUEST); renderRoute(); }
  });
  // Restore a saved session right away (works offline), then confirm it with the server.
  if (currentUser()) await onSignedIn(currentUser(), { quiet: true });
  initAuth().then((user) => {
    if (!user && store.getOwner() !== store.GUEST) { stopSync(); store.setOwner(store.GUEST); renderRoute(); }
  }).catch((err) => console.warn('Accounts unavailable', err));

  window.addEventListener('hashchange', () => { window.__mavisNavDepth = (window.__mavisNavDepth || 0) + 1; renderRoute(); });
  await renderRoute();

  if ('serviceWorker' in navigator && import.meta.env.PROD) {
    try {
      const reg = await navigator.serviceWorker.register('/sw.js');
      reg.addEventListener('updatefound', () => {
        const w = reg.installing;
        w?.addEventListener('statechange', () => {
          if (w.state === 'installed' && navigator.serviceWorker.controller) {
            toast('A new version of Mavis is ready.', {
              action: {
                label: 'Refresh',
                run: () => {
                  navigator.serviceWorker.addEventListener('controllerchange', () => location.reload(), { once: true });
                  (reg.waiting || w).postMessage('skipWaiting');
                },
              },
              timeout: 12000,
            });
          }
        });
      });
    } catch (err) { console.warn('Offline support unavailable', err); }
  }
}

boot();
