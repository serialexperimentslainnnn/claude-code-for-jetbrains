(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  var payload = null;
  var btn = null;
  var menu = null;
  var drawnSig = '';

  var view = null;

  var slide = '';

  var groupSeq = 0;

  var SEP = String.fromCharCode(31);

  var DEFER_NOTE = 'Applies to new chats';

  var WRENCH =
    '<svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor" aria-hidden="true">' +
    '<path transform="rotate(-45 8 8)" d="M3.4 3.6 Q3.4 1.6 5.4 1.6 L6.2 1.6 L6.2 4.9 Q6.2 6.3 8 6.3 Q9.8 6.3 9.8 4.9 L9.8 1.6 ' +
    'L10.6 1.6 Q12.6 1.6 12.6 3.6 L12.6 5.4 Q12.6 8.4 9.9 8.9 L9.9 13.1 Q9.9 14.4 8 14.4 ' +
    'Q6.1 14.4 6.1 13.1 L6.1 8.9 Q3.4 8.4 3.4 5.4 Z"/></svg>';

  function items() {
    var list = payload && Array.isArray(payload.items) ? payload.items : [];
    return list.filter(function (it) {
      return it && it.key != null;
    });
  }

  function labelOf(it) {
    return it.label != null ? String(it.label) : String(it.key);
  }

  function groupOf(it) {
    return it.group != null ? String(it.group) : '';
  }

  function subOf(it) {
    return it.sub != null ? String(it.sub) : '';
  }

  function isRadio(it) {
    return String(it.type || 'check') === 'radio';
  }

  function bucket(list, keyOf) {
    var order = [];
    var byKey = {};
    list.forEach(function (it) {
      var name = keyOf(it);
      if (!Object.prototype.hasOwnProperty.call(byKey, name)) {
        byKey[name] = [];
        order.push(name);
      }
      byKey[name].push(it);
    });
    return order.map(function (name) {
      return { name: name, list: byKey[name] };
    });
  }

  function groups() {
    return bucket(items(), groupOf).map(function (g) {
      var bySub = bucket(g.list, subOf);
      var direct = [];
      var subs = [];
      bySub.forEach(function (s) {
        if (s.name === '') direct = s.list;
        else subs.push(s);
      });
      return { name: g.name, list: g.list, direct: direct, subs: subs };
    });
  }

  function panelFor(path) {
    var parts = String(path).split(SEP);
    var g = null;
    groups().forEach(function (x) {
      if (x.name === parts[0]) g = x;
    });
    if (!g) return null;
    if (parts.length < 2) {
      return { group: g.name, title: g.name, path: g.name, rows: g.direct, subs: g.subs, list: g.list };
    }
    var s = null;
    g.subs.forEach(function (x) {
      if (x.name === parts[1]) s = x;
    });
    if (!s) return null;
    return { group: g.name, title: s.name, path: path, rows: s.list, subs: [], list: s.list };
  }

  function buildButton() {
    var el = h('button', {
      class: 'bar-icon settings-btn',
      title: 'Chat settings',
      attrs: {
        type: 'button',
        'aria-label': 'Chat settings',
        'aria-haspopup': 'menu',
        'aria-expanded': 'false',
      },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          if (menu) close(true);
          else open();
        },
        keydown: function (e) {
          if (e.key === 'ArrowDown' || e.key === 'Down') {
            e.preventDefault();
            if (!menu) open();
          }
        },
      },
    });
    el.innerHTML = WRENCH;
    return el;
  }

  CX.mountSettingsButton = function () {
    var views = document.getElementById('views');
    if (!views) return null;
    if (!btn) btn = buildButton();
    if (views.firstChild !== btn) views.insertBefore(btn, views.firstChild);
    return btn;
  };

  function settingRow(it, group) {
    var radio = isRadio(it);
    var on = !!it.on;
    var label = labelOf(it);
    var row = h(
      'button',
      {
        class: 'menu-item settings-item' + (on ? ' selected' : ''),
        title: label,
        attrs: {
          type: 'button',
          role: radio ? 'menuitemradio' : 'menuitemcheckbox',
          'aria-checked': on ? 'true' : 'false',
          tabindex: '-1',
        },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            choose(it, row, radio);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: label })
    );
    row.__ccKey = String(it.key);
    row.__ccGroup = group;
    row.__ccFocusId = 'k' + SEP + row.__ccKey;
    return row;
  }

  function choose(it, row, radio) {
    var already = row.getAttribute('aria-checked') === 'true';
    if (radio) {
      if (already) return;
      clearGroup(row.__ccGroup, row.__ccKey);
      it.on = true;
      applyState(row, true);
      CC.send({ type: 'settingsToggle', key: String(it.key), on: true });
      if (CC.announce) CC.announce(labelOf(it) + ' selected');
      return;
    }
    if (it.hostOwned) {
      CC.send({ type: 'settingsToggle', key: String(it.key), on: !already });
      return;
    }
    it.on = !already;
    applyState(row, !already);
    CC.send({ type: 'settingsToggle', key: String(it.key), on: !already });
    if (CC.announce) CC.announce(labelOf(it) + (already ? ' off' : ' on'));
  }

  function clearGroup(group, keptKey) {
    allRows().forEach(function (r) {
      if (r.getAttribute('role') !== 'menuitemradio') return;
      if (r.__ccGroup === group && r.__ccKey !== keptKey) applyState(r, false);
    });
    items().forEach(function (o) {
      if (groupOf(o) === group && isRadio(o) && String(o.key) !== keptKey) o.on = false;
    });
  }

  function applyState(row, on) {
    row.setAttribute('aria-checked', on ? 'true' : 'false');
    row.classList.toggle('selected', !!on);
  }

  function navEntry(path, label, list) {
    var name = label || 'Settings';
    var row = h(
      'button',
      {
        class: 'menu-item settings-item settings-group-entry',
        title: name,
        attrs: {
          type: 'button',
          role: 'menuitem',
          tabindex: '-1',
          'aria-haspopup': 'menu',
        },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            enterGroup(path);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: name })
    );
    if (isDeferred(list)) {
      row.appendChild(h('span', { class: 'settings-defer', text: DEFER_NOTE }));
    }
    row.appendChild(h('span', { class: 'menu-group-caret', attrs: { 'aria-hidden': 'true' } }));
    row.__ccFocusId = 'g' + SEP + path;
    return row;
  }

  function isDeferred(list) {
    return list.some(function (it) {
      return !!it.deferred;
    });
  }

  function groupHead(name) {
    var back = h(
      'button',
      {
        class: 'attach-back',
        title: 'Back',
        attrs: { type: 'button', role: 'menuitem', tabindex: '-1', 'aria-label': 'Back to all settings' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            leaveGroup();
          },
        },
      },
      h('span', { text: '←', attrs: { 'aria-hidden': 'true' } })
    );
    back.__ccFocusId = 'b';
    return h(
      'div',
      { class: 'attach-head' },
      back,
      h('span', { class: 'attach-title', text: name || 'Settings' })
    );
  }

  function enterGroup(path) {
    view = path;
    slide = 'attach-from-right';
    repaint();
    focusRow(rows()[1] || rows()[0]);
    refreshFromHost();
  }

  function leaveGroup() {
    var came = String(view);
    var cut = came.lastIndexOf(SEP);
    view = cut < 0 ? null : came.slice(0, cut);
    slide = 'attach-from-left';
    repaint();
    focusRow(rowForId('g' + SEP + came) || rows()[0]);
  }

  function openSettingsRow() {
    var row = h(
      'button',
      {
        class: 'menu-item settings-item',
        attrs: { type: 'button', role: 'menuitem', tabindex: '-1' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            close(false);
            CC.send({ type: 'openSettings' });
          },
        },
      },
      h('span', { class: 'menu-item-label', text: 'Open Plugin Settings' })
    );
    row.__ccFocusId = 'x';
    return row;
  }

  function buildBody() {
    var frag = document.createDocumentFragment();
    var panel = view != null ? panelFor(view) : null;
    if (panel) {
      frag.appendChild(groupHead(panel.title));
      if (panel.rows.length) {
        var body = h('div', {
          class: 'settings-section-items',
          attrs: {
            role: 'group',
            'aria-label': panel.title || 'Settings',
            id: 'settings-group-' + ++groupSeq,
          },
        });
        panel.rows.forEach(function (it) {
          body.appendChild(settingRow(it, panel.group));
        });
        frag.appendChild(body);
      }
      panel.subs.forEach(function (s) {
        frag.appendChild(navEntry(panel.path + SEP + s.name, s.name, s.list));
      });
      return frag;
    }
    var all = groups();
    if (!all.length) {
      frag.appendChild(
        h('div', {
          class: 'menu-item settings-empty',
          text: 'No quick settings yet.',
          attrs: { role: 'menuitem', 'aria-disabled': 'true', tabindex: '-1' },
        })
      );
    } else {
      all.forEach(function (g) {
        frag.appendChild(navEntry(g.name, g.name, g.list));
      });
    }
    frag.appendChild(h('div', { class: 'menu-sep', attrs: { role: 'separator' } }));
    frag.appendChild(openSettingsRow());
    return frag;
  }

  function structureSig() {
    var list = items();
    var sig = (view == null ? '' : view) + '|' + list.length + '|';
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      var f = [groupOf(it), subOf(it), it.key, labelOf(it), isRadio(it) ? 'r' : 'c', it.deferred ? '1' : '0'];
      sig += f.join(SEP) + '|';
    }
    return sig;
  }

  function allRows() {
    if (!menu) return [];
    return Array.prototype.slice.call(
      menu.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
    );
  }

  function rows() {
    return allRows();
  }

  function render() {
    if (!menu) return;
    var sig = structureSig();
    if (sig === drawnSig) {
      syncStates();
      return;
    }
    var hadFocus = menu.contains(document.activeElement);
    var wanted = focusedId();
    drawnSig = sig;
    menu.textContent = '';
    var body = h('div', { class: 'settings-body' + (slide ? ' ' + slide : '') });
    body.appendChild(buildBody());
    menu.appendChild(body);
    slide = '';
    var target = rowForId(wanted) || rows()[0] || null;
    if (hadFocus) focusRow(target);
    else setRoving(target);
    if (CX.positionMenu) CX.positionMenu(menu, btn);
  }

  function repaint() {
    drawnSig = '';
    render();
  }

  function syncStates() {
    var by = {};
    items().forEach(function (it) {
      by[String(it.key)] = !!it.on;
    });
    allRows().forEach(function (row) {
      if (row.__ccKey != null && Object.prototype.hasOwnProperty.call(by, row.__ccKey)) {
        applyState(row, by[row.__ccKey]);
      }
    });
  }

  function focusedId() {
    var active = document.activeElement;
    return active && active.__ccFocusId != null ? active.__ccFocusId : null;
  }

  function rowForId(id) {
    if (id == null) return null;
    var all = rows();
    for (var i = 0; i < all.length; i++) {
      if (all[i].__ccFocusId === id) return all[i];
    }
    return null;
  }

  function setRoving(row) {
    var all = allRows();
    for (var i = 0; i < all.length; i++) all[i].setAttribute('tabindex', all[i] === row ? '0' : '-1');
  }

  function focusRow(row) {
    setRoving(row);
    if (row) row.focus();
  }

  function step(delta) {
    var all = rows();
    if (!all.length) return;
    var at = all.indexOf(document.activeElement);
    var next = at < 0 ? (delta > 0 ? 0 : all.length - 1) : (at + delta + all.length) % all.length;
    focusRow(all[next]);
  }

  function onMenuKey(e) {
    if (e.key === 'Escape' || e.key === 'Esc') {
      e.preventDefault();
      e.stopPropagation();
      if (view != null) leaveGroup();
      else close(true);
    } else if (e.key === 'ArrowDown' || e.key === 'Down') {
      e.preventDefault();
      step(1);
    } else if (e.key === 'ArrowRight' || e.key === 'Right') {
      var entry = document.activeElement;
      if (!entry || entry.__ccFocusId == null || entry.__ccFocusId.charAt(0) !== 'g') return;
      e.preventDefault();
      enterGroup(entry.__ccFocusId.slice(2));
    } else if (e.key === 'ArrowLeft' || e.key === 'Left') {
      if (view == null) return;
      e.preventDefault();
      leaveGroup();
    } else if (e.key === 'ArrowUp' || e.key === 'Up') {
      e.preventDefault();
      step(-1);
    } else if (e.key === 'Home') {
      e.preventDefault();
      focusRow(rows()[0]);
    } else if (e.key === 'End') {
      e.preventDefault();
      focusRow(rows()[rows().length - 1]);
    } else if (e.key === 'Tab') {
      close(true);
    }
  }

  function open() {
    if (menu || !btn) return;
    if (CX.closeMenu) CX.closeMenu();

    menu = h('div', {
      class: 'menu settings-menu',
      attrs: { role: 'menu', 'aria-label': 'Chat settings' },
    });
    view = null;
    slide = '';
    drawnSig = '';
    menu.addEventListener('keydown', onMenuKey);
    document.body.appendChild(menu);
    render();
    btn.setAttribute('aria-expanded', 'true');
    if (CX.positionMenu) CX.positionMenu(menu, btn);
    focusRow(rows()[0]);
    refreshFromHost();
  }

  function refreshFromHost() {
    CC.send({ type: 'settingsRefresh' });
  }

  function close(returnFocus) {
    if (!menu) return;
    if (menu.parentNode) menu.parentNode.removeChild(menu);
    menu = null;
    drawnSig = '';
    if (!btn) return;
    btn.setAttribute('aria-expanded', 'false');
    if (returnFocus) btn.focus();
  }

  document.addEventListener(
    'mousedown',
    function (e) {
      if (!menu) return;
      if (menu.contains(e.target)) return;
      if (btn && btn.contains(e.target)) return;
      close(false);
    },
    true
  );

  cc.settingsMenu = function (data) {
    payload = data && typeof data === 'object' ? data : null;
    render();
  };

  CX.mountSettingsButton();
})();
