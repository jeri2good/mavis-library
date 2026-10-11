// GET /api/shelves — the curated shelf list (ids only; titles and covers come from /api/catalog).
// Both the website and the Android app read this, so they always show the same selection.
//   ?kids=1   → only the shelves that are fine for Kids mode

import { json, onlyGet } from '../lib/shared.mjs';
import { withCors } from '../lib/cors.mjs';
import { SHELVES, TOPIC_CHIPS } from '../lib/shelves.mjs';

const handler = async (req) => {
  const pre = onlyGet(req);
  if (pre) return pre;
  const kids = new URL(req.url).searchParams.get('kids') === '1';
  const shelves = SHELVES.filter((s) => !kids || s.kids).map(({ id, label, blurb, ids, kids: k }) => ({ id, label, blurb, ids, kids: !!k }));
  return json({ shelves, topics: TOPIC_CHIPS }, { cache: 'public, max-age=300', cdn: 'public, s-maxage=3600, stale-while-revalidate=86400' });
};

export default withCors(handler);
export const config = { path: '/api/shelves' };
