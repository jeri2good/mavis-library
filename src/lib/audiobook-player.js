// Plays a downloaded audiobook from this device: chunk after chunk, chapter
// after chapter, with lock-screen and car controls (Media Session), speed,
// a sleep timer, and a saved position. If a part isn't saved yet and the
// phone is online, it's fetched from the cloud voice (and kept).

import * as ab from './audiobook.js';
import * as idb from './idb.js';
import * as store from './store.js';
import { ownerPost } from './features.js';

const SEEK = 30;

export class AudiobookPlayer {
  constructor({ key, manifest, title, artwork, onState }) {
    this.key = key;
    this.m = manifest;
    this.title = title || manifest.title || 'Audiobook';
    this.artwork = artwork;
    this.onState = onState;
    this.pos = { ch: 0, n: 0, t: 0, ...(manifest.position || {}) };
    this.state = 'idle';
    this.rate = Number(store.getSetting('listenRate', 1)) || 1;
    this.sleepAt = null;
    this.sleepEndOfChapter = false;
    this.urls = new Map();
    this.token = 0;
    this.audio = new Audio();
    this.audio.preload = 'auto';
    this.audio.addEventListener('ended', () => this.advance());
    this.audio.addEventListener('timeupdate', () => this.tick());
    this.audio.addEventListener('pause', () => { if (this.state === 'playing' && !this.audio.ended && !this.switching) { this.state = 'paused'; this.emit(); this.persist(); } });
    this.audio.addEventListener('play', () => { if (this.state === 'paused') { this.state = 'playing'; this.emit(); } });
    this.audio.addEventListener('error', () => { if (this.state === 'playing' || this.state === 'loading') this.fail('This part of the audio couldn’t play.'); });
    this.lastSave = 0;
    this.validate();
  }

  validate() {
    const ch = this.m.chapters[this.pos.ch];
    if (!ch) this.pos = { ch: 0, n: 0, t: 0 };
    else if (!ch.chunks[this.pos.n]) this.pos = { ch: this.pos.ch, n: 0, t: 0 };
  }

  get chapter() { return this.m.chapters[this.pos.ch]; }
  get chunk() { return this.chapter?.chunks[this.pos.n]; }

  emit(extra = {}) {
    const c = this.chunk;
    const frac = this.audio.duration ? this.audio.currentTime / this.audio.duration : 0;
    this.onState?.({
      state: this.state, chapter: this.chapter?.title || '', chapterIndex: this.pos.ch, chapters: this.m.chapters.length,
      line: c ? (c.text ? sentenceAt(c.text, frac) : c.reader ? `Read by ${c.reader}` : '') : '', progress: this.progress(), rate: this.rate, sleepAt: this.sleepAt,
      sleepEndOfChapter: this.sleepEndOfChapter, ...extra,
    });
  }

  progress() {
    let total = 0, before = 0;
    this.m.chapters.forEach((c, i) => c.chunks.forEach((k, n) => {
      total += k.chars;
      if (i < this.pos.ch || (i === this.pos.ch && n < this.pos.n)) before += k.chars;
    }));
    const c = this.chunk;
    const within = c && this.audio.duration ? (this.audio.currentTime / this.audio.duration) * c.chars : 0;
    return { fraction: total ? (before + within) / total : 0, remainingChars: Math.max(0, total - before - within) };
  }

  async blobFor(ch, n) {
    const cached = await ab.chunkBlob(this.key, ch, n);
    if (cached) return cached;
    const k = this.m.chapters[ch]?.chunks[n];
    if (!k) return null;
    if (!navigator.onLine) throw new Error('This part isn’t saved on the phone and you’re offline. Save the rest of the book when you have signal.');
    const blob = await ownerPost('/api/tts', { text: k.text, speed: 1, voice: this.m.voiceId || undefined }, { as: 'blob' });
    try {
      await idb.put('audio', { id: `${store.getOwner()}|${this.key}|${ch}|${n}`, blob });
      k.done = true;
      this.m.bytes = (this.m.bytes || 0) + blob.size;
      await idb.put('audiobooks', { ...this.m, id: `${store.getOwner()}|${this.key}`, owner: store.getOwner() });
    } catch { /* playing still works */ }
    return blob;
  }

  async urlFor(ch, n) {
    const id = `${ch}|${n}`;
    if (this.urls.has(id)) return this.urls.get(id);
    const k = this.m.chapters[ch]?.chunks[n];
    const p = (async () => {
      const cached = await ab.chunkBlob(this.key, ch, n);
      if (cached) return URL.createObjectURL(cached);
      if (k?.url) {
        // A recorded audiobook (LibriVox): stream it from archive.org.
        if (!navigator.onLine) throw new Error('This part isn’t saved on the phone and you’re offline. Save the recording for offline when you have signal.');
        return k.url;
      }
      const b = await this.blobFor(ch, n);
      return b ? URL.createObjectURL(b) : null;
    })();
    this.urls.set(id, p);
    p.catch(() => this.urls.delete(id));
    return p;
  }

