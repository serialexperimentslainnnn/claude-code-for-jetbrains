(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const G = (D.git = D.git || ({} as GitNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  G.BRANCHES_ACTION = 'branches';

  G.gitOf = function (git: unknown): GitPayload | null {
    const g = git as GitPayload | null;
    return g && typeof g === 'object' && g.available ? g : null;
  };

  G.repoOf = function (g: GitPayload): GitRepo {
    return g.repo && typeof g.repo === 'object' ? g.repo : {};
  };

  G.list = function <T>(value: unknown): T[] {
    return Array.isArray(value) ? (value.filter(Boolean) as T[]) : [];
  };

  G.text = function (value: unknown, fallback: string): string {
    return value == null || value === '' ? fallback : String(value);
  };

  G.textOrNull = function (value: unknown): string | null {
    return value == null || value === '' ? null : String(value);
  };

  G.actionById = function (g: GitPayload, id: string): GitAction | null {
    let found: GitAction | null = null;
    G.list<GitAction>(g.actions).forEach(function (a) {
      if (found === null && G.text(a.id, '') === id) found = a;
    });
    return found;
  };

  G.fileCount = function (n: unknown): string | null {
    if (typeof n !== 'number' || !isFinite(n) || n < 0) return null;
    return Math.round(n) === 1 ? '1 file' : Math.round(n) + ' files';
  };

  G.ageSince = function (atMillis: unknown): string | null {
    if (typeof atMillis !== 'number' || !isFinite(atMillis) || atMillis <= 0) return null;
    return G.ageText(Date.now() - atMillis);
  };

  G.ageText = function (ms: unknown): string | null {
    if (typeof ms !== 'number' || !isFinite(ms) || ms < 0) return null;
    const mins = Math.floor(ms / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    const days = Math.floor(hours / 24);
    if (days < 30) return days + 'd ago';
    const months = Math.floor(days / 30);
    if (months < 12) return months + 'mo ago';
    return Math.floor(days / 365) + 'y ago';
  };

  function buildGitHeadCard(git: unknown): HTMLElement | null {
    const g = G.gitOf(git);
    if (!g) return null;
    const repo = G.repoOf(g);
    if (!repo.present) return viewHead(noRepoCard(g));

    const id = h(
      'div',
      { class: 'git-id' },
      h('span', { class: 'git-repo', text: G.text(repo.root, 'Repository') }),
      branchChip(g, repo),
      h('span', { class: 'git-sha', text: G.text(repo.head, '—') })
    );
    return viewHead(card('Repository', [id], true, 'git-head'));
  }

  function branchChip(g: GitPayload, repo: GitRepo): HTMLElement {
    const name = G.text(repo.branch, 'detached');
    const action = G.actionById(g, G.BRANCHES_ACTION);
    if (!action) return h('span', { class: 'git-branch', text: name });
    return h('button', {
      class: 'git-branch git-branch-pick',
      attrs: {
        type: 'button',
        'data-action': G.BRANCHES_ACTION,
        'aria-label': name + ' — switch branch',
      },
      title: G.text(action.hint, 'Switch, create or compare branches'),
      text: name,
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          send({ type: 'gitAction', id: G.BRANCHES_ACTION });
        },
      },
    });
  }

  function viewHead(cardEl: HTMLElement | null): HTMLElement {
    return h('div', { class: 'git-viewhead' }, viewTabs('overview'), cardEl);
  }

  function viewTabs(current: string): HTMLElement {
    return h(
      'div',
      { class: 'git-viewtabs', attrs: { role: 'group', 'aria-label': 'Git view' } },
      viewTab('Overview', current !== 'chat', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('overview');
      }),
      viewTab('Chat', current === 'chat', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('chat');
      })
    );
  }

  function viewTab(label: string, current: boolean, onPick: () => void): HTMLElement {
    return h('button', {
      class: 'git-viewtab' + (current ? ' active' : ''),
      attrs: { type: 'button', 'aria-current': current ? 'true' : null },
      text: label,
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          onPick();
        },
      },
    });
  }

  const INIT_ACTION: GitAction = { id: 'init', label: 'Initialize repository', group: 'Repository' };

  function noRepoCard(g: GitPayload): HTMLElement | null {
    const actions = G.list<GitAction>(g.actions);
    const buttons = actions.length ? actions.map(G.actionButton) : [G.actionButton(INIT_ACTION)];
    return card(
      'Repository',
      [
        h('div', { class: 'git-empty', text: 'This project is not a Git repository yet.' }),
        h('div', { class: 'git-group-items' }, buttons),
      ],
      true,
      'git-head'
    );
  }

  D.gitViewTabs = viewTabs;
  D.buildGitHeadCard = buildGitHeadCard;
})();
