// Optional services the site owner has switched on (cloud voice, AI
// assistant, Jev ranking), plus the owner access code stored on this device.

import { getSetting, setSetting } from './store.js';

let cache = null;
let inflight = null;
const listeners = new Set();
export function onFeatures(fn) { listeners.add(fn); return () => listeners.delete(fn); }

export function accessCode() { return getSetting('accessCode', '') || ''; }

export async function loadFeatures({ force = false } = {}) {
  if (cache && !force) return cache;
  if (inflight && !force) return inflight;
  inflight = (async () => {
    const headers = { accept: 'application/json' };
    const code = accessCode();
    if (code) headers['x-mavis-access'] = code;
    try {
      const r = await fetch('/api/features', { headers, cache: 'no-store' });
      const j = r.ok ? await r.json() : {};
      cache = { ...EMPTY, ...j, reachable: r.ok };
    } catch {
      cache = { ...EMPTY, reachable: false };
    }
    inflight = null;
    for (const fn of listeners) fn(cache);
    return cache;
  })();
  return inflight;
}

const EMPTY = { owner: false, accessCode: false, cloudVoice: null, assistant: null, jev: false };

/** Paid features are usable when configured on the server AND this device holds the right code. */
export function can(name) {
  if (!cache || !cache.owner) return false;
  if (name === 'cloudVoice') return !!cache.cloudVoice;
  if (name === 'assistant') return !!cache.assistant;
  if (name === 'jev') return !!cache.jev;
  return false;
}
export function features() { return cache || EMPTY; }

export async function setAccessCode(code) {
  await setSetting('accessCode', String(code || '').trim());
  return loadFeatures({ force: true });
}

/** POST JSON to a paid endpoint with the access code. */
export async function ownerPost(path, body, { signal, as = 'json' } = {}) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-mavis-access': accessCode() },
    body: JSON.stringify(body),
    signal,
  });
  if (!r.ok) {
    let msg = `The server answered ${r.status}.`;
    try { const j = await r.json(); if (j.message) msg = j.message; } catch { /* ignore */ }
    throw Object.assign(new Error(msg), { status: r.status });
  }
  return as === 'blob' ? r.blob() : r.json();
}
