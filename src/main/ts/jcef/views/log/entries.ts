(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const L = (D.log = D.log || ({} as LogNs));
  const h = D.h;

  const TAIL_SLACK_PX = 4;

  L.list = function (): HTMLElement {
    if (!L.listEl) {
      L.listEl = h('div', {
        class: 'log-entries',
        attrs: { role: 'log', 'aria-label': 'Plugin log lines', 'aria-live': 'off' },
      });
    }
    return L.listEl;
  };

  L.lineNode = function (line: LogLine): HTMLElement {
    const level = L.text(line.level, 'debug');
    const node = h(
      'div',
      { class: 'log-line ' + level, attrs: { 'data-seq': String(L.num(line.seq)), 'data-level': level } },
      h('span', { class: 'log-when', text: L.when(line.at) }),
      h('span', { class: 'log-level', text: level }),
      h('span', { class: 'log-category', text: L.text(line.category, '') }),
      h('span', { class: 'log-text', text: L.text(line.text, '') })
    );
    node.hidden = !L.shows(line);
    return node;
  };

  L.append = function (lines: LogLine[]): void {
    const list = L.list();
    if (!lines.length) return;
    const atTail = list.scrollTop + list.clientHeight >= list.scrollHeight - TAIL_SLACK_PX;
    lines.forEach(function (line) {
      list.appendChild(L.lineNode(line));
    });
    if (atTail) list.scrollTop = list.scrollHeight;
  };

  L.reset = function (): void {
    const list = L.list();
    while (list.firstChild) list.removeChild(list.firstChild);
  };

  L.applyFilter = function (): void {
    const list = L.list();
    for (let i = 0; i < list.children.length; i++) {
      const el = list.children[i] as HTMLElement;
      el.hidden = !L.shows({ level: el.getAttribute('data-level') });
    }
  };
})();
