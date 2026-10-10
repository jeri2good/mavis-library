// /api/study — the reading companion's AI tasks (owner access code required).
//   POST { task: 'summarize', title, author, chapter, text }            → { summary }
//   POST { task: 'recap', title, author, summaries: [{chapter, summary}], current: {chapter, text} } → { recap }
//   POST { task: 'characters', title, author, summaries, current }      → { characters: [...] }
//   POST { task: 'cast', title, author, quotes: [{text, before, after}], known } → { speakers, lines }
//   POST { task: 'picture', title, author, chapter, passage, style }    → { id }   (starts a picture)
//   POST { task: 'word', word, form, sentence, definition, title }      → { meaning, example }
//   POST { task: 'storyboard', title, author, chapter, sentences: [..], shots? } → { look, shots: [{ from, visual, camera, effect, motion }] }
//   POST { task: 'motion', image: dataURI, prompt, seconds }           → { job }  (fal.ai image-to-video)
//   GET  ?motion=<job>                                                  → { status, url?, error? }
//   GET  ?picture=<id>                                                  → { status, image?, revised?, error? }
// Only the text the reader has reached is sent, so answers can't spoil what comes later.

import { json, fail, onlyPost, requireOwner, readJson, softLimit, clientKey, clean } from '../lib/shared.mjs';
import { complete, startImage, pollImage, KIDS_RULES } from '../lib/llm.mjs';
import { vocabulary, dropUnseen, supportedName } from '../lib/textcheck.mjs';
import { startMotion, pollMotion } from '../lib/motion.mjs';

const STYLES = {
  painterly: 'a rich, painterly book illustration in oils, soft natural light',
  watercolor: 'a delicate watercolor illustration with loose washes and fine ink lines',
  pencil: 'a detailed graphite pencil sketch with gentle shading',
  storybook: 'a warm, classic storybook illustration suitable for children',
  'stained glass': 'a luminous stained-glass window design with bold lead lines',
};

const cut = (s, n) => String(s || '').slice(0, n);
const SHAPES = { wide: '1536x1024', tall: '1024x1536', square: '1024x1024' };
const about = (b) => `“${cut(b.title, 200) || 'this book'}”${b.author ? ` by ${cut(b.author, 120)}` : ''}`;

function summariesBlock(list) {
  return (Array.isArray(list) ? list : []).slice(0, 200).map((s, i) => `${i + 1}. ${cut(s.chapter, 120)}: ${cut(s.summary, 900)}`).join('\n');
}

const KIDS_PICTURE = 'This picture is for a child: make it gentle, friendly, and bright, like a classic picture book. Nothing scary, violent, or upsetting.';
const forKids = (system, kids) => (kids ? `${system}\n\n${KIDS_RULES}` : system);

// Words from chapters the reader has already finished (only those), sent by the app.
const seenNames = (b) => (Array.isArray(b.seen) ? b.seen : []).slice(0, 12000).map((w) => cut(w, 32)).join(' ');

