// Actual Chromium CDP touch input through the production DOM and WebGL hit tests.
// Account/API and native playback pushes are MOCK; this is not phone/speaker/screen-off evidence.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const {fixture} = require('./mobile-fixture.cjs');
const {installNativeMock} = require('./native-ui-fixture.cjs');
(async () => {
  const f = await fixture(), {page} = f, checks = [], gestures = [];
  const cdp = await f.context.newCDPSession(page);
  const tailOnly = !!process.env.MINERADIO_TOUCH_TAIL;
  async function enter() {
    await page.waitForFunction(() => splashReadyToEnter);
    await page.locator('#splash').tap({position:{x:20,y:60}});
    await page.waitForFunction(() => !document.body.classList.contains('splash-active'));
  }
  async function drag(point, dx, dy, options={}) {
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...point,id:1}]});
    for(let i=1;i<=14;i++) {
      await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:point.x+dx*i/14,y:point.y+dy*i/14,id:1}]});
      if(options.push) await page.evaluate(() => __nativePush({positionMs:__nativeTest.positionMs+250},true));
      await page.waitForTimeout(24);
    }
    await cdp.send('Input.dispatchTouchEvent',{type:options.cancel?'touchCancel':'touchEnd',touchPoints:[]});
    await page.waitForTimeout(350);
  }
  async function pan(label,selector='#mobile-scroll', direction='up') {
    const read=()=>page.locator(selector).evaluate(e=>({top:e.scrollTop,height:e.clientHeight,content:e.scrollHeight}));
    const before=await read(); const song=await page.evaluate(()=>currentIdx);
    const scene=await page.evaluate(()=>shelfManager.getScrollTarget());
    await f.swipe(selector,direction); const after=await read();
    assert.ok(direction==='up'?after.top>before.top+50:after.top<before.top-50,label+': '+before.top+' -> '+after.top);
    assert.equal(await page.evaluate(()=>currentIdx),song,label+' must not play another song');
    assert.equal(await page.evaluate(()=>shelfManager.getScrollTarget()),scene,label+' must not drag the scene');
    gestures.push({label,before,after});
    console.log('PASS '+label+' '+before.top+' -> '+after.top);
  }
  async function visibleRow() {
    const index=await page.locator('#mobile-song-list .mobile-row').evaluateAll(rows=>{
      const box=document.getElementById('mobile-scroll').getBoundingClientRect();
      return rows.findIndex(e=>{const r=e.getBoundingClientRect();return r.top>box.top+10&&r.bottom<box.bottom-10;});
    });
    assert.ok(index>=0);return page.locator('#mobile-song-list .mobile-song').nth(index);
  }
  async function scenePoint(detail, forTap=false) {
    const point=await page.evaluate(({detail,forTap})=>{
      const cl=shelfManager.getContentList(), points=[];
      for(let y=145;y<innerHeight-70;y+=10)for(let x=25;x<innerWidth-20;x+=10){
        if(!detail && shelfManager.getMode()==='stage' && x<180)continue;
        if(document.elementFromPoint(x,y)!==renderer.domElement)continue;
        const e={clientX:x,clientY:y},rc=raycasterFromPointerEvent(e);
        const hit=detail?cl.raycastRows(rc)||cl.pickRowAtScreen(x,y):shelfManager.raycastCards(rc)||shelfManager.pickCardAtScreen(x,y,0);
        if(!hit)continue;
        if(forTap && detail && (!hit.row || hit.row.index!==cl.getCenterIdx() || !hit.uv || hit.uv.x>.5 || hit.uv.x<.15))continue;
        if(forTap && !detail && (!hit.card || hit.card.index!==shelfManager.getCenterIdx() || isShelfPlaylistPlayHit(hit)))continue;
        points.push({x,y});
      }
      return points[Math.floor(points.length*.65)]||null;
    },{detail,forTap});
    assert.ok(point,'An actual visible '+(detail?'song row':'card')+' must be hit'); return point;
  }
  async function browse(label, detail, axis='y', cancel=false) {
    const point=await scenePoint(detail);
    const read=()=>page.evaluate(detail=>({center:(detail?shelfManager.getContentList():shelfManager).getScrollTarget(),song:currentIdx,
      orbit:[orbit.userTheta,orbit.userPhi], commands:window.__nativeCommands?.length}),detail);
    const before=await read();
    await page.evaluate(()=>{
      window.__touchDown=[];
      renderer.domElement.addEventListener('pointerdown',function trace(e){
        const rc=raycasterFromPointerEvent(e),hit=pointerCardHit(rc,e,18);
        __touchDown.push({x:e.clientX,y:e.clientY,mode:shelfManager.getMode(),content:shelfManager.hasOpenContent(),
          hit:hit?.card?.index,ui:isPointerOverUi(e),target:e.target.id});
        renderer.domElement.removeEventListener('pointerdown',trace);
      },{passive:true});
    });
    const amount=Math.min(135,axis==='y'?point.y-90:point.x-20);
    await drag(point,axis==='x'?-amount:0,axis==='y'?-amount:0,{cancel,push:!!await page.evaluate(()=>window.__nativeTest)});
    const after=await read();
    assert.ok(after.center>before.center+.5,label+' must advance by dragging: '+JSON.stringify({before,after,point,down:await page.evaluate(()=>__touchDown)}));
    assert.equal(after.song,before.song,label+' must not accidentally play a row');
    assert.deepEqual(after.orbit,before.orbit,label+' must not drag the background camera');
    assert.equal(await page.evaluate(()=>orbit.rotating||particlePointerSpin.active),false);
    gestures.push({label,point,before,after});
    console.log('PASS '+label+' '+before.center+' -> '+after.center);
  }
  try {
    await page.waitForFunction(()=>window.loginStatus&&loginStatus.loggedIn);
    await page.evaluate(()=>setPerformanceQualityMode('eco'));
    if (!tailOnly) {
    // First exercise the HTML audio path with a locally generated real MP3, not an ended mock.
    await page.locator('#mobile-dock [data-route=library]').tap();
    await page.locator('#mobile-scroll .mobile-song').first().tap();
    await page.waitForFunction(()=>document.querySelectorAll('#mobile-song-list .mobile-row').length===100);
    await pan('DOM songs before playback');await pan('DOM songs down before playback','#mobile-scroll','down');
    await (await visibleRow()).tap(); await page.waitForFunction(()=>audio&&!audio.paused&&audio.currentTime>.1);
    for(let i=0;i<2;i++)await pan('DOM songs playing '+i);
    await pan('DOM songs down playing','#mobile-scroll','down');
    await page.evaluate(()=>togglePlay());await page.waitForFunction(()=>audio.paused);await pan('DOM songs paused');
    await page.evaluate(()=>togglePlay());await page.waitForFunction(()=>!audio.paused);
    await page.evaluate(()=>nextTrack());await page.waitForFunction(()=>!audio.paused&&audio.currentTime>.1);
    await pan('DOM songs after next');
    const pos=await page.locator('#mobile-scroll').evaluate(e=>e.scrollTop);
    await page.locator('#mobile-close').tap();await page.locator('#mobile-dock [data-route=library]').tap();
    assert.ok(Math.abs(await page.locator('#mobile-scroll').evaluate(e=>e.scrollTop)-pos)<4);
    await pan('DOM songs reopened');
    await page.setViewportSize({width:844,height:390});await pan('DOM songs after landscape');
    checks.push('Real MP3: DOM native pan before/playing/paused/resumed/next/reopen/rotation, no song or scene misclick');
    }

    // Reload the very same production page in native mode, with frequent service snapshot pushes.
    await page.addInitScript(installNativeMock,f.songs);await page.reload();await enter();
    await page.waitForFunction(()=>AndroidPlayback.active&&loginStatus.loggedIn);
    if (!tailOnly) {
    await page.evaluate(()=>{ userPlaylists=Array.from({length:55},(_,i)=>({id:i+1,name:'MOCK native list '+i,trackCount:100,provider:'netease'}));safeShelfRebuild('test-library'); });
    await page.locator('#mobile-dock [data-route=library]').tap();
    await page.locator('#mobile-scroll .mobile-song').first().tap();
    await page.waitForFunction(()=>document.querySelectorAll('#mobile-song-list .mobile-row').length===100);
    await page.locator('#mobile-song-list .mobile-song').first().tap();
    await page.waitForFunction(()=>audio&&!audio.paused&&audio.src.startsWith('native:'));
    await page.setViewportSize({width:390,height:844});
    const box=await page.locator('#mobile-scroll').boundingBox();
    const commands=await page.evaluate(()=>__nativeCommands.filter(c=>c.action==='queue').length);
    await drag({x:box.x+100,y:box.y+box.height*.75},0,-250,{push:true});
    assert.ok(await page.locator('#mobile-scroll').evaluate(e=>e.scrollTop)>100);
    assert.equal(await page.evaluate(()=>__nativeCommands.filter(c=>c.action==='queue').length),commands);
    await pan('DOM native mirror down during playback','#mobile-scroll','down');
    await page.locator('#mobile-close').tap();
    await page.evaluate(()=>{ togglePlaylistPanel(false);setPeek(document.getElementById('playlist-panel'),false,'pl');setShelfMode('side');setShelfPinnedOpen(true,true); });
    await page.waitForTimeout(900);
    for(const size of [{width:1280,height:800},{width:390,height:844}]){
      await page.setViewportSize(size);await page.waitForTimeout(650);
      await browse('3D card drag '+size.width,false);
      // Open the actual centered card by tapping it, after a drag (not an internal open call).
      const hit=await scenePoint(false,true);
      await page.touchscreen.tap(hit.x,hit.y);
      await page.waitForFunction(()=>shelfManager.hasOpenContent()&&shelfManager.getContentList().getRows().some(r=>r.song.id));
      await page.waitForTimeout(650);
      await browse('3D details drag '+size.width,true);
      const point=await scenePoint(true,true),before=await page.evaluate(()=>__nativeCommands.filter(c=>c.action==='queue').length);
      await page.touchscreen.tap(point.x,point.y);
      await page.waitForFunction(before=>__nativeCommands.filter(c=>c.action==='queue').length>before,before);
      assert.equal(await page.evaluate(()=>shelfManager.hasOpenContent()),true,'Playing must leave mobile 3D details open');
      await browse('3D details continue after selecting song '+size.width,true);
      await page.evaluate(()=>togglePlay());await page.waitForFunction(()=>audio.paused);
      await browse('3D details paused '+size.width,true);
      await page.evaluate(()=>togglePlay());await page.waitForFunction(()=>!audio.paused);
      await page.evaluate(()=>nextTrack());
      await browse('3D details after next '+size.width,true,'y',true);
      await browse('3D details after cancellation '+size.width,true);
      await page.screenshot({path:path.join(f.output,'touch-1.2.3-details-'+size.width+'.png')});
      await page.evaluate(()=>safeShelfCloseContent('test-close'));await page.waitForTimeout(350);
      await browse('3D cards after detail close '+size.width,false);
    }
    checks.push('Native mirror: frequent service pushes; 3D card and detail drag, deliberate selection after drag, playing/paused/next/cancel/rotation');
    } else await page.evaluate(songs=>{playQueue=songs;return playQueueAt(0);},f.songs);

    // Queue-as-card mode previously reset its browsing position on every cover/rebuild.
    await page.setViewportSize({width:1280,height:800});
    await page.evaluate(()=>{userPlaylists=[];myPodcastCollections=[];safeShelfRebuild('test-queue');shelfManager.scrollBy(5);});
    const target=await page.evaluate(()=>shelfManager.getScrollTarget());
    await page.evaluate(()=>{safeShelfRebuild('cover-update');nextTrack();});await page.waitForTimeout(800);
    assert.equal(await page.evaluate(()=>shelfManager.getScrollTarget()),target);
    await browse('3D queue cards after cover and next',false);
    await page.evaluate(()=>setShelfMode('stage'));await page.waitForTimeout(800);
    await browse('3D stage horizontal swipe',false,'x');
    checks.push('3D queue browsing survives cover/rebuild/next; stage horizontal swipe works');
    assert.deepEqual(f.errors,[]);
    const result={passed:true,checks,gestures,pageErrors:f.errors,consoleErrors:f.consoleErrors,
      scope:'Real Chromium CDP touches + real generated MP3 / MOCK native bridge. No Android device, real login or screen-off test.'};
    fs.writeFileSync(path.join(f.output,'touch-1.2.3-results.json'),JSON.stringify(result,null,2));
    console.log(JSON.stringify(result,null,2));
  } catch(e) {
    fs.writeFileSync(path.join(f.output,'touch-1.2.3-partial.json'),JSON.stringify({error:String(e),checks,gestures},null,2));
    await page.screenshot({path:path.join(f.output,'touch-1.2.3-failure.png')});
    console.error({error:String(e),pageErrors:f.errors,consoleErrors:f.consoleErrors,requests:f.requests.slice(-20),networkFailures:f.networkFailures,checks,gestures});throw e;
  } finally {await f.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
