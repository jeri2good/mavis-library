// TEMPORARY: live check of the reading companion with the site's real keys.
// ?code=<owner code>            → summary + characters + starts a picture (returns id)
// ?code=<owner code>&id=<resp>  → polls that picture
import { json, env } from '../lib/shared.mjs';
import study from './study.mjs';

export default async (req, context) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const H = { 'content-type': 'application/json', 'x-mavis-access': env('MAVIS_ACCESS_CODE') };
  const call = async (body) => { const t = Date.now(); const r = await study(new Request(`${u.origin}/api/study`, { method: 'POST', headers: H, body: JSON.stringify(body) }), context); return { status: r.status, ms: Date.now() - t, body: await r.json() }; };
  const id = u.searchParams.get('id');
  if (id) {
    const r = await study(new Request(`${u.origin}/api/study?picture=${encodeURIComponent(id)}`, { headers: H }), context);
    const j = await r.json();
    return json({ status: r.status, picture: j.status, error: j.error, imageKB: j.image ? Math.round((j.image.length * 0.75) / 1024) : 0 });
  }
  const text = 'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters. “My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?” Mr. Bennet replied that he had not.';
  const [summary, characters, picture] = await Promise.all([
    call({ task: 'summarize', title: 'Pride and Prejudice', author: 'Jane Austen', chapter: 'Chapter 1', text }),
    call({ task: 'characters', title: 'Pride and Prejudice', author: 'Jane Austen', summaries: [], current: { chapter: 'Chapter 1', text } }),
    call({ task: 'picture', title: 'Pride and Prejudice', author: 'Jane Austen', chapter: 'Chapter 1', passage: text, style: 'watercolor' }),
  ]);
  return json({
    summary: { status: summary.status, ms: summary.ms, text: summary.body.summary || summary.body.message },
    characters: { status: characters.status, ms: characters.ms, names: (characters.body.characters || []).map((c) => `${c.name} (${c.role})`), error: characters.body.message },
    picture: { status: picture.status, ms: picture.ms, id: picture.body.id, error: picture.body.message },
  });
};

export const config = { path: '/api/selftest' };
