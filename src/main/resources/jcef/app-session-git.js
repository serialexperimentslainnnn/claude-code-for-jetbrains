/* app-session-git.js — the Git view.
 *
 * One subject: what the repository is, what can be done to it, and what has happened in it — the three cards
 * the `git` view of the dashboard is made of. Pure builders over `payload.git`, like every other card in this
 * family: given the data they return a card, given nothing they return null and the card does not appear.
 *
 * The history is ONE vertical rail, not a branch graph. The payload carries no parent hashes, so any fork or
 * merge drawn here would be invented — and an invented topology in a Git view is worse than none, because
 * nothing on screen tells a drawn branch from a real one.
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

  // ---------------------------------------------------------------------------
  // Header — the repository, and the way back to the chat
  // ---------------------------------------------------------------------------
  /**
   * Repo name, branch and short HEAD, plus the explicit chat toggle.
   *
   * With no repository it degrades to a single card offering to create one: a branch row, an action bar
   * and an empty history under it would all be answering questions that cannot be asked yet.
   */
  function buildGitHeadCard(git) {
    var g = gitOf(git);
    if (!g) return null;
    var repo = repoOf(g);
    if (!repo.present) return noRepoCard(g);

    var id = h(
      'div',
      { class: 'git-id' },
      h('span', { class: 'git-repo', text: text(repo.root, 'Repository') }),
      h('span', { class: 'git-branch', text: text(repo.branch, 'detached') }),
      h('span', { class: 'git-sha', text: text(repo.head, '—') })
    );
    return card('Repository', [h('div', { class: 'git-head' }, id, chatToggle())], true, 'git-head');
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

  /**
   * Show / Hide chat, in words rather than as a mode of some other control.
   *
   * It drives the SAME toggle as the view switcher in the tab bar — the panel's visibility has one owner
   * (`app-session.js`), and a second one would be a second answer to "is the chat on screen".
   */
  function chatToggle() {
    var btn = h('button', {
      class: 'btn git-chat-toggle',
      attrs: { type: 'button', 'aria-controls': 'cc-dashboard' },
    });
    function sync() {
      var open = typeof D.dashboardShown === 'function' ? D.dashboardShown() : true;
      // `aria-expanded` is about the panel this button controls, so it tracks the panel and not the label.
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.textContent = open ? 'Show chat' : 'Hide chat';
    }
    btn.addEventListener('click', function (ev) {
      ev.preventDefault();
      if (typeof D.toggleDashboard === 'function') D.toggleDashboard();
      sync();
    });
    sync();
    return btn;
  }

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

    var nodes = [];
    if (changes.length) nodes.push(wipNode(changes));
    commits.forEach(function (c) {
      nodes.push(commitNode(c));
    });
    return card('History', [h('div', { class: 'git-rail' }, nodes)], true, 'git-history');
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

  /** One commit: what it is, what it says, and who wrote it when. */
  function commitNode(c) {
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
        meta.length ? h('div', { class: 'git-meta', text: meta.join(' · ') }) : null
      )
    );
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

  D.buildGitHeadCard = buildGitHeadCard;
  D.buildGitActionsCard = buildGitActionsCard;
  D.buildGitHistoryCard = buildGitHistoryCard;
})();
