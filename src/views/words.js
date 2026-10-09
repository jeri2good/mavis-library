// #/words — the word builder: words saved while reading, and short practice
// rounds that bring each word back just before it would be forgotten.
// #/words/practice — one practice round.

import { html, icon, toast, confirmDialog, prefersReducedMotion } from '../lib/ui.js';
import * as store from '../lib/store.js';
import * as vocab from '../lib/vocab.js';
import { define } from '../lib/dictionary.js';
import { speakWord, ttsSupported } from '../lib/tts.js';
import { isKids, kidsName } from '../lib/kids.js';
import { loadFeatures, can, ownerPost } from '../lib/features.js';
import { stateBlock } from '../components.js';

export const title = (route) => (route.segs[0] === 'practice' ? 'Practice words' : 'Word builder');

const PRAISE = ['Right!', 'Yes — well done.', 'Exactly.', 'Nice work.', 'You got it.'];
const KID_PRAISE = ['Great job!', 'You got it!', 'Super!', 'Brilliant!', 'Yes! Well done!'];
const pick = (a) => a[Math.floor(Math.random() * a.length)];

const meaningOf = (r) => (isKids() && r.simple) || r.definition || r.simple || '';
const readHref = (r) => (r.bookKey && r.cfi ? `#/read/${encodeURIComponent(r.bookKey)}?at=${encodeURIComponent(r.cfi)}` : r.bookKey ? `#/book/${encodeURIComponent(r.bookKey)}` : '');

function boxDots(r) {
  const n = Math.min(vocab.MASTERED, r.box || 0);
  return html`<span class="word-dots" role="img" aria-label="${vocab.isMastered(r) ? 'Mastered' : `Learning: ${n} of ${vocab.MASTERED}`}">${Array.from({ length: vocab.MASTERED }, (_, i) => html`<span class="${i < n ? 'on' : ''}"></span>`)}</span>`;
}

