// Read-aloud for the EPUB reader using the browser's own speech synthesis.
//
// It reads exactly what is on screen, sentence by sentence, highlighting the
// sentence being spoken, then turns the page itself and continues. It never
// reads hidden or off-screen chapter text. If the reader turns the page by
// hand while listening, reading restarts from the new page.
//
// "Pause" is implemented as stop-and-remember, because pause()/resume() are
// unreliable on Android Chrome; resuming repeats the current sentence.

export const ttsSupported = 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window;

export function listVoices() {
  if (!ttsSupported) return [];
  return speechSynthesis.getVoices();
}

export function whenVoicesReady(timeout = 2500) {
  return new Promise((resolve) => {
    if (!ttsSupported) return resolve([]);
    const v = speechSynthesis.getVoices();
    if (v.length) return resolve(v);
    const done = () => { speechSynthesis.removeEventListener?.('voiceschanged', done); resolve(speechSynthesis.getVoices()); };
    speechSynthesis.addEventListener?.('voiceschanged', done);
    setTimeout(done, timeout);
  });
}

const segmenterFor = (lang) => {
  try { return new Intl.Segmenter(lang || 'en', { granularity: 'sentence' }); } catch { return null; }
};

const BLOCK = /^(P|DIV|H[1-6]|LI|BLOCKQUOTE|PRE|TD|TH|DT|DD|FIGCAPTION|SECTION|ARTICLE|ASIDE|HEADER|FOOTER|TR|BR)$/;

function blockOf(node) {
  let el = node.parentElement;
  while (el && !BLOCK.test(el.tagName)) el = el.parentElement;
  return el;
}

/** Collect sentence ranges for the text inside `range`. */
export function sentencesInRange(doc, range, lang) {
  const root = range.commonAncestorContainer.nodeType === 1 ? range.commonAncestorContainer : range.commonAncestorContainer.parentNode;
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(n) {
      if (!n.nodeValue || !n.nodeValue.trim()) return NodeFilter.FILTER_SKIP;
      const p = n.parentElement;
      if (p && /^(SCRIPT|STYLE|NOSCRIPT|RT|RP)$/.test(p.tagName)) return NodeFilter.FILTER_REJECT;
      return range.intersectsNode(n) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
    },
  });
  const pieces = []; // { node, start, end, gStart }
  let text = '';
  let lastBlock = null;
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    let start = 0, end = n.nodeValue.length;
    if (n === range.startContainer) start = range.startOffset;
    if (n === range.endContainer) end = range.endOffset;
    if (end <= start) continue;
    const blk = blockOf(n);
    if (lastBlock && blk !== lastBlock) text += '\n';
    lastBlock = blk;
    pieces.push({ node: n, start, end, gStart: text.length });
    text += n.nodeValue.slice(start, end);
  }
  if (!pieces.length) return [];

  const bounds = [];
  const seg = segmenterFor(lang);
  if (seg) {
    for (const s of seg.segment(text)) bounds.push([s.index, s.index + s.segment.length]);
  } else {
    const re = /[^.!?\n]+[.!?]+["'”’)\]]*\s*|[^.!?\n]+$|\n/g;
    let m;
    while ((m = re.exec(text))) bounds.push([m.index, m.index + m[0].length]);
  }
  // Split on paragraph breaks too, and break very long sentences at commas so
  // each utterance stays short (long utterances stall in some browsers).
  const out = [];
  for (const [s0, e0] of bounds) {
    let parts = [];
    let cursor = s0;
    for (let i = s0; i < e0; i++) if (text[i] === '\n') { parts.push([cursor, i]); cursor = i + 1; }
    parts.push([cursor, e0]);
    for (const [s, e] of parts) {
      if (e - s > 260) {
        let a = s;
        const chunk = text.slice(s, e);
        const re = /[,;:—]\s/g; let m; let lastCut = 0;
        while ((m = re.exec(chunk))) {
          if (m.index - lastCut > 120) { out.push([a, s + m.index + 1]); a = s + m.index + 2; lastCut = m.index; }
        }
        out.push([a, e]);
      } else out.push([s, e]);
    }
  }
  const toPoint = (g, preferEnd) => {
    for (let i = 0; i < pieces.length; i++) {
      const p = pieces[i];
      const len = p.end - p.start;
      if (g < p.gStart + len || (preferEnd && g === p.gStart + len)) {
        if (g < p.gStart) return { node: p.node, offset: p.start };
        return { node: p.node, offset: p.start + (g - p.gStart) };
      }
    }
    const last = pieces[pieces.length - 1];
    return { node: last.node, offset: last.end };
  };
  const sentences = [];
  for (let [s, e] of out) {
    while (s < e && /\s/.test(text[s])) s++;
    while (e > s && /\s/.test(text[e - 1])) e--;
    const t = text.slice(s, e).replace(/\s+/g, ' ').trim();
    if (!t || !/[\p{L}\p{N}]/u.test(t)) continue;
    const a = toPoint(s, false);
    const b = toPoint(e, true);
    const r = doc.createRange();
    try { r.setStart(a.node, a.offset); r.setEnd(b.node, b.offset); } catch { continue; }
    sentences.push({ text: t, range: r });
  }
  return sentences;
}

