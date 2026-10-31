import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseReference, extractReference, fetchPassage } from '../../netlify/lib/podsub-bible.mjs';
import { choosePassage, CURATED, devotionalEpisode, previewDevotional } from '../../netlify/lib/podsub-devotional.mjs';
import { validatePodsub, defaultPodsub } from '../../netlify/lib/podsub.mjs';
import { extractArticle } from '../../netlify/lib/podsub-page.mjs';
const cases = ['Hebrews 12:1-3','Matthew 16:13-17; Hebrews 1:1-3, 8-12','1 Corinthians 13:1-13','2 Tim. 2:1-3','III John 1:1-3','Ps. 23:1-6','Psalm 127:1-5','Gen 1:1-3','Ex. 3:1-5','Deut 6:4-9','Josh 1:1-9','1 Sam 3:1-10','2 Kings 2:1-5','1 Chr 16:8-12','Neh 8:1-3','Prov 3:1-6','Song of Solomon 2:1-5','Isa 40:28-31','Jer 29:10-14','Lam 3:19-26','Ezek 36:24-28','Dan 3:16-18','Matt 5:1-12','Jn 3:16-18','Phil 4:4–9','John 3:34-4:3','John 3:16, 18, 20-21','John 3:16-18; 4:1-3','Rev 21:1-7'];
for (const ref of cases) test(`reference: ${ref}`, () => { const p = parseReference(ref); assert.ok(p.count > 0); assert.equal(parseReference(p.reference).reference, p.reference); assert.ok(extractReference(`Reading: ${ref}\nReflection text.`).count > 0); });
for (const ref of ['junk','John 99:1','John 3:999','John 3:0','John 3:18-16','John 4:3-3:4','John 3:1 garbage','Unknown 1:1','John 3:1;']) test(`reject: ${ref}`, () => assert.throws(() => parseReference(ref)));
test('devotion HTML retains the leading Scripture heading', () => {
 const a = extractArticle('<article><h1>Faith</h1><h2>Matthew 16:13-17; Hebrews 1:1-3, 8-12</h2><p>Key verse.</p><p>Reflection.</p></article>', 'https://example.com/a');
 assert.equal(extractReference(a.scriptureText).reference, 'Matthew 16:13-17; Hebrews 1:1-3, 8-12');
});
test('validation defaults and rejects invalid devotional fields', () => {
 const { nextRunAt, nextDevotionalAt, updatedAt, ...doc } = defaultPodsub();
 assert.equal(validatePodsub(doc).devotionalSchedule.readyBy, '19:00');
 const d = { id: 'a', kind: 'topic', name: 'Anxiety', topic: 'anxiety', enabled: true };
 for (const patch of [{ kind: 'other' }, { topic: '' }, { topic: 'x'.repeat(121) }, { enabled: 1 }, { id: '../a' }, { kind: 'site', url: 'http://example.com' }, { kind: 'site', url: 'https://127.0.0.1' }]) assert.throws(() => validatePodsub({ ...doc, devotionals: [{ ...d, ...patch }] }));
 assert.throws(() => validatePodsub({ ...doc, devotionals: [d,d] }));
 assert.throws(() => validatePodsub({ ...doc, devotionals: Array.from({ length: 6 }, (_, i) => ({...d,id:String(i)})) }));
 assert.throws(() => validatePodsub({ ...doc, devotionalSchedule: {...doc.devotionalSchedule, enabled: true, days: []} }));
 assert.equal(validatePodsub({ ...doc, devotionals: [{...d,topic:'',surprise:true}] }).devotionals.length, 1);
});
test('topic LLM receives exclusions, fallback handles throws, junk and duplicate aliases', async () => {
 const d = { name:'Anxiety', topic:'anxiety' }, history = ['Philippians 4:4-9'];
 for (const llm of [async () => {throw Error('offline');}, async () => ({}), async args => {assert.equal(args.json,true); assert.ok(args.maxTokens <= 200); assert.match(args.user,/Philippians/); return {reference:'Phil. 4:4-9',title:'Peace'};}]) {
  assert.equal((await choosePassage(d,history,{llm})).reference,'Matthew 6:25-34');
 }
 const recent = CURATED.slice(0,60).map(x=>x.reference);
 assert.ok(!recent.includes((await choosePassage(d,recent,{llm:async()=>{throw Error();}})).reference));
 assert.ok(CURATED.length >= 61); for (const p of CURATED) assert.ok(parseReference(p.reference).count >= 3 && parseReference(p.reference).count <=30);
});
test('site uses newest uncovered devotion and Scripture-first title, preview returns reference', async () => {
 const page = async () => ({url:'https://example.com/library',html:'<main><a href="/new">Newest devotion</a><a href="/old">Older devotion</a></main>'});
 const article = async url => ({title:url.endsWith('new')?'New':'Old',text:'Reflection',scriptureText:'Hebrews 12:1-3'});
 const deps = {page,article,passage:async r=>`${r}\nFull Scripture`,feed:async()=>{throw Error('no feed');}};
 const e = await devotionalEpisode({id:'d',kind:'site',url:'https://example.com/library'}, {seen:['https://example.com/new']},deps);
 assert.equal(e.title,'Hebrews 12:1-3 — Old'); assert.match(e.text,/Full Scripture/); assert.match(e.text,/Today's devotional reflection/); assert.equal(e.devotional.reference,'Hebrews 12:1-3');
 assert.equal((await previewDevotional('https://example.com/library',deps)).title,'New');
 await assert.rejects(devotionalEpisode({id:'d',kind:'site',url:'https://example.com/library'}, {}, {...deps,passage:async()=>{throw Error();}}), /scripture_unavailable/);
});
test('JavaScript-rendered library: devotions come from the site feed with their printed reference (todaydevotional.com shape)', async () => {
 const page = async () => ({url:'https://todaydevotional.com/daily-devotional-library',html:'<html><head><title>Library</title></head><body><div id="app"></div><a href="/devotions/by-topic">By topic</a></body></html>'});
 const feeds = [];
 const item = (slug, title, day, ref) => ({ id:`https://todaydevotional.com/devotions/${slug}`, link:`https://todaydevotional.com/devotions/${slug}?embedded`, title,
   publishedAt: Date.parse(`2026-10-${day}T05:00:00Z`), text:`Scripture Reading &mdash;       ${ref}                        “Key verse.”  &mdash;&nbsp; ${ref.split(';')[0]}   Reflection on ${title}. Pray with us.` });
 const feed = async url => { feeds.push(url); if (!url.endsWith('/feed')) throw Error('404');
   return [item('jesus-savior','Jesus, Savior','29','Acts 4:5-12'), item('alpha-and-omega-2','Alpha and Omega','31','Revelation 22:12-21'), item('god','God','28','Matthew 16:13-17 ;  Hebrews 1:1-3, 8-12')]; };
 const article = async () => { throw Error('article must not be fetched for feed devotions'); };
 const passage = async r => `${r} (BSB)\n1 Verse`;
 assert.deepEqual(await previewDevotional('https://todaydevotional.com/daily-devotional-library',{page,feed,article}),
   {title:'Alpha and Omega',reference:'Revelation 22:12-21',url:'https://todaydevotional.com/devotions/alpha-and-omega-2'});
 assert.equal(feeds[0],'https://todaydevotional.com/feed');
 const e = await devotionalEpisode({id:'t',kind:'site',name:'Today',url:'https://todaydevotional.com/daily-devotional-library'},
   {seen:['https://todaydevotional.com/devotions/alpha-and-omega-2']},{page,feed,article,passage});
 assert.equal(e.title,'Acts 4:5-12 — Jesus, Savior');
 assert.match(e.text,/Today's devotional reflection\nhttps:\/\/todaydevotional\.com\/devotions\/jesus-savior\n/);
 assert.doesNotMatch(e.text,/&mdash;|&nbsp;/);
 assert.equal(e.posts[0].id,'https://todaydevotional.com/devotions/jesus-savior');
 const multi = await devotionalEpisode({id:'t',kind:'site',name:'Today',url:'https://todaydevotional.com/daily-devotional-library'},
   {seen:['https://todaydevotional.com/devotions/alpha-and-omega-2','https://todaydevotional.com/devotions/jesus-savior']},{page,feed,article,passage});
 assert.equal(multi.devotional.reference,'Matthew 16:13-17; Hebrews 1:1-3, 8-12');
});
test('HelloAO BSB fetch covers multiple chapters and passages and rejects missing verses', async () => {
 const urls=[]; const fetcher = async u => {urls.push(u); return Response.json({chapter:{content:Array.from({length:36},(_,i)=>({type:'verse',number:i+1,content:[`Verse ${i+1}`]}))}});};
 const text = await fetchPassage('John 3:34-4:3; Hebrews 1:1-3, 8-12',{fetcher});
 assert.equal(urls.length,3); assert.ok(urls.every(u=>u.startsWith('https://bible.helloao.org/api/BSB/'))); assert.match(text,/4:3 Verse 3/); assert.match(text,/1:12 Verse 12/);
 await assert.rejects(fetchPassage('John 3:16',{fetcher:async()=>Response.json({})}));
});
