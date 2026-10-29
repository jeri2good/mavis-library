import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import http from 'node:http';
import https from 'node:https';

export function publicAddress(address) {
  if (isIP(address) === 4) {
    const [a, b, c] = address.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127)
      || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && (b === 168 || b === 0 || b === 2)) || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100)))
      || (a === 203 && b === 0 && c === 113));
  }
  // Only globally routable IPv6 unicast; exclude documentation, 6to4 and Teredo.
  if (isIP(address) !== 6) return false;
  const [first, second] = address.split(':').map(v => parseInt(v || '0', 16));
  return first >= 0x2000 && first <= 0x3fff && first !== 0x2002
    && !(first === 0x2001 && (second <= 0x1ff || second === 0xdb8));
}
export function feedUrl(value) {
  if (typeof value !== 'string' || value.length > 2048 || /[\s\\\u0000-\u001f]/.test(value)) throw new Error('Invalid feed URL');
  const u = new URL(value), host = u.hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || (u.port && !['80', '443'].includes(u.port))
    || /(?:^|\.)(localhost|local|internal|lan|home|test|invalid)$/i.test(host) || !host.includes('.') && !isIP(host)
    || (isIP(host) && !publicAddress(host))) throw new Error('Invalid feed URL');
  return u;
}
// The built-in fetch dispatcher contract lets us pin DNS without adding a package.
// TLS still verifies the original hostname. Every redirect gets a fresh validation.
export function pinnedDispatcher(address, family) {
  return {
    dispatch(options, handler) {
      let req, response;
      try {
        const u = new URL(options.path, options.origin);
        const headers = Array.isArray(options.headers) ? Object.fromEntries(Array.from({ length: options.headers.length / 2 }, (_, i) => [options.headers[i * 2], options.headers[i * 2 + 1]])) : options.headers;
        req = (u.protocol === 'https:' ? https : http).request(u, {
          method: 'GET', headers, agent: false,
          lookup: (_host, opts, cb) => cb(null, opts.all ? [{ address, family }] : address, family),
        });
        handler.onConnect(() => req.destroy(new Error('Aborted')));
        req.on('error', err => handler.onError(err));
        req.on('response', res => {
          response = res;
          const raw = res.rawHeaders.map(v => Buffer.from(v));
          if (handler.onHeaders(res.statusCode, raw, () => res.resume(), res.statusMessage) === false) res.pause();
          res.on('data', chunk => { if (handler.onData(chunk) === false) res.pause(); });
          res.on('end', () => handler.onComplete(res.rawTrailers.map(v => Buffer.from(v))));
          res.on('error', err => handler.onError(err));
        });
        req.end();
      } catch (err) { response?.destroy(); req?.destroy(); handler.onError(err); }
      return true;
    },
  };
}
// Some publishers (IGN, Cloudflare-fronted feeds) 403 requests that carry no browser user-agent.
export const FEED_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 MavisLibrary/0.2';
export async function fetchFeed(url, opts = {}) {
  const { html, url: finalUrl } = await safeFetch(url, opts);
  return parseFeed(html, finalUrl);
}
export async function safeFetch(url, { fetcher = fetch, resolve = lookup, timeout = 6000, httpsOnly = false, accept = 'application/rss+xml, application/atom+xml, application/xml, text/xml' } = {}) {
  const ctl = new AbortController(), timer = setTimeout(() => ctl.abort(), Math.min(timeout, 6000));
  const aborted = new Promise((_, reject) => ctl.signal.addEventListener('abort', () => reject(new Error('Feed timeout')), { once: true }));
  try {
    return await Promise.race([aborted, (async () => {
      for (let redirects = 0; redirects < 4; redirects++) {
        const u = feedUrl(url), host = u.hostname.replace(/^\[|\]$/g, '');
        if (httpsOnly && u.protocol !== 'https:') throw new Error('HTTPS required');
        const addresses = isIP(host) ? [{ address: host, family: isIP(host) }] : await resolve(host, { all: true });
        if (ctl.signal.aborted || !addresses.length || addresses.some(a => !publicAddress(a.address))) throw new Error('Unsafe feed address');
        const r = await fetcher(u.href, { signal: ctl.signal, redirect: 'manual', credentials: 'omit', headers: { 'user-agent': FEED_UA, accept, 'accept-encoding': 'identity' }, dispatcher: pinnedDispatcher(addresses[0].address, addresses[0].family) });
        if ([301, 302, 303, 307, 308].includes(r.status)) {
          await r.body?.cancel();
          url = new URL(r.headers.get('location'), u).href;
          continue;
        }
        if (!r.ok || Number(r.headers.get('content-length')) > 2 * 1024 * 1024) { await r.body?.cancel(); throw new Error('Feed unavailable'); }
        const reader = r.body?.getReader(), chunks = [];
        if (!reader) throw new Error('Empty response');
        let size = 0;
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > 2 * 1024 * 1024) { await reader.cancel(); throw new Error('Feed too large'); }
          chunks.push(value);
        }
        return { html: Buffer.concat(chunks).toString('utf8'), url: u.href };
      }
      throw new Error('Too many redirects');
    })()]);
  } finally { clearTimeout(timer); ctl.abort(); }
}
function decode(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (all, entity) => {
    if (entity[0] !== '#') return ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" })[entity.toLowerCase()];
    const n = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : +entity.slice(1);
    return n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : '';
  });
}
export function parseFeed(xml, base) {
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('Feed declarations not allowed');
  // Bounded XML tokenization; CDATA stays text and cannot introduce fake entries.
  const root = { name: '', children: [], text: '', attrs: {} }, stack = [root];
  const tokens = xml.match(/<!\[CDATA\[[\s\S]*?\]\]>|<!--[\s\S]*?-->|<[^>]*>|[^<]+/g) || [];
  for (const token of tokens) {
    const parent = stack.at(-1);
    if (token.startsWith('<!--') || token.startsWith('<?')) continue;
    if (token.startsWith('<![CDATA[')) { parent.text += token.slice(9, -3); continue; }
    if (token.startsWith('</')) {
      if (stack.length <= 1 || stack.at(-1).name !== token.slice(2, -1).trim().toLowerCase()) throw new Error('Malformed feed');
      const child = stack.pop(); stack.at(-1).text += child.text; continue;
    }
    if (token.startsWith('<')) {
      const m = /^<([\w:.-]+)\b/.exec(token);
      if (!m || stack.length > 40) throw new Error('Malformed feed');
      const node = { name: m[1].toLowerCase(), text: '', children: [], attrs: {} };
      for (const a of token.matchAll(/([\w:.-]+)\s*=\s*(["'])(.*?)\2/g)) node.attrs[a[1].toLowerCase()] = decode(a[3]);
      parent.children.push(node);
      if (!token.endsWith('/>')) stack.push(node);
    } else parent.text += decode(token);
  }
  if (stack.length !== 1 || !root.children.some(n => ['rss', 'feed', 'rdf:rdf'].includes(n.name))) throw new Error('Malformed feed');
  const items = [];
  const walk = n => { if (['item', 'entry'].includes(n.name)) items.push(n); else n.children.forEach(walk); }; walk(root);
  const text = (n, names) => n.children.find(c => names.includes(c.name))?.text.trim() || '';
  const clean = s => s.replace(/<[^>]*>/g, ' ').replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  const seen = new Set();
  return items.slice(0, 1000).flatMap(n => {
    const linkNode = n.children.find(c => c.name === 'link' && (!c.attrs.rel || c.attrs.rel === 'alternate'));
    let link = linkNode?.attrs.href || linkNode?.text.trim() || '';
    try { link = link ? new URL(link, base).href : ''; } catch { link = ''; }
    if (link && !/^https?:\/\//i.test(link)) link = '';
    const id = text(n, ['guid', 'id']) || link;
    if (!id || id.length > 2048 || seen.has(id)) return [];
    seen.add(id);
    return [{ id, link, title: clean(text(n, ['title'])).slice(0, 300) || 'Untitled post', text: clean(text(n, ['content:encoded', 'content', 'description', 'summary'])).slice(0, 16000), publishedAt: Date.parse(text(n, ['pubdate', 'published', 'updated', 'dc:date'])) || 0 }];
  }).sort((a, b) => b.publishedAt - a.publishedAt);
}
