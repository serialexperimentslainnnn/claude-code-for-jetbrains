/* app-session-base.js — the dashboard family's shared plumbing.
 *
 * One subject: what every dashboard file needs before it can build anything — the `CC.dash` namespace (there
 * is no module system here, so that object IS the interface between these scripts), the safe accessors onto
 * app-core, and the formatting helpers the cards render their numbers with.
 *
 * The namespace is `CC.dash`, not `CC.session`: the method the host calls is `cc.session`, and two objects one
 * letter apart (`cc` / `CC`) each holding a `session` is a trap rather than an interface.
 *
 * Load order inside the family is deliberate: this file FIRST (it creates the namespace), then the card
 * modules, then `app-session.js` LAST — the panel builds itself eagerly at the bottom of that file, so
 * everything its first render touches has to exist by the time it runs.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});

  // ---- Safe accessors --------------------------------------------------------
  function core() {
    return window.CC || null;
  }
  function conversation() {
    var c = core();
    return (c && c.els && c.els.conversation) || document.getElementById('conversation') || null;
  }
  function appRoot() {
    var c = core();
    return (c && c.els && c.els.app) || document.getElementById('app') || document.body || null;
  }
  function h() {
    var c = core();
    if (c && typeof c.h === 'function') return c.h.apply(c, arguments);
    return null;
  }
  function send(obj) {
    var c = core();
    if (c && typeof c.send === 'function') c.send(obj);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  function num(v) {
    return typeof v === 'number' && isFinite(v) ? v : null;
  }

  function fmtInt(v) {
    var n = num(v);
    if (n == null) return null;
    try {
      return Math.round(n).toLocaleString();
    } catch (e) {
      return String(Math.round(n));
    }
  }

  function fmtUsd(v) {
    var n = num(v);
    if (n == null) return null;
    return '$' + n.toFixed(n < 1 ? 4 : 2);
  }

  function statRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: String(value) })
    );
  }

  // `wide` cards span the whole grid row (.dash-card.wide { grid-column: 1 / -1 }). Use it for anything with rows
  // that need horizontal room — the context legend, and the server/task lists whose name column would otherwise
  // collapse to an ellipsis inside a 260px column.
  /**
   * A dashboard card. [anchor], when given, tags it `data-card="…"` — which is how the stylesheet reaches
   * ONE card without a class of its own (`.dash-card[data-card='workloads']` gives the diagram the height a
   * grid row would otherwise deny it). It was a scroll target back when the buttons scrolled one long panel;
   * the views are exclusive now, and the tag survives because the CSS still needs to name that card.
   */
  function card(title, body, wide, anchor) {
    // body may be a node, an array of nodes, or empty. Hide when nothing renders.
    var children = [];
    if (Array.isArray(body)) {
      for (var i = 0; i < body.length; i++) {
        if (body[i]) children.push(body[i]);
      }
    } else if (body) {
      children.push(body);
    }
    if (!children.length) return null;
    var head = h('div', { class: 'dash-title', text: title });
    var props = { class: 'dash-card' + (wide ? ' wide' : '') };
    if (anchor) props.attrs = { 'data-card': anchor };
    return h('div', props, head, children);
  }

  D.core = core;
  D.conversation = conversation;
  D.appRoot = appRoot;
  D.h = h;
  D.send = send;
  D.num = num;
  D.fmtInt = fmtInt;
  D.fmtUsd = fmtUsd;
  D.statRow = statRow;
  D.card = card;

  /**
   * Leaves the dashboard if it is open — assigned by the shell (`app-session.js`), which owns the panel's
   * visibility, and a no-op until then.
   *
   * The Workloads diagram needs it: going to an agent means LEAVING the panel that is covering the transcript
   * you were sent to read (see [revealAndLeave]), and the panel is not this file's to close.
   */
  D.leaveDashboard = function () {};
})();