export class ReadAloud {
  /**
   * @param {object} o
   * @param {import('epubjs').Rendition} o.rendition
   * @param {() => SpeechSynthesisVoice|null} o.voice
   * @param {() => number} o.rate
   * @param {string} o.lang
   * @param {(state: object) => void} o.onState
   */
  constructor({ rendition, voice, rate, lang, onState, title }) {
    this.r = rendition;
    this.voice = voice;
    this.rate = rate;
    this.lang = lang || 'en';
    this.onState = onState;
    this.title = title;
    this.state = 'idle';
    this.sentences = [];
    this.idx = 0;
    this.selfTurn = false;
    this.token = 0;
    this.mark = null;
    this.sleepAt = null;
    this.sleepChapter = null;
    this.sleepTimer = null;
    this.watchdog = null;
    this.wakeLock = null;
    this.onRelocated = this.onRelocated.bind(this);
    this.r.on('relocated', this.onRelocated);
  }

  emit(extra = {}) {
    this.onState?.({
      state: this.state, index: this.idx, total: this.sentences.length,
      sentence: this.sentences[this.idx]?.text || '', sleepAt: this.sleepAt, sleepChapter: this.sleepChapter, ...extra,
    });
  }

  async gather() {
    let loc = this.r.currentLocation();
    if (loc && typeof loc.then === 'function') loc = await loc;
    if (!loc?.start) return [];
    const contents = this.r.getContents();
    const c = contents.find((x) => x.sectionIndex === loc.start.index) || contents[0];
    if (!c) return [];
    const doc = c.document;
    let startR, endR;
    try {
      startR = c.range(loc.start.cfi);
      endR = loc.end && loc.end.index === loc.start.index ? c.range(loc.end.cfi) : null;
    } catch { return []; }
    const range = doc.createRange();
    range.setStart(startR.startContainer, startR.startOffset);
    if (endR) range.setEnd(endR.endContainer, endR.endOffset);
    else range.setEndAfter(doc.body.lastChild || doc.body);
    this.section = loc.start.index;
    return sentencesInRange(doc, range, this.lang);
  }

  async start() {
    if (!ttsSupported) throw new Error('Read-aloud isn’t supported in this browser.');
    this.cancelSpeech();
    this.state = 'loading'; this.emit();
    this.sentences = await this.gather();
    this.idx = 0;
    this.requestWakeLock();
    this.setupMediaSession();
    if (!this.sentences.length) {
      // Image-only or blank page: move on.
      return this.turnPage();
    }
    this.state = 'playing';
    this.speak();
  }

  speak() {
    const my = ++this.token;
    const s = this.sentences[this.idx];
    if (!s) { this.turnPage(); return; }
    this.highlight(s);
    const u = new SpeechSynthesisUtterance(s.text);
    const v = this.voice?.();
    if (v) { u.voice = v; u.lang = v.lang; } else u.lang = this.lang;
    u.rate = this.rate?.() || 1;
    u.onend = () => {
      if (my !== this.token || this.state !== 'playing') return;
      clearTimeout(this.watchdog);
      this.idx++;
      if (this.sleepAt && Date.now() >= this.sleepAt) { this.stop({ reason: 'sleep' }); return; }
      this.emit();
      this.speak();
    };
    u.onerror = (e) => {
      if (my !== this.token) return;
      if (e.error === 'interrupted' || e.error === 'canceled') return;
      clearTimeout(this.watchdog);
      this.state = 'error';
      this.emit({ error: e.error === 'not-allowed'
        ? 'The browser blocked speech. Tap Play again to start reading aloud.'
        : `Speech stopped unexpectedly (${e.error}). Try another voice.` });
      this.clearHighlight();
    };
    // Some engines occasionally never fire `end`; don't let reading stall.
    clearTimeout(this.watchdog);
    const expectMs = (s.text.length / (13 * (u.rate || 1))) * 1000;
    this.watchdog = setTimeout(() => { if (my === this.token && this.state === 'playing') u.onend(); }, expectMs * 2.5 + 6000);
    speechSynthesis.speak(u);
    this.emit();
  }

