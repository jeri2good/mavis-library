// Voice lookup using the browser's speech recognition. The microphone is only
// requested after the person taps a mic button. Recognition availability and
// privacy differ by browser, so the sheet says so every time.

import { openDialog, html, icon } from './ui.js';

const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
export const voiceInputSupported = !!Recognition;

const ERRORS = {
  'not-allowed': 'Microphone access is blocked. Allow the microphone for this site in your browser settings, or type instead.',
  'service-not-allowed': 'This browser does not allow speech recognition here. Type instead.',
  'no-speech': "I didn't hear anything. Try again, or type instead.",
  'audio-capture': 'No microphone was found. Connect one, or type instead.',
  network: 'Speech recognition needs an internet connection in this browser. Type instead.',
  'language-not-supported': 'Speech recognition does not support this language here. Type instead.',
};

function privacyNote() {
  const ua = navigator.userAgent;
  if (/Chrome|CriOS|Edg/.test(ua) && !/Firefox/.test(ua)) {
    return 'In Chrome and Edge, your speech is sent to the browser maker’s servers to be turned into text. Mavis only receives the final text.';
  }
  if (/Safari/.test(ua)) return 'Safari may send your speech to Apple to be turned into text. Mavis only receives the final text.';
  return 'Your browser may send your speech to its provider to be turned into text. Mavis only receives the final text.';
}

/**
 * Opens a listening sheet. Resolves with the recognized text, or null if the
 * person cancels or recognition fails (an explanation is shown in the sheet).
 */
export function listen({ purpose = 'Search by voice', lang = navigator.language || 'en-US' } = {}) {
  return new Promise((resolve) => {
    if (!Recognition) {
      const d = openDialog({
        title: 'Voice input isn’t available',
        body: html`<p class="dialog-text">This browser doesn't support speech recognition. Chrome on Android and desktop Chrome or Edge do. You can type your search instead.</p>
          <div class="dialog-actions"><button type="button" class="btn btn-primary" data-close autofocus>Type instead</button></div>`,
        onClose: () => resolve(null),
      });
      return d;
    }
    let result = null;
    let settled = false;
    const rec = new Recognition();
    rec.lang = lang;
    rec.interimResults = true;
    rec.maxAlternatives = 1;
    rec.continuous = false;

    const d = openDialog({
      title: purpose,
      variant: 'sheet',
      className: 'listen-sheet',
      body: html`
        <div class="listen">
          <div class="listen-orb" aria-hidden="true">${icon('mic', { size: 30 })}</div>
          <p class="listen-state" role="status" aria-live="polite">Starting the microphone…</p>
          <p class="listen-heard" aria-live="polite"></p>
          <p class="listen-privacy">${privacyNote()}</p>
          <div class="dialog-actions">
            <button type="button" class="btn btn-quiet" data-act="cancel">Cancel</button>
            <button type="button" class="btn btn-primary" data-act="use" disabled>Use this</button>
          </div>
        </div>`,
      onClose: () => {
        try { rec.abort(); } catch { /* already stopped */ }
        if (!settled) { settled = true; resolve(result); }
      },
    });
    const stateEl = d.body.querySelector('.listen-state');
    const heardEl = d.body.querySelector('.listen-heard');
    const useBtn = d.body.querySelector('[data-act="use"]');
    const orb = d.body.querySelector('.listen-orb');

    d.body.addEventListener('click', (e) => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'cancel') { result = null; d.close('cancel'); }
      if (act === 'use') { d.close('use'); }
    });

    let finalText = '';
    rec.onstart = () => { stateEl.textContent = 'Listening… speak now'; orb.classList.add('live'); };
    rec.onresult = (ev) => {
      let interim = '';
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const r = ev.results[i];
        if (r.isFinal) finalText += r[0].transcript; else interim += r[0].transcript;
      }
      const shown = (finalText + interim).trim();
      heardEl.textContent = shown ? `“${shown}”` : '';
      if (finalText.trim()) { result = finalText.trim(); useBtn.disabled = false; }
    };
    rec.onerror = (ev) => {
      orb.classList.remove('live');
      if (ev.error === 'aborted') return;
      result = null;
      stateEl.textContent = ERRORS[ev.error] || `Speech recognition stopped (${ev.error}). Type instead.`;
      d.el.classList.add('listen-error');
    };
    rec.onend = () => {
      orb.classList.remove('live');
      if (result) {
        stateEl.textContent = 'Heard you. Use this text?';
        useBtn.disabled = false;
        useBtn.focus();
      } else if (!d.el.classList.contains('listen-error')) {
        stateEl.textContent = ERRORS['no-speech'];
      }
    };
    try { rec.start(); } catch (err) {
      stateEl.textContent = 'The microphone could not start. Type instead.';
    }
  });
}
