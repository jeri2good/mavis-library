// Accounts on Mavis's own server (Netlify Functions + Netlify Blobs).
// Passwords are sent only to /api/account over HTTPS and stored there as
// scrypt hashes. This device keeps a signed session token so you stay signed
// in, including offline. There is no email: a recovery code shown once at
// sign-up is how a forgotten password is reset.

import { loadFeatures } from './features.js';

const SESSION_KEY = 'mavis-session';
const AVAILABLE_KEY = 'mavis-accounts-available';

export let authConfigured = readLocal(AVAILABLE_KEY) === '1';
export const googleEnabled = false;

let session = parse(readLocal(SESSION_KEY)); // { token, user: { id, email } }
const listeners = new Set();

function readLocal(k) { try { return localStorage.getItem(k); } catch { return null; } }
function writeLocal(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* private mode */ } }
function parse(s) { try { const o = JSON.parse(s); return o?.token && o?.user?.id ? o : null; } catch { return null; } }

export function onAuth(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function emit(event) { for (const fn of listeners) { try { fn({ user: session?.user || null, event }); } catch (e) { console.error(e); } } }

export function currentUser() { return session?.user || null; }
export function sessionToken() { return session?.token || null; }
export function inRecovery() { return false; }

function save(next) {
  session = next;
  writeLocal(SESSION_KEY, next ? JSON.stringify(next) : null);
}

export class AuthError extends Error {
  constructor(message, status, code) { super(message); this.status = status; this.code = code; }
}

async function call(action, { method = 'POST', body, token } = {}) {
  let r;
  try {
    r = await fetch(`/api/account/${action}`, {
      method,
      headers: { accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      cache: 'no-store',
    });
  } catch {
    throw new AuthError('Could not reach Mavis. Check your connection and try again.', 0, 'offline');
  }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new AuthError(j.message || `The account service answered ${r.status}.`, r.status, j.error);
  return j;
}

/** Find out whether accounts are switched on, and confirm a saved session is still valid. */
export async function initAuth() {
  const f = await loadFeatures();
  if (f.reachable) {
    authConfigured = !!f.accounts;
    writeLocal(AVAILABLE_KEY, authConfigured ? '1' : '0');
  }
  if (!authConfigured) { if (f.reachable) save(null); emit('INITIAL'); return null; }
  if (session && navigator.onLine !== false) {
    try {
      const me = await call('me', { method: 'GET', token: session.token });
      save({ token: me.token || session.token, user: me.user });
    } catch (err) {
      if (err.status === 401) sessionExpired();
      // Offline or server trouble: keep the saved session and try later.
    }
  }
  emit('INITIAL');
  return currentUser();
}

/** Called by sync when the server says the session is no longer valid. */
export function sessionExpired() {
  if (!session) return;
  save(null);
  emit('SIGNED_OUT');
  emit({ error: 'Your session ended (you may have signed out everywhere or changed your password). Sign in again to keep syncing.' });
}

export async function signUp(email, password) {
  const j = await call('signup', { body: { email, password } });
  save({ token: j.token, user: j.user });
  return { recoveryCode: j.recoveryCode, user: j.user };
}

export function announceSignedIn() { emit('SIGNED_IN'); }

export async function signIn(email, password) {
  const j = await call('signin', { body: { email, password } });
  save({ token: j.token, user: j.user });
  emit('SIGNED_IN');
}

export async function recoverWithCode(email, recoveryCode, password) {
  const j = await call('recover', { body: { email, recoveryCode, password } });
  save({ token: j.token, user: j.user });
  return { recoveryCode: j.recoveryCode };
}

export async function changePassword(currentPassword, password) {
  const j = await call('password', { body: { currentPassword, password }, token: session?.token });
  save({ ...session, token: j.token });
}

export async function newRecoveryCode(password) {
  const j = await call('recovery-code', { body: { password }, token: session?.token });
  return j.recoveryCode;
}

export async function signOutEverywhere() {
  await call('signout-all', { body: {}, token: session?.token });
  save(null);
  emit('SIGNED_OUT');
}

export async function deleteAccount(password) {
  await call('delete', { body: { password }, token: session?.token });
  save(null);
  emit('SIGNED_OUT');
}

export async function signOut() {
  save(null);
  emit('SIGNED_OUT');
}
