import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
// Bible helpers are pure; stub the Vite-only URL dependency, without network access.
const source = (await readFile(new URL('../../src/lib/bible.js', import.meta.url), 'utf8'))
  .replace("import { apiUrl } from './api.js';", 'const apiUrl = p => p;');
const B = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
test('chapter payload has Scripture reference, translation and numbered whole chapter', () => {
  const p = B.bibleLessonPayload('John', 3, 'WEB', ['First verse.', 'Second verse.']);
  assert.deepEqual(p, { kind: 'chapter', book: 'Bible', chapter: 'John 3', text: 'John 3 (WEB)\n\n1 First verse.\n2 Second verse.', originalChars: 44 });
  assert.match(`${p.book} - ${p.chapter}`, /John 3/);
});
test('Psalms reference uses Psalm for lesson title', () => {
  assert.equal(B.bibleLessonPayload('Psalms', 23, 'KJV', ['Psalm']).chapter, 'Psalm 23');
});
test('payload caps text but preserves original character count', () => {
  const p = B.bibleLessonPayload('John', 3, 'KJV', ['x'.repeat(400001)]);
  assert.equal(p.text.length, 400000);
  assert.equal(p.originalChars, 'John 3 (KJV)\n\n1 '.length + 400001);
});
test('word targets preserve whitespace, punctuation and stable repeated indices', () => {
  const text = '  The word,\n the word. ';
  const parts = B.verseWords(text);
  assert.equal(parts.map(t => t.text).join(''), text);
  assert.deepEqual(parts.filter(t => t.word != null).map(t => t.word), [0, 1, 2, 3]);
});
test('KJV mapping leaves untagged function words and italics without direct Strong’s', () => {
  const raw = 'In{G1722} the beginning{G746} was{} God.{G2316}';
  const parts = B.verseWords(B.plain(raw), raw).filter(t => t.word != null);
  assert.deepEqual(parts.map(t => t.strongs), [['G1722'], null, ['G746'], null, ['G2316']]);
});
const idx = { byId: new Map([['John', { name: 'John' }]]) };
const rows = [
  { kind: 'note', cfi: 'John.3.16', text: 'Love', note: 'Remember', createdAt: 1 },
  { kind: 'wordhl', cfi: 'John.3.17', text: 'world', word: 2, createdAt: 3 },
  { kind: 'quote', cfi: 'John.3.16', text: 'Loved', createdAt: 2 },
  { kind: 'other', cfi: 'John.3.16', createdAt: 4 },
];
test('My Bible filters supported kinds and sorts newest first without mutating input', () => {
  assert.deepEqual(B.mineAnnotations(rows, idx).map(a => a.kind), ['wordhl', 'quote', 'note']);
  assert.equal(rows[0].kind, 'note');
});
test('My Bible search matches reference, word and note case-insensitively', () => {
  assert.equal(B.mineAnnotations(rows, idx, 'John 3:16').length, 2);
  assert.equal(B.mineAnnotations(rows, idx, ' WORLD ')[0].kind, 'wordhl');
  assert.equal(B.mineAnnotations(rows, idx, 'remember')[0].kind, 'note');
  assert.equal(B.mineAnnotations(rows, idx, 'absent').length, 0);
});
