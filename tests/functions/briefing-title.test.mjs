// Briefing titles and story lists: a briefing says when it was made and what it covers.
import assert from 'node:assert/strict';
import { briefingTitle, episodeMeta } from '../../netlify/lib/podsub.mjs';
import { withMeta, lastKnownPodcast } from '../../netlify/lib/podcast.mjs';

const at = Date.parse('2026-10-29T13:10:00Z');
const posts = [
  { title: 'Google Expands AI Mode Ads to 10 New Countries Including Canada and India', link: 'https://blog.google/a' },
  { title: 'Anthropic Launches Claude for Legal Artifacts', link: 'https://claude.com/b' },
  { title: 'Gemini App Launches Immersive Explorations', link: 'https://blog.google/c' },
  { title: 'Phantom Blade Zero Recast', link: 'http://insecure.example/d' },
];

// Local date in the owner's time zone, not UTC.
assert.match(briefingTitle(posts, at, 'America/New_York'), /^Thu, Oct 29 · /);
assert.match(briefingTitle(posts, Date.parse('2026-10-30T02:00:00Z'), 'America/New_York'), /^Thu, Oct 29 · /);
// Two headlines then a count of the rest.
const t = briefingTitle(posts, at, 'America/New_York');
assert.ok(t.includes('Anthropic Launches Claude for Legal Artifacts'));
assert.ok(t.endsWith(' + 2 more'));
assert.ok(t.length <= 300);
// One story: no "+ more"; long headlines are clipped with an ellipsis.
const one = briefingTitle(posts.slice(0, 1), at, 'America/New_York');
assert.ok(!one.includes('more') && one.includes('…'));
assert.equal(briefingTitle([{ title: 'Short one' }], at, 'UTC'), 'Thu, Oct 29 · Short one');

const meta = episodeMeta({ sites: [{ name: 'Future Tools', posts: posts.slice(0, 3) }, { name: 'IGN', posts: posts.slice(3) }], posts });
assert.deepEqual(meta.sites, ['Future Tools', 'IGN']);
assert.equal(meta.stories.length, 4);
assert.deepEqual(meta.stories[0], { title: posts[0].title, site: 'Future Tools', link: 'https://blog.google/a' });
// Only https links are passed on.
assert.equal(meta.stories[3].link, undefined);
assert.equal(meta.stories[3].site, 'IGN');

// Rows without meta (older podcasts) still list fine; rows with meta expose sites/stories and the date.
assert.deepEqual(withMeta({ createdAt: at }), { createdAt: new Date(at).toISOString() });
const row = { id: 'x', createdAt: at, title: t, status: 'ready', meta };
assert.deepEqual(withMeta(row).sites, ['Future Tools', 'IGN']);
// The saved briefing title wins over the studio's copy (which may be the old "Daily briefing").
assert.equal(withMeta(row).title, t);
assert.equal(withMeta({ createdAt: at, title: 'No meta' }).title, undefined);
const last = lastKnownPodcast({ ...row, lastKnown: { id: 'x', status: 'ready', createdAt: 'bogus' } });
assert.equal(last.createdAt, new Date(at).toISOString());
assert.equal(last.stories.length, 4);
console.log('briefing-title tests passed');
