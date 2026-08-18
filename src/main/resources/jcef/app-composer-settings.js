/* app-composer-settings.js — the ⚙ menu at the head of the controls row.
 *
 * One subject: the settings that belong to THIS chat, put where the chat is — model, effort, permission mode,
 * the security rules, the setting sources, the three tool lists and the two MCP switches — plus the door to
 * the full Settings page for everything else.
 *
 * WHAT IS STILL NOT HERE, AND WHY. Three of the plugin's settings are not switches and cannot become rows:
 * the custom-MCP JSON document, the environment-variable table, and the paths to the `claude` and `node`
 * binaries. Each is free text that has to be validated before it can mean anything — a malformed JSON object
 * or a path to a binary that is not Claude Code is a configuration that fails at launch, not a setting that
 * is merely off — and none of them is changed twice in a session. They stay behind *Open Plugin Settings*,
 * which is the last entry and the reason this menu can stop where it stops.
 *
 * WHY IT IS STILL A MENU AND NOT THE SETTINGS PAGE IN A POPUP. Ten groups is around seventy rows, and seventy
 * rows behind a scrollbar in a narrow tool window is a worse Settings page, not a quicker one.
 *
 * IT DRILLS DOWN, ONE SECTION AT A TIME, and it used to FOLD instead — which is the same idea and behaves
 * nothing like it. An accordion keeps every heading on screen and inserts the opened group's rows between
 * them, so opening `Permission mode` pushes the six groups under it down the screen, past the popup's cap and
 * behind a scrollbar; opening a second group moves everything again. Reading the popup means re-finding your
 * place after every press. So: the root panel is the list of sections, pressing one REPLACES the panel with
 * that section's rows behind a back arrow, and nothing else moves. It is the same two-level shape the attach
 * menu uses for the project tree — same header, same back button, same slide — because two popups a
 * centimetre apart that navigate differently is exactly the kind of second language this file's last
 * paragraph exists to refuse.
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
 * `<button>`, `role="menu"` on the popup, `role="menuitemcheckbox"`/`role="menuitemradio"` + `aria-checked` on
 * each row and `role="menuitem"` + `aria-haspopup` on each section entry — so every state is programmatic and
 * none of them is only a colour (WCAG 2.2 SC 1.4.1 and 4.1.2). `aria-haspopup` and deliberately NOT
 * `aria-expanded`: the latter promises a region that opens IN PLACE, under the entry, with the rest of the
 * menu still around it, which is what the accordion did and this does not. Declaring `role="menu"` is also a
 * promise about the keyboard: arrows, Home/End, Escape and Tab are implemented below, because ARIA that
 * announces a widget it does not behave like is worse than no ARIA at all.
 *
 * THE KEYS ARE THE HOST'S. A row that is not a standalone flag carries a composite key — `mode:acceptEdits`,
 * `model:opus[1m]`, `allow:Bash`. This file never builds one and never reads inside one: it receives the key,
 * hands it back verbatim in `settingsToggle`, and the host validates it. That is what keeps the set of keys
 * closed and knowable from one side.
 *
 * The metrics are the IDE's, borrowed rather than re-invented: `.menu` and `.menu-item` in css/composer.css
 * are the flat, dense, full-bleed rows the pill menus already use, and the panel header is the attach menu's
 * (`.attach-head`, `.attach-back`, `.attach-title`). A second popup language two centimetres from the first
 * is exactly what those rules were written to end. What this menu does NOT borrow any more is `.menu-group`,
 * the accordion the pill menus still use: it drills down instead.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  /** Last payload from the host: `{items:[{key, group, label, on, type, deferred}]}`. Null until pushed. */
  var payload = null;
  /** The gear button. Built once; the row it lives in is static, so it never needs rebuilding. */
  var btn = null;
  /** The open popup, or null. */
  var menu = null;
  /** Signature of the structure currently drawn — see [render]. */
  var drawnSig = '';

  /**
   * Which panel is on screen: `null` for the list of sections, or the group name being read.
   *
   * It is reset on every open, deliberately. Remembering the last section would make the popup open somewhere
   * other than where it was left the previous time — the same complaint as the accordion, arriving by the
   * other door: what is on screen would depend on history the user cannot see.
   */
  var view = null;

  /** Which way the next panel slides in, so the animation says whether you went in or came back out. */
  var slide = '';

  /** Serial behind the `aria-controls` ids. Monotonic, so a rebuild can never reuse a live id. */
  var groupSeq = 0;

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

  /** The note a deferred group's heading carries. Text, so it is read out as well as seen. */
  var DEFER_NOTE = 'Applies to new chats';

  // Inline SVG, `currentColor`, no `url()`: the CSP forbids external resources and there is no asset pipeline.
  //
  // A WRENCH HEAD, drawn as a SILHOUETTE. Three attempts are worth naming, because two of them failed for the
  // same reason. A circle with eight radial spokes is a drawing of a SUN and was read as one; an eight-tooth
  // cog at this size turns the teeth into a fuzzy ring — a glyph next to 11.5px type has room for four
  // features, not eight. This has exactly four: two prongs, the gap between them, and the shaft.
  // It is FILLED rather than stroked, which is the third criterion and the one the earlier tries never
  // reached: a 1.3px outline around a 3px jaw puts two hairlines within a pixel of each other and they merge
  // into a smudge, whereas a solid shape keeps the single feature that says "wrench" — an open U bite in a
  // head twice the width of its shaft — readable. The one negative space is the bite, and a negative space
  // survives being scaled down where a line does not.
  var WRENCH =
    '<svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor" aria-hidden="true">' +
    '<path d="M3.4 3.6 Q3.4 1.6 5.4 1.6 L6.2 1.6 L6.2 4.9 Q6.2 6.3 8 6.3 Q9.8 6.3 9.8 4.9 L9.8 1.6 ' +
    'L10.6 1.6 Q12.6 1.6 12.6 3.6 L12.6 5.4 Q12.6 8.4 9.9 8.9 L9.9 13.1 Q9.9 14.4 8 14.4 ' +
    'Q6.1 14.4 6.1 13.1 L6.1 8.9 Q3.4 8.4 3.4 5.4 Z"/></svg>';

  /** The settings the host has given us, defensively filtered — a row with no key cannot be sent. */
  function items() {
    var list = payload && Array.isArray(payload.items) ? payload.items : [];
    return list.filter(function (it) {
      return it && it.key != null;
    });
  }

  function labelOf(it) {
    return it.label != null ? String(it.label) : String(it.key);
  }

  /** The group an entry belongs to, normalised once so bucketing and lookup cannot disagree. */
  function groupOf(it) {
    return it.group != null ? String(it.group) : '';
  }

  /** `type` defaults to `check` when the host omits it — that default is part of the contract. */
  function isRadio(it) {
    return String(it.type || 'check') === 'radio';
  }

  /**
   * The groups, in the order the host first mentions them, each with its rows.
   *
   * Built from the payload on every read rather than cached: the host re-pushes the whole list, and a cache
   * would be a second copy of what a group contains — which is exactly how the panel on screen and the panel
   * the keyboard walks come to disagree. `hasOwnProperty` and not a truthiness test, because a group called
   * `constructor` would otherwise find one on the prototype.
   */
  function groups() {
    var order = [];
    var byGroup = {};
    items().forEach(function (it) {
      var name = groupOf(it);
      if (!Object.prototype.hasOwnProperty.call(byGroup, name)) {
        byGroup[name] = [];
        order.push(name);
      }
      byGroup[name].push(it);
    });
    return order.map(function (name) {
      return { name: name, list: byGroup[name] };
    });
  }

  /** The rows of the section on screen, or `[]` when the root panel is. */
  function currentGroup() {
    var found = null;
    groups().forEach(function (g) {
      if (g.name === view) found = g;
    });
    return found;
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
    el.innerHTML = WRENCH;
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
  /**
   * One setting. A real control with a programmatic state, never a div with an onclick.
   *
   * `.selected` draws the mark — a ✓ for a switch, a ● for a choice (a NON-colour signal, WCAG 1.4.1) — and
   * `aria-checked` carries the same fact to assistive technology. The full label also goes in the `title`
   * because a tool name can be longer than the popup is wide and the row ellipsises.
   */
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
          tabindex: '-1', // roving: exactly one entry is tabbable at a time (see setRoving)
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

  /**
   * Act on a press.
   *
   * Optimistic, and the menu STAYS OPEN. A pill menu closes on choice because a pill is one choice; this is a
   * panel of settings, and closing it after each one would make changing two settings two trips. The row
   * changes immediately rather than waiting for the host to answer — a switch that does nothing until a round
   * trip completes reads as broken — and the next `cc.settingsMenu` push is authoritative either way, so a
   * refusal upstream simply puts it back.
   *
   * A CHOICE IS NOT A SWITCH, and the difference is on the wire as well as on screen. Exactly one
   * `settingsToggle` leaves for the row that was chosen; the siblings it turns off are NOT announced one by
   * one. The host owns the group and resolves it — sending an `on:false` per sibling would be the page
   * deciding what a group means, and it would race: several toggles for one gesture, applied in whatever
   * order they are read. Pressing the row that is already chosen sends nothing at all, because nothing changed.
   */
  function choose(it, row, radio) {
    var already = row.getAttribute('aria-checked') === 'true';
    if (radio) {
      if (already) return;
      clearGroup(row.__ccGroup, row.__ccKey);
      it.on = true;
      applyState(row, true);
      CC.send({ type: 'settingsToggle', key: String(it.key), on: true });
      // WCAG 4.1.3: the change moves no focus and paints a glyph, so without this it is silent.
      if (CC.announce) CC.announce(labelOf(it) + ' selected');
      return;
    }
    it.on = !already;
    applyState(row, !already);
    CC.send({ type: 'settingsToggle', key: String(it.key), on: !already });
    if (CC.announce) CC.announce(labelOf(it) + (already ? ' off' : ' on'));
  }

  /**
   * Un-choose the rest of a radio group, on screen AND in the stash.
   *
   * Both halves are needed and for different reasons: the DOM half is what the eye sees in the same frame as
   * the press, and the stash half is what the next render reads — leaving it stale would put the old choice
   * back the moment anything redrew, including a push that changed nothing else.
   */
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

  /**
   * One section, as a row of the ROOT panel: press it and the popup becomes that section.
   *
   * `aria-haspopup="menu"` and no `aria-expanded`, and the pair is the whole difference from the accordion it
   * replaces. `aria-expanded` on a heading promises a region that opens IN PLACE, under the heading, with the
   * rest of the menu still around it — which is what this stopped doing. What it does now is replace the
   * panel, so what it announces is that it leads somewhere.
   */
  function groupEntry(g) {
    var name = g.name || 'Settings';
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
            e.stopPropagation(); // never let this reach the document handler that dismisses the menu
            enterGroup(g.name);
          },
        },
      },
      h('span', { class: 'menu-item-label', text: name })
    );
    if (isDeferred(g.list)) {
      // TEXT inside the row, not a `title` and not a colour: it is part of the row's accessible name, so it
      // is heard as well as seen — and it is on the ENTRY, before you go in, because "these apply to new
      // chats" is what decides whether it is worth going in at all.
      row.appendChild(h('span', { class: 'settings-defer', text: DEFER_NOTE }));
    }
    // The chevron is CSS generated content, hidden from the tree: `aria-haspopup` already says it leads
    // somewhere, and a glyph read out as "black right-pointing pointer" says it worse.
    row.appendChild(h('span', { class: 'menu-group-caret', attrs: { 'aria-hidden': 'true' } }));
    row.__ccFocusId = 'g' + SEP + g.name;
    return row;
  }

  /** A group is deferred when anything in it is: the note belongs to the section, and the section is a row. */
  function isDeferred(list) {
    return list.some(function (it) {
      return !!it.deferred;
    });
  }

  /**
   * The section panel's header: back, then the section's name.
   *
   * The same three classes the attach menu's tree header uses, deliberately — one header language for both
   * popups. The back arrow is a real button with a spelled-out name, because an arrow is not a word: a
   * speech-input user has nothing to say to a glyph and a screen reader nothing to read (WCAG 4.1.2, 2.5.3).
   */
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

  /**
   * Go into a section, and come back out of it.
   *
   * The focus is placed explicitly on both transitions and neither is decoration: the panel that had it is
   * gone from the document, and a browser answers that by dropping the focus to `<body>` — out of the menu and
   * out of the roving model, with nothing on screen saying where it went (WCAG 2.2 SC 2.4.3). Going in lands
   * on the section's first row, which is what was pressed for; coming back lands on the ENTRY you came
   * through, not on the top of the list, so a second section is one arrow key away.
   */
  function enterGroup(name) {
    view = name;
    slide = 'attach-from-right';
    repaint();
    focusRow(rows()[1] || rows()[0]);
  }

  function leaveGroup() {
    var came = view;
    view = null;
    slide = 'attach-from-left';
    repaint();
    focusRow(rowForId('g' + SEP + came) || rows()[0]);
  }

  /** The last entry: leaves the page entirely, so unlike the settings it closes the menu behind it. */
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

  /**
   * The panel on screen: the list of sections, or one section's rows.
   *
   * Sections are emitted in the order the host first mentions them, never in a list hardcoded here — which
   * group exists and what it is called is the host's to decide, and a fixed order would silently drop a new
   * one.
   *
   * *Open Plugin Settings* is on the ROOT panel only. Inside a section it would be a way out of the page
   * sitting where the way back is expected, one row from it.
   */
  function buildBody() {
    var frag = document.createDocumentFragment();
    var group = view != null ? currentGroup() : null;
    if (group) {
      // Everything the section holds, in one flat run under its header. `role="group"` carries the section's
      // name to the rows rather than leaving it on the header alone, so a screen reader announces which
      // section a row belongs to without the reader having to remember what they pressed.
      //
      // ITS OWN CLASS, and never `.menu-group-items`. That one is the pill menus' accordion body: the
      // stylesheet declares it `display: none` and reveals it only under a `.menu-group.open` ancestor, which
      // this panel does not have and must not grow. Borrowed here, every section opened to a header with
      // NOTHING under it — the rows were in the DOM, hidden by a rule belonging to a widget this menu stopped
      // being. jsdom lays nothing out, so the tests were green throughout; that is why the contract for this
      // is asserted against the stylesheet's TEXT (see settings-menu.test.js).
      var body = h('div', {
        class: 'settings-section-items',
        attrs: {
          role: 'group',
          'aria-label': group.name || 'Settings',
          id: 'settings-group-' + ++groupSeq,
        },
      });
      group.list.forEach(function (it) {
        body.appendChild(settingRow(it, group.name));
      });
      frag.appendChild(groupHead(group.name));
      frag.appendChild(body);
      return frag;
    }
    var all = groups();
    if (!all.length) {
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
      all.forEach(function (g) {
        frag.appendChild(groupEntry(g));
      });
    }
    frag.appendChild(h('div', { class: 'menu-sep', attrs: { role: 'separator' } }));
    frag.appendChild(openSettingsRow());
    return frag;
  }

  // ---- rendering ------------------------------------------------------------
  /**
   * What is actually drawn, so an identical push can be recognised as one.
   *
   * The `on` flags are NOT in it — that is the `syncStates` path, and keeping them out is what stops a flag
   * change from rebuilding the rows and blurring the focused one. `type` and `deferred` ARE in it: they
   * decide the role a row is given and whether its heading carries the note, so a push that changed only one
   * of them would otherwise leave a radio painted as a switch.
   */
  function structureSig() {
    var list = items();
    // WHICH PANEL is part of the structure: the same payload draws the list of sections or one section's
    // rows, so without this a push landing after a drill-down would compare equal and the panel would never
    // be redrawn.
    var sig = (view == null ? '' : view) + '|' + list.length + '|';
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      var f = [groupOf(it), it.key, labelOf(it), isRadio(it) ? 'r' : 'c', it.deferred ? '1' : '0'];
      sig += f.join(SEP) + '|';
    }
    return sig;
  }

  /** Every entry in the popup, folded-away rows included. The set state and the tab stop are decided here. */
  function allRows() {
    if (!menu) return [];
    return Array.prototype.slice.call(
      menu.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
    );
  }

  /**
   * The entries the keyboard may visit — every entry in the panel, because only one panel exists at a time.
   *
   * It used to subtract the rows of a folded group, which is the one thing the drill-down removed rather than
   * moved: a section that is not on screen is not in the DOM at all, so there is nothing to filter and no way
   * for the roving tab stop to land on something invisible. Kept as its own function because every navigation
   * path below asks it, and because the day a panel holds something unreachable this is where that is said.
   */
  function rows() {
    return allRows();
  }

  /**
   * Draw the current payload into the open popup, rebuilding only when the STRUCTURE changed.
   *
   * The host re-pushes on its own schedule, and two rules govern anything it re-pushes here. A rebuild
   * destroys and recreates every row, so whatever was focused inside is blurred — hence the states-only path
   * when nothing but a flag moved, and the focus restore by id when there is no way around a rebuild. The
   * popup is also re-positioned afterwards, because a row more or less changes its height and it hangs
   * upwards from its anchor. What a rebuild does NOT reset is which panel is on screen: `view` lives outside
   * the DOM, precisely so a push landing mid-section does not throw the reader back to the root.
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
    var wanted = focusedId();
    drawnSig = sig;
    menu.textContent = '';
    var body = h('div', { class: 'settings-body' + (slide ? ' ' + slide : '') });
    body.appendChild(buildBody());
    menu.appendChild(body);
    slide = '';
    var target = rowForId(wanted) || rows()[0] || null;
    if (hadFocus) focusRow(target);
    else setRoving(target); // one entry stays tabbable, or Tab could not reach the menu at all
    if (CX.positionMenu) CX.positionMenu(menu, btn);
  }

  /**
   * Redraw for a change this file made itself — going into a section, or coming back out.
   *
   * `render` skips when the signature is unchanged, which is right for a host push and wrong here: the
   * signature carries `view`, so it does move, but the caller has already set the focus target it wants and
   * must not have `render`'s own restore-by-id decide it instead. Dropping the stamp is what makes the next
   * `render` unconditional, and the caller focuses after it returns.
   */
  function repaint() {
    drawnSig = '';
    render();
  }

  function syncStates() {
    var by = {};
    items().forEach(function (it) {
      by[String(it.key)] = !!it.on;
    });
    // Over every row of the panel on screen. The sections that are NOT on screen hold no rows to update —
    // they are not in the DOM — and their state is read off the payload when they are built.
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

  /**
   * The entry for a focus id, matched by IDENTITY and not by position — the point is to survive a reorder.
   *
   * The id is this file's own bookkeeping and never leaves it: a setting row is found by its host key, a
   * heading by its group name. Only navigable entries are candidates, because putting the focus on a row
   * inside a folded group would be putting it nowhere.
   */
  function rowForId(id) {
    if (id == null) return null;
    var all = rows();
    for (var i = 0; i < all.length; i++) {
      if (all[i].__ccFocusId === id) return all[i];
    }
    return null;
  }

  /** Roving tabindex: exactly one entry is in the tab order, so Tab enters the menu once and then leaves. */
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

  /**
   * The keyboard model `role="menu"` promises, with the drill-down's own two keys.
   *
   * **Escape leaves ONE level**, which is the submenu behaviour of the ARIA menu pattern: inside a section it
   * goes back to the list, and only at the list does it dismiss the popup and hand the focus back to the gear.
   * Anything else makes the deepest panel the one place where the reader's habitual key throws away the whole
   * navigation. Tab always leaves entirely, and without swallowing the event, so the browser carries on from a
   * control still in the document rather than from the `<body>` a removed row leaves behind.
   *
   * **Right enters a section and Left comes back**, which is what a menu with submenus does everywhere else.
   * Right acts only on a section entry: on a setting row there is nothing to enter, and swallowing the press
   * there would take the arrow key away from the caret handling the composer still owns.
   */
  function onMenuKey(e) {
    if (e.key === 'Escape' || e.key === 'Esc') {
      // The menu owns this press and nothing downstream may read it as anything else. As things stand today
      // nothing would: the composer's Escape (which interrupts the running turn) is bound to the textarea,
      // and focus is inside the popup. So this is a boundary rather than a fix — it is what keeps the next
      // document-level Escape handler from turning a dismissal into an interrupt.
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
    // Always at the list of sections, and never mid-section: see [view]. No slide on the first paint either —
    // the popup is already appearing, and a panel sliding in inside something that is itself appearing reads
    // as two animations for one gesture.
    view = null;
    slide = '';
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
   * Host → page: the settings this chat offers.
   *
   * `{items:[{key, group, label, on, type, deferred}]}` — `type` is `"check"` (the default when absent) or
   * `"radio"`, and `deferred` marks a group whose changes only reach the NEXT chat. The page paints what it
   * is sent and hands `key` back untouched; which keys exist, what a group means and how a radio group
   * resolves are the host's.
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
