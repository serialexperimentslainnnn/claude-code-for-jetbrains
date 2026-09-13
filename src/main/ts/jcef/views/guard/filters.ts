(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const GL = (D.guardLog = D.guardLog || ({} as GuardLogNs));
  const h = D.h;
  const card = D.card;

  const ALL = '__all__';

  const DASHBOARD_ID = 'cc-dashboard';

  function toggled(picked: string[] | null, value: string): string[] | null {
    if (value === ALL) return null;
    const next = (picked || []).slice();
    const at = next.indexOf(value);
    if (at >= 0) next.splice(at, 1);
    else next.push(value);
    return next.length ? next : null;
  }

  function multiSelect(
    label: string,
    options: { id: string; label: string }[],
    pickedOf: () => string[] | null,
    onPick: (picked: string[] | null) => void
  ): HTMLElement | null {
    const core = D.core();
    if (!core || typeof core.pickMenu !== 'function') return null;

    const trigger = h('button', {
      class: 'guard-filter-trigger',
      attrs: { type: 'button', 'aria-haspopup': 'menu', 'aria-expanded': 'false' },
      text: label,
    });
    const wrapper = h(
      'div',
      { class: 'guard-filter' },
      h('span', { class: 'guard-filter-label', text: label }),
      trigger
    );

    const items: PickItem[] = [{ value: ALL, label: 'All' }].concat(
      options.map(function (opt) {
        return { value: opt.id, label: opt.label };
      })
    );

    const menu = core.pickMenu({
      anchor: trigger,
      home: wrapper,
      label: label,
      checkable: true,
      menuClass: 'guard-disable-menu guard-filter-menu',
      itemClass: 'guard-disable-option',
      items: items,
      checkedOf: function (value) {
        const picked = pickedOf();
        return value === ALL ? !picked : !!picked && picked.indexOf(value) >= 0;
      },
      onPick: function (value) {
        onPick(toggled(pickedOf(), value));
      },
      watch: function () {
        return document.getElementById(DASHBOARD_ID);
      },
    });
    menu.sync();

    trigger.addEventListener('click', function (ev: Event) {
      ev.preventDefault();
      menu.toggle();
    });

    return wrapper;
  }

  function searchBox(): HTMLElement {
    const input = h('input', {
      class: 'guard-search',
      attrs: {
        type: 'search',
        placeholder: 'rule, command, tool…',
        'aria-label': 'Search the guard log',
      },
      on: {
        input: function (ev: Event) {
          GL.queryRaw = String((ev.currentTarget as HTMLInputElement).value || '');
          GL.query = GL.queryRaw.trim().toLowerCase();
          GL.repaint();
        },
      },
    }) as HTMLInputElement;
    input.value = GL.queryRaw;
    return h(
      'label',
      { class: 'guard-filter guard-filter-search' },
      h('span', { class: 'guard-filter-label', text: 'Search' }),
      input
    );
  }

  function filterStrip(): HTMLElement {
    const categories = GL.catalog().map(function (c) {
      return { id: GL.text(c.id, ''), label: GL.text(c.label, GL.text(c.id, '')) };
    });
    return h(
      'div',
      { class: 'guard-filters', attrs: { role: 'group', 'aria-label': 'Filter the guard log' } },
      searchBox(),
      multiSelect(
        'Category',
        categories,
        function () {
          return GL.pickedCategories;
        },
        function (picked) {
          GL.pickedCategories = picked;
          GL.repaint();
        }
      ),
      multiSelect(
        'Rule',
        GL.rulesOfCategories(null),
        function () {
          return GL.pickedRules;
        },
        function (picked) {
          GL.pickedRules = picked;
          GL.repaint();
        }
      )
    );
  }

  GL.buildFiltersCard = function (): HTMLElement | null {
    if (!GL.catalog().length) return null;
    return card('Filter', filterStrip(), true, 'guard-filters');
  };
})();
