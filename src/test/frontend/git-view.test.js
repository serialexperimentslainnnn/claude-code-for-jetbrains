// The dashboard's Git view: the repository header, the grouped action bar and the single-rail history.
//
// Three classes of rule are pinned here, and each one is a thing that would otherwise regress silently:
//  - the view answers the payload it is given — the actions in the ORDER their groups arrive, the WIP node
//    only when there is uncommitted work, and a project with no repository reduced to the one action that
//    changes that, rather than to a header over an empty rail;
//  - a button is a button (WCAG 2.2 AA): a real <button type="button">, a status said in WORDS and not in
//    colour alone (1.4.1), programmatic state on the chat toggle (4.1.2) and a spoken announcement when an
//    action's state changes without the focus moving (4.1.3);
//  - the classes the JS emits have real rules, because a class with no rule renders as unstyled text and
//    nothing in a DOM assertion notices.
const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, readCss, JCEF } = require('./helpers/load');

/** A repository with everything the contract can carry: work in progress, history, and all three groups. */
const GIT = {
  available: true,
  repo: {
    present: true,
    branch: 'feature/release_5.5.0',
    head: '8933592',
    root: 'claude-code-for-jetbrains',
  },
  changes: ['README.md', 'src/main/resources/jcef/app-session.js'],
  commits: [
    {
      hash: '8933592ffee1',
      short: '8933592',
      subject: 'chore: invert .gitignore into an allowlist',
      author: 'Lain',
      ageMillis: 3 * 3600 * 1000,
      files: 3,
    },
    {
      hash: '6f781c5aabb2',
      short: '6f781c5',
      subject: "feat(session): review the whole session's changes",
      author: 'Lain',
      ageMillis: 5 * 86400 * 1000,
      files: 12,
    },
  ],
  actions: [
    {
      id: 'refresh',
      label: 'Refresh',
      hint: 'Re-read the repository',
      kind: 'direct',
      group: 'Repository',
      status: null,
    },
    { id: 'commit', label: 'Commit', hint: null, kind: 'ide', group: 'IDE actions', status: 'running' },
    {
      id: 'explain',
      label: 'Explain the last commit',
      hint: null,
      kind: 'prompt',
      group: 'Ask Claude',
      status: 'completed',
    },
    { id: 'push', label: 'Push', hint: null, kind: 'direct', group: 'Repository', status: 'failed' },
  ],
};

const clone = (o) => JSON.parse(JSON.stringify(o));

