// Verse and quote videos, made on the device: a canvas is animated frame by
// frame (slow camera move over the background, words appearing as they're
// spoken, the reference), mixed with the cloud-voice narration, and recorded
// with MediaRecorder into an MP4 (or WebM) ready to share.

import { html, icon, toast, openDialog } from './ui.js';
import * as store from './store.js';
import { loadFeatures, can, features, ownerPost } from './features.js';
import { makePicture, listPictures } from './companion.js';

const FORMATS = {
  story: { label: 'Vertical (Stories, Reels, Status)', w: 1080, h: 1920 },
  square: { label: 'Square (feed posts)', w: 1080, h: 1080 },
  wide: { label: 'Wide (church screens, YouTube)', w: 1920, h: 1080 },
};
const THEMES = {
  dawn: { label: 'Dawn', stops: ['#1d2b53', '#7e2553', '#ff9e5e'], ink: '#fff8ee' },
  sea: { label: 'Sea', stops: ['#062a3a', '#0f5c6e', '#79c2b8'], ink: '#f2fbf8' },
  forest: { label: 'Forest', stops: ['#0d1f16', '#24543a', '#9cbf7a'], ink: '#f6f3e6' },
  parchment: { label: 'Parchment', stops: ['#f6ecd6', '#ead8b1', '#d8bf8c'], ink: '#2b2116' },
  night: { label: 'Night', stops: ['#05060d', '#141a3a', '#3b3f7a'], ink: '#f4f1ff' },
};

export const videoSupported = () => typeof MediaRecorder !== 'undefined' && !!HTMLCanvasElement.prototype.captureStream;

function pickMime() {
  const opts = ['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
  return opts.find((t) => MediaRecorder.isTypeSupported?.(t)) || '';
}

/** Break text into lines that fit `maxWidth` at the current ctx.font. */
function wrap(ctx, text, maxWidth) {
  const words = String(text).split(/\s+/).filter(Boolean);
  const lines = [];
  let line = [];
  for (const w of words) {
    const test = [...line, w].join(' ');
    if (line.length && ctx.measureText(test).width > maxWidth) { lines.push(line); line = [w]; }
    else line.push(w);
  }
  if (line.length) lines.push(line);
  return lines;
}

/** Choose the largest font size that fits the text in the box. */
function fitText(ctx, text, family, box) {
  for (let size = Math.round(box.h / 5); size >= 26; size -= 2) {
    ctx.font = `500 ${size}px ${family}`;
    const lines = wrap(ctx, text, box.w);
    if (lines.length * size * 1.32 <= box.h) return { size, lines };
  }
  ctx.font = `500 26px ${family}`;
  return { size: 26, lines: wrap(ctx, text, box.w) };
}

async function loadImage(blob) {
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } finally { setTimeout(() => URL.revokeObjectURL(url), 2000); }
}

/**
 * Record the video. Returns { blob, mime, seconds }.
 * opts: { text, citation, format, theme, image (HTMLImageElement|null), narration (Blob|null), watermark, onProgress, signal }
 */
