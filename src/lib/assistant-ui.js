// "Ask Mavis" — the in-book AI assistant panel.
import { html, icon, openDialog, toast } from './ui.js';
import { features, can, ownerPost, loadFeatures } from './features.js';
import { getSetting, setSetting } from './store.js';

const SUGGESTIONS = {
  book: ['Summarize this chapter', 'Who are the main characters so far?', 'Explain the selected passage', 'Read this chapter to me'],
  bible: ['Summarize this chapter', 'Explain the selected verses', 'What is the historical context?', 'Read this chapter to me'],
};

/**
 * openAssistant({ container, getContext: () => ({ title, author, chapter, text, selection, bible }), actions: { read_aloud, stop_reading, go_to, car_mode, define_word } })
 */
export async function openAssistant({ container, getContext, actions = {}, initialQuestion = '' }) {
  await loadFeatures();
  if (!can('assistant')) return showSetup(container);
  if (!getSetting('aiConsent', false)) {
    const ok = await consent(container);
    if (!ok) return;
  }
  const f = features();
  const ctx = getContext();
  const history = [];
  const d = openDialog({
    title: 'Ask Mavis', variant: 'side', container, className: 'assistant',
    body: html`
      <p class="hint">${f.assistant.provider === 'anthropic' ? 'Claude' : 'OpenAI'} (${f.assistant.model}) answers using the part of “${ctx.title}” you’re reading. Answers can be wrong; check anything important.</p>
      <div class="chat" id="chat" aria-live="polite"></div>
      <div class="chips" id="suggest">${SUGGESTIONS[ctx.bible ? 'bible' : 'book'].map((s) => html`<button type="button" class="chip" data-suggest="${s}">${s}</button>`)}</div>
      <form class="chat-form" id="chat-form">
        <label class="visually-hidden" for="chat-q">Your question</label>
        <textarea id="chat-q" class="input" rows="2" maxlength="2000" placeholder="Ask about this book…" autofocus></textarea>
        <button type="submit" class="btn btn-primary" id="chat-send">Ask</button>
      </form>`,
  });
  const chat = d.body.querySelector('#chat');
  const form = d.body.querySelector('#chat-form');
  const q = d.body.querySelector('#chat-q');
  const send = d.body.querySelector('#chat-send');

  function bubble(role, text) {
    const b = document.createElement('div');
    b.className = `msg msg-${role}`;
    b.textContent = text;
    chat.appendChild(b);
    b.scrollIntoView({ block: 'end', behavior: 'smooth' });
    return b;
  }

  async function ask(text) {
    text = text.trim();
    if (!text) return;
    history.push({ role: 'user', content: text });
    bubble('user', text);
    const wait = bubble('assistant', 'Thinking…');
    wait.classList.add('pending');
    send.disabled = true;
    try {
      const out = await ownerPost('/api/assistant', { messages: history, context: getContext() });
      const reply = out.reply || (out.actions?.length ? 'Done.' : 'I don’t have an answer for that.');
      wait.textContent = reply;
      wait.classList.remove('pending');
      history.push({ role: 'assistant', content: reply });
      for (const a of out.actions || []) {
        const fn = actions[a.name];
        if (fn) { try { await fn(a.input || {}); } catch (err) { toast(err.message, { tone: 'error' }); } }
        if (a.name === 'read_aloud' || a.name === 'car_mode' || a.name === 'go_to') d.close();
      }
    } catch (err) {
      wait.textContent = err.message;
      wait.classList.add('error');
      history.pop();
    } finally {
      send.disabled = false;
    }
  }

  form.addEventListener('submit', (e) => { e.preventDefault(); const t = q.value; q.value = ''; ask(t); });
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); } });
  d.body.querySelector('#suggest').addEventListener('click', (e) => {
    const s = e.target.closest('[data-suggest]')?.dataset.suggest;
    if (s) ask(s);
  });
  if (initialQuestion) ask(initialQuestion);
}

function consent(container) {
  return new Promise((resolve) => {
    let ok = false;
    const f = features();
    const d = openDialog({
      title: 'Before you ask', container,
      body: html`<p class="dialog-text">Ask Mavis sends your question, the chapter or passage you’re on, and any text you selected to ${f.assistant?.provider === 'openai' ? 'OpenAI' : 'Anthropic (Claude)'} to write an answer. Each question uses a little of the site owner’s AI credit.</p>
        <p class="dialog-text">Nothing is sent until you ask, and nothing else from your library is shared.</p>
        <div class="dialog-actions"><button type="button" class="btn btn-quiet" data-close>Not now</button><button type="button" class="btn btn-primary" data-ok autofocus>Got it</button></div>`,
      onClose: () => resolve(ok),
    });
    d.body.querySelector('[data-ok]').addEventListener('click', () => { ok = true; setSetting('aiConsent', true); d.close(); });
  });
}

function showSetup(container) {
  const f = features();
  const reason = !f.reachable ? 'The Mavis server can’t be reached right now.'
    : !f.assistant ? 'The site owner hasn’t connected an AI provider yet.'
      : !f.owner ? 'This device doesn’t have the owner’s access code yet.' : '';
  openDialog({
    title: 'Ask Mavis isn’t set up', container,
    body: html`<p class="dialog-text">${reason}</p>
      <p class="dialog-text">Ask Mavis is an AI reading companion that can summarize chapters, explain passages, and start read-aloud for you. It needs an AI provider key on the server and the owner’s access code on this device.</p>
      <div class="dialog-actions"><a class="btn" href="#/settings">${icon('settings', { size: 18 })} Open settings</a><button type="button" class="btn btn-primary" data-close>OK</button></div>`,
  });
}
