// POST /api/assistant — "Ask Mavis", the in-book reading assistant.
// Body: { messages: [{role, content}], context: { title, author, chapter, text, selection, bible } }
// Reply: { reply, actions: [{ name, input }], provider, model }
//
// Configure in Netlify (never in the browser):
//   LLM_PROVIDER = anthropic | openai     (openai = any OpenAI-compatible API)
//   LLM_API_KEY  = provider key
//   LLM_MODEL    = optional model id (defaults below)
//   LLM_BASE_URL = optional, OpenAI-compatible base URL (e.g. a local bridge or OpenRouter)
// Requires the owner access code. Only the passage the reader is on (and any
// selected text) is sent, and only when they ask a question.

import { json, fail, onlyPost, requireOwner, readJson, softLimit, clientKey, env } from '../lib/shared.mjs';

const DEFAULT_MODELS = { anthropic: 'claude-haiku-5-5', openai: 'gpt-4o-mini' };

export function llmConfig() {
  const key = env('LLM_API_KEY');
  if (!key) return null;
  const provider = (env('LLM_PROVIDER') || 'anthropic').toLowerCase() === 'openai' ? 'openai' : 'anthropic';
  return {
    provider,
    key,
    model: env('LLM_MODEL') || DEFAULT_MODELS[provider],
    base: (env('LLM_BASE_URL') || (provider === 'openai' ? 'https://api.openai.com/v1' : 'https://api.anthropic.com')).replace(/\/$/, ''),
  };
}

export const TOOLS = [
  { name: 'read_aloud', description: 'Start reading the book aloud for the reader. Use when they ask you to read, play, or continue listening.', parameters: { type: 'object', properties: { from: { type: 'string', enum: ['here', 'chapter_start'], description: 'Where to start: the current page, or the start of the current chapter.' } }, required: ['from'] } },
  { name: 'stop_reading', description: 'Stop or pause read-aloud.', parameters: { type: 'object', properties: {} } },
  { name: 'go_to', description: 'Open a chapter of the current book by its title or number, or a Bible reference like "John 3:16".', parameters: { type: 'object', properties: { target: { type: 'string' } }, required: ['target'] } },
  { name: 'car_mode', description: 'Switch to car mode: large playback controls for listening while driving.', parameters: { type: 'object', properties: {} } },
  { name: 'define_word', description: 'Show the dictionary entry for a word.', parameters: { type: 'object', properties: { word: { type: 'string' } }, required: ['word'] } },
];

const cut = (s, n) => String(s || '').slice(0, n);

export function systemPrompt(ctx) {
  const lines = [
    'You are Mavis, the reading companion inside the Mavis Library app.',
    'Help the reader understand and enjoy what they are reading: summaries, explanations of passages, characters, themes, historical context, word meanings, and discussion.',
    'Be warm, clear, and brief: usually 2–6 sentences, or a short list for summaries. The reader may be listening in a car, so avoid tables and long lists.',
    'Base summaries on the passage provided. If the passage is not enough to answer, say what you can and note what is missing. Do not invent quotations.',
    'You can control the app with the provided tools (read aloud, stop, go to a chapter or Bible reference, car mode, define a word). Use a tool when the reader asks for that action, and also give a one-line reply saying what you did.',
  ];
  if (ctx.bible) lines.push('The reader is in the Bible. When discussing Scripture, cite references (Book chapter:verse), present differing Christian interpretations fairly when they exist, and do not claim certainty on contested doctrine.');
  lines.push('', `Book: ${cut(ctx.title, 300) || 'unknown'}${ctx.author ? ` by ${cut(ctx.author, 200)}` : ''}`);
  if (ctx.chapter) lines.push(`Current chapter/section: ${cut(ctx.chapter, 200)}`);
  if (ctx.selection) lines.push('', 'Text the reader selected:', '"""', cut(ctx.selection, 3000), '"""');
  if (ctx.text) lines.push('', 'Passage the reader is on (may be partial):', '"""', cut(ctx.text, 24000), '"""');
  return lines.join('\n');
}

