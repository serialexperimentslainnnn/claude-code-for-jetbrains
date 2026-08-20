const { loadFrontend, readCss } = require('./helpers/load');

const C1 = '1111111111111111111111111111111111111111';
const C2A = '2222222222222222222222222222222222222222';
const C2B = '3333333333333333333333333333333333333333';
const C3 = '4444444444444444444444444444444444444444';
const C4 = '5555555555555555555555555555555555555555';
const AWAY = '9999999999999999999999999999999999999999';

const commit = (hash, subject, parents) => ({
  hash: hash,
  short: hash.slice(0, 7),
  subject: subject,
  author: 'Lain',
  ageMillis: 3600 * 1000,
  files: 2,
  parents: parents,
});

const GIT = {
  available: true,
  repo: { present: true, branch: 'main', head: C4, root: 'proj' },
  changes: [],
  commits: [
    commit(C4, "Merge branch 'side'", [C3, C2B]),
    commit(C3, 'Third on main', [C2A]),
    commit(C2B, 'Only on side', [C1]),
    commit(C2A, 'Second on main', [C1]),
    commit(C1, 'Root commit', []),
  ],
  refs: [
    { name: 'main', kind: 'local', hash: C4, short: C4.slice(0, 7), current: true },
    { name: 'origin/main', kind: 'remote', hash: C3, short: C3.slice(0, 7), current: false },
    { name: 'archive/2024', kind: 'local', hash: AWAY, short: AWAY.slice(0, 7), current: false },
  ],
  actions: [
    {
      id: 'branches',
      label: 'Branches',
      hint: 'Switch, create or compare branches',
      kind: 'ide',
      group: 'IDE actions',
      status: null,
    },
  ],
  commitActions: [
    { id: 'commitDiff', label: 'View diff', hint: null },
    { id: 'commitCopyHash', label: 'Copy hash', hint: null },
  ],
};

const clone = (o) => JSON.parse(JSON.stringify(o));

