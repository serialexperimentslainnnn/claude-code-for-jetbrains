(function () {
  'use strict';

  const CC = window.CC || (window.CC = {} as CcShared);

  const NODE_H = 32;
  const COL_GAP = 26;
  const ROW_GAP = 10;
  const NODE_MIN = 96;
  const NODE_MAX = 230;

  interface Placed {
    node: DiagramNode;
    depth: number;
    parent: Placed | null;
    kids: Placed[];
    w: number;
    x: number;
    y: number;
    fresh: boolean;
  }

  function text(value: unknown): string {
    return value == null ? '' : String(value);
  }

  function kidsOf(node: DiagramNode): DiagramNode[] {
    return Array.isArray(node.children) ? (node.children.filter(Boolean) as DiagramNode[]) : [];
  }

  function widthFor(node: DiagramNode): number {
    const label = text(node.label).length;
    const meta = text(node.meta).length;
    const action = node.action ? 46 : 0;
    const px = 38 + Math.round(label * 7.1) + Math.round(meta * 5.8) + action;
    return Math.max(NODE_MIN, Math.min(NODE_MAX, px));
  }

  CC.diagramLabel = function (kind: string | null | undefined, depth: number, label: unknown): string {
    const shown = text(label);
    if (kind === 'task') return 'Background Task (' + shown + ')';
    if (kind === 'agent') return (depth > 1 ? 'Subagent (' : 'Agent (') + shown + ')';
    return shown;
  };

  CC.diagramShown = function (kind: string | null | undefined, depth: number, label: unknown): string {
    if (kind === 'task') return 'BT: ' + text(label);
    return CC.diagramLabel(kind, depth, label);
  };

  CC.diagram = function (roots: unknown): HTMLElement | null {
    const list: DiagramNode[] = Array.isArray(roots) ? (roots.filter(Boolean) as DiagramNode[]) : [];
    const placed: Placed[] = [];
    let cursor = 0;

    function measure(node: DiagramNode, depth: number, widths: number[]): void {
      widths[depth] = Math.max(widths[depth] || 0, widthFor(node));
      kidsOf(node).forEach(function (kid) {
        measure(kid, depth + 1, widths);
      });
    }
    const colWidth: number[] = [];
    list.forEach(function (r) {
      measure(r, 0, colWidth);
    });
    const colX: number[] = [];
    colWidth.reduce(function (x, w, i) {
      colX[i] = x;
      return x + w + COL_GAP;
    }, 0);

    function place(node: DiagramNode, depth: number, parent: Placed | null): Placed {
      const me: Placed = {
        node: node,
        depth: depth,
        parent: parent,
        kids: [],
        w: colWidth[depth],
        x: colX[depth],
        y: 0,
        fresh: isNew(node),
      };
      const kids = kidsOf(node);
      if (!kids.length) {
        me.y = cursor;
        cursor += NODE_H + ROW_GAP;
      } else {
        me.kids = kids.map(function (kid) {
          return place(kid, depth + 1, me);
        });
        const first = me.kids[0].y + NODE_H / 2;
        const last = me.kids[me.kids.length - 1].y + NODE_H / 2;
        me.y = (first + last) / 2 - NODE_H / 2;
      }
      placed.push(me);
      return me;
    }
    list.forEach(function (r) {
      place(r, 0, null);
    });
    if (!placed.length) return null;

    let width = 0;
    let height = 0;
    placed.forEach(function (p) {
      width = Math.max(width, p.x + p.w);
      height = Math.max(height, p.y + NODE_H);
    });

    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'dg-edges');
    svg.setAttribute('width', String(width));
    svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('aria-hidden', 'true');

    const cards: HTMLElement[] = [];
    placed.forEach(function (p) {
      if (p.kids.length) {
        const x1 = p.x + p.w;
        const y1 = p.y + NODE_H / 2;
        p.kids.forEach(function (kid) {
          const x2 = kid.x;
          const y2 = kid.y + NODE_H / 2;
          const mid = x1 + (x2 - x1) / 2;
          const path = edge(
            svg,
            'M' + x1 + ' ' + y1 + ' C' + mid + ' ' + y1 + ' ' + mid + ' ' + y2 + ' ' + x2 + ' ' + y2,
            !!kid.node.running
          );
          if (path && kid.fresh) drawIn(path);
        });
      }
      cards.push(diagramCard(p));
    });

    const canvas = CC.h('div', { class: 'dg-canvas', style: { width: width + 'px', height: height + 'px' } });
    canvas.appendChild(svg);
    cards.forEach(function (card) {
      canvas.appendChild(card);
    });
    return canvas;
  };

  const everSeen: Record<string, true> = {};

  function nowMs(): number {
    return Math.round(
      typeof performance !== 'undefined' && performance && typeof performance.now === 'function'
        ? performance.now()
        : new Date().getTime()
    );
  }

  function isNew(node: DiagramNode): boolean {
    const id = node && node.id != null ? String(node.id) : null;
    if (!id || everSeen[id]) return false;
    everSeen[id] = true;
    return true;
  }

  function drawIn(path: SVGPathElement): void {
    if (typeof path.getTotalLength !== 'function') return;
    let len: number;
    try {
      len = path.getTotalLength();
    } catch (e) {
      return;
    }
    if (!len) return;
    path.style.strokeDasharray = len + ' ' + len;
    path.style.strokeDashoffset = String(len);
    path.classList.add('dg-draw');
  }

  function edge(svg: SVGSVGElement, d: string, running: boolean): SVGPathElement {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'dg-edge' + (running ? ' running' : ''));
    if (running) path.style.animationDelay = '-' + (nowMs() % 1300) + 'ms';
    svg.appendChild(path);
    return path;
  }

  function diagramCard(p: Placed): HTMLElement {
    const n = p.node;
    const action = n.action;
    return CC.h(
      'button',
      {
        class:
          'dg-card' +
          (n.kind ? ' ' + n.kind : '') +
          (n.status ? ' ' + n.status : '') +
          (n.selected ? ' selected' : '') +
          (p.fresh ? ' dg-pop' : ''),
        style: {
          left: p.x + 'px',
          top: p.y + 'px',
          width: p.w + 'px',
          height: NODE_H + 'px',
          animationDelay: n.status === 'running' ? '-' + (nowMs() % 1300) + 'ms' : null,
        },
        attrs: { type: 'button', title: n.title || n.label, 'aria-label': n.name || null },
        on: { click: n.onPick || function () {} },
      },
      n.status ? CC.h('span', { class: 'dg-dot ' + n.status, attrs: { 'aria-hidden': 'true' } }) : null,
      CC.h('span', { class: 'dg-label', text: text(n.label) }),
      n.meta ? CC.h('span', { class: 'dg-meta', text: String(n.meta) }) : null,
      action
        ? CC.h('span', {
            class: 'btn dg-action',
            attrs: { role: 'button', tabindex: '0', 'aria-label': action.label },
            text: action.label,
            on: {
              click: function (ev: Event) {
                ev.preventDefault();
                ev.stopPropagation();
                action.onClick();
              },
            },
          })
        : null
    );
  }
})();
