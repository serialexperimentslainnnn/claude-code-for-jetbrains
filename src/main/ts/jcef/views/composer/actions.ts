(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;

  function svg(body: string): string {
    return (
      '<svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" ' +
      'stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      body +
      '</svg>'
    );
  }

  const GLYPH = {
    newChat: svg('<path d="M8 3.5v9"/><path d="M3.5 8h9"/>'),
    commands: svg('<circle cx="7" cy="7" r="4"/><path d="m10 10 3 3"/>'),
    git: svg(
      '<circle cx="4.5" cy="3.5" r="1.8"/><circle cx="4.5" cy="12.5" r="1.8"/><circle cx="11.5" cy="8" r="1.8"/><path d="M4.5 5.3v5.4"/><path d="M9.7 8H8.2a3.7 3.7 0 0 1-3.7-3.7"/>'
    ),
    security: svg(
      '<path d="M8 2.2 12.8 4v4.1c0 2.9-1.9 5.1-4.8 6.5C5.1 13.2 3.2 11 3.2 8.1V4z"/><path d="m6 8 1.5 1.5L10.5 6"/>'
    ),
    closeChat: svg('<path d="M3 4.5h10"/><path d="M6.5 4.5V3h3v1.5"/><path d="M4.5 4.5 5 13h6l.5-8.5"/>'),
    signOut: svg('<path d="M9.5 3.5H4v9h5.5"/><path d="M11 5.5 13.5 8 11 10.5"/><path d="M13.5 8h-6"/>'),
  };

  function actionButton(glyph: string, label: string, onClick: () => void): HTMLElement {
    const btn = h('button', {
      class: 'bar-icon',
      title: label,
      attrs: { type: 'button', 'aria-label': label },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          onClick();
        },
      },
    });
    btn.innerHTML = glyph;
    return btn;
  }

  function send(message: unknown): void {
    if (CC.send) CC.send(message);
  }

  CX.buildActionRows = function (): HTMLElement | null {
    const controls = document.getElementById('controls');
    const actions = document.getElementById('actions');
    const views = document.getElementById('views');
    if (!controls || !actions || !views) return null;
    if (CX.mountSettingsButton) CX.mountSettingsButton();
    actions.innerHTML = '';
    const commandsBtn = actionButton(GLYPH.commands, 'Browse slash commands', function () {
      if (cc.openPalette) cc.openPalette();
    });
    [
      actionButton(GLYPH.newChat, 'New chat', function () {
        send({ type: 'newChat' });
      }),
      commandsBtn,
      actionButton(GLYPH.git, 'Git', function () {
        send({ type: 'openGitView' });
      }),
      actionButton(GLYPH.security, 'Dependency vulnerabilities', function () {
        send({ type: 'openVulnView' });
      }),
      actionButton(GLYPH.closeChat, 'Close this chat', function () {
        send({ type: 'closeThisChat' });
      }),
      actionButton(GLYPH.signOut, 'Log out of Claude', function () {
        send({ type: 'logout' });
      }),
    ].forEach(function (button) {
      actions.appendChild(button);
    });
    wireOverflow(controls, views, actions);
    return controls;
  };

  function wireOverflow(controls: HTMLElement, views: HTMLElement, actions: HTMLElement): void {
    if (!CX.createOverflow) return;
    CX.createOverflow({
      row: controls,
      label: 'More chat controls',
      items: function () {
        const out: HTMLElement[] = [];
        collect(views, out);
        collect(actions, out);
        return out;
      },
      place: function (btn) {
        actions.appendChild(btn);
      },
    });
  }

  function collect(container: HTMLElement | null, out: HTMLElement[]): void {
    if (!container) return;
    for (let i = 0; i < container.children.length; i++) {
      const el = container.children[i] as HTMLElement;
      if (el.classList.contains('dash-toggles')) collect(el, out);
      else out.push(el);
    }
  }

  CX.viewsRow = function (): HTMLElement | null {
    return document.getElementById('views');
  };
})();
