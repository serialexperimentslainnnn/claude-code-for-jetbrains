(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const G = (D.git = D.git || ({} as GitNs));
  const h = D.h;

  const LANE_W = 16;
  const ROW_UNITS = 100;
  const ROW_MID = 50;

  const LANE_COLOURS = 6;

  const SVG_NS = 'http://www.w3.org/2000/svg';

  function layoutLanes(entries: unknown): LaneModel {
    const items = G.list<LaneEntry>(entries);
    const known: Record<string, boolean> = Object.create(null);
    items.forEach(function (item) {
      const hash = G.text(item.hash, '');
      if (hash) known[hash] = true;
    });

    const lanes: (string | null)[] = [];
    const rows: LaneRow[] = [];

    function firstFree(): number {
      for (let i = 0; i < lanes.length; i++) {
        if (lanes[i] == null) return i;
      }
      lanes.push(null);
      return lanes.length - 1;
    }

    items.forEach(function (item, index) {
      const hash = G.text(item.hash, '');
      const before = lanes.slice();
      const up: number[] = [];
      for (let k = 0; k < before.length; k++) {
        if (hash && before[k] === hash) up.push(k);
      }
      const lane = up.length ? up[0] : firstFree();
      up.forEach(function (waiting) {
        if (waiting !== lane) lanes[waiting] = null;
      });

      const parents = G.list<unknown>(item.parents).map(String);
      const down: number[] = [];
      const first = parents.length ? parents[0] : null;
      lanes[lane] = first && known[first] ? first : null;
      if (lanes[lane]) down.push(lane);
      for (let p = 1; p < parents.length; p++) {
        const other = parents[p];
        if (!known[other]) continue;
        let at = lanes.indexOf(other);
        if (at < 0) {
          at = firstFree();
          lanes[at] = other;
        }
        if (down.indexOf(at) < 0) down.push(at);
      }

      const through: number[] = [];
      for (let q = 0; q < lanes.length; q++) {
        if (q !== lane && before[q] != null && lanes[q] === before[q]) through.push(q);
      }
      rows.push({
        item: item,
        hash: hash,
        index: index,
        lane: lane,
        up: up,
        down: down,
        through: through,
        parents: parents,
        merge: parents.length > 1,
      });
    });
    return { rows: rows, lanes: Math.max(1, lanes.length) };
  }

  function laneX(l: number): number {
    return l * LANE_W + LANE_W / 2;
  }

  function bend(x1: number, y1: number, x2: number, y2: number): string {
    const mid = (y1 + y2) / 2;
    return 'M' + x1 + ' ' + y1 + 'C' + x1 + ' ' + mid + ' ' + x2 + ' ' + mid + ' ' + x2 + ' ' + y2;
  }

  function edge(svg: SVGElement, d: string, lane: number): void {
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('class', 'git-edge');
    path.setAttribute('d', d);
    path.setAttribute('data-lane', String(lane % LANE_COLOURS));
    svg.appendChild(path);
  }

  G.gutter = function (row: LaneRow, lanes: number): HTMLElement {
    const width = lanes * LANE_W;
    const x = laneX(row.lane);
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'git-graph');
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + ROW_UNITS);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('aria-hidden', 'true');
    row.through.forEach(function (l) {
      edge(svg, 'M' + laneX(l) + ' 0V' + ROW_UNITS, l);
    });
    row.up.forEach(function (l) {
      if (l === row.lane) {
        edge(svg, 'M' + x + ' 0V' + ROW_MID, l);
      } else {
        edge(svg, bend(laneX(l), 0, x, ROW_MID), l);
      }
    });
    row.down.forEach(function (l) {
      if (l === row.lane) {
        edge(svg, 'M' + x + ' ' + ROW_MID + 'V' + ROW_UNITS, l);
      } else {
        edge(svg, bend(x, ROW_MID, laneX(l), ROW_UNITS), l);
      }
    });
    const dot = h('span', {
      class: 'git-dot-node' + (row.merge ? ' merge' : ''),
      style: { left: x + 'px' },
      attrs: { 'data-lane': String(row.lane % LANE_COLOURS) },
    });
    return h('span', { class: 'git-gutter', style: { width: width + 'px' } }, svg, dot);
  };

  D.gitLanes = layoutLanes;
})();
