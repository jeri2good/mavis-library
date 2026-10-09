// POST /api/rank — re-rank "You might like" picks with Jev (TypeSafe AI's
// decision model). Uses TypeSafe's own API when TYPESAFE_API_KEY is set,
// otherwise Eden AI with EDENAI_API_KEY. Without either key the app ranks
// picks itself by genre overlap.
// Body: { profile: { genres: [..], recent: [..] }, candidates: [{ id, title, authors, subjects }] }
// Reply: { scores: { [id]: 0..1 }, model }

import { json, fail, onlyPost, requireOwner, readJson, softLimit, clientKey, env } from '../lib/shared.mjs';

const EDEN_URL = 'https://api.edenai.run/v3/alpha/decisions';
const TYPESAFE_URL = 'https://api.typesafe.ai/v1/systemone';

export function jevProvider() {
  if (env('TYPESAFE_API_KEY')) return { name: 'typesafe', url: TYPESAFE_URL, key: env('TYPESAFE_API_KEY'), model: env('JEV_MODEL') || 'jev-latest' };
  if (env('EDENAI_API_KEY')) return { name: 'edenai', url: EDEN_URL, key: env('EDENAI_API_KEY'), model: env('JEV_MODEL') || 'typesafe/jev-latest' };
  return null;
}
const LEVELS = ['not a fit', 'weak fit', 'possible fit', 'good fit', 'excellent fit'];

export function buildRequest(profile, candidates, model = 'typesafe/jev-latest') {
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
  return { model, state, questions };
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
  const jev = jevProvider();
  if (!jev) return fail(503, 'not_configured', 'Jev ranking is not set up. Add TYPESAFE_API_KEY (or EDENAI_API_KEY) in Netlify.');
  if (softLimit(`rank:${clientKey(req, context)}`, { limit: 20 })) return fail(429, 'rate_limited', 'Too many ranking requests.');
  let body;
  try { body = await readJson(req, 60_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const candidates = (Array.isArray(body.candidates) ? body.candidates : []).slice(0, 24).filter((c) => c && c.id && c.title);
  if (!candidates.length) return fail(400, 'bad_request', 'No candidates to rank.');
  try {
    const r = await fetch(jev.url, {
      method: 'POST',
      headers: { authorization: `Bearer ${jev.key}`, 'content-type': 'application/json' },
      body: JSON.stringify(buildRequest(body.profile || {}, candidates, jev.model)),
    });
    const data = await r.json().catch(() => ({}));
    const who = jev.name === 'typesafe' ? 'TypeSafe' : 'Eden AI';
    if (!r.ok) return fail(502, 'jev_failed', data?.message || data?.error?.message || (typeof data?.error === 'string' ? data.error : '') || `${who} answered ${r.status}.`);
    return json({ scores: readScores(data, candidates), model: data.model || 'jev', via: jev.name });
  } catch {
    return fail(502, 'jev_unreachable', 'Could not reach the Jev service.');
  }
};

export const config = {
  path: '/api/rank',
  method: 'POST',
  rateLimit: { windowLimit: 30, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
