// Dialogs for sharing a passage to a group, and the reader's Book club sheet
// (open the group, share the selection, or post AI discussion questions).

import { html, icon, toast, openDialog } from './ui.js';
import * as G from './groups.js';
import { can, loadFeatures } from './features.js';

/** Share a quote (book selection or Bible verses) to one of your groups. */
export function openShareToGroup({ groups, quote, cite, ref, chapter = '', percent = null, container }) {
  const d = openDialog({
    title: 'Share to a group', variant: 'sheet', container,
    body: html`<blockquote class="post-quote" style="margin:0">${quote}${cite ? html`<cite>${cite}</cite>` : ''}</blockquote>
      <form id="sg-form" class="field" autocomplete="off">
        ${groups.length > 1 ? html`<label for="sg-group">Group</label><select class="select" id="sg-group">${groups.map((gr) => html`<option value="${gr.id}">${gr.name}</option>`)}</select>` : html`<p class="small muted">To <b>${groups[0].name}</b></p>`}
        <label for="sg-text">Add a comment (optional)</label>
        <textarea class="input" id="sg-text" rows="3" maxlength="2000" placeholder="What stood out to you?"></textarea>
        <div class="dialog-actions"><button class="btn btn-quiet" type="button" data-cancel>Cancel</button><button class="btn btn-primary" type="submit">Share</button></div>
      </form>`,
  });
  d.body.querySelector('[data-cancel]').addEventListener('click', () => d.close());
  d.body.querySelector('#sg-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const gid = d.body.querySelector('#sg-group')?.value || groups[0].id;
    const btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;
    try {
      await G.api('post', { gid, quote, cite, ref, chapter, percent, text: d.body.querySelector('#sg-text').value });
      d.close();
      const gr = groups.find((x) => x.id === gid);
      toast(`Shared with “${gr?.name || 'your group'}”.`, { action: { label: 'Open', run: () => { location.hash = `#/groups/${gid}`; } } });
    } catch (err) { toast(err.message, { tone: 'error' }); btn.disabled = false; }
  });
  return d;
}

/**
 * The reader's Book club sheet for a book that has groups.
 * ctx: { groups, title, author, chapter(), textSoFar(), percent(), selection(), cite(), ref(), container }
 */
export async function openBookClubSheet(ctx) {
  await loadFeatures();
  const ai = can('assistant');
  const sel = ctx.selection();
  const d = openDialog({
    title: 'Book club', variant: 'sheet', container: ctx.container,
    body: html`<ul class="group-mini">${ctx.groups.map((gr) => html`<li><a href="#/groups/${gr.id}">${icon('user', { size: 18 })} <b>${gr.name}</b> <span class="small faint">${gr.members || ''}${gr.members ? ' members' : ''}</span></a></li>`)}</ul>
      <div class="ab-actions">
        ${sel ? html`<button class="btn btn-sm" type="button" data-bc="share">${icon('share', { size: 18 })} Share the selected passage</button>` : ''}
        <button class="btn btn-sm ${ai ? 'btn-primary' : ''}" type="button" data-bc="questions" ${ai ? '' : 'disabled'}>${icon('spark', { size: 18 })} Discussion questions for this chapter</button>
      </div>
      <p class="small faint" id="bc-msg">${ai ? 'Mavis writes 5 questions from the text up to your page — nothing later — and posts them to the group.' : 'Discussion questions use AI, which needs the owner access code in Settings.'}</p>
      ${ctx.groups.length > 1 ? html`<div class="field" style="margin-top:6px"><label for="bc-group">Post to</label><select class="select" id="bc-group">${ctx.groups.map((gr) => html`<option value="${gr.id}">${gr.name}</option>`)}</select></div>` : ''}`,
  });
  d.body.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-bc]')?.dataset.bc;
    if (!a) return;
    if (a === 'share') {
      d.close();
      openShareToGroup({ groups: ctx.groups, quote: sel.slice(0, 1200), cite: ctx.cite(), ref: ctx.ref(), chapter: ctx.chapter(), percent: ctx.percent(), container: ctx.container });
    }
    if (a === 'questions') {
      const gid = d.body.querySelector('#bc-group')?.value || ctx.groups[0].id;
      const btn = e.target.closest('button');
      const msg = d.body.querySelector('#bc-msg');
      btn.disabled = true; msg.textContent = 'Writing questions…';
      try {
        await G.api('questions', { gid, title: ctx.title, author: ctx.author, chapter: ctx.chapter(), text: ctx.textSoFar(), percent: ctx.percent() }, { owner: true });
        d.close();
        toast('Discussion questions posted.', { action: { label: 'Open the group', run: () => { location.hash = `#/groups/${gid}`; } } });
      } catch (err) { msg.textContent = err.message; btn.disabled = false; }
    }
  });
  return d;
}

/** The "Read together" panel on a book's page. */
export async function mountBookClubPanel(el, { key }) {
  const { currentUser } = await import('./auth.js');
  const paint = (groups) => {
    el.innerHTML = String(html`<section class="ab-panel"><h2 class="h-section">${icon('user', { size: 20 })} Read together</h2>
      ${groups.length ? html`<ul class="group-mini">${groups.map((gr) => html`<li><a href="#/groups/${gr.id}"><b>${gr.name}</b> <span class="small faint">${gr.members} member${gr.members === 1 ? '' : 's'}</span></a></li>`)}</ul>` : html`<p class="small muted">Start a book club for this book and invite friends with a code. You’ll see each other’s progress and talk chapter by chapter, without spoilers.</p>`}
      <div class="ab-actions">${currentUser()
        ? html`<a class="btn btn-sm" href="#/groups/new?book=${encodeURIComponent(key)}">${icon('plus', { size: 18 })} Start a book club</a><a class="btn btn-sm btn-quiet" href="#/groups/join">Join with a code</a>`
        : html`<a class="btn btn-sm" href="#/account">Sign in to start a book club</a>`}</div></section>`);
  };
  paint(G.groupsForBook(key));
  if (currentUser()) G.myGroups().then(() => paint(G.groupsForBook(key))).catch(() => {});
}
