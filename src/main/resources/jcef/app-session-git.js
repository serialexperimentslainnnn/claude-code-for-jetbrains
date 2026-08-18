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
 * The history is ONE card and it is a LIST: one row per commit, newest first, each row a gutter on the left
 * and the commit's prose on the right. The graph lives in that gutter — a small SVG per row — rather than on a
 * canvas with the text positioned over it, and that is the whole layout decision: a list keeps its scrolling,
 * its wrapping and its tab order from the platform, while a canvas has to reimplement all three and gets the
 * accessible name of a picture. It replaces the separate horizontal branch map, which asked the reader to hold
 * two pictures of the same history at once.
 *
 * **The graph is drawn from real parents and real refs, or it is not drawn at all.** The payload carries each
 * commit's parent hashes and every branch with the commit it points at (`JcefGitData`), so a fork is a fork the
 * repository has and a lane is named by a ref that exists; a commit with no parents in the window simply gets
 * no line, never an invented one. An invented topology in a Git view is worse than none, because nothing on
 * screen tells a drawn branch from a real one — and the same goes for a truncated one presented as complete,
 * which is why what is cut is said out loud.
 *
 * **Lane colour is never the only carrier of anything** (WCAG 1.4.1): a branch is a text tag on its row, a
 * merge says the word, and what the gutter draws is `aria-hidden` because every relationship it shows is
 * already in the row's own text.
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
   * ONE id with three doors — the action bar, the branch chip in this header, and every branch tag on a commit
   * row — because the button and what it does may not drift apart, which is the whole reason `GitActionCatalog`
   * exists. The page never invents a message for "switch to this branch": a branch name coming off this page
   * is a free-form value exactly like a commit hash, and the only thing that could act on one is a checkout,
   * which this plugin does not perform. The platform's popup offers the real list instead, with its own
   * enablement and its own undo.
   */
  var BRANCHES_ACTION = 'branches';

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
    return viewHead(card('Repository', [id], true, 'git-head'));
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
   * The strip and whatever heads the Overview, as ONE full-width item of the dashboard's grid.
   *
   * The builders return one node each and the panel appends them side by side, so the strip has to travel
   * with the first card; loose, it would be laid out as a 260px grid column beside the repository rather than
   * as a header over the view.
   */
  function viewHead(cardEl) {
    return h('div', { class: 'git-viewhead' }, viewTabs('overview'), cardEl);
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
  // History — the graph, drawn as a list
  // ---------------------------------------------------------------------------

  /**
   * One lane of development, in CSS px, and the two numbers a row is drawn in.
   *
   * The lane width is real pixels, because it decides how wide the gutter column is. A row's HEIGHT is not a
   * number this file can know — a wrapped subject, a file list or an action bar all change it — so the gutter
   * is drawn in ABSTRACT units instead: each row's SVG declares a [ROW_UNITS]-tall viewBox and is stretched to
   * whatever the row turns out to be (`preserveAspectRatio="none"`). A vertical line is the one shape that
   * cannot show that stretch, which is what makes the trick sound; the curves keep their weight through
   * `vector-effect="non-scaling-stroke"`, and the dot is an HTML element rather than a circle that would
   * arrive as an ellipse.
   */
  var LANE_W = 16;
  var ROW_UNITS = 100;
  var ROW_MID = 50;

  /**
   * How many lanes the palette tells apart before it repeats.
   *
   * Six colours, all of them derived in git.css from the IDE's own theme variables — the page invents no hue
   * of its own. The seventh lane wears the first one's colour, and that is survivable for one reason only:
   * **colour is never the sole carrier here** (1.4.1). The branch is a text tag on its row and a merge says
   * the word, so a repeated hue costs a little legibility and no information.
   */
  var LANE_COLOURS = 6;

  /** The working copy's row id. Not a hash, and nothing in a repository can collide with it. */
  var WIP_ID = ':working-copy:';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  /**
   * Working copy first, then one row per commit, each with its piece of the graph in its own gutter.
   *
   * Uncommitted work is the top row because that is where it sits in time, and it only appears when there IS
   * some: an empty "Uncommitted changes" row reads as a state, and the state it reads as is the wrong one. It
   * goes through the same lane assignment as everything else, with `HEAD`'s commit as its parent — which both
   * draws it on the line it will actually land on and keeps that line in the leftmost lane, rather than
   * wherever the newest commit of some other branch happens to push it.
   */
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

  /**
   * Assigns every entry a lane and says what its row's gutter has to draw. `git log --graph`'s own rule,
   * written once, with no DOM and no knowledge of the payload beyond `hash` and `parents`.
   *
   * A lane is a slot waiting for a particular commit. Walking newest to oldest, a commit takes the lane that
   * was waiting for it — the first of them, when several were — or opens one where nothing was; its FIRST
   * parent then continues that lane, and every other parent books one of its own. **Keeping the first parent
   * in place is what makes a mainline read as a straight line** instead of wandering into whichever slot
   * happened to be free, and it is why a merge pushes the branch it absorbed aside rather than the reverse.
   *
   * Two rules bound the width, and without them it only grows. A lane still waiting for a commit that has now
   * been drawn is RELEASED, because several lanes converge on a fork and leaving them reserved would step
   * every later line one lane further right for good; and a parent outside this window books nothing at all,
   * since it can never arrive to claim the slot.
   *
   * Exported as `CC.dash.gitLanes` so the assignment can be tested as the arithmetic it is, rather than
   * through the pixel positions it eventually becomes.
   *
   * Returns `{ rows, lanes }` — one row per entry, in the order given:
   *  - `lane` — the column this commit's dot sits in;
   *  - `up` — lanes arriving from above and ending at that dot. Empty on a branch tip, which nothing points
   *    at yet;
   *  - `down` — lanes leaving the dot for its parents. Empty on a root, and on a commit whose every parent is
   *    out of the window;
   *  - `through` — lanes that cross this row untouched, top to bottom;
   *  - `merge` — more than one parent. A fact about the commit, not about the drawing.
   */
  function layoutLanes(entries) {
    var items = list(entries);
    var known = Object.create(null);
    items.forEach(function (item) {
      var hash = text(item.hash, '');
      if (hash) known[hash] = true;
    });

    // lane index → the hash that lane is waiting for, or null while it is free.
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

  /** The centre of lane [l]: real pixels across, since only the vertical axis is stretched. */
  function laneX(l) {
    return l * LANE_W + LANE_W / 2;
  }

  /** A line that leaves one lane vertically and arrives in another vertically. */
  function bend(x1, y1, x2, y2) {
    var mid = (y1 + y2) / 2;
    return 'M' + x1 + ' ' + y1 + 'C' + x1 + ' ' + mid + ' ' + x2 + ' ' + mid + ' ' + x2 + ' ' + y2;
  }

  /**
   * One stroke of the graph.
   *
   * `data-lane` is the palette SLOT, not the lane index: the colours repeat every [LANE_COLOURS] lanes, and
   * asking CSS to know that would mean one rule per lane a repository might ever open.
   */
  function edge(svg, d, lane) {
    var path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('class', 'git-edge');
    path.setAttribute('d', d);
    path.setAttribute('data-lane', String(lane % LANE_COLOURS));
    svg.appendChild(path);
  }

  /**
   * The gutter of one row: every line that crosses it, and the dot itself.
   *
   * `aria-hidden` on the drawing, deliberately: it is decoration over content that is already reachable, since
   * the row's own text names the branches on it and says whether it is a merge. Announcing the picture too
   * would only repeat that, in a form nobody can act on.
   */
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
   * The commit `HEAD` stands on, in full, or '' when this window cannot see it.
   *
   * The refs are asked first because the checked-out one carries the full hash; `repo.head` is the fallback,
   * matched by prefix so a payload that abbreviates it still anchors. It is what the working-copy row hangs
   * from, and '' is a real answer: the row is then a dot with nothing under it, which says less than the truth
   * but never more.
   */
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

  /** One commit: where it sits in the graph, what it says, who wrote it when, and what can be done to it. */
  function commitRow(g, row, lanes, byHash, actions) {
    var c = row.item.commit;
    var hash = row.hash;
    var short = text(c.short, hash.slice(0, 7));
    var refs = byHash[hash] || [];
    var meta = [text(c.author, null), ageText(c.ageMillis), fileCount(c.files)].filter(Boolean);
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
          // The WORD, not the wider dot alone: a shape is no more audible than a colour (1.4.1), it does not
          // survive forced colours either, and the subject of a merge does not always say so itself.
          row.merge ? h('span', { class: 'git-merge', text: 'merge' }) : null,
          refs.map(function (r) {
            return refTag(g, r);
          }),
          h('span', { class: 'git-subject', text: text(c.subject, '(no message)') })
        ),
        meta.length ? h('div', { class: 'git-meta', text: meta.join(' · ') }) : null,
        // The FULL hash, never the seven characters on screen: an abbreviation is unique only until it is
        // not, and the host acts on what it is sent.
        commitActionBar(actions, hash)
      )
    );
  }

  /**
   * A branch — or a detached `HEAD` — as a tag on the row it points at, and the third door onto the platform's
   * branch switcher.
   *
   * **The tag is where a lane's identity lives.** The gutter can only say "these commits share a line"; which
   * line it is is precisely what a colour cannot carry (1.4.1), so the name is text on the row. `remote` and
   * `HEAD` are words for the same reason — otherwise those two are told apart from a plain local branch by hue
   * alone — and the accessible name contains the whole visible label, so speaking what is on screen activates
   * it (2.5.3 Label in Name).
   *
   * Pressing one opens the platform's popup: the same catalogue entry the action bar and the header chip fire,
   * never an invented "check out this branch" message, which is a write this plugin does not perform. It falls
   * back to plain text when the host is not offering that entry, since a button firing an id the catalogue
   * lookup will miss is a control that does nothing and says nothing.
   */
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
          // Which one you are on, said programmatically rather than by the accent alone (1.4.1 / 4.1.2).
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
   * Quiet until the row is hovered or something in it takes focus, and OUT OF FLOW while it is (git.css).
   * Both halves matter and they are different arguments. Quiet, because four controls on every row of a long
   * history compete with the subjects, which are what the list is read for. Out of flow, because faded is not
   * free: as a line of its own the strip was the tallest thing in a row that only ever needed two lines of
   * text, on all hundred commits at once.
   *
   * Quiet is `opacity`, never `display: none` or `visibility: hidden`: those take the button out of the tab
   * order, and a control that exists only under a pointer is a WCAG 2.1.1 failure, not a design choice.
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

  /**
   * What the card is NOT showing, in words.
   *
   * The window is always named, even when nothing was cut: a complete-looking picture read as the whole
   * history is exactly the misreading a graph invites, and it is the same defect as an invented fork. Each
   * further sentence is added only when its bound actually bit, so the note never claims a truncation that did
   * not happen.
   *
   * "across every branch" is a statement about the data, not a flourish: the host walks every ref
   * (`GitGateway.recentCommits`), which is why a commit nobody made on this branch can appear here — and why
   * the window holds fewer commits of each line than it used to hold of one.
   *
   * The last sentence is the one that answers the complaint this card exists for. A graph of a single lane
   * looks exactly like a graph that failed, and on a branch that has been linear for longer than the window
   * that is the honest picture rather than a fault — so when there is one lane and branches the window cannot
   * reach, it SAYS which of the two it is. Silence there is the defect being reported all over again, with
   * the drawing telling the reader nothing and nothing else telling them either.
   */
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
  // The lane assignment, exported so it can be tested as arithmetic rather than through pixel positions.
  D.gitLanes = layoutLanes;
  D.buildGitHeadCard = buildGitHeadCard;
  D.buildGitActionsCard = buildGitActionsCard;
  D.buildGitHistoryCard = buildGitHistoryCard;
  D.buildGitTopologyCard = buildGitTopologyCard;
  D.buildGitForgeCard = buildGitForgeCard;
})();
