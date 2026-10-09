// Book clubs and study groups against a real local Netlify Blobs server.
//   node tests/functions/groups.test.mjs

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BlobsServer } from '@netlify/blobs/server';

const dir = mkdtempSync(join(tmpdir(), 'mavis-groups-'));
const server = new BlobsServer({ directory: dir, token: 'local-token', port: 0 });
const { port } = await server.start();
process.env.MAVIS_BLOBS_URL = `http://localhost:${port}`;
process.env.MAVIS_BLOBS_TOKEN = 'local-token';
process.env.AUTH_SECRET = 'test-secret-'.padEnd(48, 'x');
process.env.MAVIS_ACCESS_CODE = 'right-code';
process.env.LLM_PROVIDER = 'openai'; process.env.LLM_API_KEY = 'k'; process.env.LLM_MODEL = 'gpt-5.6-luna';

// The Blobs client uses the real fetch; the LLM is answered here.
const realFetch = globalThis.fetch;
globalThis.__mavisRealFetch = realFetch;
let llmCalls = [];
globalThis.fetch = async (url, opts) => {
  if (String(url) === 'https://api.openai.com/v1/chat/completions') {
    llmCalls.push(JSON.parse(opts.body));
    return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ questions: ['Why does the keeper climb at dusk?', 'What would you have done with the letter?', ''] }) } }] }), { headers: { 'content-type': 'application/json' } });
  }
  return realFetch(url, opts);
};

const account = (await import('../../netlify/functions/account.mjs')).default;
const groups = (await import('../../netlify/functions/groups.mjs')).default;
const { normInvite } = await import('../../netlify/functions/groups.mjs');

let ip = 0;
const post = (path, body, token, extra = {}) => new Request(`https://mavis.test${path}`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', origin: 'https://mavis.test', ...(token ? { authorization: `Bearer ${token}` } : {}), ...extra },
  body: JSON.stringify(body),
});
const signup = async (email) => {
  const r = await account(post('/api/account/signup', { email, password: 'correct-horse-9' }), { ip: `10.2.0.${++ip}`, params: { action: 'signup' } });
  const j = await r.json();
  return { token: j.token, id: j.user.id };
};
const g = async (token, body, extra) => {
  const r = await groups(post('/api/groups', body, token, extra), { ip: `10.3.0.${++ip % 250}` });
  return { status: r.status, body: await r.json() };
};

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log(`  ✓ ${name}`); }

