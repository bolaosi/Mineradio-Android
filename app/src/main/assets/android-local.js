/* Android SAF imports stay on this device. No file or blob URL is sent to an API. */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root && root.document) api.mount(root);
})(typeof window === 'object' ? window : null, function () {
  'use strict';

  function isAudioFile(file) {
    return !!file && (/^audio\//i.test(file.type || '') || /\.(mp3|m4a|aac|flac|wav|ogg|opus|webm|mp4|3gp)$/i.test(file.name || ''));
  }

  function createController(options) {
    var media = options.media;
    var queue = [], index = -1, serial = 0, importSerial = 0;
    var notify = options.notify || function () {};
    var changed = options.changed || function () {};
    function snapshot() {
      return { queue: queue.slice(), index: index, active: isActive(), paused: media.paused };
    }
    function isActive() {
      return index >= 0 && !!queue[index] && media.src === queue[index].url;
    }
    function emit() { changed(snapshot()); }
    function stop() {
      serial++;
      if (isActive()) {
        media.pause();
        media.removeAttribute('src');
        media.load();
      }
    }
    async function play(nextIndex) {
      if (!Number.isInteger(nextIndex) || !queue[nextIndex]) return false;
      var ticket = ++serial;
      var entry = queue[nextIndex];
      media.pause();
      index = nextIndex;
      if (options.beforePlay) options.beforePlay(entry);
      // The legacy online player installs property handlers; they must not advance its queue here.
      media.onended = null;
      media.onloadedmetadata = null;
      media.onerror = null;
      media.src = entry.url;
      media.load();
      emit();
      notify('正在播放：' + entry.name);
      try {
        if (options.resumeAudio) await options.resumeAudio();
        if (ticket !== serial) return false;
        await media.play();
        if (ticket !== serial || !isActive()) return false;
        emit();
        return true;
      } catch (error) {
        if (ticket !== serial || !isActive()) return false;
        media.pause();
        notify(error && error.name === 'NotAllowedError'
          ? '系统尚未允许播放，请点“播放”。'
          : '无法播放此文件：可能已失效或格式不受设备支持，请重新导入。');
        emit();
        return false;
      }
    }
    async function importFiles(files, autoplay) {
      files = Array.from(files || []);
      if (!files.length) { notify('已取消选择，队列未改变。'); return snapshot(); }
      var importTicket = ++importSerial;
      var firstNew = -1, skipped = 0, failed = 0;
      for (var file of files) {
        if (!isAudioFile(file)) { skipped++; continue; }
        if (!file.size) { failed++; continue; }
        var key = [file.name, file.size, file.lastModified || 0].join(':');
        if (queue.some(function (entry) { return entry.key === key; })) { skipped++; continue; }
        try {
          // Reading only a small slice detects an unavailable SAF document without copying the song.
          await file.slice(0, 16).arrayBuffer();
          if (importTicket !== importSerial) return snapshot();
          var url = options.createObjectURL(file);
          if (firstNew < 0) firstNew = queue.length;
          queue.push({ name: file.name || '本地音频', key: key, file: file, url: url });
        } catch (error) { failed++; }
      }
      emit();
      if (firstNew < 0) {
        notify(failed ? '文件为空、已失效或无法读取，请重新选择。' : '没有新增音频：请选择设备支持的音频文件，重复项已略过。');
      } else {
        var message = '已导入 ' + (queue.length - firstNew) + ' 首。';
        if (skipped) message += ' 已略过 ' + skipped + ' 个重复或非音频文件。';
        if (failed) message += ' ' + failed + ' 个文件为空或无法读取。';
        notify(message);
        if (autoplay) await play(firstNew);
      }
      return snapshot();
    }
    async function toggle() {
      if (!queue.length) { notify('请先导入本地音乐。'); return false; }
      if (!isActive()) return play(index >= 0 ? index : 0);
      if (media.paused || media.ended) {
        try {
          if (options.resumeAudio) await options.resumeAudio();
          await media.play();
          notify('播放中');
        } catch (error) { media.pause(); notify('播放失败：文件不可读取、格式不支持或需要再次点播放。'); return false; }
      } else { media.pause(); notify('已暂停'); }
      emit();
      return true;
    }
    function next(step) {
      if (!queue.length) { notify('请先导入本地音乐。'); return Promise.resolve(false); }
      return play(index < 0 ? 0 : (index + step + queue.length) % queue.length);
    }
    function remove(at) {
      if (!Number.isInteger(at) || !queue[at]) return;
      var active = isActive(), wasPlaying = active && !media.paused;
      var currentRemoved = at === index;
      if (currentRemoved) stop();
      var removed = queue.splice(at, 1)[0];
      options.revokeObjectURL(removed.url);
      if (!queue.length) index = -1;
      else if (at < index) index--;
      else if (currentRemoved) index = Math.min(at, queue.length - 1);
      emit();
      notify('已移出：' + removed.name);
      if (currentRemoved && wasPlaying && queue.length) return play(index);
    }
    function clear() {
      importSerial++;
      stop();
      queue.forEach(function (entry) { options.revokeObjectURL(entry.url); });
      queue = []; index = -1;
      emit();
      notify('本地队列已清空。');
    }
    function seek(seconds) {
      var duration = Number(media.duration);
      if (!isActive() || !Number.isFinite(duration) || duration <= 0) return false;
      try { media.currentTime = Math.max(0, Math.min(duration, Number(seconds) || 0)); return true; }
      catch (error) { notify('暂时无法跳转，请等待音频载入。'); return false; }
    }
    media.addEventListener('ended', function () {
      if (!isActive()) return;
      if (queue.length > 1) next(1);
      else { notify('播放完毕。'); emit(); }
    });
    media.addEventListener('error', function () {
      if (!isActive()) return;
      media.pause();
      notify(media.error && media.error.code === 3
        ? '音频解码失败：文件可能损坏或编码不受设备支持。'
        : '无法读取或不支持此音频，请重新导入或换一种格式。');
      emit();
    });
    ['play', 'pause', 'loadedmetadata', 'emptied'].forEach(function (event) { media.addEventListener(event, emit); });
    return { importFiles: importFiles, play: play, toggle: toggle, next: next, remove: remove, clear: clear,
      seek: seek, snapshot: snapshot, isActive: isActive, cancel: function () { notify('已取消选择，队列未改变。'); } };
  }

  function mount(win) {
    if (win.AndroidPlayback && win.AndroidRuntime && win.AndroidRuntime.isNative) {
      return win.AndroidPlayback.ready.then(function (enabled) { return enabled ? mountNative(win) : mountBrowser(win); });
    }
    return mountBrowser(win);
  }
  function mountNative(win) {
    var doc = win.document, player = win.AndroidPlayback, lastSignature = '', importing = false;
    var style = doc.createElement('style');
    style.textContent = '#android-local-entry{position:fixed;left:12px;top:calc(12px + env(safe-area-inset-top,0px));z-index:31000;min-height:44px;background:#163b34;color:#fff;border:1px solid #698f86;border-radius:12px;padding:10px 14px}' +
      '#android-local-panel{position:fixed;z-index:32000;inset:70px 12px 16px;max-width:620px;overflow:auto;overscroll-behavior:contain;touch-action:pan-y;padding:18px;background:#101c1f;color:#f1f8f7;border:1px solid #405450;border-radius:16px;font:15px/1.6 system-ui}' +
      '#android-local-panel[hidden]{display:none}#android-local-panel button{min-height:44px;padding:10px 12px;background:#163b34;color:#fff;border:1px solid #58736d;border-radius:10px;margin:4px}#android-local-panel input[type=range]{width:100%;height:44px;touch-action:pan-x}' +
      '#android-local-queue{padding:0;list-style:none}#android-local-queue li{display:flex;gap:6px;padding:6px 0;border-bottom:1px solid #31453d;align-items:center}#android-local-queue .al-song{flex:1;text-align:left;overflow-wrap:anywhere}#android-local-queue [aria-current=true]{background:#286e58}#android-local-status{color:#aaf6df;min-height:1.6em}';
    doc.head.appendChild(style);
    var entry = doc.createElement('button'); entry.id = 'android-local-entry'; entry.textContent = '本地音乐';
    var panel = doc.createElement('section'); panel.id = 'android-local-panel'; panel.hidden = true; panel.setAttribute('aria-label','本地导入与播放队列');
    panel.innerHTML = '<h2>本地音乐与播放队列</h2><button id="android-local-close">收起</button>' +
      '<p>系统文件选择器多选，文件不上传。支持持久权限的文件可在重启后恢复；授权失效或不支持持久权限时需要重选。</p>' +
      '<button id="android-local-import">导入音频（可多选）</button><button id="android-local-clear">清空播放队列</button>' +
      '<p id="android-local-status" role="status" aria-live="polite">原生后台播放已就绪</p><strong id="android-local-now">请选择音频</strong>' +
      '<div><button id="android-local-prev">上一首</button><button id="android-local-toggle">播放</button><button id="android-local-next">下一首</button></div>' +
      '<input type="range" id="android-local-progress" min="0" max="0" step="0.1" value="0" aria-label="播放进度"><output id="android-local-time">0:00 / 0:00</output><ol id="android-local-queue"></ol>';
    doc.body.append(entry,panel);
    var status = doc.getElementById('android-local-status'), progress = doc.getElementById('android-local-progress'), list = doc.getElementById('android-local-queue');
    function clock(value) { value=Math.floor(Math.max(0,Number(value)||0)/1000);return Math.floor(value/60)+':'+String(value%60).padStart(2,'0'); }
    function report(error) { status.textContent=error && error.message || '操作失败，请重试'; }
    function open() { panel.hidden=false;entry.setAttribute('aria-expanded','true');render(); }
    function close() { panel.hidden=true;entry.setAttribute('aria-expanded','false'); }
    function render() {
      var state=player.getState(), current=state.queue[state.index], sig=state.queue.map(function(s){return s.provider+':'+s.id;}).join('|')+':'+state.index;
      doc.getElementById('android-local-now').textContent=current?current.name+' · '+current.artist:'请选择音频';
      doc.getElementById('android-local-toggle').textContent=state.playWhenReady?'暂停':'播放';
      if (state.error) status.textContent=state.error+'；可点播放重试或切下一首';
      progress.max=String(Math.max(0,state.durationMs||0)/1000); progress.disabled=!(state.durationMs>0);
      if(doc.activeElement!==progress)progress.value=String(Math.max(0,state.positionMs||0)/1000);
      doc.getElementById('android-local-time').textContent=clock(state.positionMs)+' / '+clock(state.durationMs);
      if(sig!==lastSignature) {
        var savedTop=panel.scrollTop; lastSignature=sig;list.replaceChildren();
        state.queue.forEach(function(song,index){var li=doc.createElement('li'),play=doc.createElement('button'),remove=doc.createElement('button');
          play.className='al-song';play.textContent=(index+1)+'. '+song.name+(song.provider==='local'?'':'（在线）');
          if(index===state.index)play.setAttribute('aria-current','true');
          play.onclick=function(){controller.play(index).catch(report);};remove.textContent='移出';remove.onclick=function(){controller.remove(index).catch(report);};li.append(play,remove);list.appendChild(li);
        }); panel.scrollTop=savedTop;
      }
    }
    var controller={
      play:function(index){return player.setQueue(player.getState().queue,index,true,false);},
      toggle:function(){return player.command({action:player.getState().playWhenReady?'pause':'play'});},
      next:function(step){return player.command({action:step<0?'previous':'next'});},
      remove:function(index){var s=player.getState(),q=s.queue.slice();q.splice(index,1);return player.setQueue(q,s.index,s.playWhenReady,true);},
      clear:function(){return player.setQueue([],0,false,false);},
      seek:function(seconds){return player.command({action:'seek',positionMs:Math.max(0,Number(seconds)||0)*1000});},
      snapshot:function(){var s=player.getState();return {queue:s.queue,index:s.index,active:!!s.queue[s.index],paused:!s.playWhenReady};},
      isActive:function(){var s=player.getState();return !!s.queue[s.index]&&s.queue[s.index].provider==='local';},
      cancel:function(){status.textContent='已取消选择，队列未改变';}
    };
    entry.onclick=function(){panel.hidden?open():close();};doc.getElementById('android-local-close').onclick=close;
    doc.getElementById('android-local-import').onclick=async function(){if(importing)return;importing=true;this.disabled=true;
      try{var result=await player.importLocal();status.textContent=result.cancelled?'已取消选择，队列未改变':result.message||'文件已加入原生队列';}
      catch(e){report(e);}finally{importing=false;this.disabled=false;}};
    doc.getElementById('android-local-clear').onclick=function(){controller.clear().catch(report);};
    doc.getElementById('android-local-toggle').onclick=function(){controller.toggle().catch(report);};
    doc.getElementById('android-local-prev').onclick=function(){controller.next(-1).catch(report);};
    doc.getElementById('android-local-next').onclick=function(){controller.next(1).catch(report);};
    progress.onchange=function(){controller.seek(progress.value).catch(report);};
    win.addEventListener('android-playback-state',render);
    var upload=doc.getElementById('upload-btn');if(upload)upload.onclick=open;
    win.AndroidLocal={open:open,close:close,controller:controller,media:win.audio,native:true};render();return win.AndroidLocal;
  }
  function mountBrowser(win) {
    if (win.AndroidLocal) return win.AndroidLocal;
    var doc = win.document;
    var style = doc.createElement('style');
    style.textContent = '#android-local-entry{position:fixed;left:12px;top:calc(12px + env(safe-area-inset-top,0px));z-index:31000}' +
      '#android-local-panel{position:fixed;z-index:32000;left:12px;right:12px;top:calc(64px + env(safe-area-inset-top,0px));max-width:620px;max-height:calc(100dvh - 100px);overflow:auto;box-sizing:border-box;padding:18px;border:1px solid #405450;border-radius:16px;background:#101c1f;color:#f1f8f7;font:15px/1.6 system-ui,sans-serif;box-shadow:0 16px 50px #000b}' +
      '#android-local-panel[hidden]{display:none}#android-local-panel *{box-sizing:border-box}' +
      '#android-local-panel h2{font-size:20px;margin:0}#android-local-panel p{font-size:13px;margin:8px 0;color:#b9cfcc}' +
      '#android-local-panel button,#android-local-entry{min-height:44px;padding:8px 14px;border:1px solid #58736d;border-radius:10px;background:#163b34;color:#fff;font:inherit;cursor:pointer}' +
      '#android-local-panel button:disabled{opacity:.45}#android-local-panel button:focus-visible,#android-local-entry:focus-visible{outline:3px solid #7dffe4;outline-offset:2px}' +
      '#android-local-panel .al-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:10px 0}' +
      '#android-local-panel .al-head{justify-content:space-between}#android-local-status{min-height:24px;color:#aaf6df}' +
      '#android-local-progress{width:100%;min-height:44px;accent-color:#77e7c3}#android-local-queue{list-style:none;padding:0;margin:8px 0}' +
      '#android-local-queue li{display:flex;gap:8px;align-items:center;border-top:1px solid #2c4141;padding:8px 0}' +
      '#android-local-queue .al-song{flex:1;min-width:0;text-align:left;overflow-wrap:anywhere}#android-local-queue [aria-current=true]{background:#286e58}' +
      '#android-local-now{overflow-wrap:anywhere;font-weight:600}';
    doc.head.appendChild(style);
    var entry = doc.createElement('button');
    entry.id = 'android-local-entry'; entry.type = 'button'; entry.textContent = '本地音乐';
    entry.setAttribute('aria-controls', 'android-local-panel'); entry.setAttribute('aria-expanded', 'false');
    var panel = doc.createElement('section');
    panel.id = 'android-local-panel'; panel.hidden = true; panel.setAttribute('aria-label', '本地音乐播放器');
    panel.innerHTML = '<div class="al-row al-head"><h2>本地音乐</h2><button type="button" id="android-local-close">收起</button></div>' +
      '<p>通过系统文件选择器多选导入。音频仅在设备本地读取，不上传到后端。</p>' +
      '<p>本地队列仅在本次打开期间保留；关闭或重启 App 后请重新导入，未保存临时文件地址。</p>' +
      '<div class="al-row"><button type="button" id="android-local-import">导入音频（可多选）</button><button type="button" id="android-local-clear">清空队列</button></div>' +
      '<input type="file" id="android-local-files" accept="audio/*,.mp3,.m4a,.aac,.flac,.wav,.ogg,.opus,.webm,.mp4,.3gp" multiple hidden>' +
      '<div id="android-local-status" role="status" aria-live="polite">尚未导入音频</div><div id="android-local-now">请选择音频</div>' +
      '<div class="al-row"><button type="button" id="android-local-prev">上一首</button><button type="button" id="android-local-toggle">播放</button><button type="button" id="android-local-next">下一首</button></div>' +
      '<input type="range" id="android-local-progress" min="0" max="0" step="0.1" value="0" aria-label="播放进度" disabled>' +
      '<output id="android-local-time">0:00 / 0:00</output><ol id="android-local-queue"></ol>';
    doc.body.append(entry, panel);
    var media = win.audio && typeof win.audio.play === 'function' ? win.audio : new win.Audio();
    win.audio = media;
    var input = doc.getElementById('android-local-files');
    var status = doc.getElementById('android-local-status');
    var list = doc.getElementById('android-local-queue');
    var progress = doc.getElementById('android-local-progress');
    var lastQueueSignature = '';
    function safeCall(name) {
      try { if (typeof win[name] === 'function') win[name].apply(win, Array.prototype.slice.call(arguments, 1)); } catch (error) { /* Optional visual UI must not block local playback. */ }
    }
    function clock(seconds) {
      seconds = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
      return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
    }
    function updateTime() {
      var active = controller.isActive();
      var duration = active && Number.isFinite(media.duration) ? Math.max(0, media.duration) : 0;
      progress.disabled = !duration; progress.max = String(duration);
      if (doc.activeElement !== progress) progress.value = String(active ? media.currentTime || 0 : 0);
      doc.getElementById('android-local-time').textContent = clock(active ? media.currentTime : 0) + ' / ' + clock(duration);
    }
    function render(state) {
      var current = state.queue[state.index];
      doc.getElementById('android-local-now').textContent = current ? current.name + (state.active ? '' : '（未播放）') : '请选择音频';
      doc.getElementById('android-local-toggle').textContent = state.active && !media.paused && !media.ended ? '暂停' : '播放';
      ['prev', 'toggle', 'next', 'clear'].forEach(function (name) { doc.getElementById('android-local-' + name).disabled = !state.queue.length; });
      var signature = state.queue.map(function (song) { return song.key; }).join('|') + ':' + state.index + ':' + state.active;
      if (signature !== lastQueueSignature) {
        lastQueueSignature = signature; list.replaceChildren();
        state.queue.forEach(function (song, i) {
          var li = doc.createElement('li'), playButton = doc.createElement('button'), removeButton = doc.createElement('button');
          playButton.type = 'button'; playButton.className = 'al-song'; playButton.textContent = (i + 1) + '. ' + song.name;
          if (state.active && i === state.index) playButton.setAttribute('aria-current', 'true');
          playButton.addEventListener('click', function () { controller.play(i); });
          removeButton.type = 'button'; removeButton.textContent = '移出'; removeButton.setAttribute('aria-label', '移出 ' + song.name);
          removeButton.addEventListener('click', function () { controller.remove(i); });
          li.append(playButton, removeButton); list.appendChild(li);
        });
      }
      updateTime();
    }
    var controller = createController({ media: media,
      createObjectURL: function (file) { return win.URL.createObjectURL(file); },
      revokeObjectURL: function (url) { win.URL.revokeObjectURL(url); },
      notify: function (text) { status.textContent = text; }, changed: render,
      resumeAudio: async function () {
        if (win.audioCtx && win.audioCtx.state === 'suspended') await win.audioCtx.resume();
        safeCall('restorePlaybackGain');
      },
      beforePlay: function (song) {
        // An outstanding online URL request is invalidated before a local source is installed.
        win.trackSwitchToken = (Number(win.trackSwitchToken) || 0) + 1;
        win.audioFadeSerial = (Number(win.audioFadeSerial) || 0) + 1;
        safeCall('clearAudioFadeTimers'); safeCall('finalizeListenSession', false);
        safeCall('cancelBeatAnalysisTimer'); safeCall('cancelBeatPrefetchTimer'); safeCall('cancelDjBeatAnalysisTimer');
        win.currentLocalSong = { type: 'local', name: song.name.replace(/\.[^.]+$/, ''), artist: '本地文件', localKey: song.key, localUrl: song.url };
        win.currentIdx = -1; win.audio = media;
        safeCall('bindPlaybackProgressEvents', media); safeCall('applyVolumeToAudio');
        safeCall('updateControlTrackInfo', win.currentLocalSong); safeCall('updateCustomCoverButton');
        var title = doc.getElementById('thumb-title'), artist = doc.getElementById('thumb-artist');
        if (title) title.textContent = win.currentLocalSong.name;
        if (artist) artist.textContent = '本地文件';
      }
    });
    function open() { panel.hidden = false; entry.setAttribute('aria-expanded', 'true'); }
    function close() { panel.hidden = true; entry.setAttribute('aria-expanded', 'false'); }
    entry.addEventListener('click', function () { panel.hidden ? open() : close(); });
    doc.getElementById('android-local-close').addEventListener('click', close);
    doc.getElementById('android-local-import').addEventListener('click', function () { input.click(); });
    doc.getElementById('android-local-clear').addEventListener('click', controller.clear);
    doc.getElementById('android-local-toggle').addEventListener('click', controller.toggle);
    doc.getElementById('android-local-prev').addEventListener('click', function () { controller.next(-1); });
    doc.getElementById('android-local-next').addEventListener('click', function () { controller.next(1); });
    input.addEventListener('change', function () { controller.importFiles(input.files, true); input.value = ''; });
    input.addEventListener('cancel', controller.cancel);
    win.addEventListener('android-file-picker-cancel', function () { if (!panel.hidden) controller.cancel(); });
    progress.addEventListener('input', function () { controller.seek(progress.value); updateTime(); });
    media.addEventListener('timeupdate', updateTime);
    media.addEventListener('loadedmetadata', updateTime);
    media.addEventListener('play', function () { safeCall('setPlayIcon', !media.paused); });
    media.addEventListener('pause', function () { safeCall('setPlayIcon', false); });
    ['nextTrack', 'prevTrack'].forEach(function (name) {
      var original = win[name];
      win[name] = function () {
        if (controller.isActive()) return controller.next(name === 'nextTrack' ? 1 : -1);
        if (typeof original === 'function') return original.apply(this, arguments);
      };
    });
    var uploadButton = doc.getElementById('upload-btn');
    if (uploadButton) { uploadButton.onclick = open; uploadButton.title = '本地音乐'; }
    win.addEventListener('pagehide', controller.clear);
    win.AndroidLocal = { open: open, close: close, controller: controller, media: media };
    render(controller.snapshot());
    return win.AndroidLocal;
  }
  return { isAudioFile: isAudioFile, createController: createController, mount: mount };
});
