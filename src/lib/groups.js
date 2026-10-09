// Book clubs and Bible study groups (client side). Needs a signed-in account;
// everything lives on the server (/api/groups) so every member sees the same
// discussion. Your reading position in the group's book (or day in its Bible
// plan) is shared with the group so members can see who's where and posts
// ahead of you stay hidden until you get there.

import { sessionToken, currentUser, sessionExpired } from './auth.js';
import * as store from './store.js';
import { accessCode } from './features.js';

export class GroupError extends Error {
  constructor(message, status, code) { super(message); this.status = status; this.code = code; }
}

export async function api(action, body = {}, { owner = false } = {}) {
  const token = sessionToken();
  if (!token || !currentUser()) throw new GroupError('Sign in to use groups.', 401, 'signed_out');
  let r;
  try {
    r = await fetch('/api/groups', {
      method: 'POST',
      headers: {
        'content-type': 'application/json', authorization: `Bearer ${token}`,
        ...(owner && { 'x-mavis-access': accessCode() }),
        ...(store.getSetting('kidsMode', false) === true && { 'x-mavis-kids': '1' }),
      },
      body: JSON.stringify({ action, ...body }),
      cache: 'no-store',
    });
  } catch { throw new GroupError('Couldn’t reach Mavis. Check your connection.', 0, 'offline'); }
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 && j.error === 'signed_out') sessionExpired();
  if (!r.ok) throw new GroupError(j.message || `The group service answered ${r.status}.`, r.status, j.error);
  return j;
}

// ---------- The signed-in reader's groups (cached on the device) ----------

const cacheKey = () => `groups|${currentUser()?.id || ''}`;
let mine = null;

export function cachedGroups() {
  if (!currentUser()) return [];
  return mine || store.getSetting(cacheKey(), []) || [];
}

export async function myGroups({ force = false } = {}) {
  if (!currentUser()) return [];
  if (mine && !force) return mine;
  const { groups } = await api('list');
  mine = groups;
  store.setSetting(cacheKey(), groups.map((x) => ({ id: x.id, name: x.name, kind: x.kind, book: x.book, planId: x.planId, startDate: x.startDate, members: x.members, posts: x.posts, lastPostAt: x.lastPostAt })));
  return groups;
}
export function forgetCache() { mine = null; }

export const groupsForBook = (bookKey) => cachedGroups().filter((x) => x.kind === 'book' && x.book?.key === bookKey);
export const bibleGroups = () => cachedGroups().filter((x) => x.kind === 'bible');

// ---------- Sharing progress ----------

const lastSent = new Map(); // gid → { sig, at }
async function send(gid, payload) {
  const sig = JSON.stringify(payload);
  const prev = lastSent.get(gid);
  if (prev && prev.sig === sig) return;
  if (prev && Date.now() - prev.at < 60_000) { setTimeout(() => shareProgressNow(), 61_000 - (Date.now() - prev.at)); return; }
  lastSent.set(gid, { sig, at: Date.now() });
  try { await api('progress', { gid, ...payload }); } catch { lastSent.delete(gid); }
}

/** Plan progress for a Bible group: { day (1-based, scheduled today), done }. */
export async function planProgress(planId) {
  if (!planId) return null;
  const row = await store.getRecord('plans', planId);
  if (!row) return null;
  const { loadIndex } = await import('./bible.js');
  const { status } = await import('./plans.js');
  const st = status(await loadIndex(), row);
  return { day: st.scheduled + 1, done: st.doneCount, total: st.total };
}

export async function shareProgressNow() {
  if (!currentUser() || !navigator.onLine) return;
  for (const gr of cachedGroups()) {
    if (gr.kind === 'book' && gr.book?.key) {
      const p = await store.getProgress(gr.book.key).catch(() => null);
      if (p?.percent != null) send(gr.id, { percent: Math.round(p.percent * 1000) / 1000, chapter: p.chapter || '' });
    } else if (gr.kind === 'bible' && gr.planId) {
      const pp = await planProgress(gr.planId);
      if (pp) send(gr.id, { plan: { day: pp.day, done: pp.done } });
    }
  }
}

let timer = null;
/** Called once at start-up: share progress a little after it changes. */
export function watchProgress() {
  store.onChange((d) => {
    if (d.type !== 'progress' && d.type !== 'plans') return;
    if (d.type === 'progress' && !groupsForBook(d.key).length) return;
    clearTimeout(timer);
    timer = setTimeout(shareProgressNow, 4000);
  });
}

// ---------- Display helpers ----------

/** Is this post past where the reader is? (Spoiler protection for book groups.) */
// Someone who hasn't opened the book yet counts as at the start; a 2% margin keeps "welcome" posts visible.
export function isAhead(post, myPercent) {
  if (post.percent == null) return false;
  return post.percent > (myPercent ?? 0) + 0.02;
}

export function inviteLink(code) {
  return `${location.origin}/#/groups/join?code=${encodeURIComponent(code)}`;
}

export const defaultDisplayName = () => {
  const saved = store.getSetting('groupName', '');
  if (saved) return saved;
  const e = currentUser()?.email || '';
  const local = e.split('@')[0].replace(/[._-]+/g, ' ').replace(/\d+/g, '').trim();
  return local ? local.replace(/\b\w/g, (c) => c.toUpperCase()).slice(0, 40) : '';
};
