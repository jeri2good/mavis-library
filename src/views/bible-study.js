// Bible study screens: Nave's Topical Bible (#/bible/topics, #/bible/topic/<id>)
// and reading plans (#/bible/plans).

import { html, icon, toast, confirmDialog } from '../lib/ui.js';
import * as B from '../lib/bible.js';
import * as P from '../lib/plans.js';
import * as store from '../lib/store.js';
import { stateBlock } from '../components.js';

const tc = B.titleCase;

// ================================================================ topics list
export async function renderTopics(root, route, { navigate, token }) {
  const q0 = route.params.get('q') || '';
  root.innerHTML = String(html`
    <div class="page bible-study">
      <div style="padding-top:8px"><a class="btn btn-quiet btn-sm" href="#/bible">${icon('back', { size: 18 })} Bible</a></div>
      <h1 class="h-page">Topics</h1>
      <p class="muted">Nave’s Topical Bible: over 5,000 subjects — people, places, doctrines, and everyday life — with every passage that speaks to them.</p>
      <form class="searchbar" role="search" id="tq-form">${icon('search')}
        <label class="visually-hidden" for="tq">Find a topic</label>
        <input id="tq" type="search" placeholder="Forgiveness, prayer, Abraham, anger…" value="${q0}" autocomplete="off" />
      </form>
      <div id="tlist" aria-live="polite"><div class="skeleton line"></div><div class="skeleton line"></div></div>
      <p class="small faint">Nave’s Topical Bible (Orville J. Nave, 1896) is in the public domain; structured data by Brady Stephenson’s bible-data project (CC BY 4.0).</p>
    </div>`);
  let topics;
  try { ({ topics } = await B.navesTopics()); }
  catch (err) { root.querySelector('#tlist').innerHTML = String(stateBlock({ tone: 'error', title: 'Topics couldn’t load', text: err.message })); return; }
  if (!token()) return;
  const list = root.querySelector('#tlist');
  const input = root.querySelector('#tq');
  const POPULAR = ['FAITH', 'PRAYER', 'LOVE', 'FORGIVENESS', 'GRACE OF GOD', 'HOPE', 'PEACE', 'JOY', 'ANGER', 'FEAR OF GOD', 'HUMILITY', 'PATIENCE', 'WISDOM', 'REPENTANCE', 'HOLY SPIRIT', 'SALVATION', 'MARRIAGE', 'CHILDREN', 'MONEY', 'WORK'];
  function paint(q) {
    const term = q.trim().toUpperCase();
    let hits;
    if (!term) hits = POPULAR.map((s) => topics.findIndex((t) => t[0] === s)).filter((i) => i >= 0);
    else {
      const starts = [], contains = [];
      topics.forEach((t, i) => { if (t[0].startsWith(term)) starts.push(i); else if (t[0].includes(term)) contains.push(i); });
      hits = [...starts, ...contains].slice(0, 120);
    }
    list.innerHTML = String(hits.length ? html`
      ${!term ? html`<p class="eyebrow">Popular topics</p>` : html`<p class="result-count num">${hits.length === 120 ? '120+' : hits.length} topic${hits.length === 1 ? '' : 's'}</p>`}
      <ul class="topic-list">${hits.map((i) => html`<li><a href="#/bible/topic/${i}"><span>${tc(topics[i][0])}</span><span class="small faint num">${topics[i][1]} passages</span></a></li>`)}</ul>`
      : stateBlock({ title: 'No topics match', text: `Nothing in Nave’s index matches “${q}”. Try a shorter word.` }));
  }
  paint(q0);
  let t;
  input.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { paint(input.value); history.replaceState(null, '', `#/bible/topics${input.value ? `?q=${encodeURIComponent(input.value)}` : ''}`); }, 120); });
  root.querySelector('#tq-form').addEventListener('submit', (e) => e.preventDefault());
}

