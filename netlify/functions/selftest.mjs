// TEMPORARY: live check of book clubs against the real Blobs store, using two
// throwaway accounts that are deleted at the end. Removed after verification.
import { json, env } from '../lib/shared.mjs';
import { createUser, deleteUser, signToken, kv } from '../lib/accounts.mjs';
import groups from './groups.mjs';

export default async (req, context) => {
  const u = new URL(req.url);
  if (!env('MAVIS_ACCESS_CODE') || u.searchParams.get('code') !== env('MAVIS_ACCESS_CODE')) return json({ error: 'forbidden' }, { status: 403 });
  const t0 = Date.now();
  const steps = [];
  const tag = Math.random().toString(36).slice(2, 8);
  const a = (await createUser(`selftest-a-${tag}@mavis.invalid`, 'selftest-pass-123')).user;
  const b = (await createUser(`selftest-b-${tag}@mavis.invalid`, 'selftest-pass-123')).user;
  const call = async (user, body) => {
    const r = await groups(new Request('https://mavis-library.netlify.app/api/groups', { method: 'POST', headers: { 'content-type': 'application/json', origin: 'https://mavis-library.netlify.app', authorization: `Bearer ${signToken(user)}` }, body: JSON.stringify(body) }), context);
    const j = await r.json();
    steps.push(`${body.action}:${r.status}`);
    return j;
  };
  let out = {};
  try {
    const c = await call(a, { action: 'create', name: 'Selftest club', displayName: 'A', kind: 'book', book: { key: 'gutenberg:1342', title: 'Pride and Prejudice' } });
    const gid = c.group.id;
    await call(b, { action: 'join', code: c.group.invite.toLowerCase(), displayName: 'B' });
    await call(b, { action: 'progress', gid, percent: 0.3, chapter: 'Chapter 5' });
    await Promise.all(Array.from({ length: 8 }, (_, i) => call(i % 2 ? a : b, { action: 'post', gid, text: `burst ${i}`, percent: 0.1 })));
    const g = await call(a, { action: 'get', gid });
    out = { members: g.members.map((m) => `${m.name}:${m.role}:${m.percent}`), posts: g.posts.length, emailsLeaked: JSON.stringify(g).includes('@mavis.invalid') };
    await call(a, { action: 'leave', gid });
    const after = await call(b, { action: 'get', gid });
    out.newLeader = after.group?.owner === b.id;
    const last = await call(b, { action: 'leave', gid });
    out.deleted = last.deleted === true;
    const left = await kv('mavis-groups').list({ prefix: `g/${gid}/` });
    out.leftoverKeys = left.blobs.length;
  } finally {
    await deleteUser(a); await deleteUser(b);
  }
  return json({ ms: Date.now() - t0, steps, ...out });
};

export const config = { path: '/api/selftest' };
