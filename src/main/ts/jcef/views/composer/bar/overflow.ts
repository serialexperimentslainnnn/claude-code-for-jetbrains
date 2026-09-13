(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;
  const overflowLabel = CX.overflowLabel;

  let openOverflow: { el: HTMLElement; owner: OverflowApi } | null = null;
  const rows: OverflowApi[] = [];

  CX.createOverflow = function (opts: OverflowOptions): OverflowApi | null {
    const row = opts.row as OverflowRow;
    if (!row) return null;
    if (row.__ccOverflow) return row.__ccOverflow;

    const toggle = h('button', {
      class: 'bar-icon overflow-btn',
      title: opts.label,
      attrs: {
        type: 'button',
        'aria-label': opts.label,
        'aria-haspopup': 'menu',
        'aria-expanded': 'false',
      },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          if (openOverflow && openOverflow.owner === api) closeMenu(true);
          else openMenu();
        },
        keydown: function (e: KeyboardEvent) {
          if (e.key === 'ArrowDown' || e.key === 'Down') {
            e.preventDefault();
            if (!(openOverflow && openOverflow.owner === api)) openMenu();
          }
        },
      },
    });
    toggle.innerHTML = CX.dotsGlyph();

    let lastSig: string | null = null;
    let collectedSig = '';
    let collected: HTMLElement[] = [];
    let busy = false;

    function candidates(): HTMLElement[] {
      const all = opts.items() || [];
      const out: HTMLElement[] = [];
      for (let i = 0; i < all.length; i++) {
        if (all[i] && all[i] !== toggle && !all[i].hidden) out.push(all[i]);
      }
      return out;
    }

    function signature(list: HTMLElement[]): string {
      let sig = list.length + '|';
      for (let i = 0; i < list.length; i++) {
        sig += overflowLabel(list[i]) + ((list[i] as HTMLButtonElement).disabled ? '#' : '') + '|';
      }
      return sig;
    }

    function setCollapsed(el: HTMLElement, on: boolean): void {
      if (el.classList.contains('cc-collapsed') !== on) el.classList.toggle('cc-collapsed', on);
    }

    function detachToggle(): void {
      if (toggle.parentNode) toggle.parentNode.removeChild(toggle);
    }

    function update(force?: boolean): void {
      if (busy) return;
      const list = candidates();
      const sig = signature(list);
      if (!force && sig === lastSig) return;
      busy = true;
      try {
        lastSig = sig;
        for (let i = 0; i < list.length; i++) setCollapsed(list[i], false);
        const reserved = (opts.reserved && opts.reserved()) || [];
        const attached = !!toggle.parentNode;
        let plan = CX.overflowFit(CX.overflowMeasure(row, list, reserved, attached ? toggle : null));
        if (plan.toggle && !attached) {
          opts.place(toggle);
          plan = CX.overflowFit(CX.overflowMeasure(row, list, reserved, toggle));
        }
        apply(list, plan);
      } finally {
        busy = false;
      }
    }

    function apply(list: HTMLElement[], plan: OverflowPlan): void {
      collected = list.slice(plan.visible);
      for (let i = plan.visible; i < list.length; i++) setCollapsed(list[i], true);
      const lastStanding = list[plan.visible - 1];
      if (!plan.toggle && toggle.parentNode) {
        const held = document.activeElement === toggle;
        detachToggle();
        if (held && lastStanding && lastStanding.focus) lastStanding.focus();
      }
      const sig = signature(collected);
      if (sig === collectedSig) return;
      collectedSig = sig;
      if (openOverflow && openOverflow.owner === api) {
        const inside = openOverflow.el.contains(document.activeElement);
        closeMenu(false);
        const returnTo = plan.toggle ? toggle : lastStanding;
        if (inside && returnTo && returnTo.focus) returnTo.focus();
      }
    }

    function entries(): HTMLElement[] {
      if (!openOverflow || openOverflow.owner !== api) return [];
      return Array.prototype.slice.call(openOverflow.el.querySelectorAll('[role="menuitem"]'));
    }
    const roving = CX.roving(entries);

    function onMenuKey(e: KeyboardEvent): void {
      if (e.key === 'Escape' || e.key === 'Esc') {
        e.preventDefault();
        e.stopPropagation();
        closeMenu(true);
      } else if (e.key === 'ArrowDown' || e.key === 'Down') {
        e.preventDefault();
        roving.step(1);
      } else if (e.key === 'ArrowUp' || e.key === 'Up') {
        e.preventDefault();
        roving.step(-1);
      } else if (e.key === 'Home') {
        e.preventDefault();
        roving.focus(entries()[0]);
      } else if (e.key === 'End') {
        e.preventDefault();
        roving.focus(entries()[entries().length - 1]);
      } else if (e.key === 'Tab') {
        closeMenu(true);
      }
    }

    function entryFor(el: HTMLElement): HTMLElement {
      const disabled = !!(el as HTMLButtonElement).disabled;
      return h(
        'div',
        {
          class: 'menu-item',
          attrs: disabled
            ? { role: 'menuitem', tabindex: '-1', 'aria-disabled': 'true' }
            : { role: 'menuitem', tabindex: '-1' },
          title: overflowLabel(el),
          on: {
            click: function (e: Event) {
              e.preventDefault();
              e.stopPropagation();
              if (disabled) return;
              closeMenu(true);
              if (!(opts.activate && opts.activate(el, toggle))) el.click();
            },
          },
        },
        h('span', { class: 'menu-item-label', text: overflowLabel(el) })
      );
    }

    function openMenu(): void {
      if (!collected.length) return;
      if (CX.closeMenu) CX.closeMenu();
      if (openOverflow) openOverflow.owner.close(false);
      const menu = h('div', { class: 'menu', attrs: { role: 'menu', 'aria-label': opts.label } });
      for (let i = 0; i < collected.length; i++) menu.appendChild(entryFor(collected[i]));
      menu.addEventListener('keydown', onMenuKey);
      document.body.appendChild(menu);
      openOverflow = { el: menu, owner: api };
      toggle.setAttribute('aria-expanded', 'true');
      if (CX.positionMenu) CX.positionMenu(menu, toggle);
      roving.focus(entries()[0]);
    }

    function closeMenu(returnFocus: boolean): void {
      if (!openOverflow || openOverflow.owner !== api) return;
      if (openOverflow.el.parentNode) openOverflow.el.parentNode.removeChild(openOverflow.el);
      openOverflow = null;
      toggle.setAttribute('aria-expanded', 'false');
      if (returnFocus && toggle.parentNode) toggle.focus();
    }

    const api: OverflowApi = {
      update: update,
      close: closeMenu,
      toggle: toggle,
      collected: function () {
        return collected.slice();
      },
    };
    row.__ccOverflow = api;
    rows.push(api);

    if (typeof ResizeObserver === 'function') {
      let lastWidth = -1;
      new ResizeObserver(function (list) {
        const w = list && list[0] && list[0].contentRect ? list[0].contentRect.width : row.clientWidth;
        if (Math.abs(w - lastWidth) < 0.5) return;
        lastWidth = w;
        update(true);
      }).observe(row);
    }

    update(true);
    return api;
  };

  CX.refreshOverflow = function (force?: boolean): void {
    for (let i = 0; i < rows.length; i++) rows[i].update(force);
  };

  document.addEventListener(
    'mousedown',
    function (e: MouseEvent) {
      if (!openOverflow) return;
      const target = e.target as Node | null;
      if (openOverflow.el.contains(target)) return;
      if (openOverflow.owner.toggle.contains(target)) return;
      openOverflow.owner.close(false);
    },
    true
  );
})();
