(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const T = (CC.tabbar = CC.tabbar || ({} as TabbarNs));
  const h = CC.h;

  function pill(opts: TabPillOptions): HTMLElement {
    const kids: HTMLElement[] = [];
    if (opts.status) {
      kids.push(h('span', { class: 'pill-dot ' + opts.status, attrs: { 'aria-hidden': 'true' } }));
    }
    kids.push(h('span', { class: 'pill-label', text: opts.label }));
    const btn = h(
      'button',
      {
        class: 'pill' + (opts.selected ? ' selected' : ''),
        attrs: {
          type: 'button',
          title: opts.title || opts.label,
          'aria-label': opts.title || opts.label,
          'aria-current': opts.selected ? 'true' : 'false',
          'aria-expanded': opts.expanded == null ? null : opts.expanded ? 'true' : 'false',
        },
        on: { click: opts.onClick },
      },
      kids
    );
    const wrap = h(
      'div',
      {
        class:
          'pill-wrap' + (opts.selected ? ' selected' : '') + (opts.expanded === true ? ' branch-open' : ''),
      },
      btn
    );
    if (opts.onClose) {
      const onClose = opts.onClose;
      const close = h('button', {
        class: 'pill-x',
        text: '×',
        attrs: { type: 'button', 'aria-label': 'Close ' + opts.label, title: 'Close' },
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            ev.stopPropagation();
            onClose();
          },
        },
      });
      wrap.appendChild(close);
    }
    return wrap;
  }

  T.pill = pill;
})();
