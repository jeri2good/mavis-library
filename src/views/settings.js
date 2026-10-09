import { html, toast, formatBytes } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { applyAppTheme } from '../main.js';
import { ttsSupported } from '../lib/tts.js';
import { voiceInputSupported } from '../lib/voice-input.js';
import { authConfigured } from '../lib/auth.js';
import { loadFeatures, setAccessCode, accessCode } from '../lib/features.js';
import { icon } from '../lib/ui.js';

export const title = () => 'Settings';

export async function render(root) {
  const est = await store.storageEstimate();
  const theme = store.getSetting('appTheme', 'system');
  const motion = store.getSetting('motion', 'system');
  const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  root.innerHTML = String(html`<div class="page"><div class="settings">
    <h1 class="h-section" style="font-size:var(--step-3)">Settings</h1>
    <div class="panel">
      <h2>Appearance</h2>
      <div class="setting-row"><span>App theme</span>
        <div class="seg" role="group" aria-label="App theme">${[['system', 'Match device'], ['light', 'Light'], ['dark', 'Dark']].map(([v, l]) => html`<button type="button" data-theme="${v}" aria-pressed="${theme === v}">${l}</button>`)}</div></div>
      <div class="setting-row"><span>Motion</span>
        <div class="seg" role="group" aria-label="Motion">${[['system', 'Match device'], ['on', 'On'], ['off', 'Off']].map(([v, l]) => html`<button type="button" data-motion="${v}" aria-pressed="${motion === v}">${l}</button>`)}</div></div>
      <p class="hint">Reading themes, fonts, and page-turn style are in the Aa menu inside any book.</p>
    </div>
    <div class="panel" id="owner-panel">
      <h2>Cloud voice and AI</h2>
      <p class="muted">These optional features use the site owner’s paid accounts, so they only work on devices that have the owner’s access code.</p>
      <dl class="kv" id="feat-list"><dt>Status</dt><dd>Checking…</dd></dl>
      <form id="code-form" class="field" autocomplete="off">
        <label for="access-code">Owner access code</label>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <input class="input" id="access-code" type="password" autocomplete="off" style="flex:1 1 200px;width:auto" placeholder="${accessCode() ? 'Saved on this device' : 'Enter the code'}" />
          <button class="btn btn-primary" type="submit">Save code</button>
          ${accessCode() ? html`<button class="btn btn-quiet" type="button" data-forget>Forget</button>` : ''}
        </div>
        <span class="hint" id="code-msg">The code is stored only on this device.</span>
      </form>
      <div class="setting-row"><span>Read-aloud voice</span>
        <div class="seg" role="group" aria-label="Default voice">${[['device', 'This device'], ['cloud', 'Cloud voice']].map(([v, l]) => html`<button type="button" data-engine="${v}" aria-pressed="${store.getSetting('voiceEngine', 'device') === v}">${l}</button>`)}</div></div>
      <p class="hint">Cloud voice keeps reading with the screen off and works with car and Bluetooth buttons. The device voice is free but stops when the phone locks.</p>
    </div>
    <div class="panel">
      <h2>This device</h2>
      <dl class="kv">
        <dt>Storage</dt><dd>${est ? html`${formatBytes(est.usage)} used${est.quota ? html` of about ${formatBytes(est.quota)}` : ''}${est.persisted ? ' · protected from cleanup' : ''}` : 'Not reported by this browser'}</dd>
        <dt>Installed app</dt><dd>${standalone ? 'Yes, running as an installed app' : 'No. Use your browser’s “Install app” or “Add to Home screen” option.'}</dd>
        <dt>Read aloud</dt><dd>${ttsSupported ? 'Available' : 'Not supported in this browser'}</dd>
        <dt>Voice search</dt><dd>${voiceInputSupported ? 'Available (speech may be processed by your browser’s provider)' : 'Not supported in this browser; type instead'}</dd>
        <dt>Accounts</dt><dd>${authConfigured ? 'Available' : 'Not connected on this deployment (guest mode)'}</dd>
      </dl>
      <div><button type="button" class="btn btn-sm" data-persist>Keep my downloads safe from cleanup</button></div>
    </div>
    <div class="panel">
      <h2>About</h2>
      <p class="muted">Mavis Library: your books, your library, your imagination. Free books come from Project Gutenberg (via Gutendex). Book search beyond free titles comes from Open Library. Definitions come from the Free Dictionary API. Borrowing and buying happen on the provider’s own site or app.</p>
    </div>
  </div></div>`);
  async function paintFeatures() {
    const f = await loadFeatures({ force: true });
    const yes = (t) => html`<span class="badge badge-ok">${icon('check', { size: 14 })} ${t}</span>`;
    const no = (t) => html`<span class="badge">${t}</span>`;
    root.querySelector('#feat-list').innerHTML = String(html`
      <dt>This device</dt><dd>${f.owner ? yes('Owner access') : f.accessCode ? no('No access code yet') : no('Owner hasn’t set an access code')}</dd>
      <dt>Cloud voice</dt><dd>${f.cloudVoice ? yes(f.cloudVoice === 'fish' ? 'Fish Audio' : 'Google Text-to-Speech') : no('Not set up')}</dd>
      <dt>Ask Mavis (AI)</dt><dd>${f.assistant ? yes(`${f.assistant.provider === 'anthropic' ? 'Anthropic' : 'OpenAI-compatible'} · ${f.assistant.model}`) : no('Not set up')}</dd>
      <dt>Jev picks</dt><dd>${f.jev ? yes(f.jev === 'typesafe' ? 'TypeSafe AI' : 'Eden AI') : no('Not set up (free genre ranking in use)')}</dd>`);
    if (!f.reachable) root.querySelector('#feat-list').innerHTML = String(html`<dt>Status</dt><dd>The Mavis server can’t be reached right now.</dd>`);
  }
  paintFeatures();
  root.querySelector('#code-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const v = root.querySelector('#access-code').value.trim();
    if (!v) return;
    const f = await setAccessCode(v);
    root.querySelector('#code-msg').textContent = f.owner ? 'Code accepted. Paid features are unlocked on this device.' : 'That code didn’t match. Check it and try again.';
    root.querySelector('#access-code').value = '';
    paintFeatures();
  });
  root.addEventListener('click', async (e) => {
    if (e.target.closest('[data-forget]')) { await setAccessCode(''); toast('Access code removed from this device.'); paintFeatures(); }
    const eng = e.target.closest('[data-engine]');
    if (eng) {
      await store.setSetting('voiceEngine', eng.dataset.engine);
      root.querySelectorAll('[data-engine]').forEach((x) => x.setAttribute('aria-pressed', String(x === eng)));
    }
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.theme) {
      await store.setSetting('appTheme', b.dataset.theme); applyAppTheme();
      root.querySelectorAll('[data-theme]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    }
    if (b.dataset.motion) {
      await store.setSetting('motion', b.dataset.motion); applyAppTheme();
      root.querySelectorAll('[data-motion]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    }
    if (b.hasAttribute('data-persist')) {
      try {
        const ok = await navigator.storage?.persist?.();
        toast(ok ? 'Downloads are protected from automatic cleanup.' : 'Your browser decided not to protect storage right now. Installing the app usually allows it.');
      } catch { toast('This browser doesn’t support protected storage.'); }
    }
  });
}
