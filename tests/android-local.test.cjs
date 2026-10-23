const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createController, isAudioFile } = require('../app/src/main/assets/android-local.js');

class Media extends EventTarget {
  constructor() { super(); this.src = ''; this.paused = true; this.currentTime = 0; this.duration = 90; this.error = null; this.playCalls = 0; }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
  async play() { this.playCalls++; if (this.playError) throw this.playError; this.paused = false; this.dispatchEvent(new Event('play')); }
  load() { this.currentTime = 0; }
  removeAttribute(name) { if (name === 'src') this.src = ''; }
}
function file(name, type = 'audio/mpeg', data = 'ID3fakeaudio') { return new File([data], name, { type, lastModified: 42 }); }
function setup(options = {}) {
  const media = new Media(), messages = [], revoked = [], before = [], changes = [];
  let counter = 0;
  const controller = createController({ media, createObjectURL: () => 'blob:local/' + ++counter,
    revokeObjectURL: url => revoked.push(url), notify: message => messages.push(message),
    beforePlay: song => before.push(song.name), changed: state => changes.push(state), ...options });
  return { controller, media, messages, revoked, before, changes };
}
test('multi selection retains each track, skips duplicates and non-audio without network', async () => {
  const { controller, media, before } = setup();
  const a = file('A.mp3'), b = file('B.m4a', ''), image = file('cover.png', 'image/png');
  await controller.importFiles([a, b, image, a], true);
  assert.deepEqual(controller.snapshot().queue.map(song => song.name), ['A.mp3', 'B.m4a']);
  assert.equal(controller.snapshot().index, 0);
  assert.equal(media.src, 'blob:local/1'); assert.equal(media.paused, false);
  assert.deepEqual(before, ['A.mp3']);
  assert.equal(isAudioFile(file('recording.AAC', '')), true);
});
test('cancel and inaccessible/empty selections give feedback without replacing queue', async () => {
  const { controller, messages } = setup();
  await controller.importFiles([file('A.mp3')], true);
  await controller.importFiles([], true);
  assert.match(messages.at(-1), /取消/);
  const stale = { name: 'stale.mp3', type: 'audio/mpeg', size: 123, slice() { return { arrayBuffer: async () => { throw new Error('NotReadableError'); } }; } };
  await controller.importFiles([stale, file('empty.mp3', 'audio/mpeg', '')]);
  assert.equal(controller.snapshot().queue.length, 1);
  assert.match(messages.at(-1), /无法读取/);
});
test('pause/resume, bounded seeking, previous/next and ended advance the local queue', async () => {
  const { controller, media } = setup();
  await controller.importFiles([file('A.mp3'), file('B.mp3')], true);
  await controller.toggle(); assert.equal(media.paused, true);
  await controller.toggle(); assert.equal(media.paused, false);
  assert.equal(controller.seek(999), true); assert.equal(media.currentTime, 90);
  controller.seek(-3); assert.equal(media.currentTime, 0);
  await controller.next(1); assert.equal(controller.snapshot().index, 1);
  await controller.next(-1); assert.equal(controller.snapshot().index, 0);
  media.dispatchEvent(new Event('ended'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(controller.snapshot().index, 1);
});
test('removal releases only owned blob URLs and keeps current index accurate', async () => {
  const { controller, media, revoked } = setup();
  await controller.importFiles([file('A.mp3'), file('B.mp3'), file('C.mp3')]);
  await controller.play(1);
  controller.remove(0);
  assert.equal(controller.snapshot().index, 0); assert.equal(media.src, 'blob:local/2');
  await controller.remove(0);
  assert.equal(controller.snapshot().queue[0].name, 'C.mp3'); assert.equal(media.src, 'blob:local/3');
  controller.clear();
  assert.equal(media.src, ''); assert.equal(media.paused, true); assert.equal(controller.snapshot().queue.length, 0);
  assert.deepEqual(revoked, ['blob:local/1', 'blob:local/2', 'blob:local/3']);
});
test('paused current removal stays paused; invalid indices do not change queue', async () => {
  const { controller, media } = setup();
  await controller.importFiles([file('A.mp3'), file('B.mp3')], true);
  await controller.toggle(); controller.remove(0);
  assert.equal(media.paused, true); assert.equal(media.src, '');
  assert.equal(await controller.play(999), false);
  controller.remove(-1); assert.equal(controller.snapshot().queue.length, 1);
  await controller.toggle(); assert.equal(media.src, 'blob:local/2');
});
test('decode/play failures do not report success; online media does not trigger local queue', async () => {
  const { controller, media, messages } = setup();
  await controller.importFiles([file('A.mp3'), file('B.mp3')]);
  media.playError = new DOMException('not supported', 'NotSupportedError');
  assert.equal(await controller.play(0), false);
  assert.equal(media.paused, true); assert.match(messages.at(-1), /无法播放/);
  media.error = { code: 3 }; media.dispatchEvent(new Event('error'));
  assert.match(messages.at(-1), /解码失败/);
  media.src = 'https://backend.example/api/audio';
  assert.equal(controller.isActive(), false); assert.equal(controller.seek(20), false);
  media.dispatchEvent(new Event('ended'));
  assert.equal(controller.snapshot().index, 0);
  controller.clear(); assert.equal(media.src, 'https://backend.example/api/audio');
});
test('a superseded async play does not restart a removed file', async () => {
  let resume;
  const { controller, media } = setup({ resumeAudio: () => new Promise(resolve => { resume = resolve; }) });
  await controller.importFiles([file('A.mp3')]);
  const playing = controller.play(0);
  controller.clear(); resume();
  assert.equal(await playing, false); assert.equal(media.playCalls, 0); assert.equal(media.src, '');
});
test('clear during an asynchronous file read does not resurrect the queue', async () => {
  let read;
  const { controller } = setup();
  const slow = { name: 'slow.mp3', type: 'audio/mpeg', size: 123, slice() { return { arrayBuffer: () => new Promise(resolve => { read = resolve; }) }; } };
  const importing = controller.importFiles([slow], true);
  controller.clear(); read(new ArrayBuffer(16)); await importing;
  assert.equal(controller.snapshot().queue.length, 0);
});
