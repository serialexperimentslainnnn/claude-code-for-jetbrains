(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const ST = (CX.settings = CX.settings || ({} as SettingsNs));

  const h = CX.h;

  let btn: HTMLElement | null = null;
  let menu: HTMLElement | null = null;
  let drawnSig = '';
  let slide = '';

  const WRENCH =
    '<svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor" aria-hidden="true">' +
    '<path transform="rotate(-45 8 8)" d="M3.4 3.6 Q3.4 1.6 5.4 1.6 L6.2 1.6 L6.2 4.9 Q6.2 6.3 8 6.3 Q9.8 6.3 9.8 4.9 L9.8 1.6 ' +
    'L10.6 1.6 Q12.6 1.6 12.6 3.6 L12.6 5.4 Q12.6 8.4 9.9 8.9 L9.9 13.1 Q9.9 14.4 8 14.4 ' +
    'Q6.1 14.4 6.1 13.1 L6.1 8.9 Q3.4 8.4 3.4 5.4 Z"/></svg>';

  function buildButton(): HTMLElement {
    const el = h('button', {
      class: 'bar-icon settings-btn',
      title: 'Chat settings',
      attrs: {
        type: 'button',
        'aria-label': 'Chat settings',
        'aria-haspopup': 'menu',
        'aria-expanded': 'false',
      },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          if (menu) close(true);
          else open();
        },
        keydown: function (e: KeyboardEvent) {
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

  CX.mountSettingsButton = function (): HTMLElement | null {
    const views = document.getElementById('views');
    if (!views) return null;
    if (!btn) btn = buildButton();
    if (views.firstChild !== btn) views.insertBefore(btn, views.firstChild);
    return btn;
  };

  ST.enterGroup = function (path: string): void {
    ST.view = path;
    slide = 'attach-from-right';
    repaint();
    rows.focus(ST.allRows()[1] || ST.allRows()[0]);
    refreshFromHost();
  };

  ST.openGroup = function (path: string): void {
    if (!btn) CX.mountSettingsButton();
    if (!menu) open();
    ST.enterGroup(path);
  };

  ST.leaveGroup = function (): void {
    const came = String(ST.view);
    const cut = came.lastIndexOf(ST.SEP);
    ST.view = cut < 0 ? null : came.slice(0, cut);
    slide = 'attach-from-left';
    repaint();
    rows.focus(rowForId('g' + ST.SEP + came) || ST.allRows()[0]);
  };

  ST.allRows = function (): SettingsRow[] {
    if (!menu) return [];
    return Array.prototype.slice.call(
      menu.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
    );
  };
  const rows = CX.roving(ST.allRows);

  function render(): void {
    if (!menu) return;
    const sig = ST.structureSig();
    if (sig === drawnSig) {
      syncStates();
      return;
    }
    const hadFocus = menu.contains(document.activeElement);
    const wanted = focusedId();
    drawnSig = sig;
    menu.textContent = '';
    const body = h('div', { class: 'settings-body' + (slide ? ' ' + slide : '') });
    body.appendChild(ST.buildBody());
    menu.appendChild(body);
    slide = '';
    const target = rowForId(wanted) || ST.allRows()[0] || null;
    if (hadFocus) rows.focus(target);
    else rows.set(target);
    if (CX.positionMenu && btn) CX.positionMenu(menu, btn);
  }

  function repaint(): void {
    drawnSig = '';
    render();
  }

  function syncStates(): void {
    const by: Record<string, boolean> = {};
    ST.items().forEach(function (it) {
      by[String(it.key)] = !!it.on;
    });
    ST.allRows().forEach(function (row) {
      if (row.__ccKey != null && Object.prototype.hasOwnProperty.call(by, row.__ccKey)) {
        ST.applyState(row, by[row.__ccKey]);
      }
    });
  }

  function focusedId(): string | null {
    const active = document.activeElement as SettingsRow | null;
    return active && active.__ccFocusId != null ? active.__ccFocusId : null;
  }

  function rowForId(id: string | null): SettingsRow | null {
    if (id == null) return null;
    const all = ST.allRows();
    for (let i = 0; i < all.length; i++) {
      if (all[i].__ccFocusId === id) return all[i];
    }
    return null;
  }

  function onMenuKey(e: KeyboardEvent): void {
    if (e.key === 'Escape' || e.key === 'Esc') {
      e.preventDefault();
      e.stopPropagation();
      if (ST.view != null) ST.leaveGroup();
      else close(true);
    } else if (e.key === 'ArrowDown' || e.key === 'Down') {
      e.preventDefault();
      rows.step(1);
    } else if (e.key === 'ArrowRight' || e.key === 'Right') {
      const entry = document.activeElement as SettingsRow | null;
      if (!entry || entry.__ccFocusId == null || entry.__ccFocusId.charAt(0) !== 'g') return;
      e.preventDefault();
      ST.enterGroup(entry.__ccFocusId.slice(2));
    } else if (e.key === 'ArrowLeft' || e.key === 'Left') {
      if (ST.view == null) return;
      e.preventDefault();
      ST.leaveGroup();
    } else if (e.key === 'ArrowUp' || e.key === 'Up') {
      e.preventDefault();
      rows.step(-1);
    } else if (e.key === 'Home') {
      e.preventDefault();
      rows.focus(ST.allRows()[0]);
    } else if (e.key === 'End') {
      e.preventDefault();
      rows.focus(ST.allRows()[ST.allRows().length - 1]);
    } else if (e.key === 'Tab') {
      close(true);
    }
  }

  function open(): void {
    if (menu || !btn) return;
    if (CX.closeMenu) CX.closeMenu();

    menu = h('div', {
      class: 'menu settings-menu',
      attrs: { role: 'menu', 'aria-label': 'Chat settings' },
    });
    ST.view = null;
    slide = '';
    drawnSig = '';
    menu.addEventListener('keydown', onMenuKey);
    document.body.appendChild(menu);
    render();
    btn.setAttribute('aria-expanded', 'true');
    if (CX.positionMenu) CX.positionMenu(menu, btn);
    rows.focus(ST.allRows()[0]);
    refreshFromHost();
  }

  function refreshFromHost(): void {
    CC.send({ type: 'settingsRefresh' });
  }

  function close(returnFocus: boolean): void {
    if (!menu) return;
    if (menu.parentNode) menu.parentNode.removeChild(menu);
    menu = null;
    drawnSig = '';
    if (!btn) return;
    btn.setAttribute('aria-expanded', 'false');
    if (returnFocus) btn.focus();
  }
  ST.close = close;

  document.addEventListener(
    'mousedown',
    function (e: MouseEvent) {
      if (!menu) return;
      const target = e.target as Node | null;
      if (menu.contains(target)) return;
      if (btn && btn.contains(target)) return;
      close(false);
    },
    true
  );

  cc.settingsMenu = function (data?: unknown): void {
    ST.payload = data && typeof data === 'object' ? (data as { items?: unknown }) : null;
    render();
  };

  CX.mountSettingsButton();
})();
