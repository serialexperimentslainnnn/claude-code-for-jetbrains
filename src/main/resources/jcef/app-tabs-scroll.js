/* app-tabs-scroll.js — how the row of chats MOVES.
 *
 * One subject, three gestures on the same capsule, and each of them exists because the obvious way failed on
 * screen: the row is grabbed and dragged (there is no scrollbar to aim at), a vertical wheel is translated
 * into a horizontal scroll (Chromium does not do it), and a tab that takes the keyboard focus is brought into
 * view (it would otherwise be focused off-screen).
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});

  /** Past this many pixels the gesture is a DRAG, not a click on the tab you happened to press. */
  var DRAG_SLOP = 4;

  /**
   * Move the capsule sideways NOW — never on the stylesheet's terms.
   *
   * `.tab-capsule` carries `scroll-behavior: smooth`, and an assignment to `scrollLeft` takes its behaviour
   * from that computed value: it starts an animation and leaves the property reading where the animation
   * BEGAN. Both gestures below are built on reading that position back, so both break — each step is computed
   * from a stale offset, consecutive steps re-aim at the same place instead of accumulating, and a read-back
   * cannot tell whether the row moved at all. `instant` is the value that overrides the property; `auto` is
   * the value that defers to it. Falls back to the assignment where `scrollTo` does not exist, which is the
   * case under jsdom — `Element.prototype` there has no scroll methods at all.
   */
  function scrollLeftTo(el, x) {
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ left: x, behavior: 'instant' });
    } else {
      el.scrollLeft = x;
    }
  }

  /** The gesture in flight, and whether it has passed the threshold. One row, one pointer, one of each. */
  var drag = null;
  var moved = false;

  /**
   * Grab the row and move it, the way you would move a physical row of tabs.
   *
   * There is no scrollbar to aim at (the platform one is a grey slab across a rounded capsule), the wheel
   * is not discoverable, and clicking your way along a row of twenty is tedious — so the row itself is the
   * handle. Selecting a chat also centres it (see `render`), which means ordinary use keeps the row roughly
   * where you can reach it and dragging is for the rest.
   *
   * The click that ENDS a drag is swallowed: releasing over a tab must not also switch to that chat. Mouse
   * events rather than pointer events on purpose — `setPointerCapture` does not exist in jsdom, and a
   * gesture nobody can test is a gesture that breaks silently.
   *
   * Only the per-capsule half is registered here. The gesture continues over the whole document once it
   * starts, and those two listeners live at module scope, registered ONCE for the page: the bar builds a NEW
   * capsule on every repaint — several times a turn — and a listener added to `document` per capsule outlives
   * the element it was added for, so one per repaint is an unbounded pile of handlers, each of them running on
   * every mouse move and each holding a discarded row alive.
   */
  function dragToScroll(el) {
    el.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      drag = { el: el, x: ev.clientX, scroll: el.scrollLeft };
      moved = false;
    });
    // Capture phase, so it runs before the pill's own handler. `moved` is cleared here rather than on
    // mouseup: the click arrives after it, and clearing it early would let the chat switch anyway.
    el.addEventListener(
      'click',
      function (ev) {
        if (!moved) return;
        moved = false;
        ev.stopPropagation();
        ev.preventDefault();
      },
      true
    );
  }

  document.addEventListener('mousemove', function (ev) {
    if (!drag) return;
    var dx = ev.clientX - drag.x;
    if (!moved && Math.abs(dx) < DRAG_SLOP) return;
    moved = true;
    drag.el.classList.add('dragging');
    scrollLeftTo(drag.el, drag.scroll - dx);
    ev.preventDefault();
  });
  document.addEventListener('mouseup', function () {
    if (!drag) return;
    drag.el.classList.remove('dragging');
    drag = null;
  });

  /**
   * A vertical wheel does NOT move a horizontal scroller in Chromium, so with enough chats the capsule
   * overflowed with no way to reach the far end — the tabs were simply unreachable. Translating the
   * gesture is the whole fix; `preventDefault` stops the page from scrolling underneath instead.
   */
  function wheelToScroll(capsule) {
    capsule.addEventListener('wheel', function (ev) {
      var delta = Math.abs(ev.deltaY) > Math.abs(ev.deltaX) ? ev.deltaY : ev.deltaX;
      if (!delta) return;
      // No overflow check: a measurement that says "nothing to scroll" when there is leaves the row inert
      // with no way to tell why. Scrolling a box that cannot scroll costs nothing — the offset simply does
      // not move — so `preventDefault` is conditioned on it having actually moved instead. That condition is
      // only meaningful because the scroll is instant: a smooth one has not moved yet when it is read back.
      var before = capsule.scrollLeft;
      scrollLeftTo(capsule, before + delta);
      if (capsule.scrollLeft !== before) ev.preventDefault();
    });
  }

  /**
   * A tab that TAKES FOCUS is scrolled into view. Without this, tabbing along a row wider than the
   * window moves focus onto pills that are scrolled out of sight or half-covered by the edge fade —
   * WCAG 2.2 SC 2.4.7 (Focus Visible) and 2.4.11 (Focus Not Obscured). One listener on the container,
   * not one per pill, so it survives the rebuild.
   */
  function keepFocusVisible(capsule) {
    capsule.addEventListener('focusin', function (ev) {
      if (ev.target && ev.target.scrollIntoView)
        ev.target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
  }

  // Exported for the same reason it exists: `render` rebuilds the capsule and has to put the row back where
  // the reader left it, and a bare assignment there would GLIDE across the row on every repaint.
  T.scrollLeftTo = scrollLeftTo;
  T.dragToScroll = dragToScroll;
  T.wheelToScroll = wheelToScroll;
  T.keepFocusVisible = keepFocusVisible;
})();
