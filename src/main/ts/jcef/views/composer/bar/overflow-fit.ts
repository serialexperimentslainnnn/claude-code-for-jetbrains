(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  CX.overflowFit = function (m: OverflowMetrics | null | undefined): OverflowPlan {
    const ends = m && m.ends ? m.ends : [];
    const n = ends.length;
    if (!m || !m.overflowing) return { visible: n, toggle: false };
    const used = n ? ends[n - 1] : 0;
    if (m.toggle && used + (m.reserved || 0) <= m.available) return { visible: n, toggle: false };
    const budget = m.available - (m.reserved || 0) - (m.toggle || 0);
    let k = 0;
    while (k < n && ends[k] <= budget) k++;
    return { visible: k, toggle: true };
  };

  CX.overflowMeasure = function (
    row: HTMLElement,
    items: HTMLElement[],
    reserved: HTMLElement[],
    toggle: HTMLElement | null
  ): OverflowMetrics {
    const rowRect = row.getBoundingClientRect();
    const left = rowRect.left + (row.clientLeft || 0);
    const ends: number[] = [];
    let lastRight = left;
    for (let i = 0; i < items.length; i++) {
      const r = items[i].getBoundingClientRect();
      ends.push(r.right - left);
      lastRight = r.right;
    }
    const toggleWidth = toggle ? toggle.getBoundingClientRect().width : 0;
    let tailEnd = toggle ? toggle.getBoundingClientRect().right : lastRight;
    for (let j = 0; j < reserved.length; j++) {
      const edge = reserved[j].getBoundingClientRect().right;
      if (edge > tailEnd) tailEnd = edge;
    }
    return {
      available: row.clientWidth,
      overflowing: row.scrollWidth > row.clientWidth + 1,
      ends: ends,
      reserved: Math.max(0, tailEnd - lastRight - toggleWidth),
      toggle: toggleWidth,
    };
  };

  CX.overflowLabel = function (el: HTMLElement): string {
    const name = (el.getAttribute && el.getAttribute('aria-label')) || el.title || el.textContent || '';
    return String(name).replace(/\s+/g, ' ').trim();
  };

  CX.dotsGlyph = function (): string {
    return (
      '<svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor" aria-hidden="true">' +
      '<circle cx="8" cy="3.4" r="1.35"/><circle cx="8" cy="8" r="1.35"/><circle cx="8" cy="12.6" r="1.35"/>' +
      '</svg>'
    );
  };
})();