// ================================================================ one topic
export async function renderTopic(root, route, { idx, token }) {
  const id = Number(route.segs[1]);
  root.innerHTML = String(html`<div class="page bible-study"><div class="skeleton line"></div></div>`);
  const t = Number.isInteger(id) ? await B.navesTopic(id).catch(() => null) : null;
  if (!token()) return;
  if (!t) { root.innerHTML = String(html`<div class="page">${stateBlock({ title: 'Topic not found', actions: html`<a class="btn btn-sm" href="#/bible/topics">All topics</a>` })}</div>`); return; }
  const tr = B.trInfo(store.getSetting('bibleTr', 'kjv')).id;
  const { topics } = await B.navesTopics();
  const seeAlso = (label) => {
    const m = /^See (.+)$/i.exec(label);
    if (!m) return null;
    const name = m[1].replace(/[.;]$/, '').trim().toUpperCase();
    const i = topics.findIndex((x) => x[0] === name);
    return i >= 0 ? i : null;
  };
  root.innerHTML = String(html`
    <div class="page bible-study">
      <div style="padding-top:8px"><a class="btn btn-quiet btn-sm" href="#/bible/topics">${icon('back', { size: 18 })} Topics</a></div>
      <h1 class="h-page">${tc(t.subject)}</h1>
      <p class="muted small">Tap a passage to read it here; open it to read in context.</p>
      <div class="topic-entries">${t.entries.map((e, n) => {
        const see = seeAlso(e.label);
        if (!e.refs.length) return html`<p class="topic-see">${see != null ? html`See <a href="#/bible/topic/${see}">${tc(e.label.replace(/^See /i, ''))}</a>` : e.label}</p>`;
        return html`<section class="topic-entry"><h2>${e.label ? e.label.replace(/^./, (c) => c.toUpperCase()) : 'General'}</h2>
          <div class="ref-chips">${e.refs.map((r, j) => {
            const p = B.parseShortRef(r);
            return p && idx.byId.get(p.book) ? html`<button type="button" class="chip ref-chip" data-ref="${r}" data-e="${n}" data-j="${j}">${B.labelSync(idx, p)}</button>` : '';
          })}</div><div class="ref-text" id="rt-${n}" hidden></div></section>`;
      })}</div>
      <p class="small faint">From Nave’s Topical Bible (public domain).</p>
    </div>`);
  root.addEventListener('click', async (e) => {
    const chip = e.target.closest('[data-ref]');
    if (!chip) return;
    const p = B.parseShortRef(chip.dataset.ref);
    const box = root.querySelector(`#rt-${chip.dataset.e}`);
    root.querySelectorAll(`[data-e="${chip.dataset.e}"]`).forEach((c) => c.setAttribute('aria-pressed', String(c === chip)));
    box.hidden = false;
    box.innerHTML = String(html`<p class="muted">Loading…</p>`);
    try {
      const range = p.verse ? p : { ...p, verse: 1, verseEnd: Math.min(idx.byId.get(p.book).verses[p.chapter - 1], 12) };
      const verses = await B.passageText(tr, range);
      box.innerHTML = String(html`<blockquote>${verses.map((v) => html`<sup>${v.v}</sup> ${v.text} `)}${!p.verse ? '…' : ''}</blockquote>
        <a class="btn btn-sm" href="#/bible/${encodeURIComponent(p.book)}/${p.chapter}${p.verse ? `?v=${p.verse}${p.verseEnd ? `&ve=${p.verseEnd}` : ''}` : ''}">Read ${B.labelSync(idx, p)} in context ${icon('chevronR', { size: 16 })}</a>`);
    } catch (err) { box.innerHTML = String(html`<p class="muted">${err.message}</p>`); }
  });
}

