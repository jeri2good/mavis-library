// Full-cast narration: split a chapter into narration and quoted dialogue
// (the words are never changed), ask the AI only who says each quoted line,
// and give every character a voice. Results are cached per chapter on the
// device so a chapter is analyzed once.

import * as store from './store.js';
import { ownerPost } from './features.js';
import { assignVoices } from './voices.js';

const OPEN = '“"';
const CLOSE = { '“': '”', '"': '"' };

/** Split text into [{ kind: 'narration'|'quote', text }] keeping every character in order. */
export function splitDialogue(text) {
  const out = [];
  let i = 0, start = 0;
  const s = String(text || '');
  while (i < s.length) {
    const ch = s[i];
    if (OPEN.includes(ch)) {
      const close = s.indexOf(CLOSE[ch], i + 1);
      // A straight quote can't close itself if it opens a word inside a quote; require a reasonable span.
      if (close > i + 1 && close - i < 1200) {
        if (i > start) out.push({ kind: 'narration', text: s.slice(start, i) });
        out.push({ kind: 'quote', text: s.slice(i, close + 1) });
        i = close + 1; start = i;
        continue;
      }
    }
    i++;
  }
  if (start < s.length) out.push({ kind: 'narration', text: s.slice(start) });
  return out.filter((x) => x.text.trim());
}

const castKey = (key) => `cast|${store.getOwner()}|${key}`;
const secKey = (key, idx) => `castsec|${store.getOwner()}|${key}|${idx}`;

export async function getCast(key) { return (await store.getCache(castKey(key))) || {}; }
export async function setCast(key, cast) { await store.setCache(castKey(key), cast); }

/**
 * Attribute every quote in a section. blocks: [{ text, cfi }]
 * Returns { blocks: [{ cfi, segments: [{ kind, text, speaker? }] }], cast }
 */
export async function castSection({ key, title, author, sectionIndex, blocks }) {
  const cached = await store.getCache(secKey(key, sectionIndex));
  const segBlocks = blocks.map((b) => ({ cfi: b.cfi, segments: splitDialogue(b.text) }));
  const quotes = [];
  segBlocks.forEach((b, bi) => b.segments.forEach((seg, si) => {
    if (seg.kind !== 'quote' || seg.text.replace(/[“”"\s]/g, '').length < 2) return;
    const before = b.segments.slice(Math.max(0, si - 1), si).map((x) => x.text).join('').slice(-200);
    const after = b.segments.slice(si + 1, si + 2).map((x) => x.text).join('').slice(0, 140);
    quotes.push({ bi, si, text: seg.text, before, after });
  }));
  let speakers = cached?.speakers || {};
  let lineSpeakers = cached?.lines || null;
  if (!lineSpeakers || lineSpeakers.length !== quotes.length) {
    lineSpeakers = new Array(quotes.length).fill('Narrator');
    const cast = await getCast(key);
    for (let i = 0; i < quotes.length; i += 60) {
      const part = quotes.slice(i, i + 60);
      const res = await ownerPost('/api/study', {
        task: 'cast', title, author,
        quotes: part.map((q) => ({ text: q.text, before: q.before, after: q.after })),
        known: [...new Set([...Object.keys(cast), ...Object.keys(speakers)])],
      });
      speakers = { ...speakers, ...res.speakers };
      for (const l of res.lines || []) lineSpeakers[i + l.i] = l.speaker;
    }
    await store.setCache(secKey(key, sectionIndex), { speakers, lines: lineSpeakers });
  }
  quotes.forEach((q, n) => { segBlocks[q.bi].segments[q.si].speaker = lineSpeakers[n]; });
  const cast = await assignVoices(speakers, await getCast(key));
  await setCast(key, cast);
  return { blocks: segBlocks, cast };
}