export async function renderVideo({ text, citation, format = 'story', theme = 'dawn', image = null, narration = null, watermark = true, onProgress, signal }) {
  const F = FORMATS[format] || FORMATS.story;
  const T = THEMES[theme] || THEMES.dawn;
  const canvas = document.createElement('canvas');
  canvas.width = F.w; canvas.height = F.h;
  const ctx = canvas.getContext('2d');
  const family = "'Mavis Fraunces', 'Mavis Literata', Georgia, serif";
  try { await Promise.all([document.fonts.load(`500 64px 'Mavis Fraunces'`), document.fonts.load(`500 40px 'Mavis Atkinson'`)]); } catch { /* fall back to Georgia */ }

  // Audio: decode the narration so we know its length, then play it into the recording.
  const ac = new (window.AudioContext || window.webkitAudioContext)();
  const dest = ac.createMediaStreamDestination();
  let buffer = null;
  if (narration) {
    try { buffer = await ac.decodeAudioData(await narration.arrayBuffer()); } catch { buffer = null; }
  }
  const words = String(text).split(/\s+/).filter(Boolean).length;
  const speech = buffer ? buffer.duration : Math.min(28, 2.5 + words * 0.38);
  const lead = 0.9, tail = 1.6;
  const total = lead + speech + tail;

  // Layout.
  const pad = Math.round(F.w * 0.09);
  const box = { x: pad, y: F.h * (format === 'wide' ? 0.16 : 0.22), w: F.w - pad * 2, h: F.h * (format === 'wide' ? 0.56 : 0.5) };
  const { size, lines } = fitText(ctx, text, family, box);
  const lineH = size * 1.32;
  const blockH = lines.length * lineH;
  const top = box.y + (box.h - blockH) / 2;
  const totalChars = String(text).length || 1;

  const drawBackground = (t) => {
    const p = t / total;
    if (image) {
      const scale = Math.max(F.w / image.width, F.h / image.height) * (1.08 + 0.1 * p);
      const iw = image.width * scale, ih = image.height * scale;
      const x = (F.w - iw) / 2 - (iw - F.w) * 0.15 * (p - 0.5);
      const y = (F.h - ih) / 2 - (ih - F.h) * 0.2 * (p - 0.5);
      ctx.drawImage(image, x, y, iw, ih);
      const g = ctx.createLinearGradient(0, 0, 0, F.h);
      g.addColorStop(0, 'rgba(0,0,0,0.25)'); g.addColorStop(0.5, 'rgba(0,0,0,0.45)'); g.addColorStop(1, 'rgba(0,0,0,0.7)');
      ctx.fillStyle = g; ctx.fillRect(0, 0, F.w, F.h);
    } else {
      const a = 0.35 + 0.25 * Math.sin(p * Math.PI);
      const g = ctx.createLinearGradient(F.w * (0.2 - 0.2 * p), 0, F.w * (0.8 + 0.2 * p), F.h);
      T.stops.forEach((c, i) => g.addColorStop(i / (T.stops.length - 1), c));
      ctx.fillStyle = g; ctx.fillRect(0, 0, F.w, F.h);
      const r = ctx.createRadialGradient(F.w * (0.3 + 0.4 * p), F.h * 0.3, 10, F.w * 0.5, F.h * 0.4, F.h * 0.8);
      r.addColorStop(0, `rgba(255,255,255,${a * 0.35})`); r.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = r; ctx.fillRect(0, 0, F.w, F.h);
    }
  };

  const drawFrame = (t) => {
    drawBackground(t);
    const ink = image ? '#fffaf0' : T.ink;
    // Words fade in as they're spoken (estimated by character position).
    const spoken = Math.max(0, Math.min(1, (t - lead) / speech));
    let charPos = 0;
    ctx.textBaseline = 'alphabetic';
    ctx.font = `500 ${size}px ${family}`;
    ctx.shadowColor = image ? 'rgba(0,0,0,0.55)' : 'rgba(0,0,0,0.0)';
    ctx.shadowBlur = image ? 18 : 0;
    lines.forEach((line, li) => {
      const lineText = line.join(' ');
      const width = ctx.measureText(lineText).width;
      let x = (F.w - width) / 2;
      const y = top + li * lineH + size;
      for (const w of line) {
        const start = charPos / totalChars;
        charPos += w.length + 1;
        const alpha = Math.max(0.18, Math.min(1, (spoken - start) * 6 + 0.18));
        ctx.globalAlpha = t < lead * 0.6 ? Math.min(0.18, t) : alpha;
        ctx.fillStyle = ink;
        ctx.fillText(w, x, y);
        x += ctx.measureText(`${w} `).width;
      }
    });
    ctx.globalAlpha = Math.min(1, Math.max(0, (t - 0.3) / 0.8));
    ctx.shadowBlur = 0;
    ctx.font = `600 ${Math.round(size * 0.48)}px 'Mavis Atkinson', system-ui, sans-serif`;
    ctx.fillStyle = image ? '#f3c97a' : (theme === 'parchment' ? '#8a5a1c' : '#f3c97a');
    const cite = `— ${citation}`;
    ctx.fillText(cite, (F.w - ctx.measureText(cite).width) / 2, top + blockH + size * 1.4);
    if (watermark) {
      ctx.globalAlpha = 0.55;
      ctx.font = `500 ${Math.round(F.w * 0.022)}px 'Mavis Atkinson', system-ui, sans-serif`;
      ctx.fillStyle = ink;
      const wm = 'Mavis Library';
      ctx.fillText(wm, F.w - pad - ctx.measureText(wm).width, F.h - pad * 0.6);
    }
    ctx.globalAlpha = 1;
  };

  drawFrame(0);
  const stream = canvas.captureStream(30);
  for (const tr of dest.stream.getAudioTracks()) stream.addTrack(tr);
  const mime = pickMime();
  const rec = new MediaRecorder(stream, { ...(mime ? { mimeType: mime } : {}), videoBitsPerSecond: 6_000_000, audioBitsPerSecond: 128_000 });
  const chunks = [];
  rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
  const done = new Promise((resolve, reject) => { rec.onstop = resolve; rec.onerror = (e) => reject(e.error || new Error('Recording failed.')); });

  if (ac.state === 'suspended') await ac.resume().catch(() => {});
  rec.start(500);
  const t0 = performance.now();
  if (buffer) {
    const src = ac.createBufferSource();
    src.buffer = buffer;
    src.connect(dest);
    src.start(ac.currentTime + lead);
  }
  await new Promise((resolve) => {
    // setInterval keeps drawing even if the browser throttles animation frames.
    const iv = setInterval(() => {
      const t = (performance.now() - t0) / 1000;
      if (signal?.aborted || t >= total) { clearInterval(iv); resolve(); return; }
      drawFrame(t);
      onProgress?.(t / total);
    }, 1000 / 30);
  });
  rec.stop();
  await done;
  stream.getTracks().forEach((tr) => tr.stop());
  ac.close().catch(() => {});
  if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
  const type = (mime || 'video/webm').split(';')[0];
  return { blob: new Blob(chunks, { type }), mime: type, seconds: total };
}

