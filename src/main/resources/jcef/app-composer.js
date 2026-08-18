/* app-composer.js — the composer.
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
    var barRight = h('div', { class: 'bar-right' }, followBtn, vibeBtn, sendBtn);
    var bar = h('div', { class: 'composer-bar' }, barLeft, barRight);

    // readout (subtle session line)
    var readout = h('div', { class: 'readout', attrs: { hidden: 'hidden' } });
    // Plan limits, one bar per window, on their own row under the readout — see renderUsageBars().
    var usageBars = h('div', { class: 'usage-bars', attrs: { hidden: 'hidden' } });

    // The dashboard's view pills and the chat's own action buttons, as ONE bar INSIDE the prompt card, in the
    // same shape as the model/mode bar under it — see app-composer-actions.js for why they are not in the
    // tool window's title bar. Inside the card, because these are controls OF this chat: outside it they read
    // as a toolbar the chat happens to sit under.
    var controls = CX.buildActionRows();

    // Controls FIRST: the row sits at the top of the card, above the box you type in. Below the textarea it
    // was a second strip stacked on the model/mode bar — two rows of controls under the text and none over it.
    var card = h('div', { class: 'composer-card' }, controls, inputRow, bar);
    // The palette belongs to the card, anchored to its top edge (see the shell). As a row of the dock it sat
    // above the plan-limit meters — a whole block away from the box it is completing.
    var palette = document.getElementById('palette');
    if (palette) card.appendChild(palette);

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

    /**
     * The ⋮ for the composer's own bar, collecting from the end: 🌈 and auto-follow first, then the pills
     * from thinking back towards the clip.
     *
     * THE SEND BUTTON IS RESERVED, and that is two separate promises. It is never collected — it is the
     * primary action of the screen and cannot end up behind a press — and it never gives up a pixel to make
     * room for a pill: in a flex row the negative space is shared out by base × shrink factor, so without
     * `flex: 0 0 auto` it would quietly narrow first and the row would only start collecting once it already
     * looked wrong (composer.css). Reserving it here is the other half of that: its width comes out of the
     * budget before anything is offered space, so the pressure falls on what can actually give.
     *
     * `activate` exists for the controls that open a popup of their own. They anchor it to THEMSELVES, and a
     * collected control is `display: none`, which has no position — its menu would open in the corner of the
     * page. Anchored to the ⋮ it opens where the reader just pressed, which is also where it belongs.
     */
    if (CX.createOverflow) {
      CX.createOverflow({
        row: bar,
        label: 'More composer controls',
        items: function () {
          var list = [];
          for (var n = 0; n < barLeft.children.length; n++) list.push(barLeft.children[n]);
          return list.concat([followBtn, vibeBtn]);
        },
        reserved: function () {
          return [sendBtn];
        },
        place: function (btn) {
          barRight.insertBefore(btn, sendBtn);
        },
        activate: function (el, anchor) {
          if (el === attachBtn) {
            CX.toggleAttachMenu(anchor);
            return true;
          }
          var key = el.getAttribute && el.getAttribute('data-pill');
          for (var n = 0; key && n < CX.PILL_DEFS.length; n++) {
            if (CX.PILL_DEFS[n].key === key) {
              CX.togglePillMenu(CX.PILL_DEFS[n], anchor);
              return true;
            }
          }
          return false;
        },
      });
    }

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
          sendInterrupt();
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
      sendInterrupt();
    } else {
      doSend();
    }
  }

  /** Stops the turn of whichever conversation the composer is talking to — see [doSend]. */
  function sendInterrupt() {
    var git = typeof CC.gitChatActive === 'function' && CC.gitChatActive();
    send(git ? { type: 'interrupt', scope: 'git' } : { type: 'interrupt' });
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
    // ONE composer, whichever conversation is on screen. While the Git view is showing its chat, the turn is
    // tagged for that session — the same `scope` a request card's answer carries, routed by the same line
    // host-side. A second text box inside the view was the alternative, and it was a second thing to style,
    // to keep in sync and to get subtly wrong.
    var git = typeof CC.gitChatActive === 'function' && CC.gitChatActive();
    send(git ? { type: 'send', text: text, scope: 'git' } : { type: 'send', text: text });
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
    // NB no `renderActions` here. Every control in the actions row acts on the chat you are already in and is
    // available whenever there is one, so none of them reads the state — the row is built once and stays.
    renderSendMode(s);
    CX.renderPills(s);
    renderQueue(s.queue);
    CX.renderReadout(s);
    // ghost suggestion
    var newGhost = s.suggestion != null ? String(s.suggestion) : '';
    ghostText = newGhost;
    renderGhost();
    CX.syncOpenMenu();
    // LAST, because it measures what everything above it just drew — a pill's label is its width. It is also
    // free when nothing moved: an identical push produces an identical signature and the row is never read.
    CX.refreshOverflow();
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
