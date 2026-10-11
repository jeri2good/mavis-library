// GET /api/tech — what is new in technology and AI, from open public sources.
//   ?kind=ai | tech | papers        (default ai)
// ai     Hacker News stories about AI and machine learning, by points (Algolia HN Search)
// tech   Hacker News front page, by points
// papers Hugging Face "Daily Papers" (community-picked AI research, each with an arXiv id)
// Each item links out to the original article; nothing is copied or summarised here.

import { json, fail, onlyGet, softLimit, clientKey, upstream } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';

const CACHE = { cache: 'public, max-age=300', cdn: 'public, s-maxage=900, stale-while-revalidate=3600' };
const AI_TERMS = ['AI', 'LLM', 'OpenAI', 'Anthropic', 'Claude', 'GPT', 'Gemini', 'agents', 'machine learning'];
const safeUrl = (u) => { try { const x = new URL(u); return x.protocol === 'https:' || x.protocol === 'http:' ? x.href : ''; } catch { return ''; } };

/** Shorten to a whole word and add an ellipsis, never cutting mid-word. */
export function clip(text, max) {
  const t = String(text || '').replace(/\s+/g, ' ').trim();
  if (t.length <= max) return t;
  const cut = t.slice(0, max);
  const at = cut.lastIndexOf(' ');
  return `${(at > max * 0.6 ? cut.slice(0, at) : cut).replace(/[\s,;:.\-]+$/, '')}…`;
}

export function mapHn(hits) {
  return (hits || [])
    .filter((h) => h && h.title)
    .map((h) => ({
      id: String(h.objectID ?? h.id),
      title: String(h.title).slice(0, 200),
      url: safeUrl(h.url) || `https://news.ycombinator.com/item?id=${h.objectID ?? h.id}`,
      points: Number(h.points) || 0,
      comments: Number(h.num_comments) || 0,
      source: 'Hacker News',
      when: h.created_at || null,
    }));
}

export function mapPapers(list) {
  return (Array.isArray(list) ? list : [])
    .filter((p) => p?.paper?.id && (p.title || p.paper.title))
    .slice(0, 30)
    .map((p) => ({
      id: String(p.paper.id),
      title: String(p.title || p.paper.title).slice(0, 240),
      url: `https://arxiv.org/abs/${encodeURIComponent(p.paper.id)}`,
      points: Number(p.paper.upvotes) || 0,
      comments: Number(p.numComments) || 0,
      source: 'arXiv · Hugging Face Daily Papers',
      when: p.publishedAt || p.paper.publishedAt || null,
      summary: clip(p.paper.summary, 280),
    }));
}

const handler = async (req, context) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  if (softLimit(`tech:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many requests. Wait a minute and try again.');
  const kind = new URL(req.url).searchParams.get('kind') || 'ai';
  try {
    if (kind === 'papers') {
      const r = await upstream('https://huggingface.co/api/daily_papers', { timeoutMs: 7000 });
      if (!r.ok) return fail(502, 'upstream_error', `The papers feed answered ${r.status}.`);
      return json({ kind, items: mapPapers(await r.json()) }, CACHE);
    }
    if (kind === 'ai') {
      // Algolia does not understand OR between terms, so ask for each word and merge the answers.
      const since = Math.floor(Date.now() / 1000) - 14 * 86400;
      const one = async (term) => {
        const u = `https://hn.algolia.com/api/v1/search?tags=story&query=${encodeURIComponent(term)}&numericFilters=${encodeURIComponent(`points>40,created_at_i>${since}`)}&hitsPerPage=30`;
        const r = await upstream(u, { timeoutMs: 7000 });
        if (!r.ok) throw Object.assign(new Error(`feed ${r.status}`), { status: r.status });
        return (await r.json()).hits || [];
      };
      const lists = await Promise.allSettled(AI_TERMS.map(one));
      if (lists.every((l) => l.status === 'rejected')) throw lists[0].reason;
      const seen = new Map();
      for (const l of lists) if (l.status === 'fulfilled') for (const h of l.value) if (!seen.has(h.objectID)) seen.set(h.objectID, h);
      const items = mapHn([...seen.values()]).sort((a, b) => b.points - a.points).slice(0, 30);
      return json({ kind, items }, CACHE);
    }
    let url;
    if (kind === 'tech') {
      url = 'https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30';
    } else {
      return fail(400, 'bad_request', 'kind must be ai, tech or papers.');
    }
    const r = await upstream(url, { timeoutMs: 7000 });
    if (!r.ok) return fail(502, 'upstream_error', `The news feed answered ${r.status}.`);
    const items = mapHn((await r.json()).hits).sort((a, b) => b.points - a.points);
    return json({ kind, items }, CACHE);
  } catch (err) {
    const timeout = err?.name === 'AbortError';
    return fail(timeout ? 504 : 502, timeout ? 'upstream_timeout' : 'upstream_unreachable', 'The technology feed is not answering. Try again in a moment.');
  }
};

export default withCors(handler);
export const config = { path: '/api/tech', rateLimit: { windowLimit: 60, windowSize: 60, aggregateBy: ['ip', 'domain'] } };
