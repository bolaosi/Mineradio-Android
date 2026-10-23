/* Chromium + simulated native service. Not an Android, screen-off, SAF, focus or account test. */
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const { fixture } = require('./mobile-fixture.cjs');
const { installNativeMock } = require('./native-ui-fixture.cjs');
(async()=>{
  const f=await fixture(),{page}=f,checks=[];
  const record=name=>{checks.push(name);console.log('PASS '+name);};let passed=false;
  try {
    await page.addInitScript(installNativeMock,f.songs);f.requests.length=0;
    await page.reload();await page.waitForFunction(()=>window.AndroidPlayback&&AndroidPlayback.active&&window.AndroidLocal&&AndroidLocal.native&&window.AndroidMobile);
    await page.waitForFunction(()=>window.splashReadyToEnter===true);
    await page.locator('#splash').tap({position:{x:20,y:60}});await page.waitForFunction(()=>!document.body.classList.contains('splash-active'));
    await page.evaluate(async songs=>{playQueue=songs.slice(0,3);await playQueueAt(0);},f.songs);
    assert.equal(await page.evaluate(()=>audio.src.startsWith('native:')&&!audio.paused),true);
    assert.equal(await page.evaluate(()=>window.__htmlPlayers),0);
    record('full bundled page uses service mirror and creates no HTML audio player');
    const before=await page.evaluate(()=>__nativeCommands.filter(c=>c.action==='next').length);
    for(const index of [1,2,0]) {
      await page.evaluate(index=>__nativePush({index,positionMs:4000,isPlaying:true,playWhenReady:true},true),index);
      await page.waitForFunction(index=>currentIdx===index,index);
      assert.equal(await page.locator('#thumb-title').textContent(),f.songs[index].name);
    }
    assert.equal(await page.evaluate(()=>__nativeCommands.filter(c=>c.action==='next').length),before);
    record('three simulated service transitions update song/queue without a JS next or ended callback');
    await page.locator('#mobile-dock [data-route=search]').tap();await page.locator('#mobile-search-query').fill('MOCK');
    await page.locator('#mobile-search-form button[type=submit]').tap();await page.waitForFunction(()=>document.querySelectorAll('#mobile-song-list .mobile-row').length===100);
    const scrollBefore=await page.locator('#mobile-scroll').evaluate(e=>e.scrollTop);await f.swipe('#mobile-scroll');
    assert.ok(await page.locator('#mobile-scroll').evaluate(e=>e.scrollTop)>scrollBefore+100);
    assert.equal(await page.evaluate(()=>audio.paused),false);
    record('native-playing UI can search and pan results using actual touch gestures without pausing');
    await page.locator('#mobile-close').tap();await page.locator('#mobile-dock [data-route=local]').tap();
    await page.locator('#android-local-import').tap();await page.waitForFunction(()=>AndroidPlayback.getState().queue.filter(s=>s.provider==='local').length===2);
    assert.equal(await page.evaluate(()=>AndroidPlayback.getState().queue.length),5);
    assert.equal(await page.evaluate(()=>audio.src.startsWith('native:local:')),true);
    await page.locator('#android-local-toggle').tap();await page.waitForFunction(()=>audio.paused);
    await page.locator('#android-local-toggle').tap();await page.waitForFunction(()=>!audio.paused);
    await page.evaluate(()=>{const p=document.getElementById('android-local-progress');p.value='12';p.dispatchEvent(new Event('change'));});
    await page.waitForFunction(()=>audio.currentTime===12);
    record('simulated SAF content URIs join the native queue, pause/resume/seek through the service, never via Blob');
    await page.evaluate(()=>__nativePush({error:'MOCK expired media',isPlaying:false,playWhenReady:false}));
    await page.locator('#android-local-toggle').tap();await page.waitForFunction(()=>!audio.paused&&!AndroidPlayback.getState().error);
    record('an explicit Play recovers simulated load failure through a new native play request');
    await page.locator('#android-local-close').tap();
    await page.evaluate(()=>{showLoginModal();setLoginProvider('netease');});
    await page.waitForFunction(()=>document.getElementById('qr-img').naturalWidth>200);
    assert.equal(await page.locator('#qr-shell').isVisible(),true);
    await page.locator('#login-provider-qq').tap();assert.equal(await page.locator('#android-qq-auto-login').isVisible(),true);
    assert.equal(await page.locator('#qq-cookie-panel').isVisible(),true);
    record('Netease QR still renders offline and QQ official login coexists with Cookie fallback');
    await page.screenshot({path:path.join(f.output,'android-1.2.0-native-ui-mock.png')});
    assert.equal(f.requests.some(r=>r.path==='/api/audio'),false);
    assert.equal(f.errors.length,0,f.errors.join('\n'));passed=true;
  } finally {
    fs.writeFileSync(path.join(f.output,'android-native-ui-results.json'),JSON.stringify({passed,scope:'Chromium with simulated native service and SAF only; no Android screen-off/audio-focus/real account/audio rendering verified',checks,pageErrors:f.errors,consoleErrors:f.consoleErrors,httpAudioRequests:f.requests.filter(r=>r.path==='/api/audio').length},null,2));
    await f.close();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
