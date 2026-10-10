// Real motion for scene films through fal.ai's queue (image-to-video).
// OpenAI's Sora video API was shut down on 2026-09-24, so motion comes from
// fal.ai, where one key reaches current video models. Off unless FAL_KEY is set.
//   FAL_KEY          — fal.ai API key
//   FAL_VIDEO_MODEL  — optional, default fal-ai/kling-video/v2.5-turbo/standard/image-to-video

import { env, clean } from './shared.mjs';

export const DEFAULT_MODEL = 'fal-ai/kling-video/v2.5-turbo/standard/image-to-video';
const QUEUE = 'https://queue.fal.run/';

export function motionConfig() {
  const key = env('FAL_KEY');
  if (!key) return null;
  const model = (env('FAL_VIDEO_MODEL') || DEFAULT_MODEL).replace(/^\/+|\/+$/g, '');
  if (!/^[\w.-]+(\/[\w.-]+){1,6}$/.test(model)) return null;
  return { key, model };
}

const b64u = (s) => Buffer.from(s).toString('base64url');
const unb64u = (s) => Buffer.from(String(s), 'base64url').toString('utf8');
const onQueue = (u) => { try { const x = new URL(u); return x.protocol === 'https:' && x.host === 'queue.fal.run'; } catch { return false; } };

/** Start one shot. Returns an opaque job token the app polls with. */
export async function startMotion({ image, prompt, seconds = 5 }) {
  const cfg = motionConfig();
  if (!cfg) throw Object.assign(new Error('Moving scenes need a fal.ai key (FAL_KEY) in Netlify.'), { status: 503 });
  if (typeof image !== 'string' || !/^data:image\/(webp|png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(image) || image.length > 380_000) {
    throw Object.assign(new Error('The picture for this shot is missing or too large.'), { status: 400 });
  }
  const r = await fetch(QUEUE + cfg.model, {
    method: 'POST',
    headers: { authorization: `Key ${cfg.key}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      prompt: clean(prompt, 900) || 'Gentle natural movement, slow cinematic camera.',
      image_url: image,
      duration: Number(seconds) >= 10 ? '10' : '5',
      negative_prompt: 'text, letters, captions, watermark, blur, distortion, low quality',
    }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.detail ? `Video service: ${typeof j.detail === 'string' ? j.detail : 'request refused'}` : `The video service answered ${r.status}.`), { status: r.status === 401 || r.status === 403 ? 503 : 502 });
  if (!onQueue(j.status_url) || !onQueue(j.response_url)) throw Object.assign(new Error('The video service gave an unexpected answer.'), { status: 502 });
  return b64u(JSON.stringify({ s: j.status_url, r: j.response_url }));
}

/** Poll a shot: { status: 'working' } | { status: 'done', url } | { status: 'failed', error } */
export async function pollMotion(token) {
  const cfg = motionConfig();
  if (!cfg) throw Object.assign(new Error('Moving scenes need a fal.ai key (FAL_KEY).'), { status: 503 });
  let job;
  try { job = JSON.parse(unb64u(token)); } catch { job = null; }
  if (!job || !onQueue(job.s) || !onQueue(job.r)) throw Object.assign(new Error('Unknown video job.'), { status: 400 });
  const H = { authorization: `Key ${cfg.key}` };
  const st = await fetch(job.s, { headers: H });
  const sj = await st.json().catch(() => ({}));
  if (st.status === 404) return { status: 'failed', error: 'That video job has expired.' };
  if (!st.ok && st.status !== 202) throw Object.assign(new Error(`The video service answered ${st.status}.`), { status: 502 });
  if (sj.status === 'IN_QUEUE' || sj.status === 'IN_PROGRESS') return { status: 'working', queue: sj.queue_position ?? null };
  if (sj.status !== 'COMPLETED') return { status: 'failed', error: 'The video service stopped this shot.' };
  const res = await fetch(job.r, { headers: H });
  const rj = await res.json().catch(() => ({}));
  const url = rj?.video?.url;
  let ok = false;
  try { const u = new URL(url); ok = u.protocol === 'https:' && /(^|\.)fal\.media$/.test(u.host); } catch { /* not a url */ }
  if (!res.ok || !ok) {
    const why = JSON.stringify(rj?.detail || rj?.error || '');
    return { status: 'failed', error: /safety|nsfw|moderat|policy/i.test(why) ? 'The video service declined this shot. The still picture is used instead.' : 'This shot couldn’t be animated. The still picture is used instead.' };
  }
  return { status: 'done', url };
}
