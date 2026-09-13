(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const G = (D.git = D.git || ({} as GitNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const announced: Record<string, string | null> = Object.create(null);

  function statusOf(action: GitAction): string | null {
    const s = action.status;
    return s == null || s === '' ? null : String(s);
  }

  function buildGitActionsCard(git: unknown): HTMLElement | null {
    const g = G.gitOf(git);
    if (!g || !G.repoOf(g).present) return null;
    const actions = G.list<GitAction>(g.actions);
    if (!actions.length) return null;

    const byGroup: Record<string, GitAction[]> = Object.create(null);
    const order: string[] = [];
    actions.forEach(function (a) {
      const name = G.text(a.group, 'Actions');
      if (!byGroup[name]) {
        byGroup[name] = [];
        order.push(name);
      }
      byGroup[name].push(a);
    });

    const groups = order.map(function (name) {
      return h(
        'div',
        { class: 'git-group' },
        h('div', { class: 'git-group-name', text: name }),
        h('div', { class: 'git-group-items' }, byGroup[name].map(G.actionButton))
      );
    });
    return card('Actions', [h('div', { class: 'git-actions' }, groups)], true, 'git-actions');
  }

  G.actionButton = function (action: GitAction): HTMLElement {
    const id = G.text(action.id, '');
    const label = G.text(action.label, id || 'Action');
    announceStatus(id, label, statusOf(action));

    const children = [h('span', { class: 'git-action-label', text: label })];
    return h(
      'button',
      {
        class: 'btn git-action',
        attrs: {
          type: 'button',
          'data-action': id,
          'data-kind': G.text(action.kind, 'direct'),
        },
        title: action.hint == null ? null : String(action.hint),
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            if (id) send({ type: 'gitAction', id: id });
          },
        },
      },
      children
    );
  };

  function announceStatus(id: string, label: string, status: string | null): void {
    if (!id) return;
    const known = Object.prototype.hasOwnProperty.call(announced, id);
    if (known && announced[id] === status) return;
    announced[id] = status;
    if (status == null) return;
    if (typeof CC.announce === 'function') CC.announce(label + ' — ' + status);
  }

  function factRow(label: string, value: unknown): HTMLElement | null {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'git-fact' },
      h('span', { class: 'git-fact-label', text: label }),
      h('span', { class: 'git-fact-value', text: String(value) })
    );
  }

  function buildGitTopologyCard(git: unknown): HTMLElement | null {
    const g = G.gitOf(git);
    if (!g || !G.repoOf(g).present) return null;
    const t = g.topology;
    if (!t || t.upstream == null) return null;
    const rows = [
      factRow('Tracking', t.upstream),
      factRow('Ahead', t.ahead),
      factRow('Behind', t.behind),
      factRow('Diverged at', t.mergeBase ? String(t.mergeBase).slice(0, 7) : null),
    ].filter(Boolean) as HTMLElement[];
    if (!rows.length) return null;
    rows.push(h('div', { class: 'git-note', text: 'Counted against your last fetch.' }));
    return card('Branch', rows, false, 'git-topology');
  }

  D.buildGitActionsCard = buildGitActionsCard;
  D.buildGitTopologyCard = buildGitTopologyCard;
})();
