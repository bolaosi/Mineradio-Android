// Diagnostic runner used on the unmodified 1.2.2 DOM and WebGL surfaces, using real CDP touch.
const fs = require('node:fs'), path = require('node:path');
const { fixture } = require('./mobile-fixture.cjs');
(async () => {
  const f = await fixture(), {page} = f, states = [];
  try {
    await page.setViewportSize({width: 1280, height: 800});
    async function dom(label, selector) {
      const read = () => page.locator(selector).evaluate(e => {
        const r = e.getBoundingClientRect(), s = getComputedStyle(e);
        return {top:e.scrollTop, height:e.clientHeight, content:e.scrollHeight, touch:s.touchAction,
          overflow:s.overflowY, pointer:s.pointerEvents, z:s.zIndex,
          hit:document.elementFromPoint(r.x+120,r.y+r.height*.5)?.closest('[id]')?.id};
      });
      const before = await read(); await f.swipe(selector); const after = await read();
      states.push({label, before, after});
    }
    await page.evaluate(() => openHomeLibrary()); await page.waitForTimeout(500);
    await dom('legacy library before playback', '#playlist-panel');
    await page.locator('#mobile-dock [data-route=library]').tap();
    await page.locator('#mobile-scroll .mobile-song').first().tap();
    await page.waitForFunction(() => document.querySelectorAll('#mobile-song-list .mobile-row').length===100);
    await dom('mobile tracks before playback', '#mobile-scroll');
    await page.locator('#mobile-scroll').evaluate(e => e.scrollTop=0);
    await page.locator('#mobile-song-list .mobile-song').first().tap();
    await page.waitForFunction(() => audio&&!audio.paused&&audio.currentTime>.1);
    await dom('mobile tracks playing', '#mobile-scroll');
    await page.locator('#mobile-close').tap();
    await page.evaluate(() => { openPlaylistPanelTab('queue'); }); await page.waitForTimeout(500);
    await dom('legacy queue playing', '#playlist-panel');
    await page.evaluate(() => { togglePlaylistPanel(false);setPeek(document.getElementById('playlist-panel'),false,'pl');setShelfMode('side');setShelfPinnedOpen(true,true); });
    await page.waitForTimeout(1000);
    async function scene(label, detail) {
      const point = await page.evaluate(detail => {
        const sm=shelfManager, cl=sm.getContentList();
        // Discover only an actual hit visible to the production raycast and DOM hit-test.
        for(let y=140;y<innerHeight-180;y+=12) for(let x=50;x<innerWidth-15;x+=12) {
          if(document.elementFromPoint(x,y)!==renderer.domElement)continue;
          const e={clientX:x,clientY:y},rc=raycasterFromPointerEvent(e);
          const hit=detail ? cl.raycastRows(rc)||cl.pickRowAtScreen(x,y) : pointerCardHit(rc,e);
          if(hit) return {x,y};
        }
        return null;
      },detail);
      if(!point)throw Error('No visible production scene hit: '+label);
      const before=await page.evaluate(detail=>({index:(detail?shelfManager.getContentList():shelfManager).getCenterIdx(),playing:playing, song:currentIdx}),detail);
      const cdp=await f.context.newCDPSession(page);
      await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...point,id:1}]});
      for(let i=1;i<=14;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:point.x,y:point.y-i*6,id:1}]});await page.waitForTimeout(24);}
      await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(600);
      const after=await page.evaluate(detail=>({index:(detail?shelfManager.getContentList():shelfManager).getCenterIdx(),playing:playing,song:currentIdx}),detail);
      states.push({label,point,before,after});
    }
    await scene('3D cards while playing',false);
    await page.evaluate(()=>{const card=shelfManager.getCards().find(c=>c.item.type==='playlist');shelfManager.openContent(card.index);});
    await page.waitForTimeout(1000);
    await scene('3D song details while playing',true);
    await page.screenshot({path:path.join(f.output,'touch-diagnostic.png')});
    console.log(JSON.stringify({states,errors:f.errors},null,2));
  } finally {
    fs.writeFileSync(path.join(f.output,'touch-diagnostic.json'),JSON.stringify({states,errors:f.errors},null,2));
    await f.close();
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
