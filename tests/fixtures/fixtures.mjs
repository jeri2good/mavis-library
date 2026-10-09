// Test fixtures. The sandbox that built Mavis Library cannot reach Gutendex,
// Gutenberg, or Open Library, so tests use payloads shaped exactly like the
// documented upstream responses, plus a generated EPUB with original text.
// Nothing here is shown to users of the deployed app.

import JSZip from 'jszip';

export function gutendexBook(id, title, extra = {}) {
  return {
    id,
    title,
    authors: [{ name: extra.author || 'Fixture, Author', birth_year: 1775, death_year: 1817 }],
    summaries: [extra.summary || `A test summary for ${title}.`],
    translators: [],
    subjects: extra.subjects || ['Fiction', 'Test fixtures'],
    bookshelves: extra.bookshelves || ['Browsing: Literature'],
    languages: extra.languages || ['en'],
    copyright: false,
    media_type: 'Text',
    formats: {
      'text/html': `https://www.gutenberg.org/ebooks/${id}.html.images`,
      'application/epub+zip': `https://www.gutenberg.org/ebooks/${id}.epub3.images`,
      'image/jpeg': `https://www.gutenberg.org/cache/epub/${id}/pg${id}.cover.medium.jpg`,
      'text/plain; charset=us-ascii': `https://www.gutenberg.org/ebooks/${id}.txt.utf-8`,
    },
    download_count: extra.downloads ?? 1000 + id,
  };
}

export function gutendexList(results, { next = false, prev = false, count } = {}) {
  return {
    count: count ?? results.length,
    next: next ? 'https://gutendex.com/books/?page=2' : null,
    previous: prev ? 'https://gutendex.com/books/?page=1' : null,
    results,
  };
}

export function openLibrarySearch() {
  return {
    numFound: 2,
    docs: [
      { key: '/works/OL893415W', title: 'Dune', author_name: ['Frank Herbert'], first_publish_year: 1965, cover_i: 11481354, edition_count: 120, language: ['eng'], isbn: ['9780441172719'], subject: ['Science fiction'], ebook_access: 'borrowable' },
      { key: '/works/OL1W', title: 'Dune Messiah', author_name: ['Frank Herbert'], first_publish_year: 1969, edition_count: 60, language: ['eng'] },
    ],
  };
}

// 1x1 PNG (sage green) for the cover image inside the fixture EPUB.
const PNG_1PX = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGOIrK0FAALLAWmrj1L4AAAAAElFTkSuQmCC'), (c) => c.charCodeAt(0));

const SENTENCES = [
  'The lantern keeper climbed the spiral stair at dusk, counting each of the ninety-one steps the way her grandmother had taught her.',
  'Below the tower, the harbor folded itself into blue shadow, and the fishing boats came home one by one.',
  'She trimmed the wick, polished the brass, and wrote the weather into the almanac in small careful letters.',
  'Some nights the fog arrived like a slow tide, and the light became the only thing anyone could trust.',
  'On those nights she read aloud to the empty room, because a voice made the hours feel shorter.',
  'Her favorite book had a cracked green spine and margins full of notes from readers she would never meet.',
  'Each note was a small conversation across years, a question left for whoever came next.',
  'When the wind changed, the gulls changed with it, wheeling inland and complaining about the cold.',
  'By midnight the stove was ticking softly and the kettle had learned to whisper instead of sing.',
  'In the morning she slept, and the town woke up beneath a sky that had been watched all night.',
];

function chapterBody(n, paragraphs = 14) {
  const ps = [];
  for (let i = 0; i < paragraphs; i++) {
    const a = SENTENCES[(i + n) % SENTENCES.length];
    const b = SENTENCES[(i * 3 + n + 1) % SENTENCES.length];
    const c = SENTENCES[(i * 7 + n + 2) % SENTENCES.length];
    ps.push(`<p>${a} ${b} ${c}</p>`);
  }
  return ps.join('\n');
}

const CHAPTERS = ['The Ninety-One Steps', 'Fog Over the Harbor', 'Notes in the Margins', 'The Morning Watch'];

function xhtml(title, body) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head><title>${title}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>${body}</body></html>`;
}

/**
 * A small but real EPUB 3 with navigation, four chapters, a cover image, a
 * stylesheet — and deliberately hostile content (an inline script, an onclick
 * handler, and an external link) used to verify the reader's sandboxing.
 */
export async function makeFixtureEpub({ title = "The Lantern Keeper's Almanac", hostile = true } = {}) {
  const zip = new JSZip();
  zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });
  zip.file('META-INF/container.xml', `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`);
  const manifest = CHAPTERS.map((_, i) => `<item id="ch${i + 1}" href="ch${i + 1}.xhtml" media-type="application/xhtml+xml"/>`).join('\n    ');
  const spine = CHAPTERS.map((_, i) => `<itemref idref="ch${i + 1}"/>`).join('');
  zip.file('OEBPS/content.opf', `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:5e1c7a2e-0000-4000-8000-mavisfixture</dc:identifier>
    <dc:title>${title}</dc:title>
    <dc:creator>Mavis Test Press</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-10-01T00:00:00Z</meta>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="style.css" media-type="text/css"/>
    <item id="cover-img" href="cover.png" media-type="image/png" properties="cover-image"/>
    ${manifest}
  </manifest>
  <spine>${spine}</spine>
</package>`);
  zip.file('OEBPS/nav.xhtml', xhtml('Contents', `<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>${CHAPTERS.map((c, i) => `<li><a href="ch${i + 1}.xhtml">${c}</a></li>`).join('')}</ol></nav>`));
  zip.file('OEBPS/style.css', 'body{margin:0} h1{font-size:1.4em;margin:1em 0} p{text-indent:1.2em;margin:0}');
  zip.file('OEBPS/cover.png', PNG_1PX);
  CHAPTERS.forEach((c, i) => {
    let extra = '';
    if (hostile && i === 0) {
      extra = `<script>window.parent.__mavisHostile = true; document.title='pwned';</script>
<p id="hostile-click" onclick="window.parent.__mavisHostileClick = true">This paragraph has an inline click handler that must never run.</p>
<p>Visit <a id="external-link" href="https://example.com/outside">an outside page</a> for more.</p>`;
    }
    zip.file(`OEBPS/ch${i + 1}.xhtml`, xhtml(c, `<h1 id="c${i + 1}">${c}</h1>${extra}\n${chapterBody(i)}\n<p id="end-${i + 1}">Here the chapter called ${c} comes to its quiet close.</p>`));
  });
  return zip.generateAsync({ type: 'uint8array', mimeType: 'application/epub+zip' });
}

export function fixtureText() {
  return `THE TIDE TABLES\n\nA sample plain-text book for import tests.\n\nCHAPTER I. Low Water\n\n${SENTENCES.slice(0, 5).join(' ')}\n\n${SENTENCES.slice(5).join(' ')}\n\nCHAPTER II. High Water\n\n${SENTENCES.slice(2, 8).join(' ')}\n\n${SENTENCES.join(' ')}\n`;
}
