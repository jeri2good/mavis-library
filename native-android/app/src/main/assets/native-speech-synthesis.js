// Android WebView has no Web Speech synthesis. Where (and only where) it is missing, this defines the standard
// objects over the "MavisSpeech" web message listener; the queue itself lives in Kotlin (SpeechDispatcher).
//
// Cancel follows Chrome desktop: the utterance being spoken fires `error` with "interrupted", queued ones fire
// `error` with "canceled", neither fires `end`, and cancel also clears `paused`.
(() => {
  if (window.top !== window || ('speechSynthesis' in window && 'SpeechSynthesisUtterance' in window)) return;

  const MAX_TEXT = 32767;
  const clamp = (value, low, high, fallback) => {
    const n = Number(value);
    return Number.isFinite(n) ? Math.min(high, Math.max(low, n)) : fallback;
  };
  const voiceFields = new WeakMap();

  class SpeechSynthesisVoice {
    get voiceURI() { return voiceFields.get(this).uri; }
    get name() { return voiceFields.get(this).name; }
    get lang() { return voiceFields.get(this).lang; }
    get localService() { return true; }
    get default() { return Boolean(voiceFields.get(this).isDefault); }
  }
  const makeVoice = (fields) => {
    const voice = new SpeechSynthesisVoice();
    voiceFields.set(voice, fields);
    return voice;
  };

  class SpeechSynthesisEvent extends Event {
    constructor(type, init = {}) {
      super(type);
      this.utterance = init.utterance;
      this.charIndex = init.charIndex || 0;
      this.charLength = init.charLength || 0;
      this.elapsedTime = init.elapsedTime || 0;
      this.name = init.name || '';
    }
  }
  class SpeechSynthesisErrorEvent extends SpeechSynthesisEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.error = init.error;
    }
  }

  const fire = (target, event) => {
    target.dispatchEvent(event);
    const handler = target[`on${event.type}`];
    if (typeof handler === 'function') handler.call(target, event);
  };

  class SpeechSynthesisUtterance extends EventTarget {
    constructor(text = '') {
      super();
      this.text = String(text);
      this.lang = '';
      this.voice = null;
      this.volume = 1;
      this.rate = 1;
      this.pitch = 1;
      for (const type of ['start', 'end', 'error', 'pause', 'resume', 'mark', 'boundary']) this[`on${type}`] = null;
    }
  }

  let nextId = Date.now();
  let live = [];
  let paused = false;
  let voices = [];
  const utterances = new Map();
  const startedAt = new Map();
  let connected = false;

  const connect = () => {
    if (connected || !window.MavisSpeech) return connected;
    connected = true;
    window.MavisSpeech.onmessage = (message) => {
      try { receive(JSON.parse(message.data)); } catch { /* a malformed event must not break the page */ }
    };
    window.MavisSpeech.postMessage(JSON.stringify({ op: 'hello' }));
    return true;
  };
  const send = (message) => {
    if (connect()) window.MavisSpeech.postMessage(JSON.stringify(message));
  };

  class SpeechSynthesis extends EventTarget {
    constructor() {
      super();
      this.onvoiceschanged = null;
    }
    get speaking() { connect(); return live.length > 0; }
    get pending() { return live.length > 1; }
    get paused() { return paused; }
    getVoices() { connect(); return voices.slice(); }
    speak(utterance) {
      if (!(utterance instanceof SpeechSynthesisUtterance)) throw new TypeError('speak() needs a SpeechSynthesisUtterance');
      const text = String(utterance.text);
      if (text.length > MAX_TEXT) {
        setTimeout(() => fire(utterance, new SpeechSynthesisErrorEvent('error', { utterance, error: 'text-too-long' })), 0);
        return;
      }
      const id = ++nextId;
      utterances.set(id, utterance);
      live.push(id);
      send({
        op: 'speak', id, text,
        voice: utterance.voice ? utterance.voice.voiceURI : null,
        lang: String(utterance.lang || ''),
        rate: clamp(utterance.rate, 0.1, 10, 1),
        pitch: clamp(utterance.pitch, 0, 2, 1),
        volume: clamp(utterance.volume, 0, 1, 1),
      });
    }
    cancel() {
      live = [];
      paused = false;
      send({ op: 'cancel' });
    }
    pause() {
      if (paused) return;
      paused = true;
      send({ op: 'pause' });
    }
    resume() {
      if (!paused) return;
      paused = false;
      send({ op: 'resume' });
    }
  }

  const synthesis = new SpeechSynthesis();

  const receive = (message) => {
    if (message.ev === 'voices') {
      voices = message.voices.map(makeVoice);
      const event = new Event('voiceschanged');
      fire(synthesis, event);
      return;
    }
    const utterance = utterances.get(message.id);
    if (!utterance) return;
    const elapsedTime = startedAt.has(message.id) ? Date.now() - startedAt.get(message.id) : 0;
    const init = { utterance, elapsedTime, charIndex: message.charIndex, charLength: message.charLength, name: message.name };
    if (message.ev === 'start') {
      startedAt.set(message.id, Date.now());
      fire(utterance, new SpeechSynthesisEvent('start', init));
    } else if (message.ev === 'boundary') {
      fire(utterance, new SpeechSynthesisEvent('boundary', init));
    } else if (message.ev === 'pause' || message.ev === 'resume') {
      fire(utterance, new SpeechSynthesisEvent(message.ev, init));
    } else if (message.ev === 'end' || message.ev === 'error') {
      live = live.filter((id) => id !== message.id);
      utterances.delete(message.id);
      startedAt.delete(message.id);
      fire(utterance, message.ev === 'end'
        ? new SpeechSynthesisEvent('end', init)
        : new SpeechSynthesisErrorEvent('error', { ...init, error: message.error }));
    }
  };

  Object.assign(window, { SpeechSynthesisVoice, SpeechSynthesisEvent, SpeechSynthesisErrorEvent, SpeechSynthesisUtterance });
  window.speechSynthesis = synthesis;
  connect();
  // Tolerates a WebView that injects the listener object just after document-start scripts.
  if (!connected) setTimeout(connect, 0);
})();
