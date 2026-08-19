(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});
  var h = CC.h;

  function pill(opts) {
    var kids = [];
    if (opts.status) {
      kids.push(h('span', { class: 'pill-dot ' + opts.status, attrs: { 'aria-hidden': 'true' } }));
    }
    kids.push(h('span', { class: 'pill-label', text: opts.label }));
    var btn = h(
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
    var wrap = h(
      'div',
      {
        class:
          'pill-wrap' + (opts.selected ? ' selected' : '') + (opts.expanded === true ? ' branch-open' : ''),
      },
      btn
    );
    if (opts.onClose) {
      var close = h('button', {
        class: 'pill-x',
        text: '×',
        attrs: { type: 'button', 'aria-label': 'Close ' + opts.label, title: 'Close' },
        on: {
          click: function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            opts.onClose();
          },
        },
      });
      wrap.appendChild(close);
    }
    return wrap;
  }

  T.pill = pill;
})();
