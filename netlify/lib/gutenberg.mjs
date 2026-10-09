// Project Gutenberg's own OPDS catalog (www.gutenberg.org/ebooks/*.opds).
// Used because the Gutendex API blocks requests from Netlify's servers.
// Parses the small, regular Atom feeds Gutenberg publishes for reading apps.

import { upstream, clean } from './shared.mjs';

const BASE = 'https://www.gutenberg.org';
export const OPDS_PAGE_SIZE = 25;

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
export function decode(s) {
  return String(s || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => safeChar(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => safeChar(Number(d)))
    .replace(/&([a-z]+);/gi, (m, n) => ENTITIES[n.toLowerCase()] ?? m);
}
function safeChar(n) { try { return String.fromCodePoint(n); } catch { return ''; } }
const text = (s) => decode(String(s || '').replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
const tag = (xml, name) => (xml.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`)) || [])[1];
const attr = (el, name) => decode((el.match(new RegExp(`\\s${name}="([^"]*)"`)) || [])[1] || '');

export const coverFor = (id) => `${BASE}/cache/epub/${id}/pg${id}.cover.medium.jpg`;

function entries(xml) {
  return String(xml || '').split('<entry').slice(1).map((e) => `<entry${e.split('</entry>')[0]}`);
}

/** Book id from an entry's <id> (search feeds: .../ebooks/123.opds; book feeds: urn:gutenberg:123:2). */
function entryId(e) {
  const id = tag(e, 'id') || '';
  const m = id.match(/ebooks\/(\d{1,6})\.opds/) || id.match(/^urn:gutenberg:(\d{1,6})(?::|$)/);
  return m ? Number(m[1]) : null;
}

/** Map Mavis query options onto Gutenberg's search syntax. */
export function buildQuery({ search = '', topic = '', languages = '' }) {
  const parts = [];
  if (search) parts.push(search);
  if (topic) parts.push(`s.${topic}`);
  const langs = String(languages || '').split(',').filter(Boolean);
  if (langs.length === 1) parts.push(`l.${langs[0]}`);
  return parts.join(' ').trim();
}

const SORTS = { popular: 'downloads', ascending: 'title', descending: 'release_date' };

export function searchUrl({ query, sort = 'popular', page = 1 }) {
  const p = new URLSearchParams();
  if (query) p.set('query', query);
  p.set('sort_order', SORTS[sort] || 'downloads');
  if (page > 1) p.set('start_index', String(1 + OPDS_PAGE_SIZE * (page - 1)));
  return `${BASE}/ebooks/search.opds/?${p}`;
}

// Search feeds mark non-English titles with a trailing "(French)" etc.
const LANG_NAMES = {
  english: 'en', french: 'fr', german: 'de', spanish: 'es', italian: 'it', portuguese: 'pt', dutch: 'nl',
  finnish: 'fi', swedish: 'sv', danish: 'da', norwegian: 'no', latin: 'la', greek: 'el', chinese: 'zh',
  japanese: 'ja', russian: 'ru', polish: 'pl', hungarian: 'hu', esperanto: 'eo', tagalog: 'tl', catalan: 'ca',
  czech: 'cs', welsh: 'cy', irish: 'ga', icelandic: 'is', hebrew: 'he', arabic: 'ar', romanian: 'ro',
};
export function splitLanguage(title) {
  const m = String(title || '').match(/^(.*\S)\s+\(([A-Z][a-z]+(?:\s+(?:and|&)\s+[A-Z][a-z]+)?)\)$/);
  if (!m) return { title, languages: [] };
  const codes = m[2].split(/\s+(?:and|&)\s+/).map((n) => LANG_NAMES[n.toLowerCase()]).filter(Boolean);
  return codes.length ? { title: m[1], languages: codes } : { title, languages: [] };
}

/** Parse a search feed into the shape /api/catalog returns. */
export function parseSearch(xml, { language = '' } = {}) {
  const results = [];
  for (const e of entries(xml)) {
    const id = entryId(e);
    if (!id) continue; // facet entries ("Authors", "Subjects", …)
    // The line under the title is the author — or, for books with no author, the download count.
    const sub = text(tag(e, 'content'));
    const author = /^\d[\d,.]*\s+downloads?$/i.test(sub) ? '' : sub;
    const t = splitLanguage(text(tag(e, 'title')));
    const book = listBook(id, t.title, author ? [author] : []);
    book.languages = language ? [language] : t.languages.length ? t.languages : ['en'];
    results.push(book);
  }
  return { results, hasNext: /<link[^>]*rel="next"/.test(xml) };
}

function listBook(id, title, authorNames) {
  return {
    id,
    title: clean(title, 400),
    authors: authorNames.slice(0, 6).map((name) => ({ name: clean(name, 160), birthYear: null, deathYear: null })),
    translators: [],
    subjects: [],
    bookshelves: [],
    languages: [],
    copyright: null, // confirmed from the book's own record before any download
    mediaType: 'Text',
    downloads: null,
    cover: coverFor(id),
    epub: true,
    epubUrl: `${BASE}/ebooks/${id}.epub3.images`,
    htmlUrl: `${BASE}/ebooks/${id}.html.images`,
    sourceUrl: `${BASE}/ebooks/${id}`,
  };
}

/** Parse a single book's feed (/ebooks/ID.opds). Returns null when the id is not a book. */
export function parseBook(xml, wantId) {
  const e = entries(xml).find((x) => entryId(x) === wantId) || entries(xml)[0];
  if (!e) return null;
  const id = entryId(e) || wantId;
  const fields = {};
  const content = tag(e, 'content') || '';
  for (const p of content.split(/<\/p>/i)) {
    const line = text(p);
    const m = line.match(/^([A-Za-z][A-Za-z .]{0,24}?):\s*([\s\S]*)$/);
    if (!m) continue;
    const key = m[1].trim().toLowerCase();
    (fields[key] ||= []).push(m[2].trim());
  }
  const rights = text(tag(e, 'rights')) || (fields.rights || [])[0] || '';
  const authors = [...e.matchAll(/<author>([\s\S]*?)<\/author>/g)].map((a) => text(tag(a[1], 'name'))).filter(Boolean);
  const subjects = [...e.matchAll(/<category\b[^>]*>/g)]
    .filter((c) => /LCSH/.test(attr(c[0], 'scheme')))
    .map((c) => clean(attr(c[0], 'term'), 200));
  const languages = [...e.matchAll(/<dcterms:language>([\s\S]*?)<\/dcterms:language>/g)].map((l) => text(l[1]).slice(0, 5));
  const links = [...e.matchAll(/<link\b[^>]*>/g)].map((l) => ({ rel: attr(l[0], 'rel'), type: attr(l[0], 'type'), href: attr(l[0], 'href') }));
  const epubLink = links.find((l) => l.type.startsWith('application/epub+zip') && /images/.test(l.href) && !/noimages/.test(l.href))
    || links.find((l) => l.type.startsWith('application/epub+zip'));
  const coverLink = links.find((l) => l.rel === 'http://opds-spec.org/image' && l.type.startsWith('image/'));
  const downloads = Number(String((fields.downloads || [])[0] || '').replace(/[^\d]/g, ''));
  const summary = (fields.summary || [])[0]?.replace(/\s*\(This is an automatically generated summary\.?\)\s*$/i, '') || null;
  const book = listBook(id, text(tag(e, 'title')) || (fields.title || [])[0] || '', authors.length ? authors : (fields.author || []).map((a) => a.replace(/,\s*\d{3,4}\??-\d{0,4}\??$/, '')));
  return {
    ...book,
    subjects: subjects.slice(0, 12),
    languages: languages.slice(0, 6),
    summary: summary ? clean(summary, 3000) : null,
    copyright: /public domain in the usa/i.test(rights) ? false : rights ? true : null,
    rights: clean(rights, 200) || null,
    downloads: Number.isFinite(downloads) && downloads > 0 ? downloads : null,
    cover: safeHttps(coverLink?.href) || book.cover,
    epub: !!epubLink,
    epubUrl: epubLink ? safeHttps(epubLink.href) : null,
  };
}

function safeHttps(u) {
  if (!u) return null;
  try {
    const url = new URL(u, BASE);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href.replace(/^http:/, 'https:') : null;
  } catch { return null; }
}

export async function fetchSearch(opts, timeoutMs = 8000) {
  const res = await upstream(searchUrl(opts), { timeoutMs, accept: 'application/atom+xml' });
  if (!res.ok) throw Object.assign(new Error(`The Gutenberg catalog answered ${res.status}.`), { status: 502 });
  return parseSearch(await res.text(), { language: opts.language || '' });
}

export async function fetchBook(id, timeoutMs = 8000) {
  const res = await upstream(`${BASE}/ebooks/${id}.opds`, { timeoutMs, accept: 'application/atom+xml' });
  if (res.status === 404) return null;
  if (!res.ok) throw Object.assign(new Error(`The Gutenberg catalog answered ${res.status}.`), { status: 502 });
  const xml = await res.text();
  if (!/<entry/.test(xml)) return null;
  return parseBook(xml, Number(id));
}
