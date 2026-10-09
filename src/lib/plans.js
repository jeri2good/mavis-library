// Bible reading plans: definitions (built from the book list, so they always
// match the app's Bible), today's reading, marking days done, and streaks.
// Progress is stored per plan in the synced "plans" store.

import * as store from './store.js';

export const PLANS = [
  { id: 'bible-year', name: 'The whole Bible in a year', days: 365, blurb: 'Genesis to Revelation in order, about 3–4 chapters a day.', books: 'all' },
  { id: 'ot-nt-year', name: 'Old and New Testament together', days: 365, blurb: 'Each day a passage from the Old Testament and one from the New, finishing both in a year.', books: 'paired' },
  { id: 'nt-90', name: 'New Testament in 90 days', days: 90, blurb: 'About 3 chapters a day, Matthew to Revelation.', books: 'NT' },
  { id: 'gospels-30', name: 'The four Gospels in 30 days', days: 30, blurb: 'Matthew, Mark, Luke, and John — about 3 chapters a day.', books: ['Matt', 'Mark', 'Luke', 'John'] },
  { id: 'psalms-proverbs-60', name: 'Psalms and Proverbs in 60 days', days: 60, blurb: 'Three chapters a day of prayer, praise, and wisdom.', books: ['Ps', 'Prov'] },
  { id: 'proverbs-31', name: 'A Proverb a day', days: 31, blurb: 'One chapter of Proverbs for each day of the month.', books: ['Prov'] },
];

const chaptersOf = (idx, ids) => ids.flatMap((id) => idx.byId.get(id).verses.map((_, i) => ({ book: id, chapter: i + 1 })));

/** Split a list of chapters into `days` nearly equal, in-order portions. */
function spread(list, days) {
  const out = Array.from({ length: days }, () => []);
  for (let i = 0; i < list.length; i++) out[Math.min(days - 1, Math.floor((i * days) / list.length))].push(list[i]);
  return out;
}

const scheduleCache = new Map();
/** Day-by-day readings: [[{ book, chapter }, …], …] */
export function schedule(idx, planId) {
  if (scheduleCache.has(planId)) return scheduleCache.get(planId);
  const plan = PLANS.find((p) => p.id === planId);
  if (!plan) return [];
  const all = idx.books.map((b) => b.id);
  let days;
  if (plan.books === 'all') days = spread(chaptersOf(idx, all), plan.days);
  else if (plan.books === 'NT') days = spread(chaptersOf(idx, idx.books.filter((b) => b.testament === 'NT').map((b) => b.id)), plan.days);
  else if (plan.books === 'paired') {
    const ot = spread(chaptersOf(idx, idx.books.filter((b) => b.testament === 'OT').map((b) => b.id)), plan.days);
    const nt = spread(chaptersOf(idx, idx.books.filter((b) => b.testament === 'NT').map((b) => b.id)), plan.days);
    days = ot.map((d, i) => [...d, ...nt[i]]);
  } else days = spread(chaptersOf(idx, plan.books), plan.days);
  scheduleCache.set(planId, days);
  return days;
}

/** "Genesis 1–3; Matthew 1" */
export function describeDay(idx, day) {
  const groups = [];
  for (const r of day) {
    const g = groups[groups.length - 1];
    if (g && g.book === r.book && r.chapter === g.to + 1) g.to = r.chapter;
    else groups.push({ book: r.book, from: r.chapter, to: r.chapter });
  }
  return groups.map((g) => {
    const b = idx.byId.get(g.book);
    if (b.verses.length === 1) return b.name;
    return `${b.name} ${g.from}${g.to !== g.from ? `–${g.to}` : ''}`;
  }).join('; ');
}

export const localDate = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const dayDiff = (a, b) => Math.round((new Date(`${b}T12:00:00`) - new Date(`${a}T12:00:00`)) / 864e5);

export async function myPlans() {
  return (await store.listRecords('plans')).filter((r) => r.planId).sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
}

export async function startPlan(planId, { startDate } = {}) {
  const start = /^\d{4}-\d{2}-\d{2}$/.test(startDate || '') ? startDate : localDate();
  return store.putRecord('plans', planId, { planId, startDate: start, startedAt: Date.now(), done: {}, log: {} });
}

export async function stopPlan(planId) { await store.deleteRecord('plans', planId); }

export async function setDayDone(planId, dayIndex, done = true) {
  const cur = await store.getRecord('plans', planId);
  if (!cur) return null;
  const doneMap = { ...(cur.done || {}) };
  const log = { ...(cur.log || {}) };
  const today = localDate();
  if (done) { doneMap[dayIndex] = Date.now(); log[today] = (log[today] || 0) + 1; }
  else delete doneMap[dayIndex];
  return store.putRecord('plans', planId, { done: doneMap, log });
}

/** Where the reader is in a plan: today's scheduled day, the next unread day, counts, and streak. */
export function status(idx, row) {
  const days = schedule(idx, row.planId);
  const done = row.done || {};
  const doneCount = Object.keys(done).filter((k) => Number(k) < days.length).length;
  const scheduled = Math.min(days.length - 1, Math.max(0, dayDiff(row.startDate, localDate())));
  let next = days.findIndex((_, i) => !done[i]);
  if (next < 0) next = null;
  const behind = next == null ? 0 : Math.max(0, scheduled - next);
  return { days, doneCount, total: days.length, scheduled, next, behind, finished: next == null, streak: streak(row.log || {}) };
}

/** Consecutive days (ending today, or yesterday if today isn't done yet) with at least one reading marked. */
export function streak(log) {
  let n = 0;
  const d = new Date();
  if (!log[localDate(d)]) d.setDate(d.getDate() - 1);
  while (log[localDate(d)]) { n++; d.setDate(d.getDate() - 1); }
  return n;
}

/** Is this chapter part of the next unread day of any active plan? */
export async function planDayFor(idx, book, chapter) {
  for (const row of await myPlans()) {
    const st = status(idx, row);
    if (st.next == null) continue;
    if (st.days[st.next].some((r) => r.book === book && r.chapter === chapter)) return { row, day: st.next, readings: st.days[st.next], plan: PLANS.find((p) => p.id === row.planId) };
  }
  return null;
}
