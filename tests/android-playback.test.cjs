/* State/bridge tests only. These do not simulate Android screen-off or audio focus. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mount, normalizeTrack } = require('../app/src/main/assets/android-playback.js');

function harness() {
  const events = new EventTarget(), doc = new EventTarget(), calls = [], notices = [];
  let service = { sessionId: 'test-service', revision: 1, queue: [], index: -1, positionMs: 0, durationMs: 90000, isPlaying: false, playWhenReady: false, mode: 'sequence' };
  let hold = null;
  doc.getElementById = () => null;
  const w = { document: doc, Event, EventTarget, CustomEvent, playQueue: [], currentIdx: -1, targetVolume: 0.6,
    addEventListener: events.addEventListener.bind(events), dispatchEvent: events.dispatchEvent.bind(events),
    showToast: value => notices.push(value), renderQueuePanel() {}, queueSong(song) { this.playQueue.push(song); this.renderQueuePanel(); },
    AndroidRuntime: { isNative: true, ready: Promise.resolve({ mode:'builtin', ready:true, nativePlayback:true }),
      pickAudio: async () => ({ cancelled:true }),
      playback: async command => {
        calls.push(structuredClone(command));
        if (hold && command.action === 'queue') await hold;
        if (command.action === 'queue') {
          service.queue = structuredClone(command.queue); service.index = command.queue.length ? command.index : -1;
          if (!command.keepCurrent) service.isPlaying = service.playWhenReady = command.autoplay;
        }
        if (command.action === 'play' || command.action === 'pause') service.isPlaying = service.playWhenReady = command.action === 'play';
        if (command.action === 'mode') service.mode = command.mode;
        if (command.action === 'seek') service.positionMs = command.positionMs;
        service.revision++; return structuredClone(service);
      } }
  };
  const player = mount(w);
  return { w, calls, notices, player, setService: state => { service = state; }, hold: promise => { hold = promise; } };
}
const song = id => ({ id: String(id), provider:'netease', name:'Test ' + id, artist:'Synthetic' });
const tick = () => new Promise(resolve => setImmediate(resolve));

test('queue metadata excludes credentials, signed URLs and blob paths', () => {
  const sanitized = normalizeTrack({ ...song(1), cookie:'synthetic-secret', token:'synthetic-token', url:'https://music.126.net/signed?key=synthetic' });
  assert.equal('cookie' in sanitized,false); assert.equal('token' in sanitized,false); assert.equal('url' in sanitized,false);
  assert.equal(sanitized.provider,'netease');
});
test('native state mirror never starts a second HTML audio player or JS ended callback', async () => {
  const h = harness(); await h.player.ready;
  h.w.playQueue=[song(1),song(2),song(3)]; await h.w.playQueueAt(0);
  assert.equal(h.w.audio.src.startsWith('native:'),true); assert.equal(h.w.audio.paused,false);
  const before = h.calls.length;
  h.player.accept({ ...h.player.getState(), revision:99, index:1, positionMs:0 });
  assert.equal(h.w.currentIdx,1); assert.equal(h.w.playQueue[1].id,'2'); assert.equal(h.calls.length,before);
  assert.equal(h.w.audio.onended,undefined); // Service transition updates the UI; it does not request another next.
});
test('pause, retry, seek and modes delegate to the same native player', async () => {
  const h=harness(); await h.player.ready; h.w.playQueue=[song(1)];await h.w.playQueueAt(0);
  await h.w.togglePlay(); assert.equal(h.w.audio.paused,true);
  await h.w.togglePlay(); assert.equal(h.w.audio.paused,false);
  h.w.audio.currentTime=12.5;await tick();assert.equal(h.calls.at(-1).positionMs,12500);
  await h.w.cyclePlayMode();assert.equal(h.w.playMode,'shuffle');
  await h.w.cyclePlayMode();assert.equal(h.w.playMode,'single');
  await h.w.cyclePlayMode();assert.equal(h.w.playMode,'loop');
  h.player.accept({...h.player.getState(),revision:100,error:'Synthetic load error',isPlaying:false,playWhenReady:false});
  await h.w.togglePlay();assert.equal(h.calls.at(-1).action,'play');assert.ok(h.notices.some(n=>n.includes('重试')));
});
test('late revisions cannot replace a newer service state but a restarted service can reconnect', async () => {
  const h=harness();await h.player.ready;
  h.player.accept({...h.player.getState(),revision:20,queue:[song(1)],index:0,positionMs:10000});
  h.player.accept({...h.player.getState(),revision:19,positionMs:500});assert.equal(h.w.audio.currentTime,10);
  h.player.accept({...h.player.getState(),sessionId:'restarted',revision:1,positionMs:500});assert.equal(h.w.audio.currentTime,0.5);
});
test('cancelled SAF import keeps the existing queue and does not rebuild the player', async () => {
  const h=harness();await h.player.ready;h.w.playQueue=[song(1)];await h.w.playQueueAt(0);
  const before=h.calls.filter(c=>c.action==='queue').length;
  assert.equal((await h.player.importLocal()).cancelled,true);
  assert.equal(h.calls.filter(c=>c.action==='queue').length,before);assert.equal(h.w.playQueue[0].id,'1');
});
test('rapid queue additions preserve pending UI edits and coalesce duplicate render requests', async () => {
  const h=harness();await h.player.ready;h.w.playQueue=[song(1)];await h.w.playQueueAt(0);
  let release;h.hold(new Promise(resolve=>{release=resolve;}));
  h.w.queueSong(song(2));h.w.queueSong(song(3));
  await tick();release();await tick();await tick();
  assert.deepEqual(h.player.getState().queue.map(s=>s.id),['1','2','3']);
  assert.equal(h.calls.filter(c=>c.action==='queue').length,2);
});
test('real PCM state is mirrored into analysis arrays without creating synthetic audio', async () => {
  const h=harness();await h.player.ready;
  h.player.accept({...h.player.getState(),revision:20,queue:[song(1)],index:0,isPlaying:true,playWhenReady:true,
    spectrumScale:'log',sampleRate:44100,spectrum:[15,30,60,120],waveform:[20,100,180,230]});
  const bins=new Uint8Array(64),wave=new Uint8Array(8);
  h.w.analyser.getByteFrequencyData(bins);h.w.analyser.getByteTimeDomainData(wave);
  assert.ok(bins.some(v=>v>0));assert.deepEqual(Array.from(wave),[20,20,100,100,180,180,230,230]);
  assert.equal(h.w.audioCtx.sampleRate,44100);
});
test('frequent position-only snapshots retain the existing timeline and queue UI objects', async () => {
  const h=harness();await h.player.ready;h.w.playQueue=[song(1),song(2)];await h.w.playQueueAt(0);
  const items=h.w.playQueue;
  h.player.accept({sessionId:'test-service',revision:99,index:1,positionMs:9000,durationMs:90000,isPlaying:true,playWhenReady:true,mode:'sequence'});
  assert.equal(h.w.playQueue,items);assert.equal(h.w.currentIdx,1);assert.equal(h.w.audio.currentTime,9);
  assert.equal(h.player.getState().queue.length,2);
});
