/* app-composer.js — A3 (composer)
 * Owns: cc.state(s), cc.meta(m), cc.openPalette(), cc.focusInput()
 * Builds the composer DOM in CC.els.composer and the slash palette in CC.els.palette.
 * Pure renderer: never holds data/transport, only emits via CC.send().
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var cc = window.cc || (window.cc = {});

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

  // ---- module state ---------------------------------------------------------
  var built = false;
  var els = null; // { card, input, send, pills:{provider,model,mode,effort,thinking}, queue, ghost, readout, sendIcon }
  var lastState = null; // last cc.state payload
  var announcedBoot = false; // guards the boot screen's one-per-boot screen-reader announcement
  var announcedMissing = false; // ditto for the "not found" card, which replaces the loading announcement
  var installMethods = []; // from cc.meta: the OS's install routes for the not-found card
  var installsBuilt = false; // the card's method rows are built once per methods payload
  var installingId = null; // method id whose button shows "Installing…" (cleared on error/card teardown)
  var commands = []; // from cc.meta
  var hostClipboard = false; // from cc.meta: native-Wayland toolkit → route paste through the host (wl-paste)
  var ghostText = ''; // current ghost suggestion (empty field only)
  var openMenu = null; // { el, closer } for an open pill menu
  var paletteState = { items: [], active: 0 };
  var attachmentsList = []; // last cc.attachments payload: [{id,label,kind}]
  var followOn = true; // auto-follow scrolling (on by default)
  var followBtnRef = null;

  function applyFollow() {
    if (followBtnRef) {
      if (followOn) followBtnRef.classList.add('active');
      else followBtnRef.classList.remove('active');
    }
    if (window.CC && typeof CC.emit === 'function') CC.emit('follow', followOn);
  }

  // pill key → which state field + how to map a chosen option to a message
  var PILL_DEFS = [
    {
      key: 'provider',
      field: 'provider',
      idKey: 'id',
      msg: function (o) {
        return { type: 'changeProvider', id: o.id };
      },
    },
    {
      key: 'model',
      field: 'model',
      idKey: 'value',
      msg: function (o) {
        return { type: 'changeModel', value: o.value };
      },
    },
    {
      key: 'mode',
      field: 'mode',
      idKey: 'wire',
      msg: function (o) {
        return { type: 'changeMode', wire: o.wire };
      },
    },
    {
      key: 'effort',
      field: 'effort',
      idKey: 'value',
      msg: function (o) {
        return { type: 'changeEffort', value: o.value == null ? null : o.value };
      },
    },
    {
      key: 'thinking',
      field: 'thinking',
      idKey: 'on',
      msg: function (o) {
        return { type: 'changeThinking', on: !!o.on };
      },
    },
  ];

  // ---- SVG glyphs (inline, themeable via currentColor) ----------------------
  function sendGlyph() {
    return (
      '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
      '<path fill="currentColor" d="M3.4 20.4 21 12 3.4 3.6 3.4 10l11 2-11 2z"/></svg>'
    );
  }
  function stopGlyph() {
    return (
      '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">' +
      '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/></svg>'
    );
  }
  // auto-follow scrolling (chevrons pointing down) — chip-follow.svg
  function followGlyph() {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M4 4.5 8 8l4-3.5"/><path d="M4 9 8 12.5l4-3.5"/></svg>'
    );
  }
  // history / rollback (a clock with a counter-clockwise arrow)
  function historyGlyph() {
    return (
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M3 3v5h5"/><path d="M3.05 13a9 9 0 1 0 2.6-6.36L3 8"/><path d="M12 7v5l3 2"/></svg>'
    );
  }
  // attach.svg from the previous UI (paperclip), themed via currentColor.
  function attachGlyph() {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M12.75 7.5 7.5 12.75a3 3 0 0 1-4.25-4.25l5.75-5.75a2 2 0 0 1 2.83 2.83l-5.75 5.75a1 1 0 0 1-1.42-1.42l5.09-5.09"/></svg>'
    );
  }
  // small kind glyph for an attachment chip (file | selection | image)
  function attIconGlyph(kind) {
    if (kind === 'image') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<rect x="3" y="4" width="18" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>' +
        '<circle cx="8.5" cy="9" r="1.6" fill="currentColor"/>' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="m4 18 5-5 4 4 3-3 4 4"/></svg>'
      );
    }
    if (kind === 'selection') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
        'd="M4 7V5a1 1 0 0 1 1-1h2M4 17v2a1 1 0 0 0 1 1h2M20 7V5a1 1 0 0 0-1-1h-2M20 17v2a1 1 0 0 1-1 1h-2M8 12h8"/></svg>'
      );
    }
    // file (default)
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" ' +
      'd="M6 2h7l5 5v15a0 0 0 0 1 0 0H6a0 0 0 0 1 0 0V2Z"/>' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="M13 2v5h5"/></svg>'
    );
  }

  // ---- build (lazy, once) ---------------------------------------------------
  function ensureBuilt() {
    if (built) return true;
    if (!CC.els || !CC.els.composer) return false;
    built = true;

    var mount = CC.els.composer;
    mount.innerHTML = '';

    // ghost suggestion (above card)
    var ghost = h('div', { class: 'ghost', attrs: { hidden: 'hidden' } });

    // queue strip (above card)
    var queue = h('div', { class: 'queue' });

    // textarea (two lines tall by default)
    var input = h('textarea', {
      class: 'composer-input',
      attrs: { rows: '2', placeholder: 'Ask Claude, or type / for commands', 'aria-label': 'Message Claude' },
    });

    // attach (📎) button
    var attachBtn = h('button', {
      class: 'attach-btn',
      title: 'Attach files',
      attrs: { type: 'button', 'aria-label': 'Attach files' },
    });
    attachBtn.innerHTML = attachGlyph();
    attachBtn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      toggleAttachMenu(attachBtn);
    });

    // send / stop button
    var sendBtn = h('button', {
      class: 'send-btn',
      title: 'Send',
      attrs: { type: 'button', 'aria-label': 'Send' },
    });
    sendBtn.innerHTML = sendGlyph();
    sendBtn.addEventListener('click', onSendClick);

    // input row: just the textarea now — all controls live in the flat bar below.
    var inputRow = h('div', { class: 'composer-input-row' }, input);

    // attachments chip row (above the card)
    var attachments = h('div', { class: 'attachments', attrs: { hidden: 'hidden' } });

    // ── controls bar (flat, single row, divided by lines) ──────────────────────
    // left:  📎 · Provider · Model · Mode · Effort · Thinking
    // right: 🌈 (icon only) · Send/Stop (snug in the corner)
    var pills = {};
    var barLeft = h('div', { class: 'bar-left' });
    barLeft.appendChild(attachBtn);
    for (var i = 0; i < PILL_DEFS.length; i++) {
      var def = PILL_DEFS[i];
      var pill = buildPill(def);
      pills[def.key] = pill;
      barLeft.appendChild(pill.el);
    }
    // Vibe Mode toggle — icon only (Nyan Cat); global gag, not part of session state.
    var vibeIcon = h('span', { class: 'vibe-emoji' });
    vibeIcon.innerHTML = window.CC && CC.nyanSvg ? CC.nyanSvg() : '🌈';
    var vibeBtn = h(
      'button',
      {
        class: 'bar-icon pill-vibe',
        title: 'Vibe Mode',
        attrs: { type: 'button', 'aria-label': 'Vibe Mode' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            var on = !(window.CC && CC.isVibe && CC.isVibe());
            send({ type: 'changeVibe', on: on });
          },
        },
      },
      vibeIcon
    );
    // History / rollback button — opens the session's Diff History (view diffs + roll back edits).
    var historyBtn = h('button', {
      class: 'bar-icon',
      title: 'Session history · diffs & rollback',
      attrs: { type: 'button', 'aria-label': 'Session history and rollback' },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          send({ type: 'openDiffHistory' });
        },
      },
    });
    historyBtn.innerHTML = historyGlyph();
    // Auto-follow scrolling toggle (on by default). Emits on the CC bus; the transcript follows.
    var followBtn = h('button', {
      class: 'bar-icon active',
      title: 'Auto-scroll (follow output)',
      attrs: { type: 'button', 'aria-label': 'Auto-follow scrolling' },
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          followOn = !followOn;
          applyFollow();
        },
      },
    });
    followBtn.innerHTML = followGlyph();
    followBtnRef = followBtn;
    var barRight = h('div', { class: 'bar-right' }, followBtn, vibeBtn, historyBtn, sendBtn);
    var bar = h('div', { class: 'composer-bar' }, barLeft, barRight);

    // readout (subtle session line)
    var readout = h('div', { class: 'readout', attrs: { hidden: 'hidden' } });
    // Plan limits, one bar per window, on their own row under the readout — see renderUsageBars().
    var usageBars = h('div', { class: 'usage-bars', attrs: { hidden: 'hidden' } });

    var card = h('div', { class: 'composer-card' }, inputRow, bar);

    mount.appendChild(ghost);
    mount.appendChild(queue);
    mount.appendChild(attachments);
    mount.appendChild(readout); // session-usage line sits ABOVE the prompt box
    mount.appendChild(usageBars); // …and the plan-limit bars directly under it
    mount.appendChild(card);

    els = {
      card: card,
      input: input,
      send: sendBtn,
      pills: pills,
      queue: queue,
      ghost: ghost,
      readout: readout,
      usageBars: usageBars,
      attachments: attachments,
      attachBtn: attachBtn,
    };

    wireInput(input);
    wireImageDrop(card);
    wireImagePaste(input);

    // render any attachments that arrived before build
    renderAttachments(attachmentsList);

    // outside-click / Esc closes any open pill menu
    document.addEventListener('mousedown', onDocMouseDown, true);

    // if state already arrived, render it now
    if (lastState) renderState(lastState);
    renderGhost();
    // sync the initial auto-follow state to the transcript (retry shortly in case the bus isn't ready)
    applyFollow();
    setTimeout(applyFollow, 60);

    // Claim the DOM focus as soon as the composer exists. Only half the story: a page cannot grant itself the
    // IDE's keyboard focus — the host hands the browser that (JcefHost.requestFocus / markWebReady).
    els.input.focus();
    return true;
  }

  // Inline chip icons for the composer pills (themeable via currentColor; ride Vibe Mode).
  // Ported from resources/icons/chip-*.svg + provider marks.
  var CHIP_ICONS = {
    model:
      '<rect x="5" y="5" width="6" height="6" rx="1.2"/><path d="M6.5 3v1.8M9.5 3v1.8M6.5 11.2V13M9.5 11.2V13M3 6.5h1.8M3 9.5h1.8M11.2 6.5H13M11.2 9.5H13"/>',
    mode: '<path d="M8 2.5 13 4.3v3.4c0 3-2.1 4.9-5 5.8-2.9-.9-5-2.8-5-5.8V4.3z"/><path d="M6 7.8 7.5 9.3 10.2 6.4"/>',
    effort: '<path d="M2.8 11.6a5.2 5.2 0 0 1 10.4 0"/><path d="M8 11.6 10.4 8.1"/>',
    thinking:
      '<path d="M7.5 2.4c.5 2.9 1.6 4 4.5 4.5-2.9.5-4 1.6-4.5 4.5-.5-2.9-1.6-4-4.5-4.5 2.9-.5 4-1.6 4.5-4.5z"/>',
  };
  // Provider brand marks keep their own colours (as in the previous UI), so they're separate
  // from the monochrome currentColor chips. provider.svg from resources/icons/provider-*.svg.
  var PROVIDER_MARKS = {
    anthropic:
      '<g stroke="#D97757" stroke-width="1.7" stroke-linecap="round" fill="none"><path d="M8 2.4v11.2M3.15 5.2l9.7 5.6M12.85 5.2l-9.7 5.6"/></g>',
    deepseek:
      '<path d="M2.4 8.4c0-2.1 1.9-3.8 4.4-3.8 2.3 0 4.2 1.4 4.6 3.4.6-.2 1.1-.6 1.5-1.1.1 1-.3 1.9-1 2.5.5.1 1 .05 1.5-.2-.5 1.2-1.8 2.1-3.4 2.1H6.8c-2.5 0-4.4-1.8-4.4-3.9z" fill="#4D6BFE"/><circle cx="6" cy="7.7" r=".75" fill="#FFFFFF"/>',
  };
  function providerMarkSvg(id) {
    var inner = PROVIDER_MARKS[id] || PROVIDER_MARKS.anthropic;
    return '<svg viewBox="0 0 16 16" aria-hidden="true">' + inner + '</svg>';
  }
  function chipIconSvg(key) {
    if (key === 'provider') return providerMarkSvg('anthropic'); // refreshed per selection in renderPills
    var inner = CHIP_ICONS[key];
    if (!inner) return null;
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      inner +
      '</svg>'
    );
  }

  function buildPill(def) {
    var label = h('span', { class: 'pill-label', text: '' });
    var caret = h('span', { class: 'pill-caret', text: '▾' });
    var iconMarkup = chipIconSvg(def.key);
    var icon = iconMarkup ? h('span', { class: 'pill-icon', html: iconMarkup }) : null;
    var el = h(
      'button',
      {
        class: 'pill',
        attrs: { type: 'button', 'data-pill': def.key },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            togglePillMenu(def, el);
          },
        },
      },
      icon,
      label,
      caret
    );
    return { el: el, label: label, def: def, icon: icon };
  }

  // ---- textarea behavior ----------------------------------------------------
  function wireInput(input) {
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doSend();
        return;
      }
      if (e.key === 'Enter' && e.shiftKey) {
        return; // newline (default)
      }
      if (e.key === 'Escape') {
        if (openMenu) {
          closeMenu();
          e.preventDefault();
          return;
        }
        // Only interrupt when a turn is actually running and we're not already interrupting — otherwise Escape
        // in an idle composer would fire a pointless interrupt request.
        if (lastState && lastState.turnActive && !lastState.interrupting) {
          e.preventDefault();
          send({ type: 'interrupt' });
        }
        return;
      }
      if (e.key === 'Tab' && e.shiftKey) {
        e.preventDefault();
        send({ type: 'cycleMode' });
        return;
      }
      if (e.key === 'Tab' && !e.shiftKey && ghostText && input.value === '') {
        // accept ghost suggestion into the field (no auto-send)
        e.preventDefault();
        input.value = ghostText;
        ghostText = '';
        renderGhost();
        autosize(input);
        return;
      }
    });

    input.addEventListener('input', function () {
      autosize(input);
      // any typing clears the ghost suggestion display
      if (ghostText) renderGhost();
      // a lone "/" in an (otherwise empty) field opens the palette
      if (input.value === '/') {
        openPalette();
      }
    });
  }

  function autosize(input) {
    if (!input) return;
    input.style.height = 'auto';
    var max = 220;
    input.style.height = Math.min(input.scrollHeight, max) + 'px';
  }

  function onSendClick(e) {
    e.preventDefault();
    if (lastState && lastState.interrupting) {
      return; // interrupt already in flight; the button is showing "Interrupting…"
    }
    if (lastState && lastState.turnActive) {
      send({ type: 'interrupt' });
    } else {
      doSend();
    }
  }

  function doSend() {
    if (!els || !els.input) return;
    var text = els.input.value;
    if (text == null) return;
    var trimmed = text.replace(/\s+$/, '');
    if (trimmed.length === 0) {
      // nothing typed; if turn active the button still interrupts, handled by onSendClick
      return;
    }
    send({ type: 'send', text: text });
    els.input.value = '';
    ghostText = '';
    renderGhost();
    autosize(els.input);
  }

  // ---- pill menus -----------------------------------------------------------
  function currentOptions(def) {
    if (!lastState) return [];
    var f = lastState[def.field];
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
      sig += (opts[i].selected ? '1' : '0') + (opts[i].label != null ? String(opts[i].label) : '') + '';
    }
    return sig;
  }

  function togglePillMenu(def, anchorEl) {
    if (openMenu && openMenu.pill === def.key) {
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
              if (openMenu && openMenu.anchor) positionMenu(menu, openMenu.anchor);
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
    openMenu = { el: menu, pill: def.key, anchor: anchorEl, sig: menuSig(def) };
  }

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

  function chooseOption(def, o) {
    closeMenu();
    var msg = def.msg(o);
    // §COMPOSER: effort value null → send {type:'changeEffort'} with value:null (acceptable)
    send(msg);
  }

  function closeMenu() {
    if (!openMenu) return;
    if (openMenu.el && openMenu.el.parentNode) openMenu.el.parentNode.removeChild(openMenu.el);
    if (openMenu.anchor) openMenu.anchor.classList.remove('pill-open');
    openMenu = null;
  }

  function onDocMouseDown(e) {
    if (openMenu) {
      if (openMenu.el && openMenu.el.contains(e.target)) return;
      if (openMenu.anchor && openMenu.anchor.contains(e.target)) return;
      closeMenu();
    }
  }

  // ---- attach menu (📎) ------------------------------------------------------
  // The 📎 button opens a small popup of context sources rather than jumping
  // straight to a file picker: files, a directory, the current editor selection,
  // the current file, and a Wayland-safe "paste image from clipboard" (read by
  // the host via AWT, since JCEF's web clipboard is unreliable under Wayland).
  // Rich attach menu (AI-Assistant-style): a search box, the attach actions, and a filterable
  // "Recent files" list. Recent files + available-context flags come from the host via cc.attachData.
  var lastAttachData = { recent: [], hasSelection: false, hasFile: false };

  function fileIconGlyph(ext) {
    var e = (ext || '').toLowerCase();
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].indexOf(e) !== -1) return attIconGlyph('image');
    return attIconGlyph('file');
  }

  function attachMenuItem(label, onClick) {
    return h(
      'div',
      {
        class: 'menu-item',
        attrs: { role: 'option' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            onClick();
          },
        },
      },
      h('span', { class: 'menu-item-label', text: label })
    );
  }

  // `_anchorEl` is kept in the signature because both call sites pass the anchor and the menu may need to
  // position against it again; nothing in the body reads it today.
  function renderAttachMenu(menu, _anchorEl) {
    menu.innerHTML = '';
    var search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: 'Search recent files…' },
    });
    var list = h('div', { class: 'attach-list' });

    function paint(q) {
      list.innerHTML = '';
      var actions = [
        {
          label: 'Files…',
          fn: function () {
            send({ type: 'pickFiles' });
          },
        },
        {
          label: 'Directory…',
          fn: function () {
            send({ type: 'pickDirectory' });
          },
        },
        {
          label: 'Image…',
          fn: function () {
            send({ type: 'pasteClipboardImage', notify: true });
          },
        },
      ];
      if (lastAttachData.hasSelection)
        actions.push({
          label: 'Current selection',
          fn: function () {
            send({ type: 'attachSelection' });
          },
        });
      if (lastAttachData.hasFile)
        actions.push({
          label: 'Current file',
          fn: function () {
            send({ type: 'attachCurrentFile' });
          },
        });
      actions.forEach(function (a) {
        list.appendChild(
          attachMenuItem(a.label, function () {
            closeMenu();
            a.fn();
          })
        );
      });

      var recent = Array.isArray(lastAttachData.recent) ? lastAttachData.recent : [];
      var ql = (q || '').toLowerCase();
      var matched = recent.filter(function (r) {
        return (
          !ql ||
          String(r.name || '')
            .toLowerCase()
            .indexOf(ql) !== -1 ||
          String(r.path || '')
            .toLowerCase()
            .indexOf(ql) !== -1
        );
      });
      if (matched.length) {
        list.appendChild(h('div', { class: 'attach-section', text: 'Recent files' }));
        matched.forEach(function (r) {
          var row = h(
            'div',
            {
              class: 'menu-item attach-recent',
              attrs: { role: 'option', title: String(r.path || '') },
              on: {
                click: function (e) {
                  e.preventDefault();
                  e.stopPropagation();
                  closeMenu();
                  send({ type: 'attachPath', path: r.path });
                },
              },
            },
            h('span', { class: 'attach-icon', html: fileIconGlyph(r.ext) }),
            h('span', { class: 'attach-name', text: String(r.name || r.path || '') })
          );
          list.appendChild(row);
        });
      }
    }

    menu.appendChild(search);
    menu.appendChild(list);
    search.addEventListener('input', function () {
      paint(search.value);
      if (openMenu && openMenu.anchor) positionMenu(menu, openMenu.anchor);
    });
    search.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeMenu();
      }
    });
    paint('');
    setTimeout(function () {
      try {
        search.focus();
      } catch (e) {
        /* ignore */
      }
    }, 0);
  }

  function toggleAttachMenu(anchorEl) {
    if (openMenu && openMenu.pill === '__attach') {
      closeMenu();
      return;
    }
    closeMenu();
    var menu = h('div', { class: 'menu attach-menu' });
    document.body.appendChild(menu);
    openMenu = { el: menu, pill: '__attach', anchor: anchorEl };
    renderAttachMenu(menu, anchorEl);
    positionMenu(menu, anchorEl);
    anchorEl.classList.add('pill-open');
    send({ type: 'requestAttachData' }); // refresh recents + context; cc.attachData re-renders
  }

  // Host pushes recent files + available context; re-render the menu if it's open.
  cc.attachData = function (payload) {
    if (payload && typeof payload === 'object') {
      lastAttachData = {
        recent: Array.isArray(payload.recent) ? payload.recent : [],
        hasSelection: !!payload.hasSelection,
        hasFile: !!payload.hasFile,
      };
    }
    if (openMenu && openMenu.pill === '__attach' && openMenu.el) {
      renderAttachMenu(openMenu.el, openMenu.anchor);
      positionMenu(openMenu.el, openMenu.anchor);
    }
  };

  // ---- ghost suggestion -----------------------------------------------------
  function renderGhost() {
    if (!els || !els.ghost) return;
    var show = ghostText && els.input && els.input.value === '';
    if (show) {
      els.ghost.textContent = ghostText;
      els.ghost.removeAttribute('hidden');
      els.ghost.title = 'Press Tab to use this suggestion';
    } else {
      els.ghost.textContent = '';
      els.ghost.setAttribute('hidden', 'hidden');
    }
  }

  // ---- queue strip ----------------------------------------------------------
  var lastQueueKey = null; // skip rebuilds when the queue is unchanged (state pushes are frequent)
  function renderQueue(queue) {
    if (!els || !els.queue) return;
    var key = JSON.stringify(Array.isArray(queue) ? queue : []);
    if (key === lastQueueKey) return; // unchanged → don't wipe/rebuild (was causing flicker)
    lastQueueKey = key;
    els.queue.innerHTML = '';
    if (!Array.isArray(queue) || queue.length === 0) {
      els.queue.setAttribute('hidden', 'hidden');
      return;
    }
    els.queue.removeAttribute('hidden');
    for (var i = 0; i < queue.length; i++) {
      (function (text, index) {
        var x = h('span', {
          class: 'queue-x',
          text: '✕',
          title: 'Remove from queue',
          attrs: { role: 'button', 'aria-label': 'Remove queued prompt' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              send({ type: 'removeQueued', index: index });
            },
          },
        });
        var chip = h(
          'span',
          { class: 'queue-chip', title: text },
          h('span', { class: 'queue-text', text: text }),
          x
        );
        els.queue.appendChild(chip);
      })(String(queue[i]), i);
    }
  }

  // ---- attachments ----------------------------------------------------------
  function renderAttachments(list) {
    if (!els || !els.attachments) return;
    var row = els.attachments;
    row.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      row.setAttribute('hidden', 'hidden');
      return;
    }
    row.removeAttribute('hidden');
    for (var i = 0; i < list.length; i++) {
      (function (att) {
        if (!att || att.id == null) return;
        var kind = att.kind != null ? String(att.kind) : 'file';
        var label = att.label != null ? String(att.label) : '';
        var icon = h('span', { class: 'att-icon', html: attIconGlyph(kind) });
        var name = h('span', { class: 'att-label', text: label });
        var x = h('span', {
          class: 'att-x',
          text: '✕',
          title: 'Remove attachment',
          attrs: { role: 'button', 'aria-label': 'Remove attachment' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              send({ type: 'removeAttachment', id: att.id });
            },
          },
        });
        var chip = h('span', { class: 'att-chip att-' + kind, title: label }, icon, name, x);
        row.appendChild(chip);
      })(list[i]);
    }
  }

  // Read an image File as raw base64 (no data: prefix) and emit {type:'attach'}.
  function attachImageFile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var result = reader.result;
      if (typeof result !== 'string') return;
      // strip "data:<mime>;base64," prefix → raw payload
      var comma = result.indexOf(',');
      var base64 = comma >= 0 ? result.slice(comma + 1) : result;
      send({
        type: 'attach',
        name: file.name != null ? String(file.name) : 'image',
        mediaType: file.type != null ? String(file.type) : 'application/octet-stream',
        base64: base64,
      });
    };
    try {
      reader.readAsDataURL(file);
    } catch (e) {
      /* ignore unreadable file */
    }
  }

  function isImageFile(f) {
    return !!(f && typeof f.type === 'string' && f.type.indexOf('image/') === 0);
  }

  // drag image file onto the composer card → attach
  function wireImageDrop(card) {
    if (!card) return;
    card.addEventListener('dragover', function (e) {
      // signal we accept a drop (and stop the browser navigating to the file)
      if (e.preventDefault) e.preventDefault();
      if (e.dataTransfer) {
        try {
          e.dataTransfer.dropEffect = 'copy';
        } catch (x) {
          /* ignore */
        }
      }
      card.classList.add('drag-over');
    });
    card.addEventListener('dragleave', function (e) {
      if (e && e.target === card) card.classList.remove('drag-over');
    });
    card.addEventListener('drop', function (e) {
      if (e.preventDefault) e.preventDefault();
      card.classList.remove('drag-over');
      var dt = e.dataTransfer;
      if (!dt || !dt.files) return;
      for (var i = 0; i < dt.files.length; i++) {
        if (isImageFile(dt.files[i])) attachImageFile(dt.files[i]);
      }
    });
  }

  // paste an image into the textarea → attach; non-image paste falls through
  // Insert text at the caret (JCEF's native paste is unreliable, so we do it ourselves).
  function insertAtCursor(input, text) {
    var start = input.selectionStart != null ? input.selectionStart : input.value.length;
    var end = input.selectionEnd != null ? input.selectionEnd : input.value.length;
    var v = input.value;
    input.value = v.slice(0, start) + text + v.slice(end);
    var pos = start + text.length;
    try {
      input.setSelectionRange(pos, pos);
    } catch (e) {
      /* ignore */
    }
    autosize(input);
  }

  // One paste handler, mutually-exclusive branches, exactly ONE action — never duplicates.
  function wireImagePaste(input) {
    if (!input) return;
    input.addEventListener('paste', function (e) {
      // Native-Wayland toolkit: CEF's web clipboard is isolated from the system clipboard, so
      // `clipboardData` only ever exposes what was copied *inside* the web view — never the system
      // selection. Ignore it entirely and let the host read the real clipboard via wl-paste.
      if (hostClipboard) {
        e.preventDefault();
        send({ type: 'pasteClipboard' });
        return;
      }

      var cd = e.clipboardData || window.clipboardData;
      if (!cd) return;

      // 1) Image already in the web clipboard (X11 / Chromium path).
      var images = [];
      var items = cd.items;
      if (items) {
        for (var i = 0; i < items.length; i++) {
          var it = items[i];
          if (it && it.kind === 'file' && typeof it.type === 'string' && it.type.indexOf('image/') === 0) {
            var f = it.getAsFile();
            if (f) images.push(f);
          }
        }
      }
      if (images.length === 0 && cd.files && cd.files.length) {
        for (var j = 0; j < cd.files.length; j++) {
          if (isImageFile(cd.files[j])) images.push(cd.files[j]);
        }
      }
      if (images.length > 0) {
        e.preventDefault();
        for (var k = 0; k < images.length; k++) attachImageFile(images[k]);
        return;
      }

      // 2) Plain text already in the web clipboard → insert it ourselves (one insert, no double-paste).
      var text;
      try {
        text = (cd.getData && (cd.getData('text/plain') || cd.getData('text'))) || '';
      } catch (x) {
        text = ''; // a DataTransfer that refuses getData — treat it as "no text on the clipboard"
      }
      if (text) {
        e.preventDefault();
        insertAtCursor(input, text);
        return;
      }

      // 3) Web clipboard empty (the Wayland case for BOTH text and images) → let the host read the
      //    system clipboard (text via AWT, image via wl-paste) and either attach or insert text.
      e.preventDefault();
      send({ type: 'pasteClipboard' });
    });
  }

  // ---- readout --------------------------------------------------------------
  // Session-usage line: a running/idle status dot + context %, tokens out, cost. Always visible.
  function renderReadout(s) {
    if (!els || !els.readout) return;
    var ro = els.readout;
    ro.innerHTML = '';
    var running = !!s.turnActive;

    var status = h(
      'span',
      { class: 'ro-item' },
      h('span', { class: 'ro-dot' + (running ? ' running' : '') }),
      h('span', { text: running ? (s.thinkingStatus ? s.thinkingStatus : 'Running…') : 'Idle' })
    );
    ro.appendChild(status);

    // These three are ALWAYS rendered, settling at 0 rather than being omitted until they are non-zero.
    // Hiding an item until it has a value makes "nothing has happened yet" look identical to "this failed to
    // load", which is exactly how the readout read on a fresh tab: a lone "Idle" and no numbers. A zero is a
    // measurement; an absence is not.
    var ctxPct = s.context && typeof s.context.pct === 'number' ? Math.round(s.context.pct) : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: 'Context ' + ctxPct + '%' }));

    var out = typeof s.tokensOut === 'number' ? s.tokensOut : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: formatTokens(out) + ' out' }));

    var reasoning = typeof s.reasoningTokens === 'number' ? s.reasoningTokens : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: formatTokens(reasoning) + ' reasoning' }));

    // Cost stays gated: unlike the counters above it is a currency amount, and "$0.0000" on every idle tab is
    // noise rather than information — there is no ambiguity to resolve, since a session that has spent nothing
    // has nothing to report.
    if (typeof s.costUsd === 'number' && s.costUsd > 0) {
      ro.appendChild(h('span', { class: 'ro-item', text: '$' + s.costUsd.toFixed(s.costUsd < 1 ? 4 : 2) }));
    }

    // NB no sign-out control here. The readout is a wrapping flex row of metrics, so a button pushed to its
    // far end drops onto a second line the moment the numbers fill the width. Log out lives in the tool
    // window's title bar (ClaudeToolWindowFactory.SignOutAction) and on the dashboard's account row.
    ro.removeAttribute('hidden');
    if (running && s.thinkingStatus) ro.classList.add('thinking');
    else ro.classList.remove('thinking');

    renderUsageBars(s);
  }

  /**
   * Plan limits, one labelled bar per window, on their own row directly under the readout.
   *
   * They used to be dots inline in the readout, which put them at the end of a wrapping row of unrelated
   * metrics: the windows that matter most (the ones nearest their cap) were the ones most likely to be pushed
   * onto a second line or off the visible width. A bar reads the fill at a glance where a dot only reads a
   * colour, and giving them their own row means the length of the status line can no longer displace them.
   *
   * The row is a `repeat(auto-fit, minmax(…, 1fr))` grid, so it is one bar per column at full tool-window
   * width and reflows to fewer, still-full-width columns as the window narrows — never a fixed track that
   * leaves dead space on the right or overflows on the left.
   */
  function renderUsageBars(s) {
    if (!els || !els.usageBars) return;
    var host = els.usageBars;
    host.innerHTML = '';
    var usage = Array.isArray(s.usage) ? s.usage : [];
    var shown = 0;
    for (var u = 0; u < usage.length; u++) {
      var win = usage[u] || {};
      if (typeof win.pct !== 'number') continue;
      var label = String(win.label || '');
      var pct = win.pct.toFixed(1) + '%';
      // The BAR is clamped to 0..100 so a server figure past its cap cannot overflow the track; the TEXT is
      // not, because a window reported at 103% is exactly the number the user needs to see.
      var fill = h('i', { class: usageLevel(win.pct) });
      fill.style.width = Math.max(0, Math.min(100, win.pct)) + '%';
      // How long the window has left, on its OWN line under the bar. A percentage without it says how much is
      // spent but not whether that matters: 90% with eight minutes to go and 90% with six hours to go are
      // different situations, and only the dashboard was answering that. Under the bar rather than beside it
      // because the bar row is already three items wide per window — a fourth turned the countdown into the
      // first thing to be squeezed out, which is the one case where it matters most.
      var reset = CC.resetInShort(win.resetsAt);
      var item = h(
        'div',
        {
          class: 'ub-item',
          title: label + ' — ' + pct + ' used' + (reset ? ' · ' + CC.resetIn(win.resetsAt) : ''),
        },
        h(
          'div',
          { class: 'ub-row' },
          h('span', { class: 'ub-label', text: label }),
          h('span', { class: 'ub-track' }, fill),
          h('span', { class: 'ub-pct', text: pct })
        )
      );
      if (reset) item.appendChild(h('span', { class: 'ub-reset', text: 'Reset time: ' + reset }));
      host.appendChild(item);
      shown++;
    }
    if (shown > 0) host.removeAttribute('hidden');
    else host.setAttribute('hidden', 'hidden');
  }

  /**
   * Quota severity, shared by the readout dot and the dashboard bar so the two can never disagree about
   * whether you are in trouble: blue under 65%, amber under 85%, red at or above it.
   *
   * Duplicated in app-session.js by NAME on purpose — these are two independently-loaded scripts with no
   * module system between them, so the CSS class names are the contract, and css-contract.test.js pins them.
   */
  function usageLevel(pct) {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  function formatTokens(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }

  // ---- pills label / send mode ----------------------------------------------
  function pillLabelFor(s, def) {
    var f = s[def.field];
    if (!f) return null;
    if (f.label != null) return String(f.label);
    return null;
  }

  function renderPills(s) {
    if (!els || !els.pills) return;
    for (var i = 0; i < PILL_DEFS.length; i++) {
      var def = PILL_DEFS[i];
      var pill = els.pills[def.key];
      if (!pill) continue;
      var label = pillLabelFor(s, def);
      var f = s[def.field];
      var hasOpts = f && Array.isArray(f.options) && f.options.length > 0;
      if (label == null) {
        pill.el.setAttribute('hidden', 'hidden');
      } else {
        pill.el.removeAttribute('hidden');
        pill.label.textContent = label;
        if (hasOpts) pill.el.removeAttribute('disabled');
        else pill.el.setAttribute('disabled', 'disabled');
      }
      // Provider chip shows the active brand mark (Anthropic / DeepSeek).
      if (def.key === 'provider' && pill.icon && f && f.id) {
        pill.icon.innerHTML = providerMarkSvg(String(f.id));
      }
    }
  }

  function renderSendMode(s) {
    if (!els || !els.send) return;
    if (s.interrupting) {
      // Interrupt in flight: show a disabled "Interrupting…" state until the host clears it (ack/timeout/turn-end).
      els.send.classList.add('stop');
      els.send.classList.add('interrupting');
      els.send.title = 'Interrupting…';
      els.send.setAttribute('aria-label', 'Interrupting');
      els.send.innerHTML = stopGlyph();
    } else if (s.turnActive) {
      els.send.classList.add('stop');
      els.send.classList.remove('interrupting');
      els.send.title = 'Stop';
      els.send.setAttribute('aria-label', 'Stop');
      els.send.innerHTML = stopGlyph();
    } else {
      els.send.classList.remove('stop');
      els.send.classList.remove('interrupting');
      els.send.title = 'Send';
      els.send.setAttribute('aria-label', 'Send');
      els.send.innerHTML = sendGlyph();
    }
  }

  /**
   * Announce turn transitions to assistive technology (WCAG 2.2 AA — 4.1.3 Status Messages).
   *
   * Only the EDGES are announced, never the streaming itself: a screen reader that re-reads on every token is
   * unusable, and a user would silence it — which is worse than saying nothing.
   *
   * Scope note: permission cards are NOT announced from here. The composer's state payload carries no
   * permission field (they arrive separately through cc.permissions), so a check for one here would be a
   * branch that silently never fires. That announcement lives in app-permissions.js, where the cards are
   * actually rendered.
   */
  var lastTurnPhase = null;
  function announceTurnState(s) {
    if (!window.CC || typeof CC.announce !== 'function') return;
    var phase = s.interrupting ? 'interrupting' : s.turnActive ? 'working' : 'idle';
    if (phase === lastTurnPhase) return;
    var wasWorking = lastTurnPhase === 'working' || lastTurnPhase === 'interrupting';
    lastTurnPhase = phase;
    if (phase === 'working') CC.announce('Claude is working…');
    else if (phase === 'interrupting') CC.announce('Stopping…');
    // Only report completion if a turn was actually running — otherwise every idle state push would announce.
    else if (wasWorking) CC.announce('Claude finished responding.');
  }

  /**
   * The boot screen: up until the binary is running, then gone for good.
   *
   * Three states, not two. `running` means go; `starting` means wait; NEITHER means the launch finished without
   * a process — a missing binary, a declined trust prompt, a refused remote-mount project. That last case must
   * clear the screen, or a failed launch leaves the tab covered forever with no way to see the notification
   * explaining why. The host fires a state push on that path precisely so this can happen.
   */
  function renderBoot(s) {
    var boot = document.getElementById('boot');
    var app = document.getElementById('app');
    if (!boot) return;
    // ONE invariant, and everything else follows from it: **the chat is reachable only while the `claude`
    // process is running.** Install -> sign in -> loading -> plugin, in that order, and any step backwards
    // (the process exits, is restarted, the credential goes away) returns to the matching screen rather
    // than leaving a chat on screen that has nothing behind it.
    //
    // This used to be `starting || binaryMissing`, so every OTHER not-running state — signed out, a dead
    // process, a launch that failed — fell through to the chat UI, which then rendered its first frame with
    // no session behind it and stayed half-empty.
    // Exactly ONE screen at a time, in the order the flow runs: install -> sign in -> loading -> chat.
    // `#boot` hosts the install card and the spinner; the sign-in card is its own layer, so the spinner
    // must stand down while it is up or it would simply cover it (z-index 60 over 55).
    var missing = !s.running && !!s.binaryMissing;
    var awaitingAuth = !s.running && !missing && (!!s.needsLogin || authForced);
    var booting = !s.running;
    var showBoot = missing || (booting && !awaitingAuth);
    boot.hidden = !showBoot;
    boot.classList.toggle('missing', missing);
    // Announce the FIRST booting render, not just a transition into it. The screen is already on-screen when
    // the page loads, so the common case never transitions — and the element's own aria-live never fires
    // either, because static markup present at load is not a mutation. Once per boot: `announcedBoot` resets
    // when the screen comes down, so a later relaunch announces again.
    if (showBoot && !missing && !announcedBoot) {
      announcedBoot = true;
      CC.announce && CC.announce('Loading Claude Code');
    }
    if (!showBoot) announcedBoot = false;
    // `booting`, not `showBoot`: the chat stays inert for the sign-in screen too.
    if (app) app.classList.toggle('booting', booting);
    var card = document.getElementById('boot-missing');
    if (card) card.hidden = !missing;
    if (missing && !announcedMissing) {
      announcedMissing = true;
      CC.announce && CC.announce('Claude Code was not found. Install options are available.');
    }
    if (!missing) {
      announcedMissing = false;
      installingId = null; // a fresh boot resets any "Installing…" button
      setBootError('');
    }
    if (!showBoot) return;
    if (missing) {
      renderInstallMethods();
      return;
    }
    var sub = document.getElementById('boot-sub');
    // Distinguish the two waits: a fresh launch versus resuming an existing session, which reads a transcript
    // back and is the slower of the two. Guessing "Starting" for both made the longer wait look like a hang.
    if (sub) sub.textContent = s.resuming ? 'Resuming your session' : 'Starting the agent';
  }

  /**
   * The not-found card's method rows: per install route, a button ("Install via X") and under it the exact
   * command with a copy affordance ("or copy this command to bash: …"). The command text is the fallback
   * that matters: the button runs it in the IDE terminal, and when a corporate network or a missing
   * Terminal plugin breaks that, the user copies the same command and runs it anywhere.
   */
  function renderInstallMethods() {
    var box = document.getElementById('boot-installs');
    if (!box) return;
    if (!installsBuilt) {
      box.textContent = '';
      installMethods.forEach(function (m) {
        var row = document.createElement('div');
        row.className = 'boot-install';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn primary boot-install-btn';
        btn.setAttribute('data-method', m.id);
        btn.addEventListener('click', function () {
          installingId = m.id;
          setBootError('');
          syncInstallButtons();
          CC.announce && CC.announce('Installing Claude Code. Watch the IDE terminal for progress.');
          CC.send({ type: 'installClaude', method: m.id });
        });
        row.appendChild(btn);

        var hint = document.createElement('div');
        hint.className = 'boot-install-hint';
        var hintLabel = document.createElement('span');
        hintLabel.className = 'boot-install-hint-label';
        hintLabel.textContent = 'or copy this command to ' + (m.shell || 'a shell') + ':';
        var code = document.createElement('code');
        code.className = 'boot-install-cmd';
        code.textContent = m.display;
        var copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn ghost boot-install-copy';
        copy.textContent = 'Copy';
        copy.setAttribute('aria-label', 'Copy the ' + (m.label || 'install') + ' command');
        copy.addEventListener('click', function (e) {
          CC.send({ type: 'copy', text: m.display });
          if (CC.flashCopied) CC.flashCopied(e.currentTarget || copy);
        });
        hint.appendChild(hintLabel);
        hint.appendChild(code);
        hint.appendChild(copy);
        row.appendChild(hint);
        box.appendChild(row);
      });
      installsBuilt = true;
      wirePathRow();
    }
    syncInstallButtons();
  }

  /** Button labels track the one installing: "Installing…" on it, normal labels (enabled) on the rest, so a
   *  visibly failed attempt in the terminal can be retried by another route without any reset step. */
  function syncInstallButtons() {
    var box = document.getElementById('boot-installs');
    if (!box) return;
    var btns = box.querySelectorAll('.boot-install-btn');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var m = null;
      for (var j = 0; j < installMethods.length; j++) {
        if (installMethods[j].id === b.getAttribute('data-method')) m = installMethods[j];
      }
      if (!m) continue;
      var busy = installingId === m.id;
      b.textContent = busy ? 'Installing…' : m.label;
      b.classList.toggle('installing', busy);
      b.setAttribute('aria-busy', busy ? 'true' : 'false');
    }
  }

  function wirePathRow() {
    var use = document.getElementById('boot-path-use');
    var input = document.getElementById('boot-path');
    if (!use || !input || use.__wired) return;
    use.__wired = true;
    var submit = function () {
      setBootError('');
      CC.send({ type: 'setBinaryPath', path: input.value || '' });
    };
    use.addEventListener('click', submit);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') submit();
    });
  }

  function setBootError(msg) {
    var el = document.getElementById('boot-path-err');
    if (el) el.textContent = msg || '';
  }

  /** Host → card: a validation or install-launch failure, verbatim. Clears the "Installing…" state so the
   *  buttons are usable again — the error IS the end of that attempt. */
  cc.bootPathError = function (msg) {
    installingId = null;
    syncInstallButtons();
    setBootError(String(msg == null ? '' : msg));
  };

  // ---- sign-in card ---------------------------------------------------------

  var authForced = false; // raised by cc.showAuth() (fresh install) until dismissed or resolved
  var authWired = false;
  var announcedAuth = false;

  /**
   * The whole card is a function of one step name; every step's markup exists statically in the shell and
   * exactly one is visible. Credential inputs are cleared the moment their value is sent — the DOM is a
   * debug surface (DevTools, DOM dumps) and a secret must not linger in it.
   */
  function setAuthStep(step, url, message) {
    var card = document.getElementById('auth-card');
    if (!card) return;
    // The host still speaks in flow events ('url' appeared / 'code' requested); both land on the ONE
    // combined browser step — the callback path and the paste path are the same screen, with the code
    // field merely emphasised once the binary explicitly asks for it.
    var wire = step;
    if (step === 'url' || step === 'code') step = 'browser';
    var steps = card.querySelectorAll('.auth-step');
    for (var i = 0; i < steps.length; i++) {
      steps[i].hidden = steps[i].getAttribute('data-step') !== step;
    }
    // A restarted flow gets a fresh authorize URL; keeping the previous one would send the user to a
    // consent page for an attempt that is already over.
    if (step === 'idle' || step === 'waiting') card.__url = null;
    if (wire === 'url' && url) card.__url = url;
    // Both buttons act on a URL the host has to have handed us; until it does they are inert rather than
    // silently no-op, so nobody clicks "Open your browser" and concludes the card is broken.
    var hasUrl = !!card.__url;
    var open = document.getElementById('auth-url-open');
    var copy = document.getElementById('auth-url-copy');
    if (open) open.disabled = !hasUrl;
    if (copy) copy.disabled = !hasUrl;
    if (wire === 'code') {
      // The binary is now waiting for input — make the optional field the obvious next thing without
      // hiding the "the browser can still finish this" framing.
      var label = document.getElementById('auth-code-label');
      if (label) label.textContent = 'Paste the authorization code from the browser (or just finish there)';
      var c = document.getElementById('auth-code');
      if (c) c.focus();
    }
    if (step === 'error') {
      var e = document.getElementById('auth-error');
      if (e) e.textContent = message || 'Sign-in failed. Please try again.';
    }
  }

  cc.authState = function (s) {
    if (!s || !s.step) return;
    setAuthStep(String(s.step), s.url, s.message);
  };

  /** Host → card, proactively (a fresh install has no credentials): raise it without waiting for state. */
  cc.showAuth = function () {
    authForced = true;
    setAuthStep('idle');
    renderAuth(lastAuthState || {});
  };

  var lastAuthState = null;

  function renderAuth(s) {
    lastAuthState = s;
    var card = document.getElementById('auth-card');
    if (!card) return;
    // Two gates, both from the same rule (install -> sign in -> loading -> chat): the install card wins,
    // because signing in is meaningless without a binary; and a RUNNING session wins over both, so the
    // card cannot linger over a live chat once the sign-in it was asking for has happened.
    var visible = (!!s.needsLogin || authForced) && !s.binaryMissing && !s.running;
    if (!visible && !card.hidden) {
      authForced = false;
      announcedAuth = false;
      setAuthStep('idle');
    }
    if (visible && card.hidden) {
      wireAuthCard();
      if (!announcedAuth) {
        announcedAuth = true;
        CC.announce && CC.announce('Sign in to Claude. Options are available.');
      }
    }
    card.hidden = !visible;
    if (!s.needsLogin && !authForced) authForced = false;
  }

  function wireAuthCard() {
    if (authWired) return;
    authWired = true;
    // Under the native-Wayland toolkit CEF's clipboard is isolated from the system one, so a plain
    // paste into these fields yields nothing. Route it through the host, which reads the real
    // clipboard — cc.insertText then lands it in whichever field has focus.
    ['auth-key', 'auth-code'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('paste', function (e) {
        if (!hostClipboard) return;
        e.preventDefault();
        CC.send({ type: 'pasteClipboard' });
      });
    });
    var on = function (id, fn) {
      var el = document.getElementById(id);
      if (el) el.addEventListener('click', fn);
    };
    on('auth-sub', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginSubscription' });
    });
    // The organisation route: same OAuth dance, same card, but the consent grants org:create_api_key and the
    // binary mints the key itself — nothing to paste, nothing to hand around.
    on('auth-console', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginConsole' });
    });
    // Typing a key by hand is the exception now, so it starts collapsed. Kept rather than removed: someone
    // holding only a bare API key must not be sent to Settings to get started.
    on('auth-key-toggle', function (e) {
      var fields = document.getElementById('auth-key-fields');
      if (!fields) return;
      var open = fields.hidden;
      fields.hidden = !open;
      var btn = e.currentTarget;
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.textContent = open ? 'Hide the API key field' : 'Use an API key instead';
      if (open) {
        var input = document.getElementById('auth-key');
        if (input) input.focus();
      }
    });
    on('auth-key-use', function () {
      var input = document.getElementById('auth-key');
      var key = input ? input.value : '';
      if (input) input.value = ''; // never leave a credential sitting in the DOM
      CC.send({ type: 'useApiKey', key: key });
    });
    on('auth-code-use', function () {
      var input = document.getElementById('auth-code');
      var code = input ? input.value : '';
      if (input) input.value = '';
      setAuthStep('verifying');
      CC.send({ type: 'submitLoginCode', code: code });
    });
    var cancel = function () {
      CC.send({ type: 'cancelLogin' });
      setAuthStep('idle');
    };
    on('auth-cancel', cancel);
    on('auth-cancel-waiting', cancel);
    // Verifying has its own Cancel: a submit that never resolves must not strand the user on a spinner
    // with no exit — observed live before the raw-TTY Enter fix, and worth an escape hatch regardless.
    on('auth-cancel-verify', cancel);
    on('auth-dismiss', function () {
      authForced = false;
      CC.send({ type: 'dismissAuth' });
    });
    on('auth-retry', function () {
      setAuthStep('idle');
    });
    on('auth-url-copy', function (e) {
      var card = document.getElementById('auth-card');
      CC.send({ type: 'copy', text: (card && card.__url) || '' });
      if (CC.flashCopied) CC.flashCopied(e.currentTarget);
    });
    on('auth-url-open', function () {
      var card = document.getElementById('auth-card');
      if (card && card.__url) CC.send({ type: 'open', url: card.__url });
    });
    var keyInput = document.getElementById('auth-key');
    if (keyInput) {
      keyInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          var btn = document.getElementById('auth-key-use');
          if (btn) btn.click();
        }
      });
    }
    var codeInput = document.getElementById('auth-code');
    if (codeInput) {
      codeInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          var btn = document.getElementById('auth-code-use');
          if (btn) btn.click();
        }
      });
    }
  }

  function renderState(s) {
    if (!s) return;
    announceTurnState(s);
    renderAuth(s);
    renderSendMode(s);
    renderPills(s);
    renderQueue(s.queue);
    renderReadout(s);
    // ghost suggestion
    var newGhost = s.suggestion != null ? String(s.suggestion) : '';
    ghostText = newGhost;
    renderGhost();
    // If a pill menu is open, only rebuild it when its selection actually changed — reopening on every state
    // push (frequent during streaming) made the menu flicker and de-selected the item under the cursor. The
    // attach menu (__attach) is never touched here; it has its own refresh path (cc.attachData).
    if (openMenu && openMenu.pill !== '__attach') {
      var key = openMenu.pill;
      var def = null;
      for (var i = 0; i < PILL_DEFS.length; i++) if (PILL_DEFS[i].key === key) def = PILL_DEFS[i];
      if (def && menuSig(def) !== openMenu.sig) {
        var anchor = openMenu.anchor;
        closeMenu();
        togglePillMenu(def, anchor);
      }
    }
  }

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

  function hidePalette() {
    var p = CC.els && CC.els.palette;
    if (!p) return;
    p.setAttribute('hidden', 'hidden');
    // if the composer field held just "/", clear it so the palette doesn't re-open on focus
    if (els && els.input && els.input.value === '/') {
      els.input.value = '';
      autosize(els.input);
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
    if (!ensureBuilt() || !els || !els.input) return;
    els.input.value = '/' + it.name + ' ';
    autosize(els.input);
    els.input.focus();
    // move caret to end
    try {
      var len = els.input.value.length;
      els.input.setSelectionRange(len, len);
    } catch (e) {
      /* ignore */
    }
  }

  // ---- Kotlin-facing API ----------------------------------------------------
  cc.state = function (s) {
    lastState = s || null;
    // The boot screen is updated FIRST and OUTSIDE the ensureBuilt gate below. It covers the whole tab and
    // blocks input, so it must never be hostage to the composer having mounted: if `ensureBuilt()` returns
    // false we bail out early, and an overlay left up with no path to clear it is a worse failure than the
    // empty composer this screen exists to hide.
    if (lastState) renderBoot(lastState);
    if (!ensureBuilt()) return; // will render on build via lastState
    renderState(lastState);
  };

  cc.meta = function (m) {
    commands = m && Array.isArray(m.commands) ? m.commands.slice() : [];
    if (m && typeof m.hostClipboard === 'boolean') hostClipboard = m.hostClipboard;
    if (m && Array.isArray(m.installMethods)) {
      installMethods = m.installMethods.slice();
      installsBuilt = false; // rebuild the card's buttons if it is (or becomes) visible
      renderInstallMethods();
    }
    // refresh palette list if open
    var p = CC.els && CC.els.palette;
    if (p && p.__built && !p.hasAttribute('hidden')) {
      filterPalette(p.__input ? p.__input.value : '');
    }
  };

  cc.openPalette = function () {
    ensureBuilt();
    openPalette();
  };

  cc.focusInput = function () {
    if (!ensureBuilt() || !els || !els.input) return;
    els.input.focus();
  };

  // Host inserts clipboard text into the composer at the caret (Ctrl+V text path on Wayland).
  /**
   * Host → web paste. Targets the FOCUSED field, not the composer: on a native-Wayland toolkit every
   * paste in the web view is routed through the host, so hardcoding the composer meant Ctrl+V did
   * nothing at all in the sign-in card's API-key and code inputs — the field looked simply broken.
   */
  cc.insertText = function (text) {
    if (text == null) return;
    var focused = document.activeElement;
    var editable =
      focused &&
      (focused.tagName === 'INPUT' || focused.tagName === 'TEXTAREA') &&
      !focused.disabled &&
      !focused.readOnly;
    if (editable) {
      insertAtCursor(focused, String(text));
      return;
    }
    if (!ensureBuilt() || !els || !els.input) return;
    els.input.focus();
    insertAtCursor(els.input, String(text));
  };

  cc.attachments = function (list) {
    attachmentsList = Array.isArray(list) ? list.slice() : [];
    if (!ensureBuilt()) return; // will render on build via attachmentsList
    renderAttachments(attachmentsList);
  };

  // build eagerly if mounts already exist; otherwise first cc.state/openPalette builds.
  try {
    ensureBuilt();
  } catch (e) {
    /* defer */
  }
})();
