(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const T = (CC.tabbar = CC.tabbar || ({} as TabbarNs));

  const DRAG_SLOP = 4;

  function scrollLeftTo(el: HTMLElement, x: number): void {
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ left: x, behavior: 'instant' });
    } else {
      el.scrollLeft = x;
    }
  }

  let drag: { el: HTMLElement; x: number; scroll: number } | null = null;
  let moved = false;

  function dragToScroll(el: HTMLElement): void {
    el.addEventListener('mousedown', function (ev: MouseEvent) {
      if (ev.button !== 0) return;
      drag = { el: el, x: ev.clientX, scroll: el.scrollLeft };
      moved = false;
    });
    el.addEventListener(
      'click',
      function (ev: Event) {
        if (!moved) return;
        moved = false;
        ev.stopPropagation();
        ev.preventDefault();
      },
      true
    );
  }

  document.addEventListener('mousemove', function (ev: MouseEvent) {
    if (!drag) return;
    const dx = ev.clientX - drag.x;
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

  function wheelToScroll(capsule: HTMLElement): void {
    capsule.addEventListener('wheel', function (ev: WheelEvent) {
      const delta = Math.abs(ev.deltaY) > Math.abs(ev.deltaX) ? ev.deltaY : ev.deltaX;
      if (!delta) return;
      const before = capsule.scrollLeft;
      scrollLeftTo(capsule, before + delta);
      if (capsule.scrollLeft !== before) ev.preventDefault();
    });
  }

  function keepFocusVisible(capsule: HTMLElement): void {
    capsule.addEventListener('focusin', function (ev: FocusEvent) {
      const target = ev.target as HTMLElement | null;
      if (target && target.scrollIntoView) target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    });
  }

  T.scrollLeftTo = scrollLeftTo;
  T.dragToScroll = dragToScroll;
  T.wheelToScroll = wheelToScroll;
  T.keepFocusVisible = keepFocusVisible;
})();
