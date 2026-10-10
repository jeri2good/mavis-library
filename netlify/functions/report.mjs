// POST /api/report — "Report this" for AI answers, pictures, summaries, and
// scene films (Google Play policy for AI-generated content). Stores a short
// note in Netlify Blobs (store "reports") for the site owner to review.
// Body: { what: answer|picture|summary|film, ref?, text?, version? }
// Reply: { ok, id, message }
// No account, email, IP, or device id is stored with the note.

import { json, fail, onlyPost, readJson, softLimit, clientKey, clean } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';
import { kv } from '../lib/accounts.mjs';
import { randomBytes } from 'node:crypto';

export const KINDS = ['answer', 'picture', 'summary', 'film'];
const MAX_BODY = 4_000;

const handler = async (req, context) => {
  const bad = onlyPost(req);
  if (bad) return bad;
  if (softLimit(`report:${clientKey(req, context)}`, { limit: 10 })) return fail(429, 'rate_limited', 'Too many reports in a short time. Wait a minute.');
  let body;
  try { body = await readJson(req, MAX_BODY); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const what = typeof body?.what === 'string' && KINDS.includes(body.what) ? body.what : null;
  if (!what) return fail(400, 'bad_request', 'Say what you are reporting.');
  const at = new Date().toISOString();
  const note = {
    what,
    ref: clean(body.ref, 300),
    text: clean(body.text, 1000),
    version: /^[\w.+-]{1,40}$/.test(body.version || '') ? body.version : '',
    at,
  };
  const id = `${at.replace(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}`;
  try {
    await kv('reports').setJSON(`r/${id}`, note);
  } catch (err) {
    console.error('report', err);
    return fail(503, 'report_failed', 'Couldn’t send the report right now. Try again later.');
  }
  return json({ ok: true, id, message: 'Thanks. Your report was sent to the site owner.' });
};

export default withCors(handler);

export const config = {
  path: '/api/report',
  method: ['POST', 'OPTIONS'],
  rateLimit: { windowLimit: 20, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
