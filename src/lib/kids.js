// Kids mode: a simpler, bigger, children's-books-only Mavis for young readers.
// A grown-up turns it on with a 4-digit PIN and needs the PIN to leave it.
// The PIN is a convenience lock kept on this device (hashed), not a security
// boundary — a determined child with browser settings can clear site data.

import * as store from './store.js';
import { localDate } from './plans.js';
import { openDialog, html } from './ui.js';

/** Children's classics from Project Gutenberg (ids checked against the live catalog). */
export const KIDS_SHELF = [14838, 11757, 67098, 11, 55, 16, 289, 113, 2781, 236, 271, 1874, 902, 146, 1448, 500, 11339, 2591, 120, 45, 23661];
// Gutenberg's subject search is loose: "children's literature" also returns Tess and Bleak House.
// Library-catalog "Juvenile" headings (Juvenile fiction, Juvenile literature) mark real children's books.
export const KIDS_TOPIC = 'juvenile';

/**
 * Which shelf books a child sees in kids mode: ones added in kids mode, the
 * story shelf, and books cataloged for children. A light filter, not a
 * guarantee — a grown-up's shelf on the same device is otherwise hidden.
 */
export function kidsBook(b) {
  if (!b) return false;
  if (b.kids) return true;
  if (b.source === 'gutenberg' && KIDS_SHELF.includes(Number(b.sourceId))) return true;
  return [...(b.subjects || []), ...(b.bookshelves || [])].some((s) => /juvenile|children/i.test(String(s)));
}

export const isKids = () => store.getSetting('kidsMode', false) === true;
export const kidsName = () => store.getSetting('kidsName', '') || '';
export const dailyGoal = () => Number(store.getSetting('kidsGoal', 15)) || 15;

const listeners = new Set();
export function onKids(fn) { listeners.add(fn); return () => listeners.delete(fn); }

async function hashPin(pin, salt) {
  const data = new TextEncoder().encode(`mavis-kids|${salt}|${pin}`);
  const buf = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export const validPin = (pin) => /^\d{4}$/.test(String(pin || ''));

export function applyKidsClass(on = isKids()) {
  document.documentElement.classList.toggle('kids', on);
}

/** Turn kids mode on, setting the grown-up PIN. */
export async function enterKids({ pin, name = '', goal = 15 }) {
  if (!validPin(pin)) throw new Error('Choose a 4-digit PIN.');
  const salt = [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, '0')).join('');
  await store.setSetting('kidsPin', { salt, hash: await hashPin(pin, salt) });
  await store.setSetting('kidsName', String(name || '').trim().slice(0, 30));
  await store.setSetting('kidsGoal', Math.min(120, Math.max(5, Number(goal) || 15)));
  await store.setSetting('kidsMode', true);
  applyKidsClass(true);
  for (const fn of listeners) fn(true);
}

// A few wrong guesses in a row slow things down, so a PIN can't be found by tapping through.
let misses = 0;
let lockedUntil = 0;
export async function checkPin(pin) {
  if (Date.now() < lockedUntil) throw new Error(`Too many tries. Wait ${Math.ceil((lockedUntil - Date.now()) / 1000)} seconds.`);
  const saved = store.getSetting('kidsPin', null);
  if (!saved?.hash) return true;
  const ok = (await hashPin(String(pin || ''), saved.salt)) === saved.hash;
  if (ok) { misses = 0; return true; }
  misses++;
  if (misses >= 3) { lockedUntil = Date.now() + 30_000 * (misses - 2); }
  return false;
}

// Grown-up pages (settings, account) open for 10 minutes after the PIN.
let grownUpUntil = 0;
export const grownUpUnlocked = () => !isKids() || Date.now() < grownUpUntil;
export async function unlockGrownUp(pin) {
  if (!(await checkPin(pin))) return false;
  grownUpUntil = Date.now() + 10 * 60_000;
  return true;
}
export function lockGrownUp() { grownUpUntil = 0; }

