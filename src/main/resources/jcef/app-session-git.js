(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  var KNOWN_STATUS = { running: true, completed: true, failed: true };

  var announced = Object.create(null);

  var BRANCHES_ACTION = 'branches';

  function gitOf(git) {
    return git && typeof git === 'object' && git.available ? git : null;
  }

  function repoOf(g) {
    return g.repo && typeof g.repo === 'object' ? g.repo : {};
  }

  function list(value) {
    return Array.isArray(value) ? value.filter(Boolean) : [];
  }

  function text(value, fallback) {
    return value == null || value === '' ? fallback : String(value);
  }

  function statusOf(action) {
    var s = action.status;
    return s == null || s === '' ? null : String(s);
  }

  function actionById(g, id) {
    var found = null;
    list(g.actions).forEach(function (a) {
      if (found === null && text(a.id, '') === id) found = a;
    });
    return found;
  }

  function buildGitHeadCard(git) {
    var g = gitOf(git);
    if (!g) return null;
    var repo = repoOf(g);
    if (!repo.present) return viewHead(noRepoCard(g), git);

    var id = h(
      'div',
      { class: 'git-id' },
      h('span', { class: 'git-repo', text: text(repo.root, 'Repository') }),
      branchChip(g, repo),
      h('span', { class: 'git-sha', text: text(repo.head, '—') })
    );
    return viewHead(card('Repository', [id], true, 'git-head'), git);
  }

  function branchChip(g, repo) {
    var name = text(repo.branch, 'detached');
    var action = actionById(g, BRANCHES_ACTION);
    if (!action) return h('span', { class: 'git-branch', text: name });
    return h('button', {
      class: 'git-branch git-branch-pick',
      attrs: {
        type: 'button',
        'data-action': BRANCHES_ACTION,
        'aria-label': name + ' — switch branch',
      },
      title: text(action.hint, 'Switch, create or compare branches'),
      text: name,
      on: {
        click: function (ev) {
          ev.preventDefault();
          send({ type: 'gitAction', id: BRANCHES_ACTION });
        },
      },
    });
  }

  function viewHead(cardEl, git) {
    var current = typeof D.gitSubView === 'function' ? D.gitSubView() : 'overview';
    return h('div', { class: 'git-viewhead' }, viewTabs(current, git), cardEl);
  }

  function viewTabs(current, git) {
    return h(
      'div',
      { class: 'git-viewtabs', attrs: { role: 'group', 'aria-label': 'Git view' } },
      viewTab('Overview', current === 'overview', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('overview');
      }),
      viewTab(mergeWord(git), current === 'merges', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('merges');
      }),
      viewTab('Pipelines', current === 'pipelines', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('pipelines');
      }),
      viewTab('Chat', current === 'chat', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('chat');
      })
    );
  }

  function forgeOf(git) {
    var g = gitOf(git);
    return (g && g.forge) || {};
  }

  function mergeWord(git) {
    return forgeOf(git).provider === 'gitlab' ? 'Merge requests' : 'Pull requests';
  }

  function forgeNote(git, emptyText) {
    var forge = forgeOf(git);
    if (!forge.configured) {
      return 'No forge token for this remote. Add one in Settings ▸ Claude Code ▸ Git forge.';
    }
    if (!forge.answered) return 'The forge did not answer. Nothing is being shown rather than a guess.';
    return emptyText;
  }

  function viewTab(label, current, onPick) {
    return h('button', {
      class: 'git-viewtab' + (current ? ' active' : ''),
      attrs: { type: 'button', 'aria-current': current ? 'true' : null },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          onPick();
        },
      },
    });
  }

  function noRepoCard(g) {
    var actions = list(g.actions);
    var buttons = actions.length ? actions.map(actionButton) : [actionButton(INIT_ACTION)];
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

  var INIT_ACTION = { id: 'init', label: 'Initialize repository', group: 'Repository' };

  function buildGitActionsCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var actions = list(g.actions);
    if (!actions.length) return null;

    var byGroup = Object.create(null);
    var order = [];
    actions.forEach(function (a) {
      var name = text(a.group, 'Actions');
      if (!byGroup[name]) {
        byGroup[name] = [];
        order.push(name);
      }
      byGroup[name].push(a);
    });

    var groups = order.map(function (name) {
      return h(
        'div',
        { class: 'git-group' },
        h('div', { class: 'git-group-name', text: name }),
        h('div', { class: 'git-group-items' }, byGroup[name].map(actionButton))
      );
    });
    return card('Actions', [h('div', { class: 'git-actions' }, groups)], true, 'git-actions');
  }

  function actionButton(action) {
    var id = text(action.id, '');
    var label = text(action.label, id || 'Action');
    var status = statusOf(action);
    announceStatus(id, label, status);

    var children = [h('span', { class: 'git-action-label', text: label })];
    if (status) {
      children.push(
        h('span', {
          class: 'git-status ' + (KNOWN_STATUS[status] ? status : 'other'),
          text: status,
        })
      );
    }
    return h(
      'button',
      {
        class: 'btn git-action',
        attrs: {
          type: 'button',
          'data-action': id,
          'data-kind': text(action.kind, 'direct'),
        },
        title: action.hint == null ? null : String(action.hint),
        on: {
          click: function (ev) {
            ev.preventDefault();
            if (id) send({ type: 'gitAction', id: id });
          },
        },
      },
      children
    );
  }

  function announceStatus(id, label, status) {
    if (!id) return;
    var known = Object.prototype.hasOwnProperty.call(announced, id);
    if (known && announced[id] === status) return;
    announced[id] = status;
    if (status == null) return;
    if (typeof CC.announce === 'function') CC.announce(label + ' — ' + status);
  }

  var LANE_W = 16;
  var ROW_UNITS = 100;
  var ROW_MID = 50;

  var LANE_COLOURS = 6;

  var WIP_ID = ':working-copy:';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  function buildGitHistoryCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var changes = list(g.changes);
    var commits = list(g.commits).filter(function (c) {
      return text(c.hash, '') !== '';
    });
    if (!changes.length && !commits.length) return null;

    var entries = [];
    if (changes.length) {
      entries.push({ hash: WIP_ID, parents: [headHashOf(g, commits)], changes: changes });
    }
    commits.forEach(function (c) {
      entries.push({ hash: text(c.hash, ''), parents: list(c.parents).map(String), commit: c });
    });

    var model = layoutLanes(entries);
    var byHash = refsByHash(g);
    var actions = commitActionsOf(g);
    var rows = model.rows.map(function (row) {
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

  function layoutLanes(entries) {
    var items = list(entries);
    var known = Object.create(null);
    items.forEach(function (item) {
      var hash = text(item.hash, '');
      if (hash) known[hash] = true;
    });

    var lanes = [];
    var rows = [];

    function firstFree() {
      for (var i = 0; i < lanes.length; i++) {
        if (lanes[i] == null) return i;
      }
      lanes.push(null);
      return lanes.length - 1;
    }

    items.forEach(function (item, index) {
      var hash = text(item.hash, '');
      var before = lanes.slice();
      var up = [];
      for (var k = 0; k < before.length; k++) {
        if (hash && before[k] === hash) up.push(k);
      }
      var lane = up.length ? up[0] : firstFree();
      up.forEach(function (waiting) {
        if (waiting !== lane) lanes[waiting] = null;
      });

      var parents = list(item.parents).map(String);
      var down = [];
      var first = parents.length ? parents[0] : null;
      lanes[lane] = first && known[first] ? first : null;
      if (lanes[lane]) down.push(lane);
      for (var p = 1; p < parents.length; p++) {
        var other = parents[p];
        if (!known[other]) continue;
        var at = lanes.indexOf(other);
        if (at < 0) {
          at = firstFree();
          lanes[at] = other;
        }
        if (down.indexOf(at) < 0) down.push(at);
      }

      var through = [];
      for (var q = 0; q < lanes.length; q++) {
        if (q !== lane && before[q] != null && lanes[q] === before[q]) through.push(q);
      }
      rows.push({
        item: item,
        hash: hash,
        index: index,
        lane: lane,
        up: up,
        down: down,
        through: through,
        parents: parents,
        merge: parents.length > 1,
      });
    });
    return { rows: rows, lanes: Math.max(1, lanes.length) };
  }

  function laneX(l) {
    return l * LANE_W + LANE_W / 2;
  }

  function bend(x1, y1, x2, y2) {
    var mid = (y1 + y2) / 2;
    return 'M' + x1 + ' ' + y1 + 'C' + x1 + ' ' + mid + ' ' + x2 + ' ' + mid + ' ' + x2 + ' ' + y2;
  }

  function edge(svg, d, lane) {
    var path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('class', 'git-edge');
    path.setAttribute('d', d);
    path.setAttribute('data-lane', String(lane % LANE_COLOURS));
    svg.appendChild(path);
  }

  function gutter(row, lanes) {
    var width = lanes * LANE_W;
    var x = laneX(row.lane);
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'git-graph');
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + ROW_UNITS);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('aria-hidden', 'true');
    row.through.forEach(function (l) {
      edge(svg, 'M' + laneX(l) + ' 0V' + ROW_UNITS, l);
    });
    row.up.forEach(function (l) {
      if (l === row.lane) {
        edge(svg, 'M' + x + ' 0V' + ROW_MID, l);
      } else {
        edge(svg, bend(laneX(l), 0, x, ROW_MID), l);
      }
    });
    row.down.forEach(function (l) {
      if (l === row.lane) {
        edge(svg, 'M' + x + ' ' + ROW_MID + 'V' + ROW_UNITS, l);
      } else {
        edge(svg, bend(x, ROW_MID, laneX(l), ROW_UNITS), l);
      }
    });
    var dot = h('span', {
      class: 'git-dot-node' + (row.merge ? ' merge' : ''),
      style: { left: x + 'px' },
      attrs: { 'data-lane': String(row.lane % LANE_COLOURS) },
    });
    return h('span', { class: 'git-gutter', style: { width: width + 'px' } }, svg, dot);
  }

  function refsByHash(g) {
    var byHash = Object.create(null);
    list(g.refs).forEach(function (r) {
      var hash = text(r.hash, '');
      if (!hash) return;
      if (!byHash[hash]) byHash[hash] = [];
      byHash[hash].push(r);
    });
    return byHash;
  }

  function headHashOf(g, commits) {
    var found = '';
    list(g.refs).forEach(function (r) {
      if (!found && r.current === true) found = text(r.hash, '');
    });
    if (found) return found;
    var head = text(repoOf(g).head, '');
    if (!head) return '';
    commits.forEach(function (c) {
      var hash = text(c.hash, '');
      if (!found && hash.indexOf(head) === 0) found = hash;
    });
    return found;
  }

  function commitActionsOf(g) {
    return list(g.commitActions).filter(function (a) {
      return text(a.id, '') !== '';
    });
  }

  function wipRow(row, lanes) {
    var changes = row.item.changes;
    var files = changes.map(function (path) {
      return h('li', { class: 'git-file', text: String(path) });
    });
    return h(
      'li',
      { class: 'git-node git-wip' },
      gutter(row, lanes),
      h(
        'div',
        { class: 'git-body' },
        h('div', { class: 'git-line' }, h('span', { class: 'git-subject', text: 'Uncommitted changes' })),
        h('div', { class: 'git-meta', text: fileCount(changes.length) }),
        h('ul', { class: 'git-files' }, files)
      )
    );
  }

  function commitRow(g, row, lanes, byHash, actions) {
    var c = row.item.commit;
    var hash = row.hash;
    var short = text(c.short, hash.slice(0, 7));
    var refs = byHash[hash] || [];
    var meta = [text(c.author, null), ageSince(c.authoredAtMillis), fileCount(c.files)].filter(Boolean);
    return h(
      'li',
      { class: 'git-node git-commit', attrs: { 'data-hash': hash } },
      gutter(row, lanes),
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
          h('span', { class: 'git-subject', text: text(c.subject, '(no message)') })
        ),
        meta.length ? h('div', { class: 'git-meta', text: meta.join(' · ') }) : null,
        commitActionBar(actions, hash)
      )
    );
  }

  function refTag(g, r) {
    var name = text(r.name, '');
    var kind = text(r.kind, 'local');
    var current = r.current === true;
    var word = null;
    if (current) {
      word = 'HEAD';
    } else if (kind === 'remote') {
      word = 'remote';
    }
    var children = [h('span', { class: 'git-ref-name', text: name })];
    if (word) children.push(h('span', { class: 'git-ref-kind', text: word }));

    var action = actionById(g, BRANCHES_ACTION);
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
        title: text(action.hint, 'Switch, create or compare branches'),
        on: {
          click: function (ev) {
            ev.preventDefault();
            send({ type: 'gitAction', id: BRANCHES_ACTION });
          },
        },
      },
      children
    );
  }

  function commitActionBar(actions, hash) {
    if (!actions.length || !hash) return null;
    return h(
      'div',
      { class: 'git-commit-actions' },
      actions.map(function (a) {
        return commitActionButton(a, hash);
      })
    );
  }

  function commitActionButton(action, hash) {
    var id = text(action.id, '');
    return h('button', {
      class: 'btn ghost git-commit-action',
      text: text(action.label, id),
      attrs: { type: 'button', 'data-action': id },
      title: action.hint == null ? null : String(action.hint),
      on: {
        click: function (ev) {
          ev.preventDefault();
          send({ type: 'gitAction', id: id, hash: hash });
        },
      },
    });
  }

  function fileCount(n) {
    if (typeof n !== 'number' || !isFinite(n) || n < 0) return null;
    return Math.round(n) === 1 ? '1 file' : Math.round(n) + ' files';
  }

  function ageSince(atMillis) {
    if (typeof atMillis !== 'number' || !isFinite(atMillis) || atMillis <= 0) return null;
    return ageText(Date.now() - atMillis);
  }

  function ageText(ms) {
    if (typeof ms !== 'number' || !isFinite(ms) || ms < 0) return null;
    var mins = Math.floor(ms / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    var hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    var days = Math.floor(hours / 24);
    if (days < 30) return days + 'd ago';
    var months = Math.floor(days / 30);
    if (months < 12) return months + 'mo ago';
    return Math.floor(days / 365) + 'y ago';
  }

  function historyNote(g, model, total) {
    var placed = Object.create(null);
    model.rows.forEach(function (row) {
      if (row.hash) placed[row.hash] = true;
    });
    var lines = ['Showing the newest ' + total + ' commits across every branch.'];
    var cutOff = model.rows.some(function (row) {
      return row.parents.some(function (hash) {
        return !placed[hash];
      });
    });
    if (cutOff) lines.push('Lines continuing past the oldest commit shown have no edge drawn.');
    var away = list(g.refs).filter(function (r) {
      var hash = text(r.hash, '');
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

  function linkTo(label, url, extraClass) {
    return h('button', {
      class: 'git-link' + (extraClass ? ' ' + extraClass : ''),
      attrs: { type: 'button' },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          send({ type: 'open', url: url });
        },
      },
    });
  }

  function factRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'git-fact' },
      h('span', { class: 'git-fact-label', text: label }),
      h('span', { class: 'git-fact-value', text: String(value) })
    );
  }

  function buildGitTopologyCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var t = g.topology;
    if (!t || t.upstream == null) return null;
    var rows = [
      factRow('Tracking', t.upstream),
      factRow('Ahead', t.ahead),
      factRow('Behind', t.behind),
      factRow('Diverged at', t.mergeBase ? String(t.mergeBase).slice(0, 7) : null),
    ].filter(Boolean);
    if (!rows.length) return null;
    rows.push(h('div', { class: 'git-note', text: 'Counted against your last fetch.' }));
    return card('Branch', rows, false, 'git-topology');
  }

  var mergeScope = 'all';

  function scopeTab(label, scope) {
    var active = mergeScope === scope;
    return h('button', {
      class: 'git-scope' + (active ? ' active' : ''),
      attrs: { type: 'button', 'aria-pressed': active ? 'true' : 'false' },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (mergeScope === scope) return;
          mergeScope = scope;
          if (typeof D.repaint === 'function') D.repaint();
        },
      },
    });
  }

  function scopeStrip(current) {
    return h(
      'div',
      { class: 'git-scopes', attrs: { role: 'group', 'aria-label': 'Which branches to show' } },
      scopeTab('All branches', 'all'),
      scopeTab(current ? 'This branch' : 'Current branch', 'branch')
    );
  }

  function buildGitMergesCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;

    var current = text(repoOf(g).branch, '');
    var pulls = list(g.pullRequests);
    var shown =
      mergeScope === 'branch' && current
        ? pulls.filter(function (p) {
            return text(p.sourceBranch, '') === current;
          })
        : pulls;

    var body = [scopeStrip(current)];
    if (shown.length) {
      shown.forEach(function (pull) {
        body.push(pullRow(pull, current));
      });
    } else if (pulls.length) {
      body.push(h('div', { class: 'git-note', text: 'Nothing open for ' + current + '.' }));
    } else {
      body.push(h('div', { class: 'git-note', text: forgeNote(git, 'Nothing open in this project.') }));
    }

    return card(mergeWord(git), body, false, 'git-merges');
  }

  function buildGitPipelinesCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;

    var runs = list(g.runs);
    var body = runs.length
      ? runs.map(runRow)
      : [h('div', { class: 'git-note', text: forgeNote(git, 'No pipeline has run for this branch.') })];

    return card('Pipelines', body, false, 'git-pipelines');
  }

  function runRow(run) {
    var status = text(run.status, 'running');
    return h(
      'div',
      { class: 'git-forge-row' },
      h('span', { class: 'git-dot ' + status, attrs: { title: status } }),
      h('span', { class: 'git-forge-label', text: text(run.name, 'Last run') }),
      linkTo('Open', text(run.url, ''), 'git-forge-open')
    );
  }

  function pullRow(pull, current) {
    var number = pull.number == null ? '' : '#' + pull.number;
    var branch = text(pull.sourceBranch, '');
    var mine = !!branch && branch === current;
    return h(
      'div',
      { class: 'git-forge-row' + (mine ? ' here' : '') },
      h('span', { class: 'git-forge-num', text: number }),
      h('span', { class: 'git-forge-label', text: text(pull.title, '(no title)') }),
      branch ? h('span', { class: 'git-forge-branch', attrs: { title: branch }, text: branch }) : null,
      pull.draft ? h('span', { class: 'git-forge-draft', text: 'draft' }) : null,
      linkTo('Open', text(pull.url, ''), 'git-forge-open')
    );
  }

  D.gitViewTabs = viewTabs;
  D.gitLanes = layoutLanes;
  D.buildGitHeadCard = buildGitHeadCard;
  D.buildGitActionsCard = buildGitActionsCard;
  D.buildGitHistoryCard = buildGitHistoryCard;
  D.buildGitTopologyCard = buildGitTopologyCard;
  D.buildGitMergesCard = buildGitMergesCard;
  D.buildGitPipelinesCard = buildGitPipelinesCard;
})();
