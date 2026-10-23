/* QQ automatic login adds no capability to provider webpages; only trusted app UI uses this RPC. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else { var api = factory(); root.MineradioQqLogin = api; if (root.AndroidRuntime) api.mount(root); }
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  function accountOnly(result) {
    if (!result || !result.saved || !result.verified || !result.loggedIn || !result.userId) throw new Error('QQ 会话未通过官方账号验证，未报告登录成功');
    var account = { provider: 'qq', saved: true, verified: true, loggedIn: true, userId: String(result.userId) };
    ['nickname', 'avatar', 'vipType', 'vipLevel', 'isVip', 'isSvip', 'playbackKeyReady', 'profileSource', 'credentialState'].forEach(function (key) { if (Object.prototype.hasOwnProperty.call(result, key)) account[key] = result[key]; });
    return account;
  }
  function createController(options) {
    var generation = 0, aborter = null, running = null;
    function cancel() { generation++; if (aborter) aborter.abort(); aborter = null; running = null; }
    function start() {
      if (running) return running;
      var run = ++generation; aborter = new AbortController();
      options.onState({ phase: 'opening', message: '正在打开隔离的 QQ 官方登录窗口；完成官网授权后才会验证账号' });
      var task = (async function () {
        try {
          var result = await options.open({ signal: aborter.signal });
          if (run !== generation) return { cancelled: true };
          if (result && result.cancelled) { options.onState({ phase: 'cancelled', message: '已取消 QQ 登录，原有账号状态不变' }); return result; }
          var account = accountOnly(result);
          await options.onVerified(account);
          if (run !== generation) return { cancelled: true };
          options.onState({ phase: 'success', message: account.playbackKeyReady ? 'QQ 官方账号已验证，已请求刷新歌单' : 'QQ 账号已验证，但播放授权不完整；部分歌曲仍可能不可播放' });
          return account;
        } catch (error) {
          if (run !== generation || error && error.name === 'AbortError') return { cancelled: true };
          options.onState({ phase: 'error', message: error.message || 'QQ 登录尚未完成，可重试或使用 Cookie 导入备用方式' });
          return { verified: false, error: true };
        }
      })();
      running = task;
      function settled() { if (running === task) { running = null; aborter = null; } }
      task.then(settled, settled);
      return task;
    }
    return { start: start, cancel: cancel, isRunning: function () { return !!running; } };
  }
  function mount(w) {
    function state(value) {
      if (w.loginProvider !== 'qq') return;
      var label = w.document.getElementById('qr-status');
      if (label) { label.textContent = value.message; label.className = value.phase === 'error' ? 'fail' : 'preview'; }
      var button = w.document.getElementById('android-qq-auto-login');
      if (button) button.disabled = value.phase === 'opening';
    }
    var controller = createController({
      open: function (options) {
        if (typeof w.AndroidRuntime.openQQLogin !== 'function') return Promise.reject(new Error('当前 APK 没有自动 QQ 登录窗口，请使用 Cookie 导入'));
        return w.AndroidRuntime.openQQLogin(options);
      },
      onState: state,
      onVerified: async function (account) {
        w.qqLoginStatus = account; w.activeAccountProvider = 'qq';
        if (typeof w.renderUserBtn === 'function') w.renderUserBtn();
        if (typeof w.refreshQQLoginStatus === 'function') {
          var fresh = await w.refreshQQLoginStatus();
          if (!fresh || !fresh.verified || !fresh.loggedIn) throw new Error('会话已交接，但当前网络无法复核 QQ 账号，请重试；未声称歌单同步成功');
        }
        if (typeof w.refreshUserPlaylists === 'function') await w.refreshUserPlaylists(true);
      }
    });
    w.openQQAutomaticLogin = controller.start;
    w.cancelQQAutomaticLogin = controller.cancel;
    var modal = w.document.getElementById('login-modal');
    if (modal && typeof w.MutationObserver === 'function') {
      var observer = new w.MutationObserver(function () {
        if (controller.isRunning() && (!modal.classList.contains('show') || w.loginProvider !== 'qq')) {
          controller.cancel(); var button = w.document.getElementById('android-qq-auto-login'); if (button) button.disabled = false;
        }
      });
      observer.observe(modal, { attributes: true, subtree: true, attributeFilter: ['class', 'style'] });
    }
    w.addEventListener('pagehide', controller.cancel);
  }
  return { createController: createController, accountOnly: accountOnly, mount: mount };
});
