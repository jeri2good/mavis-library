// TEMPORARY: live check of the kids-mode word task and kids prompt. Deleted after verification.
import { json, env } from '../lib/shared.mjs';
import study from './study.mjs';

export default async (req, context) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const kids = u.searchParams.get('kids') === '1';
  const t0 = Date.now();
  const r = await study(new Request('https://mavis-library.netlify.app/api/study', {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: 'https://mavis-library.netlify.app', 'x-mavis-access': env('MAVIS_ACCESS_CODE'), ...(kids && { 'x-mavis-kids': '1' }) },
    body: JSON.stringify({ task: 'word', word: 'amble', form: 'ambled', definition: 'To walk slowly or leisurely; to stroll.', sentence: 'The old horse ambled down the lane toward the barn.', title: 'Black Beauty' }),
  }), context);
  return json({ status: r.status, ms: Date.now() - t0, body: await r.json() });
};

export const config = { path: '/api/selftest' };
