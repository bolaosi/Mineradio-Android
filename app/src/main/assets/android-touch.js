/* Own gestures only on the scene canvas. DOM lists keep the browser's native panning. */
(function (w) {
  'use strict';
  var d = w.document;
  if (!d.documentElement.classList.contains('android-mobile') || !w.renderer) return;
  var canvas = w.renderer.domElement, gesture = null, cancelledClick = false;
  var domGesture = null, domClick = null;
  var scrollSelector = '#mobile-scroll,#playlist-panel,#mini-queue-list,#android-local-panel,#fx-panel,#search-results,.detail-scroll';
  // This is deliberately NOT set on body, panels or their ancestors.
  canvas.style.touchAction = 'none';
  function managerAt(e) {
    var sm = w.shelfManager;
    if (!sm || sm.getMode() === 'off' || w.isPointerOverUi(e)) return null;
    var rc = w.raycasterFromPointerEvent(e);
    if (sm.hasOpenContent()) {
      var cl = sm.getContentList();
      if (cl && (cl.raycastRows(rc) || cl.pickRowAtScreen(e.clientX, e.clientY) ||
          cl.raycastPanel(rc) || cl.screenContainsPanel(e.clientX, e.clientY))) return { manager: cl, axis: 'y', step: 64 };
      // Do not drag the cards through an open detail list.
      return null;
    }
    if (!sm.canInteract()) return null;
    if (!w.pointerCardHit(rc, e, 18)) return null;
    return { manager: sm, axis: sm.getMode() === 'stage' ? 'x' : 'y', step: 88 };
  }
  function finish(e, cancelled) {
    if (!gesture || e && e.pointerId != null && gesture.id !== e.pointerId) return;
    var old = gesture; gesture = null;
    if (old.dragged || cancelled) cancelledClick = true;
    if (old.owner && old.dragged) {
      var target = old.owner.manager.getScrollTarget();
      old.owner.manager.scrollBy(Math.round(target) - target);
    }
    w.orbit.rotating = false; w.particlePointerSpin.active = false;
    w.idleGuidePointerUp();
    w.mouseDownAt.hadDrag = old.dragged || !!cancelled;
    if (canvas.hasPointerCapture && canvas.hasPointerCapture(old.id)) canvas.releasePointerCapture(old.id);
  }
  canvas.addEventListener('pointerdown', function (e) {
    if (e.pointerType === 'mouse' || e.button !== 0 || d.body.classList.contains('splash-active') || w.isPointerOverUi(e)) return;
    if (gesture) { finish(null, true); return; } // Cancel pinch/multiple fingers; never turn them into a tap.
    if (!e.isPrimary) return;
    var owner = managerAt(e);
    gesture = { id: e.pointerId, x: e.clientX, y: e.clientY, dragged: false, owner: owner,
      start: owner ? owner.manager.getScrollTarget() : 0 };
    cancelledClick = false; w.mouseDownAt.hadDrag = false;
    if (!owner) w.beginParticlePointerDrag(e);
    else { w.orbit.rotating = false; w.particlePointerSpin.active = false; }
    canvas.setPointerCapture(e.pointerId);
    // Prevent compatibility mousedown from starting the desktop camera drag on a card.
    e.preventDefault();
  });
  canvas.addEventListener('pointermove', function (e) {
    if (!gesture || gesture.id !== e.pointerId) return;
    var dx = e.clientX - gesture.x, dy = e.clientY - gesture.y;
    if (Math.hypot(dx, dy) > 8) gesture.dragged = true;
    if (!gesture.dragged) return;
    cancelledClick = true;
    w.markRenderInteraction('touch-browse', 900);
    if (gesture.owner) {
      var owner = gesture.owner;
      var desired = gesture.start - (owner.axis === 'x' ? dx : dy) / owner.step;
      owner.manager.scrollBy(desired - owner.manager.getScrollTarget());
    } else w.moveParticlePointerDrag(e);
  }, { passive: true });
  canvas.addEventListener('pointerup', function (e) { finish(e, false); });
  canvas.addEventListener('pointercancel', function (e) { finish(e, true); });
  canvas.addEventListener('lostpointercapture', function (e) { finish(e, true); });
  canvas.addEventListener('click', function (e) {
    if (cancelledClick) { cancelledClick = false; w.mouseDownAt.hadDrag = false; e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
  // A new deliberate press clears suppression, so the first tap after a drag still works.
  canvas.addEventListener('mousedown', function () { cancelledClick = false; }, true);
  function cancel() { finish(null, true); domGesture = null; domClick = null; }
  w.addEventListener('blur', cancel); w.addEventListener('resize', cancel);
  d.addEventListener('visibilitychange', function () { if (d.hidden) cancel(); });

  // Observe only. No capture, preventDefault or pointer capture on scroll surfaces.
  // This also protects delegated/inline row clicks after content has been re-rendered.
  d.addEventListener('pointerdown', function (e) {
    domClick = null; domGesture = null;
    var surface = e.target.closest && e.target.closest(scrollSelector);
    if (surface && e.pointerType !== 'mouse') domGesture = { id:e.pointerId, surface:surface, x:e.clientX, y:e.clientY, moved:false };
  }, { passive: true, capture: true });
  d.addEventListener('pointermove', function (e) {
    if (domGesture && domGesture.id === e.pointerId && Math.hypot(e.clientX-domGesture.x,e.clientY-domGesture.y)>8) domGesture.moved = true;
  }, { passive: true, capture: true });
  function endDom(e) {
    if (!domGesture || domGesture.id !== e.pointerId) return;
    if (domGesture.moved || e.type === 'pointercancel') domClick = domGesture.surface;
    domGesture = null;
  }
  d.addEventListener('pointerup', endDom, { passive:true, capture:true });
  d.addEventListener('pointercancel', endDom, { passive:true, capture:true });
  d.addEventListener('click', function (e) {
    if (domClick && domClick.contains(e.target) && e.detail !== 0) { domClick = null; e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
})(window);
