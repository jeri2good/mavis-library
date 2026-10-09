// Real published covers (Open Library, Google Books) for books in view.
// Lookups go through /api/covers, run a few at a time, only for covers on
// screen, and are remembered on the device.

import { getCache, setCache } from './store.js';

const MISS_TTL = 7 * 24 * 3600e3;
const mem = new Map();
const queue = [];
let active = 0;
const MAX = 3;

function cacheId(key) { return `realcover|${key}`; }

export async function lookupCover({ key, title, author, isbn }) {
  if (!key || key.startsWith('import:')) return null;
  if (mem.has(key)) return mem.get(key);
  const cached = await getCache(cacheId(key)).catch(() => null);
  if (cached && (cached.url || Date.now() - cached.at < MISS_TTL)) { mem.set(key, cached.url); return cached.url; }
  if (!navigator.onLine) return null;
  const p = new Promise((resolve) => queue.push({ key, title, author, isbn, resolve }));
  pump();
  const url = await p;
  mem.set(key, url);
  return url;
}

function pump() {
  while (active < MAX && queue.length) {
    const job = queue.shift();
    active++;
    (async () => {
      let url = null;
      try {
        const q = new URLSearchParams();
        if (job.title) q.set('title', job.title);
        if (job.author) q.set('author', job.author);
        if (job.isbn) q.set('isbn', job.isbn);
        const r = await fetch(`/api/covers?${q}`);
        if (r.ok) url = (await r.json()).cover || null;
        await setCache(cacheId(job.key), { url, at: Date.now() });
      } catch { /* leave uncached so it retries later */ }
      job.resolve(url);
    })().finally(() => { active--; pump(); });
  }
}

let io = null;
function observer() {
  if (io || !('IntersectionObserver' in window)) return io;
  io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (!e.isIntersecting) continue;
      io.unobserve(e.target);
      upgrade(e.target);
    }
  }, { rootMargin: '400px' });
  return io;
}

async function upgrade(el) {
  const { key, title, author, isbn } = el.dataset;
  const url = await lookupCover({ key, title, author, isbn });
  if (!url || !el.isConnected) return;
  const current = el.querySelector('img');
  if (current?.src === url) return;
  const img = new Image();
  img.alt = '';
  img.decoding = 'async';
  img.referrerPolicy = 'no-referrer';
  img.className = 'real-cover';
  img.setAttribute('data-cover-fallback', '');
  img.onload = () => {
    // Ignore 1×1 "no cover" placeholders some services return.
    if (img.naturalWidth < 20) { img.remove(); return; }
    current?.remove();
    el.classList.add('has-real-cover');
  };
  img.src = url;
  el.appendChild(img);
}

/** Find covers in `root` that can be upgraded and watch them. */
export function enhanceCovers(root = document) {
  const els = root.querySelectorAll('.cover[data-key]:not([data-cover-watch])');
  const obs = observer();
  for (const el of els) {
    el.setAttribute('data-cover-watch', '');
    if (mem.has(el.dataset.key)) { upgrade(el); continue; }
    if (obs) obs.observe(el); else upgrade(el);
  }
}
