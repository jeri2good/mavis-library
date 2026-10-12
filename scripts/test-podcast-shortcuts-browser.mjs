import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';

const profile = await mkdtemp(join(tmpdir(), 'mavis-podcast-test-'));
const chrome = spawn(process.env.CHROME || 'google-chrome', ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--disable-background-networking', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank']);
const exited = new Promise(resolve => { chrome.once('exit', resolve); chrome.once('error', resolve); });
let socket;
let logs = '';
try {
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Chrome debugger did not start')), 10000);
    chrome.stderr.on('data', chunk => {
      logs += chunk;
      const match = logs.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) { clearTimeout(timer); resolve(new URL(match[1])); }
    });
    chrome.on('error', reject);
  });
  const targets = await (await fetch(`http://${endpoint.host}/json/list`)).json();
  socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
  await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }));
  let id = 0;
  const pending = new Map();
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const key = ++id;
    pending.set(key, { resolve, reject });
    socket.send(JSON.stringify({ id: key, method, params }));
  });
  const errors = [];
  socket.addEventListener('message', async event => {
    const data = JSON.parse(event.data);
    if (data.id) {
      const task = pending.get(data.id);
      pending.delete(data.id);
      if (data.error) task.reject(new Error(data.error.message)); else task.resolve(data.result);
    } else if (data.method === 'Runtime.exceptionThrown') errors.push(data.params.exceptionDetails.text + ': ' + (data.params.exceptionDetails.exception?.description || ''));
    else if (data.method === 'Fetch.requestPaused') {
      const request = data.params;
      if (new URL(request.request.url).hostname === '127.0.0.1') await call('Fetch.continueRequest', { requestId: request.requestId });
      else await call('Fetch.fulfillRequest', { requestId: request.requestId, responseCode: 200, body: Buffer.from('{}').toString('base64') });
    }
  });
  await call('Page.enable');
  await call('Runtime.enable');
  await call('Fetch.enable', { patterns: [{ urlPattern: 'http*' }] });
  await call('Page.navigate', { url: 'http://127.0.0.1:5175/tests/e2e/podcast-shortcuts.html' });
  let result = '';
  for (let i = 0; i < 300; i++) {
    const response = await call('Runtime.evaluate', { expression: 'document.body?.textContent || ""', returnByValue: true });
    result = response.result.value;
    if (result.startsWith('PASS:') || result.startsWith('FAIL:') || errors.length) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(errors.length, 0, errors.join('\n'));
  assert.ok(result.startsWith('PASS:'), result);
  console.log(result);
} finally {
  socket?.close();
  chrome.kill();
  await exited;
  await rm(profile, { recursive: true, force: true });
}
