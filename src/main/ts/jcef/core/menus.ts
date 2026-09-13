(function () {
  'use strict';

  const CC = window.CC || (window.CC = {} as CcShared);

  CC.placeMenu = function (menu: HTMLElement, anchor: HTMLElement): void {
    const margin = 8;
    const r = anchor.getBoundingClientRect();
    menu.style.position = 'fixed';
    menu.style.maxWidth = window.innerWidth - margin * 2 + 'px';
    const mw = menu.offsetWidth;
    const mh = menu.offsetHeight;
    let left = Math.min(Math.round(r.left), window.innerWidth - mw - margin);
    if (left < margin) left = margin;
    let top = r.top - mh - 6;
    if (top < margin) top = r.bottom + 6;
    if (top + mh > window.innerHeight - margin) top = Math.max(margin, window.innerHeight - mh - margin);
    menu.style.left = left + 'px';
    menu.style.top = Math.round(top) + 'px';
  };

  CC.GUARD_DURATIONS = [
    { token: '5m', label: '5 minutes' },
    { token: '15m', label: '15 minutes' },
    { token: '30m', label: '30 minutes' },
    { token: '4h', label: '4 hours' },
    { token: '8h', label: '8 hours' },
    { token: 'ide', label: 'Until IDE closes' },
    { token: 'forever', label: 'Forever' },
  ];

  CC.durationMenu = function (opts: DurationMenuOptions): PickMenu {
    return CC.pickMenu({
      anchor: opts.anchor,
      home: opts.home,
      label: opts.label || 'Disable for',
      watch: opts.watch,
      items: CC.GUARD_DURATIONS.map(function (d) {
        return { value: d.token, label: d.label };
      }),
      onPick: opts.onPick,
    });
  };

  CC.pickMenu = function (opts: PickMenuOptions): PickMenu {
    const anchor = opts.anchor;
    const home = opts.home;
    const checkable = !!opts.checkable;
    const options: HTMLButtonElement[] = [];
    let isOpen = false;
    const menu = document.createElement('div');
    menu.className = opts.menuClass || 'guard-disable-menu';
    menu.setAttribute('role', 'menu');
    menu.setAttribute('hidden', 'hidden');
    menu.setAttribute('aria-label', opts.label || 'Menu');

    function focusOption(at: number): void {
      const target = options[(at + options.length) % options.length];
      if (target) target.focus({ preventScroll: true });
    }
    function onOutside(e: Event): void {
      const target = e.target as Node | null;
      if (menu.contains(target) || anchor.contains(target)) return;
      setOpen(false);
    }
    function onEscape(e: KeyboardEvent): void {
      if (e.key !== 'Escape') return;
      setOpen(false);
      anchor.focus();
    }
    function onViewChange(): void {
      setOpen(false);
    }
    const watch =
      window.MutationObserver && opts.watch
        ? new window.MutationObserver(function () {
            if (!anchor.isConnected) setOpen(false);
          })
        : null;

    function setOpen(open: boolean): void {
      if (open === isOpen) return;
      isOpen = open;
      anchor.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (!open) {
        menu.setAttribute('hidden', 'hidden');
        home.appendChild(menu);
        document.removeEventListener('mousedown', onOutside, true);
        document.removeEventListener('keydown', onEscape, true);
        document.removeEventListener('scroll', onViewChange, true);
        window.removeEventListener('resize', onViewChange);
        if (watch) watch.disconnect();
        return;
      }
      document.body.appendChild(menu);
      menu.removeAttribute('hidden');
      CC.placeMenu(menu, anchor);
      document.addEventListener('mousedown', onOutside, true);
      document.addEventListener('keydown', onEscape, true);
      document.addEventListener('scroll', onViewChange, true);
      window.addEventListener('resize', onViewChange);
      const watched = watch && opts.watch ? opts.watch() : null;
      if (watch && watched) watch.observe(watched, { childList: true });
      focusOption(0);
    }

    menu.addEventListener('keydown', function (e: KeyboardEvent) {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      const at = options.indexOf(document.activeElement as HTMLButtonElement);
      focusOption(at < 0 ? (step > 0 ? 0 : options.length - 1) : at + step);
    });

    const items = opts.items || [];
    items.forEach(function (item) {
      const option = document.createElement('button');
      option.className = opts.itemClass || 'guard-disable-option';
      option.type = 'button';
      option.setAttribute('role', checkable ? 'menuitemcheckbox' : 'menuitem');
      option.textContent = item.label;
      if (checkable) option.setAttribute('aria-checked', item.checked ? 'true' : 'false');
      option.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        opts.onPick(item.value);
        if (!checkable) {
          setOpen(false);
          anchor.focus();
          return;
        }
        sync();
      });
      options.push(option);
      menu.appendChild(option);
    });

    function sync(): void {
      const checkedOf = opts.checkedOf;
      if (!checkable || typeof checkedOf !== 'function') return;
      options.forEach(function (option, index) {
        option.setAttribute('aria-checked', checkedOf(items[index].value) ? 'true' : 'false');
      });
    }

    home.appendChild(menu);
    return {
      menu: menu,
      sync: sync,
      toggle: function () {
        setOpen(!isOpen);
      },
      close: function () {
        setOpen(false);
      },
    };
  };
})();
