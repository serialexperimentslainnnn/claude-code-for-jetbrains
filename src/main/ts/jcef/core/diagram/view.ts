(function () {
  'use strict';

  const CC = window.CC || (window.CC = {} as CcShared);

  interface ViewState {
    x: number;
    y: number;
    zoom: number;
  }

  interface DragOrigin {
    x: number;
    y: number;
    sx: number;
    sy: number;
  }

  const viewState: Record<string, ViewState> = {};

  CC.panView = function (canvas: HTMLElement, label?: string | null, key?: string | null): PanView {
    const SLOP = 4;
    const MIN_ZOOM = 0.4;
    const MAX_ZOOM = 2.5;
    const LEGIBLE = 0.9;
    const view = CC.h('div', {
      class: 'dg-view',
      attrs: { tabindex: '0', 'aria-label': label || 'Diagram' },
    }) as PanView;
    view.appendChild(canvas);
    const at = { x: 0, y: 0 };
    let zoom = 1;
    let from: DragOrigin | null = null;
    let moved = false;

    function apply(): void {
      canvas.style.transform =
        'translate(' + Math.round(at.x) + 'px,' + Math.round(at.y) + 'px) scale(' + zoom.toFixed(3) + ')';
      if (key) viewState[key] = { x: at.x, y: at.y, zoom: zoom };
    }

    function fit(): void {
      const vw = view.clientWidth;
      const vh = view.clientHeight;
      const cw = canvas.offsetWidth;
      const ch = canvas.offsetHeight;
      if (!vw || !vh || !cw || !ch) return;
      const pad = 16;
      zoom = Math.min(1, (vw - pad * 2) / cw, (vh - pad * 2) / ch);
      if (!(zoom > 0) || zoom < LEGIBLE) zoom = Math.max(LEGIBLE, Math.min(1, zoom || 1));
      at.x = cw * zoom <= vw - pad * 2 ? (vw - cw * zoom) / 2 : pad;
      at.y = ch * zoom <= vh - pad * 2 ? (vh - ch * zoom) / 2 : pad;
      apply();
    }

    function fitOrRestore(): void {
      const saved = key ? viewState[key] : undefined;
      if (saved) {
        at.x = saved.x;
        at.y = saved.y;
        zoom = saved.zoom;
        apply();
        return;
      }
      fit();
    }
    view.__fit = fitOrRestore;

    function onDrag(ev: MouseEvent): void {
      if (!from) return;
      if (!moved && Math.abs(ev.clientX - from.sx) + Math.abs(ev.clientY - from.sy) < SLOP) return;
      moved = true;
      view.classList.add('dragging');
      at.x = ev.clientX - from.x;
      at.y = ev.clientY - from.y;
      apply();
    }
    function onRelease(): void {
      document.removeEventListener('mousemove', onDrag);
      document.removeEventListener('mouseup', onRelease);
      if (!from) return;
      from = null;
      view.classList.remove('dragging');
    }
    view.addEventListener('mousedown', function (ev: MouseEvent) {
      if (ev.button !== 0) return;
      from = { x: ev.clientX - at.x, y: ev.clientY - at.y, sx: ev.clientX, sy: ev.clientY };
      moved = false;
      document.addEventListener('mousemove', onDrag);
      document.addEventListener('mouseup', onRelease);
    });
    view.addEventListener(
      'wheel',
      function (ev: WheelEvent) {
        ev.preventDefault();
        const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom * (ev.deltaY < 0 ? 1.12 : 1 / 1.12)));
        if (next === zoom) return;
        const r = view.getBoundingClientRect();
        const px = ev.clientX - r.left;
        const py = ev.clientY - r.top;
        at.x = px - ((px - at.x) * next) / zoom;
        at.y = py - ((py - at.y) * next) / zoom;
        zoom = next;
        apply();
      },
      { passive: false }
    );
    view.addEventListener('dblclick', function (ev: MouseEvent) {
      if (ev.target !== view && ev.target !== canvas) return;
      fit();
    });
    view.addEventListener(
      'click',
      function (ev: MouseEvent) {
        if (!moved) return;
        ev.preventDefault();
        ev.stopPropagation();
        moved = false;
      },
      true
    );
    return view;
  };
})();
