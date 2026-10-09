// Word builder: words saved from the dictionary while reading, with the
// sentence they came from, practiced with short spaced-repetition quizzes
// (Leitner boxes). Rows live in the synced "vocab" store, so they follow a
// signed-in reader to every device.

import * as store from './store.js';
import { localDate } from './plans.js';

/** Days until the next review after reaching each box. Box 5+ counts as mastered. */
export const INTERVALS = [0, 1, 2, 4, 8, 16, 32];
export const MASTERED = 5;

const addDays = (date, n) => {
  const d = new Date(`${date}T12:00:00`);
  d.setDate(d.getDate() + n);
  return localDate(d);
};

export const wordKey = (w) => String(w || '').toLowerCase().normalize('NFC').trim().slice(0, 60);

export async function listWords() {
  const rows = await store.listRecords('vocab');
  return rows.sort((a, b) => (b.addedAt || 0) - (a.addedAt || 0));
}

export const getWord = (w) => store.getRecord('vocab', wordKey(w));

/**
 * Save (or refresh) a word. `form` is the exact text the reader selected
 * ("ambled"), `word` the dictionary headword ("amble").
 */
export async function saveWord({ word, form, definition, partOfSpeech = '', phonetic = '', sentence = '', bookKey = '', bookTitle = '', cfi = '', simple = '' }) {
  const k = wordKey(word);
  if (!k) throw new Error('No word to save.');
  const cur = await store.getRecord('vocab', k);
  return store.putRecord('vocab', k, {
    word: k,
    form: String(form || word).trim().slice(0, 60),
    definition: String(definition || cur?.definition || '').slice(0, 500),
    simple: String(simple || cur?.simple || '').slice(0, 400),
    partOfSpeech: String(partOfSpeech || cur?.partOfSpeech || '').slice(0, 30),
    phonetic: String(phonetic || cur?.phonetic || '').slice(0, 60),
    sentence: String(sentence || cur?.sentence || '').slice(0, 400),
    bookKey: bookKey || cur?.bookKey || '',
    bookTitle: String(bookTitle || cur?.bookTitle || '').slice(0, 200),
    cfi: cfi || cur?.cfi || '',
    box: cur?.box ?? 0,
    due: cur?.due || localDate(),
    addedAt: cur?.addedAt || Date.now(),
    reviews: cur?.reviews || 0,
    right: cur?.right || 0,
  });
}

export const removeWord = (w) => store.deleteRecord('vocab', wordKey(w));

export const isDue = (r, today = localDate()) => (r.due || today) <= today;
export const isMastered = (r) => (r.box || 0) >= MASTERED;

export function stats(rows, today = localDate()) {
  return {
    total: rows.length,
    due: rows.filter((r) => isDue(r, today)).length,
    mastered: rows.filter(isMastered).length,
    learning: rows.filter((r) => !isMastered(r)).length,
  };
}

/** The next box and review date after an answer. A miss goes back to box 0 (review today). */
export function nextState(r, correct, today = localDate()) {
  const box = correct ? Math.min(INTERVALS.length - 1, (r.box || 0) + 1) : 0;
  return { box, due: addDays(today, INTERVALS[box]) };
}

export async function grade(w, correct) {
  const r = await getWord(w);
  if (!r) return null;
  const n = nextState(r, correct);
  return store.putRecord('vocab', r.k, { ...n, reviews: (r.reviews || 0) + 1, right: (r.right || 0) + (correct ? 1 : 0), lastReviewed: Date.now() });
}

// Real words with plain meanings, used only as wrong answers when the reader
// has saved fewer than four words of their own.
export const FILLERS = [
  ['gentle', 'kind and soft; not rough'], ['ancient', 'very, very old'], ['brave', 'ready to face danger or pain'],
  ['curious', 'eager to know or learn something'], ['enormous', 'very large'], ['fragile', 'easily broken'],
  ['gloomy', 'dark and sad'], ['humble', 'not proud; modest'], ['journey', 'a trip from one place to another'],
  ['meadow', 'a field of grass and wildflowers'], ['narrow', 'small from side to side; not wide'], ['puzzle', 'something hard to understand or solve'],
  ['rapid', 'very fast'], ['shiver', 'to shake a little, as from cold or fear'], ['timid', 'shy and easily frightened'],
  ['vast', 'very great in size or amount'], ['whisper', 'to speak very softly'], ['weary', 'very tired'],
  ['cunning', 'clever at tricking others'], ['solemn', 'serious and formal'], ['linger', 'to stay longer than needed'],
  ['peculiar', 'strange or unusual'], ['quarrel', 'an angry argument'], ['tremble', 'to shake, as from fear or excitement'],
];

