// The dashboard's Git view: the Overview/Chat header, the grouped action bar and the single-rail history.
//
// Three classes of rule are pinned here, and each one is a thing that would otherwise regress silently:
//  - the view answers the payload it is given — the actions in the ORDER their groups arrive, one commit
//    button per action the host OFFERS (and none at all when it offers none), the WIP node only when there is
//    uncommitted work, and a project with no repository reduced to the one action that changes that, rather
//    than to a header over an empty rail;
//  - a button is a button (WCAG 2.2 AA): a real <button type="button">, a status said in WORDS and not in
//    colour alone (1.4.1), programmatic state on the tab strip (aria-current, 4.1.2) and a spoken
//    announcement when an action's state changes without the focus moving (4.1.3);
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
  // One catalogue, applied to every row of the history. Host-side like `actions`, for the same reason.
  commitActions: [
    { id: 'commitDiff', label: 'View diff', hint: 'Open this commit in the diff viewer' },
    { id: 'commitRevertBranch', label: 'Revert to this commit on a new branch', hint: null },
    { id: 'commitCopyHash', label: 'Copy hash', hint: null },
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
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
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

  it('every commit row carries one button per action the payload offers', () => {
    openView('git');
    const rows = Array.from(panel().querySelectorAll('.git-commit'));
    for (const row of rows) {
      const buttons = Array.from(row.querySelectorAll('.git-commit-action'));
      // The catalogue is the host's; a list hardcoded here would be a button labelled one thing and doing
      // another the moment the host adds or renames an entry.
      expect(buttons.map((b) => b.textContent)).toEqual([
        'View diff',
        'Revert to this commit on a new branch',
        'Copy hash',
      ]);
      // Real buttons: focusable, in the tab order, Enter/Space operable, accessible name from the label —
      // WCAG 2.1.1, and the reason the row hides them with `opacity` rather than `display: none`.
      expect(buttons.map((b) => b.tagName)).toEqual(['BUTTON', 'BUTTON', 'BUTTON']);
      expect(buttons.every((b) => b.getAttribute('type') === 'button')).toBe(true);
      expect(buttons.every((b) => b.textContent.trim().length > 0)).toBe(true);
    }
    // The WIP node is not a commit and has nothing to revert to.
    expect(panel().querySelectorAll('.git-wip .git-commit-action').length).toBe(0);
  });

  it('a commit button sends its id and the FULL hash of its own row', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const second = panel().querySelectorAll('.git-commit')[1];
    const revert = Array.from(second.querySelectorAll('.git-commit-action')).find((b) =>
      b.textContent.startsWith('Revert')
    );

    click(revert);
    // The full hash, not the seven characters the row paints: an abbreviation is unique only until it is
    // not, and the host acts on what it is sent. The id, never the label — the host is free to reword it.
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'commitRevertBranch', hash: '6f781c5aabb2' });
  });

  it('both bars send ONE message type, so neither can be a button the host has no parser for', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    sent.length = 0; // opening the view is not one of the two presses under test
    click(Array.from(panel().querySelectorAll('.git-action')).find((b) => b.textContent.includes('Refresh')));
    click(panel().querySelector('.git-commit .git-commit-action'));

    // The action bar and the history rail are ONE catalogue with ONE executor host-side. A second message
    // type for the second bar (`gitCommitAction`) was a type `JcefBridge` never parsed: the press produced a
    // message, the host dropped it, and the button looked merely slow. Nothing about the shape says which bar
    // it came from — only whether there is a commit to act on.
    expect(sent.map((m) => m.type)).toEqual(['gitAction', 'gitAction']);
    expect(sent[0].hash).toBeUndefined();
    expect(sent[1].hash).toBe('8933592ffee1');
  });

  it('offered no commit actions, a row carries no buttons rather than an empty row', () => {
    const bare = clone(GIT);
    delete bare.commitActions;
    win.cc.session({ git: bare });
    openView('git');
    // Absent, not empty: the host has not shipped the catalogue yet, or has filtered it away, and an empty
    // container still takes its gap and reads as controls that failed to load.
    expect(panel().querySelectorAll('.git-commit-action').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit-actions').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit').length).toBe(2);

    // Same answer for a catalogue whose entries carry no id: a button whose press sends nothing is
    // indistinguishable from a broken one, so it is not drawn.
    const idless = clone(GIT);
    idless.commitActions = [{ label: 'View diff' }];
    win.cc.session({ git: idless });
    expect(panel().querySelectorAll('.git-commit-action').length).toBe(0);
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

  it('heads the view with two destinations, and says which one you are on', () => {
    openView('git');
    // The strip inside the OVERVIEW. There are two in the document — each destination heads itself with the
    // same builder, which is what stops the two disagreeing about what the destinations are — so a bare
    // `querySelectorAll` over the panel would collect both and read as four tabs.
    const tabs = Array.from(panel().querySelector('.git-viewhead .git-viewtabs').children);
    expect(tabs.map((t) => t.textContent)).toEqual(['Overview', 'Chat']);
    // Real buttons, so Enter, Space, focus and the button role come from the platform.
    expect(tabs.map((t) => t.tagName)).toEqual(['BUTTON', 'BUTTON']);
    expect(tabs.map((t) => t.getAttribute('type'))).toEqual(['button', 'button']);
    // Which one you are on is programmatic (4.1.2), not only the underline — 1.4.1 forbids colour as the
    // only carrier, and an underline read as "selected" is exactly such a carrier.
    expect(tabs[0].getAttribute('aria-current')).toBe('true');
    expect(tabs[1].getAttribute('aria-current')).toBeNull();
    // NOT a tablist: the two destinations are drawn by different modules into sibling containers, neither of
    // which is the other's `tabpanel`, so the roles would promise a relationship (one owned region,
    // arrow-key roving) the page does not implement.
    expect(panel().querySelector('[role="tablist"]')).toBeNull();
    // There are two strips in the document — each pane heads itself with the same builder — so this asks the
    // one that is on screen rather than the first in source order.
    const strip = tabs[0].closest('.git-viewtabs');
    expect(strip.getAttribute('role')).toBe('group');
  });

  it('the Chat tab switches the view to the conversation, and stays in the panel', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const chat = Array.from(panel().querySelectorAll('.git-viewtab')).find((t) => t.textContent === 'Chat');

    click(chat);
    // It used to send `{type:'gitChat'}` and leave, because the conversation was a tab of its own somewhere
    // else. It is embedded in this view now, so switching to it asks the host for NOTHING and — the part
    // that matters — does not close the panel it lives in.
    expect(sent.some((m) => m.type === 'gitChat')).toBe(false);
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(win.CC.dash.gitSubView()).toBe('chat');
    // The pane is on screen and the cards are not: one destination at a time.
    const pane = panel().querySelector('.gitchat');
    expect(pane).toBeTruthy();
    expect(pane.hidden).toBe(false);
    expect(panel().querySelector('.dash-inner').hidden).toBe(true);
  });

  it('Overview keeps you on the overview rather than toggling it away', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const overview = panel().querySelector('.git-viewtab');

    click(overview);
    // The current destination is still a live control (it has to be tabbable), and pressing it must not
    // behave like the switcher in the composer's row, where pressing the open view closes the panel.
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().querySelector('.git-rail')).not.toBeNull();
    expect(sent).toEqual([]);
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

    // Switching to the conversation and back stays inside the view: both destinations are drawn here now, so
    // neither of them closes the panel on the way.
    const strip = () => panel().querySelector('.git-viewhead .git-viewtabs');
    const tab = (name) => Array.from(strip().children).find((t) => t.textContent === name);
    click(tab('Chat'));
    expect(panel().hasAttribute('hidden')).toBe(false);
    // `hidden` rather than removed: the cards keep their DOM — and therefore the panel's scroll offset —
    // while the conversation is up, which is the same bargain the dashboard strikes with the transcript.
    expect(panel().querySelector('.dash-inner').hidden).toBe(true);

    click(tab('Overview'));
    expect(panel().querySelector('.dash-inner').hidden).toBe(false);
    // Back to Git, not to Session: for this tab the Git view IS the default one.
    expect(panel().querySelector('.git-rail')).not.toBeNull();
  });

  it('no other tab changes behaviour: without the flag the chat stays on screen', () => {
    // The whole point of the flag is that it is opt-in. A payload carrying a repository must not shove the
    // dashboard over the transcript of a chat somebody is reading.
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

  // ── the branch card, and what the forge says about it ──────────────────────────────────────────────────
  //
  // ONE distinction carries these two cards and it is invisible in a screenshot: a key the host OMITTED means
  // it never asked (no remote, no token, unreachable) and must draw nothing, while an EMPTY list means it
  // asked and the answer was none, which is worth a sentence. Flatten them and you either hide a real answer
  // or put a card on screen asking to be configured for a feature nobody requested.

  const withGit = (extra) => {
    win.cc.session({ git: Object.assign(clone(GIT), extra) });
    openView('git');
  };

  it('says nothing about a forge nobody configured', () => {
    withGit({});
    expect(panel().querySelector('[data-card="git-forge"]')).toBeNull();
    expect(panel().querySelector('[data-card="git-topology"]')).toBeNull();
  });

  it('an empty pull-request list is an answer, and says so', () => {
    withGit({ pullRequests: [] });
    const card = panel().querySelector('[data-card="git-forge"]');
    expect(card).toBeTruthy();
    expect(card.textContent).toContain('No open pull requests');
  });

  it('draws each pull request and the last run, opening them through the host', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    withGit({
      pullRequests: [{ number: 7, title: 'Add the thing', url: 'https://example/pr/7', draft: true }],
      lastRun: { name: 'CI', status: 'failed', url: 'https://example/run/1', finishedAt: null },
    });
    const card = panel().querySelector('[data-card="git-forge"]');
    expect(card.textContent).toContain('#7');
    expect(card.textContent).toContain('Add the thing');
    expect(card.textContent).toContain('draft');
    // The status word is painted, never derived — and it is one of the page's own four.
    expect(card.querySelector('.git-dot.failed')).toBeTruthy();

    click(card.querySelector('.git-link'));
    expect(sent.filter((m) => m.type === 'open').map((m) => m.url)).toContain('https://example/run/1');
  });

  it('omits a count it was not given rather than drawing a zero', () => {
    // `ahead: null` means the count could not be read. A `0` there reads as "in sync", which is precisely the
    // answer nobody has — so the row is absent instead.
    withGit({
      topology: { branch: 'work', upstream: 'origin/work', ahead: null, behind: 2, mergeBase: null },
    });
    const card = panel().querySelector('[data-card="git-topology"]');
    expect(card.textContent).toContain('origin/work');
    expect(card.textContent).toContain('Behind');
    expect(card.textContent).not.toContain('Ahead');
  });

  it('a branch that tracks nothing has no card', () => {
    withGit({ topology: { branch: 'work', upstream: null, ahead: null, behind: null, mergeBase: null } });
    expect(panel().querySelector('[data-card="git-topology"]')).toBeNull();
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
