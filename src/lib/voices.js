// Cloud voices (Fish Audio): the narrator voice, the owner's own cloned voice,
// and the pool of voices used to cast characters in full-cast narration.

import { getSetting, setSetting } from './store.js';
import { accessCode } from './features.js';

async function call(path, init = {}) {
  let r;
  try { r = await fetch(path, { ...init, headers: { 'x-mavis-access': accessCode(), ...(init.headers || {}) }, cache: 'no-store' }); }
  catch { throw new Error('Couldn’t reach Mavis. Check your connection.'); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.message || `The voice service answered ${r.status}.`);
  return j;
}

export function libraryVoices({ gender = '', age = '', q = '', page = 1 } = {}) {
  const p = new URLSearchParams({ list: 'library', page: String(page) });
  if (gender) p.set('gender', gender);
  if (age) p.set('age', age);
  if (q) p.set('q', q);
  return call(`/api/voices?${p}`);
}

export function myVoices() { return call('/api/voices?list=mine'); }

export async function cloneMyVoice({ blob, title, text }) {
  const fd = new FormData();
  fd.append('consent', 'yes');
  fd.append('title', title || 'My voice');
  if (text) fd.append('text', text);
  const ext = /mp4|m4a|aac/.test(blob.type) ? 'm4a' : /mpeg|mp3/.test(blob.type) ? 'mp3' : /wav/.test(blob.type) ? 'wav' : /ogg/.test(blob.type) ? 'ogg' : 'webm';
  fd.append('audio', blob, `my-voice.${ext}`);
  const { voice } = await call('/api/voices', { method: 'POST', body: fd });
  return voice;
}

export function deleteVoice(id) { return call(`/api/voices?id=${encodeURIComponent(id)}`, { method: 'DELETE' }); }

/** The narrator voice chosen on this device ({ id, title } or null = the site's default voice). */
export const narratorVoice = () => getSetting('narratorVoice', null);
export const setNarratorVoice = (v) => setSetting('narratorVoice', v ? { id: v.id, title: v.title } : null);

// ---------- casting pool ----------
let poolP = null;
/** Licensed voices grouped for casting: { male: [...], female: [...] } (cached for the session). */
export function castingPool() {
  if (!poolP) {
    poolP = Promise.all([libraryVoices({ gender: 'male' }), libraryVoices({ gender: 'female' }), libraryVoices({ gender: 'male', page: 2 }).catch(() => ({ items: [] }))])
      .then(([m, f, m2]) => ({ male: [...m.items, ...m2.items], female: f.items }));
    poolP.catch(() => { poolP = null; });
  }
  return poolP;
}

const hash = (s) => [...String(s)].reduce((h, c) => (Math.imul(h, 31) + c.charCodeAt(0)) | 0, 7) >>> 0;
const ageTag = { child: 'young', young: 'young', adult: 'middle-aged', old: 'old' };

/**
 * Give each speaker a voice: keep existing choices, prefer a matching gender
 * and age, avoid the narrator's voice, and spread voices so the main
 * characters sound different from each other.
 */
export async function assignVoices(speakers, current = {}) {
  const pool = await castingPool();
  const narrator = narratorVoice()?.id;
  const cast = { ...current };
  const used = new Set(Object.values(cast).map((c) => c.id));
  for (const [name, info] of Object.entries(speakers)) {
    if (name === 'Narrator' || cast[name]) continue;
    const list = (info.gender === 'female' ? pool.female : info.gender === 'male' ? pool.male : [...pool.male, ...pool.female]).filter((v) => v.id !== narrator);
    if (!list.length) continue;
    const want = ageTag[info.age];
    const fits = list.filter((v) => !want || v.tags?.includes(want));
    const options = (fits.length ? fits : list);
    const fresh = options.filter((v) => !used.has(v.id));
    const pickFrom = fresh.length ? fresh : options;
    const v = pickFrom[hash(name) % pickFrom.length];
    cast[name] = { id: v.id, title: v.title, gender: info.gender, sample: v.sample };
    used.add(v.id);
  }
  return cast;
}
