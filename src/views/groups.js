// #/groups               — your book clubs and Bible study groups; create or join one
// #/groups/join?code=…   — join with an invite code
// #/groups/new?book=…    — start a book club for a book
// #/groups/<gid>         — one group: members' progress, discussion, shared quotes

import { html, icon, toast, confirmDialog, timeAgo } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { currentUser } from '../lib/auth.js';
import * as G from '../lib/groups.js';
import { recallBook } from '../lib/catalog.js';
import { cover, stateBlock } from '../components.js';
import { PLANS } from '../lib/plans.js';

export const title = (route) => (route.segs[0] && !['join', 'new'].includes(route.segs[0]) ? 'Group' : 'Book clubs & groups');

const signInBlock = () => stateBlock({
  title: 'Sign in to join a group',
  text: 'Book clubs and Bible study groups keep everyone’s discussion in one place, so they need a free Mavis account.',
  actions: html`<a class="btn btn-primary btn-sm" href="#/account">Sign in or create an account</a>`,
});

export async function render(root, route, ctx) {
  if (!currentUser()) { root.innerHTML = String(html`<div class="page" style="padding-top:20px">${signInBlock()}</div>`); return; }
  const seg = route.segs[0];
  if (seg && seg !== 'join' && seg !== 'new') return groupPage(root, seg, { ...ctx, route });
  return listPage(root, route, ctx);
}

// ---------- List, create, join ----------

