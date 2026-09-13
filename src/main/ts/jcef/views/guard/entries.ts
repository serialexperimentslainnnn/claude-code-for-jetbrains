(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const GL = (D.guardLog = D.guardLog || ({} as GuardLogNs));
  const h = D.h;
  const send = D.send;

  const VERDICT_CLASS: Record<string, string> = {
    DENIED: 'guard-verdict guard-denied',
    ASKED: 'guard-verdict guard-asked',
    ALLOWED: 'guard-verdict guard-allowed',
  };

  function detailRow(label: string, value: string | null): HTMLElement | null {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'guard-detail' },
      h('span', { class: 'guard-detail-label', text: label }),
      h('span', { class: 'guard-detail-value', text: String(value) })
    );
  }

  function commandRow(command: unknown): HTMLElement | null {
    if (command == null || command === '') return null;
    return h('pre', { class: 'guard-cmd' }, h('code', { text: String(command) }));
  }

  function askAction(entry: GuardLogEntry): HTMLElement | null {
    if (!entry.explainable) return null;
    return h('button', {
      class: 'guard-ask',
      attrs: { type: 'button' },
      text: 'Ask Claude why',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          send({ type: 'guardExplain', id: GL.text(entry.id, '') });
        },
      },
    });
  }

  function whitelistAction(entry: GuardLogEntry): HTMLElement | null {
    const command = GL.text(entry.command, '');
    const rule = GL.text(entry.rule, '');
    if (!command || !rule || GL.text(entry.tab, '') === 'whitelisted') return null;
    return h('button', {
      class: 'guard-ask guard-whitelist',
      attrs: { type: 'button' },
      text: 'Whitelist',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          send({ type: 'guardWhitelist', rule: rule, command: command });
        },
      },
    });
  }

  function entryActions(entry: GuardLogEntry): HTMLElement | null {
    const actions = [askAction(entry), whitelistAction(entry)].filter(Boolean) as HTMLElement[];
    if (!actions.length) return null;
    return h('div', { class: 'guard-entry-actions' }, actions);
  }

  GL.entryNode = function (entry: GuardLogEntry): HTMLElement {
    const head = h(
      'div',
      { class: 'guard-entry-head' },
      h('span', {
        class: VERDICT_CLASS[GL.text(entry.verdict, '')] || 'guard-verdict',
        text: GL.text(entry.verdictLabel, GL.text(entry.verdict, '—')),
      }),
      h('span', { class: 'guard-rule', text: GL.text(entry.ruleLabel, GL.text(entry.rule, 'Unknown rule')) }),
      h('span', { class: 'guard-when', text: GL.when(entry.at) })
    );
    return h(
      'div',
      { class: 'guard-entry' },
      head,
      detailRow('Category', GL.textOrNull(entry.category)),
      detailRow('Tool', GL.textOrNull(entry.tool)),
      detailRow('Matched', GL.textOrNull(entry.detail)),
      detailRow('Allowed by', GL.textOrNull(entry.viaLabel)),
      commandRow(entry.command),
      entryActions(entry)
    );
  };
})();
