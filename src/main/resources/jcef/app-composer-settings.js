/* app-composer-settings.js — the ⚙ menu at the head of the controls row.
 *
 * One subject: the handful of settings that get changed several times a session, put where the session is —
 * plus the door to the full Settings page for everything else.
 *
 * WHERE IT SITS, AND WHY NOT NEXT TO THE CLIP. The clip is the composer's own bar: it acts on the message you
 * are writing. This acts on the CHAT, which is what `#controls` is for, so the gear is the first item of
 * `#views` — ahead of Chat · Session · Workloads · Git · Plan. Beside the clip it would have read as a third
 * kind of attachment.
 *
 * WHY THE MOUNT IS ITS OWN IDEMPOTENT STEP. `#views` has two writers: this file, and `app-session.js`, which
 * appends its `.dash-toggles` stack there from `mountToggles()`. Either family can load and build first, and
 * neither may wait for the other (making the dashboard wait once cost 41 frontend tests). So the gear is
 * inserted BEFORE whatever is already in the row rather than appended: early, it lands first and the stack
 * arrives behind it; late, it moves in front of the stack. The position is a DOM position and never a CSS
 * `order:` — a visual order that disagrees with the DOM is a focus order that disagrees with the screen
 * (WCAG 2.2 SC 2.4.3).
 *
 * THE MENU IS A REAL MENU, not a styled div with click handlers. `aria-haspopup`/`aria-expanded` on a real
 * `<button>`, `role="menu"` on the popup, `role="menuitemcheckbox"` + `aria-checked` on each toggle — so the
 * state is programmatic and not only a colour (WCAG 2.2 SC 1.4.1 and 4.1.2). Declaring `role="menu"` is also
 * a promise about the keyboard: arrows, Home/End, Escape and Tab are implemented below, because ARIA that
 * announces a widget it does not behave like is worse than no ARIA at all.
 *
 * The metrics are the IDE's, borrowed rather than re-invented: `.menu` and `.menu-item` in css/composer.css
 * are the flat, dense, full-bleed rows the pill menus already use. A second popup language two centimetres
 * from the first is exactly what those rules were written to end.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  /** Last payload from the host: `{items:[{key, group, label, on}]}`. Null until the host has pushed one. */
  var payload = null;
  /** The gear button. Built once; the row it lives in is static, so it never needs rebuilding. */
  var btn = null;
  /** The open popup, or null. */
  var menu = null;
  /** Signature of the structure currently drawn — see [render]. */
  var drawnSig = '';

  /**
   * The field boundary inside one entry of the signature — built from its CODE POINT, never typed.
   *
   * This file once carried two literal U+0000 bytes here, and they cost an afternoon. That byte makes the
   * source binary — `git diff` reports `Bin`, `grep` goes quiet on the file — and, fatally, the HTML parser
   * rewrites U+0000 to U+FFFD while reading the script, so the text the browser hashes stops being the text
   * the host hashed. The page is served under a hash-pinned CSP, so the script was REFUSED: this whole module
   * never ran, there was no gear button, and nothing anywhere said why. `String.fromCharCode` survives every
   * editor and cannot be mistyped invisibly.
   */
  var SEP = String.fromCharCode(31); // U+001F INFORMATION SEPARATOR ONE

  // Inline SVG, `currentColor`, no `url()`: the CSP forbids external resources and there is no asset pipeline.
  //
  // A COG, and a small one. Two earlier attempts are worth naming: a circle with eight radial spokes, which is
  // a drawing of a SUN and was read as one; then an eight-tooth cog at 15px, which at this size turns the
  // teeth into a fuzzy ring — an icon this small has room for four features, not eight. Four square teeth on
  // the axes read as a cog at 13px, which is the size of the glyphs it sits beside.
  var GEAR =
    '<svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.3" ' +
    'stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M6.9 2.1h2.2v1.6l1.5.87 1.39-.8 1.1 1.9-1.39.8v1.74l1.39.8-1.1 1.9-1.39-.8-1.5.87v1.6H6.9v-1.6' +
    'l-1.5-.87-1.39.8-1.1-1.9 1.39-.8V8.47l-1.39-.8 1.1-1.9 1.39.8 1.5-.87z"/>' +
    '<circle cx="8" cy="8" r="1.9"/></svg>';

  /** The togglable settings the host has given us, defensively filtered — a row with no key cannot be sent. */
  function items() {
    var list = payload && Array.isArray(payload.items) ? payload.items : [];
    return list.filter(function (it) {
      return it && it.key != null;
    });
  }

  function labelOf(it) {
    return it.label != null ? String(it.label) : String(it.key);
  }

  // ---- the button -----------------------------------------------------------
  function buildButton() {
    var el = h('button', {
      class: 'bar-icon settings-btn',
      title: 'Chat settings',
      attrs: {
        type: 'button',
        // The visible control is an icon, so the accessible name has to be spelled out (WCAG 4.1.2), and
        // `aria-expanded` is what says the popup is open — a rotated caret says it to nobody.
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
          // Down-arrow opens and lands on the first entry, the way a platform popup does. Enter and Space
          // already activate a real <button>, so there is nothing to add for them.
          if (e.key === 'ArrowDown' || e.key === 'Down') {
            e.preventDefault();
            if (!menu) open();
          }
        },
      },
    });
    el.innerHTML = GEAR;
    return el;
  }

  /**
   * Puts the gear at the head of the controls row, whenever the row exists. Idempotent.
   *
   * Called from HERE at load time (the row is static markup in `shell.html`, so this normally succeeds on the
   * first try) and again from `app-composer-actions.js` when it fills the row — the same both-sides pattern
   * `app-session.js` uses for its view stack, and for the same reason: two families write into `#views` and
   * neither owns the other's build order.
   *
   * The `firstChild` guard is not tidiness. The DOM has no move — an insert of a node already in place is a
   * remove plus an insert — so an unguarded call would blur whatever is focused inside the button and, with a
   * menu open, tear its anchor out from under it.
   */
  CX.mountSettingsButton = function () {
    var views = document.getElementById('views');
    if (!views) return null;
    if (!btn) btn = buildButton();
    if (views.firstChild !== btn) views.insertBefore(btn, views.firstChild);
    return btn;
  };

  // ---- the menu body --------------------------------------------------------
  /** One toggle. A real control with a programmatic state, never a div with an onclick. */
  function toggleRow(it) {
    var on = !!it.on;
    var row = h(
      'button',
      {
        // `.menu-item` is the IDE's row metric; `.selected` draws the ✓ (a NON-colour signal, WCAG 1.4.1)
        // and `aria-checked` carries the same fact to assistive technology.
        class: 'menu-item settings-item' + (on ? ' selected' : ''),
        attrs: {
          type: 'button',
          role: 'menuitemcheckbox',
          'aria-checked': on ? 'true' : 'false',
          tabindex: '-1', // roving: exactly one entry is tabbable at a time (see focusRow)
        },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            flip(it, row);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: labelOf(it) })
    );
    row.__ccKey = String(it.key);
    return row;
  }

  /**
   * Flip one setting.
   *
   * Optimistic, and the menu STAYS OPEN. A pill menu closes on choice because a pill is one choice; this is a
   * list of independent switches, and closing it after each one would make changing two settings two trips.
   * The row flips immediately rather than waiting for the host to answer — a switch that does nothing until a
   * round trip completes reads as broken — and the next `cc.settingsMenu` push is authoritative either way,
   * so a refusal upstream simply puts it back.
   */
  function flip(it, row) {
    var next = row.getAttribute('aria-checked') !== 'true';
    it.on = next;
    applyState(row, next);
    CC.send({ type: 'settingsToggle', key: String(it.key), on: next });
    // WCAG 4.1.3: the change moves no focus and paints a glyph, so without this it is silent.
    if (CC.announce) CC.announce(labelOf(it) + (next ? ' on' : ' off'));
  }

  function applyState(row, on) {
    row.setAttribute('aria-checked', on ? 'true' : 'false');
    row.classList.toggle('selected', !!on);
  }

  /** The last entry: leaves the page entirely, so unlike the toggles it closes the menu behind it. */
  function openSettingsRow() {
    return h(
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
  }

  /**
   * The whole body: the groups, then a separator, then the door to the Settings page.
   *
   * Groups are emitted in the order the host first mentions them, never in a list hardcoded here — which
   * group exists and what it is called is the host's to decide, and a fixed order would silently drop a
   * third one. Each is a `role="group"` carrying the heading as its `aria-label`, with the visible heading
   * `aria-hidden` so the name is announced once (from the group) instead of twice.
   */
  function buildBody() {
    var frag = document.createDocumentFragment();
    var list = items();
    if (!list.length) {
      // Never an empty popup with no explanation. A disabled menu item rather than loose text: entries are
      // what menu navigation visits, so anything that is not one is text a screen-reader user never reaches.
      frag.appendChild(
        h('div', {
          class: 'menu-item settings-empty',
          text: 'No quick settings yet.',
          attrs: { role: 'menuitem', 'aria-disabled': 'true', tabindex: '-1' },
        })
      );
    } else {
      var order = [];
      var byGroup = {};
      list.forEach(function (it) {
        var name = it.group != null ? String(it.group) : '';
        if (!Object.prototype.hasOwnProperty.call(byGroup, name)) {
          byGroup[name] = [];
          order.push(name);
        }
        byGroup[name].push(it);
      });
      order.forEach(function (name) {
        var group = h('div', {
          class: 'settings-section',
          attrs: { role: 'group', 'aria-label': name || 'Settings' },
        });
        if (name) {
          group.appendChild(
            h('div', { class: 'settings-group', text: name, attrs: { 'aria-hidden': 'true' } })
          );
        }
        byGroup[name].forEach(function (it) {
          group.appendChild(toggleRow(it));
        });
        frag.appendChild(group);
      });
    }
    frag.appendChild(h('div', { class: 'menu-sep', attrs: { role: 'separator' } }));
    frag.appendChild(openSettingsRow());
    return frag;
  }

  // ---- rendering ------------------------------------------------------------
  /** What is actually drawn, so an identical push can be recognised as one. The `on` flags are NOT in it. */
  function structureSig() {
    var list = items();
    var sig = list.length + '|';
    for (var i = 0; i < list.length; i++) {
      sig += (list[i].group || '') + SEP + list[i].key + SEP + labelOf(list[i]) + '|';
    }
    return sig;
  }

  function rows() {
    if (!menu) return [];
    return Array.prototype.slice.call(menu.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"]'));
  }

  /**
   * Draw the current payload into the open popup, rebuilding only when the STRUCTURE changed.
   *
   * The host re-pushes on its own schedule, and two rules govern anything it re-pushes here. A rebuild
   * destroys and recreates every row, so whatever was focused inside is blurred — hence the states-only path
   * when nothing but a flag moved, and the focus restore by key when there is no way around a rebuild. The
   * popup is also re-positioned afterwards, because a row more or less changes its height and it hangs
   * upwards from its anchor.
   */
  function render() {
    if (!menu) return;
    var sig = structureSig();
    if (sig === drawnSig) {
      syncStates();
      return;
    }
    // Whether the focus was IN here decides whether we may put it back. A push can land while the reader is
    // typing in the composer with the menu still open behind them, and restoring unconditionally would yank
    // the caret out of the prompt box — a rebuild is allowed to keep the focus it had, never to take one.
    var hadFocus = menu.contains(document.activeElement);
    var wanted = focusedKey();
    drawnSig = sig;
    menu.textContent = '';
    menu.appendChild(buildBody());
    var target = rowForKey(wanted) || rows()[0] || null;
    if (hadFocus) focusRow(target);
    else setRoving(target); // one entry stays tabbable, or Tab could not reach the menu at all
    if (CX.positionMenu) CX.positionMenu(menu, btn);
  }

  function syncStates() {
    var by = {};
    items().forEach(function (it) {
      by[String(it.key)] = !!it.on;
    });
    rows().forEach(function (row) {
      if (row.__ccKey != null && Object.prototype.hasOwnProperty.call(by, row.__ccKey)) {
        applyState(row, by[row.__ccKey]);
      }
    });
  }

  function focusedKey() {
    var active = document.activeElement;
    return active && active.__ccKey != null ? active.__ccKey : null;
  }

  /** The row for a setting key, matched by KEY and not by position — the point is to survive a reorder. */
  function rowForKey(key) {
    if (key == null) return null;
    var all = rows();
    for (var i = 0; i < all.length; i++) {
      if (all[i].__ccKey === key) return all[i];
    }
    return null;
  }

  /** Roving tabindex: exactly one entry is in the tab order, so Tab enters the menu once and then leaves. */
  function setRoving(row) {
    var all = rows();
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

  /**
   * The keyboard model `role="menu"` promises. Escape and Tab both leave, and both hand the focus back to the
   * gear — Tab without swallowing the event, so the browser then carries on from a control that is still in
   * the document rather than from the `<body>` a removed row leaves behind.
   */
  function onMenuKey(e) {
    if (e.key === 'Escape' || e.key === 'Esc') {
      // The menu owns this press and nothing downstream may read it as anything else. As things stand today
      // nothing would: the composer's Escape (which interrupts the running turn) is bound to the textarea,
      // and focus is inside the popup. So this is a boundary rather than a fix — it is what keeps the next
      // document-level Escape handler from turning a dismissal into an interrupt.
      e.preventDefault();
      e.stopPropagation();
      close(true);
    } else if (e.key === 'ArrowDown' || e.key === 'Down') {
      e.preventDefault();
      step(1);
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

  // ---- open / close ---------------------------------------------------------
  function open() {
    if (menu || !btn) return;
    // Any other popup in the family closes first — they all hang off the same two rows and two open at once
    // is two answers to one gesture. The reverse direction needs nothing: opening a pill menu starts with a
    // mousedown outside this one, which the handler at the bottom of this file already treats as a dismissal.
    if (CX.closeMenu) CX.closeMenu();

    menu = h('div', {
      class: 'menu settings-menu',
      attrs: { role: 'menu', 'aria-label': 'Chat settings' },
    });
    drawnSig = '';
    menu.addEventListener('keydown', onMenuKey);
    // On `document.body`, like the pill and attach menus and for the same reason: `#work` is a stacking
    // context with declared ranks and the dock clips, so a popup anchored at the bottom of the page and
    // hanging upwards has to escape it. Unlike the find bar — which was mounted here at a fixed `top: 12px`
    // and landed on the tab row, hiding a focused tab (WCAG 2.2 SC 2.4.11) — this one is placed against its
    // own anchor by `positionMenu` and capped by `.menu`'s `max-height`, so it cannot reach the tabs.
    document.body.appendChild(menu);
    render();
    btn.setAttribute('aria-expanded', 'true');
    if (CX.positionMenu) CX.positionMenu(menu, btn);
    focusRow(rows()[0]);
  }

  function close(returnFocus) {
    if (!menu) return;
    if (menu.parentNode) menu.parentNode.removeChild(menu);
    menu = null;
    drawnSig = '';
    if (!btn) return;
    btn.setAttribute('aria-expanded', 'false');
    // Focus goes back where it was taken from (WCAG 2.4.3), except when the entry that closed the menu is
    // itself opening something else — then the focus belongs to whatever that opens.
    if (returnFocus) btn.focus();
  }
  // Deliberately NOT exported. Nothing outside this file has a reason to close the menu, and an exported
  // function nobody calls is this repository's signature defect: implemented, tested and reachable from
  // nothing. It goes on `CC.composer` the day something needs it.

  // Outside click dismisses. Capture-phase mousedown, the same shape as the pill menus' own handler, so the
  // press that opens another popup closes this one before that popup is built.
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

  // ---- Kotlin-facing API ----------------------------------------------------
  /**
   * Host → page: the settings this chat offers, `{items:[{key, group, label, on}]}`.
   *
   * Stash then render, like the dashboard: a push that lands while the menu is shut is remembered and not
   * drawn, so the next open shows the current truth instead of whatever was last on screen — and a payload
   * that arrives before the page ever built a menu is not lost.
   */
  cc.settingsMenu = function (data) {
    payload = data && typeof data === 'object' ? data : null;
    render();
  };

  // The row is static markup, so this normally lands on the first try; `buildActionRows` calls it again for
  // the case where it did not.
  CX.mountSettingsButton();
})();
