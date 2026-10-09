// Shared helpers for Mavis Library's server endpoints. These endpoints are
// deliberately narrow: they accept a small set of validated parameters and
// only ever contact fixed upstream hosts. They are not general proxies.

export const UA = 'MavisLibrary/0.1 (+https://github.com/; reading app; one request per user action)';

export function json(body, { status = 200, cache = 'no-store', cdn = null } = {}) {
  const headers = {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': cache,
    'x-content-type-options': 'nosniff',
  };
  if (cdn) headers['netlify-cdn-cache-control'] = cdn;
  return new Response(JSON.stringify(body), { status, headers });
}

export function fail(status, error, message) {
  return json({ error, message }, { status });
}

export function onlyGet(req) {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: { allow: 'GET' } });
  if (req.method !== 'GET' && req.method !== 'HEAD') return fail(405, 'method_not_allowed', 'Only GET is supported.');
  return null;
}

/** Fetch with a timeout and identifying user agent. */
export async function upstream(url, { timeoutMs = 12000, accept = 'application/json' } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    return await fetch(url, { headers: { 'user-agent': UA, accept }, signal: ctl.signal, redirect: 'follow' });
  } finally {
    clearTimeout(timer);
  }
}

// Soft per-instance rate limit (Netlify's platform rate limit in each
// function's config is the primary control; this is a backstop).
const hits = new Map();
export function softLimit(key, { limit = 60, windowMs = 60000 } = {}) {
  const t = Date.now();
  const arr = (hits.get(key) || []).filter((x) => t - x < windowMs);
  arr.push(t);
  hits.set(key, arr);
  if (hits.size > 5000) hits.clear();
  return arr.length > limit;
}

export function clientKey(req, context) {
  return context?.ip || req.headers.get('x-nf-client-connection-ip') || req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() || 'anon';
}

export const clean = (s, max) => String(s || '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
