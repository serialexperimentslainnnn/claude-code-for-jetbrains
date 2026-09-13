(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const G = (D.git = D.git || ({} as GitNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const WIP_ID = ':working-copy:';

  function buildGitHistoryCard(git: unknown): HTMLElement | null {
    const g = G.gitOf(git);
    if (!g || !G.repoOf(g).present) return null;
    const changes = G.list<string>(g.changes);
    const commits = G.list<GitCommit>(g.commits).filter(function (c) {
      return G.text(c.hash, '') !== '';
    });
    if (!changes.length && !commits.length) return null;

    const entries: LaneEntry[] = [];
    if (changes.length) {
      entries.push({ hash: WIP_ID, parents: [headHashOf(g, commits)], changes: changes });
    }
    commits.forEach(function (c) {
      entries.push({ hash: G.text(c.hash, ''), parents: G.list<unknown>(c.parents).map(String), commit: c });
    });

    const model = D.gitLanes(entries);
    const byHash = refsByHash(g);
    const actions = commitActionsOf(g);
    const rows = model.rows.map(function (row) {
      if (row.item.changes) return wipRow(row, model.lanes);
      return commitRow(g, row, model.lanes, byHash, actions);
    });
    return card(
      'History',
      [
        h('ul', { class: 'git-rail', attrs: { 'aria-label': 'Commits, newest first' } }, rows),
        historyNote(g, model, commits.length),
      ],
      true,
      'git-history'
    );
  }

  function refsByHash(g: GitPayload): Record<string, GitRef[]> {
    const byHash: Record<string, GitRef[]> = Object.create(null);
    G.list<GitRef>(g.refs).forEach(function (r) {
      const hash = G.text(r.hash, '');
      if (!hash) return;
      if (!byHash[hash]) byHash[hash] = [];
      byHash[hash].push(r);
    });
    return byHash;
  }

  function headHashOf(g: GitPayload, commits: GitCommit[]): string {
    let found = '';
    G.list<GitRef>(g.refs).forEach(function (r) {
      if (!found && r.current === true) found = G.text(r.hash, '');
    });
    if (found) return found;
    const head = G.text(G.repoOf(g).head, '');
    if (!head) return '';
    commits.forEach(function (c) {
      const hash = G.text(c.hash, '');
      if (!found && hash.indexOf(head) === 0) found = hash;
    });
    return found;
  }

  function commitActionsOf(g: GitPayload): GitAction[] {
    return G.list<GitAction>(g.commitActions).filter(function (a) {
      return G.text(a.id, '') !== '';
    });
  }

  function wipRow(row: LaneRow, lanes: number): HTMLElement {
    const changes = row.item.changes || [];
    const files = changes.map(function (path) {
      return h('li', { class: 'git-file', text: String(path) });
    });
    return h(
      'li',
      { class: 'git-node git-wip' },
      G.gutter(row, lanes),
      h(
        'div',
        { class: 'git-body' },
        h('div', { class: 'git-line' }, h('span', { class: 'git-subject', text: 'Uncommitted changes' })),
        h('div', { class: 'git-meta', text: G.fileCount(changes.length) }),
        h('ul', { class: 'git-files' }, files)
      )
    );
  }

  function commitRow(
    g: GitPayload,
    row: LaneRow,
    lanes: number,
    byHash: Record<string, GitRef[]>,
    actions: GitAction[]
  ): HTMLElement {
    const c = row.item.commit || {};
    const hash = row.hash;
    const short = G.text(c.short, hash.slice(0, 7));
    const refs = byHash[hash] || [];
    const meta = [G.textOrNull(c.author), G.ageSince(c.authoredAtMillis), G.fileCount(c.files)].filter(
      Boolean
    );
    return h(
      'li',
      { class: 'git-node git-commit', attrs: { 'data-hash': hash } },
      G.gutter(row, lanes),
      h(
        'div',
        { class: 'git-body' },
        h(
          'div',
          { class: 'git-line' },
          short ? h('span', { class: 'git-hash', text: short }) : null,
          row.merge ? h('span', { class: 'git-merge', text: 'merge' }) : null,
          refs.map(function (r) {
            return refTag(g, r);
          }),
          h('span', { class: 'git-subject', text: G.text(c.subject, '(no message)') })
        ),
        meta.length ? h('div', { class: 'git-meta', text: meta.join(' · ') }) : null,
        commitActionBar(actions, hash)
      )
    );
  }

  function refTag(g: GitPayload, r: GitRef): HTMLElement {
    const name = G.text(r.name, '');
    const kind = G.text(r.kind, 'local');
    const current = r.current === true;
    let word: string | null = null;
    if (current) {
      word = 'HEAD';
    } else if (kind === 'remote') {
      word = 'remote';
    }
    const children = [h('span', { class: 'git-ref-name', text: name })];
    if (word) children.push(h('span', { class: 'git-ref-kind', text: word }));

    const action = G.actionById(g, G.BRANCHES_ACTION);
    if (!action) {
      return h(
        'span',
        {
          class: 'git-ref' + (kind === 'remote' ? ' remote' : '') + (current ? ' current' : ''),
          attrs: { 'aria-current': current ? 'true' : null },
        },
        children
      );
    }
    return h(
      'button',
      {
        class: 'git-ref' + (kind === 'remote' ? ' remote' : '') + (current ? ' current' : ''),
        attrs: {
          type: 'button',
          'data-ref': name,
          'aria-current': current ? 'true' : null,
          'aria-label': (word ? name + ' ' + word : name) + ' — switch branch',
        },
        title: G.text(action.hint, 'Switch, create or compare branches'),
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            send({ type: 'gitAction', id: G.BRANCHES_ACTION });
          },
        },
      },
      children
    );
  }

  function commitActionBar(actions: GitAction[], hash: string): HTMLElement | null {
    if (!actions.length || !hash) return null;
    return h(
      'div',
      { class: 'git-commit-actions' },
      actions.map(function (a) {
        return commitActionButton(a, hash);
      })
    );
  }

  function commitActionButton(action: GitAction, hash: string): HTMLElement {
    const id = G.text(action.id, '');
    return h('button', {
      class: 'btn ghost git-commit-action',
      text: G.text(action.label, id),
      attrs: { type: 'button', 'data-action': id },
      title: action.hint == null ? null : String(action.hint),
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          send({ type: 'gitAction', id: id, hash: hash });
        },
      },
    });
  }

  function historyNote(g: GitPayload, model: LaneModel, total: number): HTMLElement {
    const placed: Record<string, boolean> = Object.create(null);
    model.rows.forEach(function (row) {
      if (row.hash) placed[row.hash] = true;
    });
    const lines = ['Showing the newest ' + total + ' commits across every branch.'];
    const cutOff = model.rows.some(function (row) {
      return row.parents.some(function (hash) {
        return !placed[hash];
      });
    });
    if (cutOff) lines.push('Lines continuing past the oldest commit shown have no edge drawn.');
    const away = G.list<GitRef>(g.refs).filter(function (r) {
      const hash = G.text(r.hash, '');
      return hash !== '' && !placed[hash];
    }).length;
    if (away > 0) {
      lines.push(away + (away === 1 ? ' branch points' : ' branches point') + ' outside this window.');
      if (model.lanes === 1) {
        lines.push('Every commit here is on one line; no branch point falls inside this window.');
      }
    }
    return h('div', { class: 'git-note', text: lines.join(' ') });
  }

  D.buildGitHistoryCard = buildGitHistoryCard;
})();