function cleanMessages(messages) {
  const out = [];
  for (const m of (Array.isArray(messages) ? messages : []).slice(-12)) {
    const role = m.role === 'assistant' ? 'assistant' : 'user';
    const content = cut(m.content, 4000).trim();
    if (!content) continue;
    if (out.length && out[out.length - 1].role === role) out[out.length - 1].content += `\n\n${content}`;
    else out.push({ role, content });
  }
  while (out.length && out[0].role !== 'user') out.shift();
  return out;
}

async function callAnthropic(cfg, system, messages) {
  const r = await fetch(`${cfg.base}/v1/messages`, {
    method: 'POST',
    headers: { 'x-api-key': cfg.key, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
    body: JSON.stringify({
      model: cfg.model, max_tokens: 900, system, messages,
      tools: TOOLS.map((t) => ({ name: t.name, description: t.description, input_schema: t.parameters })),
    }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error?.message ? `AI provider: ${j.error.message}` : `AI provider answered ${r.status}.`), { status: 502 });
  const reply = (j.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim();
  const actions = (j.content || []).filter((b) => b.type === 'tool_use').map((b) => ({ name: b.name, input: b.input || {} }));
  return { reply, actions };
}

async function callOpenAI(cfg, system, messages) {
  const r = await fetch(`${cfg.base}/chat/completions`, {
    method: 'POST',
    headers: { authorization: `Bearer ${cfg.key}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      model: cfg.model,
      // OpenAI's current models take max_completion_tokens (which also covers
      // their internal reasoning); other compatible APIs use max_tokens.
      ...(/api\.openai\.com/.test(cfg.base) ? { max_completion_tokens: 4000 } : { max_tokens: 900 }),
      messages: [{ role: 'system', content: system }, ...messages],
      tools: TOOLS.map((t) => ({ type: 'function', function: t })),
    }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error?.message ? `AI provider: ${j.error.message}` : `AI provider answered ${r.status}.`), { status: 502 });
  const msg = j.choices?.[0]?.message || {};
  const actions = (msg.tool_calls || []).map((c) => {
    let input = {};
    try { input = JSON.parse(c.function?.arguments || '{}'); } catch { /* ignore */ }
    return { name: c.function?.name, input };
  });
  const reply = (typeof msg.content === 'string' ? msg.content : '').trim();
  if (!reply && !actions.length && j.choices?.[0]?.finish_reason === 'length') {
    throw Object.assign(new Error('The AI ran out of room before answering. Try a shorter question.'), { status: 502 });
  }
  return { reply, actions };
}

export default async (req, context) => {
  const bad = onlyPost(req) || await requireOwner(req);
  if (bad) return bad;
  const cfg = llmConfig();
  if (!cfg) return fail(503, 'not_configured', 'The AI assistant is not set up. Add LLM_API_KEY in Netlify.');
  if (softLimit(`ai:${clientKey(req, context)}`, { limit: 20 })) return fail(429, 'rate_limited', 'Too many questions in a short time. Wait a minute.');
  let body;
  try { body = await readJson(req, 120_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const messages = cleanMessages(body.messages);
  if (!messages.length) return fail(400, 'bad_request', 'Ask a question.');
  const ctx = body.context && typeof body.context === 'object' ? body.context : {};
  try {
    const system = systemPrompt(ctx);
    const out = cfg.provider === 'openai' ? await callOpenAI(cfg, system, messages) : await callAnthropic(cfg, system, messages);
    const allowed = new Set(TOOLS.map((t) => t.name));
    out.actions = out.actions.filter((a) => allowed.has(a.name)).slice(0, 3);
    return json({ ...out, provider: cfg.provider, model: cfg.model });
  } catch (err) {
    return fail(err.status || 502, 'assistant_failed', err.message || 'The AI provider failed.');
  }
};

export const config = {
  path: '/api/assistant',
  method: 'POST',
  rateLimit: { windowLimit: 30, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
