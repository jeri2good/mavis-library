// Client for Mavis's own /api endpoints (Gutendex + Open Library adapters).

export class ApiError extends Error {
  constructor(message, { status = 0, code = 'network', retryable = true } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }
}

async function getJSON(path, { signal, timeoutMs = 20000 } = {}) {
  if (!navigator.onLine) throw new ApiError("You're offline. Connect to the internet to browse the catalog.", { code: 'offline' });
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort('timeout'), timeoutMs);
  signal?.addEventListener('abort', () => ctl.abort('cancel'), { once: true });
  let res;
  try {
    res = await fetch(path, { signal: ctl.signal, headers: { accept: 'application/json' } });
  } catch (err) {
    if (signal?.aborted) throw err;
    if (ctl.signal.aborted) throw new ApiError('The catalog took too long to answer.', { code: 'timeout' });
    throw new ApiError('Could not reach Mavis Library. Check your connection and try again.');
  } finally {
    clearTimeout(timer);
  }
  let body = null;
  try { body = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok) {
    throw new ApiError(body?.message || `The server answered ${res.status}.`, {
      status: res.status, code: body?.error || 'http', retryable: res.status >= 500 || res.status === 429,
    });
  }
  if (!body) throw new ApiError('The server sent an unreadable response.', { code: 'parse' });
  return body;
}

// "Austen, Jane" → "Jane Austen"; keeps names with more commas as given.
export function displayName(name) {
  const m = /^([^,]+),\s*([^,]+?)(?:\s*\(.*\))?$/.exec(name || '');
  return m ? `${m[2]} ${m[1]}`.trim() : (name || '').trim();
}

const memo = new Map();
export function rememberBook(book) { memo.set(book.key, book); try { sessionStorage.setItem(`book:${book.key}`, JSON.stringify(book)); } catch { /* optional */ } }
export function recallBook(key) {
  if (memo.has(key)) return memo.get(key);
  try { const s = sessionStorage.getItem(`book:${key}`); if (s) return JSON.parse(s); } catch { /* optional */ }
  return null;
}

export function fromGutenberg(b) {
  const book = {
    key: `gutenberg:${b.id}`,
    source: 'gutenberg',
    sourceId: String(b.id),
    title: b.title,
    authors: (b.authors || []).map((a) => displayName(a.name)),
    authorDetails: (b.authors || []).map((a) => ({ name: displayName(a.name), birthYear: a.birthYear, deathYear: a.deathYear })),
    translators: (b.translators || []).map(displayName),
    coverUrl: b.cover,
    languages: b.languages || [],
    subjects: b.subjects || [],
    bookshelves: b.bookshelves || [],
    summary: b.summary,
    downloads: b.downloads,
    copyright: b.copyright,
    hasEpub: b.epub,
    epubUrl: b.epubUrl,
    sourceUrl: b.sourceUrl,
    format: 'epub',
  };
  rememberBook(book);
  return book;
}

export function fromOpenLibrary(d) {
  const book = {
    key: `openlibrary:${d.id}`,
    source: 'openlibrary',
    sourceId: d.id,
    title: d.title,
    authors: d.authors || [],
    coverUrl: d.cover,
    languages: d.languages || [],
    subjects: d.subjects || [],
    year: d.firstPublishYear,
    editionCount: d.editionCount,
    isbn: d.isbn,
    gutenbergIds: d.gutenbergIds || [],
    ebookAccess: d.ebookAccess,
    sourceUrl: d.sourceUrl,
    format: null,
  };
  rememberBook(book);
  return book;
}

export async function searchGutenberg({ search = '', topic = '', languages = '', page = 1, ids = '', sort = 'popular' } = {}, opts) {
  const p = new URLSearchParams();
  if (search) p.set('search', search);
  if (topic) p.set('topic', topic);
  if (languages) p.set('languages', languages);
  if (ids) p.set('ids', ids);
  if (sort !== 'popular') p.set('sort', sort);
  if (page > 1) p.set('page', String(page));
  const data = await getJSON(`/api/catalog?${p}`, opts);
  return { ...data, results: data.results.map(fromGutenberg) };
}

export async function getGutenbergBook(id, opts) {
  const data = await getJSON(`/api/catalog?id=${encodeURIComponent(id)}`, opts);
  return fromGutenberg(data.book);
}

export async function searchOpenLibrary({ q, page = 1 }, opts) {
  const p = new URLSearchParams({ q, page: String(page) });
  const data = await getJSON(`/api/openlibrary?${p}`, opts);
  return { ...data, results: data.results.map(fromOpenLibrary) };
}

export async function getOpenLibraryWork(id, opts) {
  const data = await getJSON(`/api/openlibrary?work=${encodeURIComponent(id)}`, opts);
  return data.work;
}

/**
 * Download a public-domain EPUB through /api/epub with progress reporting.
 * Resolves to a Blob that has been checked to be a ZIP container.
 */
export async function downloadGutenbergEpub(id, { onProgress, signal } = {}) {
  if (!navigator.onLine) throw new ApiError("You're offline. Connect to download this book.", { code: 'offline' });
  let res;
  try {
    res = await fetch(`/api/epub?id=${encodeURIComponent(id)}`, { signal });
  } catch (err) {
    if (signal?.aborted) throw err;
    throw new ApiError('The download could not start. Check your connection and try again.');
  }
  if (!res.ok) {
    let body = null;
    try { body = await res.json(); } catch { /* ignore */ }
    throw new ApiError(body?.message || `The download failed (${res.status}).`, { status: res.status, code: body?.error || 'http', retryable: res.status >= 500 });
  }
  const total = Number(res.headers.get('content-length') || 0);
  const chunks = [];
  let got = 0;
  if (res.body?.getReader) {
    const reader = res.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      got += value.byteLength;
      onProgress?.(got, total);
    }
  } else {
    const buf = new Uint8Array(await res.arrayBuffer());
    chunks.push(buf); got = buf.byteLength; onProgress?.(got, total);
  }
  if (total && got < total) throw new ApiError('The download was interrupted before it finished. Try again.', { code: 'incomplete' });
  const blob = new Blob(chunks, { type: 'application/epub+zip' });
  const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  if (!(head[0] === 0x50 && head[1] === 0x4b)) throw new ApiError('Project Gutenberg sent a file that is not an EPUB. Try again later or import the EPUB yourself.', { code: 'not_epub' });
  return blob;
}
