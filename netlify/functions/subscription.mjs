import { randomBytes } from 'node:crypto';
import { json, fail, onlyPost, readJson, requireOwner, sameOrigin, softLimit, clientKey, env } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';
import { createUser, validEmail, getUser, findByEmail } from '../lib/accounts.mjs';
import { sessionAccess } from '../lib/access.mjs';
import { subStore, getSub, transition, usage, limitRequests, unavailable } from '../lib/subs.mjs';

async function body(req) {
  const bad = onlyPost(req);
  if (bad) throw { response: bad };
  const b = await readJson(req, 4000);
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw { response: fail(400, 'bad_request', 'Send a JSON object.') };
  for (const k of ['email', 'userId', 'message', 'note']) if (b[k] != null && typeof b[k] !== 'string') throw { response: fail(400, 'bad_request', 'Use text fields.') };
  if (b.plan != null && !['family', 'member'].includes(b.plan)) throw { response: fail(400, 'bad_plan', 'Choose family or member.') };
  if (b.message?.length > 200 || b.note?.length > 200) throw { response: fail(400, 'bad_request', 'Keep messages and notes under 200 characters.') };
  return b;
}
const handler = async (req, context) => {
  if (!sameOrigin(req)) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
  if (softLimit(`subs:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many subscription requests. Wait a minute.');
  const action = new URL(req.url).pathname.replace(/^\/api\/subscription\//, '');
  try {
    if (action.startsWith('admin/')) {
      const bad = await requireOwner(req);
      if (bad) return bad;
      const admin = action.slice(6);
      if (admin === 'list') {
        if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
        const sp = new URL(req.url).searchParams;
        const state = sp.get('state') || 'pending';
        if (!['pending', 'active', 'all'].includes(state)) return fail(400, 'bad_state', 'Choose pending, active, or all.');
        const limit = Math.min(200, Math.max(1, Number(sp.get('limit')) || 200));
        const cursor = sp.get('cursor') || '';
        if (cursor && !/^s\/[0-9a-f-]{36}$/.test(cursor)) return fail(400, 'bad_cursor', 'Invalid cursor.');
        const s = subStore();
        const items = [];
        let last = '', more = false;
        outer: for await (const page of s.list({ prefix: 's/', paginate: true })) {
          for (const b of page.blobs) {
            if (b.key <= cursor) continue;
            const x = await s.get(b.key, { type: 'json' });
            if (!x || (state !== 'all' && x.state !== state)) continue;
            if (items.length >= Math.floor(limit)) { more = true; break outer; }
            items.push({ userId: x.userId, email: x.email, state: x.state, plan: x.plan, requestedAt: x.requestedAt, message: x.message });
            last = b.key;
          }
        }
        return json({ items, cursor: more ? last : null });
      }
      if (!['approve', 'deny', 'revoke', 'add', 'create-account'].includes(admin)) return fail(404, 'not_found', 'Unknown subscription action.');
      const b = await body(req);
      if (['add', 'create-account'].includes(admin) && !validEmail(b.email)) return fail(400, 'bad_email', 'Enter a valid email address.');
      if (admin === 'create-account') {
        const tempPassword = randomBytes(24).toString('base64url');
        const out = await createUser(b.email, tempPassword);
        if (out.exists) return fail(409, 'exists', 'That account already exists. Use admin/add after verifying it with the owner.');
        await transition(out.user, 'add', { plan: b.plan });
        return json({ email: out.user.email, tempPassword, recoveryCode: out.recoveryCode }, { status: 201 });
      }
      const user = b.userId ? await getUser(b.userId) : validEmail(b.email) ? await findByEmail(b.email) : null;
      if (!user) return fail(404, 'account_not_found', 'No existing account was found. Ask the person to sign up and verify them, or use admin/create-account.');
      const out = await transition(user, admin, { note: b.note, plan: b.plan });
      return json({ state: out.sub.state, userId: user.id, plan: out.sub.plan });
    }
    if (!['status', 'request'].includes(action)) return fail(404, 'not_found', 'Unknown subscription action.');
    const ctx = await sessionAccess(req);
    if (ctx instanceof Response) return ctx;
    if (action === 'status') {
      if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
      const sub = await getSub(ctx.user.id);
      return json({ state: sub?.state || 'none', plan: sub?.plan || null, signedIn: true, podcastEnabled: Boolean(env('STUDIO_URL')), since: sub?.decidedAt || null, expiresAt: sub?.expiresAt ?? null, usage: await usage(ctx.user.id) });
    }
    const b = await body(req);
    const limited = await limitRequests(ctx.user.id);
    if (limited) return limited;
    const out = await transition(ctx.user, 'request', { message: b.message });
    return json({ state: out.sub.state, plan: out.sub.plan, requestedAt: out.sub.requestedAt }, { status: out.created ? 201 : 200 });
  } catch (err) {
    if (err.response) return err.response;
    if (err.status === 400 || err.status === 413) return fail(err.status, 'bad_request', err.message);
    return unavailable();
  }
};
export default withCors(handler);
export const config = { path: '/api/subscription/*', rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] } };
