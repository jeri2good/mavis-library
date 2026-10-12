import { randomBytes } from 'node:crypto';
import { kv, readForUpdate, writeIfUnchanged } from './accounts.mjs';
import { clean, env, json } from './shared.mjs';

export const DEFAULT_CAPS = Object.freeze({ assistant: 150, tts: 80, rank: 300, study: 60, podcast: 3 });
export const COOLDOWN_MS = 7 * 864e5;
export const subStore = () => kv('mavis-subs');
export const subError = (status, code, message, extra = {}) => json({ error: code, code, message, ...extra }, { status });
export const problem = (status, code, message, extra = {}) => Object.assign(new Error(message), { response: subError(status, code, message, extra) });
export const unavailable = () => subError(503, 'access_unavailable', 'Access checks are temporarily unavailable. Please try again.');
export function caps() {
  return Object.fromEntries(Object.entries(DEFAULT_CAPS).map(([k, v]) => {
    const configured = env(`SUB_CAP_${k.toUpperCase()}`);
    const n = Number(configured);
    return [k, configured != null && Number.isSafeInteger(n) && n >= 0 ? n : v];
  }));
}
export const utcDay = (now) => new Date(now).toISOString().slice(0, 10).replaceAll('-', '');
export const resetsAt = (now) => new Date(Date.UTC(new Date(now).getUTCFullYear(), new Date(now).getUTCMonth(), new Date(now).getUTCDate() + 1)).toISOString();
export const getSub = (id) => subStore().get(`s/${id}`, { type: 'json' });
export async function audit(action, userId, by, now = Date.now()) {
  const at = new Date(now).toISOString();
  await subStore().setJSON(`log/${String(now).padStart(13, '0')}-${randomBytes(12).toString('hex')}`, { at, action, userId, by }, { onlyIfNew: true });
}
export async function transition(user, action, { message = '', note = '', plan, now = Date.now() } = {}) {
  const s = subStore();
  const key = `s/${user.id}`;
  for (let i = 0; i < 40; i++) {
    const cur = await readForUpdate(s, key);
    const prev = cur.data;
    const state = prev?.state || 'none';
    if (action === 'request' && ['pending', 'active'].includes(state)) return { sub: prev, changed: false };
    if (action === 'request' && state === 'denied' && now < prev.decidedAt + COOLDOWN_MS) {
      throw problem(429, 'request_cooldown', 'Please wait seven days after a declined request before asking again.', { retryAfter: Math.ceil((prev.decidedAt + COOLDOWN_MS - now) / 1000) });
    }
    const allowed = action === 'add' || (action === 'request' && ['none', 'denied', 'revoked'].includes(state))
      || (action === 'approve' && ['pending', 'revoked'].includes(state))
      || (action === 'deny' && state === 'pending') || (action === 'revoke' && state === 'active');
    if (!allowed) throw problem(409, 'invalid_transition', 'That subscription cannot make this transition. Refresh its status.');
    const pending = action === 'request';
    const next = {
      userId: user.id, email: user.email, state: pending ? 'pending' : action === 'deny' ? 'denied' : action === 'revoke' ? 'revoked' : 'active',
      plan: plan || prev?.plan || 'member', source: pending ? 'request' : 'manual',
      requestedAt: pending ? now : prev?.requestedAt ?? null, message: pending ? clean(message, 200) : prev?.message || '',
      decidedAt: pending ? null : now, decidedBy: pending ? null : 'owner', note: pending ? '' : clean(note, 200),
      expiresAt: pending || action === 'add' || action === 'approve' ? null : prev?.expiresAt ?? null, updatedAt: now,
    };
    if (await writeIfUnchanged(s, key, next, cur)) {
      await audit(action, user.id, pending ? user.id : 'owner', now);
      return { sub: next, changed: true, created: !prev };
    }
  }
  throw problem(503, 'subscription_busy', 'Your subscription is busy. Please try again.');
}
export async function usage(id, now = Date.now()) {
  const data = await subStore().get(`use/${id}/${utcDay(now)}`, { type: 'json' }) || {};
  return Object.fromEntries(Object.entries(caps()).map(([k, cap]) => [k, { used: data[k] || 0, cap }]));
}
export async function consume(id, feature, now = Date.now()) {
  if (!Object.hasOwn(DEFAULT_CAPS, feature)) throw new Error('Unknown subscription feature');
  const s = subStore();
  const key = `use/${id}/${utcDay(now)}`;
  const cap = caps()[feature];
  for (let i = 0; i < 80; i++) {
    const cur = await readForUpdate(s, key);
    const data = cur.data || {};
    if ((data[feature] || 0) >= cap) return subError(429, 'daily_limit', 'You’ve reached today’s fair-use limit. Come back after midnight UTC.', { resetsAt: resetsAt(now) });
    if (await writeIfUnchanged(s, key, { ...data, [feature]: (data[feature] || 0) + 1 }, cur)) return null;
  }
  return unavailable();
}
export async function limitRequests(id, now = Date.now()) {
  const s = subStore();
  const key = `requests/${id}`;
  for (let i = 0; i < 40; i++) {
    const cur = await readForUpdate(s, key);
    const hits = (cur.data?.hits || []).filter((t) => now - t < 3600000);
    if (hits.length >= 5) return subError(429, 'request_limit', 'Please wait before requesting access again.', { retryAfter: Math.ceil((hits[0] + 3600000 - now) / 1000) });
    if (await writeIfUnchanged(s, key, { hits: [...hits, now] }, cur)) return null;
  }
  return unavailable();
}
export async function removeSubscription(id) {
  await subStore().delete(`s/${id}`);
  await audit('account_deleted', id, id);
}
