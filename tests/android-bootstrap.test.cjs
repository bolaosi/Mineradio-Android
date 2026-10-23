const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/android-bootstrap.js'), 'utf8');
function harness(native = false, initial = null) {
  const calls = [], events = [], store = new Map();
  if (initial) store.set('mineradio-backend-v1', JSON.stringify(initial));
  let nativeConfig = initial || { baseUrl: '', token: '' };
  const win = { location: { origin: 'https://mineradio.local', href: 'https://mineradio.local/index.html' },
    fetch: async (...args) => { calls.push(args); return new Response('{"ok":true}', { status: 200 }); },
    localStorage: { getItem: k => store.get(k), setItem: (k,v) => store.set(k,v) }, dispatchEvent: e => events.push(e.type) };
  if (native) win.MineradioNative = { postMessage: raw => {
    const msg = JSON.parse(raw); calls.push(msg);
    if (msg.type === 'cancel' || msg.path === '/api/slow') return;
    if (msg.type === 'saveConfig') nativeConfig = msg.config;
    const result = /Config$/.test(msg.type) ? nativeConfig : { status: 200, body: '{"ok":true}' };
    queueMicrotask(() => win.MineradioNative.onmessage({ data: JSON.stringify({ id: msg.id, result }) }));
  } };
  const context = vm.createContext({ window: win, URL, Request, Response, Headers, Event, DOMException, setTimeout, clearTimeout, module: { exports: {} } });
  vm.runInContext(source, context);
  return { win, calls, store, events, normalize: context.module.exports.normalizeConfig };
}
test('unconfigured backend fails honestly while static resources remain usable', async () => {
  const h = harness();
  const response = await h.win.fetch('/api/login/status');
  assert.equal(response.status, 503); assert.equal((await response.json()).error, 'BACKEND_NOT_CONFIGURED');
  await h.win.fetch('vendor/three.r128.min.js'); assert.equal(h.calls.length,1);
});
test('backend address and token persist and changes route immediately with Bearer header', async () => {
  const h = harness();
  await h.win.AndroidRuntime.saveConfig({ baseUrl: 'https://owned.example/', token: 'private-test-token' });
  await h.win.fetch('/api/login/cookie', { method: 'POST', body: '{"cookie":"synthetic"}' });
  assert.equal(h.calls[0][0], 'https://owned.example/api/login/cookie');
  assert.equal(h.calls[0][1].headers.get('Authorization'), 'Bearer private-test-token');
  const reload = harness(false, JSON.parse(h.store.get('mineradio-backend-v1')));
  assert.equal((await reload.win.AndroidRuntime.getConfig()).baseUrl,'https://owned.example');
  await h.win.AndroidRuntime.saveConfig({ baseUrl: 'https://other.example', token: 'changed' });
  await h.win.fetch('/api/health'); assert.equal(h.calls[1][0], 'https://other.example/api/health');
});
test('backend validation rejects credential URLs, arbitrary cleartext and paths', () => {
  const h = harness();
  for (const baseUrl of ['javascript:alert(1)', 'http://public.example', 'https://u:p@example.org', 'https://example.org/api', 'https://example.org?token=x'])
    assert.throws(() => h.normalize({ baseUrl, token: '' }));
  assert.equal(h.normalize({ baseUrl: 'http://192.168.1.4:3000', token: '' }).baseUrl, 'http://192.168.1.4:3000');
});
test('native transport initializes before first API, keeps token out of request payload, aborts and ignores late replies', async () => {
  const h = harness(true, { baseUrl: 'https://owned.example', token: 'test-secret' });
  const response = await h.win.fetch('/api/login/status'); assert.equal(response.status,200);
  assert.equal(h.calls[0].type,'getConfig'); assert.equal(h.calls[1].path,'/api/login/status');
  assert.equal(JSON.stringify(h.calls[1]).includes('test-secret'),false);
  const c = new AbortController(); const request = h.win.fetch('/api/slow', { signal: c.signal });
  await new Promise(r=>setImmediate(r)); c.abort();
  await assert.rejects(request, { name: 'AbortError' }); assert.equal(h.calls.at(-1).type,'cancel');
});
test('native config persistence and HTTP failure status are not converted into success', async () => {
  const h = harness(true);
  await h.win.AndroidRuntime.saveConfig({ baseUrl:'https://owned.example',token:'test' });
  assert.equal((await h.win.AndroidRuntime.getConfig()).baseUrl,'https://owned.example');
  await assert.rejects(h.win.AndroidRuntime.request('https://unrelated.example/api/health'), /API/);
  await assert.rejects(h.win.AndroidRuntime.request('/api/%2e%2e/health'), /API/);
});
test('switching backend discards late account responses from the previous service', async () => {
  const h = harness(true, { baseUrl: 'https://first.example', token: 'first' });
  const request = h.win.fetch('/api/slow');
  await new Promise(r=>setImmediate(r));
  const old = h.calls.find(c=>c.path === '/api/slow');
  await h.win.AndroidRuntime.saveConfig({ baseUrl:'https://second.example',token:'second' });
  const rejected = assert.rejects(request, { name:'AbortError' });
  h.win.MineradioNative.onmessage({ data: JSON.stringify({ id:old.id, result:{ status:200, body:'{"loggedIn":true}' } }) });
  await rejected;
});

test('APK built-in mode routes its first API through native bridge without address or token', async () => {
  const h = harness(true, { mode: 'builtin', ready: true });
  assert.deepEqual(JSON.parse(JSON.stringify(await h.win.AndroidRuntime.getConfig())), { mode: 'builtin', ready: true });
  const response = await h.win.fetch('/api/login/qr/key');
  assert.equal(response.status, 200);
  const api = h.calls.find(c => c.type === 'request');
  assert.equal(api.path, '/api/login/qr/key');
  assert.equal(api.backend, undefined); assert.equal(api.token, undefined);
  assert.equal(h.calls.filter(Array.isArray).length, 0);
  await assert.rejects(h.win.AndroidRuntime.saveConfig({ baseUrl: 'https://unrelated.example', token: 'unused' }), /无需配置/);
});
