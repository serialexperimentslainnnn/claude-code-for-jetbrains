(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const L = (D.log = D.log || ({} as LogNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const LEVELS = [
    { id: 'all', label: 'All' },
    { id: 'warn', label: 'Warn' },
    { id: 'info', label: 'Info' },
    { id: 'debug', label: 'Debug' },
  ];

  function chip(spec: { id: string; label: string }): HTMLElement {
    const active = spec.id === L.level;
    return h('button', {
      class: 'log-chip' + (active ? ' active' : ''),
      attrs: { type: 'button', 'data-log-level': spec.id, 'aria-pressed': active ? 'true' : 'false' },
      text: spec.label,
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          if (L.level === spec.id) return;
          L.level = spec.id;
          L.applyFilter();
          L.repaint();
        },
      },
    });
  }

  function debugSwitch(): HTMLElement {
    return h('button', {
      class: 'log-switch' + (L.debug ? ' on' : ''),
      attrs: { type: 'button', role: 'switch', 'aria-checked': L.debug ? 'true' : 'false' },
      text: 'Debug',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          L.debug = !L.debug;
          send({ type: 'logDebug', on: L.debug });
          L.request();
          L.repaint();
        },
      },
    });
  }

  function copyButton(): HTMLElement {
    return h('button', {
      class: 'log-copy',
      attrs: { type: 'button', title: 'Copy the shown lines with a report header' },
      text: 'Copy',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          send({ type: 'logCopy', level: L.level });
          CC.flashCopied(ev.currentTarget as HTMLElement);
        },
      },
    });
  }

  function noteText(): string {
    const shown = L.lines.filter(L.shows).length;
    const dropped = L.ring.dropped ? ' · ' + L.ring.dropped + ' older line(s) dropped' : '';
    return (
      shown +
      ' of ' +
      L.lines.length +
      ' line(s) shown · the ring keeps the last ' +
      L.ring.max +
      dropped +
      ' · debug ' +
      (L.debug ? 'on' : 'off') +
      ' for this IDE session'
    );
  }

  function toolsCard(): HTMLElement | null {
    return card(
      'Plugin log',
      [
        h(
          'div',
          { class: 'log-tools' },
          h(
            'div',
            { class: 'log-chips', attrs: { role: 'group', 'aria-label': 'Levels to show' } },
            LEVELS.map(chip)
          ),
          debugSwitch(),
          copyButton()
        ),
        h('div', { class: 'log-note', text: noteText() }),
      ],
      true,
      'log-tools'
    );
  }

  function entriesCard(): HTMLElement | null {
    if (!L.entriesEl) {
      L.emptyEl = h('div', { class: 'log-empty', text: 'Nothing has been logged yet.' });
      L.entriesEl = card('Lines', [L.list(), L.emptyEl], true, 'log-entries');
    }
    if (L.emptyEl) L.emptyEl.hidden = L.lines.length > 0;
    return L.entriesEl;
  }

  D.buildLogCards = function (): (HTMLElement | null)[] {
    return [toolsCard(), entriesCard()];
  };

  D.logVisible = function (visible: boolean): void {
    L.setVisible(!!visible);
  };

  cc.log = function (payload?: unknown): void {
    if (!payload || typeof payload !== 'object') return;
    L.absorb(payload as LogPayload);
    L.repaint();
  };
})();
