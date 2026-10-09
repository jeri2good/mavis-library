// /api/groups — book clubs and Bible study groups, stored in Netlify Blobs.
// Every request needs a signed-in account (Bearer token). Members see each
// other only by the display name they chose for the group — never by email.
//
//   POST { action: 'create', name, kind: 'book'|'bible', book?, planId?, about?, displayName } → { group }
//   POST { action: 'join', code, displayName }          → { group }
//   POST { action: 'list' }                             → { groups: [...] }
//   POST { action: 'get', gid, since? }                 → { group, members, posts, cursor }
//   POST { action: 'post', gid, text, quote?, cite?, ref?, chapter?, percent?, parent? } → { post }
//   POST { action: 'delete', gid, postId }              → { ok }
//   POST { action: 'progress', gid, percent, chapter }  → { ok }
//   POST { action: 'leave', gid }                       → { ok }
//   POST { action: 'invite', gid }        (leader)      → { invite }   new invite code
//   POST { action: 'remove', gid, uid }   (leader)      → { ok }
//   POST { action: 'update', gid, name?, about?, planId? } (leader) → { group }
//   POST { action: 'questions', gid, title, author, chapter, text, percent } (owner access code) → { post }
//
// Storage (store "mavis-groups"):
//   g/<gid>/meta · g/<gid>/m/<uid> (member + their progress) · g/<gid>/p/<ms>-<hex> (posts, append-only)
//   inv/<CODE> → gid · u/<uid>/<gid> (a user's groups)
// Each key has one writer at a time (a member writes their own record; posts
// are new keys), so concurrent use never overwrites someone else's change.

import { randomBytes } from 'node:crypto';
import { json, fail, onlyPost, readJson, softLimit, clientKey, clean, requireOwner } from '../lib/shared.mjs';
import { kv, requireUser } from '../lib/accounts.mjs';
import { complete, KIDS_RULES } from '../lib/llm.mjs';

const MAX_GROUPS_PER_USER = 30;
const MAX_MEMBERS = 50;
const MAX_POSTS_RETURNED = 400;
const KINDS = new Set(['book', 'bible']);
const PLAN_IDS = new Set(['bible-year', 'ot-nt-year', 'nt-90', 'gospels-30', 'psalms-proverbs-60', 'proverbs-31']);

