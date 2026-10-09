// Shared helpers for Mavis Library's server endpoints. These endpoints are
// deliberately narrow: they accept a small set of validated parameters and
// only ever contact fixed upstream hosts. They are not general proxies.

export const UA = 'MavisLibrary/0.2 (+https://mavis-library.netlify.app; reading app; one request per user action)';

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

/** Read an environment variable on Netlify (Netlify.env) or locally (process.env). */
export function env(name) {
  try {
    const v = globalThis.Netlify?.env?.get(name);
    if (v != null && v !== '') return v;
  } catch { /* not on Netlify */ }
  const v = typeof process !== 'undefined' ? process.env[name] : undefined;
  return v === '' ? undefined : v;
}

/**
 * Paid features (cloud voice, AI assistant, Jev ranking) spend the site
 * owner's credits, so they only answer requests that carry the owner's access
 * code (MAVIS_ACCESS_CODE). Without that variable set, they stay off.
 */
export async function requireOwner(req) {
  const code = env('MAVIS_ACCESS_CODE');
  if (!code) return fail(403, 'not_configured', 'This feature needs an access code set by the site owner (MAVIS_ACCESS_CODE).');
  const given = req.headers.get('x-mavis-access') || '';
  const { timingSafeEqual, createHash } = await import('node:crypto');
  const a = createHash('sha256').update(given).digest();
  const b = createHash('sha256').update(code).digest();
  if (!given || !timingSafeEqual(a, b)) return fail(401, 'bad_access_code', 'The access code is missing or wrong. Enter it in Settings.');
  return null;
}

export function onlyPost(req) {
  if (req.method !== 'POST') return fail(405, 'method_not_allowed', 'Only POST is supported.');
  const ct = req.headers.get('content-type') || '';
  if (!ct.includes('application/json')) return fail(415, 'unsupported_media_type', 'Send JSON.');
  // Same-origin check for browser requests (defense against cross-site use).
  const origin = req.headers.get('origin');
  if (origin) {
    try {
      if (new URL(origin).host !== new URL(req.url).host) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
    } catch { return fail(403, 'cross_origin', 'Cross-site requests are not allowed.'); }
  }
  return null;
}

export async function readJson(req, maxBytes = 200_000) {
  const text = await req.text();
  if (text.length > maxBytes) throw Object.assign(new Error('Request is too large.'), { status: 413 });
  try { return JSON.parse(text); } catch { throw Object.assign(new Error('Request body is not valid JSON.'), { status: 400 }); }
}
