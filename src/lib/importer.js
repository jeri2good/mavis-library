// File import: validates EPUB, plain text, and PDF files before anything is
// stored. Plain text is converted into a small EPUB so every reader feature
// (pagination, bookmarks, highlights, read-aloud) works on it.

import JSZip from 'jszip';
import { esc } from './ui.js';

export const LIMITS = { epub: 60 * 1024 * 1024, txt: 10 * 1024 * 1024, pdf: 100 * 1024 * 1024 };
export const ACCEPT = '.epub,.txt,.text,.pdf,application/epub+zip,text/plain,application/pdf';

export class ImportError extends Error {}

function ext(name) { return (/\.([a-z0-9]+)$/i.exec(name || '')?.[1] || '').toLowerCase(); }
function baseName(name) { return (name || 'Untitled').replace(/\.[a-z0-9]+$/i, '').replace(/[_]+/g, ' ').trim() || 'Untitled'; }
const mb = (n) => `${Math.round(n / 1024 / 1024)} MB`;

async function head(file, n) { return new Uint8Array(await file.slice(0, n).arrayBuffer()); }

export async function inspectFile(file) {
  const e = ext(file.name);
  const h = await head(file, 8);
  const isZip = h[0] === 0x50 && h[1] === 0x4b && h[2] === 0x03 && h[3] === 0x04;
  const isPdf = h[0] === 0x25 && h[1] === 0x50 && h[2] === 0x44 && h[3] === 0x46; // %PDF
  if (file.size === 0) throw new ImportError(`“${file.name}” is empty.`);

  if (e === 'epub' || file.type === 'application/epub+zip' || (isZip && e !== 'pdf' && e !== 'txt')) {
    if (file.size > LIMITS.epub) throw new ImportError(`“${file.name}” is ${mb(file.size)}. EPUB imports are limited to ${mb(LIMITS.epub)}.`);
    if (!isZip) throw new ImportError(`“${file.name}” is not a valid EPUB (it isn't a ZIP container).`);
    return importEpub(file);
  }
  if (e === 'pdf' || file.type === 'application/pdf' || isPdf) {
    if (file.size > LIMITS.pdf) throw new ImportError(`“${file.name}” is ${mb(file.size)}. PDF imports are limited to ${mb(LIMITS.pdf)}.`);
    if (!isPdf) throw new ImportError(`“${file.name}” is not a valid PDF.`);
    return { format: 'pdf', blob: file.slice(0, file.size, 'application/pdf'), mime: 'application/pdf', title: baseName(file.name), authors: [], languages: [], cover: null };
  }
  if (e === 'txt' || e === 'text' || file.type.startsWith('text/plain')) {
    if (file.size > LIMITS.txt) throw new ImportError(`“${file.name}” is ${mb(file.size)}. Text imports are limited to ${mb(LIMITS.txt)}.`);
    return importText(file);
  }
  throw new ImportError(`“${file.name}” isn't a supported book file. Mavis can import EPUB, plain text (.txt), and PDF.`);
}

async function importEpub(file) {
  let zip;
  try { zip = await JSZip.loadAsync(file); }
  catch { throw new ImportError(`“${file.name}” could not be opened. The EPUB may be damaged.`); }
  const container = zip.file('META-INF/container.xml');
  if (!container) throw new ImportError(`“${file.name}” is missing its EPUB container file, so it can't be read.`);
  const containerXml = await container.async('string');
  const doc = new DOMParser().parseFromString(containerXml, 'application/xml');
  const opfPath = doc.querySelector('rootfile')?.getAttribute('full-path');
  const opfFile = opfPath && zip.file(opfPath);
  if (!opfFile) throw new ImportError(`“${file.name}” has no package document, so it can't be read.`);
  const opf = new DOMParser().parseFromString(await opfFile.async('string'), 'application/xml');
  if (opf.querySelector('parsererror')) throw new ImportError(`“${file.name}” has a malformed package document.`);
  const encrypted = zip.file('META-INF/encryption.xml');
  if (encrypted) {
    const encXml = await encrypted.async('string');
    // Font obfuscation is allowed; anything else means DRM-protected content.
    const algos = [...encXml.matchAll(/Algorithm="([^"]+)"/g)].map((m) => m[1]);
    const onlyFonts = algos.every((a) => /idpf\.org\/2008\/embedding|ns\.adobe\.com\/pdf\/enc#RC/.test(a));
    if (!onlyFonts) throw new ImportError(`“${file.name}” is DRM-protected. Open it in the app you bought or borrowed it from; Mavis doesn't remove DRM.`);
  }
  const text = (sel) => opf.getElementsByTagNameNS('*', sel)[0]?.textContent?.trim() || '';
  const creators = [...opf.getElementsByTagNameNS('*', 'creator')].map((n) => n.textContent.trim()).filter(Boolean).slice(0, 6);
  const title = text('title') || baseName(file.name);
  const language = text('language');
  let cover = null;
  try { cover = await extractCover(zip, opf, opfPath); } catch { /* covers are optional */ }
  return {
    format: 'epub',
    blob: file.slice(0, file.size, 'application/epub+zip'),
    mime: 'application/epub+zip',
    title: title.slice(0, 300),
    authors: creators,
    languages: language ? [language.slice(0, 2).toLowerCase()] : [],
    cover,
  };
}

async function extractCover(zip, opf, opfPath) {
  const dir = opfPath.includes('/') ? opfPath.slice(0, opfPath.lastIndexOf('/') + 1) : '';
  const items = [...opf.getElementsByTagNameNS('*', 'item')];
  let item = items.find((i) => (i.getAttribute('properties') || '').split(/\s+/).includes('cover-image'));
  if (!item) {
    const metaCover = [...opf.getElementsByTagNameNS('*', 'meta')].find((m) => m.getAttribute('name') === 'cover')?.getAttribute('content');
    item = items.find((i) => i.getAttribute('id') === metaCover);
  }
  if (!item || !/^image\/(jpeg|png|gif|webp)$/.test(item.getAttribute('media-type') || '')) return null;
  const path = decodeURIComponent(new URL(item.getAttribute('href'), `https://x/${dir}`).pathname.slice(1));
  const f = zip.file(path);
  if (!f) return null;
  const blob = new Blob([await f.async('uint8array')], { type: item.getAttribute('media-type') });
  return thumbnail(blob);
}

/** Downscale an image blob to a small JPEG data URL for shelf display. */
export async function thumbnail(blob, maxW = 360) {
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    if (img.naturalWidth < 40 || img.naturalHeight < 40) return null; // placeholder pixels, not a real cover
    const scale = Math.min(1, maxW / img.naturalWidth);
    const c = document.createElement('canvas');
    c.width = Math.round(img.naturalWidth * scale);
    c.height = Math.round(img.naturalHeight * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', 0.82);
  } finally {
    URL.revokeObjectURL(url);
  }
}

async function importText(file) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let nul = 0;
  for (let i = 0; i < Math.min(bytes.length, 4096); i++) if (bytes[i] === 0) nul++;
  if (nul > 4) throw new ImportError(`“${file.name}” looks like a binary file, not plain text.`);
  let text = new TextDecoder('utf-8', { fatal: false }).decode(bytes);
  if (text.includes('�') && text.split('�').length > 20) {
    text = new TextDecoder('windows-1252').decode(bytes); // common for older .txt books
  }
  text = text.replace(/\r\n?/g, '\n').replace(/^﻿/, '');
  if (!text.trim()) throw new ImportError(`“${file.name}” has no readable text.`);
  const { title, chapters } = splitText(text, baseName(file.name));
  const blob = await buildEpub({ title, chapters });
  return { format: 'epub', blob, mime: 'application/epub+zip', title, authors: [], languages: [], cover: null, converted: 'txt' };
}

