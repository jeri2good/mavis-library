// Narrator: plays a stream of text items (verses, paragraphs, sentences)
// with either the device's built-in voice or the owner's cloud voice.
//
// Cloud voice turns text into MP3 through /api/tts and plays it in an
// <audio> element. That is what lets reading continue with the screen off or
// while another app (maps) is in front, and lets car and Bluetooth buttons
// control playback. The device voice stops when the phone locks, so car mode
// keeps the screen awake while it plays.
//
// A source supplies items in batches:
//   source.next() → Promise<Array<{ text, onStart?, onEnd? }> | null>  (null = finished)
//   source.label  → short description for the lock screen ("John 3")

import { ttsSupported } from './tts.js';
import { ownerPost } from './features.js';
import { getSetting } from './store.js';

const CHUNK = 1400; // characters per cloud request

export class Narrator {
  constructor({ source, engine = 'device', voice = () => null, rate = () => 1, lang = 'en', title = 'Mavis Library', onState }) {
    this.source = source;
    this.engine = engine;
    this.voice = voice;
    this.rate = rate;
    this.lang = lang;
    this.title = title;
    this.onState = onState;
    this.state = 'idle';
    this.items = [];
    this.idx = 0;
    this.token = 0;
    this.sleepAt = null;
    this.sleepTimer = null;
    this.wakeLock = null;
    this.audio = null;
    this.chunks = [];
    this.chunkIdx = 0;
    this.done = false;
    this.error = null;
  }

  emit(extra = {}) {
    const cur = this.engine === 'cloud' ? this.currentCloudItem() : this.items[this.idx];
    this.onState?.({ state: this.state, engine: this.engine, text: cur?.text || '', label: this.source.label?.() || '', sleepAt: this.sleepAt, error: this.error, ...extra });
  }

  async start() {
    this.stopPlayback();
    this.error = null;
    this.state = 'loading'; this.emit();
    this.items = []; this.idx = 0; this.chunks = []; this.chunkIdx = 0; this.done = false;
    this.requestWakeLock();
    this.setupMediaSession();
    if (this.engine === 'cloud') return this.cloudStart();
    if (!ttsSupported) { this.fail('This browser has no built-in voice. Use the cloud voice instead.'); return; }
    await this.fill();
    this.state = 'playing';
    this.speakDevice();
  }

  async fill() {
    while (this.idx >= this.items.length && !this.done) {
      const batch = await this.source.next();
      if (!batch) { this.done = true; break; }
      this.items.push(...batch.filter((b) => b.text && /[\p{L}\p{N}]/u.test(b.text)));
    }
  }

  // ---------- device voice ----------
  async speakDevice() {
    const my = ++this.token;
    if (this.idx >= this.items.length) await this.fill();
    if (my !== this.token || this.state !== 'playing') return;
    const item = this.items[this.idx];
    if (!item) { this.finish(); return; }
    item.onStart?.();
    const u = new SpeechSynthesisUtterance(item.text);
    const v = this.voice();
    if (v) { u.voice = v; u.lang = v.lang; } else u.lang = this.lang;
    u.rate = this.rate();
    const next = () => {
      if (my !== this.token || this.state !== 'playing') return;
      clearTimeout(this.watchdog);
      item.onEnd?.();
      this.idx++;
      if (this.sleepAt && Date.now() >= this.sleepAt) { this.stop('sleep'); return; }
      this.emit();
      this.speakDevice();
    };
    u.onend = next;
    u.onerror = (e) => {
      if (my !== this.token || e.error === 'interrupted' || e.error === 'canceled') return;
      this.fail(e.error === 'not-allowed' ? 'The browser blocked speech. Press play again.' : `Speech stopped (${e.error}). Try another voice.`);
    };
    clearTimeout(this.watchdog);
    this.watchdog = setTimeout(next, (item.text.length / (13 * u.rate)) * 2500 + 6000);
    speechSynthesis.speak(u);
    this.emit();
  }

