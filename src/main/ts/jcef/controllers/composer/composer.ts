(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const cc = (window.cc = window.cc || {});
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;

  let built = false;
  let els: ComposerEls | null = null;

  function ensureBuilt(): boolean {
    if (built) return true;
    if (!CC.els || !CC.els.composer) return false;
    built = true;

    const mount = CC.els.composer;
    mount.innerHTML = '';

    const ghost = h('div', { class: 'ghost', attrs: { hidden: 'hidden' } });

    const queue = h('div', { class: 'queue' });

    const input = h('textarea', {
      class: 'composer-input',
      attrs: { rows: '2', placeholder: 'Ask Claude, or type / for commands', 'aria-label': 'Message Claude' },
    }) as HTMLTextAreaElement;

    const attachBtn = h('button', {
      class: 'attach-btn',
      title: 'Attach files',
      attrs: { type: 'button', 'aria-label': 'Attach files' },
    });
    attachBtn.innerHTML = CX.attachGlyph();
    attachBtn.addEventListener('click', function (e: Event) {
      e.preventDefault();
      e.stopPropagation();
      CX.toggleAttachMenu(attachBtn);
    });

    const sendBtn = h('button', {
      class: 'send-btn',
      title: 'Send',
      attrs: { type: 'button', 'aria-label': 'Send' },
    });
    sendBtn.innerHTML = CX.sendGlyph();
    sendBtn.addEventListener('click', CX.onSendClick);

    const inputRow = h('div', { class: 'composer-input-row' }, input);

    const attachments = h('div', { class: 'attachments', attrs: { hidden: 'hidden' } });

    const pills: Record<string, Pill> = {};
    const barLeft = h('div', { class: 'bar-left' });
    barLeft.appendChild(attachBtn);
    for (let i = 0; i < CX.PILL_DEFS.length; i++) {
      const def = CX.PILL_DEFS[i];
      const pill = CX.buildPill(def);
      pills[def.key] = pill;
      barLeft.appendChild(pill.el);
    }

    const barRight = h('div', { class: 'bar-right' });
    const toggles = CX.buildToggles(barRight);
    barRight.appendChild(toggles.rc);
    barRight.appendChild(toggles.flame);
    barRight.appendChild(toggles.guard);
    barRight.appendChild(toggles.follow);
    barRight.appendChild(toggles.vibe);
    barRight.appendChild(sendBtn);
    const bar = h('div', { class: 'composer-bar' }, barLeft, barRight);

    const readout = h('div', { class: 'readout', attrs: { hidden: 'hidden' } });
    const usageBars = h('div', { class: 'usage-bars', attrs: { hidden: 'hidden' } });

    const controls = CX.buildActionRows();

    const card = h('div', { class: 'composer-card' }, controls, inputRow, bar);
    const palette = document.getElementById('palette');
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
          const list: HTMLElement[] = [];
          for (let n = 0; n < barLeft.children.length; n++) list.push(barLeft.children[n] as HTMLElement);
          return list.concat([toggles.follow, toggles.vibe]);
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
          const key = el.getAttribute && el.getAttribute('data-pill');
          for (let n = 0; key && n < CX.PILL_DEFS.length; n++) {
            if (CX.PILL_DEFS[n].key === key) {
              CX.togglePillMenu(CX.PILL_DEFS[n], anchor);
              return true;
            }
          }
          return false;
        },
      });
    }

    CX.wireInput(input);
    CX.wireImageDrop(card);
    CX.wireImagePaste(input);

    CX.renderAttachments();

    if (CX.lastState) renderState(CX.lastState);
    CX.renderGhost();
    CX.applyFollow();
    setTimeout(CX.applyFollow, 60);

    els.input.focus();
    return true;
  }
  CX.ensureBuilt = ensureBuilt;

  function renderState(s: ComposerState | null): void {
    if (!s) return;
    CX.announceTurnState(s);
    CX.setGuardOn(s.guardOn);
    CX.setRemoteControlOn(s.remoteControlOn, s.remoteControlError);
    CX.setGodMode(s.godModeOn);
    CX.renderAuth(s);
    CX.renderSendMode(s);
    CX.renderPills(s);
    CX.renderQueue(s.queue);
    CX.renderReadout(s);
    CX.setGhost(s.suggestion != null ? String(s.suggestion) : '');
    CX.renderGhost();
    CX.syncOpenMenu();
    CX.refreshOverflow();
  }

  cc.state = function (s?: unknown): void {
    CX.lastState = (s as ComposerState) || null;
    if (CX.lastState) CX.renderBoot(CX.lastState);
    if (!ensureBuilt()) return;
    renderState(CX.lastState);
  };

  cc.meta = function (m?: unknown): void {
    const meta = m as { commands?: unknown; hostClipboard?: unknown; installMethods?: unknown } | null;
    CX.setCommands(meta && Array.isArray(meta.commands) ? (meta.commands.slice() as PaletteCommand[]) : []);
    if (meta && typeof meta.hostClipboard === 'boolean') CX.hostClipboard = meta.hostClipboard;
    if (meta && Array.isArray(meta.installMethods))
      CX.setInstallMethods(meta.installMethods.slice() as InstallMethod[]);
  };

  cc.focusInput = function (): void {
    if (!ensureBuilt() || !els || !els.input) return;
    els.input.focus();
  };

  cc.insertText = function (text?: unknown): void {
    if (text == null) return;
    const focused = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
    const editable =
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
