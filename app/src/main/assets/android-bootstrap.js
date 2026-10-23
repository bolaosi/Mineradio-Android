/* Loaded before the original application: native API transport never races page initialization. */
(function (root) {
  'use strict';
  var originalFetch = root.fetch.bind(root);
  var native = root.MineradioNative;
  var pending = new Map();
  var serial = 0;
  var storageKey = 'mineradio-backend-v1';
  var cached = { baseUrl: '', token: '' };
  var configVersion = 0;

  function normalizeConfig(config) {
    // Native builds route directly to official services; no user supplied host or secret.
    if (native && config.mode === 'builtin') {
      var builtin = { mode: 'builtin', ready: config.ready === true };
      if (config.nativePlayback) builtin.nativePlayback = true;
      return builtin;
    }
    var raw = String(config.baseUrl || '').trim().replace(/\/+$/, '');
    var token = String(config.token || '').trim();
    if (/[\r\n]/.test(token)) throw new Error('访问令牌格式无效');
    if (!raw) return { baseUrl: '', token: token };
    var url;
    try { url = new URL(raw); } catch (_) { throw new Error('请输入完整后端地址'); }
    var privateHost = /^(localhost|127\.0\.0\.1|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$/.test(url.hostname);
    if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && privateHost)) || url.username || url.password ||
        url.search || url.hash || url.pathname !== '/' || url.origin !== raw)
      throw new Error('使用 HTTPS 服务根地址；HTTP 只支持局域网调试，地址中不要包含路径或凭据');
    return { baseUrl: url.origin, token: token };
  }
  function rpc(type, payload, signal) {
    return new Promise(function (resolve, reject) {
      if (signal && signal.aborted) return reject(new DOMException('已取消', 'AbortError'));
      var id = String(++serial), timer;
      function finish(error, value) {
        if (!pending.has(id)) return;
        pending.delete(id); clearTimeout(timer);
        if (signal) signal.removeEventListener('abort', abort);
        if (error) reject(error); else resolve(value);
      }
      function abort() {
        native.postMessage(JSON.stringify({ type: 'cancel', id: String(++serial), requestId: id }));
        finish(new DOMException('已取消', 'AbortError'));
      }
      pending.set(id, finish);
      timer = setTimeout(function () {
        if (type === 'request' || type === 'openQQLogin') {
          try { native.postMessage(JSON.stringify({ type: 'cancel', id: String(++serial), requestId: id })); } catch (_) { }
        }
        finish(new Error('操作超时，请重试'));
      }, ['saveQr', 'pickAudio', 'openQQLogin'].includes(type) ? 300000 : 45000);
      if (signal) signal.addEventListener('abort', abort, { once: true });
      try { native.postMessage(JSON.stringify(Object.assign({}, payload, { type: type, id: id }))); }
      catch (_) { finish(new Error('Android 连接不可用，请重新打开应用')); }
    });
  }
  if (native) native.onmessage = function (event) {
    try {
      var message = JSON.parse(event.data), done = pending.get(message.id);
      if (message.event === 'playback') {
        root.AndroidRuntime.latestPlayback = message.state;
        root.dispatchEvent(new CustomEvent('android-native-playback', { detail: message.state }));
        return;
      }
      if (done) done(message.error ? new Error(message.error) : null, message.result);
    } catch (_) { /* Ignore malformed/unrelated replies. */ }
  };
  var ready = native ? rpc('getConfig', {}).then(function (config) { cached = normalizeConfig(config); return cached; }) :
    Promise.resolve().then(function () {
      try { cached = normalizeConfig(JSON.parse(root.localStorage.getItem(storageKey) || '{}')); } catch (_) { }
      return cached;
    });

  async function request(path, options) {
    options = options || {};
    var url = new URL(path, root.location.origin);
    if (url.origin !== root.location.origin || !url.pathname.startsWith('/api/') || /\.\./.test(decodeURIComponent(path)))
      throw new Error('无效的 API 路径');
    var config = await ready;
    var version = configVersion;
    if (config.mode !== 'builtin' && !config.baseUrl) return new Response(JSON.stringify({ error: 'BACKEND_NOT_CONFIGURED', message: '浏览器预览需要开发后端；安装 Android APK 可直接使用在线功能' }),
      { status: 503, headers: { 'Content-Type': 'application/json' } });
    var method = String(options.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'POST') throw new Error('不支持的 API 操作');
    if (native) {
      var response = await rpc('request', { path: url.pathname + url.search, backend: config.baseUrl, method: method, body: options.body || '' }, options.signal);
      if (version !== configVersion) throw new DOMException('后端设置已更改', 'AbortError');
      if (response.status >= 300 && response.status < 400) throw new Error('在线服务返回了无效重定向，请重试');
      return new Response(response.body, { status: response.status, headers: { 'Content-Type': 'application/json' } });
    }
    var headers = new Headers(options.headers || {});
    headers.set('Authorization', 'Bearer ' + config.token);
    var response = await originalFetch(config.baseUrl + url.pathname + url.search, Object.assign({}, options, { method: method, headers: headers, redirect: 'error', credentials: 'omit' }));
    if (version !== configVersion) throw new DOMException('后端设置已更改', 'AbortError');
    return response;
  }
  root.AndroidRuntime = {
    isNative: !!native,
    ready: ready,
    getConfig: async function () { await ready; return Object.assign({}, cached); },
    saveConfig: async function (config) {
      await ready;
      if (cached.mode === 'builtin') throw new Error('Android 已内置在线接口，无需配置后端');
      var normalized = normalizeConfig(config);
      var changed = cached.baseUrl !== normalized.baseUrl || cached.token !== normalized.token;
      if (native) normalized = await rpc('saveConfig', { config: normalized });
      else root.localStorage.setItem(storageKey, JSON.stringify(normalized));
      cached = normalized; ready = Promise.resolve(cached);
      if (changed) configVersion++;
      root.dispatchEvent(new Event('mineradio-backend-changed'));
      return Object.assign({}, cached);
    },
    request: request,
    playback: async function (command) {
      var config = await ready;
      if (!native || !config.nativePlayback) throw new Error('当前预览没有原生后台播放器');
      return rpc('playback', { command: command });
    },
    pickAudio: function () { return rpc('pickAudio', {}); },
    openQQLogin: function (options) { return rpc('openQQLogin', {}, options && options.signal); },
    saveQr: function (dataUri) {
      if (native) return rpc('saveQr', { dataUri: dataUri });
      var anchor = document.createElement('a'); anchor.href = dataUri;
      anchor.download = 'Mineradio-网易云登录二维码.png'; anchor.click();
      return Promise.resolve({ saved: true });
    }
  };
  root.fetch = function (input, options) {
    var raw = typeof input === 'string' ? input : (input instanceof URL ? input.href : input.url);
    var url = new URL(raw, root.location.href);
    if (url.origin === root.location.origin && url.pathname.startsWith('/api/')) {
      if (typeof Request !== 'undefined' && input instanceof Request && !options) {
        return input.clone().text().then(function (body) { return request(url.href, { method: input.method, headers: input.headers, body: body, signal: input.signal }); });
      }
      return request(url.href, options);
    }
    return originalFetch(input, options);
  };
  // No desktop login stubs: Android uses actual QR/API flows and explicit Cookie import.
  if (typeof module !== 'undefined') module.exports = { normalizeConfig: normalizeConfig };
})(window);
