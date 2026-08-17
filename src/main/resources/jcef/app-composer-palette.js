/* app-composer-palette.js — the slash-command palette.
 *
 * One subject: the `/` overlay — its ranked list, its keyboard, and what it drops into the composer.
 * The commands themselves come from the host through cc.meta, which hands them to CX.setCommands below.
 *
 * There is ONE text field on screen and it is the composer's own. The palette has no field of its own: the
 * `/query` you can already see in the composer IS the query, so what you type never lands in a box other than
 * the one you were typing in, and the command you are composing stays visible where you will send it from.
 * Keys reach here by a CAPTURE listener on `document`, which is what lets Enter pick an item instead of
 * sending the turn and Escape close the list instead of interrupting it — the composer's own handlers are on
 * the textarea itself, and a capture listener runs first.
 *
 * Matching is SCORED, not first-come. A session that loads a large command catalogue makes an unranked
 * substring filter unusable: the exact command you named sits wherever the host happened to list it, below
 * every entry that merely mentions it in its description. Every match carries a score and the list is sorted
 * by it, so the best answer is the one already selected when you press Enter.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  var commands = []; // from cc.meta
  /**
   * [navigated] is what makes Enter mean one thing at a time.
   *
   * The list is a convenience for picking a command with the mouse; typing one out is the other way of doing
   * the same job, and it must not cost more. It used to: the capture listener took Enter unconditionally, so
   * typing `/commit` and pressing Enter COMPLETED `/commit ` instead of sending it, and only the second Enter
   * sent the turn. So the keyboard enters the list explicitly — an arrow — and until it does, Enter belongs to
   * the composer and the palette gets out of its way.
   *
   * It also governs what is drawn: while it is false NO row is `.active` and there is no
   * `aria-activedescendant`, because a highlighted row that Enter does not choose is a promise the list is not
   * keeping — visually and to a screen reader alike.
   */
  var paletteState = { items: [], active: 0, navigated: false };
  // Escape closed the list while the query was still valid. Without this, the very next keystroke would
  // re-open what the user just dismissed, and there would be no way to type a message that starts with "/".
  var dismissed = false;

  // The composer holds a command token and nothing else: a space means the command has been chosen and what
  // follows is its argument, so the list has no more say in it.
  var COMMAND_TOKEN = /^\/(\S*)$/;
  // Where a command name breaks — `git:commit`, `pr-review`, `plugin/name`. Matching a segment start is worth
  // more than matching the middle of a word: it is how people abbreviate a namespaced command.
  var SEPARATORS = '-_:./';

  function composerInput() {
    return CX.els && CX.els.input ? CX.els.input : null;
  }

  /** The query the composer is holding, or `null` when its content is not a command token at all. */
  function queryOf(value) {
    var match = COMMAND_TOKEN.exec(value == null ? '' : String(value));
    return match ? match[1].toLowerCase() : null;
  }

  function isOpen() {
    var p = CC.els && CC.els.palette;
    return !!(p && p.__built && !p.hasAttribute('hidden'));
  }

  // ---- ranking --------------------------------------------------------------
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

  /**
   * How well one command answers `q` — 0 when it does not answer it at all.
   *
   * The tiers are ordered by how deliberate the match is: the whole name, then its start, then the start of
   * one of its segments, then anywhere in the name, and only after all of that the description, which is
   * prose and matches by accident far more often than a name does.
   */
  function score(name, description, q) {
    if (!q) return 1; // no query: everything matches equally, and host order decides
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

  /**
   * Score, keep the matches, and sort them best-first. Ties break on the shorter name — of two commands that
   * match a query equally well, the shorter one is the one the query names and the longer one merely extends
   * — and then ALPHABETICALLY.
   *
   * **Alphabetically, and not "the host's order", which is what this used to fall back to at both ends.** The
   * host order was defended here as a decision, and it is one for exactly its first entry: `JcefState.metaJson`
   * puts the plugin's own commands ahead of the binary's. Everything after that is the order the binary
   * happened to list `slash_commands` in — no order at all from where the reader sits. So an unqueried palette
   * showed a long list nobody had sorted, which is what "it filters but it does not sort" describes.
   *
   * A name is the thing being chosen, so the name is what orders the list. It also makes the result stable
   * across sessions: the same catalogue always draws in the same order, whatever the binary emits.
   */
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

  // ---- the overlay ----------------------------------------------------------
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

      // THE PALETTE NEVER TAKES THE CARET. A mousedown on a div blurs whatever was focused, so merely
      // scrolling this list — or clicking the gap between two rows — emptied the composer of its caret, and
      // the one text field on screen stopped being the one you were typing in. Preventing the default on
      // mousedown is what keeps focus where it is; the click still fires, so picking a row still works.
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
    // if the composer field held just "/", clear it so the palette doesn't re-open on focus
    var input = composerInput();
    if (input && input.value === '/') {
      input.value = '';
      CX.autosize(input);
    }
    if (input) input.focus();
  }

  /**
   * The textarea is the palette's text field, so it is the textarea that carries the combobox semantics
   * (WCAG 2.2 AA — 4.1.2 Name, Role, Value). Focus never moves into the list: the active option is named by
   * `aria-activedescendant`, which is the pattern for exactly this shape and the reason no focus trap, and no
   * focus restore, is needed here at all. The attributes come off again on close, because `aria-expanded` on
   * something that is not a combobox describes a control that does not exist.
   */
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

  /** There is an active option only once the keyboard has entered the list — see [paletteState]. */
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
    // Reset selection to the first (best) match on every query change — otherwise a stale index
    // stays highlighted on a command that no longer matches what you typed. Typing also takes the keyboard
    // back OUT of the list: you are composing again, so Enter is the composer's until an arrow says otherwise.
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
        // What the QUERY is pointing at, which is not the same thing as what the keyboard has selected — and
        // drawn differently on purpose. The best match tells you what you are converging on while you type;
        // it must not look like a chosen row, because Enter does not choose it (it sends what you typed).
        // The keyboard takes over on the first arrow, and then `.active` is the one that is lit.
        var match = !paletteState.navigated && idx === 0 && paletteState.items.length > 1;
        var row = h(
          'div',
          {
            class: 'palette-item' + (active ? ' active' : '') + (match ? ' match' : ''),
            attrs: { id: optionId(idx), role: 'option', 'aria-selected': active ? 'true' : 'false' },
            on: {
              // No `mouseenter` handler: `.palette-item:hover` already draws the row the pointer is on, and
              // moving the selection with the pointer meant the mouse silently decided what Enter would pick.
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

  /**
   * Moves the keyboard selection, ENTERING the list on the first press.
   *
   * The first arrow lands on the end it points at — down on the first row, up on the last — rather than
   * stepping off a selection that was not there. Stepping from a notional index 0 would make the first press
   * of ArrowDown select the SECOND command, which is one row of quiet wrongness every time.
   */
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

  // ---- the composer's field drives all of it --------------------------------
  // Delegated from `document` because the textarea is built later (this file loads before app-composer.js) and
  // is rebuilt whenever the composer is. Capture phase, deliberately: the composer's own keydown listener is
  // on the textarea, so only a capture listener can take Enter and Escape before it acts on them.
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
        // ONLY when the keyboard is in the list. Otherwise this is someone who typed a command out and means
        // to send it: the palette closes and the event goes on to the composer, which sends the turn. Taking
        // Enter here unconditionally is what made every typed command cost two presses.
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
        dismissed = false; // the query was abandoned; a future "/" starts clean
        if (isOpen()) hidePalette();
        return;
      }
      if (dismissed) return;
      if (isOpen()) filterPalette(q);
      else openPalette();
    },
    true
  );

  /** Host → palette (via cc.meta): the session's slash commands, and a refresh if the list is on screen. */
  CX.setCommands = function (list) {
    commands = list;
    // refresh palette list if open
    if (isOpen()) filterPalette(queryOf(composerInput() && composerInput().value) || '');
  };

  cc.openPalette = function () {
    if (!CX.ensureBuilt()) return;
    var input = composerInput();
    if (input) {
      // Opened from the IDE rather than by typing: seed the field with the "/" the palette filters on, so the
      // one text box on screen is already showing the query the list is answering.
      if (queryOf(input.value) === null) {
        input.value = '/';
        CX.autosize(input);
      }
      input.focus();
      try {
        var len = input.value.length;
        input.setSelectionRange(len, len);
      } catch (e) {
        /* ignore */
      }
    }
    openPalette();
  };
})();