const store = () => kv('mavis-groups');
const hex = (n) => randomBytes(n).toString('hex');
const pad = (ms) => String(Math.floor(ms)).padStart(13, '0');
const validGid = (g) => typeof g === 'string' && /^[0-9a-f]{16}$/.test(g);
const validPostId = (p) => typeof p === 'string' && /^\d{13}-[0-9a-f]{8}$/.test(p);
const ALPHA = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I
export function newInvite() {
  const b = randomBytes(8);
  const s = [...b].map((x) => ALPHA[x % ALPHA.length]).join('');
  return `${s.slice(0, 4)}-${s.slice(4)}`;
}
export const normInvite = (c) => {
  const s = String(c || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
  return s.length === 8 ? `${s.slice(0, 4)}-${s.slice(4)}` : '';
};

const pct = (v) => (Number.isFinite(Number(v)) ? Math.min(1, Math.max(0, Number(v))) : null);

function cleanBook(b) {
  if (!b || typeof b !== 'object') return null;
  const key = clean(b.key, 120);
  if (!key) return null;
  const coverUrl = typeof b.coverUrl === 'string' && /^https:\/\/[\w.-]+\//.test(b.coverUrl) ? b.coverUrl.slice(0, 400) : null;
  return {
    key, title: clean(b.title, 200) || 'Untitled', authors: (Array.isArray(b.authors) ? b.authors : []).map((a) => clean(typeof a === 'string' ? a : a?.name, 120)).filter(Boolean).slice(0, 4),
    source: clean(b.source, 20), sourceId: clean(b.sourceId, 40), coverUrl,
  };
}

function cleanRef(r) {
  if (!r || typeof r !== 'object') return null;
  if (r.kind === 'bible') { const osis = clean(r.osis, 40); return /^[1-3]?[A-Za-z]+\.\d{1,3}(\.\d{1,3})?(-[1-3]?[A-Za-z]+\.\d{1,3}\.\d{1,3})?$/.test(osis) ? { kind: 'bible', osis } : null; }
  if (r.kind === 'book') { const key = clean(r.key, 120); return key ? { kind: 'book', key, cfi: clean(r.cfi, 600) } : null; }
  return null;
}

async function getMeta(s, gid) { return validGid(gid) ? s.get(`g/${gid}/meta`, { type: 'json' }) : null; }
async function getMember(s, gid, uid) { return s.get(`g/${gid}/m/${uid}`, { type: 'json' }); }
async function listKeys(s, prefix) {
  const { blobs } = await s.list({ prefix });
  return blobs.map((b) => b.key).sort();
}
const publicGroup = (m) => ({ id: m.id, name: m.name, kind: m.kind, about: m.about || '', book: m.book || null, planId: m.planId || null, startDate: m.startDate || null, createdAt: m.createdAt, invite: m.invite, owner: m.owner });

async function membership(s, gid, uid) {
  const meta = await getMeta(s, gid);
  if (!meta) return { response: fail(404, 'not_found', 'That group doesn’t exist any more.') };
  const me = await getMember(s, gid, uid);
  if (!me) return { response: fail(403, 'not_member', 'You’re not in this group. Ask for an invite code.') };
  return { meta, me };
}

async function writePost(s, gid, post) {
  const id = `${pad(Date.now())}-${hex(4)}`;
  const full = { ...post, id, createdAt: Date.now() };
  await s.setJSON(`g/${gid}/p/${id}`, full);
  return full;
}

async function deleteGroup(s, gid, meta) {
  const keys = await listKeys(s, `g/${gid}/`);
  for (const k of keys) {
    if (k.includes('/m/')) await s.delete(`u/${k.split('/m/')[1]}/${gid}`);
    await s.delete(k);
  }
  if (meta?.invite) await s.delete(`inv/${meta.invite}`);
}

const displayName = (v) => clean(v, 40);

const actions = {
  async create(s, user, b) {
    const name = clean(b.name, 80);
    const dn = displayName(b.displayName);
    if (!name) return fail(400, 'bad_request', 'Give the group a name.');
    if (!dn) return fail(400, 'bad_request', 'Choose the name other members will see.');
    const kind = KINDS.has(b.kind) ? b.kind : 'book';
    const book = kind === 'book' ? cleanBook(b.book) : null;
    if (kind === 'book' && !book) return fail(400, 'bad_request', 'Choose the book the group will read.');
    const planId = kind === 'bible' && PLAN_IDS.has(b.planId) ? b.planId : null;
    if ((await listKeys(s, `u/${user.id}/`)).length >= MAX_GROUPS_PER_USER) return fail(409, 'too_many', `You can be in up to ${MAX_GROUPS_PER_USER} groups.`);
    const gid = hex(8);
    let invite = '';
    for (let i = 0; i < 5 && !invite; i++) {
      const c = newInvite();
      if ((await s.setJSON(`inv/${c}`, { gid }, { onlyIfNew: true })).modified) invite = c;
    }
    if (!invite) return fail(503, 'busy', 'Couldn’t make an invite code. Try again.');
    const startDate = /^\d{4}-\d{2}-\d{2}$/.test(b.startDate || '') ? b.startDate : new Date().toISOString().slice(0, 10);
    const meta = { id: gid, name, kind, book, planId, startDate: planId ? startDate : null, about: clean(b.about, 500), owner: user.id, createdAt: Date.now(), invite };
    await s.setJSON(`g/${gid}/meta`, meta);
    await s.setJSON(`g/${gid}/m/${user.id}`, { uid: user.id, name: dn, role: 'leader', joinedAt: Date.now() });
    await s.setJSON(`u/${user.id}/${gid}`, { joinedAt: Date.now() });
    return json({ group: publicGroup(meta) });
  },

  async join(s, user, b) {
    const code = normInvite(b.code);
    const dn = displayName(b.displayName);
    if (!code) return fail(400, 'bad_request', 'Invite codes look like ABCD-EF23.');
    if (!dn) return fail(400, 'bad_request', 'Choose the name other members will see.');
    const inv = await s.get(`inv/${code}`, { type: 'json' });
    const meta = inv && await getMeta(s, inv.gid);
    if (!meta || meta.invite !== code) return fail(404, 'bad_invite', 'That invite code isn’t valid. Check it with the person who sent it.');
    const gid = meta.id;
    const existing = await getMember(s, gid, user.id);
    if (!existing) {
      if ((await listKeys(s, `g/${gid}/m/`)).length >= MAX_MEMBERS) return fail(409, 'full', `This group is full (${MAX_MEMBERS} members).`);
      if ((await listKeys(s, `u/${user.id}/`)).length >= MAX_GROUPS_PER_USER) return fail(409, 'too_many', `You can be in up to ${MAX_GROUPS_PER_USER} groups.`);
    }
    await s.setJSON(`g/${gid}/m/${user.id}`, { ...(existing || { uid: user.id, role: 'member', joinedAt: Date.now() }), name: dn });
    await s.setJSON(`u/${user.id}/${gid}`, { joinedAt: existing?.joinedAt || Date.now() });
    return json({ group: publicGroup(meta), rejoined: !!existing });
  },

  async list(s, user) {
    const gids = (await listKeys(s, `u/${user.id}/`)).map((k) => k.split('/')[2]).filter(validGid);
    const groups = (await Promise.all(gids.map(async (gid) => {
      const meta = await getMeta(s, gid);
      if (!meta) { await s.delete(`u/${user.id}/${gid}`); return null; }
      const [members, posts] = await Promise.all([listKeys(s, `g/${gid}/m/`), listKeys(s, `g/${gid}/p/`)]);
      return { ...publicGroup(meta), members: members.length, posts: posts.length, lastPostAt: posts.length ? Number(posts[posts.length - 1].split('/p/')[1].slice(0, 13)) : null };
    }))).filter(Boolean);
    groups.sort((a, b) => (b.lastPostAt || b.createdAt) - (a.lastPostAt || a.createdAt));
    return json({ groups });
  },

  async get(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    const gid = m.meta.id;
    const since = validPostId(b.since) ? b.since : '';
    const [memberKeys, postKeys] = await Promise.all([listKeys(s, `g/${gid}/m/`), listKeys(s, `g/${gid}/p/`)]);
    const wanted = postKeys.filter((k) => k.split('/p/')[1] > since).slice(-MAX_POSTS_RETURNED);
    const [members, posts] = await Promise.all([
      Promise.all(memberKeys.map((k) => s.get(k, { type: 'json' }))),
      Promise.all(wanted.map((k) => s.get(k, { type: 'json' }))),
    ]);
    return json({
      group: publicGroup(m.meta),
      you: user.id,
      members: members.filter(Boolean).map((x) => ({ uid: x.uid, name: x.name, role: x.role, joinedAt: x.joinedAt, percent: x.percent ?? null, chapter: x.chapter || '', progressAt: x.progressAt || null, plan: x.plan || null })),
      posts: posts.filter(Boolean),
      cursor: postKeys.length ? postKeys[postKeys.length - 1].split('/p/')[1] : '',
      truncated: postKeys.length > wanted.length && !since,
    });
  },

  async post(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    const text = String(b.text || '').replace(/\r\n?/g, '\n').replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '').trim().slice(0, 2000);
    const quote = clean(b.quote, 1200);
    if (!text && !quote) return fail(400, 'bad_request', 'Write something to post.');
    const parent = validPostId(b.parent) ? b.parent : null;
    if (parent && !(await s.get(`g/${m.meta.id}/p/${parent}`, { type: 'json' }))) return fail(404, 'not_found', 'That conversation was removed.');
    const post = await writePost(s, m.meta.id, {
      uid: user.id, name: m.me.name, kind: quote ? 'quote' : 'text', text, quote, cite: clean(b.cite, 200), ref: cleanRef(b.ref),
      chapter: clean(b.chapter, 160), percent: pct(b.percent), parent,
    });
    return json({ post });
  },

  async delete(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    if (!validPostId(b.postId)) return fail(400, 'bad_request', 'Unknown post.');
    const key = `g/${m.meta.id}/p/${b.postId}`;
    const p = await s.get(key, { type: 'json' });
    if (!p) return json({ ok: true });
    if (p.uid !== user.id && m.meta.owner !== user.id) return fail(403, 'forbidden', 'Only the writer or the group leader can remove this.');
    await s.setJSON(key, { id: p.id, createdAt: p.createdAt, parent: p.parent || null, deleted: true, by: p.uid === user.id ? 'author' : 'leader' });
    return json({ ok: true });
  },

  async progress(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    const plan = b.plan && typeof b.plan === 'object' ? { day: Math.max(0, Math.min(400, Number(b.plan.day) || 0)), done: Math.max(0, Math.min(400, Number(b.plan.done) || 0)) } : m.me.plan || null;
    await s.setJSON(`g/${m.meta.id}/m/${user.id}`, { ...m.me, percent: b.percent != null ? pct(b.percent) : m.me.percent ?? null, chapter: b.chapter != null ? clean(b.chapter, 160) : m.me.chapter || '', plan, progressAt: Date.now() });
    return json({ ok: true });
  },

  async leave(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    const gid = m.meta.id;
    await s.delete(`g/${gid}/m/${user.id}`);
    await s.delete(`u/${user.id}/${gid}`);
    if (m.meta.owner === user.id) {
      const rest = (await Promise.all((await listKeys(s, `g/${gid}/m/`)).map((k) => s.get(k, { type: 'json' })))).filter(Boolean).sort((a, c) => a.joinedAt - c.joinedAt);
      if (!rest.length) { await deleteGroup(s, gid, m.meta); return json({ ok: true, deleted: true }); }
      await s.setJSON(`g/${gid}/meta`, { ...m.meta, owner: rest[0].uid });
      await s.setJSON(`g/${gid}/m/${rest[0].uid}`, { ...rest[0], role: 'leader' });
    }
    return json({ ok: true });
  },

  async invite(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    if (m.meta.owner !== user.id) return fail(403, 'forbidden', 'Only the group leader can change the invite code.');
    let invite = '';
    for (let i = 0; i < 5 && !invite; i++) {
      const c = newInvite();
      if ((await s.setJSON(`inv/${c}`, { gid: m.meta.id }, { onlyIfNew: true })).modified) invite = c;
    }
    if (!invite) return fail(503, 'busy', 'Couldn’t make an invite code. Try again.');
    await s.setJSON(`g/${m.meta.id}/meta`, { ...m.meta, invite });
    await s.delete(`inv/${m.meta.invite}`);
    return json({ invite });
  },

  async remove(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    if (m.meta.owner !== user.id) return fail(403, 'forbidden', 'Only the group leader can remove members.');
    const uid = clean(b.uid, 80);
    if (!uid || uid === user.id) return fail(400, 'bad_request', 'Choose someone else to remove.');
    await s.delete(`g/${m.meta.id}/m/${uid}`);
    await s.delete(`u/${uid}/${m.meta.id}`);
    return json({ ok: true });
  },

  async update(s, user, b) {
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    if (m.meta.owner !== user.id) return fail(403, 'forbidden', 'Only the group leader can change the group.');
    const next = { ...m.meta };
    if (b.name != null) next.name = clean(b.name, 80) || next.name;
    if (b.about != null) next.about = clean(b.about, 500);
    if (b.planId !== undefined && next.kind === 'bible') { next.planId = PLAN_IDS.has(b.planId) ? b.planId : null; next.startDate = next.planId ? (/^\d{4}-\d{2}-\d{2}$/.test(b.startDate || '') ? b.startDate : new Date().toISOString().slice(0, 10)) : null; }
    await s.setJSON(`g/${m.meta.id}/meta`, next);
    return json({ group: publicGroup(next) });
  },

  async questions(s, user, b, req) {
    const bad = await requireOwner(req);
    if (bad) return bad;
    const m = await membership(s, b.gid, user.id);
    if (m.response) return m.response;
    const text = String(b.text || '').slice(0, 14_000);
    if (text.length < 200) return fail(400, 'bad_request', 'Read a little further first — there isn’t enough text for questions yet.');
    const kids = req.headers.get('x-mavis-kids') === '1';
    const out = await complete({
      system: `You write discussion questions for a ${m.meta.kind === 'bible' ? 'Bible study group' : 'book club'}. Use ONLY the text given, which ends where the reader stopped: never mention or hint at anything later, and do not use outside knowledge of the book. Return JSON only: {"questions":["…"]} with 5 open-ended questions (each under 30 words) that start real conversation: about characters' choices, themes, the group's own reactions, and connections to life${m.meta.kind === 'bible' ? ', and what the passage teaches; respect that members may hold different Christian views' : ''}. No yes/no questions.${kids ? `\n\n${KIDS_RULES}` : ''}`,
      user: `${m.meta.kind === 'bible' ? 'Passage' : 'Book'}: ${clean(b.title, 200)}${b.author ? ` by ${clean(b.author, 120)}` : ''}\nSection: ${clean(b.chapter, 160)}\n\n"""${text}"""`,
      json: true, maxTokens: 700,
    });
    const qs = (Array.isArray(out.questions) ? out.questions : []).map((q) => clean(q, 300)).filter(Boolean).slice(0, 6);
    if (!qs.length) return fail(502, 'study_failed', 'No questions came back. Try again.');
    const post = await writePost(s, m.meta.id, {
      uid: user.id, name: m.me.name, kind: 'questions', by: 'mavis', text: '', questions: qs,
      chapter: clean(b.chapter, 160), percent: pct(b.percent), ref: cleanRef(b.ref), parent: null,
    });
    return json({ post });
  },
};

export default async (req, context) => {
  const bad = onlyPost(req);
  if (bad) return bad;
  const a = await requireUser(req);
  if (a.response) return a.response;
  if (softLimit(`groups:${a.user.id}:${clientKey(req, context)}`, { limit: 90 })) return fail(429, 'rate_limited', 'Too many requests. Wait a minute.');
  let body;
  try { body = await readJson(req, 40_000); } catch (err) { return fail(err.status || 400, 'bad_request', err.message); }
  const fn = body && typeof body.action === 'string' && Object.hasOwn(actions, body.action) ? actions[body.action] : null;
  if (!fn) return fail(400, 'bad_request', 'Unknown action.');
  try {
    return await fn(store(), a.user, body, req);
  } catch (err) {
    console.error('groups', body.action, err);
    return fail(err.status || 502, 'groups_failed', err.status ? err.message : 'The group service had a problem. Try again.');
  }
};

export const config = {
  path: '/api/groups',
  method: 'POST',
  rateLimit: { windowLimit: 120, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