async function listPage(root, route, { navigate }) {
  const joinCode = route.segs[0] === 'join' ? (route.params.get('code') || '') : '';
  const bookKey = route.segs[0] === 'new' ? (route.params.get('book') || '') : '';
  const shelfBook = bookKey ? (await store.getShelfItem(bookKey).catch(() => null)) || recallBook(bookKey) : null;
  const shelf = (await store.listShelf().catch(() => [])).filter((b) => b.key !== 'bible');
  const name = G.defaultDisplayName();
  root.innerHTML = String(html`<div class="page groups">
    <div class="shelf-head">
      <h1>Book clubs & groups</h1>
      <p class="small muted">Read together. Members see each other’s progress, share quotes, and talk about each chapter. Posts from further along stay hidden until you get there.</p>
    </div>
    <div id="g-list" style="margin-top:16px">${stateBlock({ title: 'Loading your groups…', text: '' })}</div>
    <div class="group-forms">
      <form class="panel" id="g-join" autocomplete="off">
        <h2>Join a group</h2>
        <div class="field"><label for="gj-code">Invite code</label><input class="input" id="gj-code" value="${joinCode}" placeholder="ABCD-EF23" maxlength="12" autocapitalize="characters" spellcheck="false" ${joinCode ? '' : ''} /></div>
        <div class="field"><label for="gj-name">Your name in the group</label><input class="input" id="gj-name" value="${name}" maxlength="40" /></div>
        <div><button class="btn btn-primary" type="submit">Join</button></div>
        <p class="hint" id="gj-msg">Other members see this name, never your email.</p>
      </form>
      <form class="panel" id="g-new" autocomplete="off">
        <h2>Start a group</h2>
        <div class="seg" role="group" aria-label="Kind of group"><button type="button" data-kind="book" aria-pressed="true">Book club</button><button type="button" data-kind="bible" aria-pressed="false">Bible study</button></div>
        <div class="field"><label for="gn-name">Group name</label><input class="input" id="gn-name" maxlength="80" value="${shelfBook ? `${shelfBook.title} club`.slice(0, 80) : ''}" /></div>
        <div class="field" data-for="book"><label for="gn-book">Book</label><select class="select" id="gn-book">
          ${shelf.length || shelfBook ? '' : html`<option value="">Add a book to your shelf first</option>`}
          ${shelfBook && !shelf.some((b) => b.key === shelfBook.key) ? html`<option value="${shelfBook.key}" selected>${shelfBook.title}</option>` : ''}
          ${shelf.map((b) => html`<option value="${b.key}" ${b.key === bookKey ? 'selected' : ''}>${b.title}${b.authors?.[0] ? ` — ${b.authors[0]}` : ''}</option>`)}</select></div>
        <div class="field" data-for="bible" hidden><label for="gn-plan">Reading plan</label><select class="select" id="gn-plan"><option value="">No plan — just discussion</option>${PLANS.map((p) => html`<option value="${p.id}">${p.name}</option>`)}</select></div>
        <div class="field"><label for="gn-about">What’s it about? (optional)</label><input class="input" id="gn-about" maxlength="500" placeholder="We meet Thursdays · one chapter a week" /></div>
        <div class="field"><label for="gn-you">Your name in the group</label><input class="input" id="gn-you" value="${name}" maxlength="40" /></div>
        <div><button class="btn btn-primary" type="submit">Create group</button></div>
        <p class="hint" id="gn-msg">You’ll get an invite code to send to friends.</p>
      </form>
    </div>
  </div>`);
  const $ = (s) => root.querySelector(s);
  let kind = 'book';

  async function paintList() {
    const el = $('#g-list');
    try {
      const groups = await G.myGroups({ force: true });
      if (!groups.length) { el.innerHTML = String(stateBlock({ title: 'No groups yet', text: 'Join one with an invite code, or start your own below.' })); return; }
      el.innerHTML = String(html`<ul class="group-list">${groups.map((gr) => html`<li><a class="group-card" href="#/groups/${gr.id}">
        ${gr.kind === 'book' && gr.book ? cover({ ...gr.book, key: gr.book.key }) : html`<span class="group-icon" aria-hidden="true">${icon('cross', { size: 26 })}</span>`}
        <span class="group-card-body"><b>${gr.name}</b>
          <span class="small muted">${gr.kind === 'book' ? gr.book?.title : (PLANS.find((p) => p.id === gr.planId)?.name || 'Bible study')}</span>
          <span class="small faint">${gr.members} member${gr.members === 1 ? '' : 's'} · ${gr.posts} post${gr.posts === 1 ? '' : 's'}${gr.lastPostAt ? ` · active ${timeAgo(gr.lastPostAt)}` : ''}</span></span>
        ${icon('chevronR')}</a></li>`)}</ul>`);
    } catch (err) {
      el.innerHTML = String(stateBlock({ tone: 'error', title: 'Couldn’t load your groups', text: err.message, actions: html`<button class="btn btn-sm" type="button" data-retry>Try again</button>` }));
      el.querySelector('[data-retry]').onclick = paintList;
    }
  }

  root.addEventListener('click', (e) => {
    const k = e.target.closest('[data-kind]');
    if (!k) return;
    kind = k.dataset.kind;
    root.querySelectorAll('[data-kind]').forEach((b) => b.setAttribute('aria-pressed', String(b === k)));
    root.querySelector('[data-for="book"]').hidden = kind !== 'book';
    root.querySelector('[data-for="bible"]').hidden = kind !== 'bible';
  });
  $('#g-join').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('#gj-msg');
    try {
      const displayName = $('#gj-name').value.trim();
      const r = await G.api('join', { code: $('#gj-code').value, displayName });
      store.setSetting('groupName', displayName);
      G.forgetCache();
      toast(r.rejoined ? `You’re already in “${r.group.name}”.` : `Welcome to “${r.group.name}”!`);
      navigate(`/groups/${r.group.id}`);
    } catch (err) { msg.textContent = err.message; msg.style.color = 'var(--danger)'; }
  });
  $('#g-new').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('#gn-msg');
    try {
      const key = $('#gn-book').value;
      const b = kind === 'book' ? (shelf.find((x) => x.key === key) || (shelfBook?.key === key ? shelfBook : null)) : null;
      if (kind === 'book' && !b) throw new Error('Choose a book from your shelf.');
      const displayName = $('#gn-you').value.trim();
      const r = await G.api('create', {
        name: $('#gn-name').value, kind, about: $('#gn-about').value, displayName, planId: $('#gn-plan').value || null,
        book: b ? { key: b.key, title: b.title, authors: b.authors, source: b.source, sourceId: b.sourceId, coverUrl: b.coverUrl } : null,
      });
      store.setSetting('groupName', displayName);
      G.forgetCache();
      navigate(`/groups/${r.group.id}?created=1`);
    } catch (err) { msg.textContent = err.message; msg.style.color = 'var(--danger)'; }
  });
  if (joinCode) setTimeout(() => $('#gj-name').focus(), 50);
  await paintList();
}

// ---------- One group ----------

