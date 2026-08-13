/* app-composer-palette.js — the slash-command palette.
 *
 * One subject: the `/` overlay — its list, its filtering, its keyboard, and what it drops into the composer.
 * The commands themselves come from the host through cc.meta, which hands them to CX.setCommands below.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  var commands = []; // from cc.meta
  var paletteState = { items: [], active: 0 };

  // ---- slash palette --------------------------------------------------------
  function ensurePaletteBuilt() {
    if (!CC.els || !CC.els.palette) return null;
    var p = CC.els.palette;
    if (!p.__built) {
      p.__built = true;
      p.innerHTML = '';
      var box = h('div', { class: 'palette-box' });
      var input = h('input', {
        class: 'palette-input',
        attrs: { type: 'text', placeholder: 'Search commands…', 'aria-label': 'Search slash commands' },
      });
      var list = h('div', { class: 'palette-list' });
      box.appendChild(input);
      box.appendChild(list);
      p.appendChild(box);
      p.__input = input;
      p.__list = list;

      input.addEventListener('input', function () {
        // Emptying the search closes the palette (you cleared what you were typing).
        if (input.value === '') {
          hidePalette();
          return;
        }
        filterPalette(input.value);
      });
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
          e.preventDefault();
          hidePalette();
          return;
        }
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          movePaletteActive(1);
          return;
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault();
          movePaletteActive(-1);
          return;
        }
        if (e.key === 'Enter') {
          e.preventDefault();
          pickPaletteActive();
          return;
        }
      });
      // outside-click hides
      p.addEventListener('mousedown', function (e) {
        if (e.target === p) hidePalette();
      });
    }
    return p;
  }

  function openPalette() {
    var p = ensurePaletteBuilt();
    if (!p) return;
    p.removeAttribute('hidden');
    p.__input.value = '';
    paletteState.active = 0;
    filterPalette('');
    p.__input.focus();
  }
  CX.openPalette = openPalette;

  function hidePalette() {
    var p = CC.els && CC.els.palette;
    if (!p) return;
    p.setAttribute('hidden', 'hidden');
    // if the composer field held just "/", clear it so the palette doesn't re-open on focus
    var els = CX.els;
    if (els && els.input && els.input.value === '/') {
      els.input.value = '';
      CX.autosize(els.input);
    }
    if (els && els.input) els.input.focus();
  }

  function filterPalette(q) {
    var p = CC.els && CC.els.palette;
    if (!p || !p.__list) return;
    q = (q || '').toLowerCase().replace(/^\//, '');
    var matches = [];
    for (var i = 0; i < commands.length; i++) {
      var c = commands[i];
      var name = c && c.name != null ? String(c.name) : '';
      var desc = c && c.description != null ? String(c.description) : '';
      if (!q || name.toLowerCase().indexOf(q) !== -1 || desc.toLowerCase().indexOf(q) !== -1) {
        matches.push({ name: name, description: desc });
      }
    }
    paletteState.items = matches;
    // Reset selection to the first (best) match on every query change — otherwise a stale index
    // stays highlighted on a command that no longer matches what you typed.
    paletteState.active = 0;
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
      return;
    }
    for (var i = 0; i < items.length; i++) {
      (function (it, idx) {
        var row = h(
          'div',
          {
            class: 'palette-item' + (idx === paletteState.active ? ' active' : ''),
            attrs: { role: 'option' },
            on: {
              click: function (e) {
                e.preventDefault();
                pickPalette(idx);
              },
              mouseenter: function () {
                paletteState.active = idx;
                updatePaletteActiveClass();
              },
            },
          },
          h('span', { class: 'palette-name', text: '/' + it.name }),
          it.description ? h('span', { class: 'palette-desc', text: it.description }) : null
        );
        list.appendChild(row);
      })(items[i], i);
    }
  }

  function updatePaletteActiveClass() {
    var p = CC.els && CC.els.palette;
    if (!p || !p.__list) return;
    var rows = p.__list.querySelectorAll('.palette-item');
    for (var i = 0; i < rows.length; i++) {
      if (i === paletteState.active) rows[i].classList.add('active');
      else rows[i].classList.remove('active');
    }
  }

  function movePaletteActive(delta) {
    var n = paletteState.items.length;
    if (!n) return;
    paletteState.active = (paletteState.active + delta + n) % n;
    updatePaletteActiveClass();
    // keep active in view
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
    // move caret to end
    try {
      var len = input.value.length;
      input.setSelectionRange(len, len);
    } catch (e) {
      /* ignore */
    }
  }

  /** Host → palette (via cc.meta): the session's slash commands, and a refresh if the list is on screen. */
  CX.setCommands = function (list) {
    commands = list;
    // refresh palette list if open
    var p = CC.els && CC.els.palette;
    if (p && p.__built && !p.hasAttribute('hidden')) {
      filterPalette(p.__input ? p.__input.value : '');
    }
  };

  cc.openPalette = function () {
    CX.ensureBuilt();
    openPalette();
  };
})();
