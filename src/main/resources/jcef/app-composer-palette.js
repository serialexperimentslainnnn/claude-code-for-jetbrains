(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  var commands = [];
  var paletteState = { items: [], active: 0, navigated: false };
  var dismissed = false;

  var COMMAND_TOKEN = /^\/(\S*)$/;
  var SEPARATORS = '-_:./';

  function composerInput() {
    return CX.els && CX.els.input ? CX.els.input : null;
  }

  function queryOf(value) {
    var match = COMMAND_TOKEN.exec(value == null ? '' : String(value));
    return match ? match[1].toLowerCase() : null;
  }

  function isOpen() {
    var p = CC.els && CC.els.palette;
    return !!(p && p.__built && !p.hasAttribute('hidden'));
  }

  function startsAtSegment(name, q) {
    for (var i = 0; i < name.length - 1; i++) {
      if (SEPARATORS.indexOf(name.charAt(i)) !== -1 && name.indexOf(q, i + 1) === i + 1) return true;
    }
    return false;
  }

  function startsAtWord(text, q) {
    var at = text.indexOf(q);
    while (at !== -1) {
      if (at === 0 || !/[a-z0-9]/.test(text.charAt(at - 1))) return true;
      at = text.indexOf(q, at + 1);
    }
    return false;
  }

  function score(name, description, q) {
    if (!q) return 1;
    var n = name.toLowerCase();
    var d = description.toLowerCase();
    if (n === q) return 100;
    if (n.indexOf(q) === 0) return 80;
    if (startsAtSegment(n, q)) return 60;
    if (n.indexOf(q) !== -1) return 40;
    if (startsAtWord(d, q)) return 20;
    if (d.indexOf(q) !== -1) return 10;
    return 0;
  }

  function rank(q) {
    var scored = [];
    for (var i = 0; i < commands.length; i++) {
      var c = commands[i];
      var name = c && c.name != null ? String(c.name) : '';
      var description = c && c.description != null ? String(c.description) : '';
      var points = score(name, description, q);
      if (points > 0) scored.push({ name: name, description: description, score: points });
    }
    scored.sort(function (a, b) {
      if (q && a.score !== b.score) return b.score - a.score;
      if (q && a.name.length !== b.name.length) return a.name.length - b.name.length;
      return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
    });
    return scored;
  }

  function ensurePaletteBuilt() {
    if (!CC.els || !CC.els.palette) return null;
    var p = CC.els.palette;
    if (!p.__built) {
      p.__built = true;
      p.innerHTML = '';
      var box = h('div', { class: 'palette-box' });
      var list = h('div', {
        class: 'palette-list',
        attrs: { id: 'palette-list', role: 'listbox', 'aria-label': 'Slash commands' },
      });
      box.appendChild(list);
      p.appendChild(box);
      p.__list = list;

      box.addEventListener('mousedown', function (e) {
        e.preventDefault();
      });
    }
    return p;
  }

  function openPalette() {
    var p = ensurePaletteBuilt();
    if (!p) return;
    dismissed = false;
    p.removeAttribute('hidden');
    paletteState.active = 0;
    paletteState.navigated = false;
    filterPalette(queryOf(composerInput() && composerInput().value) || '');
    linkInput(true);
  }
  CX.openPalette = openPalette;

  function hidePalette() {
    var p = CC.els && CC.els.palette;
    if (!p) return;
    p.setAttribute('hidden', 'hidden');
    linkInput(false);
    var input = composerInput();
    if (input && input.value === '/') {
      input.value = '';
      CX.autosize(input);
    }
    if (input) input.focus();
  }

  function linkInput(open) {
    var input = composerInput();
    if (!input) return;
    if (!open) {
      input.removeAttribute('role');
      input.removeAttribute('aria-expanded');
      input.removeAttribute('aria-controls');
      input.removeAttribute('aria-autocomplete');
      input.removeAttribute('aria-activedescendant');
      return;
    }
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-expanded', 'true');
    input.setAttribute('aria-controls', 'palette-list');
    input.setAttribute('aria-autocomplete', 'list');
  }

  function optionId(index) {
    return 'palette-opt-' + index;
  }

  function syncActiveDescendant() {
    var input = composerInput();
    if (!input) return;
    if (paletteState.navigated && paletteState.items.length) {
      input.setAttribute('aria-activedescendant', optionId(paletteState.active));
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  }

  function filterPalette(q) {
    var p = CC.els && CC.els.palette;
    if (!p || !p.__list) return;
    paletteState.items = rank((q || '').toLowerCase().replace(/^\//, ''));
    paletteState.active = 0;
    paletteState.navigated = false;
    renderPaletteList();
  }

  function renderPaletteList() {
    var p = CC.els && CC.els.palette;
    if (!p || !p.__list) return;
    var list = p.__list;
    list.innerHTML = '';
    var items = paletteState.items;
    if (!items.length) {
      list.appendChild(h('div', { class: 'palette-empty', text: 'No matching commands' }));
      syncActiveDescendant();
      return;
    }
    for (var i = 0; i < items.length; i++) {
      (function (it, idx) {
        var active = paletteState.navigated && idx === paletteState.active;
        var match = !paletteState.navigated && idx === 0 && paletteState.items.length > 1;
        var row = h(
          'div',
          {
            class: 'palette-item' + (active ? ' active' : '') + (match ? ' match' : ''),
            attrs: { id: optionId(idx), role: 'option', 'aria-selected': active ? 'true' : 'false' },
            on: {
              click: function (e) {
                e.preventDefault();
                pickPalette(idx);
              },
            },
          },
          h('span', { class: 'palette-name', text: '/' + it.name }),
          it.description ? h('span', { class: 'palette-desc', text: it.description }) : null
        );
        list.appendChild(row);
      })(items[i], i);
    }
    syncActiveDescendant();
  }

  function updatePaletteActiveClass() {
    var p = CC.els && CC.els.palette;
    if (!p || !p.__list) return;
    var rows = p.__list.querySelectorAll('.palette-item');
    for (var i = 0; i < rows.length; i++) {
      if (paletteState.navigated && i === paletteState.active) {
        rows[i].classList.add('active');
        rows[i].setAttribute('aria-selected', 'true');
      } else {
        rows[i].classList.remove('active');
        rows[i].setAttribute('aria-selected', 'false');
      }
    }
    syncActiveDescendant();
  }

  function movePaletteActive(delta) {
    var n = paletteState.items.length;
    if (!n) return;
    if (!paletteState.navigated) {
      paletteState.navigated = true;
      paletteState.active = delta > 0 ? 0 : n - 1;
    } else {
      paletteState.active = (paletteState.active + delta + n) % n;
    }
    updatePaletteActiveClass();
    var p = CC.els && CC.els.palette;
    if (p && p.__list) {
      var row = p.__list.querySelectorAll('.palette-item')[paletteState.active];
      if (row && row.scrollIntoView) row.scrollIntoView({ block: 'nearest' });
    }
  }

  function pickPaletteActive() {
    if (!paletteState.items.length) {
      hidePalette();
      return;
    }
    pickPalette(paletteState.active);
  }

  function pickPalette(idx) {
    var it = paletteState.items[idx];
    hidePalette();
    if (!it) return;
    if (!CX.ensureBuilt() || !CX.els || !CX.els.input) return;
    var input = CX.els.input;
    input.value = '/' + it.name + ' ';
    CX.autosize(input);
    input.focus();
    try {
      var len = input.value.length;
      input.setSelectionRange(len, len);
    } catch (e) {}
  }

  document.addEventListener(
    'keydown',
    function (e) {
      if (!isOpen() || e.target !== composerInput()) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        dismissed = true;
        hidePalette();
        return;
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        e.stopPropagation();
        movePaletteActive(e.key === 'ArrowDown' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        if (!paletteState.navigated) {
          hidePalette();
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        pickPaletteActive();
      }
    },
    true
  );

  document.addEventListener(
    'input',
    function (e) {
      if (e.target !== composerInput()) return;
      var q = queryOf(e.target.value);
      if (q === null) {
        dismissed = false;
        if (isOpen()) hidePalette();
        return;
      }
      if (dismissed) return;
      if (isOpen()) filterPalette(q);
      else openPalette();
    },
    true
  );

  CX.setCommands = function (list) {
    commands = list;
    if (isOpen()) filterPalette(queryOf(composerInput() && composerInput().value) || '');
  };

  cc.openPalette = function () {
    if (!CX.ensureBuilt()) return;
    var input = composerInput();
    if (input) {
      if (queryOf(input.value) === null) {
        input.value = '/';
        CX.autosize(input);
      }
      input.focus();
      try {
        var len = input.value.length;
        input.setSelectionRange(len, len);
      } catch (e) {}
    }
    openPalette();
  };
})();
