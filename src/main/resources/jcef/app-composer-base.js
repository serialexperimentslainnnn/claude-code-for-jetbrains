/* app-composer-base.js — the composer family's shared plumbing.
 *
 * One subject: what every composer file needs before it can do anything — the `CC.composer` namespace (there
 * is no module system here, so that object IS the interface between these scripts), the local `h`/`send`
 * fallbacks, and the two pieces of state more than one of them reads.
 *
 * Load order inside the family is deliberate: this file FIRST (it creates the namespace), then the one-subject
 * modules, then `app-composer.js` LAST — the composer builds itself eagerly at the bottom of that file, so
 * everything `ensureBuilt` touches has to exist by the time it runs.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  // ---- small helpers (fall back if core's h/escape arrive late) -------------
  // `children` are read off `arguments` below (variadic), not from a named parameter.
  function h(tag, props) {
    if (CC && typeof CC.h === 'function') {
      var args = [tag, props || null];
      if (arguments.length > 2) {
        for (var i = 2; i < arguments.length; i++) args.push(arguments[i]);
      }
      return CC.h.apply(CC, args);
    }
    // minimal local fallback
    var el = document.createElement(tag);
    props = props || {};
    if (props.class) el.className = props.class;
    if (props.text != null) el.textContent = props.text;
    if (props.html != null) el.innerHTML = props.html;
    if (props.title != null) el.title = props.title;
    if (props.attrs)
      for (var k in props.attrs)
        if (Object.prototype.hasOwnProperty.call(props.attrs, k)) el.setAttribute(k, props.attrs[k]);
    if (props.on)
      for (var ev in props.on)
        if (Object.prototype.hasOwnProperty.call(props.on, ev)) el.addEventListener(ev, props.on[ev]);
    var rest = Array.prototype.slice.call(arguments, 2);
    for (var j = 0; j < rest.length; j++) {
      var c = rest[j];
      if (c == null) continue;
      if (Array.isArray(c)) {
        for (var m = 0; m < c.length; m++)
          if (c[m] != null) el.appendChild(typeof c[m] === 'string' ? document.createTextNode(c[m]) : c[m]);
      } else el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return el;
  }
  function send(obj) {
    if (CC && typeof CC.send === 'function') CC.send(obj);
  }
  CX.h = h;
  CX.send = send;

  // ---- shared state, one writer each ---------------------------------------
  /** The built composer's nodes — { card, input, send, pills, queue, ghost, readout, … }. Set by ensureBuilt. */
  CX.els = null;
  /** The last cc.state payload. Set by cc.state; read by the pill menus and the send/Escape handling. */
  CX.lastState = null;
  /** From cc.meta: native-Wayland toolkit → route paste through the host (wl-paste). Read by every field. */
  CX.hostClipboard = false;

  // ---- priority+ overflow ----------------------------------------------------
  /**
   * The ⋮ at the end of a control row: what does not fit is collected behind it.
   *
   * WHY IT IS HERE AND NOT WRITTEN TWICE. Both control rows have the same problem and it is the same problem:
   * the row above the prompt box (the ⚙, the views, the action icons) and the bar below it (the clip, the five
   * pills, the right-hand icons). A tool window is often three hundred pixels wide and `Opus 5 with 1M context`
   * next to `Bypass permissions` fills a row on its own, so the tail of each row simply sat outside the window.
   * Two implementations of one overflow is two behaviours a release later, which is why this is one helper with
   * two callers rather than a pattern each row follows.
   *
   * MEASURING IS SEPARATE FROM DECIDING, and that is a design line rather than a concession to the tests.
   * [CX.overflowMeasure] is the only part that reads layout, and it is replaceable; [overflowFit] is arithmetic
   * over numbers somebody else measured. jsdom lays nothing out — every rect is zero — so a decision entangled
   * with the DOM read is a decision no test can reach, and this one decides what the user can see.
   *
   * COLLECTED ITEMS ARE HIDDEN IN PLACE, NEVER MOVED INTO THE MENU. The DOM has no move: an `appendChild` of a
   * node already in the document is a remove plus an insert, so whatever is focused inside it is blurred —
   * measured in this repository, and the reason permission cards are reconciled the way they are. `moveBefore()`
   * exists for exactly this case and is not usable here: it is Chromium 133+ / Firefox 144+ with no Safari, so
   * it is not guaranteed by the embedded browser of the oldest IDE this plugin loads in, and jsdom does not
   * implement it — the fallback path would be the one no test ever runs. Hiding in place costs a menu entry
   * that PROXIES the control instead of being it, and that cost is paid once, here, by activating the button
   * itself: there is one handler, on the button, so the row and the menu cannot drift apart.
   *
   * A COLLECTED ITEM IS GONE FOR THE KEYBOARD TOO. `.cc-collapsed` is `display: none`, so the control leaves
   * the tab order and the accessibility tree; `visibility` or `opacity: 0` would leave a control nobody can see
   * in both. The class is ours alone and is deliberately NOT the `hidden` attribute: `hidden` already has
   * owners on these rows — `renderPills` hides an unlabelled pill, the dashboard hides Plan and Git until the
   * session has them — and one attribute with two writers is decided by whichever wrote last.
   *
   * WHAT IS NEVER COLLECTED IS DECLARED BY THE CALLER, not guessed here. `reserved` names the controls whose
   * width is spent before anything is offered space: the send button is the screen's primary action and may
   * neither end up behind a ⋮ nor give up a pixel of its own width to make room for a pill.
   */

  /** The one open ⋮ menu on the page — two rows' worth of it open at once is two answers to one gesture. */
  var openOverflow = null;
  /** Every row wired for overflow, in creation order. [CX.refreshOverflow] updates them all. */
  var rows = [];

  /**
   * Which leading items fit — pure arithmetic, no DOM.
   *
   * `available` is the row's content width; `ends[i]` is how much of it the run consumes up to and including
   * item i (gaps included, because it is a right edge and not a width); `reserved` is everything after the run
   * that is not the toggle; `toggle` is the ⋮ itself. The toggle is in the budget because a ⋮ that is not paid
   * for is a ⋮ that overflows the row it was added to fix — one item too many stays, and the button that was
   * supposed to reach the rest is the thing hanging off the edge.
   *
   * `overflowing` is measured, never derived from these numbers: while the row has free space its right-hand
   * group is pushed flush against the end by an auto margin, which inflates the last `ends` entry to about the
   * full width and makes "does it fit" a sub-pixel coin toss. Whether a row overflows is a fact the browser
   * already knows.
   *
   * A measurement taken with the ⋮ already in the row (`toggle > 0`) can also answer the question that gets a
   * row OUT of overflow: everything fits once the ⋮ goes. Without that branch the button would be its own
   * reason to exist — the row overflows because of the width the ⋮ occupies, so the ⋮ stays, so the row
   * overflows.
   */
  function overflowFit(m) {
    var ends = m && m.ends ? m.ends : [];
    var n = ends.length;
    if (!m || !m.overflowing) return { visible: n, toggle: false };
    var used = n ? ends[n - 1] : 0;
    if (m.toggle && used + (m.reserved || 0) <= m.available) return { visible: n, toggle: false };
    var budget = m.available - (m.reserved || 0) - (m.toggle || 0);
    var k = 0;
    while (k < n && ends[k] <= budget) k++;
    return { visible: k, toggle: true };
  }
  CX.overflowFit = overflowFit;

  /**
   * The thin layer that reads the row. Everything laid out and the toggle attached — the caller guarantees it.
   *
   * The tail is measured as a DISTANCE (from the last item's right edge to the right edge of the last reserved
   * control) rather than assembled from widths and gaps: padding, `column-gap` and the gap an auto margin
   * leaves behind are all in it, and none of them has to be read out of a computed style. Positions stay
   * meaningful even while the row overflows, because the controls are laid out in sequence whether or not the
   * result fits — what is clipped is the paint, not the geometry.
   *
   * On `CX` and not local, because it is the seam: a test with no layout engine substitutes this one function
   * and drives everything above it for real.
   */
  CX.overflowMeasure = function (row, items, reserved, toggle) {
    var rowRect = row.getBoundingClientRect();
    var left = rowRect.left + (row.clientLeft || 0);
    var ends = [];
    var lastRight = left;
    for (var i = 0; i < items.length; i++) {
      var r = items[i].getBoundingClientRect();
      ends.push(r.right - left);
      lastRight = r.right;
    }
    var toggleWidth = toggle ? toggle.getBoundingClientRect().width : 0;
    var tailEnd = toggle ? toggle.getBoundingClientRect().right : lastRight;
    for (var j = 0; j < reserved.length; j++) {
      var edge = reserved[j].getBoundingClientRect().right;
      if (edge > tailEnd) tailEnd = edge;
    }
    return {
      available: row.clientWidth,
      // The browser's own answer, and the only one that is not a rounding argument. It needs the row to be
      // `nowrap` and to clip: a wrapping row never overflows, it just grows downwards (composer.css).
      overflowing: row.scrollWidth > row.clientWidth + 1,
      ends: ends,
      reserved: Math.max(0, tailEnd - lastRight - toggleWidth),
      toggle: toggleWidth,
    };
  };

  /** The name a collected control carries into the menu — its own, never a second one invented here. */
  function overflowLabel(el) {
    var name = (el.getAttribute && el.getAttribute('aria-label')) || el.title || el.textContent || '';
    return String(name).replace(/\s+/g, ' ').trim();
  }

  function dotsGlyph() {
    return (
      '<svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor" aria-hidden="true">' +
      '<circle cx="8" cy="3.4" r="1.35"/><circle cx="8" cy="8" r="1.35"/><circle cx="8" cy="12.6" r="1.35"/>' +
      '</svg>'
    );
  }

  /**
   * Wires one row for overflow. Idempotent per row — a second call returns the instance the first one made.
   *
   * opts: { row, items(), reserved(), place(btn), label, activate(el, anchor) }
   *   · `items()` is re-read on every pass: `#views` is filled by the dashboard on its own schedule, and a
   *     snapshot taken at build time would overflow a row it no longer describes.
   *   · `place(btn)` decides where the ⋮ lands. It is attached only while it is needed — a disabled ⋮, or a
   *     gap held open for one, is a control that says "there is more" when there is not.
   *   · `activate(el, anchor)` handles the controls that cannot simply be clicked: one that opens a popup
   *     anchors it to ITSELF, and a `display: none` anchor has no position, so its menu would open in the
   *     page's corner. Returning false falls back to activating the real button, which is the whole point.
   */
  CX.createOverflow = function (opts) {
    var row = opts.row;
    if (!row) return null;
    if (row.__ccOverflow) return row.__ccOverflow;

    var toggle = h('button', {
      class: 'bar-icon overflow-btn',
      title: opts.label,
      attrs: {
        type: 'button',
        'aria-label': opts.label,
        'aria-haspopup': 'menu',
        'aria-expanded': 'false',
      },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          if (openOverflow && openOverflow.owner === api) closeMenu(true);
          else openMenu();
        },
        keydown: function (e) {
          // Down-arrow opens onto the first entry, the way a platform popup does. A real <button> already
          // answers Enter and Space.
          if (e.key === 'ArrowDown' || e.key === 'Down') {
            e.preventDefault();
            if (!(openOverflow && openOverflow.owner === api)) openMenu();
          }
        },
      },
    });
    toggle.innerHTML = dotsGlyph();

    var lastSig = null; // what was drawn last, so an identical push is not measured, let alone applied
    var collectedSig = ''; // which controls are behind the ⋮ — a change of THIS is what invalidates the menu
    var collected = [];
    var busy = false;

    /** The controls this row may collect: its own, minus the ⋮, minus anything its owner has hidden. */
    function candidates() {
      var all = opts.items() || [];
      var out = [];
      for (var i = 0; i < all.length; i++) {
        if (all[i] && all[i] !== toggle && !all[i].hidden) out.push(all[i]);
      }
      return out;
    }

    /** Everything that decides the outcome except the row's width — labels move, and a label is a width. */
    function signature(list) {
      var sig = list.length + '|';
      for (var i = 0; i < list.length; i++) {
        sig += overflowLabel(list[i]) + (list[i].disabled ? '#' : '') + '|';
      }
      return sig;
    }

    function setCollapsed(el, on) {
      if (el.classList.contains('cc-collapsed') !== on) el.classList.toggle('cc-collapsed', on);
    }

    function detachToggle() {
      if (toggle.parentNode) toggle.parentNode.removeChild(toggle);
    }

    /**
     * One pass: show everything, ask the row, hide the tail that does not fit.
     *
     * `force` is what a resize passes — the signature only covers the CONTENT, so a row that is narrower with
     * exactly the same controls in it has to be able to say so.
     *
     * The ⋮ IS LEFT WHERE IT IS unless the answer changes, and that is not tidiness. Detaching it to measure
     * "what would fit without it" and putting it back would be a remove plus an insert on every state push —
     * several a turn — so a reader who had tabbed to it would lose the focus to the `<body>` mid-turn, and the
     * open menu would lose its anchor. So the pass measures the row AS IT STANDS and [overflowFit] answers
     * both directions from that one reading; the second reading is paid only on the transition INTO overflow,
     * where the button does not exist yet and cannot be holding anything.
     */
    function update(force) {
      if (busy) return;
      var list = candidates();
      var sig = signature(list);
      if (!force && sig === lastSig) return;
      busy = true;
      try {
        lastSig = sig;
        for (var i = 0; i < list.length; i++) setCollapsed(list[i], false);
        var reserved = (opts.reserved && opts.reserved()) || [];
        var attached = !!toggle.parentNode;
        var plan = overflowFit(CX.overflowMeasure(row, list, reserved, attached ? toggle : null));
        if (plan.toggle && !attached) {
          opts.place(toggle);
          plan = overflowFit(CX.overflowMeasure(row, list, reserved, toggle));
        }
        apply(list, plan);
      } finally {
        busy = false;
      }
    }

    function apply(list, plan) {
      collected = list.slice(plan.visible);
      for (var i = plan.visible; i < list.length; i++) setCollapsed(list[i], true);
      /** The last control that survived the cut — the fallback for both of the focus rescues below. */
      var lastStanding = list[plan.visible - 1];
      if (!plan.toggle && toggle.parentNode) {
        // Focus is rescued before the node goes: the row has just grown, so the last control that stayed is
        // where the reader was heading anyway. Letting it fall to the `<body>` is WCAG 2.4.3 in the one
        // direction nobody notices in review — it only happens on a resize.
        var held = document.activeElement === toggle;
        detachToggle();
        if (held && lastStanding && lastStanding.focus) lastStanding.focus();
      }
      var sig = signature(collected);
      if (sig === collectedSig) return;
      collectedSig = sig;
      // The menu is a picture of a set that just changed under the reader. Rebuilding it in place would be a
      // second reconciliation to get right; closing it is honest and costs one press. Focus goes back to the
      // ⋮ if there still is one, and to the last control that survived if there is not — but only if it was
      // IN the menu: a push can land while the reader is typing in the prompt box with the menu still open
      // behind them, and a close is allowed to give back the focus it held, never to take one.
      if (openOverflow && openOverflow.owner === api) {
        var inside = openOverflow.el.contains(document.activeElement);
        closeMenu(false);
        // Not the same destination as the rescue above, which is why it is not the same name: there the ⋮ is
        // the node being removed and cannot be where the focus goes, here it is the first choice.
        var returnTo = plan.toggle ? toggle : lastStanding;
        if (inside && returnTo && returnTo.focus) returnTo.focus();
      }
    }

    // ---- the menu -------------------------------------------------------------
    /**
     * The same popup language as the ⚙ and the pills — `.menu` rows, `role="menu"`, a roving tabindex, arrows,
     * Home/End, Escape and Tab, dismissed by a capture-phase mousedown. A second popup dialect two centimetres
     * from the first is what those rules were written to end, and ARIA that announces a widget it does not
     * behave like is worse than no ARIA at all.
     */
    function entries() {
      if (!openOverflow || openOverflow.owner !== api) return [];
      return Array.prototype.slice.call(openOverflow.el.querySelectorAll('[role="menuitem"]'));
    }

    function focusEntry(el) {
      var all = entries();
      for (var i = 0; i < all.length; i++) all[i].setAttribute('tabindex', all[i] === el ? '0' : '-1');
      if (el) el.focus();
    }

    function step(delta) {
      var all = entries();
      if (!all.length) return;
      var at = all.indexOf(document.activeElement);
      focusEntry(all[at < 0 ? (delta > 0 ? 0 : all.length - 1) : (at + delta + all.length) % all.length]);
    }

    function onMenuKey(e) {
      if (e.key === 'Escape' || e.key === 'Esc') {
        // Owned here and stopped here: the composer reads a stray Escape as "interrupt the running turn".
        e.preventDefault();
        e.stopPropagation();
        closeMenu(true);
      } else if (e.key === 'ArrowDown' || e.key === 'Down') {
        e.preventDefault();
        step(1);
      } else if (e.key === 'ArrowUp' || e.key === 'Up') {
        e.preventDefault();
        step(-1);
      } else if (e.key === 'Home') {
        e.preventDefault();
        focusEntry(entries()[0]);
      } else if (e.key === 'End') {
        e.preventDefault();
        focusEntry(entries()[entries().length - 1]);
      } else if (e.key === 'Tab') {
        // Not swallowed: the browser then carries on from the ⋮, which is still in the document, rather than
        // from the <body> a removed row leaves behind.
        closeMenu(true);
      }
    }

    function entryFor(el) {
      var disabled = !!el.disabled;
      var item = h(
        'div',
        {
          class: 'menu-item',
          attrs: disabled
            ? { role: 'menuitem', tabindex: '-1', 'aria-disabled': 'true' }
            : { role: 'menuitem', tabindex: '-1' },
          title: overflowLabel(el),
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              if (disabled) return;
              // Closed FIRST, and with the focus handed back to the ⋮, so a control that opens a popup of its
              // own has a live, positioned anchor to hang it from.
              closeMenu(true);
              if (!(opts.activate && opts.activate(el, toggle))) el.click();
            },
          },
        },
        h('span', { class: 'menu-item-label', text: overflowLabel(el) })
      );
      return item;
    }

    function openMenu() {
      if (!collected.length) return;
      // Every other popup in the family closes first — they all hang off these two rows.
      if (CX.closeMenu) CX.closeMenu();
      if (openOverflow) openOverflow.owner.close(false);
      var menu = h('div', { class: 'menu', attrs: { role: 'menu', 'aria-label': opts.label } });
      // In collection order, which is row order: a menu that reorders makes the reader search for what was
      // just on screen a moment ago.
      for (var i = 0; i < collected.length; i++) menu.appendChild(entryFor(collected[i]));
      menu.addEventListener('keydown', onMenuKey);
      // On `document.body` like every other popup here: the rows live inside `#dock`, which clips, and inside
      // `#work`, which is a stacking context with declared ranks. `positionMenu` clamps it to the tool window.
      document.body.appendChild(menu);
      openOverflow = { el: menu, owner: api };
      toggle.setAttribute('aria-expanded', 'true');
      if (CX.positionMenu) CX.positionMenu(menu, toggle);
      focusEntry(entries()[0]);
    }

    function closeMenu(returnFocus) {
      if (!openOverflow || openOverflow.owner !== api) return;
      if (openOverflow.el.parentNode) openOverflow.el.parentNode.removeChild(openOverflow.el);
      openOverflow = null;
      toggle.setAttribute('aria-expanded', 'false');
      if (returnFocus && toggle.parentNode) toggle.focus();
    }

    var api = {
      update: update,
      close: closeMenu,
      /** The ⋮ itself and what is behind it — read by the tests, and by nothing else. */
      toggle: toggle,
      collected: function () {
        return collected.slice();
      },
    };
    row.__ccOverflow = api;
    rows.push(api);

    /**
     * The row's width, watched where it actually changes.
     *
     * `ResizeObserver` and not `window.resize`: the tool window is resized without the window being touched,
     * and a docked panel changes width on its own. The classic loop — collect an item, the row's width
     * changes, the observer fires again — is closed from two sides. Structurally: the row is `nowrap` and
     * clips, so its width comes from its parent and NOTHING this file does can change it. And defensively:
     * a notification whose width matches the last one is dropped, and a pass already in flight is not
     * re-entered. Resizing the observed element inside the callback is what earns the spec's deferral and its
     * "ResizeObserver loop completed with undelivered notifications" error, and neither guard depends on a
     * timer — there is nothing here to wait for.
     */
    if (typeof ResizeObserver === 'function') {
      var lastWidth = -1;
      new ResizeObserver(function (list) {
        var w = list && list[0] && list[0].contentRect ? list[0].contentRect.width : row.clientWidth;
        if (Math.abs(w - lastWidth) < 0.5) return;
        lastWidth = w;
        update(true);
      }).observe(row);
    }

    update(true);
    return api;
  };

  /** Re-run every wired row. Called at the end of a state render: an unchanged row does nothing at all. */
  CX.refreshOverflow = function (force) {
    for (var i = 0; i < rows.length; i++) rows[i].update(force);
  };

  // Outside click dismisses, capture-phase, the same shape as the pill and ⚙ menus so the press that opens
  // another popup closes this one before that popup is built.
  document.addEventListener(
    'mousedown',
    function (e) {
      if (!openOverflow) return;
      if (openOverflow.el.contains(e.target)) return;
      if (openOverflow.owner.toggle.contains(e.target)) return;
      openOverflow.owner.close(false);
    },
    true
  );
})();
