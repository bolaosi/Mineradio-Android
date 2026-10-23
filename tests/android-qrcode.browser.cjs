/* Browser encoder + independent native ZXing decoding. Test URLs contain no real login keys. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const root = path.resolve(__dirname, '..');
  const output = path.join(root, 'output/playwright'); fs.mkdirSync(output, { recursive: true });
  const target = path.join(root, 'app/src/main/assets/android-qrcode.js');
  const original = fs.readFileSync(target);
  execFileSync(process.execPath, ['scripts/build-android-qrcode.cjs'], { cwd: root });
  assert.deepEqual(fs.readFileSync(target), original, 'Rebuilding must produce identical bytes');
  const launch = { headless: true };
  if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE) launch.executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
  const browser = await chromium.launch(launch);
  const errors = [], requests = [], manifest = [];
  try {
    const page = await browser.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', route => { requests.push(route.request().url()); return route.abort(); });
    await page.setContent('<!doctype html><meta charset="utf-8"><title>Offline QR encoder test</title>');
    await page.addScriptTag({ path: target });
    assert.deepEqual(await page.evaluate(() => ({ require: typeof require, process: typeof process, api: typeof window.MineradioQr.toDataURL })),
      { require: 'undefined', process: 'undefined', api: 'function' });
    const texts = [
      'https://music.163.com/login?codekey=MOCK_ONLY_KEY_1234567890abcdef',
      'https://example.invalid/login?key=mock-only&redirect=%E6%B5%8B%E8%AF%95',
      'https://example.invalid/二维码?key=MOCK_NO_ACCOUNT_中文'
    ];
    for (let i = 0; i < texts.length; i++) {
      const result = await page.evaluate(async text => {
        const pending = window.MineradioQr.toDataURL(text);
        return { promise: typeof pending.then === 'function', uri: await pending };
      }, texts[i]);
      assert.equal(result.promise, true); assert.match(result.uri, /^data:image\/png;base64,/);
      const imagePath = path.join(output, 'android-generated-qr-fake-key-' + i + '.png');
      fs.writeFileSync(imagePath, Buffer.from(result.uri.slice(result.uri.indexOf(',') + 1), 'base64'));
      manifest.push({ path: imagePath, text: texts[i] });
    }
    for (const invalid of ['', '   ', null, 'x'.repeat(2049)]) {
      const rejected = await page.evaluate(async text => { try { await window.MineradioQr.toDataURL(text); return false; } catch (_) { return true; } }, invalid);
      assert.equal(rejected, true, 'Invalid input must reject the Promise');
    }
    const manifestPath = path.join(output, 'android-qrcode-fake-key-manifest.json');
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
    const decoded = JSON.parse(execFileSync(process.env.PYTHON || 'python', ['-X', 'utf8', path.join(__dirname, 'decode-android-qrcode.py'), manifestPath], { encoding: 'utf8' }));
    assert.equal(decoded.passed, true); assert.deepEqual(errors, []); assert.deepEqual(requests, []);
    const result = { passed: true, scope: 'Offline QR encoding/decoding with fake keys only; no official account authentication tested', deterministicRebuild: true,
      browser: 'Chromium', noNodeRuntime: true, networkRequests: requests.length, pageErrors: errors, invalidInputsRejected: 4, ...decoded };
    fs.writeFileSync(path.join(output, 'android-qrcode-results.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
