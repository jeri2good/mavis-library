import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../app/src/main/assets/native-speech-synthesis.js', import.meta.url), 'utf8');
const load = (extra = {}) => {
  const posted = [];
  const window = { MavisSpeech: { postMessage: (text) => posted.push(JSON.parse(text)) }, ...extra };
  window.top = window;
  vm.runInNewContext(source, { window, Event, EventTarget, Date, JSON, Map, WeakMap, Math, Number, String, Boolean, Object, TypeError, setTimeout });
  return { window, posted, deliver: (message) => window.MavisSpeech.onmessage({ data: JSON.stringify(message) }) };
};

// Leaves real implementations alone; also stays out of subframes.
const existing = {};
assert.equal(load({ speechSynthesis: existing, SpeechSynthesisUtterance: function () {} }).window.speechSynthesis, existing);
const frame = { MavisSpeech: { postMessage() {} }, top: {} };
vm.runInNewContext(source, { window: frame, Event, EventTarget });
assert.equal(frame.speechSynthesis, undefined);

const { window, posted, deliver } = load();
const synth = window.speechSynthesis;
assert.deepEqual(posted, [{ op: 'hello' }]);

// getVoices + voiceschanged, including addEventListener and on* handlers.
assert.equal(synth.getVoices().length, 0);
let changes = 0;
synth.addEventListener('voiceschanged', () => changes++);
synth.onvoiceschanged = () => changes++;
deliver({ ev: 'voices', voices: [{ uri: 'kokoro:af_heart', name: 'Natural — Heart (Kokoro)', lang: 'en-US', isDefault: true }] });
assert.equal(changes, 2);
const voice = synth.getVoices()[0];
assert.equal(voice.voiceURI, 'kokoro:af_heart');
assert.equal(voice.localService, true);
assert.equal(voice.default, true);
assert.ok(voice instanceof window.SpeechSynthesisVoice);

// Queue flags and the speak payload.
const first = new window.SpeechSynthesisUtterance('One.');
Object.assign(first, { voice, lang: 'en-US', rate: 99, pitch: -1, volume: 0.5 });
const second = new window.SpeechSynthesisUtterance('Two.');
const seen = [];
for (const type of ['start', 'boundary', 'end', 'error']) {
  first.addEventListener(type, (e) => seen.push(`1:${type}:${e.error || e.charIndex}`));
  second[`on${type}`] = (e) => seen.push(`2:${type}:${e.error || e.charIndex}`);
}
synth.speak(first);
assert.equal(synth.speaking, true);
assert.equal(synth.pending, false);
synth.speak(second);
assert.equal(synth.pending, true);
const [a, b] = posted.filter((m) => m.op === 'speak');
assert.equal(a.voice, 'kokoro:af_heart');
assert.equal(a.rate, 10);
assert.equal(a.pitch, 0);
assert.equal(a.volume, 0.5);

deliver({ ev: 'start', id: a.id });
deliver({ ev: 'boundary', id: a.id, name: 'word', charIndex: 2, charLength: 1 });
deliver({ ev: 'end', id: a.id });
assert.deepEqual(seen, ['1:start:0', '1:boundary:2', '1:end:0']);
assert.equal(synth.pending, false);

// Pause, resume, and cancel semantics; native reports the cancelled utterance's error, never `end`.
synth.pause();
assert.equal(synth.paused, true);
synth.resume();
assert.equal(synth.paused, false);
deliver({ ev: 'start', id: b.id });
synth.pause();
synth.cancel();
assert.equal(posted.at(-1).op, 'cancel');
assert.equal(synth.speaking, false);
assert.equal(synth.paused, false);
deliver({ ev: 'error', id: b.id, error: 'interrupted' });
assert.equal(seen.at(-1), '2:error:interrupted');
assert.equal(seen.filter((s) => s.startsWith('2:end')).length, 0);
deliver({ ev: 'end', id: b.id }); // a late event for a finished utterance is dropped
assert.equal(seen.filter((s) => s.startsWith('2:end')).length, 0);

// Too-long text fails like Chrome, without reaching native.
const before = posted.length;
const long = new window.SpeechSynthesisUtterance('x'.repeat(32768));
const longError = await new Promise((resolve) => { long.onerror = resolve; synth.speak(long); });
assert.equal(longError.error, 'text-too-long');
assert.equal(posted.length, before);
assert.throws(() => synth.speak({ text: 'nope' }), /SpeechSynthesisUtterance/);

console.log('speech shim checks passed');
