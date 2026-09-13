(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const h = CX.h;
  const send = CX.send;

  function attachMenuItem(label: string, onClick: () => void): HTMLElement {
    return h(
      'div',
      {
        class: 'menu-item',
        attrs: { role: 'option' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            onClick();
          },
        },
      },
      h('span', { class: 'menu-item-label', text: label })
    );
  }

  AT.menuEl = function (): HTMLElement | null {
    return CX.openMenu && CX.openMenu.pill === '__attach' ? CX.openMenu.el : null;
  };

  AT.bodyEl = function (): HTMLElement | null {
    const menu = AT.menuEl();
    return menu ? menu.querySelector<HTMLElement>('.attach-body') : null;
  };

  AT.reposition = function (): void {
    if (CX.openMenu && CX.openMenu.el && CX.openMenu.anchor) {
      CX.positionMenu(CX.openMenu.el, CX.openMenu.anchor);
    }
  };

  AT.renderMenu = function (menu: HTMLElement, from?: string | null, focusSearch?: boolean): void {
    menu.innerHTML = '';
    const body = h('div', { class: 'attach-body' });
    if (from) body.classList.add(from);
    menu.appendChild(body);
    if (AT.view === 'tree') AT.buildTreeView(body);
    else AT.buildRootView(body);
    AT.reposition();
    if (focusSearch !== false) focusSearchSoon(body);
  };

  function focusSearchSoon(body: HTMLElement): void {
    const search = body.querySelector<HTMLElement>('.attach-search');
    if (!search) return;
    setTimeout(function () {
      try {
        search.focus();
      } catch (e) {}
    }, 0);
  }

  AT.buildRootView = function (body: HTMLElement): void {
    const search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: 'Search recent files…', 'aria-label': 'Search recent files' },
    }) as HTMLInputElement;
    const list = h('div', { class: 'attach-list' });

    function paint(q: string): void {
      list.innerHTML = '';
      const actions: { label: string; fn: () => void }[] = [
        {
          label: 'Files…',
          fn: function () {
            AT.enterTree('files');
          },
        },
        {
          label: 'Directory…',
          fn: function () {
            AT.enterTree('directories');
          },
        },
        {
          label: 'Image…',
          fn: function () {
            CX.closeMenu();
            send({ type: 'pasteClipboardImage', notify: true });
          },
        },
      ];
      if (AT.data.hasSelection)
        actions.push({
          label: 'Current selection',
          fn: function () {
            CX.closeMenu();
            send({ type: 'attachSelection' });
          },
        });
      if (AT.data.hasFile)
        actions.push({
          label: 'Current file',
          fn: function () {
            CX.closeMenu();
            send({ type: 'attachCurrentFile' });
          },
        });
      actions.forEach(function (a) {
        list.appendChild(attachMenuItem(a.label, a.fn));
      });

      const recent = Array.isArray(AT.data.recent) ? AT.data.recent : [];
      const ql = (q || '').toLowerCase();
      const matched = recent.filter(function (r) {
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
          const row = h(
            'div',
            {
              class: 'menu-item attach-recent',
              attrs: { role: 'option', title: String(r.path || '') },
              on: {
                click: function (e: Event) {
                  e.preventDefault();
                  e.stopPropagation();
                  CX.closeMenu();
                  send({ type: 'attachPath', path: r.path });
                },
              },
            },
            h('span', { class: 'attach-icon', html: AT.fileIconGlyph(r.ext) }),
            h('span', { class: 'attach-name', text: String(r.name || r.path || '') })
          );
          list.appendChild(row);
        });
      }
    }

    body.appendChild(search);
    body.appendChild(list);
    search.addEventListener('input', function () {
      paint(search.value);
      AT.reposition();
    });
    paint('');
  };

  CX.toggleAttachMenu = function (anchorEl: HTMLElement): void {
    if (CX.openMenu && CX.openMenu.pill === '__attach') {
      CX.closeMenu();
      return;
    }
    CX.closeMenu();
    AT.view = 'root';
    AT.tree = null;
    const menu = h('div', { class: 'menu attach-menu' });
    menu.addEventListener('keydown', AT.onMenuKey);
    document.body.appendChild(menu);
    CX.openMenu = { el: menu, pill: '__attach', anchor: anchorEl };
    AT.renderMenu(menu);
    anchorEl.classList.add('pill-open');
    send({ type: 'requestAttachData' });
  };

  cc.attachData = function (payload?: unknown): void {
    const p = payload as { recent?: unknown; hasSelection?: unknown; hasFile?: unknown } | null;
    if (p && typeof p === 'object') {
      AT.data = {
        recent: Array.isArray(p.recent) ? (p.recent as RecentFile[]) : [],
        hasSelection: !!p.hasSelection,
        hasFile: !!p.hasFile,
      };
    }
    const menu = AT.menuEl();
    if (AT.view === 'root' && menu) {
      AT.renderMenu(menu);
    }
  };
})();
