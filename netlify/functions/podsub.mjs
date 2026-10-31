import { json, fail, readJson, sameOrigin } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';
import { requireAccess } from '../lib/access.mjs';
import { subStore, unavailable } from '../lib/subs.mjs';
import { podcastUsage } from '../lib/podcast.mjs';
import { pageUrl, fetchPage, pageLinks, fetchArticle, previewSite } from '../lib/podsub-page.mjs';
import { defaultPodsub, savePodsub, runs, runSubscription, limitRunNow, todayEpisodes, validUid } from '../lib/podsub.mjs';

import { previewDevotional } from '../lib/podsub-devotional.mjs';

const handler = async req => {
  if (!sameOrigin(req)) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
  const access = await requireAccess(req, { feature: 'podcast' });
  if (access instanceof Response) return access;
  const uid = access.owner ? 'owner' : access.user.id;
  const action = new URL(req.url).pathname.replace(/^\/api\/(?:podsub|podcast\/subscription)\/?/, '');
  try {
    if (['preview', 'article', 'page'].includes(action)) {
      if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
      let url;
      try { url = pageUrl(new URL(req.url).searchParams.get('url')).href; }
      catch { return fail(400, 'bad_request', 'Use a valid public HTTPS URL.'); }
      try {
        if (action === 'preview') return json(new URL(req.url).searchParams.get('kind') === 'devotional' ? await previewDevotional(url) : await previewSite(url));
        if (action === 'article') return json({ ...await fetchArticle(url), url }, { cache: 'private, max-age=3600' });
        const page = await fetchPage(url);
        const posts = pageLinks(page.html, page.url).map(({ id, title, link, publishedAt }) => ({ id, title, link, publishedAt }));
        if (!posts.length) return json({ code: 'no_articles' }, { status: 422 });
        return json({ posts });
      } catch { return json({ code: action === 'article' ? 'article_unavailable' : 'no_articles' }, { status: 422 }); }
    }
    if (!action) {
      if (req.method === 'GET') {
        const stored = await subStore().get(`podsub/${uid}`, { type: 'json' }) || {};
        const doc = { ...defaultPodsub(), ...stored };
        doc.devotionals = await Promise.all(doc.devotionals.map(async d => ({ ...d, lastEpisode: (await subStore().get(`podsub-seen/${uid}/${d.id}`, { type: 'json' }))?.lastEpisode || null })));
        const { used, limit, date } = await podcastUsage(uid);
        return json({ ...doc, today: { used, limit, date }, lastRun: (await runs(uid))[0] || null });
      }
      if (req.method === 'PUT') return json(await savePodsub(uid, await readJson(req, 100000)));
      return fail(405, 'method_not_allowed', 'Use GET or PUT.');
    }
    if (action === 'run-now') {
      if (req.method !== 'POST') return fail(405, 'method_not_allowed', 'Use POST.');
      await limitRunNow(uid);
      return json(await runSubscription(uid));
    }
    if (action === 'runs' || action === 'today') {
      if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
      return json(action === 'runs' ? await runs(uid) : await todayEpisodes(uid));
    }
    if (action === 'limit') {
      if (!access.owner) return fail(403, 'owner_required', 'Owner access is required.');
      if (req.method !== 'PUT') return fail(405, 'method_not_allowed', 'Use PUT.');
      const b = await readJson(req, 1000);
      if (!b || typeof b !== 'object' || Array.isArray(b) || Object.keys(b).some(k => !['uid', 'limit'].includes(k)) || !validUid(b.uid) || !Number.isInteger(b.limit) || b.limit < 1 || b.limit > 50) return fail(400, 'bad_request', 'Use a valid uid and limit from 1 to 50.');
      await subStore().setJSON(`podlimit/${b.uid}`, { limit: b.limit });
      return json(b);
    }
    return fail(404, 'not_found', 'Unknown podcast subscription action.');
  } catch (err) {
    if (err.response) return err.response;
    if (err.status === 400 || err.status === 413 || err.status === 415) return fail(err.status, 'bad_request', err.message);
    return unavailable();
  }
};
export default withCors(handler, { methods: 'GET, PUT, POST, OPTIONS' });
export const config = { path: ['/api/podsub', '/api/podsub/*', '/api/podcast/subscription', '/api/podcast/subscription/*'], rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] } };
