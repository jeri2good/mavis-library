// Car mode: a full-screen, high-contrast player with very large controls for
// listening on the road. It shows only what a glance needs and keeps the
// screen awake while the device voice is reading.

import { html, icon, esc } from './ui.js';

/**
 * openCarMode({ title, subtitle, onToggle, onBack, onForward, onSleep, onExit, cloud })
 * Returns { update({ state, text, label, sleepAt, error }), close() }.
 */
export function openCarMode({ title, subtitle = '', onToggle, onBack, onForward, onSleep, onExit, cloud = false, container = document.body }) {
  const opener = document.activeElement;
  const el = document.createElement('div');
  el.className = 'carmode';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Car mode');
  el.innerHTML = String(html`
    <header class="car-top">
      <button type="button" class="car-exit" data-car="exit">${icon('close', { size: 26 })} Exit car mode</button>
      <button type="button" class="car-sleep" data-car="sleep" aria-label="Sleep timer">${icon('timer', { size: 26 })}<span data-sleep-label>Sleep</span></button>
    </header>
    <div class="car-meta">
      <p class="car-title">${title}</p>
      <p class="car-sub" data-sub>${subtitle}</p>
    </div>
    <p class="car-line" data-line aria-live="off"></p>
    <div class="car-controls">
      <button type="button" class="car-btn" data-car="back" aria-label="${cloud ? 'Back 15 seconds' : 'Previous sentence'}">${icon('skipB', { size: 44 })}</button>
      <button type="button" class="car-btn car-play" data-car="toggle" aria-label="Play">${icon('play', { size: 64 })}</button>
      <button type="button" class="car-btn" data-car="forward" aria-label="${cloud ? 'Forward 15 seconds' : 'Next sentence'}">${icon('skipF', { size: 44 })}</button>
    </div>
    <p class="car-status" data-status role="status"></p>
    <p class="car-note">${cloud
      ? 'Cloud voice: you can lock the phone or switch to maps. Your car’s play/pause buttons work too.'
      : 'Device voice: keep Mavis on screen while it reads. Locking the phone stops the voice on most phones.'}</p>`);
  container.appendChild(el);
  document.body.classList.add('has-overlay');
  requestAnimationFrame(() => el.classList.add('open'));

  const sleeps = [0, 15, 30, 45, 60];
  let sleepIdx = 0;
  const onKey = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); close(); }
    if (e.key === ' ' || e.key === 'k') { e.preventDefault(); onToggle?.(); }
  };
  document.addEventListener('keydown', onKey, true);
  el.addEventListener('click', (e) => {
    const a = e.target.closest('[data-car]')?.dataset.car;
    if (a === 'exit') close();
    if (a === 'toggle') onToggle?.();
    if (a === 'back') onBack?.();
    if (a === 'forward') onForward?.();
    if (a === 'sleep') {
      sleepIdx = (sleepIdx + 1) % sleeps.length;
      onSleep?.(sleeps[sleepIdx]);
      el.querySelector('[data-sleep-label]').textContent = sleeps[sleepIdx] ? `${sleeps[sleepIdx]} min` : 'Sleep';
    }
  });
  setTimeout(() => el.querySelector('.car-play').focus({ preventScroll: true }), 60);

  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    document.removeEventListener('keydown', onKey, true);
    el.classList.remove('open');
    setTimeout(() => { el.remove(); if (!document.querySelector('.overlay, .carmode')) document.body.classList.remove('has-overlay'); }, 220);
    if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
    onExit?.();
  }

  function update({ state, text, label, error, sleepAt }) {
    if (closed) return;
    const playing = state === 'playing' || state === 'loading';
    const play = el.querySelector('.car-play');
    play.innerHTML = String(icon(playing ? 'pause' : 'play', { size: 64 }));
    play.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    el.classList.toggle('is-playing', playing);
    if (text) el.querySelector('[data-line]').textContent = text;
    if (label) el.querySelector('[data-sub]').textContent = label;
    el.querySelector('[data-status]').textContent = error
      || (state === 'loading' ? 'Loading…' : state === 'paused' ? 'Paused' : state === 'idle' ? 'Stopped' : sleepAt ? `Stops at ${new Date(sleepAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : '');
  }

  return { update, close, el };
}

export { esc };