  async turnPage() {
    const before = this.r.location?.start?.cfi;
    const atEnd = this.r.location?.atEnd;
    if (atEnd) { this.stop({ reason: 'end' }); return; }
    this.state = 'loading'; this.emit();
    this.selfTurn = true;
    const relocated = new Promise((res) => { this._relocatedOnce = res; setTimeout(res, 2500); });
    try { await this.r.next(); } catch { /* handled below */ }
    await relocated;
    this.selfTurn = false;
    if (this.state !== 'loading') return; // stopped meanwhile
    const after = this.r.location?.start?.cfi;
    if (!after || after === before) { this.stop({ reason: 'end' }); return; }
    if (this.sleepChapter != null && this.r.location.start.index !== this.sleepChapter) { this.stop({ reason: 'sleep' }); return; }
    this.sentences = await this.gather();
    this.idx = 0;
    if (!this.sentences.length) return this.turnPage();
    this.state = 'playing';
    this.speak();
  }

  onRelocated() {
    if (this.selfTurn) { this._relocatedOnce?.(); return; }
    // The reader moved by hand while listening: restart from the new page.
    if (this.state === 'playing' || this.state === 'loading') {
      this.cancelSpeech();
      clearTimeout(this._restart);
      this._restart = setTimeout(() => this.start(), 250);
    } else if (this.state === 'paused') {
      this.sentences = []; this.idx = 0; // resume will gather the new page
    }
  }

  pause() {
    if (this.state !== 'playing' && this.state !== 'loading') return;
    this.cancelSpeech();
    this.state = 'paused';
    this.emit();
  }

  async resume() {
    if (this.state !== 'paused') return;
    if (!this.sentences.length) return this.start();
    this.state = 'playing';
    this.speak();
  }

  skip(delta) {
    if (!this.sentences.length) return;
    const next = this.idx + delta;
    if (next < 0) { this.idx = 0; }
    else if (next >= this.sentences.length) { this.cancelSpeech(); this.state = 'playing'; this.turnPage(); return; }
    else this.idx = next;
    if (this.state === 'playing') { this.cancelSpeech(); this.speak(); }
    else this.emit();
  }

  restartSentence() {
    if (this.state === 'playing') { this.cancelSpeech(); this.speak(); }
  }

  setSleep(minutes) {
    clearTimeout(this.sleepTimer);
    this.sleepAt = null; this.sleepChapter = null;
    if (minutes === 'chapter') this.sleepChapter = this.r.location?.start?.index ?? null;
    else if (minutes > 0) {
      this.sleepAt = Date.now() + minutes * 60000;
      // Stop at the end of the sentence being read when the time is up.
      this.sleepTimer = setTimeout(() => { if (this.state !== 'playing') this.stop({ reason: 'sleep' }); }, minutes * 60000 + 500);
    }
    this.emit();
  }

  cancelSpeech() {
    this.token++;
    clearTimeout(this.watchdog);
    if (ttsSupported) speechSynthesis.cancel();
  }

  stop({ reason = 'user' } = {}) {
    this.cancelSpeech();
    clearTimeout(this._restart);
    clearTimeout(this.sleepTimer);
    this.sleepAt = null; this.sleepChapter = null;
    this.state = 'idle';
    this.clearHighlight();
    this.releaseWakeLock();
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none';
    this.emit({ reason });
  }

  highlight(s) {
    this.clearHighlight();
    try {
      const contents = this.r.getContents().find((x) => x.sectionIndex === this.section) || this.r.getContents()[0];
      const cfi = contents.cfiFromRange(s.range);
      this.mark = cfi;
      this.r.annotations.add('highlight', cfi, {}, null, 'mavis-tts', { fill: '#d9a441', 'fill-opacity': '0.32', 'mix-blend-mode': 'multiply' });
    } catch { /* highlight is best-effort */ }
  }

  clearHighlight() {
    if (this.mark) { try { this.r.annotations.remove(this.mark, 'highlight'); } catch { /* ignore */ } }
    this.mark = null;
  }

  async requestWakeLock() {
    try { if ('wakeLock' in navigator && !this.wakeLock) this.wakeLock = await navigator.wakeLock.request('screen'); } catch { /* optional */ }
  }
  releaseWakeLock() { try { this.wakeLock?.release(); } catch { /* ignore */ } this.wakeLock = null; }

  setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: this.title || 'Mavis Library', artist: 'Read aloud' });
      navigator.mediaSession.setActionHandler('play', () => this.resume());
      navigator.mediaSession.setActionHandler('pause', () => this.pause());
      navigator.mediaSession.setActionHandler('stop', () => this.stop());
      navigator.mediaSession.setActionHandler('nexttrack', () => this.skip(1));
      navigator.mediaSession.setActionHandler('previoustrack', () => this.skip(-1));
    } catch { /* optional */ }
  }

  destroy() {
    this.stop({ reason: 'close' });
    this.r.off('relocated', this.onRelocated);
  }
}
