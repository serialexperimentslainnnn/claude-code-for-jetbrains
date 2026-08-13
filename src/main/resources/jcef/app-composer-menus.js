/* app-composer-menus.js — the floating popup menus.
 *
 * One subject: the single popup the composer may have open at a time — where it is placed, what closes it,
 * and the pill menu itself (options, the "Other models" group, and what a choice sends). The attach menu is
 * a different content in the same popup, so it borrows `CX.openMenu`/`CX.closeMenu`/`CX.positionMenu` from
 * here and renders itself in app-composer-attach.js.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  /** The one open popup: { el, pill, anchor, sig } — or null. The whole family reads and clears it. */
  CX.openMenu = null;

  // ---- pill menus -----------------------------------------------------------
  function currentOptions(def) {
    if (!CX.lastState) return [];
    var f = CX.lastState[def.field];
    if (!f || !Array.isArray(f.options)) return [];
    return f.options;
  }

  // A cheap signature of which option is selected for a pill, so renderState only rebuilds an OPEN menu when the
  // selection actually changed — not on every (frequent, streaming) state push, which made the menu flicker and
  // dropped the highlighted item under the cursor.
  function menuSig(def) {
    var opts = currentOptions(def);
    // Key on BOTH the option set (labels) and the selection, so the open menu also rebuilds when the option list
    // itself changes mid-stream (e.g. system/init adds/removes a model), not only when the selection flips.
    var sig = opts.length + '|';
    for (var i = 0; i < opts.length; i++) {
      sig += (opts[i].selected ? '1' : '0') + (opts[i].label != null ? String(opts[i].label) : '') + '';
    }
    return sig;
  }
  CX.menuSig = menuSig;

  CX.togglePillMenu = function (def, anchorEl) {
    if (CX.openMenu && CX.openMenu.pill === def.key) {
      closeMenu();
      return;
    }
    closeMenu();
    var opts = currentOptions(def);
    if (!opts.length) return;

    var menu = h('div', { class: 'menu', attrs: { role: 'listbox' } });

    /** One selectable row. Shared by the main list and the "Other models" group so they cannot drift apart. */
    function optionItem(o) {
      var item = h(
        'div',
        {
          class: 'menu-item' + (o.selected ? ' selected' : ''),
          attrs: { role: 'option' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              chooseOption(def, o);
            },
          },
        },
        h('span', { class: 'menu-item-label', text: o.label != null ? String(o.label) : '' })
      );
      // The selected ✓ is drawn by CSS (.menu-item.selected::after) — don't ALSO append a span here, or the
      // chosen item shows two ticks.
      return item;
    }

    // The host tags previous-generation models with group:'other' (JcefState.modelJson). Everything untagged
    // stays in the flat list exactly as before, so no other pill's menu changes shape.
    var main = [];
    var other = [];
    for (var i = 0; i < opts.length; i++) {
      (opts[i].group === 'other' ? other : main).push(opts[i]);
    }
    for (var j = 0; j < main.length; j++) menu.appendChild(optionItem(main[j]));

    if (other.length) {
      // Expanded IN PLACE rather than as a second floating panel: this menu is already position-clamped to a
      // narrow tool window, and a flyout would need its own edge handling to avoid opening off-screen. It
      // starts open when the current selection lives inside it, so the ✓ is never hidden behind a collapsed row.
      var expanded = other.some(function (o) {
        return o.selected;
      });
      var group = h('div', { class: 'menu-group' + (expanded ? ' open' : '') });
      var items = h('div', { class: 'menu-group-items' });
      var header = h(
        'div',
        {
          class: 'menu-item menu-group-header',
          attrs: { role: 'button', 'aria-expanded': expanded ? 'true' : 'false' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation(); // never let this reach the document handler that closes the menu
              var nowOpen = !group.classList.contains('open');
              group.classList.toggle('open', nowOpen);
              header.setAttribute('aria-expanded', nowOpen ? 'true' : 'false');
              if (CX.openMenu && CX.openMenu.anchor) positionMenu(menu, CX.openMenu.anchor);
            },
          },
        },
        h('span', { class: 'menu-item-label', text: 'Other models' }),
        h('span', { class: 'menu-group-caret' })
      );
      for (var k = 0; k < other.length; k++) items.appendChild(optionItem(other[k]));
      group.appendChild(header);
      group.appendChild(items);
      menu.appendChild(group);
    }

    document.body.appendChild(menu);
    menu.style.minWidth = Math.round(anchorEl.getBoundingClientRect().width) + 'px';
    positionMenu(menu, anchorEl);

    anchorEl.classList.add('pill-open');
    CX.openMenu = { el: menu, pill: def.key, anchor: anchorEl, sig: menuSig(def) };
  };

  // Place a fixed popup above its anchor by default, clamped to the viewport on all sides so
  // it never spills outside the (often narrow) tool window.
  function positionMenu(menu, anchorEl) {
    var r = anchorEl.getBoundingClientRect();
    var margin = 8;
    menu.style.position = 'fixed';
    menu.style.maxWidth = window.innerWidth - margin * 2 + 'px';
    var mw = menu.offsetWidth;
    var mh = menu.offsetHeight;
    var left = Math.min(Math.round(r.left), window.innerWidth - mw - margin);
    if (left < margin) left = margin;
    var top = r.top - mh - 6;
    if (top < margin) top = r.bottom + 6; // flip below if no room above
    if (top + mh > window.innerHeight - margin) top = Math.max(margin, window.innerHeight - mh - margin);
    menu.style.left = left + 'px';
    menu.style.top = Math.round(top) + 'px';
  }
  CX.positionMenu = positionMenu;

  function chooseOption(def, o) {
    closeMenu();
    var msg = def.msg(o);
    // §COMPOSER: effort value null → send {type:'changeEffort'} with value:null (acceptable)
    send(msg);
  }

  function closeMenu() {
    if (!CX.openMenu) return;
    if (CX.openMenu.el && CX.openMenu.el.parentNode) CX.openMenu.el.parentNode.removeChild(CX.openMenu.el);
    if (CX.openMenu.anchor) CX.openMenu.anchor.classList.remove('pill-open');
    CX.openMenu = null;
  }
  CX.closeMenu = closeMenu;

  // outside-click closes any open pill menu (Esc is handled by the composer's own key handler)
  document.addEventListener(
    'mousedown',
    function (e) {
      if (CX.openMenu) {
        if (CX.openMenu.el && CX.openMenu.el.contains(e.target)) return;
        if (CX.openMenu.anchor && CX.openMenu.anchor.contains(e.target)) return;
        closeMenu();
      }
    },
    true
  );
})();
