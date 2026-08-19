(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  function h(tag, props) {
    if (CC && typeof CC.h === 'function') {
      var args = [tag, props || null];
      if (arguments.length > 2) {
        for (var i = 2; i < arguments.length; i++) args.push(arguments[i]);
      }
      return CC.h.apply(CC, args);
    }
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

  CX.els = null;
  CX.lastState = null;
  CX.hostClipboard = false;

  var openOverflow = null;
  var rows = [];

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
      overflowing: row.scrollWidth > row.clientWidth + 1,
      ends: ends,
      reserved: Math.max(0, tailEnd - lastRight - toggleWidth),
      toggle: toggleWidth,
    };
  };

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
          if (e.key === 'ArrowDown' || e.key === 'Down') {
            e.preventDefault();
            if (!(openOverflow && openOverflow.owner === api)) openMenu();
          }
        },
      },
    });
    toggle.innerHTML = dotsGlyph();

    var lastSig = null;
    var collectedSig = '';
    var collected = [];
    var busy = false;

    function candidates() {
      var all = opts.items() || [];
      var out = [];
      for (var i = 0; i < all.length; i++) {
        if (all[i] && all[i] !== toggle && !all[i].hidden) out.push(all[i]);
      }
      return out;
    }

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
      var lastStanding = list[plan.visible - 1];
      if (!plan.toggle && toggle.parentNode) {
        var held = document.activeElement === toggle;
        detachToggle();
        if (held && lastStanding && lastStanding.focus) lastStanding.focus();
      }
      var sig = signature(collected);
      if (sig === collectedSig) return;
      collectedSig = sig;
      if (openOverflow && openOverflow.owner === api) {
        var inside = openOverflow.el.contains(document.activeElement);
        closeMenu(false);
        var returnTo = plan.toggle ? toggle : lastStanding;
        if (inside && returnTo && returnTo.focus) returnTo.focus();
      }
    }

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
      if (CX.closeMenu) CX.closeMenu();
      if (openOverflow) openOverflow.owner.close(false);
      var menu = h('div', { class: 'menu', attrs: { role: 'menu', 'aria-label': opts.label } });
      for (var i = 0; i < collected.length; i++) menu.appendChild(entryFor(collected[i]));
      menu.addEventListener('keydown', onMenuKey);
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
      toggle: toggle,
      collected: function () {
        return collected.slice();
      },
    };
    row.__ccOverflow = api;
    rows.push(api);

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

  CX.refreshOverflow = function (force) {
    for (var i = 0; i < rows.length; i++) rows[i].update(force);
  };

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
