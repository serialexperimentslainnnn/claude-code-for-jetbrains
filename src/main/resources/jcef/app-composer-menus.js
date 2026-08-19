(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  CX.openMenu = null;

  function currentOptions(def) {
    if (!CX.lastState) return [];
    var f = CX.lastState[def.field];
    if (!f || !Array.isArray(f.options)) return [];
    return f.options;
  }

  function menuSig(def) {
    var opts = currentOptions(def);
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
      return item;
    }

    var main = [];
    var other = [];
    for (var i = 0; i < opts.length; i++) {
      (opts[i].group === 'other' ? other : main).push(opts[i]);
    }
    for (var j = 0; j < main.length; j++) menu.appendChild(optionItem(main[j]));

    if (other.length) {
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
              e.stopPropagation();
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
    if (top < margin) top = r.bottom + 6;
    if (top + mh > window.innerHeight - margin) top = Math.max(margin, window.innerHeight - mh - margin);
    menu.style.left = left + 'px';
    menu.style.top = Math.round(top) + 'px';
  }
  CX.positionMenu = positionMenu;

  function chooseOption(def, o) {
    closeMenu();
    var msg = def.msg(o);
    send(msg);
  }

  function closeMenu() {
    if (!CX.openMenu) return;
    if (CX.openMenu.el && CX.openMenu.el.parentNode) CX.openMenu.el.parentNode.removeChild(CX.openMenu.el);
    if (CX.openMenu.anchor) CX.openMenu.anchor.classList.remove('pill-open');
    CX.openMenu = null;
  }
  CX.closeMenu = closeMenu;

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
