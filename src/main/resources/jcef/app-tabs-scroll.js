(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});

  var DRAG_SLOP = 4;

  function scrollLeftTo(el, x) {
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ left: x, behavior: 'instant' });
    } else {
      el.scrollLeft = x;
    }
  }

  var drag = null;
  var moved = false;

  function dragToScroll(el) {
    el.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      drag = { el: el, x: ev.clientX, scroll: el.scrollLeft };
      moved = false;
    });
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

  function wheelToScroll(capsule) {
    capsule.addEventListener('wheel', function (ev) {
      var delta = Math.abs(ev.deltaY) > Math.abs(ev.deltaX) ? ev.deltaY : ev.deltaX;
      if (!delta) return;
      var before = capsule.scrollLeft;
      scrollLeftTo(capsule, before + delta);
      if (capsule.scrollLeft !== before) ev.preventDefault();
    });
  }

  function keepFocusVisible(capsule) {
    capsule.addEventListener('focusin', function (ev) {
      if (ev.target && ev.target.scrollIntoView)
        ev.target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
  }

  T.scrollLeftTo = scrollLeftTo;
  T.dragToScroll = dragToScroll;
  T.wheelToScroll = wheelToScroll;
  T.keepFocusVisible = keepFocusVisible;
})();
