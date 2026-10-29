import assert from 'node:assert/strict';
import { pageLinks, extractArticle, fetchPage, fetchArticle, previewSite } from '../../netlify/lib/podsub-page.mjs';
import { FEED_UA, fetchFeed } from '../../netlify/lib/podsub-feed.mjs';
const realFetch = globalThis.fetch;
globalThis.fetch = () => { throw new Error('Live network forbidden'); };
const resolve = async () => [{ address: '93.184.216.34', family: 4 }];
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log(`  ✓ ${name}`); };
const base = 'https://roundup.example.com/news';
const roundup = `<title>Tool news</title><nav><a href="/navigation">Navigation link that is long enough</a></nav><main>${Array.from({ length: 10 }, (_, i) => `<article><h2><a href="https://publisher.example.com/story-${i}?utm_source=roundup">Important artificial intelligence headline number ${i}</a></h2></article>`).join('')}<a href="https://publisher.example.com/story-0">Duplicate article headline number zero</a><a href="/news#top">Back to this page</a><a href="/news?sort=added">Date added</a><a href="/newsletter">Subscribe to our twice weekly newsletter</a><a href="/privacy-policy">Privacy policy for this publication</a><a href="/category/news">All news categories</a><a href="/tag/ai">AI tags</a><a href="/login">Log in to read</a><a href="http://publisher.example.com/insecure">Insecure external article headline</a><a href="mailto:editor@example.com">Email our editor</a><a href="https://x.com/person">Visit our social media profile</a></main><footer><a href="/footer-story">A footer link that looks like a headline</a></footer>`;
const rss = `<rss><channel>${[1, 2, 3, 4].map(i => `<item><title>Feed ${i}</title><link>https://publisher.example.com/feed-${i}</link></item>`).join('')}</channel></rss>`;
try {
  await test('roundup keeps ten outbound articles in page order and excludes navigation/noise', () => {
    const posts = pageLinks(roundup, base);
    assert.equal(posts.length, 10);
    for (let i = 0; i < 10; i++) { assert.equal(posts[i].link, `https://publisher.example.com/story-${i}`); assert.equal(posts[i].text, ''); if (i) assert.equal(posts[i - 1].publishedAt - posts[i].publishedAt, 1); }
  });
  await test('JSON-LD ItemList and NewsArticle precede anchors and deduplicate; cap is 30', () => {
    const html = `<script type="application/ld+json">${JSON.stringify({ '@graph': [{ '@type': 'ItemList', itemListElement: [{ item: { url: '/second', name: 'Second article' } }] }, { '@type': 'NewsArticle', url: '/first', headline: 'First article' }] })}</script><main>${Array.from({ length: 40 }, (_, i) => `<a href="/post-${i}">Post ${i}</a>`).join('')}</main>`;
    const posts = pageLinks(html, base); assert.equal(posts.length, 30); assert.deepEqual(posts.slice(0, 2).map(p => p.title), ['Second article', 'First article']);
  });
  await test('WordPress JSON-LD articleBody wins and returns metadata, decoded prose and reading time', () => {
    const body = 'The first &amp; second paragraph.\n\n' + 'word '.repeat(500);
    const html = `<meta property="og:title" content="An &quot;article&quot;"><meta property="og:site_name" content="Publisher"><meta property="og:image" content="https://images.example.com/hero.jpg"><script type="application/ld+json">${JSON.stringify({ '@type': 'NewsArticle', articleBody: body, author: { name: 'Writer' }, datePublished: '2026-10-29' })}</script><article><p>Teaser only</p></article>`;
    const a = extractArticle(html, base); assert.equal(a.title, 'An "article"'); assert.equal(a.byline, 'Writer'); assert.equal(a.siteName, 'Publisher'); assert.equal(a.publishedAt, '2026-10-29'); assert.equal(a.readMin, 2); assert.match(a.text, /first & second paragraph\.\n\nword/); assert.ok(a.words > 500); assert.equal(a.image, 'https://images.example.com/hero.jpg');
  });
  await test('paragraph soup preserves paragraph breaks and article extraction excludes script/ad/navigation text', () => {
    const html = '<title>Fallback title</title><nav><p>Navigation garbage</p></nav><div><p>First &nbsp; paragraph &#8212; here.</p><p>Second paragraph.</p><script>SECRET SCRIPT</script><style>SECRET STYLE</style><aside><p>Sidebar junk</p></aside><form><p>Form junk</p></form><div class="ad"><p>Advertisement junk</p></div><figcaption>Advertisement caption</figcaption></div>';
    const a = extractArticle(html, base); assert.equal(a.text, 'First paragraph — here.\n\nSecond paragraph.'); assert.equal(a.title, 'Fallback title'); assert.equal(a.image, '');
    const b = extractArticle(`<h1>H1 title</h1><meta property="og:image" content="http://images.example.com/no.jpg"><article><p>${'word '.repeat(12000)}</p></article>`, base);
    assert.equal(b.text.length, 39999); assert.equal(b.image, ''); assert.equal(b.title, 'H1 title');
  });
  await test('fallback anchors require headline-length text; same-page, non-HTTPS and credentials are excluded', () => {
    const html = '<a href="/short">Short</a><a href="/long">A sufficiently long headline outside main</a><a href="https://u:p@publisher.example.com/story">A credentialed URL with a long headline</a>';
    assert.deepEqual(pageLinks(html, base).map(p => p.link), ['https://roundup.example.com/long']);
  });
  await test('safe page fetch pins DNS, omits credentials and uses the existing UA', async () => {
    const page = await fetchPage(base, { resolve, fetcher: async (url, opts) => {
      assert.equal(url, base); assert.equal(opts.credentials, 'omit'); assert.equal(opts.redirect, 'manual'); assert.equal(opts.headers['user-agent'], FEED_UA); assert.ok(opts.dispatcher); assert.ok(opts.signal); assert.equal(opts.headers.cookie, undefined); return new Response(roundup);
    } }); assert.equal(page.url, base); assert.equal(page.html, roundup);
  });
  await test('HTTPS downgrade and private-IP redirects are rejected before a second request', async () => {
    for (const location of ['http://publisher.example.com/story', 'https://127.0.0.1/story', 'https://10.0.0.1/story']) {
      let calls = 0;
      await assert.rejects(fetchPage(base, { resolve, fetcher: async () => { calls++; return new Response(null, { status: 302, headers: { location } }); } })); assert.equal(calls, 1);
    }
    await assert.rejects(fetchPage(base, { resolve: async () => [{ address: '192.168.1.1', family: 4 }], fetcher: () => assert.fail('Unsafe DNS') }));
  });
  await test('safe fetch revalidates redirect DNS, caps redirects, bytes and timeout including DNS', async () => {
    let dns = 0;
    await assert.rejects(fetchPage(base, { resolve: async () => ++dns === 1 ? resolve() : [{ address: '127.0.0.1', family: 4 }], fetcher: async () => new Response(null, { status: 302, headers: { location: 'https://next.example.com/news' } }) })); assert.equal(dns, 2);
    let calls = 0;
    await assert.rejects(fetchPage(base, { resolve, fetcher: async () => { calls++; return new Response(null, { status: 302, headers: { location: '/again' } }); } }), /redirects/); assert.equal(calls, 4);
    await assert.rejects(fetchPage(base, { resolve, fetcher: async () => new Response('x'.repeat(2 * 1024 * 1024 + 1)) }), /large/);
    await assert.rejects(fetchPage(base, { timeout: 10, resolve: () => new Promise(() => {}), fetcher: () => assert.fail('No connection after timeout') }), /timeout/);
  });
  await test('fetchArticle returns prose and normalizes empty, HTTP and network failures', async () => {
    assert.equal((await fetchArticle(base, { resolve, fetcher: async () => new Response('<article><p>Readable article.</p></article>') })).text, 'Readable article.');
    for (const html of ['', '<script>only script</script>']) await assert.rejects(fetchArticle(base, { resolve, fetcher: async () => new Response(html) }), /^Error: Article unavailable$/);
    await assert.rejects(fetchArticle('http://publisher.example.com/story', { resolve, fetcher: () => assert.fail() }), /^Error: Article unavailable$/);
  });
  await test('preview prefers direct RSS or discovered alternate feed and falls back to page links', async () => {
    const direct = await previewSite(base, { resolve, fetcher: async () => new Response(rss) }); assert.equal(direct.source, 'feed'); assert.equal(direct.posts.length, 4);
    const preview = await previewSite(base, { resolve, fetcher: async url => new Response(url.endsWith('/rss') ? rss : `<link rel="alternate" type="application/rss+xml" href="/rss">${roundup}`) });
    assert.equal(preview.source, 'feed'); assert.equal(preview.feedUrl, 'https://roundup.example.com/rss'); assert.equal(preview.title, 'Tool news');
    const page = await previewSite(base, { resolve, fetcher: async () => new Response(roundup) }); assert.equal(page.source, 'page'); assert.equal(page.posts.length, 8);
    await assert.rejects(previewSite(base, { resolve, fetcher: async () => new Response('<main><a href="/one">Only one article</a></main>') }), /No articles/);
  });
  await test('legacy fetchFeed still accepts public HTTP and retains parsing behavior', async () => {
    assert.equal((await fetchFeed('http://publisher.example.com/rss', { resolve, fetcher: async () => new Response(rss) })).length, 4);
  });
  console.log(`\n${passed} page/article checks passed.`);
} finally { globalThis.fetch = realFetch; }