describe('git commit graph', () => {
  let win;

  const panel = () => win.document.querySelector('.dashboard');
  const history = () => panel().querySelector('[data-card="git-history"]');
  const openGit = () => {
    const btn = win.document.querySelector('.dash-toggle[data-view="git"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  };
  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const row = (hash) => history().querySelector('.git-commit[data-hash="' + hash + '"]');
  const ref = (hash, name) => row(hash).querySelector('.git-ref[data-ref="' + name + '"]');
  const lanes = (entries) => win.CC.dash.gitLanes(entries);
  const entriesOf = (commits) => commits.map((c) => ({ hash: c.hash, parents: c.parents, commit: c }));

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    win.cc.session({ git: clone(GIT) });
  });

  it('keeps the mainline straight: the first parent continues the lane', () => {
    const model = lanes(entriesOf(GIT.commits));
    const laneOf = (hash) => model.rows.filter((r) => r.hash === hash)[0].lane;
    expect(laneOf(C4)).toBe(0);
    expect(laneOf(C3)).toBe(0);
    expect(laneOf(C2A)).toBe(0);
    expect(laneOf(C2B)).toBe(1);
    expect(model.lanes).toBe(2);
  });

  it('a merge books a lane for every other parent, and says which lanes leave its dot', () => {
    const model = lanes(entriesOf(GIT.commits));
    const merge = model.rows[0];
    expect(merge.merge).toBe(true);
    expect(merge.down).toEqual([0, 1]);
    expect(merge.up).toEqual([]);
    expect(merge.through).toEqual([]);
  });

  it('a fork ends every lane that was waiting for it, and frees the ones it did not take', () => {
    const model = lanes(entriesOf(GIT.commits));
    const root = model.rows[4];
    expect(root.hash).toBe(C1);
    expect(root.up).toEqual([0, 1]);
    expect(root.down).toEqual([]);
    expect(model.lanes).toBe(2);
  });

  it('reuses a freed lane rather than growing the graph forever', () => {
    const model = lanes([
      { hash: 'a', parents: ['b'] },
      { hash: 'b', parents: ['c'] },
      { hash: 'c', parents: [] },
      { hash: 'd', parents: ['e'] },
      { hash: 'e', parents: [] },
    ]);
    expect(model.rows.map((r) => r.lane)).toEqual([0, 0, 0, 0, 0]);
    expect(model.lanes).toBe(1);
  });

  it('a parent outside the window reserves nothing, because it can never arrive to claim it', () => {
    const model = lanes([
      { hash: 'a', parents: ['gone'] },
      { hash: 'b', parents: ['also-gone'] },
    ]);
    expect(model.rows[0].down).toEqual([]);
    expect(model.rows[1].down).toEqual([]);
    expect(model.lanes).toBe(1);
  });

  it('lays out every entry exactly once, in the order it was given', () => {
    const model = lanes(entriesOf(GIT.commits));
    expect(model.rows.map((r) => r.hash)).toEqual([C4, C3, C2B, C2A, C1]);
    expect(model.rows.map((r) => r.index)).toEqual([0, 1, 2, 3, 4]);
  });

  it('draws one row per commit, and no second card competing with it', () => {
    openGit();
    expect(history()).not.toBeNull();
    expect(history().querySelectorAll('.git-commit').length).toBe(5);
    expect(panel().querySelector('[data-card="git-map"]')).toBeNull();
  });

  it('draws one stroke per line crossing a row, and nothing for a link the payload does not carry', () => {
    openGit();
    const strokes = (hash) => row(hash).querySelectorAll('.git-edge').length;
    expect(strokes(C4)).toBe(2);
    expect(strokes(C3)).toBe(3);
    expect(strokes(C1)).toBe(2);
  });

  it('hides the drawing from assistive technology, since every row states its own relationships', () => {
    openGit();
    expect(row(C4).querySelector('.git-graph').getAttribute('aria-hidden')).toBe('true');
    expect(row(C2B).querySelector('.git-dot-node').getAttribute('data-lane')).toBe('1');
  });

  it('says "merge" in words, because a wider dot is no more audible than a colour', () => {
    openGit();
    expect(row(C4).querySelector('.git-merge').textContent).toBe('merge');
    expect(row(C1).querySelector('.git-merge')).toBeNull();
  });

  it('names each branch on the row it points at, and only where it can point', () => {
    openGit();
    expect(ref(C4, 'main').querySelector('.git-ref-name').textContent).toBe('main');
    expect(ref(C3, 'origin/main').querySelector('.git-ref-kind').textContent).toBe('remote');
    expect(ref(C4, 'main').getAttribute('aria-current')).toBe('true');
    expect(ref(C4, 'main').querySelector('.git-ref-kind').textContent).toBe('HEAD');
    expect(ref(C3, 'origin/main').getAttribute('aria-current')).toBeNull();
    expect(history().querySelectorAll('.git-ref').length).toBe(2);
  });

  it('a branch tag opens the platform switcher — the same entry the header chip fires', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openGit();
    const tag = ref(C4, 'main');
    expect(tag.tagName).toBe('BUTTON');
    expect(tag.getAttribute('aria-label')).toBe('main HEAD — switch branch');
    click(tag);
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'branches' });
  });

  it('falls back to plain text when the host is not offering the switcher', () => {
    const noAction = clone(GIT);
    noAction.actions = [];
    win.cc.session({ git: noAction });
    openGit();
    expect(ref(C4, 'main')).toBeNull();
    expect(history().querySelector('.git-ref').tagName).toBe('SPAN');
  });

  it('hangs the working copy off the commit HEAD is standing on', () => {
    const dirty = clone(GIT);
    dirty.changes = ['README.md'];
    win.cc.session({ git: dirty });
    openGit();
    const nodes = Array.from(history().querySelectorAll('.git-node'));
    expect(nodes[0].classList.contains('git-wip')).toBe(true);
    expect(nodes[0].querySelectorAll('.git-edge').length).toBe(1);
    expect(row(C4).querySelectorAll('.git-edge').length).toBe(3);
  });

  it('names the window it is showing and counts the branches outside it', () => {
    openGit();
    const note = history().querySelector('.git-note').textContent;
    expect(note).toContain('Showing the newest 5 commits across every branch.');
    expect(note).toContain('1 branch points outside this window.');
  });

  it('says so when the window is too short to reach a branch point, instead of just looking flat', () => {
    const flat = clone(GIT);
    flat.commits = [commit(C3, 'Third on main', [C2A]), commit(C2A, 'Second on main', [C1])];
    win.cc.session({ git: flat });
    openGit();
    const note = history().querySelector('.git-note').textContent;
    expect(history().querySelectorAll('.git-commit').length).toBe(2);
    expect(note).toContain('2 branches point outside this window.');
    expect(note).toContain('Every commit here is on one line; no branch point falls inside this window.');
    win.cc.session({ git: clone(GIT) });
    expect(history().querySelector('.git-note').textContent).not.toContain('on one line');
  });

  it('says when a line continues past the oldest commit drawn, rather than drawing it', () => {
    const shorter = clone(GIT);
    shorter.commits = shorter.commits.slice(0, 3);
    win.cc.session({ git: shorter });
    openGit();
    expect(history().querySelector('.git-note').textContent).toContain(
      'Lines continuing past the oldest commit shown have no edge drawn.'
    );
  });

  it('declares the vertical rhythm the graph is drawn on', () => {
    const css = readCss();
    expect(/\.git-rail\s*\{[^}]*\}/.exec(css)[0]).toMatch(/gap:\s*0\b/);
    expect(/\.git-body\s*\{[^}]*\}/.exec(css)[0]).toMatch(/padding:\s*6px 0/);
    expect(/\.git-dot-node\s*\{[^}]*\}/.exec(css)[0]).toMatch(/top:\s*50%/);
    openGit();
    const svg = row(C3).querySelector('.git-graph');
    expect(svg.getAttribute('preserveAspectRatio')).toBe('none');
    expect(svg.getAttribute('viewBox')).toMatch(/ 100$/);
  });

  it('stretches the graph to the row instead of letting the viewBox size it', () => {
    const graph = /\.git-graph\s*\{[^}]*\}/.exec(readCss())[0];

    // An <svg> is a replaced element. With width/height auto, `inset: 0` leaves the aspect ratio of
    // the viewBox to decide, which is a flat 100px however tall the row is — so every row taller
    // than that (uncommitted changes with its files, a commit with several ref tags) drew an edge
    // that stopped short of the next dot, and the dot itself drifted off the junction.
    expect(graph).toMatch(/width:\s*100%/);
    expect(graph).toMatch(/height:\s*100%/);
  });

  it('keeps the per-commit actions reachable by keyboard, and off the row they hang from', () => {
    const css = readCss();
    const strip = /\.git-commit-actions\s*\{[^}]*\}/.exec(css)[0];
    expect(strip).toMatch(/position:\s*absolute/);
    expect(strip).toMatch(/opacity:\s*0/);
    expect(strip).not.toMatch(/display:\s*none/);
    expect(strip).not.toMatch(/visibility:\s*hidden/);
    expect(css).toMatch(/\.git-commit-actions:focus-within/);
    expect(css).not.toMatch(/\.git-commit:focus-within\s+\.git-commit-actions/);
    expect(css).toMatch(/\.git-commit:focus-within\s*\{/);

    openGit();
    const buttons = Array.from(row(C3).querySelectorAll('button'));
    expect(buttons.every((b) => b.getAttribute('type') === 'button')).toBe(true);
    expect(buttons[0].classList.contains('git-ref')).toBe(true);
    expect(buttons[buttons.length - 1].classList.contains('git-commit-action')).toBe(true);
    expect(row(C3).querySelector('.git-body').lastElementChild.classList.contains('git-commit-actions')).toBe(
      true
    );
  });

  it('gives a branch tag a target no smaller than WCAG 2.2 SC 2.5.8 allows', () => {
    const css = readCss();
    const rule = /\.git-ref\s*\{[^}]*\}/.exec(css);
    expect(rule).not.toBeNull();
    expect(rule[0]).toMatch(/min-height:\s*24px/);
    expect(rule[0]).toMatch(/min-width:\s*24px/);
  });

  it('declares one colour per palette slot the layout can hand it', () => {
    const css = readCss();
    for (let slot = 0; slot < 6; slot += 1) {
      expect(css).toContain(".git-gutter [data-lane='" + slot + "']");
    }
    expect(/\.git-edge\s*\{[^}]*\}/.exec(css)[0]).toMatch(/vector-effect:\s*non-scaling-stroke/);
  });
});
