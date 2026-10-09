// TEMPORARY: one-off live check of the paid features (voice, Ask Mavis, Jev).
// Requires ?code=<owner access code>. Reports only pass/fail details, never keys.
import { json, env } from '../lib/shared.mjs';
import tts from './tts.mjs';
import assistant from './assistant.mjs';
import rank from './rank.mjs';

export default async (req, context) => {
  const url = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || url.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const post = (path, body) => new Request(`${url.origin}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-mavis-access': env('MAVIS_ACCESS_CODE') },
    body: JSON.stringify(body),
  });
  const which = url.searchParams.get('only') || 'all';
  const out = {};
  const t = Date.now();
  await Promise.all([
    (which === 'all' || which === 'tts') && (async () => {
      const r = await tts(post('/api/tts', { text: 'Testing Mavis Library.', speed: 1 }), context);
      const buf = new Uint8Array(await r.arrayBuffer());
      out.tts = { status: r.status, type: r.headers.get('content-type'), bytes: buf.length, ms: Date.now() - t, error: r.ok ? undefined : new TextDecoder().decode(buf).slice(0, 300) };
    })(),
    (which === 'all' || which === 'ai') && (async () => {
      const r = await assistant(post('/api/assistant', { messages: [{ role: 'user', content: 'In one short sentence, who wrote this book?' }], context: { title: 'Pride and Prejudice', author: 'Jane Austen', chapter: 'Chapter 1', text: 'It is a truth universally acknowledged...' } }), context);
      const j = await r.json().catch(() => ({}));
      out.assistant = { status: r.status, model: j.model, reply: (j.reply || '').slice(0, 160), actions: (j.actions || []).map((a) => a.name), error: j.message, ms: Date.now() - t };
    })(),
    (which === 'all' || which === 'jev') && (async () => {
      const r = await rank(post('/api/rank', { profile: { genres: ['mystery', 'adventure'] }, candidates: [{ id: 'g:1661', title: 'The Adventures of Sherlock Holmes', author: 'Arthur Conan Doyle', subjects: ['Detective and mystery stories'] }, { id: 'g:1342', title: 'Pride and Prejudice', author: 'Jane Austen', subjects: ['Love stories'] }] }), context);
      const j = await r.json().catch(() => ({}));
      out.jev = { status: r.status, scores: j.scores || j.ranked || j, error: j.message, ms: Date.now() - t };
    })(),
  ].filter(Boolean));
  return json(out);
};

export const config = { path: '/api/selftest' };
