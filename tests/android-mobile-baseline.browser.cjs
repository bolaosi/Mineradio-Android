const fs = require('node:fs'), path = require('node:path');
const { fixture } = require('./mobile-fixture.cjs');
(async () => {
  const f = await fixture(), { page } = f, states = [];
  try {
    async function capture(label) { states.push(await page.evaluate(label => { const e = document.getElementById('playlist-panel'), h = document.querySelector('.empty-home-shell'); return { label, playing: window.playing, home: window.emptyHomeActive, tab: window.queueViewTab, panelClass: e.className, panelScroll: e.scrollTop, panelHeight: e.clientHeight, panelContent: e.scrollHeight, homeScroll: h.scrollTop, homeHeight: h.clientHeight, homeContent: h.scrollHeight, hit: document.elementFromPoint(120, 400)?.closest('[id]')?.id }; }, label)); }
    await page.evaluate(() => openHomeLibrary()); await page.waitForTimeout(500); await capture('before-play-before-swipe'); await f.swipe('#playlist-panel'); await capture('before-play-after-swipe');
    await page.evaluate(songs => { playQueue = songs; currentIdx = 0; return playQueueAt(0); }, f.songs);
    await page.waitForFunction(() => window.audio && !window.audio.paused); await capture('playing-before-swipe'); await f.swipe('#playlist-panel'); await capture('playing-after-swipe');
    await page.evaluate(() => goHome()); await page.waitForTimeout(400); await capture('home-before-swipe'); await f.swipe('.empty-home-shell'); await capture('home-after-swipe');
    const result = { scope: 'Chromium touch baseline before mobile fixes; generated MP3 and mock API', states, errors: f.errors, consoleErrors: f.consoleErrors };
    fs.writeFileSync(path.join(f.output, 'android-mobile-baseline.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
  } finally { await f.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