  nextOf(ch, n) {
    const c = this.m.chapters[ch];
    if (c && n + 1 < c.chunks.length) return { ch, n: n + 1 };
    if (ch + 1 < this.m.chapters.length) return { ch: ch + 1, n: 0 };
    return null;
  }

  async load({ autoplay = true, seek = this.pos.t } = {}) {
    const my = ++this.token;
    this.state = 'loading'; this.emit();
    this.setupMediaSession();
    let url;
    try { url = await this.urlFor(this.pos.ch, this.pos.n); } catch (err) { if (my === this.token) this.fail(err.message); return; }
    if (my !== this.token) return;
    if (!url) { this.finish(); return; }
    this.switching = true;
    this.audio.src = url;
    this.audio.playbackRate = this.rate;
    this.audio.preservesPitch = true;
    await new Promise((r) => { if (this.audio.readyState >= 1) r(); else this.audio.addEventListener('loadedmetadata', r, { once: true }); setTimeout(r, 3000); });
    if (my !== this.token) return;
    if (seek > 0 && this.audio.duration) this.audio.currentTime = Math.min(seek, this.audio.duration - 0.25);
    this.switching = false;
    this.updateMetadata();
    this.syncReadingPosition();
    // Prepare the next part while this one plays.
    const nx = this.nextOf(this.pos.ch, this.pos.n);
    if (nx) this.urlFor(nx.ch, nx.n).catch(() => {});
    if (!autoplay) { this.state = 'paused'; this.emit(); return; }
    try {
      await this.audio.play();
      this.state = 'playing';
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
      this.requestWakeLock();
    } catch {
      this.state = 'paused';
      this.emit({ error: 'Tap play to start.' });
      return;
    }
    this.emit();
  }

  async advance() {
    if (this.sleepAt && Date.now() >= this.sleepAt) { this.sleepStop(); return; }
    const old = `${this.pos.ch}|${this.pos.n}`;
    const nx = this.nextOf(this.pos.ch, this.pos.n);
    if (!nx) { this.pos.t = 0; this.finish(); return; }
    if (this.sleepEndOfChapter && nx.ch !== this.pos.ch) { this.pos = { ...nx, t: 0 }; this.persist(true); this.sleepStop(); return; }
    this.pos = { ...nx, t: 0 };
    this.release(old);
    await this.load({ seek: 0 });
    this.persist(true);
  }

  release(id) {
    const p = this.urls.get(id);
    this.urls.delete(id);
    p?.then((u) => u?.startsWith('blob:') && setTimeout(() => URL.revokeObjectURL(u), 4000)).catch(() => {});
  }

  tick() {
    this.pos.t = this.audio.currentTime || 0;
    this.emit();
    if (Date.now() - this.lastSave > 5000) this.persist();
    if ('mediaSession' in navigator && this.audio.duration && navigator.mediaSession.setPositionState) {
      try { navigator.mediaSession.setPositionState({ duration: this.audio.duration, playbackRate: this.rate, position: Math.min(this.audio.currentTime, this.audio.duration) }); } catch { /* ignore */ }
    }
  }

  persist(force = false) {
    if (!force && Date.now() - this.lastSave < 1000) return;
    this.lastSave = Date.now();
    ab.savePosition(this.key, { ch: this.pos.ch, n: this.pos.n, t: this.pos.t }).catch(() => {});
  }

  syncReadingPosition() {
    const c = this.chunk;
    if (!c?.cfi) return;
    store.setProgress(this.key, { cfi: c.cfi, percent: this.progress().fraction, chapter: this.chapter?.title || '' }).catch(() => {});
  }

