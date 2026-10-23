/* MOCK FLOW TEST ONLY: no real account, service login, deploy or scannable QR is tested here. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const assets = path.resolve(__dirname, '../app/src/main/assets');
  const output = path.resolve(__dirname, '../output/playwright'); fs.mkdirSync(output, { recursive: true });
  const token = 'mock-flow-test-only-no-real-credentials';
  const pixel = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jQ1sAAAAASUVORK5CYII=';
  const requests = [], checks = [], pageErrors = [], consoleErrors = [], externalRequests = [];
  const state = { health: true, mode: 'pending', account: null, key: 0, qrChecks: {}, held: null, statusCalls: 0, playlistCalls: 0 };
  const account = { provider: 'netease', loggedIn: true, verified: true, saved: true, userId: '100001', nickname: '模拟流程账号', vipType: 0 };
  function json(res, body, status = 200) { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); }
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost'), name = url.pathname;
    requests.push({ method: req.method, path: name }); // Deliberately exclude bodies, auth and Cookie values.
    if (name.startsWith('/api/')) {
      if (req.headers.authorization !== 'Bearer ' + token) return json(res, { message: '模拟后端拒绝：访问令牌不正确' }, 401);
      if (name === '/api/health') return json(res, state.health ? { ok: true, service: 'mineradio', apiVersion: 1, mode: 'single-user', authRequired: true } : { ok: true, service: 'unrelated-service' });
      if (name === '/api/login/status') { state.statusCalls++; return json(res, state.account || { loggedIn: false, verified: false }); }
      if (name === '/api/qq/login/status') return json(res, { provider: 'qq', loggedIn: false, verified: false });
      if (name === '/api/login/qr/key') { const key = 'mock-key-' + ++state.key; state.qrChecks[key] = 0; return json(res, { key }); }
      if (name === '/api/login/qr/create') return json(res, { img: state.badImage ? 'data:image/png;base64,YQ==' : pixel, url: 'https://example.invalid/mock-only-not-scannable' });
      if (name === '/api/login/qr/check') {
        const key = url.searchParams.get('key'), count = ++state.qrChecks[key];
        if (state.mode === 'late') { state.held = res; return; }
        if (state.mode === 'expired') return json(res, { code: 800 });
        if (state.mode === 'unverified') return json(res, { code: 803, saved: true, verified: false, loggedIn: false, message: '模拟：已保存凭据，但官方账号验证失败' });
        if (state.mode === 'success') {
          const code = count === 1 ? 801 : count === 2 ? 802 : 803;
          if (code === 803) { state.account = account; return json(res, { code, ...account }); }
          return json(res, { code });
        }
        return json(res, { code: 801 });
      }
      if (name === '/api/user/playlists') { state.playlistCalls++; return json(res, { playlists: [{ id: 'mock-playlist', name: '模拟验证后刷新的歌单', trackCount: 0 }] }); }
      if (name === '/api/podcast/my') return json(res, { collections: [] });
      if (name === '/api/discover/home') return json(res, { loggedIn: !!state.account, songs: [], playlists: [], podcasts: [], dailySongs: [], recommendations: [] });
      if (name === '/api/logout') { state.account = null; return json(res, { ok: true }); }
      if (name === '/api/qq/login/cookie') return json(res, { provider: 'qq', loggedIn: false, verified: false, saved: false, message: '模拟：QQ Cookie 校验失败，未保存' }, 401);
      return json(res, { songs: [], ids: [], playlists: [], collections: [] });
    }
    if (name === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    const file = path.resolve(assets, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', file.endsWith('.html') ? 'text/html; charset=utf-8' : file.endsWith('.js') ? 'application/javascript' : 'application/octet-stream');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  let browser, page, passed = false;
  function record(name) { checks.push(name); console.log('PASS ' + name); }
  async function closeLogin() {
    const cancel = page.locator('#login-modal .btn-row button').filter({ hasText: '取消' });
    if (await page.locator('#login-modal').evaluate(el => el.classList.contains('show'))) {
      await cancel.waitFor({ state: 'visible' }); await cancel.click();
      await page.waitForFunction(() => !document.getElementById('login-modal').classList.contains('show'));
    }
  }
  async function enterPage() {
    await page.waitForFunction(() => !!window.AndroidOnlineSettings);
    if (await page.evaluate(() => document.body.classList.contains('splash-active'))) {
      await page.waitForFunction(() => window.splashReadyToEnter === true, null, { timeout: 15000 });
      await page.locator('#splash').click({ position: { x: 25, y: 60 } });
      await page.waitForFunction(() => !document.body.classList.contains('splash-active'));
    }
    await page.waitForTimeout(350);
    const skip = page.locator('#visual-guide-skip'); if (await skip.count() && await skip.isVisible()) await skip.click();
    await closeLogin();
  }
  async function openNetease() {
    if (!(await page.locator('#login-modal').evaluate(el => el.classList.contains('show')))) await page.locator('#user-btn').click();
    await page.waitForFunction(() => document.getElementById('login-modal').classList.contains('show'));
    await page.locator('#login-provider-netease').click();
  }
  try {
    const launch = { headless: true, args: ['--no-proxy-server', '--autoplay-policy=no-user-gesture-required', '--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] };
    if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) launch.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
    browser = await chromium.launch(launch);
    page = await browser.newPage({ viewport: { width: 390, height: 844 }, acceptDownloads: true });
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('requestfailed', request => console.log(JSON.stringify({ failedPath: new URL(request.url()).pathname, reason: request.failure()?.errorText })));
    page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
    await page.route('**/*', route => {
      if (route.request().url().startsWith(origin + '/') || route.request().url().startsWith('data:') || route.request().url().startsWith('blob:')) return route.continue();
      externalRequests.push(new URL(route.request().url()).origin); return route.abort();
    });
    await page.goto(origin); await enterPage();
    await openNetease();
    await page.waitForFunction(() => /尚未配置在线服务/.test(document.getElementById('qr-status').textContent));
    assert.equal(await page.locator('#qr-shell').isVisible(), false);
    assert.equal(await page.locator('#qr-img').getAttribute('src'), null);
    assert.equal(await page.locator('#android-save-qr').isVisible(), false);
    await page.screenshot({ path: path.join(output, 'android-1.0.3-no-backend.png') });
    record('unconfigured login has no broken image or save button and clearly names the missing backend');
    await page.locator('#refresh-qr-btn').click();
    assert.equal(await page.locator('#android-online-settings').isVisible(), true);
    await page.locator('#online-base').fill(origin); await page.locator('#online-token').fill(token);
    await page.locator('#online-test').click();
    await page.waitForFunction(() => { const image = document.getElementById('qr-img'); return image.complete && image.naturalWidth > 0 && document.getElementById('qr-shell').style.display !== 'none'; });
    assert.equal(await page.locator('#android-online-settings').isVisible(), false);
    assert.equal(await page.locator('#android-save-qr').isEnabled(), true);
    record('testing a configured service returns directly to a successfully decoded QR image');
    await closeLogin();
    await page.reload(); await enterPage();
    await page.locator('#android-online-button').click();
    assert.equal(await page.locator('#online-base').inputValue(), origin);
    assert.equal(await page.locator('#online-token').inputValue(), token);
    record('backend configuration persists across a full page reload');
    await page.locator('#online-test').click();
    await page.waitForFunction(() => /连接通过/.test(document.getElementById('online-status').textContent));
    record('connection detection accepts the authenticated single-user API contract');
    state.health = false;
    await page.locator('#online-test').click();
    await page.waitForFunction(() => /连接失败/.test(document.getElementById('online-status').textContent));
    record('connection detection rejects an unrelated server without a false success');
    state.health = true;
    await page.locator('#online-token').fill('invalid-mock-only-token');
    await page.locator('#online-test').click();
    await page.waitForFunction(() => /连接失败/.test(document.getElementById('online-status').textContent));
    assert.equal(await page.evaluate(() => !!window.loginStatus.loggedIn || !!window.qqLoginStatus.loggedIn), false);
    record('wrong backend token fails connection and keeps both accounts logged out');
    await page.locator('#online-token').fill(token); await page.locator('#online-save').click();
    await page.waitForFunction(() => /设置已保存/.test(document.getElementById('online-status').textContent));
    await page.locator('#online-close').click();

    state.badImage = true; await openNetease();
    await page.waitForFunction(() => /图片无法显示/.test(document.getElementById('qr-status').textContent));
    assert.equal(await page.locator('#qr-shell').isVisible(), false);
    assert.equal(await page.locator('#android-save-qr').isVisible(), false);
    record('a malformed PNG cannot leave a broken image frame or an enabled save button');
    state.badImage = false; await closeLogin();

    state.mode = 'expired';
    await openNetease();
    await page.waitForFunction(() => /过期/.test(document.getElementById('qr-status').textContent));
    const previousKey = state.key;
    state.mode = 'pending'; await page.locator('#refresh-qr-btn').click();
    await page.waitForFunction(() => /^data:image\/png;base64,/.test(document.getElementById('qr-img').src));
    assert.ok(state.key > previousKey);
    const download = page.waitForEvent('download'); await page.locator('#android-save-qr').click();
    await (await download).saveAs(path.join(output, 'mock-only-nonscannable-qr.png'));
    record('expired QR refresh creates a new key; a legal test PNG can be saved (not a real QR)');
    await closeLogin();
    const countAfterClose = requests.filter(request => request.path === '/api/login/qr/check').length;
    await page.waitForTimeout(2300);
    assert.equal(requests.filter(request => request.path === '/api/login/qr/check').length, countAfterClose);
    record('closing the login modal stops QR polling');

    state.mode = 'unverified'; await openNetease();
    await page.waitForFunction(() => /验证失败/.test(document.getElementById('qr-status').textContent));
    assert.equal(await page.evaluate(() => window.loginStatus.loggedIn), false);
    record('803 with saved credentials but failed verification does not log in');
    await closeLogin();

    state.mode = 'late'; await openNetease();
    await new Promise((resolve, reject) => {
      const started = Date.now();
      const timer = setInterval(() => { if (state.held) { clearInterval(timer); resolve(); } else if (Date.now() - started > 10000) { clearInterval(timer); reject(new Error('late response fixture did not receive a poll')); } }, 50);
    });
    await closeLogin(); const statusBeforeLate = state.statusCalls;
    json(state.held, { code: 803, ...account }); state.held = null;
    await page.waitForTimeout(700);
    assert.equal(await page.evaluate(() => window.loginStatus.loggedIn), false);
    assert.equal(state.statusCalls, statusBeforeLate);
    record('a late successful response after close cannot mark the account logged in');

    state.mode = 'success'; await openNetease();
    await page.waitForFunction(() => /等待.*扫码|同屏/.test(document.getElementById('qr-status').textContent));
    await page.waitForFunction(() => /已扫码/.test(document.getElementById('qr-status').textContent), null, { timeout: 10000 });
    const statusBeforeSuccess = state.statusCalls;
    await page.waitForFunction(() => window.loginStatus.loggedIn && window.loginStatus.verified, null, { timeout: 12000 });
    assert.ok(state.statusCalls > statusBeforeSuccess);
    await page.waitForFunction(() => (window.userPlaylists || []).some(playlist => playlist.name === '模拟验证后刷新的歌单'));
    assert.ok(state.playlistCalls > 0);
    record('key/create/801/802/803 is followed by independent verified status and playlist refresh');
    await page.waitForTimeout(300); await page.screenshot({ path: path.join(output, 'android-online-mock-verified.png') });
    await closeLogin();
    await page.locator('#user-btn').click(); await page.locator('#account-logout-btn').click();
    await page.waitForFunction(() => !window.loginStatus.loggedIn);
    record('account logout clears the displayed session');

    state.mode = 'pending'; await openNetease(); await page.locator('#login-provider-qq').click();
    await page.locator('#qq-cookie-input').fill('mock-invalid-cookie-with-no-account-data');
    await page.locator('#qq-cookie-save-btn').click();
    await page.waitForFunction(() => /校验失败/.test(document.getElementById('qr-status').textContent));
    assert.equal(await page.evaluate(() => window.qqLoginStatus.loggedIn), false);
    assert.equal(await page.evaluate(() => window.loginStatus.loggedIn), false);
    await page.waitForTimeout(300); await page.screenshot({ path: path.join(output, 'android-online-mock-qq-rejected.png') });
    record('rejected QQ Cookie import keeps both accounts logged out and shows the error');
    assert.deepEqual(pageErrors, []); assert.deepEqual(externalRequests, []);
    passed = true;
  } finally {
    if (state.held) { json(state.held, { code: 800 }); state.held = null; }
    const result = { passed, scope: 'Local mock flow only; no third-party authentication verified', checks, pageErrors, consoleErrors,
      expectedHttpErrors: '401 is deliberately returned by wrong-token and invalid-Cookie test cases', externalRequests, requests };
    fs.writeFileSync(path.join(output, 'android-online-mock-results.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify({ passed, checks, pageErrors, externalRequests }, null, 2));
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
