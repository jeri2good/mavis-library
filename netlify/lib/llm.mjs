// Small helpers for one-shot AI calls (summaries, recaps, character lists,
// speaker tagging) on the provider configured for Ask Mavis.

import { env } from './shared.mjs';

export function llmConfig() {
  const key = env('LLM_API_KEY');
  if (!key) return null;
  const provider = (env('LLM_PROVIDER') || 'anthropic').toLowerCase() === 'openai' ? 'openai' : 'anthropic';
  return {
    provider, key,
    model: env('LLM_MODEL') || (provider === 'openai' ? 'gpt-4o-mini' : 'claude-haiku-5-5'),
    base: (env('LLM_BASE_URL') || (provider === 'openai' ? 'https://api.openai.com/v1' : 'https://api.anthropic.com')).replace(/\/$/, ''),
  };
}

const official = (cfg) => /api\.openai\.com/.test(cfg.base);
const effort = (cfg) => {
  const set = env('LLM_REASONING_EFFORT');
  if (set) return set === 'default' ? null : set;
  return official(cfg) && /^gpt-5/i.test(cfg.model) ? 'none' : null;
};

/**
 * complete({ system, user, json, maxTokens }) → string (or parsed object when json: true)
 * Keeps calls short so they finish inside a serverless function's time limit.
 */
export async function complete({ system, user, json = false, maxTokens = 900, timeoutMs = 9000 }) {
  const cfg = llmConfig();
  if (!cfg) throw Object.assign(new Error('The AI assistant is not set up. Add LLM_API_KEY in Netlify.'), { status: 503 });
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    let text;
    if (cfg.provider === 'openai') {
      const e = effort(cfg);
      const r = await fetch(`${cfg.base}/chat/completions`, {
        method: 'POST', signal: ctl.signal,
        headers: { authorization: `Bearer ${cfg.key}`, 'content-type': 'application/json' },
        body: JSON.stringify({
          model: cfg.model,
          ...(official(cfg) ? { max_completion_tokens: e === 'none' ? maxTokens : maxTokens * 4 } : { max_tokens: maxTokens }),
          ...(e ? { reasoning_effort: e } : {}),
          ...(json ? { response_format: { type: 'json_object' } } : {}),
          messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
        }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(j.error?.message ? `AI provider: ${j.error.message}` : `AI provider answered ${r.status}.`), { status: 502 });
      text = j.choices?.[0]?.message?.content || '';
    } else {
      const r = await fetch(`${cfg.base}/v1/messages`, {
        method: 'POST', signal: ctl.signal,
        headers: { 'x-api-key': cfg.key, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
        body: JSON.stringify({ model: cfg.model, max_tokens: maxTokens, system: json ? `${system}\nReply with JSON only.` : system, messages: [{ role: 'user', content: user }] }),
      });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw Object.assign(new Error(j.error?.message ? `AI provider: ${j.error.message}` : `AI provider answered ${r.status}.`), { status: 502 });
      text = (j.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('\n');
    }
    text = String(text || '').trim();
    if (!json) return text;
    const m = /\{[\s\S]*\}/.exec(text);
    try { return JSON.parse(m ? m[0] : text); } catch { throw Object.assign(new Error('The AI sent an answer Mavis couldn’t read. Try again.'), { status: 502 }); }
  } catch (err) {
    if (err.name === 'AbortError') throw Object.assign(new Error('The AI took too long. Try again, or try a shorter passage.'), { status: 504 });
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

// ---------- pictures (OpenAI Responses API, background mode) ----------
// Image generation takes longer than a function may run, so it is started in
// the background and the app polls for the finished picture.

export function imageConfig() {
  const cfg = llmConfig();
  if (!cfg || cfg.provider !== 'openai' || !official(cfg)) return null;
  return { ...cfg, model: env('IMAGE_LLM_MODEL') || cfg.model, quality: env('IMAGE_QUALITY') || 'medium', imageModel: env('IMAGE_MODEL') || null };
}

export async function startImage(prompt, { size = '1024x1536' } = {}) {
  const cfg = imageConfig();
  if (!cfg) throw Object.assign(new Error('Pictures need an OpenAI key (LLM_PROVIDER=openai).'), { status: 503 });
  const tool = { type: 'image_generation', size, quality: cfg.quality, output_format: 'webp', output_compression: 82 };
  if (cfg.imageModel) tool.model = cfg.imageModel;
  const r = await fetch(`${cfg.base}/responses`, {
    method: 'POST',
    headers: { authorization: `Bearer ${cfg.key}`, 'content-type': 'application/json' },
    body: JSON.stringify({ model: cfg.model, background: true, input: prompt, tools: [tool], tool_choice: { type: 'image_generation' } }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.id) throw Object.assign(new Error(j.error?.message ? `AI provider: ${j.error.message}` : `AI provider answered ${r.status}.`), { status: 502 });
  return j.id;
}

export async function pollImage(id) {
  const cfg = imageConfig();
  if (!cfg) throw Object.assign(new Error('Pictures need an OpenAI key.'), { status: 503 });
  const r = await fetch(`${cfg.base}/responses/${encodeURIComponent(id)}`, { headers: { authorization: `Bearer ${cfg.key}` } });
  const j = await r.json().catch(() => ({}));
  if (r.status === 404) return { status: 'failed', error: 'That picture request has expired.' };
  if (!r.ok) throw Object.assign(new Error(`AI provider answered ${r.status}.`), { status: 502 });
  if (['queued', 'in_progress'].includes(j.status)) return { status: 'working' };
  const img = (j.output || []).find((o) => o.type === 'image_generation_call' && o.result);
  if (j.status === 'completed' && img) return { status: 'done', image: img.result, revised: img.revised_prompt || '' };
  const why = j.error?.message || j.incomplete_details?.reason || 'The picture couldn’t be made.';
  return { status: 'failed', error: /safety|moderation|policy/i.test(why) ? 'The picture service declined this scene. Try a different passage.' : why };
}

// Added to prompts when the request comes from kids mode.
export const KIDS_RULES = 'The reader is a child (about 6–12) using kids mode. Use short sentences and everyday words a child knows. Be kind, encouraging, and calm. Keep everything suitable for children: if something in the book is frightening or sad, explain it gently and without graphic detail. Never discuss romance, violence, or other mature topics in detail; for questions that are not about the book or that a parent should answer, say kindly that it is a good question for a grown-up. Never ask for or repeat personal information such as their full name, address, school, or contact details.';
