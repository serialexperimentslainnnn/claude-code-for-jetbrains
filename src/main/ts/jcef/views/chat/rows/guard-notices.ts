(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const safeSend = TX.safeSend;
  const conversationEl = TX.conversationEl;

  interface BypassAction {
    label: string;
    message: Record<string, unknown>;
  }

  const BYPASS_ACTIONS: Record<string, BypassAction> = {
    enableGuard: {
      label: 'Enable Sensitive Guard',
      message: { type: 'guardMaster', on: true, duration: '' },
    },
    revokeApproval: { label: 'Disable this authorization', message: { type: 'guardRevokeApproval' } },
    removeFromWhitelist: { label: 'Remove from whitelist', message: { type: 'guardRemoveWhitelist' } },
  };

  function guardLogButton(): HTMLElement {
    return el('button', {
      class: 'guard-log-link',
      text: 'Open the guard log',
      attrs: { type: 'button' },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          if (typeof cc.openGuardView === 'function') cc.openGuardView();
        },
      },
    });
  }

  TX.buildBypassNotice = function (entry: TranscriptEntry): RowRec {
    const node = el('div', { class: 'notice guard-bypass' });
    const body = el('div', { class: 'body' }) as BodyEl;
    node.appendChild(body);

    const action = entry.bypassAction ? BYPASS_ACTIONS[entry.bypassAction] : undefined;
    const actions = el('div', { class: 'guard-block-actions' });
    if (action) {
      actions.appendChild(
        el('button', {
          class: 'guard-whitelist-link',
          text: action.label,
          attrs: { type: 'button' },
          on: {
            click: function (e: Event) {
              e.preventDefault();
              e.stopPropagation();
              const message: Record<string, unknown> = Object.assign({}, action.message);
              if (entry.bypassedRule) message.rule = String(entry.bypassedRule);
              if (entry.command) message.command = String(entry.command);
              safeSend(message);
            },
          },
        })
      );
    }
    actions.appendChild(guardLogButton());
    node.appendChild(actions);
    return { el: node, bodyNode: body, kind: 'md' };
  };

  TX.buildBlockNotice = function (rule: unknown, command: unknown): RowRec {
    const node = el('div', { class: 'notice guard-block' });
    const body = el('div', { class: 'body' }) as BodyEl;
    node.appendChild(body);

    const link = el('button', {
      class: 'guard-disable-link',
      text: 'Disable rule',
      attrs: { type: 'button', 'aria-expanded': 'false', 'aria-haspopup': 'menu' },
    });
    const actions = el('div', { class: 'guard-block-actions' });

    const menu = CC.durationMenu({
      anchor: link,
      home: actions,
      label: 'Disable this rule for',
      watch: conversationEl,
      onPick: function (token) {
        safeSend({ type: 'guardSuspend', rule: String(rule), duration: token });
      },
    });

    link.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      menu.toggle();
    });

    actions.appendChild(link);
    actions.appendChild(menu.menu);

    if (command) {
      actions.appendChild(
        el('button', {
          class: 'guard-whitelist-link',
          text: 'Whitelist Command',
          attrs: { type: 'button' },
          on: {
            click: function (e: Event) {
              e.preventDefault();
              e.stopPropagation();
              safeSend({ type: 'guardWhitelist', rule: String(rule), command: String(command) });
            },
          },
        })
      );
    }

    actions.appendChild(guardLogButton());

    node.appendChild(actions);
    return { el: node, bodyNode: body, kind: 'md' };
  };
})();