  // ---------- controls ----------
  play() {
    if (this.state === 'playing' || this.state === 'loading') return;
    if (this.state === 'paused' && this.audio.src) {
      this.audio.play().then(() => { this.state = 'playing'; this.requestWakeLock(); if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'; this.emit(); })
        .catch(() => this.load());
      return;
    }
    this.load();
  }

  pause() {
    if (this.state !== 'playing' && this.state !== 'loading') return;
    this.token++;
    this.audio.pause();
    this.state = 'paused';
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    this.releaseWakeLock();
    this.persist(true);
    this.emit();
  }

  toggle() { if (this.state === 'playing' || this.state === 'loading') this.pause(); else this.play(); }

  async seekBy(sec) {
    const t = (this.audio.currentTime || 0) + sec;
    if (t >= 0 && (!this.audio.duration || t < this.audio.duration)) { this.audio.currentTime = t; this.pos.t = t; this.emit(); return; }
    const wasPlaying = this.state === 'playing';
    if (t < 0) {
      if (this.pos.n > 0) this.pos = { ch: this.pos.ch, n: this.pos.n - 1, t: Number.MAX_SAFE_INTEGER };
      else if (this.pos.ch > 0) this.pos = { ch: this.pos.ch - 1, n: this.m.chapters[this.pos.ch - 1].chunks.length - 1, t: Number.MAX_SAFE_INTEGER };
      else { this.audio.currentTime = 0; return; }
      await this.load({ autoplay: wasPlaying, seek: 1e9 });
      if (this.audio.duration) this.audio.currentTime = Math.max(0, this.audio.duration + t);
    } else {
      const nx = this.nextOf(this.pos.ch, this.pos.n);
      if (!nx) return;
      this.pos = { ...nx, t: 0 };
      await this.load({ autoplay: wasPlaying, seek: Math.max(0, t - (this.audio.duration || 0)) });
    }
    this.persist(true);
  }

  async goToChapter(ch) {
    if (!this.m.chapters[ch]) return;
    const wasPlaying = this.state === 'playing' || this.state === 'loading' || this.state === 'idle';
    this.pos = { ch, n: 0, t: 0 };
    await this.load({ autoplay: wasPlaying, seek: 0 });
    this.persist(true);
  }

  prevChapter() {
    // Like a CD player: restart this chapter unless we're near its start.
    if (this.pos.n > 0 || (this.audio.currentTime || 0) > 5) return this.goToChapter(this.pos.ch);
    return this.goToChapter(Math.max(0, this.pos.ch - 1));
  }
  nextChapter() { return this.goToChapter(Math.min(this.m.chapters.length - 1, this.pos.ch + 1)); }

  setRate(r) {
    this.rate = Math.min(2.5, Math.max(0.6, Number(r) || 1));
    this.audio.playbackRate = this.rate;
    store.setSetting('listenRate', this.rate);
    this.emit();
  }

  setSleep(v) {
    clearTimeout(this.sleepTimer);
    this.sleepEndOfChapter = v === 'chapter';
    const min = Number(v);
    this.sleepAt = min > 0 ? Date.now() + min * 60000 : null;
    if (this.sleepAt) this.sleepTimer = setTimeout(() => this.sleepStop(), min * 60000);
    this.emit();
  }

  sleepStop() {
    this.sleepAt = null;
    this.sleepEndOfChapter = false;
    this.pause();
    this.emit({ reason: 'sleep' });
  }

  finish() {
    this.token++;
    this.state = 'ended';
    this.releaseWakeLock();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none';
    this.persist(true);
    this.emit();
  }

  fail(message) {
    this.state = 'error';
    this.audio.pause();
    this.releaseWakeLock();
    this.emit({ error: message });
  }

  async requestWakeLock() {
    // Screen-off playback works with <audio>; the lock only helps the screen
    // stay readable while it's in a dash mount.
    if (store.getSetting('listenKeepAwake', false) !== true) return;
    try { if ('wakeLock' in navigator && !this.wake) this.wake = await navigator.wakeLock.request('screen'); } catch { /* optional */ }
  }
  releaseWakeLock() { try { this.wake?.release(); } catch { /* ignore */ } this.wake = null; }

  setupMediaSession() {
    if (!('mediaSession' in navigator) || this.msReady) return;
    this.msReady = true;
    const ms = navigator.mediaSession;
    const set = (a, fn) => { try { ms.setActionHandler(a, fn); } catch { /* unsupported action */ } };
    set('play', () => this.play());
    set('pause', () => this.pause());
    set('stop', () => this.pause());
    set('seekbackward', (d) => this.seekBy(-(d?.seekOffset || SEEK)));
    set('seekforward', (d) => this.seekBy(d?.seekOffset || SEEK));
    set('previoustrack', () => this.prevChapter());
    set('nexttrack', () => this.nextChapter());
    set('seekto', (d) => { if (d?.seekTime != null && this.audio.duration) this.audio.currentTime = Math.min(d.seekTime, this.audio.duration - 0.25); });
  }

  updateMetadata() {
    if (!('mediaSession' in navigator)) return;
    try {
      const art = [{ src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' }];
      if (this.artwork) art.unshift({ src: this.artwork, sizes: '400x600', type: 'image/jpeg' });
      navigator.mediaSession.metadata = new MediaMetadata({ title: this.chapter?.title || this.title, artist: this.title, album: 'Mavis Library', artwork: art });
    } catch { /* optional */ }
  }

  destroy() {
    this.persist(true);
    this.token++;
    this.audio.pause();
    this.audio.removeAttribute('src');
    try { this.audio.load(); } catch { /* ignore */ }
    for (const p of this.urls.values()) p.then((u) => u?.startsWith('blob:') && URL.revokeObjectURL(u)).catch(() => {});
    this.urls.clear();
    clearTimeout(this.sleepTimer);
    this.releaseWakeLock();
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = 'none';
      for (const a of ['play', 'pause', 'stop', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack', 'seekto']) { try { navigator.mediaSession.setActionHandler(a, null); } catch { /* ignore */ } }
    }
  }
}

/** The sentence being spoken, estimated from how far through the chunk we are. */
export function sentenceAt(text, frac) {
  const parts = String(text || '').match(/[^.!?]+[.!?]+["'”’)\]]*\s*|[^.!?]+$/g) || [text];
  const total = text.length || 1;
  let acc = 0;
  for (const p of parts) { acc += p.length; if (acc / total >= frac) return p.trim(); }
  return parts[parts.length - 1]?.trim() || '';
}
