'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { PassThrough } = require('node:stream');
const { createQrController, mount } = require('../../android-online');
const TOKEN = 'test-token-only-000000000000000000000000';
const ROOT = path.resolve(__dirname, '..');

function harness(initialFiles) {
  let handler;
  const files = new Map(initialFiles || []), logs = [];
  const api = {
    login_status: async () => ({ body: { data: { code: 200, profile: null, account: null } } }),
    user_account: async () => ({ body: { code: 301 } }),
    login_qr_key: async () => ({ body: { data: { unikey: 'fake-key' } } }),
    login_qr_create: async () => ({ body: { data: { qrimg: 'data:image/png;base64,YQ==', qrurl: 'https://music.163.com/login?codekey=fake-key' } } }),
    login_qr_check: async () => ({ body: { code: 803 }, cookie: ['MUSIC_U=fake-test-cookie'] }),
    logout: async () => ({ body: { code: 200 } }),
  };
  const fakeFs = Object.assign({}, fs, {
    existsSync: file => files.has(file) || fs.existsSync(file),
    readFileSync: (file, encoding) => files.has(file) ? files.get(file) : fs.readFileSync(file, encoding),
    writeFileSync: (file, value) => files.set(file, value),
    renameSync: (from, to) => { files.set(to, files.get(from)); files.delete(from); },
  });
  const sandbox = vm.createContext({
    require: name => name === 'NeteaseCloudMusicApi' ? new Proxy(api, { get: (_, key) => typeof api[key] === 'function' ? (...args) => api[key](...args) : undefined }) : name === './safe-logging' ? require('../safe-logging') : name === 'fs' ? fakeFs : name === './dj-analyzer' ? {} : name === 'http' ? { createServer: cb => { handler = cb; return { listen: (port, host, ready) => ready() }; } } : require(name),
    process: { env: { MINERADIO_API_TOKEN: TOKEN, COOKIE_FILE: '/test-only/.cookie', QQ_COOKIE_FILE: '/test-only/.qq-cookie' } },
    __dirname: ROOT, Buffer, URL, URLSearchParams, AbortController, fetch, setTimeout, clearTimeout,
    module: { exports: {} }, console: { log: (...a) => logs.push(a), warn: (...a) => logs.push(a), error: (...a) => logs.push(a) },
    __qqRequest: async () => JSON.stringify({ code: 0, nick: { code: 1000, data: {} } }),
  });
  vm.runInContext(fs.readFileSync(path.join(ROOT, 'server.js'), 'utf8'), sandbox);
  vm.runInContext('requestText = (...args) => __qqRequest(...args)', sandbox);
  async function request(url, options = {}) {
    const req = new PassThrough(); req.url = url; req.method = options.method || 'GET';
    req.headers = Object.assign({ authorization: 'Bearer ' + TOKEN }, options.headers || {});
    const headers = {}; let status = 200, output = '';
    const result = new Promise(resolve => {
      const res = { setHeader: (k, v) => headers[k.toLowerCase()] = v, writeHead: (s, h) => { status = s; Object.assign(headers, h); }, end: data => { output += data || ''; resolve({ status, headers, data: output ? JSON.parse(output) : null }); } };
      Promise.resolve(handler(req, res)).catch(error => { throw error; });
    });
    req.end(options.body === undefined ? '' : JSON.stringify(options.body));
    return result;
  }
  return { request, files, api, sandbox, logs };
}

