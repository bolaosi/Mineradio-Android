/* Full bundled-page integration, with no backend, account or third-party request mocks. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const assets = path.resolve(__dirname, '../app/src/main/assets');
  const output = path.resolve(__dirname, '../output/playwright'); fs.mkdirSync(output, { recursive: true });
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mineradio-integration-'));
  const audio = path.join(temp, 'tone.mp3');
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=550:duration=30', '-codec:a', 'libmp3lame', audio]);
  const requests = [];
  const mime = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.bin': 'application/octet-stream', '.png': 'image/png' };
  const server = http.createServer((req, res) => {
    requests.push({ method: req.method, url: req.url });
    const name = new URL(req.url, 'http://localhost').pathname;
    const file = path.resolve(assets, '.' + (name === '/' ? '/index.html' : name));
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { console.log(JSON.stringify({ missing: name })); res.statusCode = 404; res.end(); return; }
    res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream'); res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  const results = [];
  try {
    const launch = { headless: true, args: ['--autoplay-policy=no-user-gesture-required', '--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] };
    if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) launch.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
    browser = await chromium.launch(launch);
    for (const width of [390, 1440]) {
      const page = await browser.newPage({ viewport: { width, height: width === 390 ? 844 : 900 } });
      const pageErrors = [], consoleErrors = [], failedRequests = [];
      page.on('pageerror', error => pageErrors.push(error.message));
      page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
      page.on('requestfailed', request => failedRequests.push({ url: request.url(), reason: request.failure()?.errorText }));
      await page.goto('http://127.0.0.1:' + server.address().port, { waitUntil: 'load', timeout: 60000 });
      await page.waitForTimeout(1200);
      const initial = await page.evaluate(() => ({ runtime: !!window.AndroidRuntime, local: !!window.AndroidLocal, online: !!window.AndroidOnlineSettings,
        splash: document.body.classList.contains('splash-active'), ready: window.splashReadyToEnter, loginModal: document.getElementById('login-modal').classList.contains('show') }));
      console.log(JSON.stringify({ width, initial, pageErrors, consoleErrors, failedRequests }));
      await page.screenshot({ path: path.join(output, 'android-integration-' + width + '-initial.png') });
      if (initial.splash) {
        await page.waitForFunction(() => window.splashReadyToEnter === true, { timeout: 15000 });
        await page.locator('#splash').click({ position: { x: 30, y: 60 } });
        await page.waitForFunction(() => !document.body.classList.contains('splash-active'));
      }
      await page.waitForTimeout(500);
      // New-user onboarding offers real dismissal buttons; use the UI before playback.
      for (const selector of ['#visual-guide-skip', '#login-guide-skip', '#login-modal .btn-row button:first-child']) {
        const element = page.locator(selector);
        if (await element.count() && await element.isVisible()) await element.click();
      }
      await page.locator('#android-online-button').click();
      assert.equal(await page.locator('#android-online-settings').isVisible(), true);
      assert.equal(await page.locator('#online-base').inputValue(), '');
      assert.match(await page.locator('#android-online-settings').innerText(), /本地音乐无需后端/);
      await page.locator('#online-close').click();
      await page.locator('#android-local-entry').click();
      await page.locator('#android-local-files').setInputFiles([
        { name: '本地第一首.mp3', mimeType: 'audio/mpeg', buffer: fs.readFileSync(audio) },
        { name: '本地第二首.mp3', mimeType: 'audio/mpeg', buffer: fs.readFileSync(audio) }
      ]);
      await page.waitForFunction(() => window.AndroidLocal.media.currentTime > 0.2 && !window.AndroidLocal.media.paused);
      assert.equal(await page.locator('#android-local-queue li').count(), 2);
      await page.locator('#android-local-close').click();
      const controlState = await page.locator('#play-btn').evaluate(el => ({ visible: el.getBoundingClientRect().width > 0, rect: el.getBoundingClientRect().toJSON() }));
      console.log(JSON.stringify({ width, controlState }));
      // The app's console button reveals its original bottom transport controls.
      if (!(await page.locator('#play-btn').isVisible())) {
        const consoleButton = page.getByRole('button', { name: '展开播放器控制台', exact: true });
        if (await consoleButton.isVisible()) await consoleButton.click();
      }
      await page.locator('#play-btn').click({ timeout: 10000 });
      await page.waitForFunction(() => window.audio.paused === true, { timeout: 10000 });
      await page.locator('#play-btn').click();
      await page.waitForFunction(() => window.audio.paused === false);
      await page.locator('#next-btn').click();
      await page.waitForFunction(() => window.AndroidLocal.controller.snapshot().index === 1 && !window.audio.paused);
      await page.locator('#prev-btn').click();
      await page.waitForFunction(() => window.AndroidLocal.controller.snapshot().index === 0);
      await page.locator('#user-btn').click();
      await page.waitForFunction(() => document.getElementById('login-modal').classList.contains('show'));
      await page.waitForFunction(() => /在线服务|后端/.test(document.getElementById('qr-status').textContent));
      assert.equal(await page.locator('#qr-shell').isVisible(), false);
      assert.equal(await page.locator('#qr-img').getAttribute('src'), null);
      assert.equal(await page.locator('#android-save-qr').isVisible(), false);
      await page.locator('#login-provider-qq').click();
      assert.equal(await page.locator('#qq-cookie-input').isVisible(), true);
      assert.match(await page.locator('#login-modal-desc').innerText(), /自动.*尚未实现/);
      assert.match(await page.locator('#login-modal-desc').innerText(), /Cookie/);
      await page.locator('#qq-cookie-save-btn').click();
      assert.match(await page.locator('#qr-status').innerText(), /请先粘贴/);
      await page.screenshot({ path: path.join(output, 'android-integration-' + width + '-qq.png') });
      await page.locator('#login-modal .btn-row button').filter({ hasText: '取消' }).click();
      await page.locator('#android-local-entry').click();
      await page.screenshot({ path: path.join(output, 'android-integration-' + width + '-local.png') });
      results.push({ width, passed: true, initial, pageErrors, consoleErrors, failedRequests });
      await page.close();
    }
    fs.writeFileSync(path.join(output, 'android-integration-results.json'), JSON.stringify({ results, requests }, null, 2));
    console.log(JSON.stringify({ results, requests }, null, 2));
    assert.equal(results.every(result => result.pageErrors.length === 0), true);
    assert.equal(requests.every(request => request.method === 'GET'), true);
  } finally {
    fs.writeFileSync(path.join(output, 'android-integration-results.json'), JSON.stringify({ results, requests }, null, 2));
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(temp, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
