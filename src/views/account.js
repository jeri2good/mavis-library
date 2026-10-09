import { html, icon, toast, timeAgo } from '../lib/ui.js';
import * as auth from '../lib/auth.js';
import { syncNow, onSyncState, flushBeforeSignOut, stopSync } from '../lib/sync.js';
import * as store from '../lib/store.js';

export const title = () => 'Account';

export async function render(root, route) {
  let mode = route.params.get('mode') === 'signup' ? 'signup' : 'signin';
  let unsubSync = null;

  function paint() {
    unsubSync?.(); unsubSync = null;
    const user = auth.currentUser();
    if (!auth.authConfigured) return paintNotConfigured();
    if (auth.inRecovery() && user) return paintRecovery();
    if (user) return paintSignedIn(user);
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
        <p class="small faint">For the site owner: add a Supabase project’s URL and public anon key as VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY, then redeploy. See the README.</p>
      </div>`);
  }

  function paintSignedOut() {
    const signup = mode === 'signup';
    shell(html`
      <h1>${signup ? 'Create your account' : 'Welcome back'}</h1>
      <p class="muted">An account keeps your shelf, reading progress, bookmarks, highlights, and notes in sync across your phone, tablet, and computer. You can keep reading as a guest without one.</p>
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
        ${auth.googleEnabled ? html`<div style="display:grid;gap:8px"><span class="hint" style="text-align:center">or</span><button class="btn btn-block" type="button" data-google>${icon('globe', { size: 20 })} Continue with Google</button></div>` : ''}
      </form>
      <p class="small faint">Accounts are handled by Supabase Auth; Mavis never sees or stores your password. ${signup ? 'We’ll email you a link to confirm your address.' : ''}</p>`);
    root.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => { mode = b.dataset.mode; paint(); }));
    const form = root.querySelector('#auth-form');
    const err = root.querySelector('#auth-error');
    const submit = root.querySelector('#auth-submit');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      err.textContent = '';
      const email = form.email.value.trim();
      const password = form.password.value;
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { err.textContent = 'Enter a valid email address.'; form.email.focus(); return; }
      if (password.length < (signup ? 8 : 1)) { err.textContent = signup ? 'Use at least 8 characters for your password.' : 'Enter your password.'; form.password.focus(); return; }
      submit.disabled = true;
      submit.textContent = signup ? 'Creating account…' : 'Signing in…';
      try {
        if (signup) {
          const r = await auth.signUp(email, password);
          if (r.existing) { err.textContent = 'An account with that email may already exist. Try signing in or resetting your password.'; return; }
          if (r.needsConfirmation) {
            shell(html`<h1>Check your email</h1><div class="panel"><p>We sent a confirmation link to <strong>${email}</strong>. Open it on this device to finish creating your account.</p><p class="small faint">No email after a few minutes? Check your spam folder, or try again.</p><button type="button" class="btn" data-again>Back</button></div>`);
            root.querySelector('[data-again]').addEventListener('click', paint);
          }
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
    root.querySelector('[data-google]')?.addEventListener('click', async () => {
      try { await auth.signInWithGoogle(); } catch (e3) { err.textContent = e3.message; }
    });
  }

  function paintForgot() {
    shell(html`
      <h1>Reset your password</h1>
      <form class="panel" id="reset-form" novalidate>
        <p class="muted">Enter your account email. We’ll send a link that lets you choose a new password.</p>
        <div class="field"><label for="remail">Email</label><input class="input" id="remail" type="email" autocomplete="email" required /></div>
        <p class="form-error" role="alert" id="rerr"></p>
        <div class="dialog-actions" style="justify-content:flex-start"><button class="btn btn-primary" type="submit">Send reset link</button><button class="btn btn-quiet" type="button" data-back>Back to sign in</button></div>
      </form>`);
    const f = root.querySelector('#reset-form');
    f.addEventListener('submit', async (e) => {
      e.preventDefault();
      const email = f.remail.value.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { root.querySelector('#rerr').textContent = 'Enter a valid email address.'; return; }
      try {
        await auth.sendReset(email);
        shell(html`<h1>Check your email</h1><div class="panel"><p>If an account exists for <strong>${email}</strong>, a reset link is on its way. Open it on this device.</p><button type="button" class="btn" data-again>Back to sign in</button></div>`);
        root.querySelector('[data-again]').addEventListener('click', paint);
      } catch (e2) { root.querySelector('#rerr').textContent = e2.message; }
    });
    root.querySelector('[data-back]').addEventListener('click', paint);
  }

  function paintRecovery() {
    shell(html`
      <h1>Choose a new password</h1>
      <form class="panel" id="pw-form" novalidate>
        <div class="field"><label for="npw">New password</label><input class="input" id="npw" type="password" autocomplete="new-password" minlength="8" required autofocus /><span class="hint">At least 8 characters.</span></div>
        <p class="form-error" role="alert" id="perr"></p>
        <button class="btn btn-primary" type="submit">Save new password</button>
      </form>`);
    const f = root.querySelector('#pw-form');
    f.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (f.npw.value.length < 8) { root.querySelector('#perr').textContent = 'Use at least 8 characters.'; return; }
      try { await auth.updatePassword(f.npw.value); toast('Password updated.'); paint(); }
      catch (e2) { root.querySelector('#perr').textContent = e2.message; }
    });
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
        <p class="muted">Your shelf, collections, reading position, bookmarks, highlights, and notes sync to every device where you sign in. The most recent change wins if two devices edit the same thing.</p>
        <p class="muted"><strong>Book files don’t sync.</strong> Free books can be downloaded again on each device with one tap. Books you imported stay on the device you imported them on; import the same file on another device to read it there.</p>
      </div>
      <div class="panel">
        <h2>Sign out</h2>
        <label style="display:flex;gap:10px;align-items:flex-start"><input type="checkbox" id="wipe" style="width:20px;height:20px;margin-top:2px" /><span>Also remove this account’s shelf, notes, and downloaded books from this device <span class="faint">(recommended on shared devices; everything stays in your account)</span></span></label>
        <div><button class="btn" type="button" data-signout>${icon('logout', { size: 18 })} Sign out</button></div>
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
  }

  paint();
  const off = auth.onAuth(() => paint());
  return () => { off(); unsubSync?.(); };
}