test('remote backend requires token and restricts origins, even health; installer writes disabled', async () => {
  const h = harness();
  assert.equal((await h.request('/api/health', { headers: { authorization: '' } })).status, 401);
  assert.equal((await h.request('/api/health', { headers: { origin: 'https://evil.invalid' } })).status, 403);
  const health = await h.request('/api/health', { headers: { origin: 'https://mineradio.local' } });
  assert.equal(health.status, 200); assert.equal(health.data.mode, 'single-user'); assert.equal(health.data.authRequired, true);
  assert.equal(health.headers['access-control-allow-origin'], 'https://mineradio.local');
  assert.equal((await h.request('/api/update/patch', { method: 'POST' })).status, 403);
  assert.equal((await h.request('/api/logout')).status, 405);
  assert.equal((await h.request('/server.js')).status, 404);
});
test('invalid or unverifiable Netease cookie is never saved or reported logged in', async () => {
  const h = harness();
  assert.equal((await h.request('/api/login/cookie', { method: 'POST', body: { cookie: 'foo=bar' } })).status, 400);
  const result = await h.request('/api/login/cookie', { method: 'POST', body: { cookie: 'MUSIC_U=fake-test-cookie' } });
  assert.equal(result.status, 422); assert.equal(result.data.loggedIn, false); assert.equal(result.data.saved, false);
  assert.equal(h.files.has('/test-only/.cookie'), false);
  const qr = await h.request('/api/login/qr/check?key=fake-key');
  assert.equal(qr.data.code, 803); assert.equal(qr.data.hasCookie, true); assert.equal(qr.data.loggedIn, false); assert.equal(qr.data.saved, false);
  assert.equal(h.files.has('/test-only/.cookie'), false);
});
test('Netease verified account persists and logout clears disk/session; id=0 is rejected', async () => {
  const h = harness();
  h.api.login_status = async () => ({ body: { data: { code: 200, profile: { userId: 42, nickname: 'Test' } } } });
  let result = await h.request('/api/login/cookie', { method: 'POST', body: { cookie: 'MUSIC_U=fake-test-cookie' } });
  assert.equal(result.data.loggedIn, true); assert.equal(result.data.verified, true); assert.equal(result.data.saved, true);
  assert.equal(h.files.get('/test-only/.cookie'), 'MUSIC_U=fake-test-cookie');
  assert.equal((await h.request('/api/login/status')).data.userId, 42);
  h.api.login_status = async () => ({ body: { data: { code: 500, profile: { userId: 42 } } } });
  result = await h.request('/api/login/status'); assert.equal(result.data.loggedIn, false);
  h.api.login_status = async () => ({ body: { data: { code: 200, account: { id: 0 } } } });
  result = await h.request('/api/login/status'); assert.equal(result.data.loggedIn, false);
  await h.request('/api/logout', { method: 'POST' }); assert.equal(h.files.get('/test-only/.cookie'), '');
  assert.equal((await h.request('/api/login/status')).data.credentialState, 'missing');
});
test('saved credentials are restored as unverified until a fresh official check succeeds', async () => {
  const h = harness([['/test-only/.cookie', 'MUSIC_U=restored-test-only'], ['/test-only/.qq-cookie', 'uin=12345; qm_keyst=restored-test-only']]);
  assert.equal((await h.request('/api/login/status')).data.loggedIn, false);
  assert.equal((await h.request('/api/qq/login/status')).data.loggedIn, false);
  h.api.login_status = async () => ({ body: { data: { code: 200, profile: { userId: 42 } } } });
  assert.equal((await h.request('/api/login/status')).data.verified, true);
  const failedImport = await h.request('/api/login/cookie', { method: 'POST', body: { cookie: '' } });
  assert.equal(failedImport.status, 400); assert.equal(h.files.get('/test-only/.cookie'), 'MUSIC_U=restored-test-only');
});
test('QQ cookie shape/public identity is not verification; authenticated response required', async () => {
  const h = harness();
  assert.equal((await h.request('/api/qq/login/cookie', { method: 'POST', body: { cookie: 'uin=12345' } })).status, 400);
  let result = await h.request('/api/qq/login/cookie', { method: 'POST', body: { cookie: 'uin=12345; qm_keyst=fake-key; nick=fake' } });
  assert.equal(result.status, 422); assert.equal(result.data.loggedIn, false); assert.equal(h.files.has('/test-only/.qq-cookie'), false);
  h.sandbox.__qqRequest = async (url, options, payload) => {
    const body = JSON.parse(payload); assert.equal(body.nick.method, 'GetLoginUserInfo'); assert.deepEqual(body.nick.param, {});
    return JSON.stringify({ code: 0, nick: { code: 0, data: { info: { nick: 'Verified', uin: '12345' } } } });
  };
  result = await h.request('/api/qq/login/cookie', { method: 'POST', body: { cookie: 'uin=12345; qm_keyst=fake-key' } });
  assert.equal(result.data.loggedIn, true); assert.equal(result.data.verified, true); assert.equal(result.data.nickname, 'Verified');
  h.sandbox.__qqRequest = async () => { throw new Error('PRIVATE_COOKIE_SHOULD_NOT_APPEAR'); };
  result = await h.request('/api/qq/login/status'); assert.equal(result.data.loggedIn, false); assert.equal(result.data.credentialState, 'unverified');
  assert.ok(!JSON.stringify(h.logs).includes('PRIVATE_COOKIE_SHOULD_NOT_APPEAR'));
  await h.request('/api/qq/logout', { method: 'POST' }); assert.equal(h.files.get('/test-only/.qq-cookie'), '');
});

