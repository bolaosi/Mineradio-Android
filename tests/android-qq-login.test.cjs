'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createController, accountOnly } = require('../app/src/main/assets/android-qq-login');
const verified = { saved: true, verified: true, loggedIn: true, userId: '12345', nickname: 'Mock', playbackKeyReady: true };
function setup(open) {
  const states = [], accounts = [];
  const controller = createController({ open, onState: value => states.push(value), onVerified: async value => accounts.push(value) });
  return { controller, states, accounts };
}
test('QQ open or browser success is not accepted as authenticated login', async () => {
  for (const result of [{ ok: true }, { saved: true, loggedIn: true }, { ...verified, verified: false }]) {
    const h = setup(async () => result); await h.controller.start(); assert.equal(h.accounts.length, 0); assert.equal(h.states.at(-1).phase, 'error');
  }
});
test('verified native response publishes only account metadata and requests a refresh', async () => {
  const h = setup(async () => ({ ...verified, cookie: 'TEST_ONLY_NOT_A_REAL_COOKIE', token: 'TEST_ONLY' }));
  const result = await h.controller.start(); assert.equal(h.accounts.length, 1); assert.equal(result.verified, true); assert.equal(h.states.at(-1).phase, 'success');
  assert.equal(Object.hasOwn(h.accounts[0], 'cookie'), false); assert.equal(Object.hasOwn(result, 'token'), false);
});
test('native cancellation leaves account state untouched', async () => {
  const h = setup(async () => ({ cancelled: true })); const result = await h.controller.start(); assert.equal(result.cancelled, true); assert.equal(h.accounts.length, 0); assert.equal(h.states.at(-1).phase, 'cancelled');
});
test('closing modal cancels native request and discards a late verified response', async () => {
  let resolve, signal;
  const h = setup(options => { signal = options.signal; return new Promise(done => resolve = done); });
  const pending = h.controller.start(); h.controller.cancel(); assert.equal(signal.aborted, true); resolve(verified);
  assert.equal((await pending).cancelled, true); assert.equal(h.accounts.length, 0); assert.equal(h.states.some(value => value.phase === 'success'), false);
});
test('official verification failure is shown without claiming saved success and can retry', async () => {
  let attempt = 0;
  const h = setup(async () => { if (++attempt === 1) throw new Error('官方账号验证未通过'); return verified; });
  await h.controller.start(); assert.equal(h.states.at(-1).phase, 'error'); assert.equal(h.accounts.length, 0);
  await h.controller.start(); assert.equal(h.accounts.length, 1); assert.equal(h.states.at(-1).phase, 'success');
});
test('parallel button clicks share the same pending native activity', async () => {
  let resolve, count = 0; const h = setup(() => { count++; return new Promise(done => resolve = done); });
  const first = h.controller.start(), second = h.controller.start(); assert.equal(first, second); assert.equal(count, 1); resolve(verified); await first;
  assert.equal(h.accounts.length, 1); assert.equal(h.controller.isRunning(), false);
});
test('a synchronous native bridge failure releases the operation for retry', async () => {
  let count = 0; const h = setup(() => { if (++count === 1) throw new Error('登录窗口未打开'); return Promise.resolve(verified); });
  await h.controller.start(); assert.equal(h.controller.isRunning(), false); assert.equal(h.accounts.length, 0);
  await h.controller.start(); assert.equal(count, 2); assert.equal(h.accounts.length, 1);
});
