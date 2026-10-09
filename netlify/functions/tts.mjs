// POST /api/tts  { text, speed }  → audio/mpeg
// Turns a short passage into MP3 with the owner's cloud voice so read-aloud
// keeps playing with the screen off and works with car/Bluetooth controls.
// Providers (set by environment variables, never sent to the browser):
//   TTS_PROVIDER=fish   FISH_AUDIO_API_KEY, FISH_AUDIO_VOICE_ID (optional), FISH_AUDIO_MODEL (optional)
//   TTS_PROVIDER=google GOOGLE_TTS_API_KEY, GOOGLE_TTS_VOICE (optional, e.g. en-US-Neural2-D)
// Requires the owner access code (see shared.requireOwner).

import { fail, onlyPost, requireOwner, readJson, softLimit, clientKey, env } from '../lib/shared.mjs';

export const MAX_CHARS = 1800;

export function ttsProvider() {
  const p = (env('TTS_PROVIDER') || '').toLowerCase();
  if (p === 'fish' && env('FISH_AUDIO_API_KEY')) return 'fish';
  if (p === 'google' && env('GOOGLE_TTS_API_KEY')) return 'google';
  if (!p && env('FISH_AUDIO_API_KEY')) return 'fish';
  if (!p && env('GOOGLE_TTS_API_KEY')) return 'google';
  return null;
}

async function fish(text, speed) {
  const body = { text, format: 'mp3', mp3_bitrate: 64, latency: 'balanced', normalize: true, prosody: { speed } };
  const voice = env('FISH_AUDIO_VOICE_ID');
  if (voice) body.reference_id = voice;
  const headers = { authorization: `Bearer ${env('FISH_AUDIO_API_KEY')}`, 'content-type': 'application/json' };
  const model = env('FISH_AUDIO_MODEL');
  if (model) headers.model = model;
  const r = await fetch('https://api.fish.audio/v1/tts', { method: 'POST', headers, body: JSON.stringify(body) });
  if (!r.ok) {
    let msg = `Fish Audio answered ${r.status}.`;
    try { const j = await r.json(); if (j.message) msg = `Fish Audio: ${j.message}`; } catch { /* ignore */ }
    if (r.status === 402) msg = 'Fish Audio says the API balance is empty. Add API credit in your Fish Audio account.';
    throw Object.assign(new Error(msg), { status: 502 });
  }
  return new Uint8Array(await r.arrayBuffer());
}

async function google(text, speed) {
  const voiceName = env('GOOGLE_TTS_VOICE') || 'en-US-Neural2-D';
  const languageCode = voiceName.split('-').slice(0, 2).join('-');
  const r = await fetch(`https://texttospeech.googleapis.com/v1/text:synthesize?key=${encodeURIComponent(env('GOOGLE_TTS_API_KEY'))}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      input: { text },
      voice: { languageCode, name: voiceName },
      audioConfig: { audioEncoding: 'MP3', speakingRate: speed },
    }),
  });
  if (!r.ok) {
    let msg = `Google Text-to-Speech answered ${r.status}.`;
    try { const j = await r.json(); if (j.error?.message) msg = `Google Text-to-Speech: ${j.error.message}`; } catch { /* ignore */ }
    throw Object.assign(new Error(msg), { status: 502 });
  }
  const j = await r.json();
  return Uint8Array.from(Buffer.from(j.audioContent || '', 'base64'));
}

export default async (req, context) => {
  const bad = onlyPost(req) || await requireOwner(req);
  if (bad) return bad;
  const provider = ttsProvider();
  if (!provider) return fail(503, 'not_configured', 'No cloud voice is set up. Add a Fish Audio or Google Text-to-Speech key in Netlify.');
  if (softLimit(`tts:${clientKey(req, context)}`, { limit: 90 })) return fail(429, 'rate_limited', 'Too many voice requests. Wait a minute.');
  let body;
  try { body = await readJson(req, 20_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const text = String(body.text || '').replace(/\s+/g, ' ').trim();
  const speed = Math.min(2, Math.max(0.5, Number(body.speed) || 1));
  if (!text) return fail(400, 'bad_request', 'Nothing to read.');
  if (text.length > MAX_CHARS) return fail(413, 'too_long', `Send at most ${MAX_CHARS} characters at a time.`);
  try {
    const audio = provider === 'fish' ? await fish(text, speed) : await google(text, speed);
    if (!audio.length) return fail(502, 'empty_audio', 'The voice service returned no audio.');
    return new Response(audio, { status: 200, headers: { 'content-type': 'audio/mpeg', 'cache-control': 'private, max-age=86400', 'x-mavis-voice': provider } });
  } catch (err) {
    return fail(err.status || 502, 'voice_failed', err.message || 'The voice service failed.');
  }
};

export const config = {
  path: '/api/tts',
  method: 'POST',
  rateLimit: { windowLimit: 120, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
