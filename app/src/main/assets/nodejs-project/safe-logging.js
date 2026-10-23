'use strict';
const installed = new WeakSet();
// NeteaseCloudMusicApi 4.32.0 logs its rejected `answer` (including Set-Cookie)
// under [ERR] inside util/request.js. Filter that known dependency diagnostic
// before it reaches stdout/stderr; never change authentication/API results.
function installNeteaseLogFilter(logger = console) {
  if (installed.has(logger)) return;
  installed.add(logger);
  for (const name of ['log', 'warn', 'error']) {
    const original = logger[name].bind(logger);
    logger[name] = function (...args) {
      if (args[0] === '[ERR]' || args[0] === '代理URL解析失败:') {
        original('[Netease] upstream request rejected (sensitive details omitted)'); return;
      }
      original(...args);
    };
  }
}
module.exports = { installNeteaseLogFilter };
