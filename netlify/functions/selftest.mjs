// TEMPORARY: check whether the configured OpenAI key can use the Videos API (Sora). Removed after.
import { json, env } from '../lib/shared.mjs';
import { llmConfig } from '../lib/llm.mjs';

export default async (req) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const cfg = llmConfig();
  const H = { authorization: `Bearer ${cfg.key}` };
  const step = u.searchParams.get('step') || 'models';
  if (step === 'models') {
    const r = await fetch(`${cfg.base}/models`, { headers: H });
    const j = await r.json().catch(() => ({}));
    return json({ status: r.status, video: (j.data || []).map((m) => m.id).filter((id) => /sora|video|veo/i.test(id)), count: (j.data || []).length });
  }
  if (step === 'create') {
    const fd = new FormData();
    fd.set('model', u.searchParams.get('model') || 'sora-2');
    fd.set('prompt', 'A slow cinematic dolly shot up a spiral stone staircase inside an old lighthouse at dusk, a woman in a wool shawl carrying an oil lantern, warm lamplight on whitewashed walls, fog beyond a small window. Painterly, period setting, no text.');
    fd.set('seconds', '4');
    fd.set('size', '1280x720');
    const r = await fetch(`${cfg.base}/videos`, { method: 'POST', headers: H, body: fd });
    return json({ status: r.status, body: await r.json().catch(() => null) });
  }
  if (step === 'poll') {
    const r = await fetch(`${cfg.base}/videos/${encodeURIComponent(u.searchParams.get('id'))}`, { headers: H });
    return json({ status: r.status, body: await r.json().catch(() => null) });
  }
  if (step === 'content') {
    const t0 = Date.now();
    const r = await fetch(`${cfg.base}/videos/${encodeURIComponent(u.searchParams.get('id'))}/content`, { headers: H });
    const buf = r.ok ? await r.arrayBuffer() : null;
    return json({ status: r.status, type: r.headers.get('content-type'), bytes: buf?.byteLength || 0, ms: Date.now() - t0, err: r.ok ? null : await r.text().catch(() => '') });
  }
  return json({ error: 'step?' });
};

export const config = { path: '/api/selftest' };
