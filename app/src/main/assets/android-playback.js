/* Android has exactly one audible player: the service. This module mirrors it for the existing UI. */
(function (root, factory) {
  var lib = factory();
  if (typeof module === 'object' && module.exports) module.exports = lib;
  if (root && root.AndroidRuntime) lib.mount(root);
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  function normalizeTrack(song, quality) {
    song = song || {};
    var provider = song.localUri ? 'local' : song.provider === 'qq' || song.source === 'qq' || song.type === 'qq' ? 'qq' : 'netease';
    var item = { id: String(song.id || song.mid || song.songmid || ''), provider: provider,
      name: String(song.name || '未命名歌曲'), artist: String(song.artist || ''), cover: String(song.cover || ''),
      duration: Number(song.duration) || 0, quality: quality || song.quality || 'standard' };
    ['mid', 'mediaMid', 'album', 'albumMid'].forEach(function (key) { if (song[key] != null) item[key] = String(song[key]); });
    if (provider === 'local') { item.localUri = String(song.localUri); item.persisted = song.persisted === true; item.type = 'local'; }
    // A bridge request never includes a saved Cookie, backend token or a temporary media URL.
    return item;
  }
  function key(song) { return song ? String(song.provider || '') + ':' + String(song.id || '') + ':' + String(song.localUri || '') : ''; }
  function signature(queue) { return (queue || []).map(key).join('|'); }
  function nativeMode(mode) { return mode === 'single' ? 'single' : mode === 'shuffle' || mode === 'random' ? 'random' : 'sequence'; }
  function webMode(mode) { return mode === 'single' ? 'single' : mode === 'random' ? 'shuffle' : 'loop'; }

  function mount(w) {
    if (w.AndroidPlayback) return w.AndroidPlayback;
    var d = w.document, state = { queue: [], index: -1, positionMs: 0, durationMs: 0, isPlaying: false, playWhenReady: false, mode: 'sequence' };
    var applying = false, commandChain = Promise.resolve(), queueWrites = 0, requestedQueue = '', lastKey = '', lastQueue = '', lastError = '';
    var volume = Number.isFinite(Number(w.targetVolume)) ? Number(w.targetVolume) : 0.8;
    var active = false, facade = null, syncQueued = false;
    function invoke(name) { try { if (typeof w[name] === 'function') return w[name].apply(w, Array.prototype.slice.call(arguments, 1)); } catch (_) { } }
    function toast(text) { invoke('showToast', text); }
    function queue() { return (w.playQueue || []).map(function (s) { return normalizeTrack(s, w.playbackQuality); }); }
    function send(command) {
      commandChain = commandChain.catch(function () {}).then(function () { return w.AndroidRuntime.playback(command); }).then(function (next) {
        if (next) accept(next); return next;
      });
      return commandChain.catch(function (error) { toast(error.message || '播放服务暂不可用，请重试'); throw error; });
    }
    function emit(name) { if (facade) facade.dispatchEvent(new w.Event(name)); }
    function analysis(fftSize) {
      return { fftSize: fftSize, frequencyBinCount: fftSize / 2,
        getByteFrequencyData: function (array) {
          var bins = state.spectrum || [], rate = state.sampleRate || 48000;
          for (var i = 0; i < array.length; i++) {
            var hz = i * rate / fftSize, at;
            if (state.spectrumScale === 'log') at = hz <= 20 ? 0 : Math.log(hz / 20) / Math.log(1000) * Math.max(0, bins.length - 1);
            else at = hz * (state.fftSize || bins.length * 2 || 128) / rate;
            array[i] = state.isPlaying ? (bins[Math.max(0, Math.min(bins.length - 1, Math.round(at)))] || 0) : 0;
          }
        },
        getByteTimeDomainData: function (array) {
          var wave = state.waveform || [];
          for (var i = 0; i < array.length; i++) array[i] = state.isPlaying && wave.length ? wave[Math.floor(i / array.length * wave.length)] : 128;
        }
      };
    }
    function updateTrack(song) {
      invoke('finalizeListenSession', false);
      w.trackSwitchToken = (Number(w.trackSwitchToken) || 0) + 1;
      invoke('cancelBeatAnalysisTimer'); invoke('cancelBeatPrefetchTimer'); invoke('clearAudioFadeTimers');
      w.audioFadeSerial = (Number(w.audioFadeSerial) || 0) + 1;
      w.currentLocalSong = song && song.provider === 'local' ? Object.assign({}, song, { type: 'local', localKey: song.id }) : null;
      w.currentBeatMap = null; w.beatMapNextIdx = 0; invoke('resetRealtimeBeatEngine');
      if (!song) return;
      w.firstPlayDone = true; w.homeForcedOpen = false; w.homeSuppressed = false;
      invoke('tweenParticleAlpha', w.uniforms && w.uniforms.uAlpha ? w.uniforms.uAlpha.value || 0 : 0, 1, 220);
      invoke('switchPlaybackVisualToEmily'); invoke('resetCinemaTrackProfile', song); invoke('setDjModeActive', false, song);
      invoke('updateControlTrackInfo', song); invoke('updateCustomCoverButton'); invoke('updateLikeButtons', song);
      var title = d.getElementById('thumb-title'), artist = d.getElementById('thumb-artist'), thumb = d.getElementById('thumb-wrap');
      if (title) title.textContent = song.name; if (artist) artist.textContent = song.artist; if (thumb) thumb.classList.add('visible');
      var hint = d.getElementById('hint'); if (hint) hint.classList.add('hidden');
      invoke('updateEmptyHomeVisibility', { forceLoad: false });
      var coverOptions = { trackToken: w.trackSwitchToken, deferHeavy: true, delay: 350, timeout: 2000 };
      var customCover = invoke('getCustomCoverForSong', song);
      if (customCover) invoke('applyCoverDataUrl', customCover, coverOptions);
      else if (song.cover) invoke('loadCoverFromUrl', song.cover, coverOptions);
      if (song.provider !== 'local') { invoke('fetchLyric', song, w.trackSwitchToken); invoke('syncLikeStatusForSong', song); }
      else { invoke('setOriginalLyricsState', invoke('withLyricFallback', []) || [], false, 'fallback'); invoke('applyPreferredLyricsForCurrent', true); }
      invoke('beginListenSession', song, null);
    }
    function accept(next) {
      if (!next) return;
      if (!Array.isArray(next.queue)) next = Object.assign({}, next, { queue: state.queue });
      if (state.sessionId && next.sessionId === state.sessionId && Number(next.revision) < Number(state.revision)) return;
      var wasPlaying = state.isPlaying, previousDuration = state.durationMs;
      state = Object.assign({}, next); state.index = Number.isInteger(next.index) ? next.index : -1;
      var song = state.queue[state.index], nextKey = key(song), nextQueue = signature(state.queue);
      applying = true;
      if (!queueWrites && nextQueue !== lastQueue) w.playQueue = state.queue.map(function (s) { return Object.assign({}, s, { source: s.provider, type: s.provider === 'local' ? 'local' : s.provider === 'qq' ? 'qq' : 'song' }); });
      w.currentIdx = queueWrites ? (w.playQueue || []).findIndex(function (s) { return key(s) === nextKey; }) : state.index;
      w.playMode = webMode(state.mode);
      if (nextKey !== lastKey) { lastKey = nextKey; updateTrack(song); emit('loadedmetadata'); }
      if (!queueWrites && nextQueue !== lastQueue) { lastQueue = nextQueue; invoke('safeRenderQueuePanel', 'native-queue', { scrollCurrent: false }); invoke('safeShelfRebuild', 'native-queue', true); }
      if (previousDuration !== state.durationMs) emit('durationchange');
      w.playing = !!state.isPlaying; invoke('setPlayIcon', !!state.playWhenReady); invoke('updatePlayModeButton', false);
      if (wasPlaying !== state.isPlaying) emit(state.isPlaying ? 'playing' : 'pause');
      emit('timeupdate'); invoke('updatePlaybackProgressUi');
      if (state.buffering && state.playWhenReady) invoke('showLoading'); else invoke('hideLoading');
      if (state.error && state.error !== lastError) toast(state.error + '；可点播放重试，或切换下一首');
      lastError = state.error || '';
      var trial = d.getElementById('trial-banner');
      if (trial) trial.classList.toggle('show', !!state.trial);
      var trialText = d.getElementById('trial-text');
      if (trialText && state.trial) trialText.textContent = '官方仅返回试听片段，完整播放仍需相应账号或版权权限';
      applying = false;
      w.dispatchEvent(new w.CustomEvent('android-playback-state', { detail: state }));
    }
    async function setQueue(items, index, autoplay, keepCurrent) {
      var normalized = items.map(function (s) { return normalizeTrack(s, w.playbackQuality); });
      requestedQueue = signature(normalized); queueWrites++;
      try { return await send({ action: 'queue', queue: normalized, index: Math.max(0, Number(index) || 0),
        autoplay: !!autoplay, keepCurrent: !!keepCurrent, mode: nativeMode(w.playMode) }); }
      finally { queueWrites--; if (!queueWrites) { requestedQueue = ''; accept(state); } }
    }
    function syncQueue() {
      if (!active || applying || syncQueued) return;
      syncQueued = true;
      Promise.resolve().then(function () {
        syncQueued = false;
        var items = queue();
        if (signature(items) === (queueWrites ? requestedQueue : signature(state.queue))) return;
        // A render followed by an explicit Play in the same gesture is a single native edit.
        setQueue(items, w.currentIdx, state.playWhenReady, true).catch(function () {});
      });
    }
    var api = w.AndroidPlayback = {
      get active() { return active; }, getState: function () { return state; }, accept: accept,
      command: send, setQueue: setQueue, syncQueue: syncQueue,
      setEq: function (bands) { return send(Object.assign({ action: 'eq' }, bands)); },
      importLocal: async function () {
        var result = await w.AndroidRuntime.pickAudio();
        if (result.cancelled) { toast('已取消选择，队列未改变'); return result; }
        var incoming = result.tracks || [], items = queue(), first = -1;
        incoming.forEach(function (item) { if (!items.some(function (old) { return key(old) === key(item); })) { if (first < 0) first = items.length; items.push(item); } });
        if (first >= 0) await setQueue(items, first, true, false);
        else toast(result.message || '没有新增可读取的音频');
        if (incoming.some(function (item) { return !item.persisted; })) toast('部分文件仅有本次访问权限，重启后请重新选择');
        return result;
      }
    };
    api.ready = w.AndroidRuntime.ready.then(async function (config) {
      if (!w.AndroidRuntime.isNative || !config.nativePlayback) return false;
      active = true;
      // Dispose any earlier HTML player before installing the state mirror.
      if (w.audio) { try { w.audio.pause(); w.audio.removeAttribute('src'); w.audio.load(); } catch (_) { } }
      if (w.audioCtx && w.audioCtx.close) { try { await w.audioCtx.close(); } catch (_) { } }
      facade = new w.EventTarget();
      Object.defineProperties(facade, {
        src: { get: function () { return state.queue[state.index] ? 'native:' + key(state.queue[state.index]) : ''; }, set: function () {} },
        currentTime: { get: function () { return (Number(state.positionMs) || 0) / 1000; }, set: function (seconds) { send({ action: 'seek', positionMs: Math.max(0, Number(seconds) || 0) * 1000 }).catch(function () {}); } },
        duration: { get: function () { return (Number(state.durationMs) || 0) / 1000; } },
        paused: { get: function () { return !state.playWhenReady; } }, ended: { get: function () { return false; } },
        error: { get: function () { return state.error ? { code: 2, message: state.error } : null; } },
        volume: { get: function () { return volume; }, set: function (value) { volume = Math.max(0, Math.min(1, Number(value) || 0)); send({ action: 'volume', volume: volume }).catch(function () {}); } },
        playbackRate: { get: function () { return 1; } }, muted: { get: function () { return volume === 0; }, set: function (value) { if (value) facade.volume = 0; } }
      });
      facade.play = function () { return send({ action: 'play' }); };
      facade.pause = function () { send({ action: 'pause' }).catch(function () {}); };
      facade.load = function () {}; facade.removeAttribute = function () {};
      w.audio = facade; w.source = null; w.gainNode = null;
      w.initAudio = function () {
        // Read-only analysis adapter backed by native PCM, with no second audio output graph.
        w.audioCtx = { get sampleRate() { return state.sampleRate || 48000; }, state: 'running', get currentTime() { return facade.currentTime; }, resume: function () { return Promise.resolve(); } };
        w.analyser = analysis(w.FFT_SIZE || 2048); w.beatAnalyser = analysis(w.BEAT_FFT_SIZE || 2048); w.audioReady = true;
      };
      w.resumeAudioAnalysis = function () { return Promise.resolve(); };
      w.applyVolumeToAudio = function () { if (Math.abs(volume - w.targetVolume) > 0.001) facade.volume = w.targetVolume; };
      w.preparePlaybackFadeIn = function () {};
      w.startPlaybackFadeIn = w.restorePlaybackGain = w.applyVolumeToAudio;
      w.fadeOutAndPauseAudio = function () { return send({ action: 'pause' }); };
      w.syncPlaybackStateFromAudioEvent = function () { w.playing = !!state.isPlaying; invoke('setPlayIcon', !!state.playWhenReady); };
      w.playQueueAt = function (index, options) {
        if (index < 0 || index >= (w.playQueue || []).length) return Promise.resolve(false);
        var op = setQueue(queue(), index, true, false);
        if (options && options.resumeAt > 0) return op.then(function () { return send({ action: 'seek', positionMs: options.resumeAt * 1000 }); }).catch(function () { return false; });
        return op.catch(function () { return false; });
      };
      w.togglePlay = function () { return send({ action: state.playWhenReady ? 'pause' : 'play' }).catch(function () { return false; }); };
      w.nextTrack = function () { return send({ action: 'next' }).catch(function () { return false; }); };
      w.prevTrack = function () { return send({ action: 'previous' }).catch(function () { return false; }); };
      w.cyclePlayMode = function () { var modes = ['sequence', 'random', 'single']; return send({ action: 'mode', mode: modes[(modes.indexOf(state.mode) + 1) % modes.length] }).catch(function () { return false; }); };
      w.clearQueue = function () { return setQueue([], 0, false, false); };
      w.removeFromQueue = function (at) { var items = queue(); items.splice(at, 1); return setQueue(items, w.currentIdx, state.playWhenReady, true); };
      w.shuffleQueue = function () { return send({ action: 'mode', mode: 'random' }); };
      ['queueSong', 'moveQueueIndexToTop'].forEach(function (name) {
        var original = w[name]; if (typeof original !== 'function') return;
        w[name] = function () { var result = original.apply(w, arguments); syncQueue(); return result; };
      });
      var render = w.renderQueuePanel;
      if (typeof render === 'function') w.renderQueuePanel = function () { var result = render.apply(w, arguments); syncQueue(); return result; };
      w.initAudio();
      w.addEventListener('android-native-playback', function (event) { accept(event.detail); });
      w.addEventListener('pageshow', function () { send({ action: 'snapshot' }).catch(function () {}); });
      d.addEventListener('visibilitychange', function () { if (!d.hidden) send({ action: 'snapshot' }).catch(function () {}); });
      await send({ action: 'snapshot' });
      await send({ action: 'volume', volume: volume });
      if (w.AndroidRuntime.latestPlayback) accept(w.AndroidRuntime.latestPlayback);
      return true;
    });
    api.ready.catch(function (error) { toast(error.message || '原生播放器连接失败，可重新打开 App'); });
    return api;
  }
  return { normalizeTrack: normalizeTrack, key: key, signature: signature, nativeMode: nativeMode, webMode: webMode, mount: mount };
});
