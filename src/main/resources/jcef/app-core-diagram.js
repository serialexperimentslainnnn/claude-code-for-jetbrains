/*
 * app-core-diagram.js — the node diagram behind the Workloads view.
 *
 * Owns: the node diagram behind the Workloads view.
 *
 * One subject: laying out a tree of running work as cards + connectors, and the viewport you drag and zoom
 * it in. Split out of app-core.js; loads right after it and only extends window.CC.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});

  // ---------------------------------------------------------------------------
  // Node diagram (the Workloads view)
  // ---------------------------------------------------------------------------
  /**
   * A tree diagram that DESCENDS: one node per row, each child a small step to the right of its parent, with
   * an L-shaped connector down the parent's channel and into the child's left edge.
   *
   * **Why not a column per depth.** The first version put each level in its own column, which is the classic
   * org-chart shape and the wrong one here: a node was 208px wide and every level cost another 264px, so a
   * three-level tree was wider than the tool window, which is the one dimension there is none of. Descending
   * costs INDENT (18px) per level instead of a whole column, so depth is nearly free and the thing that grows
   * is the height — which is the axis you can actually scroll and drag.
   *
   * **The geometry is COMPUTED, never measured.** Every coordinate here is arithmetic on the tree, and the
   * connectors are drawn from those same numbers. That is what makes it right at every moment: the tab bar's
   * first design measured `getBoundingClientRect` at render, on a CSS transition and on resize, so it was
   * wrong whenever it was measured at the wrong one. Nothing here is measured at all.
   *
   * The cards size themselves to their text (CSS), which the layout does not need to know: a connector only
   * ever travels down the indent channel to the LEFT of a card, never across one.
   *
   * [roots] is `[{id, kind, label, meta, status, title, onPick, children:[…]}]`. Returns the canvas element;
   * the caller decides where to put it and how big the viewport is.
   */
  var NODE_H = 32;
  // Horizontal space between one level and the next — where the connector lives. Deliberately unchanged when
  // the cards were halved: it is the width of a channel a curve has to be legible in, which is an absolute
  // quantity and not a fraction of a card. Narrower cards make it proportionally MORE visible, so the tree
  // reads as more separated, not as more sparse.
  var COL_GAP = 26;
  var ROW_GAP = 10; // vertical space between two cards on the same level
  /**
   * The floor, and it is DERIVED rather than chosen: 36px of a card exists before a single character of its
   * label does — 4px of borders (1 + a 3px state edge), 18px of padding, the 7px state dot and the 7px gap
   * after it — and below roughly eight characters a label is an ellipsis with a hint attached. 36 + 8 × 7.1
   * (the per-character estimate below) ≈ 93, rounded to 96.
   *
   * Half of the old 170 would be 85, which lands under that: it buys about six characters, i.e. `Agent…`,
   * and every card at the floor would be the same size and say nothing — a column of identical blanks is not
   * a smaller diagram, it is a diagram with the labels taken out.
   */
  var NODE_MIN = 96;
  /**
   * The cap, halved from 460 on the same reasoning that made 460 wrong in the first place — see `widthFor`.
   */
  var NODE_MAX = 230;

  /**
   * How wide a card needs to be for its own text — computed from the STRING, never measured from the DOM.
   *
   * A single fixed width was the wrong answer in both directions: `Agent (Inventario de dependencias)` was
   * cut to `Agent (In…` while `Chat 3` wasted two thirds of its slot. Sizing to the text fixes both; the
   * clamp decides how far that is allowed to go.
   *
   * **The cap is 230, and long labels therefore ellipsize.** That is a reversal, and the reason it is right
   * is the one the previous cap ignored: a 460px card is not a node, it is a ROW. Four of them fill a tool
   * window edge to edge, so the diagram becomes a list drawn with connectors — and a graph that cannot show
   * two columns at once is not showing you a graph, whatever each individual card manages to say. At half
   * that, several levels are on screen together and the SHAPE of the work is readable, which is the only
   * thing this view offers that the transcript does not. The full text is never lost: it is in the button's
   * `title`, which every card already sets from `n.title || n.label`.
   *
   * **The per-character coefficients are not a size decision and are not tuned here.** 7.1px for the label
   * at 12.5px and 5.8px for the meta at 9.5px are estimates of how much space that text OCCUPIES; lowering
   * them to squeeze cards would stop the width corresponding to the content, and the clipping would then
   * land in a different place on every card for no reason a reader could predict. The way to make cards
   * narrower is the cap, which is a decision about the diagram, and it is the only thing that moved.
   */
  function widthFor(node) {
    var label = (node.label == null ? '' : String(node.label)).length;
    var meta = (node.meta == null ? '' : String(node.meta)).length;
    var action = node.action ? 46 : 0;
    var px = 38 + Math.round(label * 7.1) + Math.round(meta * 5.8) + action;
    return Math.max(NODE_MIN, Math.min(NODE_MAX, px));
  }

  /**
   * What a node is CALLED: `Agent (…)`, `Subagent (…)`, `Background Task (…)`.
   *
   * **This is the spoken form, and it never abbreviates.** It is what reaches a `title` and an accessible
   * name, so it is read aloud — see [diagramShown] for the form that is only ever looked at.
   *
   * WHAT it is comes first and the title second, because the title is the model's own sentence about the
   * job ("Inventario de dependencias") and says nothing about what kind of thing is running it. It also
   * matches the transcript, where the card that spawned the work is already labelled `Agent (…)` — the same
   * work should not have two names depending on which panel you read it in.
   *
   * Agent vs Subagent is DEPTH, not a different kind of object: an agent started by the chat is an agent,
   * one started by another agent is a subagent. [depth] 1 = a chat's own.
   */
  CC.diagramLabel = function (kind, depth, label) {
    var text = label == null ? '' : String(label);
    if (kind === 'task') return 'Background Task (' + text + ')';
    if (kind === 'agent') return (depth > 1 ? 'Subagent (' : 'Agent (') + text + ')';
    return text;
  };

  /**
   * What a node SHOWS, which may abbreviate what it is called.
   *
   * **A background task is `BT: npm run dev`.** Written out it is `Background Task (npm run dev)` — 29
   * characters against 15, and a diagram card is sized from its label (`widthFor`) and capped at
   * [NODE_MAX] 230px, so the long form ran past the cap on every task and ellipsised away the only part that
   * identifies it: the command. The prefix is 4 characters and buys back the whole title.
   *
   * **`BT:` must never reach an accessible name** — a screen reader spells an unknown pair of capitals out,
   * so the name stays [diagramLabel]'s. That split is the entire reason these are two functions and not a
   * changed one: a single abbreviating label would have been read aloud as "bee tee", and a caller that
   * wanted the long form would have had to rebuild it, which is the third copy that drifts.
   *
   * Everything that is not a task shows exactly what it is called: an agent's kind is the useful half of its
   * name in a diagram, and it is short enough to keep.
   */
  CC.diagramShown = function (kind, depth, label) {
    if (kind === 'task') return 'BT: ' + (label == null ? '' : String(label));
    return CC.diagramLabel(kind, depth, label);
  };

  CC.diagram = function (roots) {
    var list = Array.isArray(roots) ? roots.filter(Boolean) : [];
    var placed = [];
    var cursor = 0; // next free y, in rows

    /**
     * Classic LEFT-TO-RIGHT tree placement, in one pass.
     *
     * A leaf takes the next free row; a parent is centred vertically over the span of its children. Depth is
     * the column, so the diagram grows RIGHT with nesting and DOWN with how much is running.
     *
     * **Why this way round.** Top-down put every sibling on its own column, so a chat with eight agents was
     * eight card-widths across — in a tool window that is a few hundred pixels wide, fitting that on screen
     * meant shrinking it to unreadable. Nesting rarely goes past three or four levels while siblings run to
     * dozens, so the axis that grows without bound has to be the vertical one: it is the axis a panel can
     * give, and the one a drag can follow.
     *
     * Columns are as wide as their widest card, so the whole level lines up instead of stair-stepping.
     */
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
      // `fresh` is decided HERE, once per node per render, so the card and its connector agree about whether
      // this is an arrival — asking twice would mark it seen on the first ask and unseen on the second.
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
        // Centred on the span of its children's centres, so a group of three and a group of ten both hang
        // off the middle of their parent rather than off its top edge.
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
      // One connector PER CHILD, out of the parent's right edge and into the child's left edge, as a flat S
      // with horizontal tangents at both ends. Nothing is shared between siblings, so a connector can carry
      // its own child's state — and there is no bar to get out of step with the cards, which is what made
      // the previous shape look broken whenever a group was uneven.
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
          // A NEW branch draws itself: the line runs out to where the card is about to be, and the card pops
          // in behind it. `stroke-dasharray` set to the path's own length and animated to zero offset is the
          // standard way to do that, and it needs the length — which only the browser knows.
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

  /**
   * Ids already drawn, so a node that appears LATER can announce itself.
   *
   * A rebuild redraws everything; without this there is no way to tell "this agent just started" from "this
   * agent was already there", and either everything animates on every push (flicker) or nothing ever does.
   */
  var everSeen = {};

  // NB the arrival timings (connector draw, then card pop) live in the CSS next to the keyframes — see
  // `.dg-edge.dg-draw` and `.dg-card.dg-pop` in css/dashboard.css. One place, so they cannot drift apart.

  /** A monotonic clock for animation phases. `performance.now()` where it exists; never `Date.now()` alone. */
  function nowMs() {
    return Math.round(
      typeof performance !== 'undefined' && performance && typeof performance.now === 'function'
        ? performance.now()
        : new Date().getTime()
    );
  }

  /**
   * Whether this node is being drawn for the FIRST time, and records that it has been.
   *
   * Keyed by id, remembered for the life of the page: a node that keeps appearing in every rebuild is not
   * new, it is just still running.
   */
  function isNew(node) {
    var id = node && node.id != null ? String(node.id) : null;
    if (!id || everSeen[id]) return false;
    everSeen[id] = true;
    return true;
  }

  /** Makes [path] draw itself from its start to its end. No-op where `getTotalLength` does not exist. */
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
    // Same shared phase as the card it points at, so a rebuild does not restart the pulse — see the card's
    // `animationDelay`.
    if (running) path.style.animationDelay = '-' + (nowMs() % 1300) + 'ms';
    svg.appendChild(path);
    return path;
  }

  function diagramCard(p) {
    var n = p.node;
    return CC.h(
      'button',
      {
        // The STATUS is a class, so a node wears the same states — and the same animation — as the tool card
        // that represents it in the transcript. One visual language for one fact.
        class:
          'dg-card' +
          (n.kind ? ' ' + n.kind : '') +
          (n.status ? ' ' + n.status : '') +
          (n.selected ? ' selected' : '') +
          // A node that has just appeared zooms in, after its connector has finished drawing towards it.
          (p.fresh ? ' dg-pop' : ''),
        style: {
          left: p.x + 'px',
          top: p.y + 'px',
          width: p.w + 'px',
          height: NODE_H + 'px',
          // A NEGATIVE delay starts the running animation part-way through, at the phase a shared clock is
          // already at. Without it every rebuild — and the dashboard rebuilds several times a turn — restarts
          // `toolRun` from 0%, so the running nodes visibly jumped back to blue on each refresh. That was the
          // flicker; the animation itself is fine, it was being born again every second.
          animationDelay: n.status === 'running' ? '-' + (nowMs() % 1300) + 'ms' : null,
        },
        // [n.name] is the SPOKEN name, set only by a node whose visible label abbreviates (a background
        // task: `BT: …` on screen, `Background Task (…)` to a screen reader — see `CC.diagramShown`).
        // Without it the name is computed from the card's own text, which is exactly how the abbreviation
        // would have been read aloud. Absent, the computed name is right and nothing is overridden: an
        // explicit name on every card would silently drop `.dg-meta` from it.
        attrs: { type: 'button', title: n.title || n.label, 'aria-label': n.name || null },
        on: { click: n.onPick || function () {} },
      },
      n.status ? CC.h('span', { class: 'dg-dot ' + n.status, attrs: { 'aria-hidden': 'true' } }) : null,
      CC.h('span', { class: 'dg-label', text: n.label == null ? '' : String(n.label) }),
      // The type rides on the same line, dimmed: it qualifies the node, it is not the node. Two stacked
      // lines doubled every card's height for a word most of them share.
      n.meta ? CC.h('span', { class: 'dg-meta', text: String(n.meta) }) : null,
      // An action ON the node — Stop, for a task that is still running. Inside the card because that is where
      // the thing it acts on is; it stops the click from also navigating, since stopping is not going.
      n.action
        ? CC.h('span', {
            // The page's own button style (`btn`), plus one class for where it sits inside a node.
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

  /**
   * Wraps a diagram in a viewport you move by dragging, the way a diagram editor works.
   *
   * Not a scroll pane: a canvas wider than the tool window, inside a panel that itself scrolls, gives two
   * scrollbars and a fight over which one the wheel belongs to. A drag has neither problem.
   *
   * A drag is not a click — the pointer must travel more than `SLOP` before the canvas starts following, and
   * the click that ends a real drag is swallowed, so releasing over a card does not also open it.
   */
  /**
   * Where each named viewport was left — zoom and offset — so a repaint does not throw it away.
   *
   * The dashboard rebuilds its cards on every state push, several times a turn, and each rebuild made a new
   * viewport that re-fitted from scratch: pan somewhere, zoom in, and a second later you were back at the
   * start for no reason you could see. Keyed by name, remembered for the life of the page.
   */
  var viewState = {};

  CC.panView = function (canvas, label, key) {
    var SLOP = 4;
    var MIN_ZOOM = 0.4;
    var MAX_ZOOM = 2.5;
    /**
     * The smallest scale [fit] may use. Legibility beats seeing everything at once.
     *
     * At 0.55 a big tree technically "fitted" and could not be read — which is not fitting, it is hiding.
     * Past this the diagram stays readable and overflows instead, and overflowing is fine: you drag it.
     */
    var LEGIBLE = 0.9;
    var view = CC.h('div', { class: 'dg-view', attrs: { tabindex: '0', 'aria-label': label || 'Diagram' } });
    view.appendChild(canvas);
    var at = { x: 0, y: 0 };
    var zoom = 1;
    var from = null;
    var moved = false;

    function apply() {
      // Translate THEN scale, in that order: the origin is the canvas's top-left (`transform-origin: 0 0`),
      // so the pan stays in screen pixels and does not get multiplied by the zoom.
      canvas.style.transform =
        'translate(' + Math.round(at.x) + 'px,' + Math.round(at.y) + 'px) scale(' + zoom.toFixed(3) + ')';
      if (key) viewState[key] = { x: at.x, y: at.y, zoom: zoom };
    }

    /**
     * Scales and centres the diagram so the WHOLE of it is on screen, then stops touching it.
     *
     * Without this the canvas simply sat at 0,0 at scale 1: anything wider or taller than the viewport was
     * cut off, and a tree whose root happened to sit past the right edge showed as an empty panel with a few
     * stray connector ends — a black rectangle with nothing in it, which is exactly what it looked like.
     *
     * This is the one place the DOM is measured, and it is measured once, for the viewport only: the
     * diagram's own geometry is still pure arithmetic. Never scales UP past 1 — a two-node tree blown up to
     * fill a panel looks broken, not helpful.
     */
    function fit() {
      var vw = view.clientWidth;
      var vh = view.clientHeight;
      var cw = canvas.offsetWidth;
      var ch = canvas.offsetHeight;
      if (!vw || !vh || !cw || !ch) return; // not laid out yet; the caller retries on the next frame
      var pad = 16;
      zoom = Math.min(1, (vw - pad * 2) / cw, (vh - pad * 2) / ch);
      // Never below LEGIBLE. A diagram scaled to 0.2 to "fit" is a picture of some grey smudges: it fits and
      // it tells you nothing. Past this point the answer is to drag, which is what the canvas is for.
      if (!(zoom > 0) || zoom < LEGIBLE) zoom = Math.max(LEGIBLE, Math.min(1, zoom || 1));
      // Left-aligned, not centred: the tree reads left to right from its root, so the root is what has to be
      // on screen. Centring a wide diagram puts its middle in view and its beginning off the edge.
      at.x = cw * zoom <= vw - pad * 2 ? (vw - cw * zoom) / 2 : pad;
      at.y = ch * zoom <= vh - pad * 2 ? (vh - ch * zoom) / 2 : pad;
      apply();
    }

    /**
     * Fits ONLY the first time this viewport is shown; afterwards it restores where the user left it.
     *
     * The dashboard rebuilds its cards on every state push, so an unconditional fit meant the diagram jumped
     * back to its default zoom and position every couple of seconds while you were trying to read it.
     */
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
    // The caller inserts the view, then asks for a fit — it can only be computed once the browser has laid
    // the viewport out.
    view.__fit = fitOrRestore;

    view.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      from = { x: ev.clientX - at.x, y: ev.clientY - at.y, sx: ev.clientX, sy: ev.clientY };
      moved = false;
    });
    // Bound to the document, not the viewport: a drag that leaves the panel has to keep working, and has to
    // end even when the button comes up outside it.
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
    /**
     * The wheel zooms, ANCHORED AT THE POINTER.
     *
     * Anchoring is what makes it usable: zooming about the origin walks whatever you were looking at off the
     * screen, so you zoom in and then have to go hunting for it. Keeping the point under the cursor fixed is
     * one line of arithmetic — convert the cursor to canvas coordinates at the old scale, and put it back at
     * the new one — and it is the difference between a zoom and a surprise.
     *
     * `preventDefault` because the panel behind this scrolls: without it the wheel would zoom AND scroll the
     * dashboard out from under the diagram.
     */
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
    // Double-click on the background re-fits. Panning and zooming are easy to overdo, and without a way home
    // the diagram is simply gone — you cannot scroll back to something that does not scroll.
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
