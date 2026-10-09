// POST /api/rank — re-rank "You might like" picks with Jev (TypeSafe AI's
// decision model) through Eden AI. Optional: without EDENAI_API_KEY the app
// ranks picks itself by genre overlap.
// Body: { profile: { genres: [..], recent: [..] }, candidates: [{ id, title, authors, subjects }] }
// Reply: { scores: { [id]: 0..1 }, model }

import { json, fail, onlyPost, requireOwner, readJson, softLimit, clientKey, env } from '../lib/shared.mjs';

const URL_ = 'https://api.edenai.run/v3/alpha/decisions';
const LEVELS = ['not a fit', 'weak fit', 'possible fit', 'good fit', 'excellent fit'];

export function buildRequest(profile, candidates) {
  const state = {
    reader_likes_genres: (profile.genres || []).slice(0, 12).map((g) => String(g).slice(0, 80)),
    reader_recently_read: (profile.recent || []).slice(0, 8).map((t) => String(t).slice(0, 160)),
  };
  const questions = {};
  candidates.forEach((c, i) => {
    questions[`c${i}`] = {
      type: 'score',
      instructions: `How well does this book match the reader's tastes? Book: "${String(c.title).slice(0, 200)}" by ${(c.authors || []).join(', ').slice(0, 120) || 'unknown'}; subjects: ${(c.subjects || []).slice(0, 6).join('; ').slice(0, 400)}`,
      criteria: LEVELS,
    };
  });
  return { model: env('JEV_MODEL') || 'typesafe/jev-latest', state, questions };
}

export function readScores(data, candidates) {
  const answers = data.answers || data;
  const scores = {};
  candidates.forEach((c, i) => {
    const a = answers?.[`c${i}`];
    if (a && Number.isFinite(Number(a.score))) scores[c.id] = Math.max(0, Math.min(1, Number(a.score) / (LEVELS.length - 1)));
  });
  return scores;
}

export default async (req, context) => {
  const bad = onlyPost(req) || await requireOwner(req);
  if (bad) return bad;
  const key = env('EDENAI_API_KEY');
  if (!key) return fail(503, 'not_configured', 'Jev ranking is not set up. Add EDENAI_API_KEY in Netlify.');
  if (softLimit(`rank:${clientKey(req, context)}`, { limit: 20 })) return fail(429, 'rate_limited', 'Too many ranking requests.');
  let body;
  try { body = await readJson(req, 60_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const candidates = (Array.isArray(body.candidates) ? body.candidates : []).slice(0, 24).filter((c) => c && c.id && c.title);
  if (!candidates.length) return fail(400, 'bad_request', 'No candidates to rank.');
  try {
    const r = await fetch(URL_, {
      method: 'POST',
      headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
      body: JSON.stringify(buildRequest(body.profile || {}, candidates)),
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) return fail(502, 'jev_failed', data?.message || data?.error?.message || `Eden AI answered ${r.status}.`);
    return json({ scores: readScores(data, candidates), model: data.model || 'jev' });
  } catch {
    return fail(502, 'jev_unreachable', 'Could not reach Eden AI.');
  }
};

export const config = {
  path: '/api/rank',
  method: 'POST',
  rateLimit: { windowLimit: 30, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
