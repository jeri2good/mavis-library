// TEMPORARY: live spoiler check of the reading companion prompts. Removed after verification.
import { json, env } from '../lib/shared.mjs';
import study from './study.mjs';

const TEXT = 'To Mrs. Saville, England. St. Petersburgh, Dec. 11th, 17—. You will rejoice to hear that no disaster has accompanied the commencement of an enterprise which you have regarded with such evil forebodings. I arrived here yesterday, and my first task is to assure my dear sister of my welfare and increasing confidence in the success of my undertaking. I am already far north of London, and as I walk in the streets of Petersburgh, I feel a cold northern breeze play upon my cheeks, which braces my nerves and fills me with delight. Do you understand this feeling? This breeze, which has travelled from the regions towards which I am advancing, gives me a foretaste of those icy climes. Inspirited by this wind of promise, my daydreams become more fervent and vivid. I try in vain to be persuaded that the pole is the seat of frost and desolation; it ever presents itself to my imagination as the region of beauty and delight.';

export default async (req, context) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const task = u.searchParams.get('task') === 'recap' ? 'recap' : 'characters';
  const t0 = Date.now();
  const r = await study(new Request('https://mavis-library.netlify.app/api/study', {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: 'https://mavis-library.netlify.app', 'x-mavis-access': env('MAVIS_ACCESS_CODE') },
    body: JSON.stringify({ task, title: 'Frankenstein; Or, The Modern Prometheus', author: 'Mary Wollstonecraft Shelley', summaries: [], current: { chapter: 'Letter 1', text: TEXT } }),
  }), context);
  const body = await r.json();
  const all = JSON.stringify(body);
  const leaks = ['Victor', 'Frankenstein', 'creature', 'monster', 'Elizabeth', 'Clerval', 'Walton', 'Robert', 'Geneva'].filter((w) => new RegExp(`\\b${w}\\b`, 'i').test(all.replace(/Frankenstein; Or, The Modern Prometheus/g, '')));
  return json({ task, status: r.status, ms: Date.now() - t0, leaks, body });
};

export const config = { path: '/api/selftest' };
