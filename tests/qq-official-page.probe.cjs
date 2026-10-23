'use strict';
// Read-only official UI check in a new, empty browser context. No account input.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'C:/Users/33904/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const output = path.resolve('output/qq-login'); fs.mkdirSync(output, { recursive: true });
  const report = { checkedAt: new Date().toISOString(), freshContext: true, accountUsed: false, loginVerified: false, network: [], networkFailures: [], frames: [] };
  let browser;
  try {
    browser = await chromium.launch({ headless: true, channel: 'chrome', args: ['--no-proxy-server'] });
    const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
    const page = await context.newPage();
    page.on('response', response => { try { const url = new URL(response.url()); if (url.hostname.endsWith('qq.com') && /login|profile|oauth|authorize|xlogin|ptqr/.test(url.pathname)) report.network.push({ host: url.hostname, path: url.pathname, status: response.status() }); } catch {} });
    page.on('requestfailed', request => { try { const url = new URL(request.url()); if (url.hostname.endsWith('qq.com') && /login|profile|oauth|authorize|xlogin|ptqr/.test(url.pathname)) report.networkFailures.push({ host: url.hostname, path: url.pathname, errorCode: ((request.failure() || {}).errorText || '').match(/ERR_[A-Z_]+/)?.[0] || 'REQUEST_FAILED' }); } catch {} });
    const response = await page.goto('https://y.qq.com/n/ryqq/profile', { waitUntil: 'domcontentloaded', timeout: 25000 });
    report.initialStatus = response ? response.status() : null;
    await page.waitForTimeout(2500);
    const login = page.getByText('登录', { exact: true }).first();
    report.loginControlVisible = await login.isVisible().catch(() => false);
    if (report.loginControlVisible) { await login.click({ timeout: 3000 }); await page.waitForTimeout(3000); }
    report.frames = page.frames().map(frame => { try { const u = new URL(frame.url()); return { host: u.hostname, path: u.pathname }; } catch { return { blank: true }; } });
    report.loginDialogVisible = await page.getByText('QQ登录', { exact: false }).first().isVisible().catch(() => false);
    report.accountPasswordLoginVisible = false;
    for (const frame of page.frames()) {
      if (await frame.getByText(/[帐账][号户]密码登录|密码登录/, { exact: false }).first().isVisible().catch(() => false)) report.accountPasswordLoginVisible = true;
    }
    report.oauthFrameReady = report.frames.some(frame => frame.host === 'xui.ptlogin2.qq.com' && frame.path === '/cgi-bin/xlogin');
    report.title = await page.title();
    report.conclusion = 'Only official login UI reachability was checked; no account authorization occurred.';
  } catch (error) {
    report.failureType = error.name || 'BrowserProbeError';
    report.failureCode = /executable|browser.*not found/i.test(error.message || '') ? 'BROWSER_UNAVAILABLE' : /ERR_|net::/i.test(error.message || '') ? 'NETWORK_FAILED' : 'PAGE_UNAVAILABLE';
    report.conclusion = 'Official page could not complete this fresh-context probe in the current network; no account login was attempted.';
  } finally { if (browser) await browser.close(); }
  fs.writeFileSync(path.join(output, 'official-page-probe.json'), JSON.stringify(report, null, 2));
  process.stdout.write(JSON.stringify(report, null, 2));
})();