/** Ask for the grown-up PIN. Resolves true when it's right. */
export async function askPin({ title = 'Grown-ups only', message = 'Enter the 4-digit PIN to continue.' } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const d = openDialog({
      title,
      onClose: () => { if (!done) resolve(false); },
      body: html`<p class="muted">${message}</p>
        <form id="pin-form" class="field" autocomplete="off"><label for="pin-in">PIN</label>
          <input class="input pin-input" id="pin-in" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" autofocus />
          <p class="small" id="pin-err" role="alert" style="color:var(--danger);min-height:1.2em"></p>
          <div class="dialog-actions"><button type="button" class="btn btn-quiet" data-cancel>Cancel</button><button class="btn btn-primary" type="submit">Continue</button></div></form>`,
    });
    d.body.querySelector('[data-cancel]').addEventListener('click', () => d.close());
    d.body.querySelector('#pin-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const input = d.body.querySelector('#pin-in');
      const err = d.body.querySelector('#pin-err');
      try {
        if (await unlockGrownUp(input.value)) { done = true; d.close(); resolve(true); return; }
        err.textContent = 'That PIN isn’t right.';
      } catch (ex) { err.textContent = ex.message; }
      input.value = ''; input.focus();
    });
  });
}

export async function exitKids(pin) {
  if (!(await checkPin(pin))) throw new Error('That PIN isn’t right.');
  await store.setSetting('kidsMode', false);
  applyKidsClass(false);
  for (const fn of listeners) fn(false);
}

// ---------- Reading time (kept on this device, per reader) ----------

const logKey = () => `readlog|${store.getOwner()}`;
export function readingLog() { return store.getSetting(logKey(), {}) || {}; }

export async function addReadingTime(ms) {
  if (!(ms > 0)) return;
  const log = { ...readingLog() };
  const d = localDate();
  log[d] = Math.round(((log[d] || 0) + ms / 60000) * 100) / 100;
  // Keep about four months.
  const keys = Object.keys(log).sort();
  for (const k of keys.slice(0, Math.max(0, keys.length - 120))) delete log[k];
  await store.setSetting(logKey(), log);
}

export const minutesToday = () => Math.floor(readingLog()[localDate()] || 0);

/** Days in a row (ending today, or yesterday if today isn't done yet) that met the goal. */
export function goalStreak(goal = dailyGoal()) {
  const log = readingLog();
  const d = new Date();
  let n = 0;
  if ((log[localDate(d)] || 0) < goal) d.setDate(d.getDate() - 1);
  while ((log[localDate(d)] || 0) >= goal) { n++; d.setDate(d.getDate() - 1); }
  return n;
}

/** The last 7 days, oldest first: [{ date, minutes, star }]. */
export function lastWeek(goal = dailyGoal()) {
  const log = readingLog();
  const out = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i);
    const date = localDate(d);
    out.push({ date, day: d.toLocaleDateString([], { weekday: 'short' }), minutes: Math.floor(log[date] || 0), star: (log[date] || 0) >= goal });
  }
  return out;
}

/**
 * Counts reading time while the page is visible and the reader has
 * interacted in the last 2 minutes (so a book left open doesn't count).
 */
export function trackReading() {
  let last = Date.now();
  let active = Date.now();
  const poke = () => { active = Date.now(); };
  const tick = () => {
    const t = Date.now();
    if (document.visibilityState === 'visible' && t - active < 120_000) addReadingTime(Math.min(t - last, 60_000));
    last = t;
  };
  const timer = setInterval(tick, 30_000);
  const evs = ['pointerdown', 'keydown', 'wheel', 'touchstart'];
  for (const e of evs) window.addEventListener(e, poke, { passive: true, capture: true });
  const onVis = () => { if (document.visibilityState === 'hidden') tick(); else last = Date.now(); };
  document.addEventListener('visibilitychange', onVis);
  return {
    poke,
    stop() { tick(); clearInterval(timer); for (const e of evs) window.removeEventListener(e, poke, { capture: true }); document.removeEventListener('visibilitychange', onVis); },
  };
}
