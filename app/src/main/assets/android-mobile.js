/* Touch navigation uses real DOM scroll surfaces; playback stays with the shared player. */
(function (w) {
  'use strict';
  if (!w.document || !(w.AndroidRuntime && w.AndroidRuntime.isNative || w.matchMedia('(pointer:coarse)').matches)) return;
  var d = w.document, key = 'mineradio-mobile-preferences-v1';
  var defaults = { visual: true, lyrics: !(w.fx && w.fx.particleLyrics === false), eq: { low: 0, mid: 0, high: 0 } }, preferences;
  try { preferences = Object.assign({}, defaults, JSON.parse(w.localStorage.getItem(key) || '{}')); } catch (_) { preferences = Object.assign({}, defaults); }
  preferences.eq = Object.assign({}, defaults.eq, preferences.eq || {});
  ['low', 'mid', 'high'].forEach(function (band) { preferences.eq[band] = Math.max(-12, Math.min(12, Number(preferences.eq[band]) || 0)); });
  var route = '', positions = Object.create(null), epoch = 0, requestAbort = null, searchTimer = null;
  var search = { text: '', source: 'netease', songs: [], status: '输入歌曲或歌手', queryKey: '' };
  var library = { lists: [], loaded: false, accountKey: '' }, detail = null, queueSignature = '', nativeEqAvailable = false;
  d.documentElement.classList.add('android-mobile');
  var style = d.createElement('style');
  style.textContent = '.android-mobile #android-local-entry{display:none}.android-mobile #android-online-button{bottom:calc(77px + env(safe-area-inset-bottom,0px))}' +
    '.android-mobile #top-right{top:calc(12px + env(safe-area-inset-top,0px));right:12px}.android-mobile #bottom-bar{bottom:calc(76px + env(safe-area-inset-bottom,0px))!important}' +
    '.android-mobile #bottom-handle{bottom:calc(77px + env(safe-area-inset-bottom,0px))}.android-mobile #empty-home{bottom:95px}' +
    '.android-mobile #search-area,.android-mobile #hotkey-settings-btn{display:none!important}.android-mobile .mobile-unavailable{display:none!important}.android-mobile #fx-panel{z-index:52000;top:calc(66px + env(safe-area-inset-top,0px));bottom:calc(80px + env(safe-area-inset-bottom,0px));max-height:calc(var(--mobile-height,100dvh) - 150px);overscroll-behavior:contain;touch-action:pan-y}' +
    '.android-mobile #fx-panel .fx-head{position:sticky;top:0;z-index:6;background:#10201f;display:flex;justify-content:space-between;align-items:center;gap:8px}.android-mobile #mobile-fx-close{min-height:44px;min-width:60px;border:1px solid #627f6e;border-radius:8px;background:#193f34;color:#effdf5;touch-action:manipulation}' +
    '.android-mobile #playlist-panel,.android-mobile #mini-queue-list,.android-mobile #android-local-panel{touch-action:pan-y;overscroll-behavior:contain;-webkit-overflow-scrolling:touch}.android-mobile #android-local-panel{top:calc(64px + env(safe-area-inset-top,0px));bottom:calc(75px + env(safe-area-inset-bottom,0px));max-height:calc(var(--mobile-height,100dvh) - 144px)}' +
    '.android-mobile .modal{max-height:calc(var(--mobile-height,100dvh) - 36px);overflow-y:auto}.android-mobile .custom-lyric-input{user-select:text}' +
    'body.mobile-visual-off #canvas-container,body.mobile-visual-off #idle-guide-canvas{visibility:hidden}' +
    '#mobile-dock{box-sizing:border-box;position:fixed;z-index:45000;bottom:calc(8px + env(safe-area-inset-bottom,0px));left:calc(10px + env(safe-area-inset-left,0px));right:calc(10px + env(safe-area-inset-right,0px));margin:0 auto;max-width:620px;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:5px;padding:5px;border-radius:18px;color:#f4fcf8;font:14px system-ui,sans-serif}' +
    '#mobile-dock button,#mobile-panel button{min-height:44px;border:1px solid #4a6c60;border-radius:9px;background:#193f34;color:#effdf5;font:inherit;padding:8px 10px;touch-action:manipulation}' +
    '#mobile-dock button{min-width:0;min-height:44px;padding:8px 2px}' +
    '#mobile-panel{position:fixed;z-index:46000;inset:calc(64px + env(safe-area-inset-top,0px)) 10px calc(75px + env(safe-area-inset-bottom,0px));max-height:calc(var(--mobile-height,100dvh) - 144px);display:flex;flex-direction:column;border:1px solid #587062;border-radius:15px;background:#10201ff7;color:#edf9f4;box-shadow:0 18px 70px #000b;font:14px/1.5 system-ui,sans-serif;overflow:hidden}' +
    '#mobile-panel[hidden]{display:none}#mobile-panel *{box-sizing:border-box}#mobile-panel h2{font-size:19px;margin:0;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}#mobile-panel h3{font-size:16px;margin:20px 0 10px}' +
    '#mobile-panel .mobile-head{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:10px 12px;border-bottom:1px solid #334e43;flex-shrink:0}' +
    '#mobile-now{padding:5px 12px;font-size:12px;color:#b9d6c7;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex-shrink:0}' +
    '#mobile-scroll{min-height:0;overflow-y:auto;overflow-x:hidden;touch-action:pan-y;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;padding:12px;flex:1}' +
    '#mobile-panel .mobile-row{display:flex;align-items:center;gap:8px;min-height:66px;border-bottom:1px solid #2e483c;padding:8px 0}' +
    '#mobile-panel .mobile-row .mobile-song{flex:1;min-width:0;text-align:left;background:transparent;border-color:transparent;overflow-wrap:anywhere}' +
    '#mobile-panel .mobile-song strong{display:block;font-size:14px}#mobile-panel small{display:block;color:#b7d1c3;font-size:12px}' +
    '#mobile-panel .is-current .mobile-song{border-color:#8ed5ac;background:#24563e}#mobile-panel input:not([type=range]):not([type=color]),#mobile-panel select{font:16px system-ui,sans-serif;min-height:44px;width:100%;background:#091915;color:#effdf5;border:1px solid #627f6e;border-radius:8px;padding:9px;user-select:text}' +
    '#mobile-panel input[type=range]{width:100%;min-height:44px;accent-color:#90dfb5;touch-action:pan-x}#mobile-panel input[type=color]{width:100%;height:44px;background:#10211b;border:1px solid #6d917b}' +
    '#mobile-panel label{display:block;margin:10px 0}#mobile-panel .mobile-actions{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0}#mobile-panel .mobile-searchline{display:flex;gap:7px;margin-top:8px}#mobile-panel .mobile-searchline input{min-width:0;flex:1}' +
    '#mobile-panel .mobile-status{color:#c5e5d1;min-height:25px;margin:8px 0;overflow-wrap:anywhere}#mobile-panel button:disabled{opacity:.45}#mobile-panel [role=status]{user-select:text}' +
    '#mobile-panel button:focus-visible,#mobile-dock button:focus-visible{outline:3px solid #c7f6ab;outline-offset:2px}';
  d.head.appendChild(style);
  var dock = d.createElement('nav'); dock.id = 'mobile-dock'; dock.dataset.mobileUi = '1'; dock.setAttribute('aria-label', '手机播放器导航');
  ['search:搜索', 'library:歌单', 'queue:队列', 'visual:视觉', 'local:本地'].forEach(function (entry) {
    var parts = entry.split(':'), button = d.createElement('button'); button.className = 'android-glass-action'; button.type = 'button'; button.dataset.route = parts[0]; button.textContent = parts[1];
    button.setAttribute('aria-pressed', 'false'); button.addEventListener('click', function () { open(parts[0]); }); dock.appendChild(button);
  });
  var panel = d.createElement('section'); panel.id = 'mobile-panel'; panel.dataset.mobileUi = '1'; panel.hidden = true; panel.setAttribute('aria-label', '手机播放器');
  panel.innerHTML = '<div class="mobile-head"><button id="mobile-back" type="button">返回</button><h2 id="mobile-title"></h2><button id="mobile-close" type="button">收起</button></div><div id="mobile-now"></div><div id="mobile-scroll"></div>';
  d.body.append(dock, panel);
  var fxHead = d.querySelector('#fx-panel .fx-head');
  if (fxHead) {
    var fxClose = button('返回', function () { w.toggleFxPanel(false); open('visual'); }, '返回手机视觉设置'); fxClose.id = 'mobile-fx-close'; fxHead.appendChild(fxClose);
    var subtitle = fxHead.querySelector('.fx-sub'); if (subtitle) subtitle.textContent = '手指滑动调节 · 点返回收起';
  }
  // These controls require Electron desktop windows or camera permission that this Android app does not expose.
  ['t-desktopLyrics', 't-desktopLyricsClickThrough', 't-desktopLyricsCinema', 't-desktopLyricsHighlight', 't-wallpaperMode', 'fx-desktoplyricssize', 'fx-desktoplyricsopacity', 'fx-desktoplyricsy', 'desktop-lyrics-fps-seg', 'fx-wallpaperopacity', 'cam-seg'].forEach(function (id) {
    var control = d.getElementById(id); if (control) (control.closest('.fx-slider') || control).classList.add('mobile-unavailable');
  });
  d.querySelectorAll('#fx-panel .fx-section-label').forEach(function (label) { if (/桌面|壁纸|摄像头/.test(label.textContent)) label.classList.add('mobile-unavailable'); });
  var stageTitle = d.querySelector('#fx-stage-fold .fx-fold-title'); if (stageTitle) stageTitle.innerHTML = '<strong>3D 歌单架</strong><small>布局 / 外观</small>';
  var scroll = d.getElementById('mobile-scroll');
  function persist() { try { w.localStorage.setItem(key, JSON.stringify(preferences)); } catch (_) { notify('设置未能保存，请检查设备存储空间'); } }
  function notify(text) { if (typeof w.showToast === 'function') w.showToast(text); }
  function stopRequest() { epoch++; if (requestAbort) requestAbort.abort(); requestAbort = null; clearTimeout(searchTimer); }
  function positionKey() { return route === 'library' && detail ? 'tracks:' + detail.provider + ':' + detail.id : route; }
  function remember() { if (route && !panel.hidden) positions[positionKey()] = scroll.scrollTop; }
  function replace(content, top) { scroll.replaceChildren(content); scroll.scrollTop = top == null ? positions[positionKey()] || 0 : top; }
  function node(tag, text, className) { var element = d.createElement(tag); if (text != null) element.textContent = text; if (className) element.className = className; return element; }
  function button(text, action, label) { var element = node('button', text); element.type = 'button'; if (label) element.setAttribute('aria-label', label); element.addEventListener('click', action); return element; }
  function status(text) { var element = node('div', text, 'mobile-status'); element.setAttribute('role', 'status'); return element; }
  function close() { remember(); stopRequest(); panel.hidden = true; dock.querySelectorAll('button').forEach(function (b) { b.setAttribute('aria-pressed', 'false'); }); }
  function updateNow() { var song = w.currentLocalSong || w.playQueue && w.playQueue[w.currentIdx]; d.getElementById('mobile-now').textContent = song ? ((!w.audio || w.audio.paused ? '已暂停 · ' : '播放中 · ') + (song.name || '当前歌曲')) : '浏览和搜索不会暂停音乐'; }
  function open(next) {
    remember(); stopRequest();
    if (w.toggleFxPanel) w.toggleFxPanel(false);
    if (next !== 'local' && w.AndroidLocal && w.AndroidLocal.close) w.AndroidLocal.close();
    if (next === 'local') { close(); if (w.AndroidLocal) w.AndroidLocal.open(); dock.querySelector('[data-route=local]').setAttribute('aria-pressed','true'); return; }
    route = next; panel.hidden = false; updateNow();
    dock.querySelectorAll('button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.route === next)); });
    d.getElementById('mobile-title').textContent = { search: '搜索音乐', library: '我的歌单', queue: '播放队列', visual: '视觉与声音' }[route];
    if (route === 'search') renderSearch();
    else if (route === 'library') {
      var accounts = ['netease', 'qq'].map(function (provider) { var account = provider === 'qq' ? w.qqLoginStatus : w.loginStatus; return account && account.loggedIn ? provider + ':' + account.userId : ''; }).join('|');
      if (accounts !== library.accountKey) { library = { lists: [], loaded: false, accountKey: accounts }; detail = null; }
      if (detail) renderTracks(); else loadLibrary();
    }
    else if (route === 'queue') renderQueue();
    else if (route === 'visual') renderVisual();
  }
  d.getElementById('mobile-close').addEventListener('click', close);
  d.addEventListener('click', function (event) {
    if (event.target && event.target.closest && event.target.closest('#user-btn,#home-btn,#android-online-button')) { close(); if (w.AndroidLocal && w.AndroidLocal.close) w.AndroidLocal.close(); }
    if (event.target && event.target.closest && event.target.closest('#android-local-close')) dock.querySelector('[data-route=local]').setAttribute('aria-pressed','false');
  }, true);
  d.getElementById('mobile-back').addEventListener('click', function () {
    if (route === 'library' && detail) { remember(); stopRequest(); detail = null; renderLibrary(); }
    else close();
  });
  function addSong(song) { if (typeof w.queueSong === 'function') { w.queueSong(song); notify('已加入队列：' + song.name); } }
  async function playSongs(songs, index) {
    try {
      if (!songs[index]) return;
      w.playQueue = songs.map(function (song) { return w.cloneSong ? w.cloneSong(song) : Object.assign({}, song); });
      w.currentIdx = index;
      if (w.safeRenderQueuePanel) w.safeRenderQueuePanel('mobile-play', { scrollCurrent: false });
      await w.playQueueAt(index, { manual: true, preserveHomeState: true });
      updateNow(); markCurrent();
    } catch (_) { notify('播放未能开始，请检查网络或歌曲权限后重试'); }
  }
  function songRows(songs, queueMode) {
    var list = node('div'); list.id = 'mobile-song-list';
    songs.forEach(function (song, index) {
      var row = node('div', null, 'mobile-row'); row.dataset.songIndex = String(index); row.dataset.songKey = songKey(song);
      var play = button('', function () { if (queueMode) Promise.resolve(w.playQueueAt(index, { manual: true })).catch(function () { notify('播放失败，请重试'); }); else playSongs(songs, index); }, '播放 ' + song.name);
      play.className = 'mobile-song'; play.append(node('strong', (index + 1) + '. ' + (song.name || '未命名歌曲')), node('small', song.artist || '未知歌手'));
      row.appendChild(play);
      if (queueMode) row.appendChild(button('移出', function () { var top = scroll.scrollTop; w.removeFromQueue(index); renderQueue(top); }, '移出 ' + song.name));
      else row.appendChild(button('+ 队列', function () { addSong(song); }, '加入队列 ' + song.name));
      list.appendChild(row);
    }); return list;
  }
  function songKey(song) { return String(song && (song.localKey || ((song.provider || song.source || 'netease') + ':' + (song.mid || song.id))) || ''); }
  function markCurrent() { var song = w.currentLocalSong || w.playQueue && w.playQueue[w.currentIdx], active = songKey(song); scroll.querySelectorAll('[data-song-key]').forEach(function (row) { row.classList.toggle('is-current', !!active && row.dataset.songKey === active); }); }
  function renderSearch(top) {
    var previousInput = d.getElementById('mobile-search-query'), focused = previousInput && d.activeElement === previousInput;
    var selection = focused ? previousInput.selectionStart : null;
    var box = node('div'), form = node('form'); form.id = 'mobile-search-form';
    var source = node('select'); source.id = 'mobile-search-source'; source.setAttribute('aria-label', '搜索平台');
    ['netease:网易云音乐', 'qq:QQ 音乐'].forEach(function (entry) { var p = entry.split(':'), opt = node('option', p[1]); opt.value = p[0]; source.appendChild(opt); }); source.value = search.source;
    var line = node('div', null, 'mobile-searchline'), input = node('input'); input.id = 'mobile-search-query'; input.type = 'search'; input.placeholder = '歌曲 / 歌手'; input.setAttribute('aria-label', '搜索歌曲或歌手'); input.value = search.text; input.autocomplete = 'off';
    var submit = node('button', '搜索'); submit.type = 'submit';
    line.append(input, submit, button('清空', function () { stopRequest(); search.text = ''; search.songs = []; search.status = '输入歌曲或歌手'; search.queryKey = ''; renderSearch(0); d.getElementById('mobile-search-query').focus(); }));
    form.append(source, line); box.append(form, status(search.status)); box.appendChild(songRows(search.songs));
    input.addEventListener('input', function () { search.text = input.value; clearTimeout(searchTimer); searchTimer = setTimeout(runSearch, 400); });
    source.addEventListener('change', function () { search.source = source.value; runSearch(); });
    form.addEventListener('submit', function (event) { event.preventDefault(); clearTimeout(searchTimer); search.text = input.value; input.blur(); runSearch(); });
    replace(box, top); markCurrent();
    if (focused) { input.focus({ preventScroll: true }); if (selection != null) { try { input.setSelectionRange(selection, selection); } catch (_) {} } }
  }
  async function runSearch() {
    if (route !== 'search' || panel.hidden) return;
    stopRequest(); var run = epoch, query = search.text.trim();
    if (!query) { search.songs = []; search.status = '输入歌曲或歌手'; renderSearch(0); return; }
    search.status = '正在搜索…'; renderSearch(0); requestAbort = new AbortController();
    try {
      var result = await w.apiJson((search.source === 'qq' ? '/api/qq/search' : '/api/search') + '?keywords=' + encodeURIComponent(query) + '&limit=50', { signal: requestAbort.signal, timeoutMs: 20000 });
      if (run !== epoch) return;
      search.songs = (result.songs || []).map(function (song) { return Object.assign({}, song, { provider: search.source }); }); search.queryKey = search.source + ':' + query;
      search.status = search.songs.length ? '找到 ' + search.songs.length + ' 首；点曲名播放，或加入队列' : '没有找到歌曲，换个关键词试试'; renderSearch(0);
    } catch (error) { if (run === epoch) { search.songs = []; search.status = '搜索失败：' + (error.message || '请检查网络后重试'); renderSearch(0); } }
  }
  async function loadLibrary() {
    if (library.loaded) { renderLibrary(); return; }
    replace(status('正在载入歌单…'));
    var run = epoch; requestAbort = new AbortController();
    try {
      var active = [];
      if (w.loginStatus && w.loginStatus.loggedIn) active.push('netease'); if (w.qqLoginStatus && w.qqLoginStatus.loggedIn) active.push('qq');
      if (!active.length) { var loggedOut = node('div'); loggedOut.append(status('登录后可查看本人歌单'), button('登录账号', function () { close(); w.openProviderLogin('netease'); })); replace(loggedOut); return; }
      var result = await Promise.all(active.map(async function (provider) { var response = await w.apiJson(provider === 'qq' ? '/api/qq/user/playlists' : '/api/user/playlists', { signal: requestAbort.signal, timeoutMs: 20000 }); return (response.playlists || []).map(function (item) { return Object.assign({}, item, { provider: provider }); }); }));
      if (run !== epoch) return; library.lists = [].concat.apply([], result); library.loaded = true; renderLibrary();
    } catch (error) { if (run === epoch) { var failed = node('div'); failed.append(status('歌单加载失败：' + (error.message || '请稍后重试')), button('重试', loadLibrary)); replace(failed); } }
  }
  function renderLibrary() {
    d.getElementById('mobile-title').textContent = '我的歌单'; var box = node('div'); box.appendChild(button('刷新歌单', function () { library.loaded = false; loadLibrary(); }));
    if (!library.lists.length) box.appendChild(status('当前账号没有返回歌单'));
    library.lists.forEach(function (item) { var row = node('div', null, 'mobile-row'); var openButton = button('', function () { remember(); detail = { id: item.id, provider: item.provider, name: item.name, tracks: null }; loadTracks(); }); openButton.className = 'mobile-song'; openButton.append(node('strong', item.name || '未命名歌单'), node('small', (item.provider === 'qq' ? 'QQ 音乐' : '网易云') + ' · ' + (item.trackCount || 0) + ' 首')); row.appendChild(openButton); box.appendChild(row); }); replace(box);
  }
  async function loadTracks() {
    stopRequest(); var run = epoch, current = detail; replace(status('正在载入歌曲…'), 0); requestAbort = new AbortController();
    try {
      var response = await w.apiJson((current.provider === 'qq' ? '/api/qq/playlist/tracks' : '/api/playlist/tracks') + '?id=' + encodeURIComponent(current.id), { signal: requestAbort.signal, timeoutMs: 25000 });
      if (run !== epoch || detail !== current) return;
      current.tracks = (response.tracks || []).map(function (song) { return Object.assign({}, song, { provider: current.provider }); }); current.message = response.partial ? response.message || '本次仅载入部分歌曲' : ''; renderTracks();
    } catch (error) { if (run === epoch) { var failed = node('div'); failed.append(status('歌曲加载失败：' + (error.message || '请重试')), button('重试', loadTracks)); replace(failed); } }
  }
  function renderTracks() { if (!detail.tracks) { loadTracks(); return; } d.getElementById('mobile-title').textContent = detail.name || '歌单歌曲'; var box = node('div'); box.append(status(detail.message || (detail.tracks.length + ' 首，浏览位置会保留')), songRows(detail.tracks)); replace(box); markCurrent(); }
  function renderQueue(top) { queueSignature = (w.playQueue || []).map(songKey).join('|'); var box = node('div'); box.append(status((w.playQueue || []).length + ' 首；点击曲名播放，列表不会自动滚回当前歌曲'), songRows(w.playQueue || [], true)); replace(box, top); markCurrent(); }
  function selectControl(box, title, id, items, value, changed) { var label = node('label', title), control = node('select'); control.id = id; items.forEach(function (item) { var option = node('option', item[1]); option.value = item[0]; control.appendChild(option); }); control.value = String(value); control.addEventListener('change', function () { changed(control.value); }); label.appendChild(control); box.appendChild(label); return control; }
  function rangeControl(box, title, id, value, min, max, step, changed) { var label = node('label', title), control = node('input'), output = node('output', String(value)); control.id = id; control.type = 'range'; control.min = min; control.max = max; control.step = step; control.value = value; control.setAttribute('aria-label', title); control.addEventListener('input', function () { output.textContent = control.value; changed(Number(control.value)); }); label.append(control, output); box.appendChild(label); return control; }
  function toggleControl(box, title, id, checked, changed) { var control = button(title + (checked ? '：开启' : '：关闭'), function () { checked = !checked; control.textContent = title + (checked ? '：开启' : '：关闭'); control.setAttribute('aria-pressed', String(checked)); changed(checked); }); control.id = id; control.setAttribute('aria-pressed', String(checked)); box.appendChild(control); return control; }
  async function applyEq() {
    try {
      if (w.AndroidRuntime && w.AndroidRuntime.isNative) {
        var playback = w.AndroidPlayback, snapshot = playback && playback.getState();
        if (!snapshot || snapshot.eqSupported !== true) return;
        await playback.setEq(Object.assign({}, preferences.eq));
      } else if (w.setAudioEqualizer) w.setAudioEqualizer(preferences.eq);
    } catch (_) { notify('此设备未能应用 EQ，请重试或恢复默认'); }
  }
  function updateEqAvailability() {
    var native = w.AndroidRuntime && w.AndroidRuntime.isNative, snapshot = w.AndroidPlayback && w.AndroidPlayback.getState(), supported = !native || snapshot && snapshot.eqSupported === true;
    ['low', 'mid', 'high'].forEach(function (band) { var input = d.getElementById('mobile-eq-' + band); if (input) input.disabled = !supported; });
    var note = d.getElementById('mobile-eq-note'); if (note) note.textContent = supported ? (native ? '使用 Android 原生 EQ，范围 -12 至 +12 dB' : '浏览器预览使用真实三段 Web Audio EQ') : snapshot && snapshot.eqSupported === false ? '此设备不支持原生 EQ，控制已禁用' : '正在等待原生播放器报告 EQ 支持情况';
  }
  function renderVisual() {
    var box = node('div'), fx = w.fx || {};
    toggleControl(box, '视觉舞台', 'mobile-visual-enabled', preferences.visual !== false, function (value) { preferences.visual = value; d.body.classList.toggle('mobile-visual-off', !value); persist(); });
    selectControl(box, '界面模式', 'mobile-mode', [['simple', '简约模式'], ['diy', 'DIY 玩家模式']], w.diyPlayerMode ? 'diy' : 'simple', function (value) { w.applyDiyMode(value === 'diy', { save: true }); });
    selectControl(box, '视觉预设（选择后不被播放覆盖）', 'mobile-preset', (w.presetMeta || []).map(function (preset, index) { return [String(index), preset.name]; }), w.playbackVisualPreset, function (value) { w.setPreset(Number(value), { commitPlaybackPreset: true }); });
    selectControl(box, '画质 / 负载', 'mobile-quality', [['eco', '低负载'], ['balanced', '均衡'], ['high', '高画质']], fx.performanceQuality || 'high', function (value) { w.setPerformanceQualityMode(value); });
    var more = node('div', null, 'mobile-actions'); more.appendChild(button('打开完整 DIY 控制台', function () { close(); w.applyDiyMode(true, { save: true }); w.toggleFxPanel(true); })); box.appendChild(more);
    box.appendChild(node('h3', '背景'));
    var color = node('input'); color.id = 'mobile-background-color'; color.type = 'color'; color.value = fx.backgroundColor || '#000000'; color.setAttribute('aria-label', '背景颜色'); color.addEventListener('input', function () { w.setCustomBackgroundColor(color.value, true); }); box.appendChild(color);
    rangeControl(box, '背景不透明度', 'mobile-background-opacity', fx.backgroundOpacity == null ? 1 : fx.backgroundOpacity, 0, 1, 0.05, function (value) { w.setCustomBackgroundOpacity(value, true); });
    var backgrounds = node('div', null, 'mobile-actions'); backgrounds.append(button('选择图片 / 视频背景', function () { d.getElementById('background-image-input').click(); }), button('清除背景媒体', function () { w.clearCustomBackgroundImage(); }), button('封面渐变背景', function () { w.setCustomBackgroundCoverMode(); })); box.appendChild(backgrounds);
    box.appendChild(node('small', '图片与本地视频使用现有设备存储功能；媒体解码和设备空间限制会明确提示。'));
    box.appendChild(node('h3', '歌词与效果'));
    toggleControl(box, '舞台歌词', 'mobile-lyrics', preferences.lyrics !== false, function (value) { preferences.lyrics = value; w.setParticleLyricsSilently(value); persist(); });
    ['cinema:电影镜头', 'bloom:粒子溢光'].forEach(function (item) { var p = item.split(':'); toggleControl(box, p[1], 'mobile-' + p[0], !!fx[p[0]], function (value) { if (!!w.fx[p[0]] !== value) w.toggleFx(p[0]); }); });
    var lyrics = node('div', null, 'mobile-actions'); lyrics.appendChild(button('当前歌曲自定义歌词', function () { close(); w.openCustomLyricModal(); })); box.appendChild(lyrics);
    box.appendChild(node('h3', '均衡器 EQ'));
    ['low:低频', 'mid:中频', 'high:高频'].forEach(function (item) { var p = item.split(':'); rangeControl(box, p[1] + ' dB', 'mobile-eq-' + p[0], preferences.eq[p[0]], -12, 12, 1, function (value) { preferences.eq[p[0]] = value; persist(); applyEq(); }); });
    var eqNote = status(''); eqNote.id = 'mobile-eq-note'; box.append(eqNote, button('EQ 恢复平直', function () { preferences.eq = { low: 0, mid: 0, high: 0 }; persist(); applyEq(); var top = scroll.scrollTop; renderVisual(); scroll.scrollTop = top; }));
    replace(box); updateEqAvailability();
  }
  function resize() { var viewport = w.visualViewport; d.documentElement.style.setProperty('--mobile-height', (viewport ? viewport.height : w.innerHeight) + 'px'); }
  w.addEventListener('resize', resize); if (w.visualViewport) w.visualViewport.addEventListener('resize', resize); resize();
  function playbackChanged() {
    updateNow();
    if (route === 'queue' && !panel.hidden && queueSignature !== (w.playQueue || []).map(songKey).join('|')) renderQueue(scroll.scrollTop);
    else markCurrent();
    updateEqAvailability();
    var supported = !!(w.AndroidRuntime && w.AndroidRuntime.isNative && w.AndroidPlayback && w.AndroidPlayback.getState().eqSupported === true);
    if (supported && !nativeEqAvailable) { nativeEqAvailable = true; applyEq(); }
    else if (!supported) nativeEqAvailable = false;
  }
  w.addEventListener('android-playback-state', playbackChanged);
  if (w.audio && w.audio.addEventListener) ['play', 'pause', 'ended'].forEach(function (event) { w.audio.addEventListener(event, function () { updateNow(); markCurrent(); }); });
  w.AndroidMobile = { open: open, close: close, getPreferences: function () { return JSON.parse(JSON.stringify(preferences)); }, isVisualEnabled: function () { return preferences.visual !== false; } };
  d.body.classList.toggle('mobile-visual-off', preferences.visual === false);
  if (w.setParticleLyricsSilently) w.setParticleLyricsSilently(preferences.lyrics !== false);
  applyEq();
  if (w.AndroidPlayback && w.AndroidPlayback.ready) w.AndroidPlayback.ready.then(playbackChanged).catch(function () {});
})(window);
