const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm'), fs = require('node:fs'), path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function context() {
  const ctx = vm.createContext({ Date, audio: { duration: 180, currentTime: 2, paused: false },
    activeRadioContext: null, listenSession: null, currentSong: { id: 'new' },
    currentCoverSong() { return this.currentSong; }, queueItemKey: song => song.id,
    listenSongSnapshot: song => ({ key: song.id }), saveListenStatsState() {}, emptyHomeActive: false,
    listenStatsState: { history: [], songs: {}, artists: {} } });
  // Host callbacks receive no global this in strict VM calls; capture the context explicitly.
  ctx.currentCoverSong = () => ctx.currentSong;
  for (const name of ['beginListenSession', 'updateListenStatsTick', 'finalizeListenSession']) {
    const match = html.match(new RegExp('function ' + name + '\\([^]*?\\n\\}'));
    assert.ok(match, name + ' is present'); vm.runInContext(match[0], ctx);
  }
  return ctx;
}
test('replacing a queue before begin does not recurse through finalization', () => {
  const ctx = context(); ctx.beginListenSession({ id: 'old' }, null);
  ctx.beginListenSession({ id: 'new' }, null);
  assert.equal(ctx.listenSession.key, 'new'); assert.equal(ctx.listenSession.listenMs, 0);
});
test('tick can transition from a stale previous song without stack overflow', () => {
  const ctx = context(); ctx.beginListenSession({ id: 'old' }, null);
  ctx.updateListenStatsTick(false);
  assert.equal(ctx.listenSession.key, 'new');
});
test('finalize after selection changes never creates a new session or attributes its progress to old song', () => {
  const ctx = context(); ctx.beginListenSession({ id: 'old' }, null);
  ctx.finalizeListenSession(false);
  assert.equal(ctx.listenSession, null); assert.equal(ctx.listenStatsState.history.length, 0);
});
