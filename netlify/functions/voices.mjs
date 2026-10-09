// /api/voices — cloud voices for read-aloud (Fish Audio), owner access code required.
//   GET  ?list=library&gender=male|female&age=young|middle-aged|old&q=&page=1 → voices licensed by Fish Audio
//   GET  ?list=mine                                                         → the owner's private cloned voices
//   POST multipart { title, consent: "yes", audio: <file>, text? }          → creates a private clone of the speaker's own voice
//   DELETE ?id=<voice id>                                                   → deletes one of the owner's cloned voices
// Cloned voices are private to the owner's Fish Audio account.

import { json, fail, requireOwner, softLimit, clientKey, env, clean } from '../lib/shared.mjs';
import { validVoiceId } from './tts.mjs';

const F = 'https://api.fish.audio';
const MAX_AUDIO = 12 * 1024 * 1024;
const auth = () => ({ authorization: `Bearer ${env('FISH_AUDIO_API_KEY')}` });

const shape = (m) => ({
  id: m._id, title: clean(m.title, 80), description: clean(m.description, 200),
  tags: (m.tags || []).slice(0, 10).map((t) => clean(t, 30)), languages: (m.languages || []).slice(0, 4),
  sample: (m.samples || []).map((x) => x.audio).find((a) => typeof a === 'string' && /^https:\/\//.test(a)) || null,
  state: m.state, mine: false,
});

function sameOrigin(req) {
  const origin = req.headers.get('origin');
  if (!origin) return true;
  try { return new URL(origin).host === new URL(req.url).host; } catch { return false; }
}

export default async (req, context) => {
  const bad = await requireOwner(req);
  if (bad) return bad;
  if (!env('FISH_AUDIO_API_KEY')) return fail(503, 'not_configured', 'Voices need a Fish Audio key (FISH_AUDIO_API_KEY).');
  if (softLimit(`voices:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many requests. Wait a minute.');
  const sp = new URL(req.url).searchParams;
  try {
    if (req.method === 'GET') {
      const mine = sp.get('list') === 'mine';
      const q = new URLSearchParams({ page_size: '24', page_number: String(Math.min(20, Math.max(1, Number(sp.get('page')) || 1))), sort_by: 'score' });
      if (mine) q.set('self', 'true');
      else {
        q.set('language', 'en'); q.set('licensed', 'true');
        const tags = [sp.get('gender'), sp.get('age')].filter((t) => ['male', 'female', 'young', 'middle-aged', 'old'].includes(t));
        for (const t of tags) q.append('tag', t);
        const term = clean(sp.get('q'), 60);
        if (term) q.set('title', term);
      }
      const r = await fetch(`${F}/model?${q}`, { headers: auth() });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) return fail(502, 'upstream_error', `Fish Audio answered ${r.status}.`);
      const items = (j.items || []).filter((m) => m.type === 'tts' || !m.type).map((m) => ({ ...shape(m), mine }));
      return json({ total: j.total ?? items.length, hasMore: !!j.has_more, items }, { cache: mine ? 'no-store' : 'private, max-age=600' });
    }
    if (req.method === 'DELETE') {
      if (!sameOrigin(req)) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
      const id = sp.get('id');
      if (!validVoiceId(id)) return fail(400, 'bad_request', 'Unknown voice.');
      const r = await fetch(`${F}/model/${id}`, { method: 'DELETE', headers: auth() });
      if (!r.ok && r.status !== 404) return fail(502, 'upstream_error', `Fish Audio answered ${r.status}.`);
      return json({ ok: true });
    }
    if (req.method === 'POST') {
      if (!sameOrigin(req)) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
      if (!/multipart\/form-data/.test(req.headers.get('content-type') || '')) return fail(415, 'unsupported_media_type', 'Send the recording as a form upload.');
      const len = Number(req.headers.get('content-length') || 0);
      if (len > MAX_AUDIO + 100_000) return fail(413, 'too_large', 'That recording is too large. Keep it under 2 minutes.');
      const form = await req.formData();
      if (form.get('consent') !== 'yes') return fail(400, 'no_consent', 'Confirm that this is your own voice and that you agree to create a private voice from it.');
      const audio = form.get('audio');
      if (!audio || typeof audio === 'string' || !audio.size) return fail(400, 'no_audio', 'Add a recording.');
      if (audio.size > MAX_AUDIO) return fail(413, 'too_large', 'That recording is too large. Keep it under 2 minutes.');
      if (audio.size < 30_000) return fail(400, 'too_short', 'That recording is too short. Read for at least 20 seconds.');
      if (!/^audio\/|^video\/webm/.test(audio.type || 'audio/')) return fail(415, 'not_audio', 'That file isn’t an audio recording.');
      const fd = new FormData();
      fd.append('type', 'tts');
      fd.append('train_mode', 'fast');
      fd.append('title', clean(form.get('title'), 60) || 'My voice');
      fd.append('description', 'Private voice created in Mavis Library from the owner’s own recording.');
      fd.append('visibility', 'private');
      fd.append('enhance_audio_quality', 'true');
      const text = clean(form.get('text'), 4000);
      if (text) fd.append('texts', text);
      fd.append('voices', audio, audio.name || 'recording.webm');
      const r = await fetch(`${F}/model`, { method: 'POST', headers: auth(), body: fd });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || !j._id) {
        const why = j.message || j.detail || `Fish Audio answered ${r.status}.`;
        return fail(r.status === 402 ? 402 : 502, 'clone_failed', r.status === 402 ? 'Fish Audio says the account needs more credit to make a voice.' : `Couldn’t make the voice: ${clean(String(why), 200)}`);
      }
      return json({ voice: { ...shape(j), mine: true } });
    }
    return fail(405, 'method_not_allowed', 'Use GET, POST, or DELETE.');
  } catch (err) {
    return fail(502, 'upstream_unreachable', 'Fish Audio didn’t answer. Try again in a moment.');
  }
};

export const config = {
  path: '/api/voices',
  rateLimit: { windowLimit: 60, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
