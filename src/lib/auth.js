// Accounts via Supabase Auth. Mavis never stores or checks passwords itself.
// When VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY are not set at build time,
// accounts are reported as "not connected" and the app runs in guest mode.

const URL_ = import.meta.env.VITE_SUPABASE_URL || '';
const KEY = import.meta.env.VITE_SUPABASE_ANON_KEY || '';
export const authConfigured = Boolean(URL_ && KEY);
export const googleEnabled = authConfigured && import.meta.env.VITE_AUTH_GOOGLE === 'true';

let client = null;
let user = null;
let recovery = false;
const listeners = new Set();

export function onAuth(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function emit(event) { for (const fn of listeners) { try { fn({ user, event, recovery }); } catch (e) { console.error(e); } } }

export function currentUser() { return user; }
export function inRecovery() { return recovery; }
export async function getClient() {
  if (!authConfigured) return null;
  if (client) return client;
  const { createClient } = await import('@supabase/supabase-js');
  client = createClient(URL_, KEY, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true, flowType: 'pkce' },
  });
  return client;
}

export async function initAuth() {
  if (!authConfigured) { emit('INITIAL'); return null; }
  try {
    const sb = await getClient();
    sb.auth.onAuthStateChange((event, session) => {
      user = session?.user || null;
      if (event === 'PASSWORD_RECOVERY') recovery = true;
      if (event === 'SIGNED_OUT') recovery = false;
      // Defer so Supabase's internal lock is released before listeners query.
      setTimeout(() => emit(event), 0);
    });
    const { data } = await sb.auth.getSession();
    user = data.session?.user || null;
    // Clean ?code= / error params out of the address bar after the redirect.
    const sp = new URLSearchParams(location.search);
    if (sp.has('code') || sp.has('error_description') || sp.has('reset')) {
      if (sp.get('error_description')) setTimeout(() => emit({ error: sp.get('error_description') }), 0);
      history.replaceState(null, '', location.pathname + location.hash);
    }
  } catch (err) {
    console.warn('Auth unavailable', err);
  }
  return user;
}

const redirectTo = () => `${location.origin}${location.pathname}`;

function friendly(error) {
  const m = error?.message || String(error || '');
  if (/invalid login credentials/i.test(m)) return 'That email and password don’t match. Check them, or reset your password.';
  if (/email not confirmed/i.test(m)) return 'Confirm your email first. Check your inbox for the link we sent.';
  if (/already registered|already been registered/i.test(m)) return 'An account with that email already exists. Sign in instead.';
  if (/password should be at least|weak password/i.test(m)) return 'Choose a longer password (at least 8 characters, mixing letters and numbers).';
  if (/rate limit|too many/i.test(m)) return 'Too many attempts. Wait a few minutes and try again.';
  if (/fetch|network/i.test(m)) return 'Could not reach the account service. Check your connection.';
  return m || 'Something went wrong with your account request.';
}

export async function signUp(email, password) {
  const sb = await getClient();
  const { data, error } = await sb.auth.signUp({ email, password, options: { emailRedirectTo: redirectTo() } });
  if (error) throw new Error(friendly(error));
  // With email confirmation on, Supabase returns a user without a session.
  return { needsConfirmation: !data.session, existing: data.user && data.user.identities?.length === 0 };
}

export async function signIn(email, password) {
  const sb = await getClient();
  const { error } = await sb.auth.signInWithPassword({ email, password });
  if (error) throw new Error(friendly(error));
}

export async function signInWithGoogle() {
  const sb = await getClient();
  const { error } = await sb.auth.signInWithOAuth({ provider: 'google', options: { redirectTo: redirectTo() } });
  if (error) throw new Error(friendly(error));
}

export async function sendReset(email) {
  const sb = await getClient();
  const { error } = await sb.auth.resetPasswordForEmail(email, { redirectTo: redirectTo() });
  if (error) throw new Error(friendly(error));
}

export async function updatePassword(password) {
  const sb = await getClient();
  const { error } = await sb.auth.updateUser({ password });
  if (error) throw new Error(friendly(error));
  recovery = false;
  emit('PASSWORD_UPDATED');
}

export async function signOut() {
  const sb = await getClient();
  if (!sb) return;
  const { error } = await sb.auth.signOut();
  if (error) throw new Error(friendly(error));
}