const HEADING = /^(?:(?:chapter|book|part|section|canto|letter)\s+(?:[0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)\b.*|prologue|epilogue|preface|introduction)\s*$/i;

export function splitText(text, fallbackTitle) {
  const lines = text.split('\n');
  const firstLine = lines.find((l) => l.trim())?.trim() || fallbackTitle;
  const title = firstLine.length <= 120 ? firstLine : fallbackTitle;
  const chapters = [];
  let cur = { title: 'Opening', lines: [] };
  for (const line of lines) {
    const t = line.trim();
    if (t && t.length < 90 && HEADING.test(t)) {
      if (cur.lines.join('').trim()) chapters.push(cur);
      cur = { title: t, lines: [] };
    } else {
      cur.lines.push(line);
    }
  }
  if (cur.lines.join('').trim()) chapters.push(cur);
  // No headings found: split long text into parts of roughly 3,000 words.
  if (chapters.length <= 1) {
    const paras = toParagraphs((chapters[0] || cur).lines.join('\n'));
    const parts = [];
    let bucket = [], words = 0;
    for (const p of paras) {
      bucket.push(p); words += p.split(/\s+/).length;
      if (words > 3000) { parts.push(bucket); bucket = []; words = 0; }
    }
    if (bucket.length) parts.push(bucket);
    return { title, chapters: parts.map((ps, i) => ({ title: parts.length > 1 ? `Part ${i + 1}` : title, paragraphs: ps })) };
  }
  return { title, chapters: chapters.map((c) => ({ title: c.title, paragraphs: toParagraphs(c.lines.join('\n')) })) };
}

function toParagraphs(s) {
  // Blank lines separate paragraphs; hard-wrapped lines inside are joined.
  return s.split(/\n\s*\n/).map((p) => p.replace(/\s*\n\s*/g, ' ').trim()).filter(Boolean);
}

export async function buildEpub({ title, chapters, author = '' }) {
  const zip = new JSZip();
  const id = crypto.randomUUID ? crypto.randomUUID() : String(Date.now());
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>');
  const page = (t, body) => `<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en"><head><title>${esc(t)}</title><style>p{margin:0 0 .2em;text-indent:1.3em}h2{margin:1.2em 0 .8em}</style></head><body>${body}</body></html>`;
  chapters.forEach((c, i) => {
    zip.file(`OEBPS/c${i}.xhtml`, page(c.title, `<h2>${esc(c.title)}</h2>${c.paragraphs.map((p) => `<p>${esc(p)}</p>`).join('\n')}`));
  });
  zip.file('OEBPS/nav.xhtml', page('Contents', `<nav epub:type="toc"><ol>${chapters.map((c, i) => `<li><a href="c${i}.xhtml">${esc(c.title)}</a></li>`).join('')}</ol></nav>`));
  zip.file('OEBPS/content.opf', `<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:${id}</dc:identifier><dc:title>${esc(title)}</dc:title>${author ? `<dc:creator>${esc(author)}</dc:creator>` : ''}<dc:language>en</dc:language><meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d+Z$/, 'Z')}</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${chapters.map((_, i) => `<item id="c${i}" href="c${i}.xhtml" media-type="application/xhtml+xml"/>`).join('')}</manifest><spine>${chapters.map((_, i) => `<itemref idref="c${i}"/>`).join('')}</spine></package>`);
  return zip.generateAsync({ type: 'blob', mimeType: 'application/epub+zip' });
}