async function groupPage(root, gid, { navigate, token, route }) {
  root.innerHTML = String(html`<div class="page group-page"><div id="gp">${stateBlock({ title: 'Opening the group…', text: '' })}</div></div>`);
  const el = root.querySelector('#gp');
  let data = null;
  let myPercent = null;
  let reveal = new Set();
  let replyTo = null;
  let pollTimer = null;
  let planInfo = null;

  async function load({ full = true } = {}) {
    try {
      if (full || !data) data = await G.api('get', { gid });
      else {
        const more = await G.api('get', { gid, since: data.cursor });
        const have = new Set(data.posts.map((p) => p.id));
        const fresh = more.posts.filter((p) => !have.has(p.id));
        data.posts.push(...fresh); data.cursor = more.cursor || data.cursor;
        data.members = more.members; data.group = more.group;
        if (!fresh.length) { paintMembers(); return; }
      }
    } catch (err) {
      if (!data) {
        el.innerHTML = String(stateBlock({ tone: 'error', title: err.status === 403 ? 'You’re not in this group' : err.status === 404 ? 'This group doesn’t exist any more' : 'Couldn’t open the group', text: err.message, actions: html`<a class="btn btn-sm" href="#/groups">Your groups</a>` }));
      }
      return;
    }
    const gr = data.group;
    if (gr.kind === 'book' && gr.book) myPercent = (await store.getProgress(gr.book.key).catch(() => null))?.percent ?? null;
    if (gr.kind === 'bible' && gr.planId) planInfo = await bibleDay(gr);
    paint();
  }

  async function bibleDay(gr) {
    try {
      const { loadIndex } = await import('../lib/bible.js');
      const P = await import('../lib/plans.js');
      const idx = await loadIndex();
      const days = P.schedule(idx, gr.planId);
      const today = Math.min(days.length - 1, Math.max(0, Math.round((new Date(`${P.localDate()}T12:00:00`) - new Date(`${gr.startDate}T12:00:00`)) / 864e5)));
      const mine = await store.getRecord('plans', gr.planId);
      const st = mine ? P.status(idx, mine) : null;
      const first = days[today]?.[0];
      return { plan: PLANS.find((p) => p.id === gr.planId), today, total: days.length, label: P.describeDay(idx, days[today] || []), href: first ? `#/bible/${first.book}/${first.chapter}` : '#/bible', mine: st, sameStart: mine?.startDate === gr.startDate };
    } catch { return null; }
  }

  const isLeader = () => data.group.owner === data.you;

  function paint() {
    const gr = data.group;
    el.innerHTML = String(html`
      <div class="group-head">
        ${gr.kind === 'book' && gr.book ? html`<a class="group-cover" href="#/book/${encodeURIComponent(gr.book.key)}">${cover({ ...gr.book })}</a>` : html`<span class="group-icon big" aria-hidden="true">${icon('cross', { size: 34 })}</span>`}
        <div style="min-width:0">
          <p class="eyebrow">${gr.kind === 'book' ? 'Book club' : 'Bible study'}</p>
          <h1>${gr.name}</h1>
          ${gr.about ? html`<p class="muted">${gr.about}</p>` : ''}
          ${gr.kind === 'book' && gr.book ? html`<p class="small">Reading <a href="#/book/${encodeURIComponent(gr.book.key)}">${gr.book.title}</a>${gr.book.authors?.[0] ? ` by ${gr.book.authors[0]}` : ''} · <a href="#/read/${encodeURIComponent(gr.book.key)}">Open the book</a></p>` : ''}
        </div>
      </div>
      ${planInfo ? html`<div class="plan-banner group-plan">
        <div><b>${planInfo.plan?.name}</b><span class="small muted">Group day ${planInfo.today + 1} of ${planInfo.total}: ${planInfo.label}</span>
        ${planInfo.mine ? html`<span class="small faint">You’ve read ${planInfo.mine.doneCount} of ${planInfo.total} days.</span>` : ''}</div>
        <div class="ab-actions"><a class="btn btn-sm btn-primary" href="${planInfo.href}">Read today’s passage</a>
        ${planInfo.mine ? '' : html`<button class="btn btn-sm" type="button" data-g="start-plan">Follow this plan with the group</button>`}</div></div>` : ''}

      <section class="section" aria-labelledby="gm-h">
        <div class="section-head"><h2 class="h-section" id="gm-h">Members</h2>
          <div class="invite"><span class="small muted">Invite code</span> <code class="invite-code">${gr.invite}</code>
            <button class="btn btn-sm btn-quiet" type="button" data-g="copy-invite">${icon('copy', { size: 16 })} Copy invite</button>
            ${navigator.share ? html`<button class="btn btn-sm btn-quiet" type="button" data-g="share-invite">${icon('share', { size: 16 })} Share</button>` : ''}
            ${isLeader() ? html`<button class="btn btn-sm btn-quiet" type="button" data-g="new-invite">New code</button>` : ''}</div></div>
        <ul class="member-list" id="g-members"></ul>
      </section>

      <section class="section" aria-labelledby="gd-h">
        <div class="section-head"><h2 class="h-section" id="gd-h">Discussion</h2><button class="btn btn-sm btn-quiet" type="button" data-g="refresh">${icon('refresh', { size: 16 })} Refresh</button></div>
        <form id="g-compose" class="compose">
          <div id="g-replying" class="small muted" hidden></div>
          <label class="visually-hidden" for="g-text">Write a post</label>
          <textarea class="input" id="g-text" rows="3" maxlength="2000" placeholder="${gr.kind === 'book' ? 'What did you think of this chapter?' : 'Share a thought on today’s reading'}"></textarea>
          <div class="compose-actions"><span class="small faint">${gr.kind === 'book' ? (myPercent != null ? `Posting from ${Math.round(myPercent * 100)}% through the book — members behind you won’t see it until they catch up.` : 'Open the book to share your place, so posts stay spoiler-safe.') : 'To share a verse, select it in the Bible and choose “Group”.'}</span>
            <button class="btn btn-primary btn-sm" type="submit">Post</button></div>
        </form>
        <p class="small faint">${gr.kind === 'book' ? 'Share a passage: select text in the book and choose “Share to book club”. Discussion questions: in the book, open Book club → Discussion questions.' : ''}</p>
        <div id="g-posts" aria-live="polite"></div>
      </section>
      <div class="ab-actions" style="margin-top:28px"><button class="btn btn-quiet btn-sm" type="button" data-g="leave">${icon('logout', { size: 16 })} Leave group</button><a class="btn btn-quiet btn-sm" href="#/groups">All groups</a></div>`);
    paintMembers();
    paintPosts();
  }

  function paintMembers() {
    const ul = root.querySelector('#g-members');
    if (!ul) return;
    const gr = data.group;
    const rows = [...data.members].sort((a, b) => (b.percent ?? -1) - (a.percent ?? -1) || a.joinedAt - b.joinedAt);
    ul.innerHTML = String(html`${rows.map((m) => {
      const f = gr.kind === 'book' ? m.percent : m.plan && planInfo ? m.plan.done / planInfo.total : null;
      return html`<li class="member">
        <span class="avatar" aria-hidden="true">${(m.name || '?').slice(0, 1).toUpperCase()}</span>
        <span class="member-body"><span class="member-name"><b>${m.name}${m.uid === data.you ? ' (you)' : ''}</b>${m.role === 'leader' ? html`<span class="badge badge-accent">Leader</span>` : ''}</span>
          <span class="progress-line"><span style="width:${Math.round((f || 0) * 100)}%"></span></span>
          <span class="small faint">${gr.kind === 'book' ? (m.percent != null ? `${Math.round(m.percent * 100)}%${m.chapter ? ` · ${m.chapter}` : ''}` : 'Hasn’t started') : m.plan ? `${m.plan.done} day${m.plan.done === 1 ? '' : 's'} read` : 'Not following the plan'}${m.progressAt ? ` · ${timeAgo(m.progressAt)}` : ''}</span></span>
        ${isLeader() && m.uid !== data.you ? html`<button class="icon-btn" type="button" data-remove="${m.uid}" aria-label="Remove ${m.name} from the group">${icon('close', { size: 18 })}</button>` : ''}
      </li>`;
    })}`);
  }

  function refHref(ref) {
    if (!ref) return '';
    if (ref.kind === 'bible') { const [b, c, v] = ref.osis.split('-')[0].split('.'); return `#/bible/${b}/${c}${v ? `?v=${v}` : ''}`; }
    return `#/read/${encodeURIComponent(ref.key)}${ref.cfi ? `?at=${encodeURIComponent(ref.cfi)}` : ''}`;
  }

  function postHtml(p, isReply = false) {
    if (p.deleted) return html`<div class="post removed small faint">${p.by === 'leader' ? 'Removed by the group leader.' : 'Removed by the writer.'}</div>`;
    const hidden = data.group.kind === 'book' && G.isAhead(p, myPercent) && p.uid !== data.you && !reveal.has(p.id);
    const mine = p.uid === data.you;
    return html`<article class="post ${isReply ? 'reply' : ''} ${p.kind === 'questions' ? 'questions' : ''}" id="post-${p.id}">
      <header><span class="avatar sm" aria-hidden="true">${p.by === 'mavis' ? '✦' : (p.name || '?').slice(0, 1).toUpperCase()}</span>
        <b>${p.by === 'mavis' ? 'Mavis' : p.name}</b>${p.by === 'mavis' ? html` <span class="small faint">for ${p.name}</span>` : ''}
        <span class="small faint">${timeAgo(p.createdAt)}${p.chapter ? ` · ${p.chapter}` : ''}${p.percent != null && data.group.kind === 'book' ? ` · ${Math.round(p.percent * 100)}%` : ''}</span></header>
      ${hidden ? html`<div class="spoiler">${icon('info', { size: 18 })} <span>Posted from further along than you’ve read${p.chapter ? ` (${p.chapter})` : ''}.</span> <button type="button" class="btn btn-sm btn-quiet" data-reveal="${p.id}">Show anyway</button></div>` : html`
        ${p.kind === 'questions' ? html`<p class="small muted">Discussion questions${p.chapter ? ` for ${p.chapter}` : ''}:</p><ol class="q-list">${(p.questions || []).map((q) => html`<li>${q} <button type="button" class="linklike small" data-answer="${p.id}" data-q="${q}">Answer</button></li>`)}</ol>` : ''}
        ${p.quote ? html`<blockquote class="post-quote">${p.quote}${p.cite ? html`<cite>${p.ref ? html`<a href="${refHref(p.ref)}">${p.cite}</a>` : p.cite}</cite>` : ''}</blockquote>` : ''}
        ${!p.quote && p.ref ? html`<p class="small"><a href="${refHref(p.ref)}">${icon(p.ref.kind === 'bible' ? 'cross' : 'book', { size: 14 })} ${p.cite || p.ref.osis || 'Open the passage'}</a></p>` : ''}
        ${p.text ? html`<p class="post-text">${p.text}</p>` : ''}`}
      <footer>${isReply ? '' : html`<button type="button" class="btn btn-sm btn-quiet" data-reply="${p.id}">Reply</button>`}
        ${mine || isLeader() ? html`<button type="button" class="btn btn-sm btn-quiet" data-del="${p.id}">${icon('trash', { size: 14 })} Remove</button>` : ''}</footer>
    </article>`;
  }

  function paintPosts() {
    const box = root.querySelector('#g-posts');
    if (!box) return;
    const roots = data.posts.filter((p) => !p.parent);
    const replies = new Map();
    for (const p of data.posts) if (p.parent) { if (!replies.has(p.parent)) replies.set(p.parent, []); replies.get(p.parent).push(p); }
    const lastActivity = (p) => Math.max(p.createdAt, ...(replies.get(p.id) || []).map((r) => r.createdAt));
    roots.sort((a, b) => lastActivity(b) - lastActivity(a));
    if (!roots.length) { box.innerHTML = String(stateBlock({ title: 'No posts yet', text: 'Say hello, or share a passage from the book.' })); return; }
    box.innerHTML = String(html`${data.truncated ? html`<p class="small faint">Showing the most recent posts.</p>` : ''}${roots.map((p) => html`<div class="thread">${postHtml(p)}${(replies.get(p.id) || []).map((r) => postHtml(r, true))}</div>`)}`);
  }

  function setReply(id, prefill = '') {
    replyTo = id;
    const r = root.querySelector('#g-replying');
    const p = data.posts.find((x) => x.id === id);
    r.hidden = !id;
    r.innerHTML = id ? String(html`Replying to ${p?.by === 'mavis' ? 'Mavis' : p?.name} · <button type="button" class="linklike" data-g="cancel-reply">Cancel</button>`) : '';
    const t = root.querySelector('#g-text');
    if (prefill) t.value = prefill;
    t.focus();
  }

  root.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-g],[data-reply],[data-del],[data-reveal],[data-remove],[data-answer]');
    if (!t) return;
    const gr = data?.group;
    try {
      if (t.dataset.reveal) { reveal.add(t.dataset.reveal); paintPosts(); return; }
      if (t.dataset.reply) { setReply(t.dataset.reply); return; }
      if (t.dataset.answer) { setReply(t.dataset.answer, `“${t.dataset.q}” — `); return; }
      if (t.dataset.del) {
        if (!(await confirmDialog({ title: 'Remove this post?', message: 'It’s removed for everyone in the group.', confirmLabel: 'Remove' }))) return;
        await G.api('delete', { gid, postId: t.dataset.del });
        const p = data.posts.find((x) => x.id === t.dataset.del);
        if (p) Object.assign(p, { deleted: true, by: p.uid === data.you ? 'author' : 'leader' });
        paintPosts();
        return;
      }
      if (t.dataset.remove) {
        const m = data.members.find((x) => x.uid === t.dataset.remove);
        if (!(await confirmDialog({ title: `Remove ${m?.name || 'this member'}?`, message: 'They can rejoin only with a new invite code. Consider changing the code too.', confirmLabel: 'Remove' }))) return;
        await G.api('remove', { gid, uid: t.dataset.remove });
        data.members = data.members.filter((x) => x.uid !== t.dataset.remove);
        paintMembers();
        return;
      }
      const a = t.dataset.g;
      if (a === 'cancel-reply') setReply(null);
      if (a === 'refresh') await load();
      if (a === 'copy-invite') {
        const text = `Join my ${gr.kind === 'book' ? 'book club' : 'Bible study'} “${gr.name}” on Mavis Library. Invite code: ${gr.invite}\n${G.inviteLink(gr.invite)}`;
        try { await navigator.clipboard.writeText(text); toast('Invite copied. Paste it in a message.'); } catch { toast(`Invite code: ${gr.invite}`); }
      }
      if (a === 'share-invite') navigator.share({ title: gr.name, text: `Join “${gr.name}” on Mavis Library with invite code ${gr.invite}.`, url: G.inviteLink(gr.invite) }).catch(() => {});
      if (a === 'new-invite') {
        if (!(await confirmDialog({ title: 'Make a new invite code?', message: 'The old code stops working. People already in the group stay in.', confirmLabel: 'New code', tone: 'primary' }))) return;
        const r = await G.api('invite', { gid });
        data.group.invite = r.invite; paint(); toast(`New invite code: ${r.invite}`);
      }
      if (a === 'start-plan') {
        const { startPlan } = await import('../lib/plans.js');
        await startPlan(gr.planId, { startDate: gr.startDate });
        toast('You’re following the plan with the group. Your progress is shared with members.');
        planInfo = await bibleDay(gr);
        G.shareProgressNow();
        paint();
      }
      if (a === 'leave') {
        const last = data.members.length === 1;
        if (!(await confirmDialog({ title: `Leave “${gr.name}”?`, message: last ? 'You’re the last member, so the group and its posts will be deleted.' : isLeader() ? 'The longest-standing member becomes the leader.' : 'You can rejoin with the invite code.', confirmLabel: 'Leave' }))) return;
        await G.api('leave', { gid });
        G.forgetCache();
        toast(`You left “${gr.name}”.`);
        navigate('/groups');
      }
    } catch (err) { toast(err.message, { tone: 'error' }); }
  });

  root.addEventListener('submit', async (e) => {
    if (e.target.id !== 'g-compose') return;
    e.preventDefault();
    const ta = root.querySelector('#g-text');
    const text = ta.value.trim();
    if (!text) return;
    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;
    try {
      const prog = data.group.kind === 'book' && data.group.book ? await store.getProgress(data.group.book.key).catch(() => null) : null;
      const { post } = await G.api('post', { gid, text, parent: replyTo, percent: prog?.percent ?? null, chapter: prog?.chapter || '' });
      data.posts.push(post);
      ta.value = ''; setReply(null); paintPosts();
    } catch (err) { toast(err.message, { tone: 'error' }); }
    btn.disabled = false;
  });

  await load();
  if (data) {
    if (route?.params.get('created') === '1') {
      toast('Group created. Send the invite code to the people you’d like to read with.');
      history.replaceState(null, '', `#/groups/${gid}`);
    }
    G.myGroups({ force: true }).then(() => G.shareProgressNow()).catch(() => {});
  }
  // Check for new posts while the page is open.
  pollTimer = setInterval(() => { if (document.visibilityState === 'visible' && token()) load({ full: false }); }, 20_000);
  return () => clearInterval(pollTimer);
}
