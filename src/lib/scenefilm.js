// Scene films: a passage (a selection, or the page you're on) turned into a
// short film. The AI plans 2–5 shots that follow the text, paints each one in a
// consistent look, and the narrator reads the passage over them. With a fal.ai
// key, each painted shot can be brought to life as real moving footage;
// otherwise the camera moves over the stills with drifting atmosphere (fog,
// rain, snow, embers…). Everything is assembled and recorded on the device.

import { html, icon, toast, openDialog } from './ui.js';
import * as store from './store.js';
import { loadFeatures, can, features, ownerPost, accessCode } from './features.js';
import { makePicture, savePicture, STYLES } from './companion.js';
import { videoSupported } from './video.js';

const SHAPES = {
  wide: { label: 'Wide 16:9 (TV, YouTube, computer)', w: 1920, h: 1080, pic: 'wide' },
  tall: { label: 'Tall 9:16 (phone, Stories, Reels)', w: 1080, h: 1920, pic: 'tall' },
  square: { label: 'Square (feed posts)', w: 1080, h: 1080, pic: 'square' },
};
export const MAX_CHARS = 1800; // about two minutes of narration

const ease = (p) => 0.5 - 0.5 * Math.cos(Math.PI * Math.min(1, Math.max(0, p)));
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }

/** Split text into sentences (Intl.Segmenter when available). */
export function sentencesOf(text, lang = 'en') {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  if (!t) return [];
  try {
    const seg = new Intl.Segmenter(lang, { granularity: 'sentence' });
    return [...seg.segment(t)].map((x) => x.segment.trim()).filter(Boolean);
  } catch {
    return t.match(/[^.!?]+[.!?]+["”’)\]]*|[^.!?]+$/g)?.map((x) => x.trim()).filter(Boolean) || [t];
  }
}

/** Keep whole sentences up to the narration limit. */
export function trimToLimit(text, limit = MAX_CHARS) {
  const out = [];
  let n = 0;
  for (const s of sentencesOf(text)) {
    if (n + s.length > limit && out.length) break;
    out.push(s.length > limit ? `${s.slice(0, limit - 1).replace(/\s+\S*$/, '')}…` : s);
    n += s.length + 1;
  }
  return out;
}

/** Group sentences into shots by the storyboard's "from" indexes. */
export function shotTexts(sentences, shots) {
  return shots.map((s, i) => sentences.slice(s.from, i + 1 < shots.length ? shots[i + 1].from : sentences.length).join(' '));
}

async function loadImage(blob) {
  const url = URL.createObjectURL(blob);
  const img = new Image();
  img.src = url;
  await img.decode();
  return img;
}

function toJpegDataUri(img, maxW = 1280) {
  const s = Math.min(1, maxW / img.width);
  const c = document.createElement('canvas');
  c.width = Math.round(img.width * s); c.height = Math.round(img.height * s);
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
  return c.toDataURL('image/jpeg', 0.86);
}

function pickMime() {
  const opts = ['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
  return opts.find((t) => MediaRecorder.isTypeSupported?.(t)) || '';
}

function wrap(ctx, text, maxWidth) {
  const words = String(text).split(/\s+/).filter(Boolean);
  const lines = [];
  let line = '';
  for (const w of words) {
    const test = line ? `${line} ${w}` : w;
    if (line && ctx.measureText(test).width > maxWidth) { lines.push(line); line = w; } else line = test;
  }
  if (line) lines.push(line);
  return lines;
}

// ---------- Atmosphere ----------

function drawEffect(ctx, kind, t, W, H, seed) {
  if (!kind || kind === 'none') return;
  const r = rng(seed);
  ctx.save();
  if (kind === 'fog' || kind === 'mist') {
    const n = kind === 'fog' ? 5 : 3;
    for (let i = 0; i < n; i++) {
      const y = H * (0.35 + 0.6 * r());
      const x = ((r() * 1.6 - 0.3) * W + t * W * (0.012 + 0.02 * r())) % (W * 1.6) - W * 0.3;
      const rad = W * (0.35 + 0.3 * r());
      const g = ctx.createRadialGradient(x, y, 0, x, y, rad);
      g.addColorStop(0, `rgba(235,238,240,${kind === 'fog' ? 0.22 : 0.12})`);
      g.addColorStop(1, 'rgba(235,238,240,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
    }
  } else if (kind === 'rain') {
    ctx.strokeStyle = 'rgba(210,225,240,0.28)';
    ctx.lineWidth = Math.max(1, W / 900);
    for (let i = 0; i < 140; i++) {
      const sp = 0.9 + r() * 0.6;
      const x = (r() * W * 1.2 + t * W * 0.15) % (W * 1.2) - W * 0.1;
      const y = ((r() + t * sp) % 1) * H * 1.1 - H * 0.05;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - W * 0.008, y + H * 0.045); ctx.stroke();
    }
  } else if (kind === 'snow') {
    ctx.fillStyle = 'rgba(255,255,255,0.8)';
    for (let i = 0; i < 110; i++) {
      const sp = 0.05 + r() * 0.08;
      const y = ((r() + t * sp) % 1) * H;
      const x = (r() * W + Math.sin(t * (0.6 + r()) + i) * W * 0.02 + W) % W;
      const s = W * (0.0015 + r() * 0.003);
      ctx.beginPath(); ctx.arc(x, y, s, 0, Math.PI * 2); ctx.fill();
    }
  } else if (kind === 'embers' || kind === 'sparkle' || kind === 'dust') {
    ctx.globalCompositeOperation = kind === 'dust' ? 'source-over' : 'lighter';
    const n = kind === 'dust' ? 70 : 45;
    for (let i = 0; i < n; i++) {
      const sp = kind === 'embers' ? 0.06 + r() * 0.08 : 0.01 + r() * 0.02;
      const y = kind === 'embers' ? H - ((r() + t * sp) % 1) * H : ((r() + t * sp * (r() > 0.5 ? 1 : -1)) % 1 + 1) % 1 * H;
      const x = (r() * W + Math.sin(t * 0.7 + i) * W * 0.015 + W) % W;
      const tw = 0.5 + 0.5 * Math.sin(t * (2 + r() * 4) + i);
      const s = W * (kind === 'dust' ? 0.0012 : 0.0018) * (1 + r() * 1.5);
      const col = kind === 'embers' ? `rgba(255,${140 + Math.round(r() * 60)},60,${0.35 + 0.5 * tw})` : kind === 'sparkle' ? `rgba(255,250,220,${0.2 + 0.7 * tw})` : `rgba(255,236,200,${0.12 + 0.2 * tw})`;
      ctx.fillStyle = col;
      ctx.beginPath(); ctx.arc(x, y, s, 0, Math.PI * 2); ctx.fill();
    }
  } else if (kind === 'leaves') {
    for (let i = 0; i < 18; i++) {
      const sp = 0.05 + r() * 0.06;
      const y = ((r() + t * sp) % 1) * H;
      const x = (r() * W + t * W * 0.03 + Math.sin(t + i) * W * 0.03) % W;
      ctx.save(); ctx.translate(x, y); ctx.rotate(t * (1 + r()) + i);
      ctx.fillStyle = `rgba(${150 + Math.round(r() * 60)},${80 + Math.round(r() * 40)},40,0.75)`;
      ctx.beginPath(); ctx.ellipse(0, 0, W * 0.006, W * 0.003, 0, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }
  }
  ctx.restore();
}

// ---------- Camera ----------

function cover(srcW, srcH, W, H) { return Math.max(W / srcW, H / srcH); }

function drawShot(ctx, shot, p, W, H) {
  const src = shot.clip && shot.clip.readyState >= 2 ? shot.clip : shot.image;
  const sw = src.videoWidth || src.width, sh = src.videoHeight || src.height;
  const base = cover(sw, sh, W, H);
  const e = ease(p);
  let scale = 1.08, dx = 0, dy = 0;
  if (shot.clip && src === shot.clip) { scale = 1.0 + 0.05 * e; }
  else {
    switch (shot.camera) {
      case 'push-in': scale = 1.04 + 0.16 * e; break;
      case 'pull-out': scale = 1.2 - 0.16 * e; break;
      case 'pan-left': scale = 1.16; dx = 0.5 - e; break;
      case 'pan-right': scale = 1.16; dx = e - 0.5; break;
      case 'rise': scale = 1.16; dy = 0.5 - e; break;
      default: scale = 1.08 + 0.08 * e; dx = (e - 0.5) * 0.5; dy = (0.5 - e) * 0.3;
    }
  }
  const iw = sw * base * scale, ih = sh * base * scale;
  const x = (W - iw) / 2 + dx * (iw - W);
  const y = (H - ih) / 2 + dy * (ih - H);
  ctx.drawImage(src, x, y, iw, ih);
}

function vignette(ctx, W, H) {
  const g = ctx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.max(W, H) * 0.75);
  g.addColorStop(0, 'rgba(0,0,0,0)');
  g.addColorStop(1, 'rgba(0,0,0,0.45)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, W, H);
}

/**
 * Lay out the film's timeline. Each shot lasts as long as its narration (or a
 * reading-speed estimate), shots crossfade, a title card opens and a short
 * closing card ends it.
 */
export function timeline(shots, { titleDur = 2.6, fade = 0.9, endDur = 2.4 } = {}) {
  let t = titleDur;
  const out = shots.map((s) => {
    const speech = s.audio ? s.audio.duration : Math.max(3.5, s.text.length / 15);
    const dur = Math.max(3.2, speech + 0.7);
    const shot = { ...s, start: t, dur, speechStart: t + 0.35, speech };
    t += dur;
    return shot;
  });
  return { shots: out, total: t + endDur, titleDur, fade, endDur };
}

/**
 * Record the film. shots: [{ image, clip?, audio? (AudioBuffer), text, camera, effect }]
 * Returns { blob, mime, seconds }.
 */
export async function renderFilm({ shots, shape = 'wide', title = '', subtitle = '', citation = '', captions = true, watermark = true, ac, canvas, onProgress, signal }) {
  const S = SHAPES[shape] || SHAPES.wide;
  const W = S.w, H = S.h;
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext('2d');
  try { await Promise.all([document.fonts.load("560 80px 'Mavis Fraunces'"), document.fonts.load("600 40px 'Mavis Atkinson'")]); } catch { /* system fonts */ }
  const plan = timeline(shots);
  const dest = ac.createMediaStreamDestination();
  const unit = Math.min(W, H);
  const capFont = `600 ${Math.round(unit * 0.036)}px 'Mavis Atkinson', system-ui, sans-serif`;

  // Captions: each sentence of a shot gets a slice of that shot's narration by length.
  for (const s of plan.shots) {
    const parts = s.text.match(/[^.!?…]+[.!?…]+["”’)\]]*|[^.!?…]+$/g)?.map((x) => x.trim()).filter(Boolean) || [s.text];
    const totalLen = parts.reduce((a, x) => a + x.length, 0) || 1;
    let acc = 0;
    s.cues = parts.map((x) => { const c = { text: x, at: s.speechStart + (acc / totalLen) * s.speech }; acc += x.length; return c; });
  }

  const at = (t) => plan.shots.findIndex((s) => t >= s.start && t < s.start + s.dur);
  const drawFrame = (t) => {
    ctx.globalAlpha = 1;
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    const last = plan.shots[plan.shots.length - 1];
    let i = at(t);
    if (t < plan.shots[0].start) i = 0;
    if (i < 0) i = plan.shots.length - 1;
    const cur = plan.shots[i];
    const p = Math.min(1, Math.max(0, (t - cur.start + (i === 0 ? plan.titleDur : 0)) / (cur.dur + (i === 0 ? plan.titleDur : 0) + (cur === last ? plan.endDur : 0))));
    drawShot(ctx, cur, p, W, H);
    if (!cur.clip) drawEffect(ctx, cur.effect, t, W, H, (i + 1) * 7919);
    // Crossfade into the next shot.
    const next = plan.shots[i + 1];
    if (next && t > next.start - plan.fade) {
      ctx.globalAlpha = ease((t - (next.start - plan.fade)) / plan.fade);
      drawShot(ctx, next, 0, W, H);
      if (!next.clip) drawEffect(ctx, next.effect, t, W, H, (i + 2) * 7919);
      ctx.globalAlpha = 1;
    }
    vignette(ctx, W, H);

    // Title card.
    if (t < plan.titleDur + 0.6) {
      const a = t < plan.titleDur - 0.6 ? Math.min(1, t / 0.6) : Math.max(0, 1 - (t - (plan.titleDur - 0.6)) / 1.2);
      ctx.globalAlpha = a * 0.55; ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
      ctx.globalAlpha = a; ctx.fillStyle = '#fff8ec'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.font = `560 ${Math.round(unit * 0.075)}px 'Mavis Fraunces', Georgia, serif`;
      const lines = wrap(ctx, title, W * 0.8).slice(0, 3);
      const lh = unit * 0.09;
      lines.forEach((l, k) => ctx.fillText(l, W / 2, H / 2 - ((lines.length - 1) * lh) / 2 + k * lh - (subtitle ? lh * 0.35 : 0)));
      if (subtitle) {
        ctx.font = `600 ${Math.round(unit * 0.032)}px 'Mavis Atkinson', system-ui, sans-serif`;
        ctx.fillStyle = '#f3c97a';
        ctx.fillText(subtitle, W / 2, H / 2 + ((lines.length - 1) * lh) / 2 + lh * 0.75);
      }
      ctx.globalAlpha = 1;
    }

    // Captions.
    if (captions && t >= plan.titleDur) {
      const cue = [...cur.cues].reverse().find((c) => t >= c.at);
      if (cue && t < cur.start + cur.dur - 0.2) {
        ctx.font = capFont; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
        const lines = wrap(ctx, cue.text, W * 0.84).slice(0, 4);
        const lh = unit * 0.05;
        const boxH = lines.length * lh + unit * 0.03;
        const y0 = H - unit * 0.07 - boxH;
        ctx.fillStyle = 'rgba(0,0,0,0.5)';
        ctx.fillRect(W * 0.05, y0, W * 0.9, boxH);
        ctx.fillStyle = '#fffaf0';
        lines.forEach((l, k) => ctx.fillText(l, W / 2, y0 + unit * 0.012 + (k + 0.85) * lh));
      }
    }

    // Closing card.
    const endStart = plan.total - plan.endDur;
    if (t > endStart) {
      const a = Math.min(1, (t - endStart) / 0.8);
      ctx.globalAlpha = a * 0.6; ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
      ctx.globalAlpha = a; ctx.fillStyle = '#f3c97a'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.font = `600 ${Math.round(unit * 0.036)}px 'Mavis Atkinson', system-ui, sans-serif`;
      wrap(ctx, `— ${citation}`, W * 0.8).slice(0, 2).forEach((l, k) => ctx.fillText(l, W / 2, H / 2 + k * unit * 0.05));
      const fadeOut = Math.max(0, (t - (plan.total - 0.7)) / 0.7);
      if (fadeOut > 0) { ctx.globalAlpha = fadeOut; ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H); }
      ctx.globalAlpha = 1;
    }
    if (watermark) {
      ctx.globalAlpha = 0.5; ctx.textAlign = 'right'; ctx.textBaseline = 'alphabetic';
      ctx.font = `500 ${Math.round(unit * 0.022)}px 'Mavis Atkinson', system-ui, sans-serif`;
      ctx.fillStyle = '#fffaf0';
      ctx.fillText('Mavis Library', W - unit * 0.04, unit * 0.06);
      ctx.globalAlpha = 1;
    }
    ctx.textAlign = 'start';
  };

  drawFrame(0);
  const stream = canvas.captureStream(30);
  for (const tr of dest.stream.getAudioTracks()) stream.addTrack(tr);
  const mime = pickMime();
  const rec = new MediaRecorder(stream, { ...(mime ? { mimeType: mime } : {}), videoBitsPerSecond: 8_000_000, audioBitsPerSecond: 128_000 });
  const chunks = [];
  rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  const done = new Promise((resolve, reject) => { rec.onstop = resolve; rec.onerror = (e) => reject(e.error || new Error('Recording failed.')); });
  if (ac.state === 'suspended') await ac.resume().catch(() => {});
  rec.start(500);
  const t0 = performance.now();
  const base = ac.currentTime;
  for (const s of plan.shots) {
    if (!s.audio) continue;
    const src = ac.createBufferSource();
    src.buffer = s.audio;
    src.connect(dest);
    src.start(base + s.speechStart);
  }
  const started = new Set();
  await new Promise((resolve) => {
    const iv = setInterval(() => {
      const t = (performance.now() - t0) / 1000;
      if (signal?.aborted || t >= plan.total) { clearInterval(iv); resolve(); return; }
      // Start each moving shot as its crossfade begins, slowed to fill the shot if needed.
      plan.shots.forEach((s, k) => {
        if (s.clip && !started.has(k) && t >= s.start - plan.fade) {
          started.add(k);
          s.clip.currentTime = 0;
          s.clip.playbackRate = Math.max(0.5, Math.min(1, (s.clip.duration || 5) / (s.dur + plan.fade)));
          s.clip.play().catch(() => {});
        }
      });
      drawFrame(t);
      onProgress?.(t / plan.total);
    }, 1000 / 30);
  });
  rec.stop();
  await done;
  stream.getTracks().forEach((tr) => tr.stop());
  for (const s of plan.shots) s.clip?.pause();
  if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
  const type = (mime || 'video/webm').split(';')[0];
  return { blob: new Blob(chunks, { type }), mime: type, seconds: plan.total };
}

// ---------- Motion (fal.ai) ----------

async function animateShot(image, prompt, { signal, onState } = {}) {
  const { job } = await ownerPost('/api/study', { task: 'motion', image: toJpegDataUri(image), prompt, seconds: 5 }, { signal });
  const t0 = Date.now();
  for (;;) {
    if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
    await new Promise((r) => setTimeout(r, 4000));
    const r = await fetch(`/api/study?motion=${encodeURIComponent(job)}`, { headers: { 'x-mavis-access': accessCode() }, signal, cache: 'no-store' });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.message || `The video service answered ${r.status}.`);
    onState?.(j);
    if (j.status === 'failed') throw new Error(j.error || 'This shot couldn’t be animated.');
    if (j.status === 'done') {
      const v = await fetch(j.url, { signal });
      if (!v.ok) throw new Error('The moving shot couldn’t be downloaded.');
      const el = document.createElement('video');
      el.muted = true; el.playsInline = true; el.preload = 'auto';
      el.src = URL.createObjectURL(await v.blob());
      await new Promise((res, rej) => { el.onloadeddata = res; el.onerror = () => rej(new Error('The moving shot couldn’t be played.')); });
      return el;
    }
    if (Date.now() - t0 > 8 * 60_000) throw new Error('Animating this shot is taking too long.');
  }
}

// ---------- The maker ----------

/**
 * openSceneFilm({ text, source: 'selection'|'page'|'quote'|'verses', title, author, chapter, citation, bookKey, container, defaultStyle, lang })
 */
export async function openSceneFilm(opts) {
  if (!videoSupported()) { toast('This browser can’t record videos. Try Chrome on Android or a computer.', { tone: 'error' }); return null; }
  await loadFeatures();
  const f = features();
  if (!can('assistant') || !f.pictures) {
    toast(f.owner ? 'Scene films need the OpenAI picture service, which isn’t set up.' : 'Scene films use the AI picture service. Enter the owner access code in Settings first.', { tone: 'error' });
    return null;
  }
  const sentences = trimToLimit(opts.text);
  if (sentences.join(' ').length < 40) { toast('Choose a passage with a bit more text to film.'); return null; }
  const passage = sentences.join(' ');
  const trimmed = passage.length < String(opts.text || '').replace(/\s+/g, ' ').trim().length - 3;
  const voiceOk = can('cloudVoice');
  const motionOk = !!f.motion;
  const kids = store.getSetting('kidsMode', false) === true;
  let ctl = null;
  const SOURCE = { selection: 'The passage you selected', page: 'This page', quote: 'Your saved quote', verses: 'The verses you chose' };
  const d = openDialog({
    title: 'Scene film', variant: 'side', className: 'video-maker scene-film', container: opts.container, onClose: () => ctl?.abort(),
    body: html`
      <p class="eyebrow">${SOURCE[opts.source] || 'The passage'}</p>
      <blockquote class="vm-text">${passage.length > 320 ? `${passage.slice(0, 317).replace(/\s+\S*$/, '')}…` : passage}</blockquote>
      <p class="small faint">${opts.citation}${trimmed ? ` · the film covers the first ${sentences.length} sentences (about two minutes)` : ''}</p>
      <div class="field"><label for="sf-style">Look</label><select class="select" id="sf-style">${STYLES.map(([v, l]) => html`<option value="${v}" ${v === (kids ? 'storybook' : opts.defaultStyle || store.getSetting('pictureStyle', 'painterly')) ? 'selected' : ''}>${l}</option>`)}</select></div>
      <div class="field"><label for="sf-shape">Shape</label><select class="select" id="sf-shape">${Object.entries(SHAPES).map(([k, v]) => html`<option value="${k}" ${k === store.getSetting('filmShape', 'wide') ? 'selected' : ''}>${v.label}</option>`)}</select></div>
      <div class="field"><label for="sf-shots">Shots</label><select class="select" id="sf-shots"><option value="0">Let the director decide</option><option value="2">2</option><option value="3">3</option><option value="4">4</option><option value="5">5</option></select></div>
      <label class="consent"><input type="checkbox" id="sf-voice" ${voiceOk ? 'checked' : 'disabled'} /> <span>Narrate it ${voiceOk ? '(your narrator voice)' : '(needs the cloud voice)'}</span></label>
      <label class="consent"><input type="checkbox" id="sf-caps" checked /> <span>Show the words as captions</span></label>
      <label class="consent"><input type="checkbox" id="sf-motion" ${motionOk ? 'checked' : 'disabled'} /> <span>${motionOk ? 'Real motion: bring each shot to life with AI video (fal.ai, about $0.21 a shot; adds a few minutes)' : 'Real motion needs a fal.ai key (FAL_KEY) in Netlify. Without it, the camera moves over the painted shots.'}</span></label>
      <label class="consent"><input type="checkbox" id="sf-wm" checked /> <span>Small “Mavis Library” mark</span></label>
      <button type="button" class="btn btn-primary" data-sf="make">${icon('play', { size: 18 })} Make the film</button>
      <div id="sf-out" aria-live="polite"></div>
      <p class="hint">Painting takes about a minute; the film is then recorded on this device in real time. Keep Mavis open on screen until it’s done.</p>`,
  });
  const $ = (s) => d.body.querySelector(s);

  d.body.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-sf]')?.dataset.sf;
    if (a === 'stop') { ctl?.abort(); return; }
    if (a !== 'make') return;
    const btn = e.target.closest('[data-sf]');
    btn.disabled = true;
    const out = $('#sf-out');
    ctl = new AbortController();
    const signal = ctl.signal;
    const style = $('#sf-style').value, shape = $('#sf-shape').value;
    store.setSetting('pictureStyle', style); store.setSetting('filmShape', shape);
    const wantVoice = $('#sf-voice').checked, wantMotion = $('#sf-motion').checked;
    let wake = null;
    try { wake = await navigator.wakeLock?.request('screen'); } catch { /* optional */ }
    const steps = { plan: 'Planning the shots', paint: 'Painting the shots', voice: wantVoice ? 'Recording the narration' : null, motion: wantMotion ? 'Bringing the shots to life' : null, film: 'Filming' };
    const state = {};
    const thumbs = [];
    const paint = () => {
      out.innerHTML = String(html`<ol class="sf-steps">${Object.entries(steps).filter(([, v]) => v).map(([k, v]) => html`<li class="${state[k]?.done ? 'done' : state[k] ? 'now' : ''}">${state[k]?.done ? icon('check', { size: 16 }) : html`<span class="pulse-dot" aria-hidden="true"></span>`} ${v}${state[k]?.note ? html` <span class="small faint">${state[k].note}</span>` : ''}</li>`)}</ol>
        ${thumbs.length ? html`<div class="sf-thumbs">${thumbs.map((u) => html`<img src="${u}" alt="" />`)}</div>` : ''}
        <div id="sf-live"></div>
        <button type="button" class="btn btn-sm btn-quiet" data-sf="stop">Cancel</button>`);
    };
    const set = (k, v) => { state[k] = { ...(state[k] || {}), ...v }; paint(); };
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    try {
      set('plan', { note: '' });
      const board = await ownerPost('/api/study', { task: 'storyboard', title: opts.title, author: opts.author, chapter: opts.chapter, sentences, shots: Number($('#sf-shots').value) || undefined }, { signal });
      const texts = shotTexts(sentences, board.shots);
      set('plan', { done: true, note: `${board.shots.length} shots` });

      let painted = 0;
      set('paint', { note: `0 of ${board.shots.length}` });
      const pictures = Promise.all(board.shots.map(async (s, i) => {
        const { blob } = await makePicture({ title: opts.title, author: opts.author, chapter: opts.chapter, passage: texts[i], style, scene: s.visual, look: board.look, shape: SHAPES[shape].pic }, { signal });
        const img = await loadImage(blob);
        thumbs[i] = img.src; painted++;
        set('paint', { note: `${painted} of ${board.shots.length}` });
        return { img, blob };
      }));
      const narration = wantVoice ? Promise.all(texts.map(async (t) => {
        const b = await ownerPost('/api/tts', { text: t.slice(0, 1700), speed: 0.95, voice: store.getSetting('narratorVoice', null)?.id || undefined }, { as: 'blob', signal });
        return ac.decodeAudioData(await b.arrayBuffer());
      })).then((x) => { set('voice', { done: true }); return x; }) : Promise.resolve(texts.map(() => null));
      if (wantVoice) set('voice', { note: '' });
      const pics = await pictures;
      set('paint', { done: true, note: `${board.shots.length} shots` });
      // Keep the painted shots in this book's picture gallery.
      if (opts.bookKey) for (const [i, p] of pics.entries()) savePicture(opts.bookKey, { id: store.uuid(), blob: p.blob, style, chapter: opts.chapter || opts.citation, passage: texts[i].slice(0, 300), revised: board.shots[i].visual.slice(0, 400), createdAt: Date.now() }).catch(() => {});

      let clips = board.shots.map(() => null);
      if (wantMotion) {
        let moved = 0, failed = 0;
        set('motion', { note: `0 of ${board.shots.length}` });
        clips = await Promise.all(board.shots.map((s, i) => animateShot(pics[i].img, `${s.motion || 'Gentle natural movement'}. ${s.camera.replace('-', ' ')} camera move, slow and cinematic. ${board.look.slice(0, 300)}`, { signal })
          .then((el) => { moved++; set('motion', { note: `${moved} of ${board.shots.length}${failed ? ` · ${failed} kept still` : ''}` }); return el; })
          .catch((err) => { if (err.name === 'AbortError') throw err; failed++; set('motion', { note: `${moved} of ${board.shots.length} · ${failed} kept still` }); return null; })));
        set('motion', { done: true });
      }
      const audio = await narration;

      set('film', { note: '0%' });
      const canvas = document.createElement('canvas');
      canvas.className = 'sf-canvas';
      $('#sf-live').append(canvas);
      const res = await renderFilm({
        shots: board.shots.map((s, i) => ({ image: pics[i].img, clip: clips[i], audio: audio[i], text: texts[i], camera: s.camera, effect: s.effect })),
        shape, title: opts.title, subtitle: opts.chapter && opts.chapter !== opts.title ? opts.chapter : '', citation: opts.citation,
        captions: $('#sf-caps').checked, watermark: $('#sf-wm').checked, ac, canvas, signal,
        onProgress: (p) => { const li = out.querySelector('.sf-steps li.now .faint'); if (li) li.textContent = `${Math.round(p * 100)}%`; },
      });
      const url = URL.createObjectURL(res.blob);
      const ext = res.mime.includes('mp4') ? 'mp4' : 'webm';
      const name = `${(opts.citation || opts.title || 'scene').replace(/[^\w-]+/g, '-').replace(/^-|-$/g, '').slice(0, 48) || 'scene'}.${ext}`;
      out.innerHTML = String(html`<video class="vm-preview" src="${url}" controls playsinline></video>
        <div class="dialog-actions" style="justify-content:flex-start">
          <button type="button" class="btn btn-sm btn-primary" data-sf="share">${icon('share', { size: 18 })} Share</button>
          <a class="btn btn-sm" href="${url}" download="${name}">${icon('download', { size: 18 })} Save video</a>
          <button type="button" class="btn btn-sm btn-quiet" data-sf="make">${icon('refresh', { size: 18 })} Make another take</button>
        </div>
        <p class="small faint">${Math.round(res.seconds)} seconds · ${board.shots.length} shots${clips.some(Boolean) ? ` (${clips.filter(Boolean).length} moving)` : ''} · ${ext.toUpperCase()} · ${(res.blob.size / 1048576).toFixed(1)} MB. The painted shots are also in this book’s pictures.</p>`);
      out.querySelector('[data-sf="share"]').onclick = async () => {
        const file = new File([res.blob], name, { type: res.mime });
        try {
          if (navigator.canShare?.({ files: [file] })) await navigator.share({ files: [file], text: opts.citation });
          else toast('Sharing videos isn’t supported here. Use “Save video” instead.');
        } catch { /* cancelled */ }
      };
    } catch (err) {
      out.innerHTML = err.name === 'AbortError' ? '' : String(html`<p class="form-error" role="alert">${err.message}</p>`);
    } finally {
      ctl = null;
      btn.disabled = false;
      wake?.release?.().catch(() => {});
      setTimeout(() => ac.close().catch(() => {}), 500);
    }
  });
  return d;
}