// ================================================================ plans
export async function renderPlans(root, route, { idx, navigate, token }) {
  async function paint() {
    const mine = await P.myPlans().catch(() => []);
    if (!token()) return;
    const activeIds = new Set(mine.map((r) => r.planId));
    root.innerHTML = String(html`
      <div class="page bible-study">
        <div style="padding-top:8px"><a class="btn btn-quiet btn-sm" href="#/bible">${icon('back', { size: 18 })} Bible</a></div>
        <h1 class="h-page">Reading plans</h1>
        ${mine.length ? html`<div class="plan-cards">${mine.map((row) => planCard(row))}</div>` : html`<p class="muted">Pick a plan to read through Scripture a little each day. Your progress and streak sync to your account.</p>`}
        <h2 class="h-section" style="margin-top:22px">${mine.length ? 'Start another plan' : 'Plans'}</h2>
        <ul class="plan-list">${P.PLANS.filter((p) => !activeIds.has(p.id)).map((p) => html`<li class="plan-item">
          <div><strong>${p.name}</strong><p class="small muted">${p.blurb}</p><p class="small faint">${p.days} days · starts with ${P.describeDay(idx, P.schedule(idx, p.id)[0])}</p></div>
          <button type="button" class="btn btn-sm" data-start="${p.id}">Start</button></li>`)}</ul>
      </div>`);
  }

  function planCard(row) {
    const plan = P.PLANS.find((p) => p.id === row.planId);
    if (!plan) return '';
    const st = P.status(idx, row);
    const pct = Math.round((st.doneCount / st.total) * 100);
    const day = st.next;
    return html`<article class="plan-card">
      <header><div><p class="eyebrow">${plan.name}</p>
        ${st.finished ? html`<h2>Finished! 🎉</h2>` : html`<h2>Day ${day + 1} <span class="faint">of ${st.total}</span></h2>`}</div>
        <div class="streak" title="Days in a row with a reading marked">${icon('spark', { size: 18 })}<span class="num">${st.streak}</span><span class="small">day streak</span></div></header>
      ${!st.finished ? html`<p class="plan-today"><a href="#/bible/${encodeURIComponent(st.days[day][0].book)}/${st.days[day][0].chapter}">${P.describeDay(idx, st.days[day])}</a></p>
        ${st.behind ? html`<p class="small faint">${st.behind} day${st.behind === 1 ? '' : 's'} behind schedule — no pressure, just keep going.</p>` : ''}
        <div class="plan-actions"><a class="btn btn-sm btn-primary" href="#/bible/${encodeURIComponent(st.days[day][0].book)}/${st.days[day][0].chapter}">${icon('book', { size: 18 })} Read</a>
          <button type="button" class="btn btn-sm" data-done="${row.planId}" data-day="${day}">${icon('check', { size: 18 })} Mark as read</button></div>` : ''}
      <div class="dl-bar" aria-hidden="true"><span style="width:${pct}%"></span></div>
      <p class="small faint num">${st.doneCount} of ${st.total} days · ${pct}%</p>
      <details><summary>All days</summary><ol class="plan-days">${st.days.map((d, i) => html`<li class="${row.done?.[i] ? 'done' : ''}"><button type="button" class="linklike" data-toggle="${row.planId}" data-day="${i}" aria-pressed="${!!row.done?.[i]}">${row.done?.[i] ? icon('check', { size: 14 }) : ''} Day ${i + 1}</button> <a href="#/bible/${encodeURIComponent(d[0].book)}/${d[0].chapter}">${P.describeDay(idx, d)}</a></li>`)}</ol></details>
      <button type="button" class="btn btn-quiet btn-sm" data-stop="${row.planId}">Stop this plan</button>
    </article>`;
  }

  root.addEventListener('click', async (e) => {
    const start = e.target.closest('[data-start]')?.dataset.start;
    if (start) { await P.startPlan(start); toast('Plan started. Day 1 is ready.'); paint(); return; }
    const done = e.target.closest('[data-done]');
    if (done) { await P.setDayDone(done.dataset.done, Number(done.dataset.day), true); toast('Marked as read. Well done.'); paint(); return; }
    const tog = e.target.closest('[data-toggle]');
    if (tog) { await P.setDayDone(tog.dataset.toggle, Number(tog.dataset.day), tog.getAttribute('aria-pressed') !== 'true'); paint(); return; }
    const stop = e.target.closest('[data-stop]')?.dataset.stop;
    if (stop) {
      const ok = await confirmDialog({ title: 'Stop this plan?', message: 'Your progress in this plan will be removed.', confirmLabel: 'Stop plan' });
      if (ok) { await P.stopPlan(stop); paint(); }
    }
  });
  const off = store.onChange((ev) => { if (ev.type === 'remote' && ev.store === 'plans') paint(); });
  await paint();
  return () => off();
}
