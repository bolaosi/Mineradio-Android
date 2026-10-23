const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createQrController } = require('../app/src/main/assets/android-online.js');
const png = 'data:image/png;base64,aGVsbG8='; // State-machine stub; actual PNG round-trip has its own browser/decoder test.

function harness(overrides = {}) {
  const states = [], scheduled = [], calls = [];
  const c = createQrController({
    request: async path => { calls.push(path); if (path.endsWith('/key')) return { key:'test-key' };
      if (path.includes('/create')) return { url:'https://music.163.com/login?codekey=test-key' };
      return { code: 801 }; },
    renderQr: async url => { assert.equal(url,'https://music.163.com/login?codekey=test-key'); return png; },
    onState: state => states.push(state), onVerified: () => assert.fail('unverified account accepted'),
    setTimeout: callback => { scheduled.push(callback); return scheduled.length; }, clearTimeout: () => {}, ...overrides
  });
  return { c, states, scheduled, calls };
}
test('built-in QR URL is rendered locally before polling', async () => {
  const h = harness(); await h.c.start();
  assert.equal(h.states.at(-1).image,png); assert.equal(h.scheduled.length,1);
  await h.scheduled.shift()(); assert.equal(h.c.getState().phase,'waiting');
});
test('malformed or unrelated QR destinations never render and never poll', async () => {
  for (const url of ['https://evil.example/login?codekey=test-key', 'https://music.163.com/login?codekey=other', 'javascript:alert(1)']) {
    let rendered = false;
    const h = harness({ request:async path => path.endsWith('/key') ? { key:'test-key' } : { url }, renderQr:async () => { rendered = true; return png; } });
    await h.c.start(); assert.equal(h.c.getState().phase,'error'); assert.equal(rendered,false); assert.equal(h.scheduled.length,0);
  }
});
test('close during PNG generation discards completion and schedules no checks', async () => {
  let complete;
  const h = harness({ renderQr: () => new Promise(resolve => { complete = resolve; }) });
  const run = h.c.start(); await new Promise(resolve => setImmediate(resolve));
  h.c.cancel(); complete(png); await run;
  assert.equal(h.scheduled.length,0); assert.equal(h.states.some(s=>s.image),false);
});
