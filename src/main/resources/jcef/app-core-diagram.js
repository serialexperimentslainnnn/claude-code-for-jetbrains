(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});

  var NODE_H = 32;
  var COL_GAP = 26;
  var ROW_GAP = 10;
  var NODE_MIN = 96;
  var NODE_MAX = 230;

  function widthFor(node) {
    var label = (node.label == null ? '' : String(node.label)).length;
    var meta = (node.meta == null ? '' : String(node.meta)).length;
    var action = node.action ? 46 : 0;
    var px = 38 + Math.round(label * 7.1) + Math.round(meta * 5.8) + action;
    return Math.max(NODE_MIN, Math.min(NODE_MAX, px));
  }

  CC.diagramLabel = function (kind, depth, label) {
    var text = label == null ? '' : String(label);
    if (kind === 'task') return 'Background Task (' + text + ')';
    if (kind === 'agent') return (depth > 1 ? 'Subagent (' : 'Agent (') + text + ')';
    return text;
  };

  CC.diagramShown = function (kind, depth, label) {
    if (kind === 'task') return 'BT: ' + (label == null ? '' : String(label));
    return CC.diagramLabel(kind, depth, label);
  };

  CC.diagram = function (roots) {
    var list = Array.isArray(roots) ? roots.filter(Boolean) : [];
    var placed = [];
    var cursor = 0;

    function measure(node, depth, widths) {
      widths[depth] = Math.max(widths[depth] || 0, widthFor(node));
      (Array.isArray(node.children) ? node.children.filter(Boolean) : []).forEach(function (kid) {
        measure(kid, depth + 1, widths);
      });
    }
    var colWidth = [];
    list.forEach(function (r) {
      measure(r, 0, colWidth);
    });
    var colX = [];
    colWidth.reduce(function (x, w, i) {
      colX[i] = x;
      return x + w + COL_GAP;
    }, 0);

    function place(node, depth, parent) {
      var me = {
        node: node,
        depth: depth,
        parent: parent,
        kids: [],
        w: colWidth[depth],
        x: colX[depth],
        fresh: isNew(node),
      };
      var kids = Array.isArray(node.children) ? node.children.filter(Boolean) : [];
      if (!kids.length) {
        me.y = cursor;
        cursor += NODE_H + ROW_GAP;
      } else {
        me.kids = kids.map(function (kid) {
          return place(kid, depth + 1, me);
        });
        var first = me.kids[0].y + NODE_H / 2;
        var last = me.kids[me.kids.length - 1].y + NODE_H / 2;
        me.y = (first + last) / 2 - NODE_H / 2;
      }
      placed.push(me);
      return me;
    }
    list.forEach(function (r) {
      place(r, 0, null);
    });
    if (!placed.length) return null;

    var width = 0;
    var height = 0;
    placed.forEach(function (p) {
      width = Math.max(width, p.x + p.w);
      height = Math.max(height, p.y + NODE_H);
    });

    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'dg-edges');
    svg.setAttribute('width', String(width));
    svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('aria-hidden', 'true');

    var cards = [];
    placed.forEach(function (p) {
      if (p.kids.length) {
        var x1 = p.x + p.w;
        var y1 = p.y + NODE_H / 2;
        p.kids.forEach(function (kid) {
          var x2 = kid.x;
          var y2 = kid.y + NODE_H / 2;
          var mid = x1 + (x2 - x1) / 2;
          var path = edge(
            svg,
            'M' + x1 + ' ' + y1 + ' C' + mid + ' ' + y1 + ' ' + mid + ' ' + y2 + ' ' + x2 + ' ' + y2,
            kid.node.running
          );
          if (path && kid.fresh) drawIn(path);
        });
      }
      cards.push(diagramCard(p));
    });

    var canvas = CC.h('div', { class: 'dg-canvas', style: { width: width + 'px', height: height + 'px' } });
    canvas.appendChild(svg);
    cards.forEach(function (card) {
      canvas.appendChild(card);
    });
    return canvas;
  };

  var everSeen = {};

  function nowMs() {
    return Math.round(
      typeof performance !== 'undefined' && performance && typeof performance.now === 'function'
        ? performance.now()
        : new Date().getTime()
    );
  }

  function isNew(node) {
    var id = node && node.id != null ? String(node.id) : null;
    if (!id || everSeen[id]) return false;
    everSeen[id] = true;
    return true;
  }

  function drawIn(path) {
    if (typeof path.getTotalLength !== 'function') return;
    var len;
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

  function edge(svg, d, running) {
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'dg-edge' + (running ? ' running' : ''));
    if (running) path.style.animationDelay = '-' + (nowMs() % 1300) + 'ms';
    svg.appendChild(path);
    return path;
  }

  function diagramCard(p) {
    var n = p.node;
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
      CC.h('span', { class: 'dg-label', text: n.label == null ? '' : String(n.label) }),
      n.meta ? CC.h('span', { class: 'dg-meta', text: String(n.meta) }) : null,
      n.action
        ? CC.h('span', {
            class: 'btn dg-action',
            attrs: { role: 'button', tabindex: '0', 'aria-label': n.action.label },
            text: n.action.label,
            on: {
              click: function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                n.action.onClick();
              },
            },
          })
        : null
    );
  }

  var viewState = {};

  CC.panView = function (canvas, label, key) {
    var SLOP = 4;
    var MIN_ZOOM = 0.4;
    var MAX_ZOOM = 2.5;
    var LEGIBLE = 0.9;
    var view = CC.h('div', { class: 'dg-view', attrs: { tabindex: '0', 'aria-label': label || 'Diagram' } });
    view.appendChild(canvas);
    var at = { x: 0, y: 0 };
    var zoom = 1;
    var from = null;
    var moved = false;

    function apply() {
      canvas.style.transform =
        'translate(' + Math.round(at.x) + 'px,' + Math.round(at.y) + 'px) scale(' + zoom.toFixed(3) + ')';
      if (key) viewState[key] = { x: at.x, y: at.y, zoom: zoom };
    }

    function fit() {
      var vw = view.clientWidth;
      var vh = view.clientHeight;
      var cw = canvas.offsetWidth;
      var ch = canvas.offsetHeight;
      if (!vw || !vh || !cw || !ch) return;
      var pad = 16;
      zoom = Math.min(1, (vw - pad * 2) / cw, (vh - pad * 2) / ch);
      if (!(zoom > 0) || zoom < LEGIBLE) zoom = Math.max(LEGIBLE, Math.min(1, zoom || 1));
      at.x = cw * zoom <= vw - pad * 2 ? (vw - cw * zoom) / 2 : pad;
      at.y = ch * zoom <= vh - pad * 2 ? (vh - ch * zoom) / 2 : pad;
      apply();
    }

    function fitOrRestore() {
      var saved = key && viewState[key];
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

    view.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      from = { x: ev.clientX - at.x, y: ev.clientY - at.y, sx: ev.clientX, sy: ev.clientY };
      moved = false;
    });
    document.addEventListener('mousemove', function (ev) {
      if (!from) return;
      if (!moved && Math.abs(ev.clientX - from.sx) + Math.abs(ev.clientY - from.sy) < SLOP) return;
      moved = true;
      view.classList.add('dragging');
      at.x = ev.clientX - from.x;
      at.y = ev.clientY - from.y;
      apply();
    });
    document.addEventListener('mouseup', function () {
      if (!from) return;
      from = null;
      view.classList.remove('dragging');
    });
    view.addEventListener(
      'wheel',
      function (ev) {
        ev.preventDefault();
        var next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom * (ev.deltaY < 0 ? 1.12 : 1 / 1.12)));
        if (next === zoom) return;
        var r = view.getBoundingClientRect();
        var px = ev.clientX - r.left;
        var py = ev.clientY - r.top;
        at.x = px - ((px - at.x) * next) / zoom;
        at.y = py - ((py - at.y) * next) / zoom;
        zoom = next;
        apply();
      },
      { passive: false }
    );
    view.addEventListener('dblclick', function (ev) {
      if (ev.target !== view && ev.target !== canvas) return;
      fit();
    });
    view.addEventListener(
      'click',
      function (ev) {
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
