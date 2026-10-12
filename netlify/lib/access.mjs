import { requireOwner } from './shared.mjs';
import { readToken, getUser } from './accounts.mjs';
import { getSub, consume, subError, unavailable } from './subs.mjs';

export async function sessionAccess(req) {
  try {
    const m = /^Bearer\s+(\S+)$/.exec(req.headers.get('authorization') || '');
    const token = m && readToken(m[1]);
    if (!token) return subError(401, 'sign_in_required', 'Sign in to use this feature.');
    const user = await getUser(token.u);
    if (!user || (user.tokenVersion || 0) !== token.v) return subError(401, 'sign_in_required', 'Your session has ended. Please sign in again.');
    return { user };
  } catch {
    return unavailable();
  }
}
export async function requireAccess(req, { feature } = {}) {
  if (!(await requireOwner(req))) return { owner: true, feature };
  const ctx = await sessionAccess(req);
  if (ctx instanceof Response) return ctx;
  try {
    const sub = await getSub(ctx.user.id);
    const expired = sub?.expiresAt != null && (!Number.isFinite(sub.expiresAt) || sub.expiresAt <= Date.now());
    if (sub?.state === 'active' && !expired) return { ...ctx, sub, feature, owner: false };
    const state = ['pending', 'denied', 'revoked', 'active'].includes(sub?.state) ? sub.state : 'none';
    return subError(403, 'subscription_required', state === 'pending' ? 'Your request is waiting for approval.' : expired ? 'Your subscription has expired. Ask the owner for access.' : 'Ask the owner for access.', { state });
  } catch {
    return unavailable();
  }
}
export async function chargeAccess(ctx, feature = ctx.feature) {
  if (ctx.owner) return null;
  try { return await consume(ctx.user.id, feature); } catch { return unavailable(); }
}
