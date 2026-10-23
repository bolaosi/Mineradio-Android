/* Run with Node and Playwright available on NODE_PATH. No online services or credentials are used. */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mineradio-local-test-'));
  const audio = path.join(temp, 'tone.mp3');
  // A real encoded MP3 ensures browser media decoding, rather than a mock Audio implementation.
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=12', '-codec:a', 'libmp3lame', audio]);
  const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/android-local.js'));
  const requests = [], errors = [];
  const server = http.createServer((req, res) => {
    requests.push({ method: req.method, url: req.url });
    if (req.url === '/android-local.js') { res.setHeader('Content-Type', 'application/javascript'); res.end(script); }
    else { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Local playback test</title><body style="background:#08090b"><script src="/android-local.js"></script>'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    const launch = { headless: true, args: ['--autoplay-policy=no-user-gesture-required'] };
    if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) launch.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
    browser = await chromium.launch(launch);
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('http://127.0.0.1:' + server.address().port);
    await page.getByRole('button', { name: '本地音乐', exact: true }).click();
    assert.equal(await page.locator('#android-local-panel').isVisible(), true);
    await page.locator('#android-local-files').setInputFiles([
      { name: '第一首.mp3', mimeType: 'audio/mpeg', buffer: fs.readFileSync(audio) },
      { name: '第二首.mp3', mimeType: 'audio/mpeg', buffer: fs.readFileSync(audio) }
    ]);
    await page.waitForFunction(() => window.AndroidLocal.media.currentTime > 0.2 && !window.AndroidLocal.media.paused);
    assert.equal(await page.locator('#android-local-queue li').count(), 2);
    await page.getByRole('button', { name: '暂停', exact: true }).click();
    const pausedAt = await page.evaluate(() => window.AndroidLocal.media.currentTime);
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.AndroidLocal.media.paused), true);
    assert.equal(await page.evaluate(() => window.AndroidLocal.media.currentTime), pausedAt);
    await page.getByRole('button', { name: '播放', exact: true }).click();
    await page.waitForFunction(at => window.AndroidLocal.media.currentTime > at + 0.1, pausedAt);
    await page.locator('#android-local-progress').fill('4');
    await page.locator('#android-local-progress').dispatchEvent('input');
    await page.waitForFunction(() => window.AndroidLocal.media.currentTime >= 4);
    await page.getByRole('button', { name: '下一首', exact: true }).click();
    await page.waitForFunction(() => window.AndroidLocal.controller.snapshot().index === 1 && !window.AndroidLocal.media.paused);
    await page.getByRole('button', { name: '上一首', exact: true }).click();
    await page.waitForFunction(() => window.AndroidLocal.controller.snapshot().index === 0);
    await page.evaluate(() => window.dispatchEvent(new Event('android-file-picker-cancel')));
    assert.match(await page.locator('#android-local-status').innerText(), /取消/);
    assert.equal(await page.locator('#android-local-queue li').count(), 2);
    await page.getByRole('button', { name: '移出 第一首.mp3', exact: true }).click();
    await page.waitForFunction(() => window.AndroidLocal.controller.snapshot().queue.length === 1 && !window.AndroidLocal.media.paused);
    assert.match(await page.locator('#android-local-now').innerText(), /第二首/);
    await page.getByRole('button', { name: '清空队列', exact: true }).click();
    assert.equal(await page.evaluate(() => window.AndroidLocal.media.paused), true);
    assert.equal(await page.locator('#android-local-queue li').count(), 0);
    await page.locator('#android-local-files').setInputFiles([{ name: '损坏.mp3', mimeType: 'audio/mpeg', buffer: Buffer.from('not an audio file') }]);
    await page.waitForFunction(() => /无法|失败/.test(document.getElementById('android-local-status').textContent));
    assert.equal(await page.evaluate(() => window.AndroidLocal.media.paused), true);
    assert.match(await page.locator('#android-local-panel').innerText(), /重启.*重新导入/);
    assert.equal(await page.evaluate(() => document.getElementById('android-local-panel').getBoundingClientRect().right <= innerWidth), true);
    const output = path.join(__dirname, '../output/playwright'); fs.mkdirSync(output, { recursive: true });
    await page.screenshot({ path: path.join(output, 'android-local.png') });
    await page.reload();
    assert.equal(await page.evaluate(() => window.AndroidLocal.controller.snapshot().queue.length), 0);
    assert.deepEqual(errors, []);
    assert.equal(requests.every(req => req.method === 'GET' && ['/', '/android-local.js', '/favicon.ico'].includes(req.url)), true);
    console.log(JSON.stringify({ passed: true, browser: 'Chromium', checks: ['real MP3 decoding and time advancing', 'multiple import', 'pause/resume', 'seek', 'previous/next', 'remove current and auto-continue', 'clear stops audio', 'cancel leaves queue unchanged', 'corrupt MP3 failure', '390px layout', 'reload has no stale blob URL queue', 'no upload requests', 'no page errors'], requests }, null, 2));
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(temp, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
