/* Android online features. QR state machine is also exported for offline tests. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else {
    var lib = factory();
    root.MineradioOnline = lib;
    if (root.AndroidRuntime) lib.mount(root);
  }
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  function createQrController(options) {
    var epoch = 0, timer = null, key = '', aborter = null, state = { phase: 'idle' };
    var schedule = options.setTimeout || setTimeout, unschedule = options.clearTimeout || clearTimeout;
    function emit(phase, extra) {
      state = Object.assign({ phase: phase }, extra || {});
      options.onState(state);
    }
    function cancel() {
      epoch++;
      if (aborter) aborter.abort();
      aborter = null;
      if (timer !== null) unschedule(timer);
      timer = null; key = '';
    }
    function query(path) { return options.request(path, aborter ? { signal: aborter.signal } : {}); }
    function later(run) { timer = schedule(function () { timer = null; poll(run); }, options.interval || 2000); }
    async function poll(run) {
      if (run !== epoch || !key) return;
      try {
        var result = await query('/api/login/qr/check?key=' + encodeURIComponent(key));
        if (run !== epoch) return;
        var code = Number(result.code);
        if (code === 800) { emit('expired', { message: '二维码已过期，请点刷新二维码' }); return; }
        if (code === 801 || code === 802) {
          emit(code === 801 ? 'waiting' : 'scanned', { message: code === 801 ? '等待网易云 App 扫码' : '已扫码，请在网易云 App 确认' });
          later(run); return;
        }
        if (code === 803) {
          emit('verifying', { message: '扫码已确认，正在验证账号…' });
          if (!result.saved || !result.verified || !result.loggedIn) throw new Error(result.message || '已确认扫码，但账号凭据尚未验证；请刷新重试');
          var account = await query('/api/login/status?t=' + Date.now());
          if (run !== epoch) return;
          if (!account.loggedIn || !account.verified || !account.userId) throw new Error('账号验证未通过，未标记为登录成功');
          emit('success', { message: '官方账号验证通过，正在刷新歌单' });
          options.onVerified(account); return;
        }
        throw new Error(result.message || result.error || '官方二维码接口返回未知状态，请刷新重试');
      } catch (error) {
        if (run === epoch) emit('error', { message: error.message || '二维码查询失败，请刷新重试' });
      }
    }
    async function start() {
      cancel(); var run = epoch;
      if (typeof AbortController !== 'undefined') aborter = new AbortController();
      emit('loading', { message: '正在获取网易云二维码…' });
      try {
        var reply = await query('/api/login/qr/key');
        if (run !== epoch) return;
        if (!reply.key) throw new Error(reply.message || reply.error || '未取得二维码 key');
        key = reply.key;
        var image = await query('/api/login/qr/create?key=' + encodeURIComponent(key));
        if (run !== epoch) return;
        if (!image.img && image.url && options.renderQr) {
          var qrUrl = new URL(image.url);
          if (qrUrl.origin !== 'https://music.163.com' || qrUrl.pathname !== '/login' || qrUrl.searchParams.get('codekey') !== key)
            throw new Error('官方二维码地址无效，请重试');
          image.img = await options.renderQr(image.url);
          if (run !== epoch) return;
        }
        if (!/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(image.img || '')) throw new Error('未能生成有效 PNG 二维码，请重试');
        emit('waiting', { image: image.img, message: '请用网易云 App 扫码；同屏请保存二维码后从相册识别' });
        later(run);
      } catch (error) {
        if (run === epoch) emit('error', { message: error.message || '获取二维码失败，请刷新重试' });
      }
    }
    return { start: start, cancel: cancel, getState: function () { return state; } };
  }

  function mount(w) {
    var d = w.document, qrImage = '', cookieBusy = false, interaction = 0;
    function el(id) { return d.getElementById(id); }
    function status(message, fail) { var s = el('qr-status'); if (s) { s.textContent = message; s.className = fail ? 'fail' : 'preview'; } }
    function toast(message) { if (w.showToast) w.showToast(message); }
    function invoke(name) { if (typeof w[name] === 'function') return w[name].apply(w, Array.prototype.slice.call(arguments, 1)); }
    async function configured() { var c = await w.AndroidRuntime.getConfig(); return !!(c && (c.mode === 'builtin' && c.ready || c.baseUrl && c.token)); }
    function clearQrImage() {
      qrImage = '';
      var image = el('qr-img');
      image.onload = null; image.onerror = null; image.removeAttribute('src');
      image.style.display = 'none'; el('qr-shell').style.display = 'none';
      var save = el('android-save-qr');
      if (save) { save.disabled = true; save.style.display = 'none'; }
    }
    function resetAccounts() {
      w.loginStatus = { loggedIn: false, verified: false };
      w.qqLoginStatus = { provider: 'qq', loggedIn: false, verified: false };
      w.userPlaylists = []; w.qqPlaylists = []; w.myPodcastCollections = []; w.myPodcastItems = {}; w.likedSongMap = {};
      if (w.homeDiscoverState) { w.homeDiscoverState.loaded = false; w.homeDiscoverState.loggedIn = false; }
      invoke('renderUserBtn'); invoke('updateLikeButtons');
    }
    function acceptAccount(provider, info) {
      if (!info || !info.loggedIn || !info.verified || !info.userId) throw new Error('官方账号验证未通过');
      if (provider === 'qq') w.qqLoginStatus = info; else w.loginStatus = info;
      w.activeAccountProvider = provider;
      invoke('renderUserBtn'); invoke('refreshUserPlaylists', true); invoke('loadHomeDiscover', true);
      status(provider === 'qq' && !info.playbackKeyReady ? 'QQ 账号已验证，但当前凭据缺少播放票据；部分歌曲仍不可播放' : '账号已验证，歌单正在刷新');
      toast(provider === 'qq' ? 'QQ 音乐账号已验证' : '网易云账号已验证');
    }
    var controller = createQrController({
      request: function (path, options) { return w.apiJson(path, Object.assign({ timeoutMs: 25000 }, options)); },
      renderQr: function (url) { return w.MineradioQr.toDataURL(url); },
      onState: function (s) {
        if (s.phase === 'loading' || s.phase === 'error' || s.phase === 'expired') clearQrImage();
        if (s.image) {
          qrImage = s.image;
          var image = el('qr-img');
          image.onload = function () {
            if (qrImage !== s.image || w.loginProvider === 'qq') return;
            image.style.display = ''; el('qr-shell').style.display = '';
            var save = el('android-save-qr'); save.disabled = false; save.style.display = '';
          };
          image.onerror = function () {
            if (qrImage !== s.image) return;
            controller.cancel(); clearQrImage();
            status('二维码图片无法显示，请刷新重试', true);
          };
          image.src = s.image;
        }
        status(s.message || '', s.phase === 'error' || s.phase === 'expired');
      },
      onVerified: function (info) { acceptAccount('netease', info); }
    });
    function cancelLogin() {
      interaction++; controller.cancel();
      if (typeof w.cancelQQAutomaticLogin === 'function') w.cancelQQAutomaticLogin();
      var automatic = el('android-qq-auto-login'); if (automatic) automatic.disabled = false;
    }
    w.stopQrPoll = cancelLogin;
    w.startQrPoll = function () {};
    w.checkQr = function () {};
    var originalClose = w.closeLoginModal;
    w.closeLoginModal = function () {
      cancelLogin(); clearQrImage();
      if (el('qq-cookie-input')) el('qq-cookie-input').value = '';
      originalClose();
    };
    w.updateLoginProviderUi = function () {
      var qq = w.loginProvider === 'qq';
      el('login-provider-netease').classList.toggle('active', !qq); el('login-provider-qq').classList.toggle('active', qq);
      el('login-modal-title').textContent = qq ? 'QQ 音乐 Cookie 导入' : '网易云二维码登录';
      if (qq && w.AndroidRuntime.isNative) el('login-modal-title').textContent = 'QQ 音乐登录';
      el('login-modal-desc').textContent = qq
        ? '自动网页/扫码回传尚未实现。请在本人浏览器登录 y.qq.com，复制该站完整 Cookie 后导入；Android 只将凭据保存在本机并提交给 QQ 官方接口验证。'
        : '使用网易云音乐 App 扫码。只有官方账号验证通过才显示已登录；歌单和播放权限另行查询。';
      if (qq && w.AndroidRuntime.isNative) el('login-modal-desc').textContent = '打开 QQ 官方登录页面，按官网选择网页登录或扫码并完成授权。取得会话后仍须官方账号验证；Cookie 导入保留为备用。';
      el('qr-shell').classList.remove('web-login-preview', 'qq-preview', 'netease-preview');
      el('qr-shell').style.display = qq || !qrImage ? 'none' : '';
      el('qq-web-login-card').style.display = 'none';
      el('qq-cookie-panel').classList.toggle('show', qq);
      el('qq-cookie-toggle-btn').style.display = 'none';
      el('qq-cookie-save-btn').textContent = '验证并保存';
      el('qq-cookie-input').placeholder = '粘贴 y.qq.com 完整 Cookie；不要只填 QQ 号或自行拼造票据';
      el('refresh-qr-btn').textContent = qq ? '重新验证 QQ 账号' : '刷新二维码';
      el('refresh-qr-btn').onclick = qq ? async function () {
        var info = await w.refreshQQLoginStatus();
        status(info && info.loggedIn && info.verified ? 'QQ 官方账号验证通过' : '尚无已验证 QQ 会话，请导入完整 Cookie', !(info && info.loggedIn));
      } : w.refreshQr;
      el('android-save-qr').style.display = qq || !qrImage ? 'none' : '';
      var automatic = el('android-qq-auto-login');
      if (automatic) automatic.style.display = qq ? '' : 'none';
      var note = d.querySelector('.qq-cookie-note');
      if (note) note.textContent = '支持现有 uin/qqmusic_uin/wxuin 与 qm_keyst/qqmusic_key 等字段；完整 Cookie 通常还包含其它官方校验票据。';
    };
    w.refreshQr = async function () {
      cancelLogin(); clearQrImage(); w.updateLoginProviderUi();
      var run = interaction, available = await configured();
      if (run !== interaction) return;
      if (!available) {
        var qq = w.loginProvider === 'qq';
        el('login-modal-desc').textContent = qq
          ? '自动网页/扫码回传尚未实现。此 APK 没有内置在线后端，先连接本人控制的服务，再导入 QQ 音乐 Cookie；本地音乐不受影响。'
          : '此 APK 没有内置在线后端。连接本人控制的服务后，才能向网易云申请登录二维码；本地音乐不受影响。';
        status(qq ? '尚未配置在线服务，暂时无法验证 QQ 音乐 Cookie。' : '尚未配置在线服务，因此还没有生成二维码。', true);
        el('refresh-qr-btn').textContent = qq ? '设置在线服务' : '设置在线服务并获取二维码';
        el('refresh-qr-btn').onclick = function () { return w.AndroidOnlineSettings.open(); };
        return;
      }
      if (w.loginProvider === 'qq') { status(w.AndroidRuntime.isNative ? '可打开 QQ 官方登录并完成授权；若官网受限，也可导入本人完整 Cookie 验证' : '粘贴本人 QQ 音乐完整 Cookie，点“验证并保存”'); return; }
      await controller.start();
    };
    w.openQQWebLogin = function () { w.setLoginProvider('qq'); };
    w.openNeteaseWebLogin = w.refreshQr;
    w.openProviderWebLogin = function () { return w.loginProvider === 'qq' ? w.openQQWebLogin() : w.refreshQr(); };
    w.submitQQCookieLogin = async function () {
      if (cookieBusy) return;
      var input = el('qq-cookie-input'), cookie = input.value.trim();
      if (!cookie) { status('请先粘贴本人 QQ 音乐完整 Cookie', true); return; }
      var run = interaction;
      cookieBusy = true; el('qq-cookie-save-btn').disabled = true; status('正在向官方接口验证 QQ 凭据…');
      try {
        var info = await w.apiJson('/api/qq/login/cookie', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cookie: cookie }), timeoutMs: 25000 });
        if (run !== interaction || w.loginProvider !== 'qq') return;
        if (!info.saved) throw new Error(info.message || '凭据验证未通过，未保存');
        acceptAccount('qq', info);
        input.value = '';
      } catch (error) { if (run === interaction) status(error.message || 'Cookie 验证失败', true); }
      finally { cookieBusy = false; el('qq-cookie-save-btn').disabled = false; cookie = ''; }
    };
    ['refreshLoginStatus', 'refreshQQLoginStatus'].forEach(function (name) {
      var original = w[name];
      w[name] = async function () {
        if (!(await configured())) return { loggedIn: false, verified: false };
        var provider = name === 'refreshQQLoginStatus' ? 'qq' : 'netease';
        var previous = provider === 'qq' ? w.qqLoginStatus : w.loginStatus;
        var info = await original.apply(w, arguments);
        if (info && info.loggedIn && info.verified) return info;
        var unverified = Object.assign({}, info || {}, { provider: provider, loggedIn: false, verified: false,
          hasCookie: !!(info && info.hasCookie || previous && previous.hasCookie),
          credentialState: info && info.credentialState || 'unverified' });
        if (provider === 'qq') { w.qqLoginStatus = unverified; w.qqPlaylists = []; }
        else { w.loginStatus = unverified; w.myPodcastCollections = []; w.myPodcastItems = {}; w.likedSongMap = {}; }
        w.userPlaylists = (w.userPlaylists || []).filter(function (pl) { return pl.provider !== provider && !(provider === 'netease' && !pl.provider); });
        invoke('renderUserBtn');
        if (previous && previous.loggedIn && (!info || !info.credentialState || info.credentialState === 'unverified')) toast('当前无法验证' + (provider === 'qq' ? ' QQ 音乐' : '网易云') + '账号，请检查网络后重试');
        return unverified;
      };
    });
    var originalSetProvider = w.setLoginProvider;
    w.setLoginProvider = function () { cancelLogin(); return originalSetProvider.apply(w, arguments); };
    var saveQr = d.createElement('button'); saveQr.id = 'android-save-qr'; saveQr.className = 'modal-btn'; saveQr.textContent = '保存二维码 PNG'; saveQr.disabled = true;
    saveQr.onclick = async function () {
      try {
        if (!qrImage) throw new Error('先刷新二维码');
        var result = await w.AndroidRuntime.saveQr(qrImage);
        status(result && result.cancelled ? '已取消保存；仍可用另一台设备扫码' : '已保存 PNG。请在网易云 App 的扫一扫中选相册识别；若该机型不支持，使用另一台设备扫码。');
      } catch (error) { status(error.message || '二维码保存失败，可使用另一台设备扫码', true); }
    };
    el('qr-status').insertAdjacentElement('afterend', saveQr);
    if (w.AndroidRuntime.isNative) {
      var automatic = d.createElement('button'); automatic.id = 'android-qq-auto-login'; automatic.className = 'modal-btn'; automatic.textContent = '打开 QQ 官方登录'; automatic.style.display = 'none';
      automatic.onclick = function () {
        if (typeof w.openQQAutomaticLogin === 'function') return w.openQQAutomaticLogin();
        status('QQ 登录窗口尚未就绪，请重新打开 App；也可使用 Cookie 导入', true);
      };
      saveQr.insertAdjacentElement('afterend', automatic);
    }
    clearQrImage();
    mountSettings(w, { cancel: cancelLogin }, resetAccounts, configured);
    w.addEventListener('pagehide', cancelLogin);
  }
  function mountSettings(w, controller, resetAccounts, configured) {
    var d = w.document;
    var style = d.createElement('style');
    style.textContent = '#android-online-button{position:fixed;right:14px;bottom:14px;z-index:8500;padding:12px}#android-online-settings{position:fixed;inset:0;z-index:100000;background:#000c;display:none;align-items:center;justify-content:center;padding:16px}#android-online-settings .online-card{box-sizing:border-box;max-width:480px;width:100%;max-height:88vh;overflow:auto;background:#17231e;color:#fff;border:1px solid #819378;border-radius:16px;padding:20px;font:14px/1.6 sans-serif}#android-online-settings input{box-sizing:border-box;width:100%;padding:12px;margin:6px 0 14px;border:1px solid #819378;border-radius:8px;background:#101b15;color:#fff;font:16px sans-serif}#android-online-settings button{padding:10px 14px;margin:6px 6px 0 0;border-radius:8px;border:1px solid #9baf8e;background:#293a2c;color:#fff}#online-status{overflow-wrap:anywhere;white-space:pre-wrap}#android-save-qr{margin:0 0 12px}';
    d.head.appendChild(style);
    if (w.AndroidRuntime.isNative) {
      var nativeButton = d.createElement('button'); nativeButton.id = 'android-online-button'; nativeButton.className = 'android-glass-action'; nativeButton.textContent = '在线状态'; d.body.appendChild(nativeButton);
      var nativeDialog = d.createElement('div'); nativeDialog.id = 'android-online-settings'; nativeDialog.setAttribute('role', 'dialog'); nativeDialog.setAttribute('aria-label', '内置在线接口状态');
      nativeDialog.innerHTML = '<div class="online-card"><h2>在线接口已内置</h2><p>安装后直接使用，无需后端地址、令牌或电脑。Android 直接连接网易云和 QQ 官方接口，账号凭据只保存在本 App 私有目录。</p><p>网易云：点登录获取二维码，保存 PNG 后在网易云扫一扫中从相册识别，或用另一台设备扫码。QQ：可打开隔离的官方登录窗口完成授权；受限时可用完整 Cookie 导入备用。</p><p>原生服务负责息屏播放与连续切歌。本地文件取得持久权限时可重启恢复；文件失效或未获持久授权时需重选。在线歌曲仍受账号权限、地区和版权限制。</p><button id="online-test">检测网易连接</button><button id="online-close">返回</button><p id="online-status" aria-live="polite"></p></div>';
      d.body.appendChild(nativeDialog);
      var nativeOutput = d.getElementById('online-status');
      async function openNative() { controller.cancel(); nativeOutput.textContent = ''; nativeDialog.style.display = 'flex'; }
      nativeButton.onclick = openNative;
      d.getElementById('online-close').onclick = function () { nativeDialog.style.display = 'none'; if (d.getElementById('login-modal').classList.contains('show') && w.loginProvider !== 'qq') w.refreshQr(); };
      d.getElementById('online-test').onclick = async function () {
        var button = this; button.disabled = true; nativeOutput.textContent = '正在连接网易云官方接口…';
        try {
          var reply = await w.apiJson('/api/login/qr/key', { timeoutMs: 25000 });
          if (!reply.key) throw new Error(reply.message || '官方接口没有返回二维码 key');
          nativeOutput.textContent = '网易云官方二维码接口可达。账号登录与歌曲播放权限需另行验证。';
        } catch (error) { nativeOutput.textContent = '连接失败：' + error.message; }
        finally { button.disabled = false; }
      };
      w.AndroidOnlineSettings = { open: openNative };
      return;
    }
    var button = d.createElement('button'); button.id = 'android-online-button'; button.className = 'android-glass-action'; button.textContent = '在线设置'; d.body.appendChild(button);
    var dialog = d.createElement('div'); dialog.id = 'android-online-settings'; dialog.setAttribute('role', 'dialog'); dialog.setAttribute('aria-modal', 'true'); dialog.setAttribute('aria-label', '在线后端设置');
    dialog.innerHTML = '<div class="online-card"><h2>本人在线后端</h2><p>本地音乐无需后端。在线功能需自行部署本项目 Node 服务；每个后端仅供一人使用，账号凭据保存在后端。</p><p>还没有服务：可在本人电脑运行随项目提供的 Node 后端，手机与电脑连接同一 Wi-Fi，再填写电脑的局域网地址。不要填写网易云官网或手机自己的 localhost。</p><label for="online-base">后端地址（HTTPS；本机/局域网可用 HTTP）</label><input id="online-base" type="url" autocomplete="off" spellcheck="false" placeholder="https://你的后端域名"><label for="online-token">后端访问令牌</label><input id="online-token" type="password" autocomplete="off" spellcheck="false" placeholder="MINERADIO_API_TOKEN"><div><button id="online-save">保存</button><button id="online-test">检测连接</button><button id="online-close">返回</button></div><p id="online-status" aria-live="polite"></p><p>QQ 自动登录尚未完成，可导入本人完整 Cookie；网易云提供二维码登录。未配置可用服务或未用真实账号验证时，不代表在线功能已可用。</p></div>';
    d.body.appendChild(dialog);
    var base = d.getElementById('online-base'), token = d.getElementById('online-token'), output = d.getElementById('online-status');
    async function open() { controller.cancel(); var c = await w.AndroidRuntime.getConfig(); base.value = c.baseUrl || ''; token.value = c.token || ''; output.textContent = ''; dialog.style.display = 'flex'; }
    async function save() {
      var before = await w.AndroidRuntime.getConfig();
      var next = await w.AndroidRuntime.saveConfig({ baseUrl: base.value.trim(), token: token.value.trim() });
      if (before.baseUrl !== next.baseUrl || before.token !== next.token) { controller.cancel(); resetAccounts(); }
      output.textContent = '设置已保存；连接状态需点击检测确认。'; return next;
    }
    button.onclick = function () { open().catch(function (e) { output.textContent = e.message; }); };
    d.getElementById('online-close').onclick = function () { dialog.style.display = 'none'; token.value = ''; };
    d.getElementById('online-save').onclick = async function () { try { await save(); } catch (e) { output.textContent = e.message; } };
    d.getElementById('online-test').onclick = async function () {
      try {
        await save(); if (!(await configured())) throw new Error('请先填写后端地址和令牌');
        output.textContent = '正在检测…';
        var reply = await w.apiJson('/api/health', { timeoutMs: 15000 });
        if (!reply.ok || reply.service !== 'mineradio' || reply.apiVersion !== 1 || reply.mode !== 'single-user' || !reply.authRequired) throw new Error('地址不是本修复版本的受保护单用户后端');
        output.textContent = '连接通过（已认证单用户后端）。可返回后点登录；第三方账号仍需单独验证。';
        await Promise.all([w.refreshLoginStatus(true), w.refreshQQLoginStatus()]);
        if (d.getElementById('login-modal').classList.contains('show') && w.loginProvider !== 'qq') {
          dialog.style.display = 'none'; token.value = '';
          await w.refreshQr();
        }
      } catch (e) { output.textContent = '连接失败：' + e.message; }
    };
    w.AndroidOnlineSettings = { open: open };
  }
  return { createQrController: createQrController, mount: mount };
});
