// The home screen in kids mode: today's reading goal and stars, books in
// progress, words to practice, a hand-picked story shelf, and Bible stories.

import { html, icon } from '../lib/ui.js';
import { searchGutenberg } from '../lib/catalog.js';
import * as store from '../lib/store.js';
import * as vocab from '../lib/vocab.js';
import { bookCard, cover, skeletonGrid, stateBlock } from '../components.js';
import { KIDS_SHELF, KIDS_TOPIC, kidsBook, kidsName, dailyGoal, minutesToday, goalStreak, lastWeek } from '../lib/kids.js';

const BIBLE_STORIES = [
  ['In the beginning', 'Gen', 1, 1], ['Noah’s ark', 'Gen', 6, 9], ['Joseph’s colorful coat', 'Gen', 37, 1],
  ['Crossing the Red Sea', 'Exod', 14, 1], ['David and Goliath', '1Sam', 17, 1], ['Daniel and the lions', 'Dan', 6, 1],
  ['Jonah and the big fish', 'Jonah', 1, 1], ['Jesus is born', 'Luke', 2, 1], ['The Good Samaritan', 'Luke', 10, 25],
  ['The lost sheep', 'Luke', 15, 1], ['Jesus feeds five thousand', 'John', 6, 1], ['Zacchaeus climbs a tree', 'Luke', 19, 1],
];

function goalRing(mins, goal) {
  const f = Math.min(1, mins / goal);
  const r = 34, c = 2 * Math.PI * r;
  return html`<svg class="goal-ring" viewBox="0 0 80 80" role="img" aria-label="${mins} of ${goal} minutes read today">
    <circle cx="40" cy="40" r="${r}" class="track" />
    <circle cx="40" cy="40" r="${r}" class="fill" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - f)}" />
    <text x="40" y="38" text-anchor="middle" class="big">${mins}</text><text x="40" y="54" text-anchor="middle" class="small">of ${goal} min</text></svg>`;
}

export async function render(root, _route, { navigate, token }) {
  const shelf = await store.listShelf().catch(() => []);
  const progress = await store.allProgress().catch(() => new Map());
  const reading = shelf.filter((b) => b.status === 'reading' && b.lastOpenedAt && kidsBook(b)).sort((a, b) => b.lastOpenedAt - a.lastOpenedAt);
  const words = await vocab.listWords().catch(() => []);
  const ws = vocab.stats(words);
  const goal = dailyGoal();
  const mins = minutesToday();
  const streak = goalStreak(goal);
  const week = lastWeek(goal);
  const name = kidsName();

  root.innerHTML = String(html`<div class="page kids-home">
    <section class="kids-hello" aria-labelledby="kh-h">
      <div>
        <p class="eyebrow">${new Date().toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' })}</p>
        <h1 class="display" id="kh-h">${name ? `Hi, ${name}!` : 'Hi, reader!'}</h1>
        <p class="lede">${mins >= goal ? 'You reached today’s reading goal. Amazing!' : mins ? `Keep going — ${goal - mins} more minute${goal - mins === 1 ? '' : 's'} to today’s star.` : `Read for ${goal} minutes today to earn a star.`}</p>
        <ol class="week-stars" aria-label="This week">${week.map((d) => html`<li class="${d.star ? 'on' : ''}" title="${d.minutes} min"><span class="star" aria-hidden="true">${d.star ? '★' : '☆'}</span><span class="d">${d.day}</span><span class="visually-hidden">${d.star ? 'goal reached' : `${d.minutes} minutes`}</span></li>`)}</ol>
        ${streak > 1 ? html`<p class="small"><b>${streak} days in a row!</b></p>` : ''}
      </div>
      ${goalRing(mins, goal)}
    </section>

    ${reading.length ? html`<section class="section" aria-labelledby="kc-h">
      <div class="section-head"><h2 class="h-section" id="kc-h">Keep reading</h2></div>
      <div class="feature-cards">${reading.slice(0, 3).map((b) => {
        const p = progress.get(b.key);
        return html`<a class="continue" href="#/read/${encodeURIComponent(b.key)}">${cover(b)}
          <div style="display:grid;gap:6px;min-width:0"><span class="title">${b.title}</span>
          <div class="progress-line"><span style="width:${Math.round((p?.percent || 0) * 100)}%"></span></div>
          <span class="small faint num">${p?.percent != null ? `${Math.round(p.percent * 100)}% read` : 'Started'}</span></div></a>`;
      })}</div></section>` : ''}

    ${words.length ? html`<section class="section"><a class="kids-words" href="${ws.due ? '#/words/practice' : '#/words'}">
      <span class="kids-words-icon" aria-hidden="true">Aa</span>
      <span><b>${ws.due ? `${ws.due} word${ws.due === 1 ? '' : 's'} to practice` : 'My words'}</b><span class="small muted">${ws.total} saved · ${ws.mastered} mastered</span></span>
      ${icon('chevronR')}</a></section>` : ''}

    <section class="section" aria-labelledby="ks-h">
      <div class="section-head"><h2 class="h-section" id="ks-h">Story shelf</h2><span class="small faint">Free classics · Project Gutenberg</span></div>
      <div id="k-shelf">${skeletonGrid(8)}</div>
    </section>

    <section class="section" aria-labelledby="kb-h">
      <div class="section-head"><h2 class="h-section" id="kb-h">Bible stories</h2></div>
      <ul class="bible-stories">${BIBLE_STORIES.map(([t, b, c, v]) => html`<li><a href="#/bible/${b}/${c}${v > 1 ? `?v=${v}` : ''}">${icon('cross', { size: 18 })} ${t}</a></li>`)}</ul>
    </section>

    <section class="section" aria-labelledby="km-h">
      <div class="section-head"><h2 class="h-section" id="km-h">More children’s books</h2><a class="btn btn-quiet btn-sm" href="#/search">Find a story ${icon('chevronR', { size: 16 })}</a></div>
      <div id="k-more">${skeletonGrid(6)}</div>
    </section>

    <p class="small faint" style="margin-top:28px">How to save a word: while reading, press and hold a word, tap the dictionary button, then “Save word”.</p>
  </div>`);

  const controllers = [];
  async function loadInto(el, fetcher) {
    const ctl = new AbortController();
    controllers.push(ctl);
    try {
      const data = await fetcher(ctl.signal);
      if (!token()) return;
      el.innerHTML = data.results.length ? String(html`<div class="row">${data.results.map((b) => bookCard(b))}</div>`) : String(stateBlock({ title: 'Nothing here yet', text: 'Try again later.' }));
    } catch (err) {
      if (err.name === 'AbortError' || !token()) return;
      el.innerHTML = String(stateBlock({ tone: 'error', title: 'Couldn’t load these books', text: err.message, actions: html`<button type="button" class="btn btn-sm" data-retry>Try again</button>` }));
      el.querySelector('[data-retry]').addEventListener('click', () => loadInto(el, fetcher));
    }
  }
  loadInto(root.querySelector('#k-shelf'), (signal) => searchGutenberg({ ids: KIDS_SHELF.join(',') }, { signal }));
  loadInto(root.querySelector('#k-more'), (signal) => searchGutenberg({ topic: KIDS_TOPIC, languages: 'en' }, { signal }));
  return () => controllers.forEach((c) => c.abort());
}
