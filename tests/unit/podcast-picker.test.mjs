import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { DOMParser } from '@xmldom/xmldom';
import { extractPodcastChapter } from '../../src/lib/podcast-chapter.js';
const source = await readFile(new URL('../../src/lib/podcast-picker.js', import.meta.url), 'utf8');
async function queries(store, epub = {}) {
  const body = source.replace("import * as store from './store.js';", '')
    .replace("import { extractPodcastChapter } from './podcast-chapter.js';", '')
    .replace("await import('epubjs')", 'epub').replaceAll('export async function', 'async function');
  return new Function('store', 'epub', 'extractPodcastChapter', `${body};return { annotationsSummary, podcastChapters };`)(store, epub, extractPodcastChapter);
}
test('annotation query exports only allowed fields, capped at 2000', async () => {
  const api = await queries({ listAllAnnotations: async () => Array.from({length: 2001}, () => ({ bookKey:'bible',kind:'note',cfi:'John.3.16',text:'verse',note:'note',chapter:'John 3',createdAt:1,owner:'private',secret:'private' })) });
  const rows = await api.annotationsSummary();
  assert.equal(rows.length,2000);
  assert.deepEqual(Object.keys(rows[0]),['bookKey','kind','cfi','text','note','chapter','createdAt']);
});
test('chapter query uses reader extraction, resolves marked and last-read chapters, destroys detached book', async () => {
  const doc = new DOMParser().parseFromString('<html><body><h1 id="one">One</h1><p>First</p><h1 id="two">Two</h1><p>Second</p></body></html>', 'text/xml');
  doc.body = doc.getElementsByTagName('body')[0];
  let destroyed = false;
  const section = {index:0,href:'one.xhtml',document:doc,load:async()=>{},unload:()=>{}};
  const toc = [{label:'One',href:'one.xhtml#one'},{label:'Two',href:'one.xhtml#two'}];
  const epub = { default:()=>({ open:async()=>{}, ready:Promise.resolve(), loaded:{navigation:Promise.resolve({toc})}, spine:{spineItems:[section],get:()=>section},load:()=>{},destroy:()=>{destroyed=true;} }), EpubCFI:class {constructor(value){this.value=value;this.spinePos=0;}toRange(){return {startContainer:doc.getElementById(this.value)};} } };
  const api=await queries({getFile:async()=>({blob:new Blob(['fake'])}),getProgress:async()=>({cfi:'two'})},epub);
  const rows=await api.podcastChapters('book',[{bookKey:'book',cfi:'one'}]);
  assert.equal(rows.length,2);
  assert.equal(rows[0].text,extractPodcastChapter(doc,toc,doc.getElementById('one'),'One').text);
  assert.equal(rows[0].marked,true);
  assert.equal(rows[1].lastRead,true);
  assert.equal(destroyed,true);
});
test('missing downloaded file produces actionable error', async () => {
  const api=await queries({getFile:async()=>null});
  await assert.rejects(api.podcastChapters('missing'),/download this book/);
});
for (const id of ['John', 'Ps']) test(`Kotlin ${id} parity fixture matches current web helper and bundled data`, async () => {
  const bibleSource = (await readFile(new URL('../../src/lib/bible.js', import.meta.url), 'utf8')).replace("import { apiUrl } from './api.js';", 'const apiUrl=p=>p;');
  const { bibleLessonPayload, plain } = await import(`data:text/javascript;base64,${Buffer.from(bibleSource).toString('base64')}`);
  const fixture = JSON.parse(await readFile(new URL(`../../native-android/app/src/test/resources/picker/${id}.json`, import.meta.url), 'utf8'));
  const data = JSON.parse(await readFile(new URL(`../../public/bible/kjv/${id}.json`, import.meta.url), 'utf8'));
  assert.deepEqual(fixture.raw, data.chapters[fixture.chapter - 1]);
  assert.deepEqual(fixture.expected, bibleLessonPayload(fixture.name, fixture.chapter, 'KJV', fixture.raw.map(plain)));
});