export async function render(root, route, { navigate }) {
  if (route.segs[0] === 'practice') return practice(root, route, { navigate });
  let q = '';
  let filter = route.params.get('show') || 'all';
  const kids = isKids();
  root.innerHTML = String(html`<div class="page words">
    <div class="shelf-head">
      <p class="eyebrow">${kids && kidsName() ? `${kidsName()}’s words` : 'Your words'}</p>
      <h1>Word builder</h1>
      <p class="small muted">${kids ? 'Words you saved while reading. Practice a few each day and watch the stars fill up!' : 'Words you saved from the dictionary while reading, with the sentence you found them in. Short practice rounds bring each word back just before you’d forget it.'}</p>
      <div class="word-stats" id="w-stats"></div>
      <div class="ab-actions" id="w-go"></div>
    </div>
    <form class="searchbar word-add" id="w-add" role="search" style="box-shadow:none;margin-top:16px">
      <label class="visually-hidden" for="w-new">Add a word</label>
      <input id="w-new" type="search" placeholder="Add a word" autocomplete="off" maxlength="40" />
      <button type="submit" class="icon-btn go" aria-label="Look up and add">${icon('plus')}</button>
    </form>
    <div id="w-add-out" aria-live="polite"></div>
    <div class="shelf-tools" style="margin-top:14px"><div class="tabs" role="tablist" id="w-tabs"></div>
      <label class="visually-hidden" for="w-filter">Filter</label><input class="input" id="w-filter" type="search" placeholder="Filter" autocomplete="off" /></div>
    <div id="w-list" style="margin-top:14px"></div>
  </div>`);
  const $ = (s) => root.querySelector(s);

  async function paint() {
    const rows = await vocab.listWords();
    const s = vocab.stats(rows);
    $('#w-stats').innerHTML = String(html`
      <div><b class="num">${s.total}</b><span>saved</span></div>
      <div><b class="num">${s.due}</b><span>to practice today</span></div>
      <div><b class="num">${s.mastered}</b><span>mastered</span></div>`);
    $('#w-go').innerHTML = String(rows.length
      ? html`<a class="btn btn-primary ${kids ? 'btn-lg' : ''}" href="#/words/practice">${icon('play', { size: 18 })} ${s.due ? `Practice ${Math.min(s.due, 10)} word${Math.min(s.due, 10) === 1 ? '' : 's'}` : 'Practice anyway'}</a>
        ${s.due ? '' : html`<span class="small muted">Nothing is due today — come back tomorrow, or practice anyway.</span>`}`
      : '');
    const TABS = [['all', 'All', rows.length], ['due', 'To practice', s.due], ['learning', 'Learning', s.learning], ['mastered', 'Mastered', s.mastered]];
    $('#w-tabs').innerHTML = String(html`${TABS.map(([k, v, n]) => html`<button role="tab" type="button" aria-selected="${k === filter}" data-tab="${k}">${v} <span class="faint num">${n}</span></button>`)}`);
    let list = rows;
    if (filter === 'due') list = list.filter((r) => vocab.isDue(r));
    if (filter === 'learning') list = list.filter((r) => !vocab.isMastered(r));
    if (filter === 'mastered') list = list.filter(vocab.isMastered);
    if (q) list = list.filter((r) => `${r.word} ${r.form} ${r.definition} ${r.bookTitle}`.toLowerCase().includes(q));
    const el = $('#w-list');
    if (!list.length) {
      el.innerHTML = String(stateBlock({
        title: rows.length ? 'Nothing here' : 'No words yet',
        text: rows.length ? 'No words match.' : 'While reading, select a word and tap the dictionary button, then “Save word”. Or add one above.',
        actions: rows.length ? '' : html`<a class="btn btn-sm btn-primary" href="#/shelf">Open a book</a>`,
      }));
      return;
    }
    const ai = can('assistant');
    el.innerHTML = String(html`<ul class="word-list">${list.map((r) => html`
      <li class="word-card">
        <div class="word-top">
          <div><span class="dict-word">${r.word}</span> <span class="faint">${r.phonetic}</span> ${r.partOfSpeech ? html`<span class="dict-pos" style="display:inline">${r.partOfSpeech}</span>` : ''}</div>
          ${vocab.isMastered(r) ? html`<span class="badge badge-ok">${icon('check', { size: 14 })} Mastered</span>` : boxDots(r)}
        </div>
        <p class="word-def">${meaningOf(r)}</p>
        ${r.simple && !isKids() ? html`<p class="small muted">In simple words: ${r.simple}</p>` : ''}
        ${r.sentence ? html`<p class="word-sent">“${r.sentence}”</p>` : ''}
        <div class="quote-meta">
          ${r.bookTitle ? html`<a href="${readHref(r)}">${icon('book', { size: 16 })} ${r.bookTitle}</a>` : html`<span></span>`}
          <span class="faint small">${vocab.isDue(r) ? 'Practice today' : `Next: ${new Date(`${r.due}T12:00:00`).toLocaleDateString([], { month: 'short', day: 'numeric' })}`}</span>
        </div>
        <div class="quote-actions">
          ${ttsSupported ? html`<button type="button" class="btn btn-sm btn-quiet" data-say="${r.word}">${icon('headphones', { size: 16 })} Hear it</button>` : ''}
          ${ai ? html`<button type="button" class="btn btn-sm btn-quiet" data-simple="${r.word}">${icon('spark', { size: 16 })} ${r.simple ? 'Explain again' : 'Explain simply'}</button>` : ''}
          <button type="button" class="icon-btn" data-del="${r.word}" aria-label="Remove ${r.word}">${icon('trash', { size: 18 })}</button>
        </div>
      </li>`)}</ul>`);
  }

  root.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-tab],[data-say],[data-del],[data-simple],[data-addsave]');
    if (!t) return;
    if (t.dataset.tab) { filter = t.dataset.tab; paint(); }
    if (t.dataset.say) speakWord(t.dataset.say);
    if (t.dataset.del) {
      const ok = await confirmDialog({ title: `Remove “${t.dataset.del}”?`, message: 'It leaves your word builder on every device you’re signed in on.', confirmLabel: 'Remove' });
      if (ok) { await vocab.removeWord(t.dataset.del); toast('Removed.'); paint(); }
    }
    if (t.dataset.simple) {
      const r = await vocab.getWord(t.dataset.simple);
      t.disabled = true; t.textContent = 'Thinking…';
      try {
        const out = await explainSimply(r);
        await vocab.saveWord({ ...r, simple: out.meaning });
        paint();
      } catch (err) { toast(err.message, { tone: 'error' }); t.disabled = false; t.textContent = 'Explain simply'; }
    }
    if (t.dataset.addsave) {
      const [ei, mi, di] = t.dataset.addsave.split('.').map(Number);
      const en = addResult.entries[ei]; const m = en.meanings[mi]; const d = m.definitions[di];
      await vocab.saveWord({ word: en.word, form: addResult.typed, definition: d.definition, partOfSpeech: m.partOfSpeech, phonetic: en.phonetic, sentence: d.example || '' });
      $('#w-add-out').innerHTML = '';
      $('#w-new').value = '';
      toast(`Saved “${en.word}”.`);
      paint();
    }
  });
  let addResult = null;
  $('#w-add').addEventListener('submit', async (e) => {
    e.preventDefault();
    const typed = $('#w-new').value.trim();
    if (!typed) return;
    const out = $('#w-add-out');
    out.innerHTML = String(html`<p class="muted small">Looking up “${typed}”…</p>`);
    const r = await define(typed).catch(() => ({ entries: [], error: 'The dictionary couldn’t be reached.' }));
    if (r.error) { out.innerHTML = String(html`<p class="muted small">${r.error}</p>`); return; }
    addResult = { ...r, typed };
    out.innerHTML = String(html`<div class="word-pick"><p class="small muted">Pick the meaning to learn:</p>${r.entries.map((en, ei) => en.meanings.map((m, mi) => m.definitions.slice(0, 2).map((d, di) => html`
      <button type="button" class="word-pick-btn" data-addsave="${ei}.${mi}.${di}"><b>${en.word}</b> <span class="dict-pos" style="display:inline">${m.partOfSpeech}</span> ${d.definition}</button>`)))}</div>`);
  });
  $('#w-filter').addEventListener('input', (e) => { q = e.target.value.trim().toLowerCase(); paint(); });
  const off = store.onChange((d) => { if (d.type === 'vocab' || (d.type === 'remote' && d.store === 'vocab') || d.type === 'owner') paint(); });
  await loadFeatures();
  await paint();
  return () => off();
}

