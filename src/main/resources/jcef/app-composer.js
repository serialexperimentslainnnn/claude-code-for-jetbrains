/* app-composer.js — A3 (composer)
 * Owns: cc.state(s), cc.meta(m), cc.openPalette(), cc.focusInput()
 * Builds the composer DOM in CC.els.composer and the slash palette in CC.els.palette.
 * Pure renderer: never holds data/transport, only emits via CC.send().
 *
 * This file is the composer's SHELL: the card it builds, the textarea's behaviour, and the state pipeline.
 * Everything with a subject of its own lives beside it and hangs off `CC.composer` (see
 * app-composer-base.js) — pills, menus, attachments, the readout, the palette, the boot screen and the
 * sign-in card. It loads LAST of the family: `ensureBuilt()` runs at the bottom, so every collaborator it
 * calls has to be there already.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var cc = window.cc || (window.cc = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  // ---- module state ---------------------------------------------------------
  var built = false;
  var els = null; // { card, input, send, pills:{provider,model,mode,effort,thinking}, queue, ghost, readout, sendIcon }
  var ghostText = ''; // current ghost suggestion (empty field only)
  var followOn = true; // auto-follow scrolling (on by default)
  var followBtnRef = null;

  function applyFollow() {
    if (followBtnRef) {
      if (followOn) followBtnRef.classList.add('active');
      else followBtnRef.classList.remove('active');
    }
    if (window.CC && typeof CC.emit === 'function') CC.emit('follow', followOn);
  }

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
    attachBtn.innerHTML = CX.attachGlyph();
    attachBtn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      CX.toggleAttachMenu(attachBtn);
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
    for (var i = 0; i < CX.PILL_DEFS.length; i++) {
      var def = CX.PILL_DEFS[i];
      var pill = CX.buildPill(def);
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
    CX.els = els;

    wireInput(input);
    CX.wireImageDrop(card);
    CX.wireImagePaste(input);

    // render any attachments that arrived before build
    CX.renderAttachments();

    // if state already arrived, render it now
    if (CX.lastState) renderState(CX.lastState);
    renderGhost();
    // sync the initial auto-follow state to the transcript (retry shortly in case the bus isn't ready)
    applyFollow();
    setTimeout(applyFollow, 60);

    // Claim the DOM focus as soon as the composer exists. Only half the story: a page cannot grant itself the
    // IDE's keyboard focus — the host hands the browser that (JcefHost.requestFocus / markWebReady).
    els.input.focus();
    return true;
  }
  CX.ensureBuilt = ensureBuilt;

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
        if (CX.openMenu) {
          CX.closeMenu();
          e.preventDefault();
          return;
        }
        // Only interrupt when a turn is actually running and we're not already interrupting — otherwise Escape
        // in an idle composer would fire a pointless interrupt request.
        if (CX.lastState && CX.lastState.turnActive && !CX.lastState.interrupting) {
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
        CX.openPalette();
      }
    });
  }

  function autosize(input) {
    if (!input) return;
    input.style.height = 'auto';
    var max = 220;
    input.style.height = Math.min(input.scrollHeight, max) + 'px';
  }
  CX.autosize = autosize;

  function onSendClick(e) {
    e.preventDefault();
    if (CX.lastState && CX.lastState.interrupting) {
      return; // interrupt already in flight; the button is showing "Interrupting…"
    }
    if (CX.lastState && CX.lastState.turnActive) {
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

  // ---- send button ----------------------------------------------------------
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

  function renderState(s) {
    if (!s) return;
    announceTurnState(s);
    CX.renderAuth(s);
    renderSendMode(s);
    CX.renderPills(s);
    renderQueue(s.queue);
    CX.renderReadout(s);
    // ghost suggestion
    var newGhost = s.suggestion != null ? String(s.suggestion) : '';
    ghostText = newGhost;
    renderGhost();
    CX.syncOpenMenu();
  }

  // ---- Kotlin-facing API ----------------------------------------------------
  cc.state = function (s) {
    CX.lastState = s || null;
    // The boot screen is updated FIRST and OUTSIDE the ensureBuilt gate below. It covers the whole tab and
    // blocks input, so it must never be hostage to the composer having mounted: if `ensureBuilt()` returns
    // false we bail out early, and an overlay left up with no path to clear it is a worse failure than the
    // empty composer this screen exists to hide.
    if (CX.lastState) CX.renderBoot(CX.lastState);
    if (!ensureBuilt()) return; // will render on build via lastState
    renderState(CX.lastState);
  };

  cc.meta = function (m) {
    CX.setCommands(m && Array.isArray(m.commands) ? m.commands.slice() : []);
    if (m && typeof m.hostClipboard === 'boolean') CX.hostClipboard = m.hostClipboard;
    if (m && Array.isArray(m.installMethods)) CX.setInstallMethods(m.installMethods.slice());
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
      CX.insertAtCursor(focused, String(text));
      return;
    }
    if (!ensureBuilt() || !els || !els.input) return;
    els.input.focus();
    CX.insertAtCursor(els.input, String(text));
  };

  // build eagerly if mounts already exist; otherwise first cc.state/openPalette builds.
  try {
    ensureBuilt();
  } catch (e) {
    /* defer */
  }
})();
