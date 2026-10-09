// Word builder logic: spaced-repetition schedule, practice rounds, cloze, spelling.
import assert from 'node:assert/strict';
import * as v from '../../src/lib/vocab.js';

let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log(`  ✓ ${name}`); };

// Deterministic "random" for repeatable rounds.
const seeded = (s = 7) => () => ((s = (s * 16807) % 2147483647) / 2147483647);

const W = (word, extra = {}) => ({ word, form: word, definition: `meaning of ${word}`, box: 0, due: '2026-10-09', ...extra });

test('a right answer moves a word up a box and pushes its review out; a miss sends it back to today', () => {
  assert.deepEqual(v.nextState({ box: 0 }, true, '2026-10-09'), { box: 1, due: '2026-10-10' });
  assert.deepEqual(v.nextState({ box: 3 }, true, '2026-10-09'), { box: 4, due: '2026-10-17' });
  assert.deepEqual(v.nextState({ box: 6 }, true, '2026-10-09'), { box: 6, due: '2026-11-10' }, 'top box stays at the longest interval');
  assert.deepEqual(v.nextState({ box: 4 }, false, '2026-10-09'), { box: 0, due: '2026-10-09' });
  assert.deepEqual(v.nextState({ box: 1 }, true, '2026-12-31'), { box: 2, due: '2027-01-02' }, 'crosses the year');
});

test('stats count due, mastered, and learning words', () => {
  const rows = [W('a'), W('b', { due: '2026-12-01' }), W('c', { box: 5, due: '2026-12-01' }), W('d', { box: 6, due: '2026-10-01' })];
  assert.deepEqual(v.stats(rows, '2026-10-09'), { total: 4, due: 2, mastered: 2, learning: 2 });
});

test('cloze blanks the exact word form and nothing inside other words', () => {
  assert.equal(v.cloze('She ambled down the lane.', 'ambled'), 'She _____ down the lane.');
  assert.equal(v.cloze('Ambled, she went.', 'ambled'), '_____, she went.');
  assert.equal(v.cloze('The scrambled eggs.', 'ambled'), null, 'no match inside another word');
  assert.equal(v.cloze('', 'x'), null);
  assert.equal(v.cloze('A café (old).', 'café'), 'A _____ (old).');
});

test('spelling is forgiving about case, spaces, and curly apostrophes', () => {
  assert.ok(v.spelledRight('  Ambled ', 'ambled'));
  assert.ok(v.spelledRight('o’clock', "o'clock"));
  assert.ok(!v.spelledRight('amblled', 'ambled'));
});

test('a round uses due words first, gives each multiple-choice question one right answer, and fills in with real words', () => {
  const rows = [W('ambled', { sentence: 'She ambled home.' }), W('verdant'), W('later', { due: '2026-12-01' })];
  const round = v.buildRound(rows, { today: '2026-10-09', rand: seeded() });
  assert.deepEqual(round.map((q) => q.word).sort(), ['ambled', 'verdant'], 'only due words when some are due');
  for (const q of round) {
    assert.equal(q.options.length, 4);
    assert.equal(q.options.filter((o) => o.id === q.answer).length, 1);
    assert.equal(new Set(q.options.map((o) => o.id)).size, 4, 'no duplicate options');
  }
  const kids = v.buildRound(rows, { kids: true, today: '2026-10-09', rand: seeded(3) });
  assert.ok(kids.every((q) => q.options.length === 3), 'kids get three choices');
});

test('with nothing due, a round practices the least-known words; words without a meaning are skipped', () => {
  const rows = [W('a', { box: 4, due: '2027-01-01' }), W('b', { box: 1, due: '2027-01-01' }), W('c', { definition: '', due: '2026-01-01' })];
  const round = v.buildRound(rows, { size: 1, today: '2026-10-09', rand: seeded() });
  assert.deepEqual(round.map((q) => q.word), ['b']);
});

test('spelling questions only appear when the device can speak and the word is past box 2', () => {
  const rows = Array.from({ length: 6 }, (_, i) => W(`w${i}`, { box: 3 }));
  const mute = v.buildRound(rows, { canSpeak: false, today: '2026-10-09', rand: seeded() });
  assert.ok(!mute.some((q) => q.kind === 'spell'));
  const speak = v.buildRound(rows, { canSpeak: true, today: '2026-10-09', rand: seeded() });
  assert.ok(speak.some((q) => q.kind === 'spell'));
  const s = speak.find((q) => q.kind === 'spell');
  assert.equal(s.answer, s.word);
  assert.ok(!s.prompt.includes(s.word) || s.prompt === `meaning of ${s.word}`);
});

test('word keys are lowercased and trimmed', () => {
  assert.equal(v.wordKey('  Verdant '), 'verdant');
});

console.log(`\n${passed} word builder checks passed.`);
