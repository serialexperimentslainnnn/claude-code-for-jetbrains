(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var cc = window.cc || (window.cc = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;
  var send = CX.send;

  var built = false;
  var els = null;
  var ghostText = '';
  var followOn = true;
  var followBtnRef = null;
  var guardOn = true;
  var guardBtnRef = null;

  function applyFollow() {
    if (followBtnRef) {
      if (followOn) followBtnRef.classList.add('active');
      else followBtnRef.classList.remove('active');
    }
    if (window.CC && typeof CC.emit === 'function') CC.emit('follow', followOn);
  }

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
  function followGlyph() {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M4 4.5 8 8l4-3.5"/><path d="M4 9 8 12.5l4-3.5"/></svg>'
    );
  }
  function guardGlyph() {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M8 1.8 13 3.6v4.1c0 3-2.1 5.6-5 6.5-2.9-.9-5-3.5-5-6.5V3.6z"/></svg>'
    );
  }

  function applyGuard() {
    if (!guardBtnRef) return;
    if (guardOn) guardBtnRef.classList.add('active');
    else guardBtnRef.classList.remove('active');
    guardBtnRef.title = guardOn
      ? 'Sensitive Guard is on — click to switch it off'
      : 'Sensitive Guard is OFF — click to switch it back on';
  }

  CX.setGuardOn = function (on) {
    var next = on !== false;
    if (next === guardOn) return;
    guardOn = next;
    applyGuard();
  };

  function ensureBuilt() {
    if (built) return true;
    if (!CC.els || !CC.els.composer) return false;
    built = true;

    var mount = CC.els.composer;
    mount.innerHTML = '';

    var ghost = h('div', { class: 'ghost', attrs: { hidden: 'hidden' } });

    var queue = h('div', { class: 'queue' });

    var input = h('textarea', {
      class: 'composer-input',
      attrs: { rows: '2', placeholder: 'Ask Claude, or type / for commands', 'aria-label': 'Message Claude' },
    });

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

    var sendBtn = h('button', {
      class: 'send-btn',
      title: 'Send',
      attrs: { type: 'button', 'aria-label': 'Send' },
    });
    sendBtn.innerHTML = sendGlyph();
    sendBtn.addEventListener('click', onSendClick);

    var inputRow = h('div', { class: 'composer-input-row' }, input);

    var attachments = h('div', { class: 'attachments', attrs: { hidden: 'hidden' } });

    var pills = {};
    var barLeft = h('div', { class: 'bar-left' });
    barLeft.appendChild(attachBtn);
    for (var i = 0; i < CX.PILL_DEFS.length; i++) {
      var def = CX.PILL_DEFS[i];
      var pill = CX.buildPill(def);
      pills[def.key] = pill;
      barLeft.appendChild(pill.el);
    }
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

    var guardBtn = h('button', {
      class: 'bar-icon active',
      attrs: {
        type: 'button',
        'aria-label': 'Sensitive Guard',
        'aria-expanded': 'false',
        'aria-haspopup': 'menu',
      },
    });
    guardBtn.innerHTML = guardGlyph();
    guardBtnRef = guardBtn;
    var barRight = h('div', { class: 'bar-right' }, guardBtn, followBtn, vibeBtn, sendBtn);
    var bar = h('div', { class: 'composer-bar' }, barLeft, barRight);

    var guardMenu = CC.durationMenu({
      anchor: guardBtn,
      home: barRight,
      label: 'Switch the Sensitive Guard off for',
      onPick: function (token) {
        send({ type: 'guardMaster', on: false, duration: token });
      },
    });
    guardBtn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      if (!guardOn) {
        guardMenu.close();
        send({ type: 'guardMaster', on: true, duration: '' });
        return;
      }
      guardMenu.toggle();
    });

    var readout = h('div', { class: 'readout', attrs: { hidden: 'hidden' } });
    var usageBars = h('div', { class: 'usage-bars', attrs: { hidden: 'hidden' } });

    var controls = CX.buildActionRows();

    var card = h('div', { class: 'composer-card' }, controls, inputRow, bar);
    var palette = document.getElementById('palette');
    if (palette) card.appendChild(palette);

    mount.appendChild(ghost);
    mount.appendChild(queue);
    mount.appendChild(attachments);
    mount.appendChild(readout);
    mount.appendChild(usageBars);
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

    CX.renderAttachments();

    applyGuard();
    if (CX.lastState) renderState(CX.lastState);
    renderGhost();
    applyFollow();
    setTimeout(applyFollow, 60);

    els.input.focus();
    return true;
  }
  CX.ensureBuilt = ensureBuilt;

  function wireInput(input) {
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        doSend();
        return;
      }
      if (e.key === 'Enter' && e.shiftKey) {
        return;
      }
      if (e.key === 'Escape') {
        if (CX.openMenu) {
          CX.closeMenu();
          e.preventDefault();
          return;
        }
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
      if (ghostText) renderGhost();
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
      return;
    }
    if (CX.lastState && CX.lastState.turnActive) {
      sendInterrupt();
    } else {
      doSend();
    }
  }

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
      return;
    }
    var git = typeof CC.gitChatActive === 'function' && CC.gitChatActive();
    send(git ? { type: 'send', text: text, scope: 'git' } : { type: 'send', text: text });
    els.input.value = '';
    ghostText = '';
    renderGhost();
    autosize(els.input);
  }

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

  var lastQueueKey = null;
  function renderQueue(queue) {
    if (!els || !els.queue) return;
    var key = JSON.stringify(Array.isArray(queue) ? queue : []);
    if (key === lastQueueKey) return;
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

  function renderSendMode(s) {
    if (!els || !els.send) return;
    if (s.interrupting) {
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

  var lastTurnPhase = null;
  function announceTurnState(s) {
    if (!window.CC || typeof CC.announce !== 'function') return;
    var phase = s.interrupting ? 'interrupting' : s.turnActive ? 'working' : 'idle';
    if (phase === lastTurnPhase) return;
    var wasWorking = lastTurnPhase === 'working' || lastTurnPhase === 'interrupting';
    lastTurnPhase = phase;
    if (phase === 'working') CC.announce('Claude is working…');
    else if (phase === 'interrupting') CC.announce('Stopping…');
    else if (wasWorking) CC.announce('Claude finished responding.');
  }

  function renderState(s) {
    if (!s) return;
    announceTurnState(s);
    CX.setGuardOn(s.guardOn);
    CX.renderAuth(s);
    renderSendMode(s);
    CX.renderPills(s);
    renderQueue(s.queue);
    CX.renderReadout(s);
    var newGhost = s.suggestion != null ? String(s.suggestion) : '';
    ghostText = newGhost;
    renderGhost();
    CX.syncOpenMenu();
    CX.refreshOverflow();
  }

  cc.state = function (s) {
    CX.lastState = s || null;
    if (CX.lastState) CX.renderBoot(CX.lastState);
    if (!ensureBuilt()) return;
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

  try {
    ensureBuilt();
  } catch (e) {}
})();
