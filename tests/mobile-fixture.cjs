const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
async function fixture() {
  const root = path.resolve(__dirname, '..'), assets = path.join(root, 'app/src/main/assets');
  const output = path.join(root, 'output/playwright'); fs.mkdirSync(output, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mineradio-mobile-')), mp3 = path.join(tmp, 'tone.mp3');
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=620:duration=180', '-codec:a', 'libmp3lame', mp3]);
  const songs = Array.from({ length: 100 }, (_, i) => ({ id: i + 100, name: 'MOCK 歌曲 ' + String(i + 1).padStart(3, '0'), artist: '模拟歌手', duration: 60000, provider: 'netease' }));
  const playlists = Array.from({ length: 55 }, (_, i) => ({ id: i + 1, name: 'MOCK 歌单 ' + (i + 1), trackCount: songs.length }));
  const requests = [], errors = [], consoleErrors = [], networkFailures = [];
  let origin;
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, origin || 'http://localhost'), pn = url.pathname;
    requests.push({ method: req.method, path: pn });
    if (pn === '/api/audio' || pn === '/tone.mp3') {
      const audio = fs.readFileSync(mp3); res.setHeader('Content-Type', 'audio/mpeg'); res.setHeader('Accept-Ranges', 'bytes');
      const range = req.headers.range && req.headers.range.match(/bytes=(\d+)-(\d*)/);
      if (range) { const start = Number(range[1]), end = range[2] ? Number(range[2]) : audio.length - 1; res.writeHead(206, { 'Content-Range': 'bytes ' + start + '-' + end + '/' + audio.length, 'Content-Length': end - start + 1 }); res.end(audio.subarray(start, end + 1)); }
      else { res.setHeader('Content-Length', audio.length); res.end(audio); } return;
    }
    if (pn.startsWith('/api/')) {
      let result = {};
      if (pn === '/api/login/status') result = { loggedIn: true, verified: true, userId: '1234', nickname: 'MOCK 账号' };
      else if (pn === '/api/qq/login/status') result = { loggedIn: false, verified: false };
      else if (pn === '/api/user/playlists') result = { playlists };
      else if (pn === '/api/playlist/tracks') result = { tracks: songs };
      else if (pn === '/api/podcast/my') result = { collections: [] };
      else if (pn === '/api/discover/home') result = { loggedIn: true, songs: [], playlists, podcasts: [], recommendations: [] };
      else if (pn === '/api/search' || pn === '/api/qq/search') {
        const q = url.searchParams.get('keywords');
        result = { songs: q === 'empty' ? [] : songs };
        if (q === 'error') { res.writeHead(503, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ message: 'MOCK 搜索网络失败' })); return; }
      } else if (pn === '/api/song/url' || pn === '/api/qq/song/url') result = { url: origin + '/tone.mp3', level: 'standard', trial: false };
      else if (pn === '/api/lyric' || pn === '/api/qq/lyric') result = { lyric: '[00:00.00]MOCK 测试歌词\n[00:04.00]本地生成测试音频' };
      else result = { songs: [], ids: [], playlists: [], collections: [] };
      res.setHeader('Content-Type', 'application/json');
      if ((pn === '/api/search' || pn === '/api/qq/search') && url.searchParams.get('keywords') === 'slow') { setTimeout(() => res.end(JSON.stringify(result)), 500); return; }
      res.end(JSON.stringify(result)); return;
    }
    const file = path.resolve(assets, '.' + (pn === '/' ? '/index.html' : pn));
    if (!file.startsWith(assets + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
    const ext = path.extname(file); res.setHeader('Content-Type', ext === '.html' ? 'text/html; charset=utf-8' : ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : ext === '.png' ? 'image/png' : 'application/octet-stream'); res.end(fs.readFileSync(file));
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(Number(process.env.MINERADIO_TEST_PORT) || 0, '127.0.0.1', resolve); }); origin = 'http://127.0.0.1:' + server.address().port;
  const launch = { headless: true, args: ['--no-proxy-server', '--autoplay-policy=no-user-gesture-required', '--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] };
  if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) launch.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
  const browser = await chromium.launch(launch);
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 });
  await context.addInitScript(() => {
    localStorage.setItem('mineradio-backend-v1', JSON.stringify({ baseUrl: location.origin, token: 'MOCK_ONLY' }));
    localStorage.setItem('mineradio-visual-guide-seen-v2', '1');
  });
  const page = await context.newPage(); page.on('pageerror', e => errors.push(e.stack || e.message)); page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('requestfailed', req => networkFailures.push({ url: req.url(), error: req.failure()?.errorText }));
  await page.goto(origin); await page.waitForFunction(() => window.splashReadyToEnter === true);
  await page.locator('#splash').tap({ position: { x: 20, y: 60 } }); await page.waitForFunction(() => !document.body.classList.contains('splash-active'));
  const cdp = await context.newCDPSession(page);
  async function swipe(selector, direction = 'up') {
    const rect = await page.locator(selector).boundingBox(); if (!rect) throw new Error('Missing scroll surface ' + selector);
    const x = rect.x + Math.min(rect.width * 0.45, 120), from = rect.y + Math.min(rect.height - 35, rect.height * 0.78), to = rect.y + Math.max(40, rect.height * 0.22);
    const first = direction === 'up' ? from : to, last = direction === 'up' ? to : from;
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: first, id: 1 }] });
    for (let i = 1; i <= 14; i++) { await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: first + (last - first) * i / 14, id: 1 }] }); await page.waitForTimeout(24); }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await page.waitForTimeout(160);
  }
  return { root, output, page, context, browser, songs, requests, errors, consoleErrors, networkFailures, swipe, async close() { await browser.close(); await new Promise(resolve => server.close(resolve)); fs.rmSync(tmp, { recursive: true, force: true }); } };
}
module.exports = { fixture };
