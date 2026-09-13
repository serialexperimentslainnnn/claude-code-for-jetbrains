(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const PA = (CX.palette = CX.palette || ({} as PaletteNs));

  const h = CX.h;

  PA.state = { items: [], active: 0, navigated: false };

  PA.composerInput = function (): HTMLTextAreaElement | null {
    return CX.els && CX.els.input ? CX.els.input : null;
  };

  PA.paletteEl = function (): PaletteEl | null {
    return CC.els && CC.els.palette ? (CC.els.palette as PaletteEl) : null;
  };

  PA.isOpen = function (): boolean {
    const p = PA.paletteEl();
    return !!(p && p.__built && !p.hasAttribute('hidden'));
  };

  PA.ensureBuilt = function (): PaletteEl | null {
    const p = PA.paletteEl();
    if (!p) return null;
    if (!p.__built) {
      p.__built = true;
      p.innerHTML = '';
      const box = h('div', { class: 'palette-box' });
      const list = h('div', {
        class: 'palette-list',
        attrs: { id: 'palette-list', role: 'listbox', 'aria-label': 'Slash commands' },
      });
      box.appendChild(list);
      p.appendChild(box);
      p.__list = list;

      box.addEventListener('mousedown', function (e: Event) {
        e.preventDefault();
      });
    }
    return p;
  };

  PA.linkInput = function (open: boolean): void {
    const input = PA.composerInput();
    if (!input) return;
    if (!open) {
      input.removeAttribute('role');
      input.removeAttribute('aria-expanded');
      input.removeAttribute('aria-controls');
      input.removeAttribute('aria-autocomplete');
      input.removeAttribute('aria-activedescendant');
      return;
    }
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-expanded', 'true');
    input.setAttribute('aria-controls', 'palette-list');
    input.setAttribute('aria-autocomplete', 'list');
  };

  function optionId(index: number): string {
    return 'palette-opt-' + index;
  }

  PA.syncActiveDescendant = function (): void {
    const input = PA.composerInput();
    if (!input) return;
    if (PA.state.navigated && PA.state.items.length) {
      input.setAttribute('aria-activedescendant', optionId(PA.state.active));
    } else {
      input.removeAttribute('aria-activedescendant');
    }
  };

  PA.renderList = function (onPick: (idx: number) => void): void {
    const p = PA.paletteEl();
    if (!p || !p.__list) return;
    const list = p.__list;
    list.innerHTML = '';
    const items = PA.state.items;
    if (!items.length) {
      list.appendChild(h('div', { class: 'palette-empty', text: 'No matching commands' }));
      PA.syncActiveDescendant();
      return;
    }
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      const idx = i;
      const active = PA.state.navigated && idx === PA.state.active;
      const match = !PA.state.navigated && idx === 0 && PA.state.items.length > 1;
      const row = h(
        'div',
        {
          class: 'palette-item' + (active ? ' active' : '') + (match ? ' match' : ''),
          attrs: { id: optionId(idx), role: 'option', 'aria-selected': active ? 'true' : 'false' },
          on: {
            click: function (e: Event) {
              e.preventDefault();
              onPick(idx);
            },
          },
        },
        h('span', { class: 'palette-name', text: '/' + it.name }),
        it.description ? h('span', { class: 'palette-desc', text: it.description }) : null
      );
      list.appendChild(row);
    }
    PA.syncActiveDescendant();
  };

  PA.updateActiveClass = function (): void {
    const p = PA.paletteEl();
    if (!p || !p.__list) return;
    const rows = p.__list.querySelectorAll('.palette-item');
    for (let i = 0; i < rows.length; i++) {
      if (PA.state.navigated && i === PA.state.active) {
        rows[i].classList.add('active');
        rows[i].setAttribute('aria-selected', 'true');
      } else {
        rows[i].classList.remove('active');
        rows[i].setAttribute('aria-selected', 'false');
      }
    }
    PA.syncActiveDescendant();
  };
})();
