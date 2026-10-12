import test from 'node:test';
import assert from 'node:assert/strict';
import { DOMParser } from '@xmldom/xmldom';
import { extractPodcastChapter } from '../../src/lib/podcast-chapter.js';

const parse = (body) => new DOMParser().parseFromString(`<html><body>${body}</body></html>`, 'text/xml');
const toc = [{ label: 'Chapter One', href: 'one.xhtml#one' }, { label: 'Chapter Two', href: 'one.xhtml#two' }];

test('extracts full current chapter, including earlier pages, not next chapter', () => {
  const doc = parse('<h1 id="one">Chapter One</h1><p>First page.</p><p id="current">Later page.</p><p>Final page.</p><h1 id="two">Chapter Two</h1><p>Not this chapter.</p>');
  const result = extractPodcastChapter(doc, toc, doc.getElementById('current'), 'Section 1');
  assert.equal(result.label, 'Chapter One');
  assert.equal(result.text, 'Chapter One\nFirst page.\nLater page.\nFinal page.');
});

test('fragment boundaries choose second chapter when reading it', () => {
  const doc = parse('<h1 id="one">One</h1><p>Excluded</p><h1 id="two">Two</h1><p id="current">Included &amp; plain.</p>');
  const result = extractPodcastChapter(doc, toc, doc.getElementById('current'), 'Section 1');
  assert.equal(result.label, 'Chapter Two');
  assert.equal(result.text, 'Two\nIncluded & plain.');
});

test('falls back to full spine section without clear boundaries; excludes executable/hidden text', () => {
  const doc = parse('<h1>A section</h1><p>Before <b>bold</b>.</p><script>secret()</script><style>bad css</style><p hidden="hidden">Hidden</p><p aria-hidden="true">Also hidden</p><p id="current">After<br/>line.</p>');
  const result = extractPodcastChapter(doc, [], doc.getElementById('current'), 'Section 4');
  assert.equal(result.label, 'Section 4');
  assert.equal(result.text, 'A section\nBefore bold.\nAfter\nline.');
});

test('missing location falls back to full section instead of guessing first chapter', () => {
  const doc = parse('<h1 id="one">One</h1><p>First</p><h1 id="two">Two</h1><p>Second</p>');
  const result = extractPodcastChapter(doc, toc, null, 'Section 1');
  assert.equal(result.label, 'Section 1');
  assert.equal(result.text, 'One\nFirst\nTwo\nSecond');
});

test('does not truncate at the former 24k recap limit', () => {
  const text = 'word '.repeat(10000).trim();
  const doc = parse(`<p>${text}</p>`);
  assert.equal(extractPodcastChapter(doc, [], null, 'Section 1').text, text);
});
