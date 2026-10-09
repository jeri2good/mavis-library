import { html, icon, toast, timeAgo, confirmDialog } from '../lib/ui.js';
import * as auth from '../lib/auth.js';
import { syncNow, onSyncState, flushBeforeSignOut, stopSync } from '../lib/sync.js';
import * as store from '../lib/store.js';

export const title = () => 'Account';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export async function render(root, route) {
  let mode = route.params.get('mode') === 'signup' ? 'signup' : 'signin';
  let unsubSync = null;
  let pendingCode = null; // recovery code waiting to be acknowledged

  function paint() {
    unsubSync?.(); unsubSync = null;
    if (pendingCode) return paintRecoveryCode(pendingCode.code, pendingCode.fresh, pendingCode.announce);
    const user = auth.currentUser();
    if (user) return paintSignedIn(user);
    if (!auth.authConfigured) return paintNotConfigured();
    return paintSignedOut();
  }

  function shell(inner) {
    root.innerHTML = String(html`<div class="page"><div class="account">${inner}</div></div>`);
  }

  function paintNotConfigured() {
    shell(html`
      <h1>Account</h1>
      <div class="panel">
        <h2>You’re reading as a guest</h2>
        <p class="muted">Accounts aren’t switched on for this copy of Mavis Library yet, so your shelf, progress, bookmarks, and notes are saved only in this browser on this device.</p>
        <p class="muted">Everything else works: discover and download free books, import your own, read, highlight, and listen.</p>
        <p class="small faint">For the site owner: set AUTH_SECRET (a random string of 32+ characters) in the Netlify project’s environment variables and redeploy. Accounts are stored in Netlify Blobs; no other service is needed.</p>
      </div>`);
  }

  function paintSignedOut() {
    const signup = mode === 'signup';
    shell(html`
      <h1>${signup ? 'Create your account' : 'Welcome back'}</h1>
      <p class="muted">An account keeps your shelf, reading progress, bookmarks, highlights, notes, and saved quotes in sync across your phone, tablet, and computer. You can keep reading as a guest without one.</p>
      <div class="seg" role="group" aria-label="Account action">
        <button type="button" data-mode="signin" aria-pressed="${!signup}">Sign in</button>
        <button type="button" data-mode="signup" aria-pressed="${signup}">Create account</button>
      </div>
      <form class="panel" id="auth-form" novalidate>
        <div class="field"><label for="email">Email</label><input class="input" id="email" name="email" type="email" autocomplete="email" required inputmode="email" /></div>
        <div class="field"><label for="password">Password</label>
          <input class="input" id="password" name="password" type="password" autocomplete="${signup ? 'new-password' : 'current-password'}" required minlength="8" />
          ${signup ? html`<span class="hint">At least 8 characters. Use something you don’t use elsewhere.</span>` : ''}</div>
        <p class="form-error" id="auth-error" role="alert"></p>
        <button class="btn btn-primary btn-block" type="submit" id="auth-submit">${signup ? 'Create account' : 'Sign in'}</button>
        ${!signup ? html`<button class="btn btn-quiet btn-sm" type="button" data-forgot>Forgot your password?</button>` : ''}
      </form>
      <p class="small faint">Your account lives on this site’s own server. Your password is stored only as a one-way hash. ${signup ? 'There’s no email step: after you sign up you’ll get a recovery code — keep it somewhere safe; it’s the only way to reset a forgotten password.' : ''}</p>`);
    root.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => { mode = b.dataset.mode; paint(); }));
    const form = root.querySelector('#auth-form');
    const err = root.querySelector('#auth-error');
    const submit = root.querySelector('#auth-submit');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      err.textContent = '';
      const email = form.email.value.trim();
      const password = form.password.value;
      if (!EMAIL_RE.test(email)) { err.textContent = 'Enter a valid email address.'; form.email.focus(); return; }
      if (password.length < (signup ? 8 : 1)) { err.textContent = signup ? 'Use at least 8 characters for your password.' : 'Enter your password.'; form.password.focus(); return; }
      submit.disabled = true;
      submit.textContent = signup ? 'Creating account…' : 'Signing in…';
      try {
        if (signup) {
          const r = await auth.signUp(email, password);
          pendingCode = { code: r.recoveryCode, fresh: true };
          paint();
        } else {
          await auth.signIn(email, password);
          toast('Signed in.');
        }
      } catch (e2) {
        err.textContent = e2.message;
      } finally {
        if (submit.isConnected) { submit.disabled = false; submit.textContent = signup ? 'Create account' : 'Sign in'; }
      }
    });
    root.querySelector('[data-forgot]')?.addEventListener('click', paintForgot);
  }

  function paintRecoveryCode(code, fresh, announce = fresh) {
    shell(html`
      <h1>${fresh ? 'Save your recovery code' : 'Your new recovery code'}</h1>
      <div class="panel recovery">
        <p>If you ever forget your password, this code is the only way back into your account. Mavis can’t email you a reset link.</p>
        <p class="recovery-code num" id="rcode" aria-label="Recovery code">${code}</p>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button type="button" class="btn btn-sm" data-copy>${icon('copy', { size: 18 })} Copy</button>
          <button type="button" class="btn btn-sm" data-save>${icon('download', { size: 18 })} Save as a file</button>
        </div>
        <label style="display:flex;gap:10px;align-items:flex-start;margin-top:8px"><input type="checkbox" id="saved" style="width:20px;height:20px;margin-top:2px" /><span>I’ve saved this code somewhere safe (a password manager, a photo, or on paper).</span></label>
        <button type="button" class="btn btn-primary" data-continue disabled>Continue</button>
        ${fresh ? '' : html`<p class="small faint">Your old recovery code no longer works.</p>`}
      </div>`);
    root.querySelector('[data-copy]').addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(code); toast('Recovery code copied.'); } catch { toast('Couldn’t copy. Select the code and copy it by hand.', { tone: 'error' }); }
    });
    root.querySelector('[data-save]').addEventListener('click', () => {
      const user = auth.currentUser();
      const text = `Mavis Library recovery code\n\nAccount: ${user?.email || ''}\nRecovery code: ${code}\nSite: ${location.origin}\n\nUse this on the sign-in page (Forgot your password?) to choose a new password.\n`;
      const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([text], { type: 'text/plain' })), download: 'mavis-recovery-code.txt' });
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    });
    const box = root.querySelector('#saved');
    const go = root.querySelector('[data-continue]');
    box.addEventListener('change', () => { go.disabled = !box.checked; });
    go.addEventListener('click', () => {
      pendingCode = null;
      if (fresh) toast('Account created. You’re signed in.');
      if (announce) auth.announceSignedIn();
      paint();
    });
  }

  function paintForgot() {
    shell(html`
      <h1>Reset your password</h1>
      <form class="panel" id="reset-form" novalidate>
        <p class="muted">Enter your account email, the recovery code you saved when you signed up, and a new password.</p>
        <div class="field"><label for="remail">Email</label><input class="input" id="remail" type="email" autocomplete="email" required /></div>
        <div class="field"><label for="rcodein">Recovery code</label><input class="input num" id="rcodein" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="XXXX-XXXX-XXXX-XXXX" required /></div>
        <div class="field"><label for="rpw">New password</label><input class="input" id="rpw" type="password" autocomplete="new-password" minlength="8" required /><span class="hint">At least 8 characters.</span></div>
        <p class="form-error" role="alert" id="rerr"></p>
        <div class="dialog-actions" style="justify-content:flex-start"><button class="btn btn-primary" type="submit">Reset password</button><button class="btn btn-quiet" type="button" data-back>Back to sign in</button></div>
        <p class="small faint">Lost your recovery code too? The site owner can’t see your password or code; you’ll need to create a new account.</p>
      </form>`);
    const f = root.querySelector('#reset-form');
    const rerr = root.querySelector('#rerr');
    f.addEventListener('submit', async (e) => {
      e.preventDefault();
      rerr.textContent = '';
      const email = f.remail.value.trim();
      if (!EMAIL_RE.test(email)) { rerr.textContent = 'Enter a valid email address.'; return; }
      if (f.rcodein.value.replace(/[^0-9a-z]/gi, '').length < 16) { rerr.textContent = 'Enter the full 16-character recovery code.'; return; }
      if (f.rpw.value.length < 8) { rerr.textContent = 'Use at least 8 characters for your new password.'; return; }
      try {
        const r = await auth.recoverWithCode(email, f.rcodein.value, f.rpw.value);
        // Show the new code first; switching the app to the account (which
        // redraws this page) waits until the code has been saved.
        pendingCode = { code: r.recoveryCode, fresh: false, announce: true };
        toast('Password reset.');
        paint();
      } catch (e2) { rerr.textContent = e2.message; }
    });
    root.querySelector('[data-back]').addEventListener('click', paint);
  }

  function paintSignedIn(user) {
    shell(html`
      <h1>Your account</h1>
      <div class="panel">
        <dl class="kv"><dt>Signed in as</dt><dd>${user.email}</dd><dt>Sync</dt><dd id="sync-state">…</dd></dl>
        <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn btn-sm" type="button" data-sync>${icon('refresh', { size: 18 })} Sync now</button></div>
      </div>
      <div class="panel">
        <h2>What syncs</h2>
        <p class="muted">Your shelf, collections, reading position, bookmarks, highlights, notes, and saved quotes — in books and in the Bible — sync to every device where you sign in. The most recent change wins if two devices edit the same thing.</p>
        <p class="muted"><strong>Book files don’t sync.</strong> Free books can be downloaded again on each device with one tap. Books you imported stay on the device you imported them on; import the same file on another device to read it there.</p>
      </div>
      <div class="panel">
        <h2>Security</h2>
        <details><summary>Change password</summary>
          <form id="pw-form" class="stack" novalidate>
            <div class="field"><label for="cpw">Current password</label><input class="input" id="cpw" type="password" autocomplete="current-password" required /></div>
            <div class="field"><label for="npw">New password</label><input class="input" id="npw" type="password" autocomplete="new-password" minlength="8" required /></div>
            <p class="form-error" role="alert" id="pwerr"></p>
            <div><button class="btn btn-sm btn-primary" type="submit">Change password</button></div>
            <p class="small faint">Other devices will be signed out.</p>
          </form>
        </details>
        <details><summary>Get a new recovery code</summary>
          <form id="rc-form" class="stack" novalidate>
            <div class="field"><label for="rcpw">Your password</label><input class="input" id="rcpw" type="password" autocomplete="current-password" required /></div>
            <p class="form-error" role="alert" id="rcerr"></p>
            <div><button class="btn btn-sm" type="submit">Make a new code</button></div>
          </form>
        </details>
        <div><button class="btn btn-sm" type="button" data-everywhere>${icon('logout', { size: 18 })} Sign out on all devices</button></div>
      </div>
      <div class="panel">
        <h2>Sign out</h2>
        <label style="display:flex;gap:10px;align-items:flex-start"><input type="checkbox" id="wipe" style="width:20px;height:20px;margin-top:2px" /><span>Also remove this account’s shelf, notes, and downloaded books from this device <span class="faint">(recommended on shared devices; everything stays in your account)</span></span></label>
        <div><button class="btn" type="button" data-signout>${icon('logout', { size: 18 })} Sign out</button></div>
      </div>
      <div class="panel">
        <h2>Delete account</h2>
        <details><summary>Delete my account and everything synced to it</summary>
          <form id="del-form" class="stack" novalidate>
            <p class="small">This permanently removes your account and its synced shelf, progress, and notes from the server. Copies on your devices stay until you remove them.</p>
            <div class="field"><label for="dpw">Your password</label><input class="input" id="dpw" type="password" autocomplete="current-password" required /></div>
            <p class="form-error" role="alert" id="delerr"></p>
            <div><button class="btn btn-sm btn-danger" type="submit">Delete my account</button></div>
          </form>
        </details>
      </div>`);
    const stateEl = root.querySelector('#sync-state');
    unsubSync = onSyncState((s) => {
      if (!stateEl.isConnected) return;
      stateEl.textContent = s.status === 'syncing' ? 'Syncing…'
        : s.status === 'error' ? `Paused: ${s.error}. Changes are kept on this device and will sync when it recovers.`
          : s.status === 'offline' ? 'Offline. Changes will sync when you reconnect.'
            : s.lastSyncedAt ? `Up to date · ${timeAgo(s.lastSyncedAt)}` : 'Waiting to sync';
    });
    root.querySelector('[data-sync]').addEventListener('click', () => syncNow());

    const pwf = root.querySelector('#pw-form');
    pwf.addEventListener('submit', async (e) => {
      e.preventDefault();
      const out = root.querySelector('#pwerr');
      out.textContent = '';
      if (pwf.npw.value.length < 8) { out.textContent = 'Use at least 8 characters.'; return; }
      try { await auth.changePassword(pwf.cpw.value, pwf.npw.value); toast('Password changed. Other devices were signed out.'); pwf.reset(); }
      catch (e2) { out.textContent = e2.message; }
    });
    const rcf = root.querySelector('#rc-form');
    rcf.addEventListener('submit', async (e) => {
      e.preventDefault();
      try { const code = await auth.newRecoveryCode(rcf.rcpw.value); pendingCode = { code, fresh: false }; paint(); }
      catch (e2) { root.querySelector('#rcerr').textContent = e2.message; }
    });
    root.querySelector('[data-everywhere]').addEventListener('click', async () => {
      const ok = await confirmDialog({ title: 'Sign out on all devices?', message: 'Every phone, tablet, and computer signed in to this account will need to sign in again. Unsynced changes on other devices stay on those devices until they sign back in.', confirmLabel: 'Sign out everywhere' });
      if (!ok) return;
      try { await flushBeforeSignOut(); stopSync(); await auth.signOutEverywhere(); toast('Signed out on all devices.'); }
      catch (e2) { toast(e2.message, { tone: 'error' }); }
    });
    root.querySelector('[data-signout]').addEventListener('click', async (e) => {
      e.target.disabled = true;
      const wipe = root.querySelector('#wipe').checked;
      try {
        await flushBeforeSignOut();
        const id = user.id;
        stopSync();
        await auth.signOut();
        if (wipe) await store.clearOwnerData(id);
        store.setOwner(store.GUEST);
        toast(wipe ? 'Signed out and removed your data from this device.' : 'Signed out.');
        paint();
      } catch (err) {
        toast(`Sign-out failed: ${err.message}`, { tone: 'error' });
        e.target.disabled = false;
      }
    });
    const df = root.querySelector('#del-form');
    df.addEventListener('submit', async (e) => {
      e.preventDefault();
      const ok = await confirmDialog({ title: 'Delete your account?', message: 'This can’t be undone.', confirmLabel: 'Delete account' });
      if (!ok) return;
      try {
        const id = user.id;
        stopSync();
        await auth.deleteAccount(df.dpw.value);
        await store.clearOwnerData(id);
        store.setOwner(store.GUEST);
        toast('Your account was deleted.');
        paint();
      } catch (e2) { root.querySelector('#delerr').textContent = e2.message; }
    });
  }

  paint();
  const off = auth.onAuth(() => { if (!pendingCode) paint(); });
  return () => { off(); unsubSync?.(); };
}
