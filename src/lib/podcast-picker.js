// Read-only native picker queries. No reader navigation, store writes or podcast dispatch.
import * as store from './store.js';
import { extractPodcastChapter } from './podcast-chapter.js';

export async function annotationsSummary() {
  return (await store.listAllAnnotations()).slice(0, 2000).map(({ bookKey, kind, cfi, text, note, chapter, createdAt }) =>
    ({ bookKey, kind, cfi, text, note, chapter, createdAt }));
}

/** Books the reader knows (shelf + anything opened), most recently opened first, with progress. */
export async function booksSummary() {
  const rows = (await store.listShelf()).filter(b => b.key && b.key !== 'bible');
  const out = await Promise.all(rows.map(async b => {
    const p = await store.getProgress(b.key).catch(() => null);
    return { key: b.key, source: b.source || '', sourceId: String(b.sourceId ?? ''), title: b.title || 'Untitled',
      authors: b.authors || [], coverUrl: b.coverUrl || null, percent: p?.percent ?? null, chapter: p?.chapter ?? null,
      opened: Math.max(b.lastOpenedAt || 0, p?.updatedAt || 0, b.addedAt || 0) };
  }));
  return out.sort((a, b) => b.opened - a.opened).slice(0, 200);
}

export async function podcastChapters(key, annotations = [], locator = null) {
  const file = await store.getFile(key);
  if (!file?.blob) throw new Error('Open and download this book in the reader first.');
  const { default: ePub, EpubCFI } = await import('epubjs');
  const book = ePub();
  try {
    await book.open(await file.blob.arrayBuffer(), 'binary');
    await book.ready;
    const toc = [];
    const flatten = items => items.forEach(it => {
      toc.push({ label: (it.label || '').trim() || 'Untitled section', href: it.href });
      flatten(it.subitems || []);
    });
    flatten((await book.loaded.navigation)?.toc || []);
    const progress = await store.getProgress(key);
    const rows = [];
    for (const section of book.spine.spineItems) {
      await section.load(book.load.bind(book));
      const entries = toc.filter(it => book.spine.get(it.href.split('#')[0])?.index === section.index);
      const points = entries.length ? entries : [{ label: `Section ${section.index + 1}`, href: section.href }];
      let lastLabel;
      const markedLabels = new Set();
      for (const annotation of annotations.filter(a => a.bookKey === key)) {
        try {
          const cfi = new EpubCFI(annotation.cfi);
          if (cfi.spinePos !== section.index) continue;
          markedLabels.add(extractPodcastChapter(section.document, entries, cfi.toRange(section.document).startContainer, `Section ${section.index + 1}`).label);
        } catch { /* malformed or obsolete annotation */ }
      }
      try {
        const cfi = new EpubCFI(locator || progress?.cfi);
        if (cfi.spinePos !== section.index) throw new Error('Different section');
        const node = cfi.toRange(section.document).startContainer;
        lastLabel = extractPodcastChapter(section.document, entries, node, `Section ${section.index + 1}`).label;
      } catch { /* no reading position in this section */ }
      for (const point of points) {
        const fragment = point.href.split('#')[1];
        const node = fragment ? section.document.getElementById(decodeURIComponent(fragment)) : section.document.body;
        const chapter = extractPodcastChapter(section.document, entries, node, point.label);
        if (chapter.text && !rows.some(row => row.href === point.href)) rows.push({ ...chapter, href: point.href, lastRead: chapter.label === lastLabel, marked: markedLabels.has(chapter.label) });
      }
      section.unload();
    }
    return rows;
  } finally { book.destroy(); }
}
