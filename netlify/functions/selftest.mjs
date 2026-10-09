// TEMPORARY: check OpenAI background image generation with the site's key.
// Requires ?code=<owner access code>. ?id=<response id> polls.
import { json, env } from '../lib/shared.mjs';

export default async (req) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const key = env('LLM_API_KEY');
  const model = u.searchParams.get('model') || env('LLM_MODEL');
  const headers = { authorization: `Bearer ${key}`, 'content-type': 'application/json' };
  const id = u.searchParams.get('id');
  const t0 = Date.now();
  if (id) {
    const r = await fetch(`https://api.openai.com/v1/responses/${encodeURIComponent(id)}`, { headers });
    const j = await r.json().catch(() => ({}));
    const img = (j.output || []).find((o) => o.type === 'image_generation_call');
    return json({ http: r.status, status: j.status, error: j.error, outputTypes: (j.output || []).map((o) => `${o.type}:${o.status || ''}`), imageBytes: img?.result ? Math.round(img.result.length * 0.75) : 0, revised: (img?.revised_prompt || '').slice(0, 200), ms: Date.now() - t0 });
  }
  if (u.searchParams.get('w') === 'models') {
    const r = await fetch('https://api.openai.com/v1/models', { headers });
    const j = await r.json().catch(() => ({}));
    return json({ http: r.status, models: (j.data || []).map((m) => m.id).filter((x) => /image|gpt-5|gpt-4\.1|gpt-4o/.test(x)).sort() });
  }
  const r = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST', headers,
    body: JSON.stringify({
      model, background: true,
      input: 'Paint a calm, painterly book illustration (no text, no letters) of a lighthouse keeper climbing a spiral stair at dusk, lantern in hand.',
      tools: [{ type: 'image_generation', size: '1024x1536', quality: 'low', output_format: 'webp' }],
      tool_choice: { type: 'image_generation' },
    }),
  });
  const j = await r.json().catch(() => ({}));
  return json({ http: r.status, id: j.id, status: j.status, error: j.error, model, ms: Date.now() - t0 });
};

export const config = { path: '/api/selftest' };