const tasks = {
  async word(b, { kids }) {
    const word = clean(b.word, 60);
    if (!word) throw Object.assign(new Error('No word given.'), { status: 400 });
    const out = await complete({
      system: forKids('You explain one English word to a reader in plain, simple words, the way a good teacher would. Return JSON only: {"meaning":"one short sentence (under 20 words) giving the meaning that fits the sentence the word came from","example":"one new, everyday example sentence using the word"}. If the dictionary meaning is given, keep to that sense. Never use the word itself in the meaning.', kids),
      user: `Word: ${word}${b.form && b.form !== word ? ` (appears as “${clean(b.form, 60)}”)` : ''}\n${b.definition ? `Dictionary meaning: ${cut(b.definition, 400)}\n` : ''}${b.sentence ? `Sentence it came from${b.title ? ` (in ${about(b)})` : ''}: “${cut(b.sentence, 400)}”` : ''}`,
      json: true, maxTokens: 220,
    });
    return { meaning: clean(out.meaning, 300), example: clean(out.example, 300) };
  },

  async summarize(b, { kids } = {}) {
    const text = cut(b.text, 30_000);
    if (text.length < 40) return { summary: '' };
    const summary = await complete({
      system: forKids('You summarize one chapter of a book for a reader’s private notes. Write 2–4 plain sentences (under 90 words): what happens, who is involved, and any turning point. Never mention later events. No preamble. Use ONLY the text provided here. Even if you recognize this book, do not use anything you know about it from elsewhere — no names, events, or outcomes that aren’t in the given text.', kids),
      user: `Book: ${about(b)}\nChapter: ${cut(b.chapter, 160)}\n\n"""${text}"""`,
      maxTokens: 260,
    });
    // Spoiler guard: drop any sentence naming someone this chapter doesn't mention.
    return { summary: cut(dropUnseen(summary, vocabulary(text)), 1200) };
  },

  async recap(b, { kids } = {}) {
    const prior = summariesBlock(b.summaries);
    const cur = cut(b.current?.text, 12_000);
    const recap = await complete({
      system: forKids('You are Mavis, a reading companion. Give a warm, spoken-style “story so far” recap for a reader returning to a book, in 120–180 words: main characters, what has happened, and where things stand at the reader’s exact point. Only use what you are given — it ends where the reader stopped; never hint at what happens next. No headings or lists; it will be read aloud. Use ONLY the text provided here. Even if you recognize this book, do not use anything you know about it from elsewhere — no names, events, or outcomes that aren’t in the given text.', kids),
      user: `Book: ${about(b)}\n\nChapter summaries so far:\n${prior || '(this is the first chapter)'}\n\nCurrent chapter: ${cut(b.current?.chapter, 160)}\nText of the current chapter up to where the reader stopped:\n"""${cur}"""`,
      maxTokens: 450,
    });
    return { recap: cut(dropUnseen(recap, vocabulary(prior, cur, seenNames(b))), 3000) };
  },

  async characters(b, { kids } = {}) {
    const prior = summariesBlock(b.summaries);
    const cur = cut(b.current?.text, 10_000);
    const out = await complete({
      system: forKids('You build a character list for a reader, using only the text given (it ends where the reader stopped — never reveal later events). Return JSON: {"characters":[{"name":"","aka":[""],"role":"one short phrase","description":"1–2 sentences, spoiler-free","firstSeen":"chapter name","importance":1-3,"relations":[{"to":"other character name","relation":"short phrase"}]}]}. Include up to 16 characters, most important first (importance 3 = central). Use ONLY the text provided here. Even if you recognize this book, do not use anything you know about it from elsewhere — no names, events, or outcomes that aren’t in the given text. A character the text mentions without naming gets a descriptive name (for example, “the new tenant”, or “the narrator” for an unnamed “I”). Every name you write must appear in the text above.', kids),
      user: `Book: ${about(b)}\n\nChapter summaries so far:\n${prior || '(first chapter)'}\n\nCurrent chapter (${cut(b.current?.chapter, 160)}) up to the reader’s position:\n"""${cur}"""`,
      json: true, maxTokens: 1400,
    });
    const raw = (Array.isArray(out.characters) ? out.characters : []).slice(0, 16).map((c) => ({
      name: clean(c.name, 80), aka: (Array.isArray(c.aka) ? c.aka : []).map((a) => clean(a, 60)).filter(Boolean).slice(0, 4),
      role: clean(c.role, 120), description: clean(c.description, 400), firstSeen: clean(c.firstSeen, 120),
      importance: Math.min(3, Math.max(1, Number(c.importance) || 1)),
      relations: (Array.isArray(c.relations) ? c.relations : []).slice(0, 8).map((r) => ({ to: clean(r.to, 80), relation: clean(r.relation, 80) })).filter((r) => r.to),
    })).filter((c) => c.name);
    // Spoiler guard: every name must appear in what the reader has read. Names are trimmed
    // to the part the text supports; characters the text doesn't name at all are dropped.
    const source = `${prior}\n${cur}`;
    const vocab = vocabulary(source, seenNames(b));
    const renamed = new Map();
    const kept = [];
    for (const c of raw) {
      const name = supportedName(c.name, c.aka, source, vocab);
      if (!name) continue;
      renamed.set(c.name, name);
      kept.push({ ...c, name, aka: c.aka.filter((a) => supportedName(a, [], source, vocab) === a && a !== name) });
    }
    const characters = kept.map((c) => ({
      ...c,
      role: dropUnseen(c.role, vocab, { phrase: true }), description: dropUnseen(c.description, vocab),
      relations: c.relations.map((r) => ({ ...r, to: renamed.get(r.to) || supportedName(r.to, [], source, vocab) })).filter((r) => r.to && dropUnseen(r.relation, vocab, { phrase: true }) === r.relation),
    }));
    return { characters };
  },

  async cast(b) {
    const quotes = (Array.isArray(b.quotes) ? b.quotes : []).slice(0, 60).map((q, i) => ({ i, text: cut(q.text, 260), before: cut(q.before, 200), after: cut(q.after, 140) }));
    if (!quotes.length) return { speakers: {}, lines: [] };
    const known = (Array.isArray(b.known) ? b.known : []).slice(0, 40).map((k) => cut(k, 60)).filter(Boolean);
    const out = await complete({
      system: 'You attribute lines of dialogue in a book to the characters who speak them, using the surrounding text. Return JSON only: {"speakers":{"Name":{"gender":"male|female|unknown","age":"child|young|adult|old"}},"lines":[{"i":0,"speaker":"Name"}]}. Give every line an entry. Use one consistent name per character, reusing names from the known list when they match. If a quoted line is not spoken aloud (a sign, a title, a thought), use "Narrator". Use only the text given.',
      user: `Book: ${about(b)}\nKnown speakers: ${known.join(', ') || '(none yet)'}\n\nLines (i, text, and the words around it):\n${quotes.map((q) => `#${q.i} [${q.before}] «${q.text}» [${q.after}]`).join('\n')}`,
      json: true, maxTokens: 1600,
    });
    const speakers = {};
    for (const [name, info] of Object.entries(out.speakers || {}).slice(0, 40)) {
      const n = clean(name, 60);
      if (!n) continue;
      speakers[n] = { gender: ['male', 'female'].includes(info?.gender) ? info.gender : 'unknown', age: ['child', 'young', 'adult', 'old'].includes(info?.age) ? info.age : 'adult' };
    }
    const lines = (Array.isArray(out.lines) ? out.lines : []).map((l) => ({ i: Number(l.i), speaker: clean(l.speaker, 60) || 'Narrator' }))
      .filter((l) => Number.isInteger(l.i) && l.i >= 0 && l.i < quotes.length);
    return { speakers, lines };
  },

  async picture(b, { kids } = {}) {
    const passage = cut(b.passage, 3500);
    const scene = cut(b.scene, 1200);
    if (passage.length < 30 && scene.length < 20) throw Object.assign(new Error('Choose a page or passage with a bit more text to draw.'), { status: 400 });
    const style = kids ? STYLES.storybook : STYLES[b.style] || STYLES.painterly;
    const size = SHAPES[b.shape] || '1024x1536';
    const prompt = scene ? [
      // One shot of a scene film: a single frame, consistent with the other shots.
      `Create one ${b.shape === 'tall' ? 'vertical' : b.shape === 'square' ? 'square' : 'wide, cinematic'} film still for a scene from ${about(b)}${b.chapter ? ` (${cut(b.chapter, 120)})` : ''}.`,
      `This shot shows: ${scene}`,
      b.look ? `Keep the look consistent with the rest of the film: ${cut(b.look, 800)}` : '',
      `Style: ${style}. Cinematic composition and lighting, as a frame from a beautifully art-directed film.`,
      'Absolutely no text, letters, captions, signatures, or watermarks in the image. Keep it tasteful: no gore.',
      ...(kids ? [KIDS_PICTURE] : []),
      passage ? `For reference, the passage:\n"""${cut(passage, 1500)}"""` : '',
    ].filter(Boolean).join('\n') : [
      `Create one illustration of the scene in this passage from ${about(b)}${b.chapter ? ` (${cut(b.chapter, 120)})` : ''}.`,
      `Style: ${style}.`,
      'Show the setting, the people as the text describes them, the action, mood, and lighting, with period-appropriate clothing and objects.',
      'Absolutely no text, letters, captions, signatures, or watermarks in the image. Keep it tasteful: no gore.',
      ...(kids ? [KIDS_PICTURE] : []),
      `Passage:\n"""${passage}"""`,
    ].join('\n');
    return { id: await startImage(prompt, { size }) };
  },

  // A scene film's shot list: the passage split into a few shots, each with what
  // we see, a camera move, an atmosphere effect, and a short motion description.
  async storyboard(b, { kids } = {}) {
    const sentences = (Array.isArray(b.sentences) ? b.sentences : []).slice(0, 80).map((x) => cut(x, 600)).filter((x) => x.trim());
    const total = sentences.join(' ').length;
    if (total < 40) throw Object.assign(new Error('Choose a passage with a bit more text to film.'), { status: 400 });
    const want = Math.min(5, Math.max(2, Number(b.shots) || (total > 900 ? 4 : total > 350 ? 3 : 2)));
    const out = await complete({
      system: forKids(`You are a film director planning a short, faithful film of one passage from a book, shot by shot. Use ONLY the passage: show what it describes, in its own period and setting, and nothing that happens later. Return JSON only:
{"look":"one paragraph that keeps every shot consistent: each character's appearance exactly as the text describes (or plausible and unremarkable if it doesn't), clothing, setting, era, colour palette, light and weather",
 "shots":[{"from":0,"visual":"what the camera sees in this shot, 1–2 sentences, concrete and visual","camera":"push-in|pull-out|pan-left|pan-right|rise|drift","effect":"none|fog|mist|rain|snow|embers|dust|sparkle|leaves","motion":"what moves in the shot, under 20 words (e.g. 'waves roll in; her shawl lifts in the wind')"}]}
Make exactly ${want} shots. "from" is the number of the sentence where the shot begins; the first shot begins at 0 and each shot begins later than the one before. Never put words, signs, or writing in a shot. Do not name real people or invent characters the passage doesn't have.`, kids),
      user: `Book: ${about(b)}${b.chapter ? `\nSection: ${cut(b.chapter, 160)}` : ''}\n\nPassage, by sentence:\n${sentences.map((x, i) => `${i}. ${x}`).join('\n')}`,
      json: true, maxTokens: 1400,
    });
    const CAMERAS = ['push-in', 'pull-out', 'pan-left', 'pan-right', 'rise', 'drift'];
    const EFFECTS = ['none', 'fog', 'mist', 'rain', 'snow', 'embers', 'dust', 'sparkle', 'leaves'];
    let shots = (Array.isArray(out.shots) ? out.shots : []).slice(0, 6).map((x, i) => ({
      from: Number.isInteger(Number(x.from)) ? Math.max(0, Math.min(sentences.length - 1, Number(x.from))) : null,
      visual: clean(x.visual, 600), motion: clean(x.motion, 160),
      camera: CAMERAS.includes(x.camera) ? x.camera : CAMERAS[i % CAMERAS.length],
      effect: EFFECTS.includes(x.effect) ? x.effect : 'none',
    })).filter((x) => x.visual);
    if (!shots.length) throw Object.assign(new Error('The director came back empty-handed. Try again.'), { status: 502 });
    // Shots must start at 0 and move forward through the passage; otherwise spread them evenly.
    const ordered = shots.every((x, i) => x.from != null && (i === 0 ? x.from === 0 : x.from > shots[i - 1].from));
    if (!ordered) shots = shots.map((x, i) => ({ ...x, from: Math.floor((i * sentences.length) / shots.length) }));
    shots = shots.filter((x, i) => i === 0 || x.from > shots[i - 1].from);
    return { look: clean(out.look, 900), shots };
  },

  async motion(b) {
    return { job: await startMotion({ image: b.image, prompt: b.prompt, seconds: b.seconds }) };
  },
};