/**
 * The video maker dialog.
 * { text, citation, bookKey, title, defaultStyle }
 */
export async function openVideoMaker({ text, citation, bookKey = '', title = '', defaultStyle = 'painterly' }) {
  if (!videoSupported()) { toast('This browser can’t make videos. Try Chrome on Android or a computer.', { tone: 'error' }); return; }
  await loadFeatures();
  const f = features();
  const voiceOk = can('cloudVoice');
  const picOk = can('assistant') && !!f.pictures;
  const gallery = bookKey ? await listPictures(bookKey).catch(() => []) : [];
  const short = text.length > 600 ? `${text.slice(0, 597).replace(/\s+\S*$/, '')}…` : text;
  let ctl = null;
  const d = openDialog({
    title: 'Make a video', variant: 'side', className: 'video-maker', onClose: () => ctl?.abort(),
    body: html`
      <blockquote class="vm-text">${short}</blockquote>
      <p class="small faint">— ${citation}</p>
      <div class="field"><label for="vm-format">Shape</label><select class="select" id="vm-format">${Object.entries(FORMATS).map(([k, v]) => html`<option value="${k}">${v.label}</option>`)}</select></div>
      <div class="field"><label for="vm-bg">Background</label><select class="select" id="vm-bg">
        ${Object.entries(THEMES).map(([k, v]) => html`<option value="theme:${k}">${v.label} colors</option>`)}
        ${gallery.length ? html`<option value="gallery">A picture from this book’s gallery</option>` : ''}
        ${picOk ? html`<option value="ai">A new AI picture of this passage (about 30 s)</option>` : ''}
      </select></div>
      <label class="consent"><input type="checkbox" id="vm-voice" ${voiceOk ? 'checked' : 'disabled'} /> <span>Read it aloud in the video ${voiceOk ? '(your narrator voice)' : '(needs the cloud voice)'}</span></label>
      <label class="consent"><input type="checkbox" id="vm-wm" checked /> <span>Small “Mavis Library” mark in the corner</span></label>
      <button type="button" class="btn btn-primary" data-vm="make">${icon('present', { size: 18 })} Make video</button>
      <div id="vm-out" aria-live="polite"></div>
      <p class="hint">The video is made on this device; keep Mavis open on screen while it records.</p>`,
  });
  const $ = (s) => d.body.querySelector(s);
  d.body.addEventListener('click', async (e) => {
    const a = e.target.closest('[data-vm]')?.dataset.vm;
    if (a === 'stop') ctl?.abort();
    if (a !== 'make') return;
    const btn = e.target.closest('[data-vm]');
    btn.disabled = true;
    const out = $('#vm-out');
    ctl = new AbortController();
    const step = (msg, pct = null) => { out.innerHTML = String(html`<div class="cp-wait"><div class="pulse-dot" aria-hidden="true"></div><p>${msg}${pct != null ? html` <span class="num">${pct}%</span>` : ''}</p><button type="button" class="btn btn-sm btn-quiet" data-vm="stop">Cancel</button></div>`); };
    try {
      const bg = $('#vm-bg').value;
      let image = null;
      if (bg === 'gallery') image = await loadImage(gallery[0].blob);
      if (bg === 'ai') {
        step('Painting the background…');
        const { blob } = await makePicture({ title, author: '', chapter: citation, passage: text.slice(0, 2000), style: defaultStyle }, { signal: ctl.signal });
        image = await loadImage(blob);
      }
      let narration = null;
      if ($('#vm-voice').checked) {
        step('Recording the narration…');
        const spoken = `${short.replace(/…$/, '')}. ${citation.replace(/\(([^)]+)\)$/, '').replace(/:/g, ', ').trim()}.`;
        narration = await ownerPost('/api/tts', { text: spoken.slice(0, 1700), speed: 0.95, voice: store.getSetting('narratorVoice', null)?.id || undefined }, { as: 'blob', signal: ctl.signal });
      }
      step('Making your video…', 0);
      const theme = bg.startsWith('theme:') ? bg.slice(6) : 'night';
      const res = await renderVideo({
        text: short, citation, format: $('#vm-format').value, theme, image, narration, watermark: $('#vm-wm').checked, signal: ctl.signal,
        onProgress: (p) => { const el = out.querySelector('.num'); if (el) el.textContent = `${Math.round(p * 100)}%`; },
      });
      const url = URL.createObjectURL(res.blob);
      const ext = res.mime.includes('mp4') ? 'mp4' : 'webm';
      out.innerHTML = String(html`<video class="vm-preview" src="${url}" controls playsinline></video>
        <div class="dialog-actions" style="justify-content:flex-start">
          <button type="button" class="btn btn-sm btn-primary" data-vm="share">${icon('share', { size: 18 })} Share</button>
          <a class="btn btn-sm" href="${url}" download="${citation.replace(/[^\w-]+/g, '-').slice(0, 40)}.${ext}">${icon('download', { size: 18 })} Save video</a>
        </div><p class="small faint">${Math.round(res.seconds)} seconds · ${ext.toUpperCase()} · ${(res.blob.size / 1048576).toFixed(1)} MB</p>`);
      out.querySelector('[data-vm="share"]').onclick = async () => {
        const file = new File([res.blob], `${citation.replace(/[^\w-]+/g, '-').slice(0, 40)}.${ext}`, { type: res.mime });
        try {
          if (navigator.canShare?.({ files: [file] })) await navigator.share({ files: [file], text: `${short} — ${citation}` });
          else toast('Sharing videos isn’t supported here. Use “Save video” instead.');
        } catch { /* cancelled */ }
      };
    } catch (err) {
      out.innerHTML = err.name === 'AbortError' ? '' : String(html`<p class="form-error" role="alert">${err.message}</p>`);
    } finally {
      ctl = null;
      btn.disabled = false;
    }
  });
}