function shuffle(a, rand = Math.random) {
  const out = [...a];
  for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [out[i], out[j]] = [out[j], out[i]]; }
  return out;
}

const meaningOf = (r) => r.simple || r.definition || '';

/** Blank the word out of its sentence, or null when the sentence doesn't contain it. */
export function cloze(sentence, form) {
  if (!sentence || !form) return null;
  const esc = form.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(^|[^\\p{L}])(${esc})(?![\\p{L}])`, 'iu');
  if (!re.test(sentence)) return null;
  return sentence.replace(re, (_, pre) => `${pre}_____`);
}

/**
 * Build a practice round: up to `size` due words (or, if none are due, the
 * least-known ones), each with a question type that fits what we know about it.
 */
export function buildRound(rows, { size = 10, kids = false, canSpeak = false, today = localDate(), rand = Math.random } = {}) {
  const usable = rows.filter((r) => meaningOf(r));
  const due = usable.filter((r) => isDue(r, today));
  const pool = (due.length ? due : [...usable].sort((a, b) => (a.box || 0) - (b.box || 0) || (a.due || '').localeCompare(b.due || ''))).slice(0, size);
  const choices = kids ? 3 : 4;
  const others = (r) => {
    const mine = usable.filter((o) => o.word !== r.word).map((o) => [o.word, meaningOf(o), o.form || o.word]);
    const fill = FILLERS.filter(([w]) => w !== r.word && !mine.some((m) => m[0] === w)).map(([w, d]) => [w, d, w]);
    return [...shuffle(mine, rand), ...shuffle(fill, rand)].slice(0, choices - 1);
  };
  return shuffle(pool, rand).map((r, i) => {
    const kinds = ['meaning', 'word'];
    const blanked = cloze(r.sentence, r.form || r.word);
    if (blanked) kinds.push('cloze');
    if (canSpeak && (r.box || 0) >= 2) kinds.push('spell');
    const kind = kinds[(i + (r.box || 0)) % kinds.length];
    const wrong = others(r);
    if (kind === 'spell') return { kind, word: r.word, answer: r.word, prompt: meaningOf(r), record: r };
    if (kind === 'word') {
      const options = shuffle([[r.word, meaningOf(r)], ...wrong.map(([w, d]) => [w, d])], rand).map(([w, d]) => ({ id: w, text: d }));
      return { kind, word: r.word, prompt: r.form && r.form.toLowerCase() !== r.word ? `${r.word} (as in “${r.form}”)` : r.word, options, answer: r.word, record: r };
    }
    if (kind === 'cloze') {
      const options = shuffle([[r.word, r.form || r.word], ...wrong.map(([w, , f]) => [w, f])], rand).map(([w, f]) => ({ id: w, text: f }));
      return { kind, word: r.word, prompt: blanked, options, answer: r.word, record: r };
    }
    const options = shuffle([r.word, ...wrong.map(([w]) => w)], rand).map((w) => ({ id: w, text: w }));
    return { kind: 'meaning', word: r.word, prompt: meaningOf(r), options, answer: r.word, record: r };
  });
}

/** Spelling check: forgiving about case, spaces, and curly apostrophes. */
export const spelledRight = (typed, answer) => String(typed || '').trim().toLowerCase().replace(/[‘’]/g, "'") === String(answer || '').toLowerCase();

/** The sentence around a selection in the book (its containing paragraph, trimmed to the sentence). */
export function sentenceAround(range) {
  try {
    let node = range.startContainer;
    if (node.nodeType === 3) node = node.parentElement;
    const block = node.closest('p, li, blockquote, td, h1, h2, h3, h4, div') || node;
    const text = (block.textContent || '').replace(/\s+/g, ' ').trim();
    const sel = range.toString().replace(/\s+/g, ' ').trim();
    if (!text || !sel) return '';
    const parts = text.match(/[^.!?]+[.!?]+["”’)\]]*|[^.!?]+$/g) || [text];
    const hit = parts.find((s) => s.includes(sel)) || '';
    return hit.trim().slice(0, 400);
  } catch { return ''; }
}
