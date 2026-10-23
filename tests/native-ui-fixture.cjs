// Mock bridge only: does not simulate Android audio or real accounts.
function installNativeMock(songs) {
  const originalAudio = window.Audio; window.__htmlPlayers = 0;
  window.Audio = function (...args) { window.__htmlPlayers++; return new originalAudio(...args); };
  const state = window.__nativeTest = { sessionId:'MOCK_SERVICE',revision:1,queue:[],index:-1,positionMs:0,durationMs:180000,
    isPlaying:false,playWhenReady:false,buffering:false,error:'',mode:'sequence',eqSupported:true,spectrumScale:'log',spectrum:[],waveform:[] };
  window.__nativeCommands = [];
  function emit(id,result,error) { setTimeout(()=>window.MineradioNative.onmessage({data:JSON.stringify({id,result,error})}),0); }
  window.__nativePush = function (changes,omitQueue) { Object.assign(state,changes);state.revision++;const update=JSON.parse(JSON.stringify(state));if(omitQueue)delete update.queue;window.MineradioNative.onmessage({data:JSON.stringify({event:'playback',state:update})}); };
  window.MineradioNative = { onmessage:null,postMessage(raw) {
    const c=JSON.parse(raw);
    if(c.type==='getConfig')return emit(c.id,{mode:'builtin',ready:true,nativePlayback:true});
    if(c.type==='cancel')return;
    if(c.type==='pickAudio')return emit(c.id,{tracks:[1,2].map(n=>({id:'local:mock'+n,provider:'local',name:'MOCK 本地 '+n+'.mp3',artist:'本地文件',localUri:'content://mock.documents/audio/'+n,persisted:true})),message:'MOCK 已选择2首'});
    if(c.type==='playback') {
      const op=c.command;window.__nativeCommands.push(JSON.parse(JSON.stringify(op)));
      if(op.action==='queue') {const previous=state.queue[state.index];state.queue=op.queue;state.index=op.queue.length?op.index:-1;
        if(op.keepCurrent&&previous){const found=state.queue.findIndex(s=>s.provider===previous.provider&&s.id===previous.id);if(found>=0)state.index=found;}
        else {state.isPlaying=state.playWhenReady=!!op.autoplay;state.positionMs=0;}state.mode=op.mode;state.error='';}
      if(op.action==='play'||op.action==='pause'){state.isPlaying=state.playWhenReady=op.action==='play';state.error='';}
      if(op.action==='next'||op.action==='previous')state.index=(state.index+(op.action==='next'?1:-1)+state.queue.length)%state.queue.length;
      if(op.action==='mode')state.mode=op.mode;
      if(op.action==='seek')state.positionMs=op.positionMs;
      if(op.action==='volume')state.volume=op.volume;
      state.revision++;return emit(c.id,JSON.parse(JSON.stringify(state)));
    }
    if(c.type==='request') {
      const p=new URL(c.path,location.origin), name=p.pathname;let body={songs:[],playlists:[],collections:[],ids:[]};
      if(name==='/api/login/status')body={loggedIn:true,verified:true,userId:'1234',nickname:'MOCK native account'};
      if(name==='/api/qq/login/status')body={loggedIn:false,verified:false};
      if(name==='/api/search'||name==='/api/qq/search')body={songs};
      if(name==='/api/user/playlists')body={playlists:Array.from({length:55},(_,i)=>({id:String(i+1),name:'MOCK native list '+(i+1),trackCount:songs.length}))};
      if(name==='/api/playlist/tracks')body={tracks:songs};
      if(name==='/api/lyric')body={lyric:'[00:00.00]MOCK native UI lyrics'};
      if(name==='/api/login/qr/key')body={key:'MOCK_NATIVE_QR_KEY'};
      if(name==='/api/login/qr/create')body={url:'https://music.163.com/login?codekey=MOCK_NATIVE_QR_KEY'};
      if(name==='/api/login/qr/check')body={code:801};
      return emit(c.id,{status:200,body:JSON.stringify(body)});
    }
    emit(c.id,null,'MOCK unsupported operation');
  }};
}

module.exports={installNativeMock};
