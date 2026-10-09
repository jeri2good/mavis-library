import { html, toast, formatBytes } from '../lib/ui.js';
import * as store from '../lib/store.js';
import { applyAppTheme } from '../main.js';
import { ttsSupported } from '../lib/tts.js';
import { voiceInputSupported } from '../lib/voice-input.js';
import { authConfigured } from '../lib/auth.js';

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
  root.addEventListener('click', async (e) => {
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