export default async (req, context) => {
  if (req.method === 'GET') {
    const bad = await requireOwner(req);
    if (bad) return bad;
    const sp = new URL(req.url).searchParams;
    if (sp.has('motion')) {
      const job = sp.get('motion') || '';
      if (!/^[A-Za-z0-9_-]{20,2000}$/.test(job)) return fail(400, 'bad_request', 'Unknown video job.');
      try { return json(await pollMotion(job)); } catch (err) { return fail(err.status || 502, 'motion_failed', err.message); }
    }
    const id = sp.get('picture') || '';
    if (!/^resp_[A-Za-z0-9]{10,100}$/.test(id)) return fail(400, 'bad_request', 'Unknown picture.');
    try { return json(await pollImage(id)); } catch (err) { return fail(err.status || 502, 'picture_failed', err.message); }
  }
  const bad = onlyPost(req) || await requireOwner(req);
  if (bad) return bad;
  if (softLimit(`study:${clientKey(req, context)}`, { limit: 40 })) return fail(429, 'rate_limited', 'Too many requests in a short time. Wait a minute.');
  let body;
  try { body = await readJson(req, 400_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const fn = body && Object.hasOwn(tasks, body.task) ? tasks[body.task] : null;
  if (!fn) return fail(400, 'bad_request', 'Unknown task.');
  try {
    return json(await fn(body, { kids: req.headers.get('x-mavis-kids') === '1' }));
  } catch (err) {
    return fail(err.status || 502, 'study_failed', err.message || 'The AI request failed.');
  }
};

export const config = {
  path: '/api/study',
  rateLimit: { windowLimit: 60, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
