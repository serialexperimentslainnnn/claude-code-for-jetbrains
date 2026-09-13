(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;
  const send = CX.send;

  let ghostText = '';

  function sendGlyph(): string {
    return (
      '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
      '<path fill="currentColor" d="M3.4 20.4 21 12 3.4 3.6 3.4 10l11 2-11 2z"/></svg>'
    );
  }
  CX.sendGlyph = sendGlyph;

  function stopGlyph(): string {
    return (
      '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">' +
      '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/></svg>'
    );
  }

  CX.wireInput = function (input: HTMLTextAreaElement): void {
    input.addEventListener('keydown', function (e: KeyboardEvent) {
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
  };

  function autosize(input: HTMLTextAreaElement | HTMLInputElement | null): void {
    if (!input) return;
    input.style.height = 'auto';
    const max = 220;
    input.style.height = Math.min(input.scrollHeight, max) + 'px';
  }
  CX.autosize = autosize;

  CX.onSendClick = function (e: Event): void {
    e.preventDefault();
    if (CX.lastState && CX.lastState.interrupting) {
      return;
    }
    if (CX.lastState && CX.lastState.turnActive) {
      sendInterrupt();
    } else {
      doSend();
    }
  };

  function sendInterrupt(): void {
    const git = typeof CC.gitChatActive === 'function' && CC.gitChatActive();
    send(git ? { type: 'interrupt', scope: 'git' } : { type: 'interrupt' });
  }

  function doSend(): void {
    const els = CX.els;
    if (!els || !els.input) return;
    const text = els.input.value;
    if (text == null) return;
    const trimmed = text.replace(/\s+$/, '');
    if (trimmed.length === 0) {
      return;
    }
    const git = typeof CC.gitChatActive === 'function' && CC.gitChatActive();
    send(git ? { type: 'send', text: text, scope: 'git' } : { type: 'send', text: text });
    els.input.value = '';
    ghostText = '';
    renderGhost();
    autosize(els.input);
  }

  CX.setGhost = function (text: string): void {
    ghostText = text;
  };

  function renderGhost(): void {
    const els = CX.els;
    if (!els || !els.ghost) return;
    const show = ghostText && els.input && els.input.value === '';
    if (show) {
      els.ghost.textContent = ghostText;
      els.ghost.removeAttribute('hidden');
      els.ghost.title = 'Press Tab to use this suggestion';
    } else {
      els.ghost.textContent = '';
      els.ghost.setAttribute('hidden', 'hidden');
    }
  }
  CX.renderGhost = renderGhost;

  let lastQueueKey: string | null = null;
  CX.renderQueue = function (queue: unknown): void {
    const els = CX.els;
    if (!els || !els.queue) return;
    const key = JSON.stringify(Array.isArray(queue) ? queue : []);
    if (key === lastQueueKey) return;
    lastQueueKey = key;
    els.queue.innerHTML = '';
    if (!Array.isArray(queue) || queue.length === 0) {
      els.queue.setAttribute('hidden', 'hidden');
      return;
    }
    els.queue.removeAttribute('hidden');
    for (let i = 0; i < queue.length; i++) {
      const text = String(queue[i]);
      const index = i;
      const x = h('span', {
        class: 'queue-x',
        text: '✕',
        title: 'Remove from queue',
        attrs: { role: 'button', 'aria-label': 'Remove queued prompt' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            send({ type: 'removeQueued', index: index });
          },
        },
      });
      const chip = h(
        'span',
        { class: 'queue-chip', title: text },
        h('span', { class: 'queue-text', text: text }),
        x
      );
      els.queue.appendChild(chip);
    }
  };

  CX.renderSendMode = function (s: ComposerState): void {
    const els = CX.els;
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
  };

  let lastTurnPhase: string | null = null;
  CX.announceTurnState = function (s: ComposerState): void {
    if (!window.CC || typeof CC.announce !== 'function') return;
    const phase = s.interrupting ? 'interrupting' : s.turnActive ? 'working' : 'idle';
    if (phase === lastTurnPhase) return;
    const wasWorking = lastTurnPhase === 'working' || lastTurnPhase === 'interrupting';
    lastTurnPhase = phase;
    if (phase === 'working') CC.announce('Claude is working…');
    else if (phase === 'interrupting') CC.announce('Stopping…');
    else if (wasWorking) CC.announce('Claude finished responding.');
  };
})();
