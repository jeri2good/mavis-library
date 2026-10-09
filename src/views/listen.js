// #/listen/<bookKey> — the offline audiobook player. Built to be used at a
// glance: huge controls, high contrast, chapter skip, speed, and sleep timer.

import { html, icon, toast } from '../lib/ui.js';
import * as ab from '../lib/audiobook.js';
import { AudiobookPlayer } from '../lib/audiobook-player.js';
import * as store from '../lib/store.js';
import { recallBook } from '../lib/catalog.js';
import { cover, stateBlock } from '../components.js';

export const title = (route) => `Listen · ${recallBook(route.segs[0])?.title || 'Audiobook'}`;

const RATES = [0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2];

export async function render(root, route, { navigate }) {
  const key = route.segs[0] || '';
  const m = await ab.manifest(key);
  const item = await store.getShelfItem(key).catch(() => null);
  const book = { ...(recallBook(key) || {}), ...(item || {}) };
  if (!m || !ab.summarize(m).done) {
    root.innerHTML = String(html`<div class="page" style="padding-top:20px">${stateBlock({
      title: 'No audio saved for this book yet',
      text: 'Open the book’s page and choose “Get it as audio” to save it for offline listening.',
      actions: html`<a class="btn btn-primary btn-sm" href="#/book/${encodeURIComponent(key)}">Book page</a><a class="btn btn-sm" href="#/shelf">My shelf</a>`,
    })}</div>`);
    return;
  }
  const bookTitle = book.title || m.title || 'Audiobook';
  root.innerHTML = String(html`
    <div class="page listen">
      <div class="listen-top">
        <button type="button" class="btn btn-quiet btn-sm" data-l="back">${icon('back', { size: 18 })} Back</button>
        <a class="btn btn-quiet btn-sm" id="open-book" href="#/read/${encodeURIComponent(key)}">${icon('book', { size: 18 })} Open the book here</a>
      </div>
      <div class="listen-head">
        <div class="listen-cover">${cover(book, { eager: true })}</div>
        <div class="listen-meta">
          <p class="eyebrow">Listening offline</p>
          <h1>${bookTitle}</h1>
          <p class="listen-chapter" id="l-chapter"></p>
        </div>
      </div>
      <p class="listen-line" id="l-line" aria-live="off"></p>
      <div class="listen-progress"><div class="dl-bar" aria-hidden="true"><span id="l-bar"></span></div><p class="small faint num" id="l-left"></p></div>
      <div class="listen-controls" role="group" aria-label="Playback">
        <button type="button" class="car-btn" data-l="prev" aria-label="Previous chapter">${icon('chevronL', { size: 40 })}</button>
        <button type="button" class="car-btn" data-l="back30" aria-label="Back 30 seconds">${icon('skipB', { size: 40 })}<span class="car-btn-label">30</span></button>
        <button type="button" class="car-btn car-play" data-l="toggle" aria-label="Play">${icon('play', { size: 64 })}</button>
        <button type="button" class="car-btn" data-l="fwd30" aria-label="Forward 30 seconds">${icon('skipF', { size: 40 })}<span class="car-btn-label">30</span></button>
        <button type="button" class="car-btn" data-l="next" aria-label="Next chapter">${icon('chevronR', { size: 40 })}</button>
      </div>
      <p class="car-status" id="l-status" role="status"></p>
      <div class="listen-options">
        <div class="field"><label for="l-rate">Speed</label><select class="select" id="l-rate">${RATES.map((r) => html`<option value="${r}">${r}×</option>`)}</select></div>
        <div class="field"><label for="l-sleep">Sleep timer</label><select class="select" id="l-sleep">
          <option value="0">Off</option><option value="15">15 minutes</option><option value="30">30 minutes</option><option value="45">45 minutes</option><option value="60">1 hour</option><option value="chapter">End of chapter</option></select></div>
      </div>
      <details class="listen-chapters"><summary>Chapters</summary><ol id="l-list">${m.chapters.map((c) => {
        const done = c.chunks.filter((k) => k.done).length;
        return html`<li><button type="button" class="linklike" data-ch="${c.index}">${c.title}</button> <span class="small faint">${done === c.chunks.length ? ab.durationLabel(c.chunks.reduce((a, k) => a + k.chars, 0)) : done ? `${Math.round((done / c.chunks.length) * 100)}% saved` : 'not saved'}</span></li>`;
      })}</ol></details>
      <p class="small faint listen-note">Plays from this device — no signal needed. You can lock the phone or switch to maps; your car’s and headphones’ buttons work (skip = next chapter).</p>
    </div>`);

  const $ = (s) => root.querySelector(s);
  const rateSel = $('#l-rate');
  const player = new AudiobookPlayer({
    key, manifest: m, title: bookTitle, artwork: book.coverUrl || null,
    onState: (s) => paint(s),
  });
  rateSel.value = String(RATES.reduce((a, r) => (Math.abs(r - player.rate) < Math.abs(a - player.rate) ? r : a), 1));

  let lastLine = '';
  function paint(s) {
    const playBtn = $('[data-l="toggle"]');
    if (!playBtn) return;
    const playing = s.state === 'playing' || s.state === 'loading';
    playBtn.innerHTML = String(icon(playing ? 'pause' : 'play', { size: 64 }));
    playBtn.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    root.querySelector('.listen').classList.toggle('is-playing', playing);
    $('#l-chapter').textContent = `${s.chapter} · ${s.chapterIndex + 1} of ${s.chapters}`;
    if (s.line && s.line !== lastLine) { lastLine = s.line; $('#l-line').textContent = s.line; }
    $('#l-bar').style.width = `${Math.round(s.progress.fraction * 1000) / 10}%`;
    $('#l-left').textContent = `${Math.round(s.progress.fraction * 100)}% · about ${ab.durationLabel(s.progress.remainingChars / (s.rate || 1))} left`;
    const c = m.chapters[s.chapterIndex]?.chunks[player.pos.n];
    if (c?.cfi) $('#open-book').href = `#/read/${encodeURIComponent(key)}?at=${encodeURIComponent(c.cfi)}`;
    $('#l-status').textContent = s.error || (s.state === 'loading' ? 'Loading…' : s.state === 'paused' ? 'Paused' : s.state === 'ended' ? 'The end.' : s.reason === 'sleep' ? 'Sleep timer: paused.' : s.sleepAt ? `Stops at ${new Date(s.sleepAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : s.sleepEndOfChapter ? 'Stops at the end of this chapter' : '');
    for (const b of root.querySelectorAll('[data-ch]')) b.toggleAttribute('aria-current', Number(b.dataset.ch) === s.chapterIndex);
  }

  root.addEventListener('click', (e) => {
    const ch = e.target.closest('[data-ch]');
    if (ch) { player.goToChapter(Number(ch.dataset.ch)); return; }
    const a = e.target.closest('[data-l]')?.dataset.l;
    if (a === 'back') { history.length > 1 ? history.back() : navigate(`/book/${encodeURIComponent(key)}`); }
    if (a === 'toggle') player.toggle();
    if (a === 'back30') player.seekBy(-30);
    if (a === 'fwd30') player.seekBy(30);
    if (a === 'prev') player.prevChapter();
    if (a === 'next') player.nextChapter();
  });
  rateSel.addEventListener('change', () => player.setRate(rateSel.value));
  $('#l-sleep').addEventListener('change', (e) => {
    player.setSleep(e.target.value);
    toast(e.target.value === '0' ? 'Sleep timer off.' : e.target.value === 'chapter' ? 'Will stop at the end of this chapter.' : `Will stop in ${e.target.value} minutes.`);
  });
  const onKey = (e) => {
    if (e.target.closest('input, select, textarea')) return;
    if (e.key === ' ' || e.key === 'k') { e.preventDefault(); player.toggle(); }
    if (e.key === 'ArrowLeft') player.seekBy(-30);
    if (e.key === 'ArrowRight') player.seekBy(30);
  };
  document.addEventListener('keydown', onKey);
  await player.load({ autoplay: route.params.get('play') === '1' });
  player.emit();
  return () => { document.removeEventListener('keydown', onKey); player.destroy(); };
}
