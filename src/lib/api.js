// Where the Netlify functions live. On the web every call stays relative
// (same origin). The Android build sets VITE_API_BASE so the bundled app,
// served from https://localhost, reaches the public site's functions.

const BASE = String(import.meta.env.VITE_API_BASE || '').replace(/\/+$/, '');

/** Prefix a relative `/api/...` path with VITE_API_BASE when set; anything else is returned unchanged. */
export function apiUrl(path) {
  if (!BASE || typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) return path;
  return BASE + path;
}

/** The public site. Links people share (invites) always point here, in the web app and the Android app. */
export const PUBLIC_ORIGIN = 'https://mavis-library.netlify.app';
