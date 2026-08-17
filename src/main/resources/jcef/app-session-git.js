/* app-session-git.js — the Git view.
 *
 * One subject: what the repository is, what can be done to it, and what has happened in it — the three cards
 * the `git` view of the dashboard is made of. Pure builders over `payload.git`, like every other card in this
 * family: given the data they return a card, given nothing they return null and the card does not appear.
 *
 * The view is headed by two destinations, `Overview` and `Chat`: the repository as a surface, and the
 * conversation that acts on it. The Git conversation is a chat of its own (see `TabSessionCommands.gitChat`),
 * so putting its door here is what stops it having to be found among the ordinary chat pills.
 *
 * The history is ONE vertical rail, and it stays one on purpose: it is the list you read down, newest first,
 * to see what happened. The GRAPH is a separate card (`buildGitBranchMapCard`), drawn horizontally with time
 * on the x axis and one lane per line of development.
 *
 * **That graph is drawn from real parents and real refs, or it is not drawn at all.** The rule this file was
 * written under has not been relaxed, it has been satisfied: the payload now carries each commit's parent
 * hashes and every branch with the commit it points at (`JcefGitData`), so a fork is a fork the repository
 * has and a lane is named by a ref that exists. With either half missing the card returns null. An invented
 * topology in a Git view is worse than none, because nothing on screen tells a drawn branch from a real one —
 * and the same goes for a truncated one presented as complete, which is why what is cut is said out loud.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  /** The states the host names (see JcefStatus). Anything else is still PAINTED, just not colour-coded. */
  var KNOWN_STATUS = { running: true, completed: true, failed: true };

  /** Action id → the last status announced for it, so a rebuild with nothing new stays silent. */
  var announced = Object.create(null);

  /**
   * The catalogue id that opens the platform's own branch switcher.
   *
   * ONE id with three doors — the action bar, the branch chip in this header, and every ref on the branch map
   * — because the button and what it does may not drift apart, which is the whole reason `GitActionCatalog`
   * exists. The page never invents a message for "switch to this branch": a branch name coming off this page
   * is a free-form value exactly like a commit hash, and the only thing that could act on one is a checkout,
   * which this plugin does not perform. The platform's popup offers the real list instead, with its own
   * enablement and its own undo.
   */
  var BRANCHES_ACTION = 'branches';

  /**
   * The commit the user picked on the branch map, remembered across rebuilds.
   *
   * Module-level and not a DOM read, for the reason the dashboard learned twice: the panel is rebuilt on every
   * host push, several times a turn, so anything held only in the elements is thrown away by a repaint the
   * user did not cause. Cleared when that commit falls out of the drawn window, since a selection pointing at
   * nothing would leave the detail row describing a commit that is no longer on screen.
   */
  var selectedHash = null;

  /**
   * Where the map was scrolled to, or null before it has ever been drawn.
   *
   * Same reason as the selection: a rebuilt container is born at offset 0. Null is not 0 — it means "nobody
   * has looked at this yet", and the first paint answers it by going to the far right, where the newest
   * commits are. Restoring 0 there would open every session on the oldest commit in the window.
   */
  var mapScrollLeft = null;

  // ---------------------------------------------------------------------------
  // Payload readers
  // ---------------------------------------------------------------------------
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

  /**
   * The word the host sent, or null.
   *
   * Never derived: `running`/`completed`/`failed` are decided host-side and this page paints the word it is
   * given. Deriving one here is how the tab bar came to say `done` where the dashboard said `completed` for
   * the same task, each with its own colour.
   */
  function statusOf(action) {
    var s = action.status;
    return s == null || s === '' ? null : String(s);
  }

  /**
   * One action out of the payload's bar, by id, or null when the host is not offering it right now.
   *
   * Null is the answer the callers need rather than a fabricated entry: a control that fires an id the host
   * did not offer is the one control on the page that cannot work, and it fails silently — the message is
   * parsed, the catalogue lookup misses, and a warning goes to `idea.log` where nobody is looking.
   */
  function actionById(g, id) {
    var found = null;
    list(g.actions).forEach(function (a) {
      if (found === null && text(a.id, '') === id) found = a;
    });
    return found;
  }

  // ---------------------------------------------------------------------------
  // Header — the two destinations, then the repository
  // ---------------------------------------------------------------------------
  /**
   * The tab strip, then repo name, branch and short HEAD.
   *
   * With no repository it degrades to a single card offering to create one: a branch row, an action bar
   * and an empty history under it would all be answering questions that cannot be asked yet. The strip stays
   * even then — the Git chat is exactly where a project with no repository has something to ask.
   */
  function buildGitHeadCard(git) {
    var g = gitOf(git);
    if (!g) return null;
    var repo = repoOf(g);
    if (!repo.present) return viewHead(noRepoCard(g));

    var id = h(
      'div',
      { class: 'git-id' },
      h('span', { class: 'git-repo', text: text(repo.root, 'Repository') }),
      branchChip(g, repo),
      h('span', { class: 'git-sha', text: text(repo.head, '—') })
    );
    return viewHead(card('Repository', [id], true, 'git-head'), buildGitBranchMapCard(g));
  }

  /**
   * The branch you are on — a REAL control, not a label.
   *
   * It was a `<span>`: the one fact everybody looks for first, sitting next to no way of leaving it, while
   * the button that changes it was four cards down in a group headed "IDE actions" — a name about who runs
   * the thing rather than about what it does, which is not where anybody scans for "switch branch". Pressing
   * the branch opens the platform's branch list; it fires [BRANCHES_ACTION], the same entry the action bar
   * and the map fire, so all three cannot come to mean different things.
   *
   * Falls back to plain text when the host is not offering that entry — with no repository there is nothing
   * to switch to, and a button that fires an id the catalogue lookup will miss is a control that does nothing
   * and says nothing.
   *
   * The accessible name CONTAINS the visible one (2.5.3 Label in Name), so speaking the words on screen
   * activates it; the extra words exist because "main, button" tells a screen-reader user nothing about what
   * pressing it would do.
   */
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

  /**
   * The strip, whatever heads the Overview, and the branch map, as ONE full-width item of the dashboard's grid.
   *
   * The builders return one node each and the panel appends them side by side, so the strip has to travel
   * with the first card; loose, it would be laid out as a 260px grid column beside the repository rather than
   * as a header over the view. [mapEl] rides along for the same reason and because it belongs there: "which
   * branch am I on" and "where does that branch sit" are one question, and the map is the picture of it.
   */
  function viewHead(cardEl, mapEl) {
    return h('div', { class: 'git-viewhead' }, viewTabs('overview'), cardEl, mapEl || null);
  }

  /**
   * Overview and Chat: the repository as a surface, and the conversation that acts on it.
   *
   * Both live in THIS view now. The Git chat used to be a tab of its own in the row with the user's own
   * conversations, and this strip could do no more than send you there; it is an embedded pane
   * (`app-session-gitchat.js`) whose visibility the panel owns (`CC.dash.setGitSubView`).
   *
   * Deliberately NOT a `role="tablist"`, even so. The two destinations are drawn by different modules into
   * sibling containers, neither of which is the other's `tabpanel`, and a tablist would promise a
   * relationship (`aria-controls` onto one owned region, arrow-key roving) the page does not implement. It is
   * a group of two buttons, and `aria-current` says which one you are on.
   *
   * [current] is the destination being drawn, so each pane heads itself with the same strip and only one of
   * them can be lit.
   */
  function viewTabs(current) {
    return h(
      'div',
      { class: 'git-viewtabs', attrs: { role: 'group', 'aria-label': 'Git view' } },
      viewTab('Overview', current !== 'chat', function () {
        // A real, focusable button even when it is "you are here": a two-item switcher whose current item is
        // unreachable cannot be tabbed back to, and `aria-current` rather than the highlight alone is what
        // carries which one that is (1.4.1).
        if (typeof D.setGitSubView === 'function') D.setGitSubView('overview');
      }),
      viewTab('Chat', current === 'chat', function () {
        if (typeof D.setGitSubView === 'function') D.setGitSubView('chat');
      })
    );
  }

  /** One destination. `current` drives the highlight AND `aria-current`, so it cannot be painted only. */
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

  /** No repository yet: say so, and offer the one action that changes it. */
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

  /**
   * The fallback used only when the host sent no action at all for a project with no repository.
   *
   * The id is `init` because that is the id the host's catalogue knows (`GitActionCatalog`); any other spelling
   * makes this button the one control on the page that cannot work, and only in the state nothing else covers.
   */
  var INIT_ACTION = { id: 'init', label: 'Initialize repository', group: 'Repository' };

  // ---------------------------------------------------------------------------
  // Actions — grouped exactly as the payload groups them
  // ---------------------------------------------------------------------------
  /**
   * One bar of buttons per `group`, in the order the groups first appear.
   *
   * The order is the payload's, not an alphabetical or hardcoded one: the host decides which group leads, and
   * a list re-sorted here would drift from it the moment a group is added.
   */
  function buildGitActionsCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var actions = list(g.actions);
    if (!actions.length) return null;

    // `Object.create(null)` rather than `{}`: a group called `constructor` or `__proto__` would otherwise
    // resolve to something off the prototype and silently swallow its buttons.
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

  /** One action: a real button, its optional status chip, and the one message this view sends. */
  function actionButton(action) {
    var id = text(action.id, '');
    var label = text(action.label, id || 'Action');
    var status = statusOf(action);
    announceStatus(id, label, status);

    var children = [h('span', { class: 'git-action-label', text: label })];
    if (status) {
      // The WORD, not only the colour (1.4.1): a chip that says nothing carries its meaning in hue alone,
      // which is unreadable under forced colours and invisible to a colour-blind user.
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

  /**
   * Says a finished or started action out loud (4.1.3 Status Messages).
   *
   * The chip changes under the pointer without the focus moving, so a screen-reader user gets no signal
   * otherwise. Only on CHANGE, and only into a state worth reporting: the dashboard rebuilds several times a
   * turn, and a region re-announcing the same three words on every push is one the user switches off.
   */
  function announceStatus(id, label, status) {
    if (!id) return;
    var known = Object.prototype.hasOwnProperty.call(announced, id);
    if (known && announced[id] === status) return;
    announced[id] = status;
    if (status == null) return; // back to idle, or never started: not news
    if (typeof CC.announce === 'function') CC.announce(label + ' — ' + status);
  }

  // ---------------------------------------------------------------------------
  // History — the WIP node and one vertical rail of commits
  // ---------------------------------------------------------------------------
  /**
   * Working copy first, then the commits, all on ONE rail.
   *
   * Uncommitted work is the top node because that is where it sits in time, and it only appears when there IS
   * some: an empty "Uncommitted changes" node reads as a state, and the state it reads as is the wrong one.
   */
  function buildGitHistoryCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var changes = list(g.changes);
    var commits = list(g.commits);
    if (!changes.length && !commits.length) return null;

    var perCommit = commitActionsOf(g);
    var nodes = [];
    if (changes.length) nodes.push(wipNode(changes));
    commits.forEach(function (c) {
      nodes.push(commitNode(c, perCommit));
    });
    return card('History', [h('div', { class: 'git-rail' }, nodes)], true, 'git-history');
  }

  /**
   * What every commit row can be asked to do — the host's catalogue, not a list written here.
   *
   * Same rule as `actions`: the page paints what it is given and sends an id back, so the label and the thing
   * it does can never drift apart. An entry with no id is dropped rather than drawn, because a button whose
   * press sends nothing is indistinguishable from a broken one.
   */
  function commitActionsOf(g) {
    return list(g.commitActions).filter(function (a) {
      return text(a.id, '') !== '';
    });
  }

  /** The working copy: what would go into the next commit. */
  function wipNode(changes) {
    var files = changes.map(function (path) {
      return h('li', { class: 'git-file', text: String(path) });
    });
    return h(
      'div',
      { class: 'git-node git-wip' },
      h('span', { class: 'git-dot' }),
      h(
        'div',
        { class: 'git-body' },
        h('div', { class: 'git-line' }, h('span', { class: 'git-subject', text: 'Uncommitted changes' })),
        h('div', { class: 'git-meta', text: fileCount(changes.length) }),
        h('ul', { class: 'git-files' }, files)
      )
    );
  }

  /** One commit: what it is, what it says, who wrote it when, and what can be done to it. */
  function commitNode(c, actions) {
    var hash = text(c.short, text(c.hash, '').slice(0, 7));
    var meta = [text(c.author, null), ageText(c.ageMillis), fileCount(c.files)].filter(Boolean);
    return h(
      'div',
      { class: 'git-node git-commit' },
      h('span', { class: 'git-dot' }),
      h(
        'div',
        { class: 'git-body' },
        h(
          'div',
          { class: 'git-line' },
          hash ? h('span', { class: 'git-hash', text: hash }) : null,
          h('span', { class: 'git-subject', text: text(c.subject, '(no message)') })
        ),
        meta.length ? h('div', { class: 'git-meta', text: meta.join(' · ') }) : null,
        // The FULL hash, never the seven characters on screen: an abbreviation is unique only until it is
        // not, and the host acts on what it is sent. `short` is the fallback merely so a payload that omits
        // the long form still produces working buttons rather than silent ones.
        commitActionBar(actions, text(c.hash, hash))
      )
    );
  }

  /**
   * The buttons for one commit, or null when there are none to draw.
   *
   * Null rather than an empty row: an empty container still takes its gap in the column and reads as a set of
   * controls that failed to load, which is the one message it must not send about a revert button.
   */
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

  /**
   * One per-commit action: a real button, and the SAME message the action bar sends.
   *
   * `gitAction` with a `hash`, not a second `gitCommitAction` type: the two bars are one catalogue with one
   * executor host-side, and a message type the host has no parser for is a button that silently does nothing.
   * The hash is the row's; an entry that does not act on a commit simply omits it.
   *
   * Quiet until the row is hovered or something in it takes focus (git.css) — three controls on every row of a
   * long history compete with the subjects, which are what the list is read for. Quiet is `opacity`, never
   * `display: none` or `visibility: hidden`: those take the button out of the tab order, and a control that
   * exists only under a pointer is a WCAG 2.1.1 failure, not a design choice.
   */
  function commitActionButton(action, hash) {
    var id = text(action.id, '');
    return h('button', {
      class: 'btn ghost git-commit-action',
      // The accessible name is the visible label, so the two cannot disagree (2.5.3 Label in Name).
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

  /** "1 file" / "7 files", or null when the count is not a number — an unknown count is not zero. */
  function fileCount(n) {
    if (typeof n !== 'number' || !isFinite(n) || n < 0) return null;
    return Math.round(n) === 1 ? '1 file' : Math.round(n) + ' files';
  }

  /** How long ago, in the coarsest unit that still says something: a timestamp makes the reader do sums. */
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

  // ---------------------------------------------------------------------------
  // The branch map — real parents, real refs, drawn horizontally
  // ---------------------------------------------------------------------------

  /**
   * How much is drawn, and why each bound exists.
   *
   * The window is the host's list, which is the last `GitHistoryService.DEFAULT_COMMIT_LIMIT` commits; the cap
   * here is a second, independent ceiling so that raising the host's limit for the history rail cannot silently
   * turn this into a canvas thousands of columns wide. Whatever these bounds cut is SAID (see [mapNote]) — a
   * map that truncates in silence reads as the whole picture, which is the same defect as an invented one.
   */
  var MAX_MAP_COMMITS = 24;
  var MAX_MAP_REFS = 12;

  /** Geometry, in CSS px. Computed from these, never measured — the same discipline as CC.diagram. */
  var COL_W = 44; // one commit of history
  var LANE_H = 30; // one line of development
  var MAP_PAD = 18;
  var DOT = 12; // what is painted
  var HIT = 24; // what is pressable — WCAG 2.2 SC 2.5.8 floor, and both gaps above clear it

  function cxOf(col, total) {
    // Newest on the RIGHT: time reads left to right, and the newest commit is where the eye starts because it
    // is the one the scroller opens on.
    return MAP_PAD + DOT / 2 + (total - 1 - col) * COL_W;
  }

  function cyOf(lane) {
    return MAP_PAD + DOT / 2 + lane * LANE_H;
  }

  /**
   * Assigns every commit a column (time) and a lane (line of development) in one pass, newest to oldest.
   *
   * This is `git log --graph`'s own rule and nothing cleverer: a lane is a slot waiting for a particular
   * commit. A commit takes the lane that was waiting for it, or the first free one if nothing was; its FIRST
   * parent then continues that lane, and every other parent — a merge — books a lane of its own. Keeping the
   * first parent in place is what makes a mainline read as a straight line instead of wandering into whichever
   * slot happened to be free.
   *
   * Nothing here infers a fork from ordering: an edge exists only where the payload gave a parent hash.
   */
  function layoutCommits(commits) {
    var lanes = [];
    var placed = Object.create(null);
    var order = [];

    function firstFree() {
      for (var i = 0; i < lanes.length; i++) {
        if (lanes[i] == null) return i;
      }
      lanes.push(null);
      return lanes.length - 1;
    }

    commits.forEach(function (c, col) {
      var hash = text(c.hash, '');
      var lane = lanes.indexOf(hash);
      if (lane < 0) lane = firstFree();
      // Two lanes can be waiting for the SAME commit — that is what a branch merging back into the one it came
      // from looks like from this end. The commit lands on the first of them and the others are released; left
      // reserved for a hash already drawn, they would push every later line one lane further down for good.
      for (var q = 0; q < lanes.length; q++) {
        if (q !== lane && lanes[q] === hash) lanes[q] = null;
      }
      var parents = list(c.parents).map(String);
      var node = { commit: c, hash: hash, col: col, lane: lane, parents: parents };
      placed[hash] = node;
      order.push(node);
      lanes[lane] = parents.length ? parents[0] : null;
      for (var k = 1; k < parents.length; k++) {
        if (lanes.indexOf(parents[k]) < 0) lanes[firstFree()] = parents[k];
      }
    });
    return { placed: placed, order: order, lanes: Math.max(1, lanes.length) };
  }

  /** Commit hash → the refs pointing at it. A ref with no hash is dropped: it cannot be anchored to anything. */
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

  /**
   * The branch map, or null when there is nothing real to draw.
   *
   * **Null in three cases, and none of them is an empty card.** No repository; fewer than two commits, so
   * there is no relationship to show; or not one commit carrying a parent, which is what a payload from a host
   * that never read the topology looks like. In that last case a graph would be a row of unconnected dots
   * presented as a history, and this file's founding rule is that a drawing nobody can tell apart from a real
   * one must not be made.
   */
  function buildGitBranchMapCard(g) {
    if (!g || !repoOf(g).present) return null;
    var all = list(g.commits).filter(function (c) {
      return text(c.hash, '') !== '';
    });
    var commits = all.slice(0, MAX_MAP_COMMITS);
    if (commits.length < 2) return null;
    var hasTopology = commits.some(function (c) {
      return list(c.parents).length > 0;
    });
    if (!hasTopology) return null;

    var model = layoutCommits(commits);
    var byHash = refsByHash(g);
    var ctx = {
      g: g,
      model: model,
      byHash: byHash,
      total: commits.length,
      commitActions: commitActionsOf(g),
    };
    // A selection kept from a previous push may name a commit that has since scrolled out of the window; the
    // detail row would then describe something that is not on screen.
    if (selectedHash && !model.placed[selectedHash]) selectedHash = null;

    var detail = h('div', { class: 'git-map-detail' });
    var nodes = [];
    var scroller = mapScroller(ctx, nodes, detail);
    var refRow = mapRefs(ctx, nodes, detail);
    applySelection(ctx, nodes, detail);

    return card(
      'Branch map',
      [scroller, refRow, detail, mapNote(ctx, all.length)].filter(Boolean),
      true,
      'git-map'
    );
  }

  /**
   * The canvas and the viewport it scrolls in.
   *
   * **A native scroll container, deliberately, and not the pan-and-drag viewport the Workloads diagram uses.**
   * That one is moved only by dragging, which WCAG 2.2 SC 2.5.7 (Dragging Movements) requires an alternative
   * for, and it moves by `transform`, so the browser cannot bring a focused node into view — SC 2.4.11 (Focus
   * Not Obscured) again. A scroll container gets the wheel, the keyboard, a scrollbar and `scrollIntoView`
   * from the platform, for less code than the drag handler would cost. What IS reused is the drawing itself:
   * `.dg-view`, `.dg-edges` and `.dg-edge` are app-core-diagram's classes, so this reads as the same surface
   * as the Workloads canvas rather than as a second visual language.
   */
  function mapScroller(ctx, nodes, detail) {
    var width = MAP_PAD * 2 + (ctx.total - 1) * COL_W + DOT;
    var height = MAP_PAD * 2 + (ctx.model.lanes - 1) * LANE_H + DOT;
    var canvas = h('div', {
      class: 'git-map-canvas',
      style: { width: width + 'px', height: height + 'px' },
    });
    canvas.appendChild(mapEdges(ctx, width, height));
    ctx.model.order.forEach(function (p) {
      var el = mapNode(ctx, p, nodes, detail);
      nodes.push({ hash: p.hash, el: el, kind: 'node' });
      canvas.appendChild(el);
    });

    var scroller = h('div', {
      // `.dg-view` for the recessed dot-grid surface; the rest of that rule (overflow, grab cursor) is
      // overridden in git.css, because here the surface scrolls rather than being dragged.
      class: 'dg-view git-map-scroll',
      attrs: {
        role: 'group',
        'aria-label': 'Branch map — the newest ' + ctx.total + ' commits, one lane per line of development',
      },
      on: {
        scroll: function (ev) {
          mapScrollLeft = ev.target.scrollLeft;
        },
      },
    });
    scroller.appendChild(canvas);
    // The card is not in the document yet, so it has no scroll extent to set. On the next frame it has.
    // Null means nobody has looked at this map yet: open on the newest commits, which are at the far right.
    requestAnimationFrame(function () {
      scroller.scrollLeft = mapScrollLeft == null ? scroller.scrollWidth : mapScrollLeft;
    });
    return scroller;
  }

  /**
   * One curve per real parent link. `aria-hidden`, because the graph is decoration over content that is
   * already reachable: every node is a button with the same relationships in its accessible name.
   *
   * A parent OUTSIDE the drawn window gets no stub. A line trailing off the edge would say "this continues",
   * which is true, but it would say it in exactly the same ink as a line that ends — so the fact is stated in
   * words instead ([mapNote]) where it cannot be misread.
   */
  function mapEdges(ctx, width, height) {
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('class', 'dg-edges');
    svg.setAttribute('width', String(width));
    svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('aria-hidden', 'true');
    ctx.model.order.forEach(function (p) {
      var x1 = cxOf(p.col, ctx.total);
      var y1 = cyOf(p.lane);
      p.parents.forEach(function (parentHash) {
        var parent = ctx.model.placed[parentHash];
        if (!parent) return;
        var x2 = cxOf(parent.col, ctx.total);
        var y2 = cyOf(parent.lane);
        var mid = x1 + (x2 - x1) / 2;
        var path = document.createElementNS(ns, 'path');
        path.setAttribute('class', 'dg-edge');
        path.setAttribute(
          'd',
          'M' + x1 + ' ' + y1 + ' C' + mid + ' ' + y1 + ' ' + mid + ' ' + y2 + ' ' + x2 + ' ' + y2
        );
        svg.appendChild(path);
      });
    });
    return svg;
  }

  /**
   * How a node names itself out loud: hash, subject, then the two things the drawing says in shape and colour.
   *
   * A merge is drawn as a wider dot and the checked-out commit carries the accent; neither survives forced
   * colours and neither is audible, so both are also words here (1.4.1 Use of Color).
   */
  function nodeName(p, refs) {
    var bits = [text(p.commit.short, p.hash.slice(0, 7)), text(p.commit.subject, '(no message)')];
    if (p.parents.length > 1) bits.push('merge of ' + p.parents.length + ' parents');
    if (refs.length) {
      bits.push(
        refs
          .map(function (r) {
            return text(r.name, '') + (r.current === true ? ' (checked out)' : '');
          })
          .join(', ')
      );
    }
    return bits.join(' — ');
  }

  /**
   * One commit: a dot you can press.
   *
   * A real `<button>` rather than an SVG circle with a click handler — it arrives with focus, Enter, Space and
   * the button role instead of a set of ARIA attributes this file would have to keep correct. `aria-pressed`
   * is what makes the selection programmatic rather than a colour (4.1.2), and the 24px box around a 12px dot
   * is SC 2.5.8; the lane and column gaps are both wider than it, so two targets never overlap.
   */
  function mapNode(ctx, p, nodes, detail) {
    var refs = ctx.byHash[p.hash] || [];
    var current = refs.some(function (r) {
      return r.current === true;
    });
    var label = nodeName(p, refs);
    return h(
      'button',
      {
        class:
          'git-map-node' +
          (p.parents.length > 1 ? ' merge' : '') +
          (refs.length ? ' tagged' : '') +
          (current ? ' current' : ''),
        style: {
          left: cxOf(p.col, ctx.total) - HIT / 2 + 'px',
          top: cyOf(p.lane) - HIT / 2 + 'px',
        },
        attrs: {
          type: 'button',
          'data-hash': p.hash,
          'aria-pressed': 'false',
          'aria-label': label,
        },
        title: label,
        on: {
          click: function (ev) {
            ev.preventDefault();
            pick(ctx, p.hash, label, nodes, detail);
          },
        },
      },
      h('span', { class: 'git-map-dot', attrs: { 'aria-hidden': 'true' } })
    );
  }

  /**
   * The branches, as the map's index — and the one place a branch is something you can act on.
   *
   * Pressing one goes to its commit: it selects it, brings it into view, and the detail row below then offers
   * what can be done there, *Branches* included. That is one meaning per control — a chip that both navigated
   * and checked out would be two actions behind one press, and the destructive one would be the surprise.
   *
   * Only refs anchored inside the drawn window are listed: a button for a branch that is not on the map could
   * only fail to go anywhere. How many were left out is said in [mapNote], and the header's branch chip
   * reaches the full list whatever is drawn here.
   */
  function mapRefs(ctx, nodes, detail) {
    var anchored = list(ctx.g.refs).filter(function (r) {
      return !!ctx.model.placed[text(r.hash, '')];
    });
    if (!anchored.length) return null;
    var shown = anchored.slice(0, MAX_MAP_REFS);
    return h(
      'div',
      { class: 'git-map-refs', attrs: { role: 'group', 'aria-label': 'Branches on this map' } },
      shown.map(function (r) {
        return refButton(ctx, r, nodes, detail);
      })
    );
  }

  function refButton(ctx, r, nodes, detail) {
    var name = text(r.name, '');
    var hash = text(r.hash, '');
    var kind = text(r.kind, 'local');
    var current = r.current === true;
    var p = ctx.model.placed[hash];
    var el = h(
      'button',
      {
        class:
          'git-map-ref ' +
          (kind === 'remote' || kind === 'head' ? kind : 'local') +
          (current ? ' current' : ''),
        attrs: {
          type: 'button',
          'data-ref': name,
          // Which one you are ON, said programmatically rather than by the accent alone (1.4.1 / 4.1.2).
          'aria-current': current ? 'true' : null,
          'aria-pressed': 'false',
          'aria-label': name + ' — go to this branch on the map',
        },
        title: 'Go to ' + name + ' (' + text(r.short, hash.slice(0, 7)) + ')',
        on: {
          click: function (ev) {
            ev.preventDefault();
            pick(ctx, hash, nodeName(p, ctx.byHash[hash] || []), nodes, detail);
            var node = nodeFor(nodes, hash);
            // jsdom has no layout and therefore no `scrollIntoView`; the guard is what keeps the tests honest
            // rather than making them assert on a stub.
            if (node && typeof node.scrollIntoView === 'function') {
              node.scrollIntoView({ block: 'nearest', inline: 'center' });
            }
          },
        },
      },
      h('span', { class: 'git-map-ref-name', text: name }),
      // The kind in words, because `local` / `remote` is otherwise only a colour, and the "you are here"
      // marker likewise. Both are the same 1.4.1 rule the status chips follow.
      h('span', { class: 'git-map-ref-kind', text: current ? 'HEAD' : kind })
    );
    nodes.push({ hash: hash, el: el, kind: 'ref' });
    return el;
  }

  function nodeFor(nodes, hash) {
    var found = null;
    nodes.forEach(function (entry) {
      if (found === null && entry.kind === 'node' && entry.hash === hash) found = entry.el;
    });
    return found;
  }

  /**
   * Selects a commit — or clears the selection when it was already the one selected.
   *
   * Updates in place rather than asking for a rebuild: the dashboard only repaints on a host push, so a
   * selection that waited for one would appear to do nothing until the next turn. It is also announced,
   * because the detail row appears without the focus moving and a screen-reader user would otherwise get no
   * signal at all (4.1.3 Status Messages).
   */
  function pick(ctx, hash, label, nodes, detail) {
    if (!hash) return;
    selectedHash = selectedHash === hash ? null : hash;
    applySelection(ctx, nodes, detail);
    if (typeof CC.announce === 'function') {
      CC.announce(selectedHash ? 'Selected ' + label : 'Commit selection cleared');
    }
  }

  /** Paints the current selection onto every control that reflects it, then redraws the detail row. */
  function applySelection(ctx, nodes, detail) {
    nodes.forEach(function (entry) {
      var on = !!selectedHash && entry.hash === selectedHash;
      entry.el.classList.toggle('selected', on);
      entry.el.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    renderDetail(ctx, nodes, detail);
  }

  /**
   * What the selected commit is, and what can be done to it.
   *
   * The buttons are the host's catalogue and nothing else: the per-commit entries come from `commitActions`
   * (the same builder the history rail uses, so the two bars cannot offer different things), and *Branches*
   * is added only when the commit actually carries a ref — that is the one place on this card where a branch
   * is what you are acting on.
   */
  function renderDetail(ctx, nodes, detail) {
    while (detail.firstChild) {
      detail.removeChild(detail.firstChild);
    }
    var p = selectedHash ? ctx.model.placed[selectedHash] : null;
    if (!p) {
      detail.appendChild(
        h('div', { class: 'git-note', text: 'Select a commit or a branch to see what can be done there.' })
      );
      return;
    }
    var refs = ctx.byHash[p.hash] || [];
    detail.appendChild(
      h(
        'div',
        { class: 'git-line' },
        h('span', { class: 'git-hash', text: text(p.commit.short, p.hash.slice(0, 7)) }),
        h('span', { class: 'git-subject', text: text(p.commit.subject, '(no message)') })
      )
    );
    if (refs.length) {
      detail.appendChild(
        h(
          'div',
          { class: 'git-map-tags' },
          refs.map(function (r) {
            return h('span', {
              class: 'git-map-tag' + (r.current === true ? ' current' : ''),
              text: text(r.name, '') + (r.current === true ? ' · HEAD' : ''),
            });
          })
        )
      );
    }
    var buttons = ctx.commitActions.map(function (a) {
      return commitActionButton(a, p.hash);
    });
    var branches = refs.length ? actionById(ctx.g, BRANCHES_ACTION) : null;
    if (branches) buttons.push(actionButton(branches));
    if (buttons.length) detail.appendChild(h('div', { class: 'git-map-actions' }, buttons));
  }

  /**
   * What the map is NOT showing, in words.
   *
   * The window is always named, even when nothing was cut: "the newest N commits" is what stops a complete-
   * looking picture being read as the whole history. Each further sentence is added only when its bound
   * actually bit, so the note never claims a truncation that did not happen.
   */
  function mapNote(ctx, available) {
    var lines = ['Showing the newest ' + ctx.total + ' commits.'];
    if (available > ctx.total) lines.push('Older commits in this payload are not drawn.');
    var cutOff = ctx.model.order.some(function (p) {
      return p.parents.some(function (hash) {
        return !ctx.model.placed[hash];
      });
    });
    if (cutOff) lines.push('Lines continuing past the oldest commit shown have no edge drawn.');
    var refs = list(ctx.g.refs);
    var anchored = refs.filter(function (r) {
      return !!ctx.model.placed[text(r.hash, '')];
    });
    var away = refs.length - anchored.length;
    if (away > 0) {
      lines.push(away + (away === 1 ? ' branch points' : ' branches point') + ' outside this window.');
    }
    if (anchored.length > MAX_MAP_REFS) {
      lines.push(anchored.length - MAX_MAP_REFS + ' more are not listed.');
    }
    return h('div', { class: 'git-note', text: lines.join(' ') });
  }

  // ---------------------------------------------------------------------------
  // Where the branch sits, and what the forge says about it
  // ---------------------------------------------------------------------------

  /** Opens [url] through the host, which decides whether the destination is openable. Never `window.open`. */
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

  /** One `label: value` line, or null when there is no value — an absent field draws nothing, never "—". */
  function factRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'git-fact' },
      h('span', { class: 'git-fact-label', text: label }),
      h('span', { class: 'git-fact-value', text: String(value) })
    );
  }

  /**
   * Where this branch came from and how far it has drifted from it.
   *
   * `ahead`/`behind` are omitted when the host sent no number rather than drawn as `0`: the count is null
   * when it could not be read, and a zero there reads as "in sync", which is the one answer nobody has.
   * They are also *since the last fetch* — the plugin reads refs and never contacts a remote — so the card
   * says so rather than implying it is live.
   */
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

  /**
   * The branch's open pull requests, and the last CI run on it.
   *
   * ONE card for both because they answer the same question — "what is happening to this branch away from my
   * machine" — and because either half may be missing on its own: a repository with CI and no open PR is
   * ordinary, and so is the reverse.
   *
   * **A missing key draws nothing; an empty list draws a sentence.** The host omits `pullRequests` entirely
   * when it never asked (no remote, no token, unreachable) and sends `[]` when it asked and the answer was
   * none. Collapsing those would either hide a real answer or put a card on screen asking to be configured
   * for a feature the user has not requested.
   */
  function buildGitForgeCard(git) {
    var g = gitOf(git);
    if (!g || !repoOf(g).present) return null;
    var hasPulls = Object.prototype.hasOwnProperty.call(g, 'pullRequests');
    var run = g.lastRun;
    if (!hasPulls && !run) return null;

    var body = [];
    if (run) body.push(runRow(run));
    if (hasPulls) {
      var pulls = list(g.pullRequests);
      if (!pulls.length) {
        body.push(h('div', { class: 'git-note', text: 'No open pull requests for this branch.' }));
      } else {
        pulls.forEach(function (pull) {
          body.push(pullRow(pull));
        });
      }
    }
    return card('This branch elsewhere', body, false, 'git-forge');
  }

  /** The last run: its status word — the page's own four — plus a way to open it. */
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

  function pullRow(pull) {
    var number = pull.number == null ? '' : '#' + pull.number;
    return h(
      'div',
      { class: 'git-forge-row' },
      h('span', { class: 'git-forge-num', text: number }),
      h('span', { class: 'git-forge-label', text: text(pull.title, '(no title)') }),
      pull.draft ? h('span', { class: 'git-forge-draft', text: 'draft' }) : null,
      linkTo('Open', text(pull.url, ''), 'git-forge-open')
    );
  }

  // The strip, so the embedded chat pane heads itself with the same two destinations rather than a copy.
  D.gitViewTabs = viewTabs;
  D.buildGitHeadCard = buildGitHeadCard;
  D.buildGitActionsCard = buildGitActionsCard;
  D.buildGitHistoryCard = buildGitHistoryCard;
  D.buildGitTopologyCard = buildGitTopologyCard;
  D.buildGitForgeCard = buildGitForgeCard;
})();
