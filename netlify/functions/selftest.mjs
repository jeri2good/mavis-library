// TEMPORARY: probe Fish Audio's voice library and cloning with the site's key.
// The clone test uses Fish's own synthetic audio (no real person's voice) and
// deletes the test model afterwards.
import { json, env } from '../lib/shared.mjs';

const F = 'https://api.fish.audio';
const auth = () => ({ authorization: `Bearer ${env('FISH_AUDIO_API_KEY')}` });

export default async (req) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const w = u.searchParams.get('w') || 'list';
  if (w === 'list') {
    const q = new URLSearchParams({ page_size: '12', language: 'en', licensed: u.searchParams.get('lic') || 'true', sort_by: 'score' });
    if (u.searchParams.get('tag')) q.set('tag', u.searchParams.get('tag'));
    const r = await fetch(`${F}/model?${q}`, { headers: auth() });
    const j = await r.json().catch(() => ({}));
    return json({
      http: r.status, total: j.total,
      items: (j.items || []).map((m) => ({ id: m._id, title: m.title, tags: (m.tags || []).slice(0, 8), langs: m.languages, sample: (m.samples?.[0]?.audio || '').slice(0, 90), desc: (m.description || '').slice(0, 60), licensed: m.licensed })),
      error: j.message,
    });
  }
  if (w === 'clone') {
    const t0 = Date.now();
    const out = {};
    // 1. Make ~15 s of synthetic speech to stand in for a recording.
    const text = 'This is a short test recording for Mavis Library. The quick brown fox jumps over the lazy dog. A gentle voice reads by lamplight, page after page, until the story is done.';
    const s = await fetch(`${F}/v1/tts`, { method: 'POST', headers: { ...auth(), 'content-type': 'application/json' }, body: JSON.stringify({ text, format: 'mp3' }) });
    const audio = new Uint8Array(await s.arrayBuffer());
    out.sampleStatus = s.status; out.sampleKB = Math.round(audio.length / 1024);
    // 2. Create a private fast-trained model from it.
    const fd = new FormData();
    fd.append('type', 'tts'); fd.append('train_mode', 'fast'); fd.append('title', 'Mavis self-test (delete me)');
    fd.append('visibility', 'private'); fd.append('enhance_audio_quality', 'true'); fd.append('texts', text);
    fd.append('voices', new Blob([audio], { type: 'audio/mpeg' }), 'sample.mp3');
    const c = await fetch(`${F}/model`, { method: 'POST', headers: auth(), body: fd });
    const cj = await c.json().catch(() => ({}));
    out.createStatus = c.status; out.modelId = cj._id; out.state = cj.state; out.createError = cj.message || cj.detail;
    out.ms = Date.now() - t0;
    return json(out);
  }
  if (w === 'clone2') {
    const t0 = Date.now();
    const out = {};
    const cj = { _id: u.searchParams.get('id') };
    const m = await fetch(`${F}/model/${cj._id}`, { headers: auth() });
    const mj = await m.json().catch(() => ({}));
    out.getStatus = m.status; out.state = mj.state; out.visibility = mj.visibility;
    // 3. Speak with it.
    if (cj._id) {
      const t = await fetch(`${F}/v1/tts`, { method: 'POST', headers: { ...auth(), 'content-type': 'application/json' }, body: JSON.stringify({ text: 'Hello from the cloned test voice.', reference_id: cj._id, format: 'mp3' }) });
      out.cloneTtsStatus = t.status; out.cloneTtsKB = Math.round((await t.arrayBuffer()).byteLength / 1024);
      // 4. Delete it.
      const d = await fetch(`${F}/model/${cj._id}`, { method: 'DELETE', headers: auth() });
      out.deleteStatus = d.status;
    }
    out.ms = Date.now() - t0;
    return json(out);
  }
  return json({});
};

export const config = { path: '/api/selftest' };