try {
  const ada = await signup('ada@example.test');
  const ben = await signup('ben@example.test');
  const cy = await signup('cy@example.test');
  let gid, invite;
  const book = { key: 'gutenberg:1342', title: 'Pride and Prejudice', authors: ['Jane Austen'], source: 'gutenberg', sourceId: '1342', coverUrl: 'https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg' };

  await test('groups need a signed-in account', async () => {
    assert.equal((await g(null, { action: 'list' })).status, 401);
    assert.equal((await g(ada.token, { action: 'nope' })).status, 400);
  });

  await test('create a book club: needs a name, your display name, and a book; gets an invite code', async () => {
    assert.equal((await g(ada.token, { action: 'create', name: '', displayName: 'Ada', book })).status, 400);
    assert.equal((await g(ada.token, { action: 'create', name: 'Austen Club', displayName: '', book })).status, 400);
    assert.equal((await g(ada.token, { action: 'create', name: 'Austen Club', displayName: 'Ada', kind: 'book' })).status, 400);
    const r = await g(ada.token, { action: 'create', name: 'Austen Club', displayName: 'Ada', kind: 'book', book: { ...book, coverUrl: 'javascript:alert(1)' } });
    assert.equal(r.status, 200);
    ({ id: gid, invite } = r.body.group);
    assert.match(invite, /^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/);
    assert.equal(r.body.group.book.coverUrl, null, 'only https cover URLs are kept');
    assert.equal(normInvite(invite.toLowerCase().replace('-', ' ')), invite);
  });

  await test('join with the invite code (any case, spaces); a wrong code is refused; non-members can’t read', async () => {
    assert.equal((await g(ben.token, { action: 'get', gid })).status, 403);
    assert.equal((await g(ben.token, { action: 'join', code: 'ZZZZ-ZZZZ', displayName: 'Ben' })).status, 404);
    const r = await g(ben.token, { action: 'join', code: ` ${invite.toLowerCase()} `, displayName: 'Ben' });
    assert.equal(r.status, 200);
    assert.equal(r.body.group.id, gid);
    const again = await g(ben.token, { action: 'join', code: invite, displayName: 'Benjamin' });
    assert.equal(again.body.rejoined, true);
  });

  await test('members see display names and reading progress, never emails', async () => {
    await g(ben.token, { action: 'progress', gid, percent: 0.42, chapter: 'Chapter 12' });
    const r = await g(ada.token, { action: 'get', gid });
    assert.equal(r.status, 200);
    const text = JSON.stringify(r.body);
    assert.ok(!text.includes('@example.test'), 'no emails');
    const names = r.body.members.map((m) => m.name).sort();
    assert.deepEqual(names, ['Ada', 'Benjamin']);
    const benRow = r.body.members.find((m) => m.name === 'Benjamin');
    assert.equal(benRow.percent, 0.42);
    assert.equal(benRow.chapter, 'Chapter 12');
    assert.equal(r.body.members.find((m) => m.name === 'Ada').role, 'leader');
  });

  let first;
  await test('posts, shared quotes, and replies keep order; “since” returns only new posts', async () => {
    first = (await g(ada.token, { action: 'post', gid, text: 'Welcome! Start with chapter 1.', chapter: 'Chapter 1', percent: 0.01 })).body.post;
    assert.equal(first.name, 'Ada');
    const q = await g(ben.token, { action: 'post', gid, quote: 'It is a truth universally acknowledged…', cite: 'Pride and Prejudice · Chapter 1', ref: { kind: 'book', key: 'gutenberg:1342', cfi: 'epubcfi(/6/4)' }, text: 'Love this opening.', percent: 0.02 });
    assert.equal(q.body.post.kind, 'quote');
    assert.deepEqual(q.body.post.ref, { kind: 'book', key: 'gutenberg:1342', cfi: 'epubcfi(/6/4)' });
    const reply = await g(ada.token, { action: 'post', gid, text: 'Same!', parent: q.body.post.id });
    assert.equal(reply.body.post.parent, q.body.post.id);
    assert.equal((await g(ada.token, { action: 'post', gid, text: '' })).status, 400);
    assert.equal((await g(ada.token, { action: 'post', gid, text: 'x', parent: '0000000000000-deadbeef' })).status, 404);
    const all = await g(ben.token, { action: 'get', gid });
    assert.equal(all.body.posts.length, 3);
    assert.deepEqual(all.body.posts.map((p) => p.text), ['Welcome! Start with chapter 1.', 'Love this opening.', 'Same!']);
    const none = await g(ben.token, { action: 'get', gid, since: all.body.cursor });
    assert.equal(none.body.posts.length, 0);
    await g(ben.token, { action: 'post', gid, text: 'New one' });
    const fresh = await g(ada.token, { action: 'get', gid, since: all.body.cursor });
    assert.deepEqual(fresh.body.posts.map((p) => p.text), ['New one']);
  });

  await test('twelve members posting at once all land', async () => {
    await Promise.all(Array.from({ length: 12 }, (_, i) => g(i % 2 ? ada.token : ben.token, { action: 'post', gid, text: `burst ${i}` })));
    const r = await g(ada.token, { action: 'get', gid });
    assert.equal(r.body.posts.filter((p) => /^burst /.test(p.text)).length, 12);
  });

  await test('only the writer or the leader can remove a post', async () => {
    assert.equal((await g(ben.token, { action: 'delete', gid, postId: first.id })).status, 403);
    const benPost = (await g(ben.token, { action: 'post', gid, text: 'oops' })).body.post;
    assert.equal((await g(ada.token, { action: 'delete', gid, postId: benPost.id })).status, 200, 'leader can');
    const r = await g(ben.token, { action: 'get', gid });
    const gone = r.body.posts.find((p) => p.id === benPost.id);
    assert.equal(gone.deleted, true);
    assert.equal(gone.text, undefined, 'removed text is gone');
  });

  await test('AI discussion questions need the owner code, use only the text so far, and post as Mavis', async () => {
    const body = { action: 'questions', gid, title: 'Pride and Prejudice', chapter: 'Chapter 3', text: 'Text so far. '.repeat(30), percent: 0.05 };
    assert.equal((await g(ben.token, body)).status, 401);
    const r = await g(ben.token, body, { 'x-mavis-access': 'right-code' });
    assert.equal(r.status, 200);
    assert.deepEqual(r.body.post.questions, ['Why does the keeper climb at dusk?', 'What would you have done with the letter?']);
    assert.equal(r.body.post.by, 'mavis');
    assert.match(llmCalls[0].messages[0].content, /never mention or hint at anything later/);
    assert.equal((await g(ben.token, { ...body, text: 'short' }, { 'x-mavis-access': 'right-code' })).status, 400);
  });

  await test('a Bible study group with a reading plan', async () => {
    const r = await g(cy.token, { action: 'create', name: 'Gospels in a month', displayName: 'Cy', kind: 'bible', planId: 'gospels-30', startDate: '2026-10-01' });
    assert.equal(r.body.group.kind, 'bible');
    assert.equal(r.body.group.planId, 'gospels-30');
    assert.equal(r.body.group.startDate, '2026-10-01');
    const p = await g(cy.token, { action: 'post', gid: r.body.group.id, text: 'Notice verse 16.', ref: { kind: 'bible', osis: 'John.3.16' } });
    assert.deepEqual(p.body.post.ref, { kind: 'bible', osis: 'John.3.16' });
    const bad = await g(cy.token, { action: 'post', gid: r.body.group.id, text: 'x', ref: { kind: 'bible', osis: '<script>' } });
    assert.equal(bad.body.post.ref, null);
    await g(cy.token, { action: 'progress', gid: r.body.group.id, plan: { day: 9, done: 8 } });
    const m = (await g(cy.token, { action: 'get', gid: r.body.group.id })).body.members[0];
    assert.deepEqual(m.plan, { day: 9, done: 8 });
  });

  await test('list shows my groups with member and post counts, newest activity first', async () => {
    const r = await g(ada.token, { action: 'list' });
    assert.equal(r.body.groups.length, 1);
    assert.equal(r.body.groups[0].members, 2);
    assert.ok(r.body.groups[0].posts >= 16);
    assert.equal((await g(cy.token, { action: 'list' })).body.groups[0].name, 'Gospels in a month');
  });

  await test('the leader can change the invite code (the old one stops working) and remove members', async () => {
    assert.equal((await g(ben.token, { action: 'invite', gid })).status, 403);
    const r = await g(ada.token, { action: 'invite', gid });
    assert.notEqual(r.body.invite, invite);
    assert.equal((await g(cy.token, { action: 'join', code: invite, displayName: 'Cy' })).status, 404, 'old code is dead');
    assert.equal((await g(cy.token, { action: 'join', code: r.body.invite, displayName: 'Cy' })).status, 200);
    invite = r.body.invite;
    assert.equal((await g(ben.token, { action: 'remove', gid, uid: cy.id })).status, 403);
    assert.equal((await g(ada.token, { action: 'remove', gid, uid: cy.id })).status, 200);
    assert.equal((await g(cy.token, { action: 'get', gid })).status, 403);
    assert.equal((await g(cy.token, { action: 'list' })).body.groups.length, 1, 'only their own Bible group is left');
  });

  await test('when the leader leaves, the longest-standing member leads; when the last member leaves, the group is deleted', async () => {
    assert.equal((await g(ada.token, { action: 'leave', gid })).status, 200);
    let r = await g(ben.token, { action: 'get', gid });
    assert.equal(r.body.group.owner, ben.id);
    assert.equal(r.body.members[0].role, 'leader');
    r = await g(ben.token, { action: 'leave', gid });
    assert.equal(r.body.deleted, true);
    assert.equal((await g(ben.token, { action: 'join', code: invite, displayName: 'Ben' })).status, 404);
    assert.equal((await g(ada.token, { action: 'list' })).body.groups.length, 0);
  });
} finally {
  await server.stop();
  rmSync(dir, { recursive: true, force: true });
}

console.log(`\n${passed} group checks passed.`);