function qrHarness(replies) {
  const states = [], verified = [], timers = new Map(), paths = []; let seq = 0;
  const ctrl = createQrController({
    request: async url => { paths.push(url); var reply = replies.shift(); if (reply instanceof Error) throw reply; return typeof reply === 'function' ? reply(url) : reply; },
    onState: s => states.push(s), onVerified: account => verified.push(account),
    setTimeout: cb => { timers.set(++seq, cb); return seq; }, clearTimeout: id => timers.delete(id),
  });
  async function tick() { const entry = timers.entries().next().value; assert.ok(entry, 'a poll must be scheduled'); timers.delete(entry[0]); entry[1](); await new Promise(resolve => setImmediate(resolve)); }
  return { ctrl, states, verified, timers, paths, tick };
}
const image = { img: 'data:image/png;base64,YQ==' };
test('dependency diagnostic cannot log response cookie values', () => {
  const { installNeteaseLogFilter } = require('../safe-logging');
  const captured = [], logger = { log: (...args) => captured.push(args), warn: (...args) => captured.push(args), error: (...args) => captured.push(args) };
  installNeteaseLogFilter(logger);
  logger.log('[ERR]', { cookie: ['MUSIC_U=DO_NOT_LOG_TEST'], body: { cookie: 'DO_NOT_LOG_TEST' } });
  assert.ok(!JSON.stringify(captured).includes('DO_NOT_LOG_TEST')); assert.equal(captured.length, 1);
});
test('QR waiting → scan → confirmation → authenticated status; polling is serial and stops', async () => {
  const h = qrHarness([{ key: 'test' }, image, { code: 801 }, { code: 802 }, { code: 803, saved: true, verified: true, loggedIn: true }, { loggedIn: true, verified: true, userId: 42 }]);
  await h.ctrl.start(); await h.tick(); await h.tick(); await h.tick();
  assert.deepEqual(h.states.map(s => s.phase), ['loading', 'waiting', 'waiting', 'scanned', 'verifying', 'success']);
  assert.equal(h.verified[0].userId, 42); assert.equal(h.timers.size, 0);
});
test('QR expiry, retry and cancellation discard late key/image/poll results', async () => {
  const h = qrHarness([{ key: 'test' }, image, { code: 800 }, { key: 'retry' }, image]);
  await h.ctrl.start(); await h.tick(); assert.equal(h.ctrl.getState().phase, 'expired'); assert.equal(h.timers.size, 0);
  await h.ctrl.start(); assert.equal(h.ctrl.getState().phase, 'waiting'); h.ctrl.cancel(); assert.equal(h.timers.size, 0);
  let resolve; const late = qrHarness([() => new Promise(r => resolve = r)]);
  const pending = late.ctrl.start(); late.ctrl.cancel(); resolve({ key: 'late' }); await pending;
  assert.equal(late.paths.length, 1); assert.equal(late.timers.size, 0);
});
test('QR 803 with only a cookie or invalid final account cannot complete login', async () => {
  for (const result of [{ code: 803, hasCookie: true }, { code: 803, loggedIn: true, verified: true, saved: true }]) {
    const h = qrHarness([{ key: 'test' }, image, result, { loggedIn: false }]);
    await h.ctrl.start(); await h.tick(); assert.equal(h.ctrl.getState().phase, 'error'); assert.equal(h.verified.length, 0); assert.equal(h.timers.size, 0);
  }
});
test('QR missing key, malformed image and network failure are explicit retriable errors', async () => {
  for (const replies of [[{}], [{ key: 'test' }, { img: 'javascript:bad' }], [{ key: 'test' }, image, new Error('offline')]]) {
    const h = qrHarness(replies); await h.ctrl.start(); if (h.timers.size) await h.tick();
    assert.equal(h.ctrl.getState().phase, 'error'); assert.equal(h.verified.length, 0);
  }
});

function windowHarness() {
  const elements = new Map();
  function element() { return { value: '', textContent: '', style: {}, classList: { toggle() {}, remove() {}, add() {} }, setAttribute() {}, removeAttribute() {}, appendChild() {}, insertAdjacentElement() {} }; }
  const doc = { getElementById: id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); }, createElement: element, querySelector: () => null, head: element(), body: element() };
  const w = { document: doc, AndroidRuntime: { getConfig: async () => ({ baseUrl: 'https://self.invalid', token: TOKEN }) },
    apiJson: async () => ({ loggedIn: false }), addEventListener() {}, closeLoginModal() {}, setLoginProvider() {},
    refreshLoginStatus: async () => null, refreshQQLoginStatus: async () => null,
    loginStatus: { loggedIn: true, verified: true, hasCookie: true }, qqLoginStatus: { loggedIn: false },
    userPlaylists: [{ provider: 'netease' }, { provider: 'qq' }], qqPlaylists: [], myPodcastItems: {}, homeDiscoverState: {},
    loginProvider: 'qq', renderUserBtn() {}, updateLikeButtons() {}, showToast() {}, refreshUserPlaylists() {}, loadHomeDiscover() {} };
  mount(w); return w;
}
test('network failure invalidates the displayed verified account while preserving credential uncertainty', async () => {
  const w = windowHarness(); const info = await w.refreshLoginStatus();
  assert.equal(info.loggedIn, false); assert.equal(info.verified, false); assert.equal(info.credentialState, 'unverified'); assert.equal(info.hasCookie, true);
  assert.equal(w.loginStatus.loggedIn, false); assert.equal(w.userPlaylists.length, 1); assert.equal(w.userPlaylists[0].provider, 'qq');
});
test('QQ import result arriving after modal closure cannot update the account UI', async () => {
  const w = windowHarness(); w.loginStatus = { loggedIn: false }; let resolve;
  w.apiJson = () => new Promise(r => resolve = r);
  w.document.getElementById('qq-cookie-input').value = 'uin=12345; qm_keyst=fake-test';
  const pending = w.submitQQCookieLogin(); w.closeLoginModal();
  resolve({ saved: true, verified: true, loggedIn: true, userId: '12345' }); await pending;
  assert.equal(w.qqLoginStatus.loggedIn, false); assert.equal(w.document.getElementById('qq-cookie-input').value, '');
});
