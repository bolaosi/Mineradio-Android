const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {fixture}=require('./mobile-fixture.cjs');
(async()=>{
 const f=await fixture(),{page}=f,results=[];
 try{
  const media=await f.context.newCDPSession(page);
  await media.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-transparency',value:'no-preference'}]});
  await page.evaluate(async songs=>{playQueue=songs.slice(0,3);await playQueueAt(0);},f.songs);
  await page.waitForFunction(()=>audio&&!audio.paused&&audio.currentTime>0.1);
  for(const size of [{width:390,height:844},{width:844,height:390},{width:320,height:640}]){
   await page.setViewportSize(size);
   await page.locator('#mobile-dock [data-route=queue]').tap();
   const state=await page.evaluate(()=>{
    const dock=document.querySelector('#mobile-dock'),style=getComputedStyle(dock),r=dock.getBoundingClientRect();
    return {opacity:style.opacity,bg:style.backgroundColor,blur:style.backdropFilter,r:{x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom},buttons:[...dock.querySelectorAll('button')].map(b=>{const box=b.getBoundingClientRect();return {width:box.width,height:box.height,pressed:b.getAttribute('aria-pressed')};}),panelBottom:document.querySelector('#mobile-panel').getBoundingClientRect().bottom,controlsBottom:document.querySelector('#bottom-bar').getBoundingClientRect().bottom,paused:audio.paused,shared:[document.querySelector('#android-online-button'),dock.querySelector('button')].map(e=>{const s=getComputedStyle(e);return {bg:s.backgroundColor,border:s.border,borderRadius:s.borderRadius,blur:s.backdropFilter,color:s.color,textShadow:s.textShadow,font:s.font};})};
   });
   assert.equal(state.opacity,'1');assert.match(state.bg,/0\.1\)/);assert.equal(state.blur,'none');assert.deepEqual(state.shared[0],state.shared[1]);assert.match(state.shared[0].blur,/blur\(12px\)/);
   assert.ok(state.r.x>=0&&state.r.x+state.r.width<=size.width+1);assert.ok(state.r.bottom<=size.height-7);
   assert.ok(state.buttons.every(b=>b.height>=44&&b.width>=44));assert.ok(state.panelBottom<state.r.y);
   assert.ok(state.controlsBottom<state.r.y,'Dock must stay below the progress and playback controls');
   assert.equal(state.buttons[2].pressed,'true');assert.equal(state.paused,false);
   await page.locator('#mobile-close').tap();
   await page.screenshot({path:path.join(f.output,`android-1.2.3-glass-${size.width}x${size.height}.png`)});
   for(const route of ['search','library','queue','visual','local']){
    await page.locator(`#mobile-dock [data-route=${route}]`).tap();
    if(route==='local'){
     assert.equal(await page.locator('#android-local-panel').isVisible(),true);
     assert.equal(await page.locator('#mobile-dock [data-route=local]').getAttribute('aria-pressed'),'true');
     await page.locator('#android-local-close').tap();
     assert.equal(await page.locator('#mobile-dock [data-route=local]').getAttribute('aria-pressed'),'false');
    }else{
     assert.equal(await page.locator('#mobile-panel').isVisible(),true);
     assert.equal(await page.locator(`#mobile-dock [data-route=${route}]`).getAttribute('aria-pressed'),'true');
     await page.locator('#mobile-close').tap();
    }
   }
   assert.equal(await page.evaluate(()=>audio.paused),false);results.push({viewport:size,...state,allFiveNavigationTapsPassed:true});
  }
  await page.setViewportSize({width:390,height:844});
  await page.evaluate(()=>{
   const backing=document.createElement('div');backing.id='test-glass-backing';backing.style.cssText='position:fixed;z-index:44999;inset:auto 0 0;height:85px;background:linear-gradient(90deg,#fff 0 50%,#050b09 50% 100%);pointer-events:none';document.body.append(backing);
  });
  await page.locator('#mobile-dock').screenshot({path:path.join(f.output,'android-1.2.3-glass-light-dark.png')});
  await page.evaluate(()=>document.querySelector('#test-glass-backing').remove());
  await media.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-transparency',value:'reduce'}]});
  assert.equal(await page.locator('#mobile-dock').evaluate(e=>getComputedStyle(e).backdropFilter),'none');
  assert.deepEqual(f.errors,[]);
  fs.writeFileSync(path.join(f.output,'android-mobile-glass-results.json'),JSON.stringify({passed:true,results,pageErrors:f.errors,scope:'Chromium actual MP3 and touch, no Android device or native output'},null,2));
  console.log('PASS shared online-status glass dock: portrait/landscape/small phone, all five taps, active state, live audio, separated panel, no container opacity');
 }finally{await f.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