  // ---------- cloud voice ----------
  async nextChunk() {
    // Build the next chunk of items up to CHUNK characters.
    const items = [];
    let len = 0;
    for (;;) {
      if (this.idx >= this.items.length) await this.fill();
      const it = this.items[this.idx];
      if (!it) break;
      if (items.length && len + it.text.length > CHUNK) break;
      // Full cast: each chunk is one voice, so a change of speaker starts a new chunk.
      if (items.length && (it.voice || null) !== (items[0].voice || null)) break;
      items.push(it); len += it.text.length + 1; this.idx++;
      if (len > CHUNK) break;
    }
    if (!items.length) return null;
    const chunk = { items, voice: items[0].voice || null, text: items.map((i) => i.text).join(' '), url: null, promise: null };
    chunk.promise = this.synthesize(chunk);
    return chunk;
  }

  async synthesize(chunk) {
    const voice = chunk.voice || getSetting('narratorVoice', null)?.id || undefined;
    const blob = await ownerPost('/api/tts', { text: chunk.text, speed: this.rate(), voice }, { as: 'blob' });
    chunk.url = URL.createObjectURL(blob);
    return chunk;
  }

  async cloudStart() {
    if (!this.audio) {
      this.audio = new Audio();
      this.audio.preload = 'auto';
      this.audio.addEventListener('timeupdate', () => this.cloudProgress());
      this.audio.addEventListener('ended', () => this.cloudNext());
      this.audio.addEventListener('error', () => { if (this.state === 'playing') this.fail('The cloud voice audio could not play.'); });
    }
    const first = await this.nextChunk().catch((e) => { this.fail(e.message); return null; });
    if (!first) { if (this.state !== 'error') this.finish(); return; }
    this.chunks = [first];
    this.chunkIdx = 0;
    this.nextPromise = null;
    this.playChunk();
  }

  async playChunk() {
    const my = ++this.token;
    const chunk = this.chunks[this.chunkIdx];
    if (!chunk) { this.finish(); return; }
    this.state = 'loading'; this.emit();
    try { await chunk.promise; } catch (e) { if (my === this.token) this.fail(e.message); return; }
    if (my !== this.token) return;
    // Prefetch the following chunk while this one plays.
    if (!this.nextPromise) {
      this.nextPromise = this.nextChunk();
      this.nextPromise.catch(() => {});
    }
    this.audio.src = chunk.url;
    this.audio.playbackRate = 1;
    this.activeItem = -1;
    try {
      await this.audio.play();
      this.state = 'playing';
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
      this.emit();
    } catch {
      this.state = 'paused';
      this.emit({ error: 'Tap play to start the cloud voice.' });
    }
  }

  cloudProgress() {
    const chunk = this.chunks[this.chunkIdx];
    if (!chunk || !this.audio.duration) return;
    // Estimate which item is being spoken from its share of the characters.
    const t = this.audio.currentTime / this.audio.duration;
    let acc = 0, i = 0;
    const total = chunk.text.length || 1;
    for (; i < chunk.items.length; i++) {
      acc += (chunk.items[i].text.length + 1) / total;
      if (t < acc) break;
    }
    i = Math.min(i, chunk.items.length - 1);
    if (i !== this.activeItem) {
      if (this.activeItem >= 0) chunk.items[this.activeItem]?.onEnd?.();
      this.activeItem = i;
      chunk.items[i]?.onStart?.();
      this.emit();
    }
  }

  currentCloudItem() {
    const chunk = this.chunks[this.chunkIdx];
    return chunk?.items[Math.max(0, this.activeItem)] || null;
  }

  async cloudNext() {
    const chunk = this.chunks[this.chunkIdx];
    if (chunk) {
      chunk.items.forEach((it) => it.onEnd?.());
      if (chunk.url) setTimeout(() => URL.revokeObjectURL(chunk.url), 5000);
    }
    if (this.sleepAt && Date.now() >= this.sleepAt) { this.stop('sleep'); return; }
    const pending = this.nextPromise || this.nextChunk();
    this.nextPromise = null;
    const c = await pending.catch((e) => { this.fail(e.message); return null; });
    if (!c) { if (this.state !== 'error') this.finish(); return; }
    this.chunks[++this.chunkIdx] = c;
    this.playChunk();
  }

  // ---------- controls ----------
  pause() {
    if (this.state !== 'playing' && this.state !== 'loading') return;
    if (this.engine === 'cloud') { this.audio?.pause(); }
    else { this.token++; clearTimeout(this.watchdog); speechSynthesis.cancel(); }
    this.state = 'paused';
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    this.emit();
  }

