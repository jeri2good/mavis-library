// CORS for the Android app. The bundled app runs at https://localhost and
// calls these functions across origins. Only two origins are ever allowed,
// and never with a wildcard. Netlify [[headers]] rules don't apply to
// function responses, so this lives in code.

export const ALLOWED_ORIGINS = new Set(['https://localhost', 'https://mavis-library.netlify.app']);
const METHODS = 'GET, POST, OPTIONS';
const HEADERS = 'authorization, content-type, x-mavis-access, x-mavis-kids, x-mavis-source, x-mavis-voice';
// The client reads content-length on /api/epub for download progress.
const EXPOSE = 'content-length';

export const isAllowedOrigin = (origin) => ALLOWED_ORIGINS.has(origin);

function corsHeaders(origin, methods) {
  return {
    'access-control-allow-origin': origin,
    'access-control-allow-methods': methods,
    'access-control-allow-headers': HEADERS,
    'access-control-expose-headers': EXPOSE,
    'access-control-max-age': '600',
    vary: 'Origin',
  };
}

/**
 * Wrap a function handler with CORS. Requests without an Origin header or
 * with a refused one reach the handler unchanged and get no CORS headers;
 * a preflight from a refused origin is answered 403.
 */
export function withCors(handler, { methods = METHODS } = {}) {
  return async (req, context) => {
    const origin = req.headers.get('origin');
    const allowed = !!origin && isAllowedOrigin(origin);
    if (req.method === 'OPTIONS' && origin) {
      if (!allowed) return new Response(null, { status: 403, headers: { vary: 'Origin' } });
      return new Response(null, { status: 204, headers: corsHeaders(origin, methods) });
    }
    const res = await handler(req, context);
    if (!res) return res;
    // CDN-cached answers must vary by Origin, or a copy cached for the web
    // (no CORS headers) would be served to the app and the other way round.
    const cdnCached = res.headers.has('netlify-cdn-cache-control');
    if (!allowed && !cdnCached) return res;
    const headers = new Headers(res.headers);
    if (allowed) for (const [k, v] of Object.entries(corsHeaders(origin, methods))) if (k !== 'vary') headers.set(k, v);
    if (!/\borigin\b/i.test(headers.get('vary') || '')) headers.append('vary', 'Origin');
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
  };
}
