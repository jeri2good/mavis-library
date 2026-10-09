// /api/account/<action> — Mavis accounts on Netlify Blobs.
//   POST signup        { email, password }                 → { token, user, recoveryCode }
//   POST signin        { email, password }                 → { token, user }
//   POST recover       { email, recoveryCode, password }   → { token, user, recoveryCode }
//   GET  me            (Bearer)                            → { user, token? }
//   POST password      (Bearer) { currentPassword, password } → { token }
//   POST recovery-code (Bearer) { password }               → { recoveryCode }
//   POST signout-all   (Bearer)                            → { ok }
//   POST delete        (Bearer) { password }               → { ok }
// Requires AUTH_SECRET (32+ characters) in the site's environment.

import { json, fail, onlyPost, readJson, softLimit, clientKey } from '../lib/shared.mjs';
import {
  accountsEnabled, validEmail, passwordProblem, createUser, findByEmail, verifySecret, hashSecret,
  signToken, requireUser, updateUser, deleteUser, newRecoveryCode, normRecovery, publicUser,
  throttleCheck, throttleFail, throttleClear,
} from '../lib/accounts.mjs';

const MISMATCH = 'That email and password don’t match.';

async function body(req) {
  const bad = onlyPost(req);
  if (bad) throw Object.assign(new Error('bad'), { response: bad });
  let b;
  try { b = await readJson(req, 8_000); } catch (err) { throw Object.assign(err, { response: fail(err.status || 400, 'bad_request', err.message) }); }
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw Object.assign(new Error('bad'), { response: fail(400, 'bad_request', 'Send a JSON object.') });
  for (const k of ['email', 'password', 'currentPassword', 'recoveryCode']) if (b[k] != null && typeof b[k] !== 'string') b[k] = '';
  return b;
}

const actions = {
  async signup(req) {
    const { email, password } = await body(req);
    if (!validEmail(email)) return fail(400, 'bad_email', 'Enter a valid email address.');
    const problem = passwordProblem(password);
    if (problem) return fail(400, 'weak_password', problem);
    const out = await createUser(email, password);
    if (out.exists) return fail(409, 'exists', 'An account with that email already exists. Sign in instead.');
    return json({ token: signToken(out.user), user: publicUser(out.user), recoveryCode: out.recoveryCode });
  },

  async signin(req) {
    const { email, password } = await body(req);
    if (!validEmail(email) || !password) return fail(400, 'bad_request', 'Enter your email and password.');
    const wait = await throttleCheck(email);
    if (wait) return fail(429, 'locked', `Too many tries. Wait ${wait > 90 ? `${Math.ceil(wait / 60)} minutes` : `${wait} seconds`} and try again.`);
    const user = await findByEmail(email);
    // Hash even when the account doesn't exist so timing doesn't reveal it.
    const ok = user ? await verifySecret(password, user.pw) : (await hashSecret(password), false);
    if (!ok) { await throttleFail(email); return fail(401, 'mismatch', MISMATCH); }
    await throttleClear(email);
    return json({ token: signToken(user), user: publicUser(user) });
  },

  async recover(req) {
    const { email, recoveryCode, password } = await body(req);
    if (!validEmail(email) || !recoveryCode) return fail(400, 'bad_request', 'Enter your email and recovery code.');
    const problem = passwordProblem(password);
    if (problem) return fail(400, 'weak_password', problem);
    const wait = await throttleCheck(email);
    if (wait) return fail(429, 'locked', `Too many tries. Wait ${Math.ceil(wait / 60)} minutes and try again.`);
    const user = await findByEmail(email);
    const ok = user ? await verifySecret(normRecovery(recoveryCode), user.rc) : (await hashSecret('x'), false);
    if (!ok) { await throttleFail(email); return fail(401, 'mismatch', 'That email and recovery code don’t match.'); }
    await throttleClear(email);
    const code = newRecoveryCode();
    const pw = await hashSecret(password);
    const rc = await hashSecret(normRecovery(code));
    const next = await updateUser(user.id, (u) => ({ pw, rc, tokenVersion: (u.tokenVersion || 0) + 1 }));
    return json({ token: signToken(next), user: publicUser(next), recoveryCode: code });
  },

  async me(req) {
    if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
    const a = await requireUser(req);
    if (a.response) return a.response;
    // Refresh tokens older than a week so active devices stay signed in.
    const fresh = Date.now() - a.token.i > 7 * 864e5 ? signToken(a.user) : undefined;
    return json({ user: publicUser(a.user), token: fresh });
  },

  async password(req) {
    const a = await requireUser(req);
    if (a.response) return a.response;
    const { currentPassword, password } = await body(req);
    if (!(await verifySecret(currentPassword, a.user.pw))) return fail(401, 'mismatch', 'Your current password isn’t right.');
    const problem = passwordProblem(password);
    if (problem) return fail(400, 'weak_password', problem);
    const pw = await hashSecret(password);
    const next = await updateUser(a.user.id, (u) => ({ pw, tokenVersion: (u.tokenVersion || 0) + 1 }));
    return json({ token: signToken(next) });
  },

  async 'recovery-code'(req) {
    const a = await requireUser(req);
    if (a.response) return a.response;
    const { password } = await body(req);
    if (!(await verifySecret(password, a.user.pw))) return fail(401, 'mismatch', 'Your password isn’t right.');
    const code = newRecoveryCode();
    const rc = await hashSecret(normRecovery(code));
    await updateUser(a.user.id, () => ({ rc }));
    return json({ recoveryCode: code });
  },

  async 'signout-all'(req) {
    const a = await requireUser(req);
    if (a.response) return a.response;
    await body(req);
    await updateUser(a.user.id, (u) => ({ tokenVersion: (u.tokenVersion || 0) + 1 }));
    return json({ ok: true });
  },

  async delete(req) {
    const a = await requireUser(req);
    if (a.response) return a.response;
    const { password } = await body(req);
    if (!(await verifySecret(password, a.user.pw))) return fail(401, 'mismatch', 'Your password isn’t right.');
    await deleteUser(a.user);
    return json({ ok: true });
  },
};

export default async (req, context) => {
  if (!accountsEnabled()) return fail(503, 'accounts_off', 'Accounts are not switched on for this site.');
  const action = context?.params?.action || new URL(req.url).pathname.split('/').pop();
  const fn = Object.hasOwn(actions, action) ? actions[action] : null;
  if (!fn) return fail(404, 'not_found', 'Unknown account action.');
  if (softLimit(`acct:${action}:${clientKey(req, context)}`, { limit: action === 'me' ? 120 : 20 })) {
    return fail(429, 'rate_limited', 'Too many account requests. Wait a minute.');
  }
  try {
    return await fn(req);
  } catch (err) {
    if (err.response) return err.response;
    console.error('account error', action, err?.message);
    return fail(err.status || 500, 'account_error', err.status ? err.message : 'Something went wrong. Try again.');
  }
};

export const config = {
  path: '/api/account/:action',
  rateLimit: { windowLimit: 60, windowSize: 60, aggregateBy: ['ip', 'domain'] },
};