/** Owner-only: a short kid-friendly meaning for a word as used in its sentence. */
export async function explainSimply(r) {
  const out = await ownerPost('/api/study', { task: 'word', word: r.word, form: r.form, sentence: r.sentence, definition: r.definition, title: r.bookTitle });
  if (!out.meaning) throw new Error('No explanation came back. Try again.');
  return out;
}

// ---------- Practice ----------

async function practice(root, route, { navigate }) {
  const kids = isKids();
  const rows = await vocab.listWords();
  const round = vocab.buildRound(rows, { size: 10, kids, canSpeak: ttsSupported });
  if (!round.length) {
    root.innerHTML = String(html`<div class="page" style="padding-top:20px">${stateBlock({
      title: 'No words to practice yet',
      text: 'Save words from the dictionary while you read, then come back here.',
      actions: html`<a class="btn btn-sm btn-primary" href="#/words">Word builder</a>`,
    })}</div>`);
    return;
  }
  const missed = new Set();
  let i = 0, right = 0, movedUp = 0, mastered = 0, answered = false;
  const total = round.length;

  root.innerHTML = String(html`<div class="page practice ${kids ? 'is-kids' : ''}">
    <div class="listen-top"><a class="btn btn-quiet btn-sm" href="#/words">${icon('back', { size: 18 })} Words</a><span class="small faint num" id="p-count"></span></div>
    <div class="dl-bar" aria-hidden="true" style="margin:10px 0 18px"><span id="p-bar"></span></div>
    <div id="p-card" class="practice-card" aria-live="polite"></div>
  </div>`);
  const $ = (s) => root.querySelector(s);

  function paint() {
    const c = round[i];
    answered = false;
    $('#p-count').textContent = `${Math.min(i + 1, round.length)} of ${round.length}`;
    $('#p-bar').style.width = `${Math.round((i / round.length) * 100)}%`;
    const ask = {
      meaning: kids ? 'Which word means this?' : 'Which word means…',
      word: html`What does <b>${c.prompt}</b> mean?`,
      cloze: 'Which word fits?',
      spell: kids ? 'Listen, then spell the word.' : 'Listen and spell the word.',
    }[c.kind];
    $('#p-card').innerHTML = String(html`
      <p class="practice-ask">${ask}</p>
      ${c.kind === 'meaning' ? html`<p class="practice-prompt">${c.prompt}</p>` : ''}
      ${c.kind === 'cloze' ? html`<p class="practice-prompt word-sent">“${c.prompt}”</p>` : ''}
      ${c.kind === 'spell' ? html`<div class="ab-actions"><button type="button" class="btn ${kids ? 'btn-lg' : ''}" data-p="say">${icon('headphones', { size: 20 })} Hear the word</button></div><p class="small muted">Hint: ${c.prompt}</p>
        <form id="p-spell" class="field" autocomplete="off"><label for="p-typed">Your spelling</label><input class="input" id="p-typed" autocapitalize="off" autocorrect="off" spellcheck="false" maxlength="40" />
        <div class="ab-actions"><button class="btn btn-primary" type="submit">Check</button></div></form>` : ''}
      ${c.options ? html`<div class="practice-options ${c.kind === 'word' ? 'long' : ''}" role="group" aria-label="Answers">${c.options.map((o) => html`<button type="button" class="practice-opt" data-opt="${o.id}">${o.text}</button>`)}</div>` : ''}
      <div id="p-fb" class="practice-fb" hidden></div>`);
    if (c.kind === 'spell') { speakWord(c.word); setTimeout(() => $('#p-typed')?.focus(), 50); }
    else $('.practice-opt')?.focus({ preventScroll: true });
  }

  async function answer(ok, chosen) {
    if (answered) return;
    answered = true;
    const c = round[i];
    const before = c.record.box || 0;
    const after = await vocab.grade(c.word, ok);
    if (!missed.has(c.word)) {
      if (ok) { right++; if ((after?.box || 0) > before) movedUp++; if ((after?.box || 0) >= vocab.MASTERED && before < vocab.MASTERED) mastered++; }
    }
    if (!ok && !missed.has(c.word)) {
      missed.add(c.word);
      // Ask it once more at the end of the round.
      const others = rows.filter((r) => r.word !== c.word).map((r) => ({ ...r, due: '9999-12-31' }));
      const again = vocab.buildRound([{ ...c.record, box: 0, due: '0000-00-00' }, ...others], { size: 1, kids, canSpeak: false })
        .find((x) => x.word === c.word);
      if (again) round.push(again);
    }
    for (const b of root.querySelectorAll('[data-opt]')) {
      b.disabled = true;
      if (b.dataset.opt === c.answer) b.classList.add('is-right');
      else if (b.dataset.opt === chosen) b.classList.add('is-wrong');
    }
    const r = c.record;
    const fb = $('#p-fb');
    fb.hidden = false;
    fb.className = `practice-fb ${ok ? 'ok' : 'miss'}`;
    fb.innerHTML = String(html`
      <p class="practice-verdict">${ok ? html`${kids ? html`<span class="kid-star" aria-hidden="true">★</span>` : icon('check', { size: 20 })} ${pick(kids ? KID_PRAISE : PRAISE)}` : html`Not quite — it’s <b>${r.word}</b>.`}</p>
      <p><b>${r.word}</b>${r.partOfSpeech ? html` <span class="dict-pos" style="display:inline">${r.partOfSpeech}</span>` : ''} — ${meaningOf(r)}</p>
      ${r.sentence ? html`<p class="word-sent">“${r.sentence}”${r.bookTitle ? html` <span class="faint small">— ${r.bookTitle}</span>` : ''}</p>` : ''}
      <div class="ab-actions"><button type="button" class="btn btn-primary ${kids ? 'btn-lg' : ''}" data-p="next">${i + 1 < round.length ? 'Next' : 'See how you did'} ${icon('chevronR', { size: 18 })}</button></div>`);
    if (ok && kids && !prefersReducedMotion()) burst(fb);
    fb.querySelector('[data-p="next"]').focus({ preventScroll: true });
  }

  function finish() {
    $('#p-bar').style.width = '100%';
    $('#p-count').textContent = 'Done';
    const stars = Math.round((right / total) * 5);
    $('#p-card').innerHTML = String(html`
      <p class="eyebrow">Round complete</p>
      <h2 class="practice-prompt">${kids ? html`${right === total ? 'Perfect!' : 'Well done!'} <span class="kid-stars" aria-label="${stars} of 5 stars">${'★'.repeat(stars)}${'☆'.repeat(5 - stars)}</span>` : `${right} of ${total} right the first time`}</h2>
      <p class="muted">${kids ? `You got ${right} of ${total} right.` : ''} ${movedUp ? `${movedUp} word${movedUp === 1 ? '' : 's'} moved up${mastered ? `, and ${mastered} ${mastered === 1 ? 'is' : 'are'} now mastered` : ''}.` : ''} Missed words come back tomorrow.</p>
      <div class="ab-actions"><a class="btn btn-primary" href="#/words">Back to my words</a><button type="button" class="btn" data-p="again">Practice again</button></div>`);
  }

  root.addEventListener('click', (e) => {
    const o = e.target.closest('[data-opt]');
    if (o && !o.disabled) { answer(o.dataset.opt === round[i].answer, o.dataset.opt); return; }
    const a = e.target.closest('[data-p]')?.dataset.p;
    if (a === 'say') speakWord(round[i].word);
    if (a === 'next') { i++; if (i < round.length) paint(); else finish(); }
    if (a === 'again') navigate('/words/practice?fresh=' + Date.now(), { replace: true });
  });
  root.addEventListener('submit', (e) => {
    if (e.target.id !== 'p-spell') return;
    e.preventDefault();
    const typed = $('#p-typed').value;
    if (!typed.trim()) return;
    $('#p-typed').disabled = true;
    answer(vocab.spelledRight(typed, round[i].answer), null);
  });
  paint();
}

function burst(el) {
  const box = document.createElement('div');
  box.className = 'star-burst';
  box.setAttribute('aria-hidden', 'true');
  for (let k = 0; k < 10; k++) {
    const s = document.createElement('span');
    s.textContent = '★';
    s.style.setProperty('--a', `${k * 36}deg`);
    s.style.setProperty('--d', `${40 + Math.random() * 30}px`);
    box.append(s);
  }
  el.prepend(box);
  setTimeout(() => box.remove(), 900);
}
