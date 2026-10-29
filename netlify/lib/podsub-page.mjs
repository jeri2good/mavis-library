import { safeFetch, fetchFeed, feedUrl } from './podsub-feed.mjs';

export function pageUrl(value) {
  const url = feedUrl(value);
  if (url.protocol !== 'https:') throw new Error('HTTPS required');
  return url;
}
export function fetchPage(url, opts = {}) {
  return safeFetch(url, { ...opts, httpsOnly: true, accept: 'text/html, application/xhtml+xml' });
}
const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', hellip: '…', copy: '©' };
function decode(s) {
  return String(s || '').replace(/&(#x[\da-f]+|#\d+|[a-z]+);/gi, (all, e) => {
    if (e[0] !== '#') return entities[e.toLowerCase()] ?? all;
    const n = e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : +e.slice(1);
    return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : '';
  });
}
const voids = new Set('area base br col embed hr img input link meta param source track wbr'.split(' '));
// A bounded, tolerant HTML tree. Raw script/style content is never parsed as markup.
function document(html) {
  const root = { tag: '', attrs: {}, children: [], parent: null }, stack = [root];
  const tokens = String(html).slice(0, 2 * 1024 * 1024).match(/<!--[\s\S]*?-->|<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>|<[^>]*>|[^<]+/gi) || [];
  for (const token of tokens) {
    if (/^<!--|^<!|^<\?/.test(token)) continue;
    if (/^<\//.test(token)) {
      const tag = /^<\/\s*([\w:-]+)/.exec(token)?.[1]?.toLowerCase();
      for (let i = stack.length - 1; i > 0; i--) if (stack[i].tag === tag) { stack.length = i; break; }
      continue;
    }
    const m = /^<([\w:-]+)\b([^>]*?)>/.exec(token);
    if (!m) { stack.at(-1).children.push(decode(token)); continue; }
    const tag = m[1].toLowerCase(), attrs = {};
    for (const a of m[2].matchAll(/([^\s=/'"<>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) attrs[a[1].toLowerCase()] = decode(a[2] ?? a[3] ?? a[4] ?? '');
    if (tag === 'p' && stack.at(-1).tag === 'p') stack.pop();
    const node = { tag, attrs, children: [], parent: stack.at(-1) };
    node.parent.children.push(node);
    if (tag === 'script' || tag === 'style') node.children.push(token.slice(m[0].length).replace(/<\/[^>]+>\s*$/, ''));
    else if (!voids.has(tag) && !/\/$/.test(m[2]) && stack.length < 150) stack.push(node);
  }
  return root;
}
function nodes(root, predicate) {
  const out = [], pending = [root];
  while (pending.length) {
    const n = pending.pop();
    if (typeof n === 'string') continue;
    if (predicate(n)) out.push(n);
    for (let i = n.children.length - 1; i >= 0; i--) pending.push(n.children[i]);
  }
  return out;
}
const noiseTags = new Set('script style nav aside form iframe noscript template footer header'.split(' '));
function noisy(n) {
  return noiseTags.has(n.tag) || n.attrs.hidden !== undefined || n.attrs['aria-hidden'] === 'true'
    || /(?:^|[\s_-])(ad|ads|advertisement|advertising|sponsor|social|share|cookie|newsletter|related)(?:$|[\s_-])/i.test(`${n.attrs.class || ''} ${n.attrs.id || ''}`)
    || (n.tag === 'figcaption' && /advert|sponsor/i.test(rawText(n)));
}
function rawText(n) { return typeof n === 'string' ? n : n.children.map(rawText).join(''); }
function content(n, paragraphs = false) {
  if (typeof n === 'string') return n;
  if (noisy(n)) return '';
  const s = n.children.map(c => content(c, paragraphs)).join('');
  return s + (paragraphs && /^(p|div|section|article|h[1-6]|li|br)$/.test(n.tag) ? '\n\n' : ' ');
}
const tidy = s => s.replace(/[\t\r\f ]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
function jsonData(root) {
  const out = [];
  for (const n of nodes(root, n => n.tag === 'script' && /application\/ld\+json/i.test(n.attrs.type))) {
    try {
      const pending = [JSON.parse(rawText(n))];
      while (pending.length) {
        const v = pending.pop();
        if (!v || typeof v !== 'object') continue;
        if (!Array.isArray(v)) out.push(v);
        pending.push(...Object.values(v).filter(x => x && typeof x === 'object').reverse());
      }
    } catch { /* Malformed publisher metadata is optional. */ }
  }
  return out;
}
function meta(root, key) { return nodes(root, n => n.tag === 'meta' && (n.attrs.property || n.attrs.name)?.toLowerCase() === key)[0]?.attrs.content || ''; }
function titleOf(root) { return meta(root, 'og:title') || tidy(content(nodes(root, n => n.tag === 'h1')[0] || { tag: '', attrs: {}, children: [] })) || tidy(rawText(nodes(root, n => n.tag === 'title')[0] || '')); }
function httpsLink(value, base) {
  if (typeof value !== 'string' || !value.trim()) return '';
  try { return pageUrl(new URL(value, base).href).href; } catch { return ''; }
}
export function pageLinks(html, baseUrl) {
  const root = document(html), candidates = [], seen = new Set(), posts = [], now = Date.now();
  const add = (url, title) => candidates.push({ url, title });
  for (const data of jsonData(root)) {
    const types = [].concat(data['@type'] || []);
    if (types.includes('ItemList')) for (const entry of data.itemListElement || []) {
      const item = entry.item || entry;
      add(typeof item === 'string' ? item : item.url || item['@id'], item.name || item.headline || entry.name);
    }
    if (types.includes('NewsArticle')) add(data.url || data.mainEntityOfPage?.['@id'], data.headline || data.name);
  }
  const anchors = nodes(root, n => n.tag === 'a' && n.attrs.href).filter(n => {
    for (let p = n; p; p = p.parent) if (noisy(p)) return false;
    return true;
  });
  const preferred = n => { for (let p = n.parent; p; p = p.parent) if (['article', 'main', 'h2', 'h3'].includes(p.tag)) return true; return false; };
  for (const n of anchors.filter(preferred)) add(n.attrs.href, tidy(content(n)));
  for (const n of anchors.filter(n => !preferred(n))) { const t = tidy(content(n)); if (t.length >= 25 && t.length <= 200) add(n.attrs.href, t); }
  for (const c of candidates) {
    if (!c.url || !c.title) continue;
    const link = httpsLink(c.url, baseUrl);
    if (!link) continue;
    const u = new URL(link), base = new URL(baseUrl);
    if (/(^|\.)(facebook|twitter|x|instagram|linkedin|youtube|tiktok|pinterest|reddit)\.com$/i.test(u.hostname)
      || /\/(?:login|sign-?in|sign-?up|register|tags?|categor(?:y|ies)|privacy|terms|about|contact|subscribe|newsletter|search)(?:[/.?-]|$)/i.test(u.pathname)
      || /^\/?$/.test(u.pathname) || (u.origin === base.origin && u.pathname.replace(/\/$/, '') === base.pathname.replace(/\/$/, ''))) continue;
    u.hash = '';
    for (const key of [...u.searchParams.keys()]) if (/^utm_/i.test(key)) u.searchParams.delete(key);
    if (seen.has(u.href)) continue;
    seen.add(u.href);
    posts.push({ id: u.href, link: u.href, title: tidy(String(c.title)).slice(0, 300), text: '', publishedAt: now - posts.length });
    if (posts.length === 30) break;
  }
  return posts;
}
export function extractArticle(html, url) {
  const root = document(html), data = jsonData(root).find(d => typeof d.articleBody === 'string');
  let text = '';
  if (data) text = tidy(content(document(data.articleBody), true));
  if (!text) {
    const articles = nodes(root, n => n.tag === 'article' && !noisy(n));
    const containers = articles.length ? articles : nodes(root, n => ['div', 'section', 'main', 'body', ''].includes(n.tag) && !noisy(n));
    // Prefer the smallest container holding the greatest amount of paragraph prose.
    const scored = containers.map(n => {
      const prose = nodes(n, p => p.tag === 'p').filter(p => { for (let a = p; a && a !== n; a = a.parent) if (noisy(a)) return false; return true; });
      const body = tidy(prose.map(p => content(p)).join('\n\n'));
      return { n, body, score: body.length, size: rawText(n).length };
    }).sort((a, b) => b.score - a.score || a.size - b.size);
    if (scored[0]) text = scored[0].body || (articles.length ? tidy(content(scored[0].n, true)) : '');
  }
  text = text.slice(0, 40000).trim();
  const words = text.match(/\S+/g)?.length || 0;
  const author = data?.author;
  return { title: titleOf(root), byline: (Array.isArray(author) ? author.map(a => a.name || a).join(', ') : author?.name || author) || meta(root, 'author'), siteName: meta(root, 'og:site_name') || new URL(url).hostname, image: httpsLink(meta(root, 'og:image'), url), publishedAt: data?.datePublished || meta(root, 'article:published_time') || null, text, words, readMin: Math.max(1, Math.round(words / 230)) };
}
export async function fetchArticle(url, opts = {}) {
  try {
    const page = await fetchPage(url, opts), article = extractArticle(page.html, page.url);
    if (!article.text) throw new Error('Empty article');
    return article;
  } catch { throw new Error('Article unavailable'); }
}
export async function previewSite(url, opts = {}) {
  pageUrl(url);
  const feedOpts = { ...opts, httpsOnly: true };
  const result = (source, feedUrl, title, posts, siteUrl = url) => ({ source, feedUrl, siteUrl, title, posts: posts.slice(0, 8).map(({ title, link }) => ({ title, link })) });
  try { const posts = await fetchFeed(url, feedOpts); if (posts.length >= 3) return result('feed', url, new URL(url).hostname, posts); } catch { /* Try HTML discovery. */ }
  const page = await fetchPage(url, opts), root = document(page.html);
  for (const n of nodes(root, n => n.tag === 'link' && /\balternate\b/i.test(n.attrs.rel) && /(?:rss|atom)/i.test(n.attrs.type)).slice(0, 5)) {
    const link = httpsLink(n.attrs.href, page.url);
    if (!link) continue;
    try { const posts = await fetchFeed(link, feedOpts); if (posts.length >= 3) return result('feed', link, titleOf(root), posts, page.url); } catch { /* Try next feed. */ }
  }
  const posts = pageLinks(page.html, page.url);
  if (posts.length < 3) throw new Error('No articles');
  return result('page', page.url, titleOf(root), posts, page.url);
}
