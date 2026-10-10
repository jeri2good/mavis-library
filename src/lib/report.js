// "Report this" for AI answers, pictures, summaries, and scene films, and the
// "Made with AI" label. One delegated click handler serves every button, so
// views only add markup: reportButton('picture', 'Emma · Chapter 3').

import { html, openDialog, toast } from './ui.js';
import { apiUrl } from './api.js';
import { APP_VERSION, APP_BUILD } from './version.js';

const WHAT = { answer: 'AI answer', picture: 'AI picture', summary: 'AI summary', film: 'scene film' };

export const reportButton = (what, ref = '') => html`<button type="button" class="btn btn-sm btn-quiet report-btn" data-report="${what}" data-report-ref="${String(ref).slice(0, 300)}">Report this</button>`;

export const aiLabel = () => html`<span class="badge ai-label">Made with AI</span>`;

export async function sendReport({ what, ref = '', text = '' }) {
  let r;
  try {
    r = await fetch(apiUrl('/api/report'), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ what, ref, text, version: APP_BUILD ? `${APP_VERSION}+${APP_BUILD}` : APP_VERSION }),
    });
  } catch { throw new Error('Couldn’t reach Mavis. Check your connection and try again.'); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.message || `The server answered ${r.status}.`);
  return j;
}

function openReport({ what, ref }, container) {
  const d = openDialog({
    title: `Report this ${WHAT[what] || 'content'}`, container,
    body: html`<form class="stack" id="rp-form">
        <p class="dialog-text">Tell the site owner what’s wrong with it: wrong, harmful, offensive, or a spoiler. Mavis sends what it is${ref ? ' and where it came from' : ''}, plus your note. Nothing about your account is sent.</p>
        <div class="field"><label for="rp-text">What’s wrong? (optional)</label><textarea class="input" id="rp-text" rows="3" maxlength="1000"></textarea></div>
        <p class="form-error" role="alert" id="rp-err"></p>
        <div class="dialog-actions"><button type="button" class="btn btn-quiet" data-close>Cancel</button><button type="submit" class="btn btn-primary">Send report</button></div>
      </form>`,
  });
  const form = d.body.querySelector('#rp-form');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = form.querySelector('[type="submit"]');
    btn.disabled = true;
    try {
      const out = await sendReport({ what, ref, text: form.querySelector('#rp-text').value });
      d.close();
      toast(out.message || 'Thanks. Your report was sent.');
    } catch (err) {
      form.querySelector('#rp-err').textContent = err.message;
      btn.disabled = false;
    }
  });
}

let installed = false;
export function installReportHandler() {
  if (installed) return;
  installed = true;
  document.addEventListener('click', (e) => {
    const b = e.target.closest?.('[data-report]');
    if (!b) return;
    e.preventDefault();
    openReport({ what: b.dataset.report, ref: b.dataset.reportRef || '' }, b.closest('.overlay')?.parentElement || document.body);
  });
}
