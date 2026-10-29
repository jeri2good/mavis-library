import { json, fail, onlyPost, readJson, sameOrigin, softLimit, clientKey } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';
import { requireAccess } from '../lib/access.mjs';
import { subStore, subError, unavailable } from '../lib/subs.mjs';
import { createPodcast, studioConfig, studioJson, publicJob, validJobId, terminalStatus, signAudio, releaseSlot, deletePodcastRow, rememberPodcast, lastKnownPodcast, podcastQueue, withQueue, withMeta } from '../lib/podcast.mjs';

async function indexedJobs(uid, owner, cursor = '', limit = 100) {
  const s = subStore(), jobs = [];
  let more = false;
  outer: for await (const page of s.list({ prefix: owner ? 'pod/' : `pod/${uid}/`, paginate: true })) {
    for (const b of page.blobs) {
      if (b.key <= cursor) continue;
      if (jobs.length >= limit) { more = true; break outer; }
      const row = await s.get(b.key, { type: 'json' });
      if (row && row.status !== 'deleted') jobs.push({ ...row, key: b.key });
    }
  }
  return { jobs, cursor: more ? jobs.at(-1)?.key : null };
}
async function ownedJob(uid, owner, id) {
  if (!validJobId(id)) return null;
  const s = subStore();
  if (!owner) return s.get(`pod/${uid}/${id}`, { type: 'json' });
  for await (const page of s.list({ prefix: 'pod/', paginate: true })) {
    const hit = page.blobs.find((b) => b.key.split('/')[2] === id);
    if (hit) return s.get(hit.key, { type: 'json' });
  }
  return null;
}
const handler = async (req, context) => {
  if (!sameOrigin(req)) return fail(403, 'cross_origin', 'Cross-site requests are not allowed.');
  const access = await requireAccess(req, { feature: 'podcast' });
  if (access instanceof Response) return access;
  if (softLimit(`podcast:${clientKey(req, context)}`, { limit: 60 })) return fail(429, 'rate_limited', 'Too many podcast requests. Wait a minute.');
  const cfg = studioConfig();
  if (!cfg) return subError(503, 'studio_not_configured', 'The podcast studio is not configured yet.');
  cfg.deadline = Date.now() + 10000;
  const uid = access.owner ? 'owner' : access.user.id;
  const url = new URL(req.url);
  const parts = url.pathname.replace(/^\/api\/podcast\//, '').split('/');
  const [action, id] = parts;
  try {
    if (action === 'create' && parts.length === 1) {
      const bad = onlyPost(req);
      if (bad) return bad;
      const body = await readJson(req, 1700000);
      const { reused, ...job } = await createPodcast(uid, body, { cfg });
      return json(job, { status: reused ? 200 : 201 });
    }
    if (action === 'list' && parts.length === 1) {
      if (req.method !== 'GET') return fail(405, 'method_not_allowed', 'Use GET.');
      const cursor = url.searchParams.get('cursor') || '';
      if (cursor && !/^pod\/(owner|[0-9a-f-]{36})\/[A-Za-z0-9_-]{1,100}$/.test(cursor)) return fail(400, 'bad_request', 'Invalid cursor.');
      const page = await indexedJobs(uid, access.owner, cursor);
      // Look jobs up in parallel (8 at a time). One slow or missing job must never sink the whole list:
      // gone from the studio -> drop its stale row; studio slow -> show the row as last known.
      const jobs = new Array(page.jobs.length);
      let next = 0;
      await Promise.all(Array.from({ length: Math.min(8, page.jobs.length) }, async () => {
        while (next < page.jobs.length) {
          const i = next++, row = page.jobs[i];
          try {
            const job = publicJob(await studioJson(cfg, `/${row.id}`, row.userId), row.id);
            if (terminalStatus(job.status) && row.userId !== 'owner') await releaseSlot(row.userId, row.id);
            await rememberPodcast(row, job);
            jobs[i] = { ...job, ...withMeta(row) };
          } catch (e) {
            if (e?.response?.status === 404) { await deletePodcastRow(row); continue; } // not visible to this user in the studio: hide it
            jobs[i] = lastKnownPodcast(row);
          }
        }
      }));
      const queue = await podcastQueue(cfg);
      return json({ jobs: jobs.filter(Boolean).map(job => withQueue(job, queue)), cursor: page.cursor });
    }
    if (['job', 'audio'].includes(action) && parts.length === 2) {
      if (action === 'audio' ? req.method !== 'GET' : !['GET', 'DELETE'].includes(req.method)) return fail(405, 'method_not_allowed', action === 'audio' ? 'Use GET.' : 'Use GET or DELETE.');
      const row = await ownedJob(uid, access.owner, id);
      if (!row || row.status === 'deleted') {
        const identity = validJobId(id) ? await subStore().get(`pod-id/${id}`, { type: 'json' }) : null;
        if (action === 'job' && validJobId(id) && (!identity || access.owner || identity.userId === uid)) return json({ id, status: 'deleted' });
        return subError(404, 'not_found', 'That podcast was not found.');
      }
      if (req.method === 'DELETE') {
        try { await studioJson(cfg, `/${id}`, row.userId, { method: 'DELETE' }); }
        catch (err) { if (err.response?.status !== 404) throw err; }
        await deletePodcastRow(row);
        return json({ id, status: 'deleted' });
      }
      let job;
      try { job = publicJob(await studioJson(cfg, `/${id}`, row.userId), id, { transcript: action === 'job' }); }
      catch (err) {
        if (err.response?.status !== 404 || action !== 'job') throw err;
        await deletePodcastRow(row);
        return json({ id, status: 'deleted' });
      }
      const { transcript, ...summary } = job;
      await rememberPodcast(row, summary);
      if (terminalStatus(job.status) && row.userId !== 'owner') await releaseSlot(row.userId, id);
      if (action === 'audio') {
        if (job.status !== 'ready') return subError(409, 'audio_not_ready', 'This podcast audio is not ready yet. Please check again shortly.');
        const exp = Math.floor(Date.now() / 1000) + 600;
        const audio = new URL(`${cfg.base}/v1/podcasts/${id}/audio`);
        audio.search = new URLSearchParams({ exp: String(exp), uid, sig: signAudio(cfg.secret, id, exp, uid) }).toString();
        return json({ url: audio.href, expiresAt: new Date(exp * 1000).toISOString() });
      }
      return json(withQueue(job, await podcastQueue(cfg)));
    }
    return fail(404, 'not_found', 'Unknown podcast action.');
  } catch (err) {
    if (err.response) return err.response;
    if (err.status === 400 || err.status === 413) return fail(err.status, 'bad_request', err.message);
    return unavailable();
  }
};
export default withCors(handler, { methods: 'GET, POST, DELETE, OPTIONS' });
export const config = { path: '/api/podcast/*', rateLimit: { windowLimit: 90, windowSize: 60, aggregateBy: ['ip', 'domain'] } };