describe('git view', () => {
  let win;

  const panel = () => win.document.querySelector('.dashboard');
  const openView = (name) => {
    const btn = win.document.querySelector('.dash-toggle[data-view="' + name + '"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return btn;
  };
  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const texts = (sel) => Array.from(panel().querySelectorAll(sel)).map((el) => el.textContent.trim());

  beforeEach(() => {
    win = loadFrontend(['app-session.js'], { vendor: false });
    win.cc.session({ git: clone(GIT) });
  });

  it('heads the view with the repository, its branch and the short HEAD', () => {
    openView('git');
    expect(panel().querySelector('.git-repo').textContent).toBe('claude-code-for-jetbrains');
    expect(panel().querySelector('.git-branch').textContent).toBe('feature/release_5.5.0');
    // The SHORT head. A 40-character hash in a narrow tool window pushes the branch name off the row and
    // says nothing the first seven characters do not.
    expect(panel().querySelector('.git-sha').textContent).toBe('8933592');
  });

  it('groups the actions by `group`, in the order the groups arrive', () => {
    openView('git');
    // Not alphabetical and not a list hardcoded here: the host decides which group leads, and a view that
    // re-sorted them would drift from it the moment a group is added.
    expect(texts('.git-group-name')).toEqual(['Repository', 'IDE actions', 'Ask Claude']);
    const first = panel().querySelector('.git-group .git-group-items');
    // `push` arrives fourth and still belongs to the first group — grouping is by name, not by position.
    expect(Array.from(first.querySelectorAll('.git-action')).length).toBe(2);
  });

  it('an action is a real button and sends its own id', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const explain = Array.from(panel().querySelectorAll('.git-action')).find((b) =>
      b.textContent.includes('Explain the last commit')
    );
    // Not a div wearing role="button": the element that already has focus, Enter and Space.
    expect(explain.tagName).toBe('BUTTON');
    expect(explain.getAttribute('type')).toBe('button');
    click(explain);
    // The id, never the label: the label is what the user reads and the host is free to reword it.
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'explain' });
  });

  it('paints the word the host sent for each state, never a colour alone', () => {
    openView('git');
    const chips = Array.from(panel().querySelectorAll('.git-status'));
    // WCAG 1.4.1: colour may not be the only carrier. Every chip says its state in text, so it survives
    // forced-colours mode, a colour-blind reader and a screen reader alike.
    // Document order is GROUP order, so `push` (Repository) comes before `commit` (IDE actions).
    expect(chips.map((c) => c.textContent)).toEqual(['failed', 'running', 'completed']);
    expect(chips.map((c) => c.className)).toEqual([
      'git-status failed',
      'git-status running',
      'git-status completed',
    ]);
    // An action with no status gets no chip at all — "idle" is not a state worth a badge.
    const refresh = Array.from(panel().querySelectorAll('.git-action')).find((b) =>
      b.textContent.includes('Refresh')
    );
    expect(refresh.querySelector('.git-status')).toBeNull();
  });

  it('announces a state change, because the chip changes without the focus moving', () => {
    const spoken = [];
    win.CC.announce = (m) => spoken.push(m);
    openView('git');
    const next = clone(GIT);
    next.actions[0].status = 'running'; // Refresh: idle → running
    win.cc.session({ git: next });
    // WCAG 4.1.3 Status Messages: the panel repaints in place, so without the live region nothing tells a
    // screen-reader user that the action they pressed started.
    expect(spoken).toContain('Refresh — running');
    // …and a push that changes nothing is silent: the dashboard rebuilds several times a turn, and a region
    // that repeats itself is one the user switches off.
    const before = spoken.length;
    win.cc.session({ git: clone(next) });
    expect(spoken.length).toBe(before);
  });

  it('draws the WIP node above the commits, and only when there is uncommitted work', () => {
    openView('git');
    const nodes = Array.from(panel().querySelectorAll('.git-node'));
    expect(nodes[0].classList.contains('git-wip')).toBe(true);
    expect(nodes[0].textContent).toContain('Uncommitted changes');
    expect(texts('.git-wip .git-file')).toEqual(['README.md', 'src/main/resources/jcef/app-session.js']);
    // One rail, one node per commit, in the order they were given.
    expect(texts('.git-commit .git-hash')).toEqual(['8933592', '6f781c5']);

    const clean = clone(GIT);
    clean.changes = [];
    win.cc.session({ git: clean });
    // An empty "Uncommitted changes" node reads as a state, and the state it reads as is the wrong one.
    expect(panel().querySelectorAll('.git-wip').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit').length).toBe(2);
  });

  it('a commit says its hash, subject, author, age and file count', () => {
    openView('git');
    const first = panel().querySelector('.git-commit');
    expect(first.querySelector('.git-subject').textContent).toBe(
      'chore: invert .gitignore into an allowlist'
    );
    // Relative, because an absolute timestamp makes the reader do the arithmetic.
    expect(first.querySelector('.git-meta').textContent).toBe('Lain · 3h ago · 3 files');
  });

  it('with no repository it is ONE card offering to create one', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.session({
      git: { available: true, repo: { present: false }, changes: [], commits: [], actions: [] },
    });
    openView('git');
    // No branch row, no action bar, no rail: a header over an empty rail would be answering questions that
    // cannot be asked yet.
    expect(panel().querySelectorAll('.dash-card').length).toBe(1);
    expect(panel().querySelectorAll('.git-rail').length).toBe(0);
    expect(panel().textContent).toContain('not a Git repository');
    const init = panel().querySelector('.git-action');
    expect(init.textContent).toContain('Initialize repository');
    click(init);
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'init' });
  });

  it('shows and hides the chat from the header, in both directions', () => {
    openView('git');
    const toggle = panel().querySelector('.git-chat-toggle');
    // The dashboard is what is on screen, so the offer is to show the chat — and the state is programmatic
    // (4.1.2), not merely painted.
    expect(toggle.textContent).toBe('Show chat');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-controls')).toBe('cc-dashboard');

    click(toggle);
    expect(panel().hasAttribute('hidden')).toBe(true);
    expect(toggle.textContent).toBe('Hide chat');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    // The same control, the same toggle the tab bar's switcher drives: one owner for the panel's visibility.
    click(toggle);
    expect(panel().hasAttribute('hidden')).toBe(false);
  });

  it('the Git button appears only when the host reports a Git surface', () => {
    const gitBtn = () =>
      Array.from(win.document.querySelectorAll('.dash-toggles button')).find((b) => b.textContent === 'Git');
    expect(gitBtn().hidden).toBe(false);

    win.cc.session({ git: { available: false } });
    // A project with no Git support would otherwise get a permanent button onto a view that can only say so.
    expect(gitBtn().hidden).toBe(true);
  });

  it('a Git tab opens ON the Git view, and comes back to it', () => {
    // `gitIntegration` marks THIS tab as the one made to show the repository; opening it on an empty chat
    // would make the user press a button to reach the only reason the tab exists.
    win.cc.meta({ gitIntegration: true });
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().querySelector('.git-rail')).not.toBeNull();

    const toggle = panel().querySelector('.git-chat-toggle');
    click(toggle);
    expect(panel().hasAttribute('hidden')).toBe(true);
    click(toggle);
    // Back to Git, not to Session: for this tab the Git view IS the default one.
    expect(panel().querySelector('.git-rail')).not.toBeNull();
  });

  it('no other tab changes behaviour: without the flag the chat stays on screen', () => {
    // The whole point of the flag is that it is opt-in. A payload carrying a repository must not shove the
    // dashboard over the transcript of a chat somebody is reading.
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

  it('every git- class the view emits has a real CSS rule', () => {
    // Same contract as css-contract.test.js, scoped to this view: a class with no rule does not fail, it
    // renders as unstyled text — which no DOM assertion above would notice.
    const src = fs.readFileSync(path.join(JCEF, 'app-session-git.js'), 'utf8');
    const emitted = new Set();
    for (const m of src.matchAll(/class:\s*(["'])([^"']+)\1/g)) {
      for (const c of m[2].split(/\s+/)) {
        if (c.startsWith('git-')) emitted.add(c);
      }
    }
    const declared = new Set([...readCss().matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]));
    expect(emitted.size).toBeGreaterThan(0);
    expect([...emitted].filter((c) => !declared.has(c)).sort()).toEqual([]);
  });
});
