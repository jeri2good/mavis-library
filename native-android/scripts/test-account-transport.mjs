import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../app/src/main/assets/native-account-transport.js', import.meta.url), 'utf8');
const seen = [];
let state = 'active';
let expiresAt = null;
class Xhr {
  headers = {};
  open(method, url) { this.url = url; }
  send() {}
  setRequestHeader(key, value) { this.headers[key] = value; }
}
const window = {
  MavisNativeApiBase: 'https://site.example.org',
  fetch: async request => {
    seen.push(request);
    const path = new URL(request.url).pathname;
    return new Response(JSON.stringify(path === '/api/subscription/status' ? {state, expiresAt} : {owner: false, assistant: {model: 'fixture'}}), {headers: {'content-type': 'application/json'}});
  }
};
const sandbox = {window, XMLHttpRequest: Xhr, Request, Headers, Response, URL, WeakMap, Set, Object, String, Date, JSON, location: {href: 'https://localhost/'}};
vm.runInNewContext(source, sandbox);
window.MavisNativeAccount.setToken('fixture-account-session');
for (const feature of ['assistant', 'tts', 'rank', 'study']) {
  await window.fetch(`https://site.example.org/api/${feature}`, {method: 'POST', headers: {'x-mavis-access': 'fixture-owner'}, body: '{}'});
  assert.equal(seen.at(-1).headers.get('Authorization'), 'Bearer fixture-account-session');
  assert.equal(seen.at(-1).headers.get('x-mavis-access'), 'fixture-owner');
}
for (const url of ['https://foreign.example.org/api/assistant', 'https://site.example.org.evil.test/api/tts', 'https://site.example.org/api/voices', 'https://site.example.org/api/account/signin']) {
  await window.fetch(url);
  assert.equal(seen.at(-1).headers.get('Authorization'), null);
}
let flags = await (await window.fetch('https://site.example.org/api/features')).json();
assert.equal(flags.owner, true);
assert.equal(flags.nativeSubscriber, true);
for (const value of ['none', 'pending', 'denied', 'revoked']) {
  state = value;
  assert.equal((await (await window.fetch('https://site.example.org/api/features')).json()).owner, false);
}
state = 'active'; expiresAt = Date.now() - 100;
assert.equal((await (await window.fetch('https://site.example.org/api/features')).json()).owner, false);
const xhr = new Xhr(); xhr.open('POST', 'https://site.example.org/api/study'); xhr.setRequestHeader('x-mavis-access', 'fixture-owner'); xhr.send();
assert.equal(xhr.headers.Authorization, 'Bearer fixture-account-session');
assert.equal(xhr.headers['x-mavis-access'], 'fixture-owner');
window.MavisNativeAccount.setToken('');
await window.fetch('https://site.example.org/api/tts');
assert.equal(seen.at(-1).headers.get('Authorization'), null);
const anonymousXhr = new Xhr(); anonymousXhr.open('POST', 'https://site.example.org/api/study'); anonymousXhr.send();
assert.equal(anonymousXhr.headers.Authorization, undefined);
assert.equal(sandbox.localStorage, undefined);
console.log('Native account transport: fake fetch/XHR headers, origin isolation, sign-out, and subscriber UI gating passed.');
