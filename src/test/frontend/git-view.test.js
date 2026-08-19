const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, readCss, JCEF } = require('./helpers/load');

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
    expect(panel().querySelector('.git-sha').textContent).toBe('8933592');
  });

  it('groups the actions by `group`, in the order the groups arrive', () => {
    openView('git');
    expect(texts('.git-group-name')).toEqual(['Repository', 'IDE actions', 'Ask Claude']);
    const first = panel().querySelector('.git-group .git-group-items');
    expect(Array.from(first.querySelectorAll('.git-action')).length).toBe(2);
  });

  it('an action is a real button and sends its own id', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const explain = Array.from(panel().querySelectorAll('.git-action')).find((b) =>
      b.textContent.includes('Explain the last commit')
    );
    expect(explain.tagName).toBe('BUTTON');
    expect(explain.getAttribute('type')).toBe('button');
    click(explain);
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'explain' });
  });

  it('paints the word the host sent for each state, never a colour alone', () => {
    openView('git');
    const chips = Array.from(panel().querySelectorAll('.git-status'));
    expect(chips.map((c) => c.textContent)).toEqual(['failed', 'running', 'completed']);
    expect(chips.map((c) => c.className)).toEqual([
      'git-status failed',
      'git-status running',
      'git-status completed',
    ]);
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
    next.actions[0].status = 'running';
    win.cc.session({ git: next });
    expect(spoken).toContain('Refresh — running');
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
    expect(texts('.git-commit .git-hash')).toEqual(['8933592', '6f781c5']);

    const clean = clone(GIT);
    clean.changes = [];
    win.cc.session({ git: clean });
    expect(panel().querySelectorAll('.git-wip').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit').length).toBe(2);
  });

  it('a commit says its hash, subject, author, age and file count', () => {
    openView('git');
    const first = panel().querySelector('.git-commit');
    expect(first.querySelector('.git-subject').textContent).toBe(
      'chore: invert .gitignore into an allowlist'
    );
    expect(first.querySelector('.git-meta').textContent).toBe('Lain · 3h ago · 3 files');
  });

  it('every commit row carries one button per action the payload offers', () => {
    openView('git');
    const rows = Array.from(panel().querySelectorAll('.git-commit'));
    for (const row of rows) {
      const buttons = Array.from(row.querySelectorAll('.git-commit-action'));
      expect(buttons.map((b) => b.textContent)).toEqual([
        'View diff',
        'Revert to this commit on a new branch',
        'Copy hash',
      ]);
      expect(buttons.map((b) => b.tagName)).toEqual(['BUTTON', 'BUTTON', 'BUTTON']);
      expect(buttons.every((b) => b.getAttribute('type') === 'button')).toBe(true);
      expect(buttons.every((b) => b.textContent.trim().length > 0)).toBe(true);
    }
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
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'commitRevertBranch', hash: '6f781c5aabb2' });
  });

  it('both bars send ONE message type, so neither can be a button the host has no parser for', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    sent.length = 0;
    click(Array.from(panel().querySelectorAll('.git-action')).find((b) => b.textContent.includes('Refresh')));
    click(panel().querySelector('.git-commit .git-commit-action'));

    expect(sent.map((m) => m.type)).toEqual(['gitAction', 'gitAction']);
    expect(sent[0].hash).toBeUndefined();
    expect(sent[1].hash).toBe('8933592ffee1');
  });

  it('offered no commit actions, a row carries no buttons rather than an empty row', () => {
    const bare = clone(GIT);
    delete bare.commitActions;
    win.cc.session({ git: bare });
    openView('git');
    expect(panel().querySelectorAll('.git-commit-action').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit-actions').length).toBe(0);
    expect(panel().querySelectorAll('.git-commit').length).toBe(2);

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
    const tabs = Array.from(panel().querySelector('.git-viewhead .git-viewtabs').children);
    expect(tabs.map((t) => t.textContent)).toEqual(['Overview', 'Chat']);
    expect(tabs.map((t) => t.tagName)).toEqual(['BUTTON', 'BUTTON']);
    expect(tabs.map((t) => t.getAttribute('type'))).toEqual(['button', 'button']);
    expect(tabs[0].getAttribute('aria-current')).toBe('true');
    expect(tabs[1].getAttribute('aria-current')).toBeNull();
    expect(panel().querySelector('[role="tablist"]')).toBeNull();
    const strip = tabs[0].closest('.git-viewtabs');
    expect(strip.getAttribute('role')).toBe('group');
  });

  it('the Chat tab switches the view to the conversation, and stays in the panel', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('git');
    const chat = Array.from(panel().querySelectorAll('.git-viewtab')).find((t) => t.textContent === 'Chat');

    click(chat);
    expect(sent.some((m) => m.type === 'gitChat')).toBe(false);
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(win.CC.dash.gitSubView()).toBe('chat');
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
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().querySelector('.git-rail')).not.toBeNull();
    expect(sent).toEqual([]);
  });

  it('the Git button appears only when the host reports a Git surface', () => {
    const gitBtn = () =>
      Array.from(win.document.querySelectorAll('.dash-toggles button')).find((b) => b.textContent === 'Git');
    expect(gitBtn().hidden).toBe(false);

    win.cc.session({ git: { available: false } });
    expect(gitBtn().hidden).toBe(true);
  });

  it('a Git tab opens ON the Git view, and comes back to it', () => {
    win.cc.meta({ gitIntegration: true });
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().querySelector('.git-rail')).not.toBeNull();

    const strip = () => panel().querySelector('.git-viewhead .git-viewtabs');
    const tab = (name) => Array.from(strip().children).find((t) => t.textContent === name);
    click(tab('Chat'));
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(panel().querySelector('.dash-inner').hidden).toBe(true);

    click(tab('Overview'));
    expect(panel().querySelector('.dash-inner').hidden).toBe(false);
    expect(panel().querySelector('.git-rail')).not.toBeNull();
  });

  it('no other tab changes behaviour: without the flag the chat stays on screen', () => {
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

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
    expect(card.querySelector('.git-dot.failed')).toBeTruthy();

    click(card.querySelector('.git-link'));
    expect(sent.filter((m) => m.type === 'open').map((m) => m.url)).toContain('https://example/run/1');
  });

  it('omits a count it was not given rather than drawing a zero', () => {
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