  async resume() {
    if (this.state !== 'paused') return;
    if (this.engine === 'cloud') {
      if (this.audio?.src) {
        try { await this.audio.play(); this.state = 'playing'; this.emit(); return; } catch { /* fall through */ }
      }
      return this.playChunk();
    }
    this.state = 'playing';
    this.speakDevice(); // repeats the current item
  }

  toggle() {
    if (this.state === 'playing' || this.state === 'loading') this.pause();
    else if (this.state === 'paused') this.resume();
    else this.start();
  }

  skip(delta) {
    if (this.engine === 'cloud') {
      if (!this.audio) return;
      if (delta < 0) { this.audio.currentTime = Math.max(0, this.audio.currentTime - 15); return; }
      this.audio.currentTime = Math.min(this.audio.duration || 0, this.audio.currentTime + 15);
      return;
    }
    this.items[this.idx]?.onEnd?.();
    this.idx = Math.max(0, this.idx + delta);
    if (this.state === 'playing') { this.token++; speechSynthesis.cancel(); this.speakDevice(); }
    else this.emit();
  }

  setSleep(minutes) {
    clearTimeout(this.sleepTimer);
    this.sleepAt = minutes > 0 ? Date.now() + minutes * 60000 : null;
    if (this.sleepAt) this.sleepTimer = setTimeout(() => { if (this.state !== 'playing') this.stop('sleep'); }, minutes * 60000 + 500);
    this.emit();
  }

  stopPlayback() {
    const cur = this.engine === 'cloud' ? this.currentCloudItem() : this.items[this.idx];
    this.token++;
    clearTimeout(this.watchdog);
    if (ttsSupported) { try { speechSynthesis.cancel(); } catch { /* ignore */ } }
    if (this.audio) { this.audio.pause(); this.audio.removeAttribute('src'); try { this.audio.load(); } catch { /* ignore */ } }
    for (const c of this.chunks) if (c.url) URL.revokeObjectURL(c.url);
    this.chunks = [];
    this.nextPromise = null;
    cur?.onEnd?.();
  }

  stop(reason = 'user') {
    this.stopPlayback();
    clearTimeout(this.sleepTimer);
    this.sleepAt = null;
    this.state = 'idle';
    this.releaseWakeLock();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none';
    this.emit({ reason });
  }

  finish() { this.stop('end'); }

  fail(message) {
    this.stopPlayback();
    this.error = message;
    this.state = 'error';
    this.releaseWakeLock();
    this.emit({ error: message });
  }

  async requestWakeLock() {
    try { if ('wakeLock' in navigator && !this.wakeLock) this.wakeLock = await navigator.wakeLock.request('screen'); } catch { /* optional */ }
  }
  releaseWakeLock() { try { this.wakeLock?.release(); } catch { /* ignore */ } this.wakeLock = null; }

  setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: this.source.label?.() || this.title, artist: this.title, album: 'Mavis Library', artwork: [{ src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' }] });
      navigator.mediaSession.setActionHandler('play', () => this.resume());
      navigator.mediaSession.setActionHandler('pause', () => this.pause());
      navigator.mediaSession.setActionHandler('stop', () => this.stop());
      navigator.mediaSession.setActionHandler('nexttrack', () => this.skip(1));
      navigator.mediaSession.setActionHandler('previoustrack', () => this.skip(-1));
      navigator.mediaSession.setActionHandler('seekforward', () => this.skip(1));
      navigator.mediaSession.setActionHandler('seekbackward', () => this.skip(-1));
    } catch { /* optional */ }
  }

  destroy() { this.stop('close'); this.audio = null; }
}

/** Split a paragraph into speakable sentences of reasonable length. */
export function sentences(text, lang = 'en') {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  if (!t) return [];
  let parts;
  try { parts = [...new Intl.Segmenter(lang, { granularity: 'sentence' }).segment(t)].map((s) => s.segment.trim()); }
  catch { parts = t.match(/[^.!?]+[.!?]+["'”’)\]]*|[^.!?]+$/g) || [t]; }
  const out = [];
  for (const p of parts) {
    if (p.length <= 320) { if (p) out.push(p); continue; }
    let rest = p;
    while (rest.length > 320) {
      let cut = rest.lastIndexOf(', ', 300);
      if (cut < 80) cut = rest.lastIndexOf(' ', 300);
      out.push(rest.slice(0, cut + 1).trim());
      rest = rest.slice(cut + 1).trim();
    }
    if (rest) out.push(rest);
  }
  return out;
}
