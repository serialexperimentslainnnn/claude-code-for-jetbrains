// The Git view's branch map: the horizontal graph, and the two controls that make a branch something you can
// act on rather than a word on a card.
//
// Four classes of rule are pinned here, and each one is a thing that would otherwise regress in silence:
//  - the map is drawn from REAL topology or not at all — one node per commit, one edge per parent link the
//    payload actually carries, no card when the host sent no parents and none when there is a single commit.
//    This is the founding rule of `app-session-git.js`: an invented fork is indistinguishable on screen from
//    a real one, so it must be impossible to draw;
//  - what is cut is SAID. A truncated map presented as complete is the same defect as an invented one, and
//    the note is the only thing standing between the two;
//  - a commit and a branch can both be selected and both offer something to do, with the state carried
//    programmatically (`aria-pressed`, `aria-current`) and not by colour alone — WCAG 1.4.1 and 4.1.2 — and
//    the merge and checked-out markers said in words for the same reason;
//  - the branch is a real control. It was dead text for a release while the button that changes it sat four
//    cards down under a heading named after who runs it, which is not where anyone scans for "switch branch".
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

/**
 * A repository with a real fork and a real merge, so the lane assignment has something to be right about:
 *
 *   c1 ── c2a ── c3 ──┐
 *     └─── c2b ───────┴── c4 (merge, on `main`)
 */
const GIT = {
  available: true,
  repo: { present: true, branch: 'main', head: C4.slice(0, 7), root: 'proj' },
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
    // Points at a commit older than the window: it must not become a button that goes nowhere, and it must
    // be counted out loud.
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

describe('git branch map', () => {
  let win;

  const panel = () => win.document.querySelector('.dashboard');
  const map = () => panel().querySelector('[data-card="git-map"]');
  const openGit = () => {
    const btn = win.document.querySelector('.dash-toggle[data-view="git"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  };
  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const node = (hash) => map().querySelector('.git-map-node[data-hash="' + hash + '"]');
  const ref = (name) => map().querySelector('.git-map-ref[data-ref="' + name + '"]');

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    win.cc.session({ git: clone(GIT) });
  });

  // ── drawn from real topology, or not drawn ───────────────────────────────────────────────────────────

  it('draws one node per commit and one edge per parent link the payload carries', () => {
    openGit();
    expect(map()).not.toBeNull();
    expect(map().querySelectorAll('.git-map-node').length).toBe(5);
    // Five links: the merge has two parents, the three middle commits one each, and the root has none. A
    // sixth edge would mean one was invented; a fourth, that a real one was dropped.
    expect(map().querySelectorAll('.dg-edge').length).toBe(5);
  });

  it('puts the two lines of development in two lanes, and keeps the mainline straight', () => {
    openGit();
    const top = (hash) => parseFloat(node(hash).style.top);
    const left = (hash) => parseFloat(node(hash).style.left);
    // The FIRST parent continues its lane, so the merge, its first parent and that one's parent all share a
    // row; only the side branch is pushed down. Without that rule the mainline wanders into whichever slot
    // was free and the picture stops being readable.
    expect(top(C4)).toBe(top(C3));
    expect(top(C3)).toBe(top(C2A));
    expect(top(C2B)).toBeGreaterThan(top(C4));
    // Time runs left to right: the root is leftmost and the newest commit is rightmost.
    expect(left(C1)).toBeLessThan(left(C2A));
    expect(left(C2A)).toBeLessThan(left(C4));
  });

  it('draws no map when the host sent no parents — an unconnected row of dots is not a history', () => {
    const flat = clone(GIT);
    flat.commits.forEach((c) => {
      c.parents = [];
    });
    win.cc.session({ git: flat });
    openGit();
    // The whole point of the card: with no topology there is nothing honest to draw, so it is absent rather
    // than present and empty. The history rail is unaffected and still lists the commits.
    expect(map()).toBeNull();
    expect(panel().querySelectorAll('.git-commit').length).toBe(5);
  });

  it('draws no map for a single commit, which has no relationship to show', () => {
    const one = clone(GIT);
    one.commits = [commit(C1, 'Root commit', [])];
    win.cc.session({ git: one });
    openGit();
    expect(map()).toBeNull();
  });

  // ── it says what it cut ──────────────────────────────────────────────────────────────────────────────

  it('names the window it is showing and counts the branches outside it', () => {
    openGit();
    // Every note in the card, not the first: the card carries two — the hint about selecting a node, and
    // this one — and picking `querySelector` would silently assert against whichever happens to come first.
    const notes = Array.from(map().querySelectorAll('.git-note'))
      .map((el) => el.textContent)
      .join(' ');
    // Always states the window, even when nothing was truncated: a complete-looking picture read as the
    // whole history is exactly the misreading this card must not cause.
    expect(notes).toContain('Showing the newest 5 commits.');
    expect(notes).toContain('1 branch points outside this window.');
  });

  it('lists only the branches it can actually point at', () => {
    openGit();
    // `archive/2024` sits on a commit outside the window, so a button for it could only fail to go anywhere.
    // It is counted in the note instead; the header's branch chip still reaches the full list.
    expect(Array.from(map().querySelectorAll('.git-map-ref-name')).map((el) => el.textContent)).toEqual([
      'main',
      'origin/main',
    ]);
  });

  // ── selection, and what it offers ────────────────────────────────────────────────────────────────────

  it('a commit node is a real button whose name carries what the drawing says in shape and colour', () => {
    openGit();
    const merge = node(C4);
    expect(merge.tagName).toBe('BUTTON');
    expect(merge.getAttribute('type')).toBe('button');
    // 1.4.1: the merge is drawn as a wider dot and the checked-out commit takes the accent. Neither survives
    // forced colours and neither is audible, so both are also words.
    expect(merge.getAttribute('aria-label')).toContain('merge of 2 parents');
    expect(merge.getAttribute('aria-label')).toContain('main (checked out)');
    expect(node(C1).getAttribute('aria-label')).not.toContain('merge');
  });

  it('selecting a commit shows what can be done there, and says so out loud', () => {
    const spoken = [];
    win.CC.announce = (m) => spoken.push(m);
    openGit();
    click(node(C3));

    // 4.1.2: the state is programmatic, not just a ring.
    expect(node(C3).getAttribute('aria-pressed')).toBe('true');
    expect(node(C4).getAttribute('aria-pressed')).toBe('false');
    const detail = map().querySelector('.git-map-detail');
    expect(detail.querySelector('.git-hash').textContent).toBe(C3.slice(0, 7));
    expect(detail.querySelector('.git-subject').textContent).toBe('Third on main');
    // The host's catalogue, not a list written in the page.
    expect(Array.from(detail.querySelectorAll('.git-commit-action')).map((b) => b.textContent)).toEqual([
      'View diff',
      'Copy hash',
    ]);
    // 4.1.3: the row appears without the focus moving, so nothing else would tell a screen-reader user.
    expect(spoken.some((m) => m.indexOf('Third on main') >= 0)).toBe(true);
  });

  it('a per-commit button sends the catalogue id with that row own hash', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openGit();
    click(node(C2B));
    click(map().querySelector('.git-map-detail .git-commit-action'));
    // The FULL hash, never the seven characters on screen: an abbreviation is unique only until it is not.
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'commitDiff', hash: C2B });
  });

  it('offers the branch switcher only where a branch actually is', () => {
    openGit();
    click(node(C1));
    const bare = map().querySelector('.git-map-detail');
    expect(bare.querySelector('.git-action')).toBeNull();
    click(node(C1)); // pressing the selected one again clears it
    click(node(C4));
    const tagged = map().querySelector('.git-map-detail');
    expect(tagged.querySelector('.git-action').textContent).toContain('Branches');
    expect(tagged.querySelector('.git-map-tag').textContent).toContain('main');
  });

  it('pressing a branch goes to its commit, and says which one you are on', () => {
    openGit();
    // 1.4.1 / 4.1.2 again: which branch is checked out is an attribute, and the kind is a word.
    expect(ref('main').getAttribute('aria-current')).toBe('true');
    expect(ref('origin/main').getAttribute('aria-current')).toBeNull();
    expect(ref('main').querySelector('.git-map-ref-kind').textContent).toBe('HEAD');
    expect(ref('origin/main').querySelector('.git-map-ref-kind').textContent).toBe('remote');

    click(ref('origin/main'));
    expect(node(C3).getAttribute('aria-pressed')).toBe('true');
    expect(ref('origin/main').getAttribute('aria-pressed')).toBe('true');
    expect(map().querySelector('.git-map-detail .git-subject').textContent).toBe('Third on main');
  });

  it('keeps the selection across a host repaint', () => {
    openGit();
    click(node(C3));
    // The dashboard rebuilds on every push, several times a turn. A selection held only in the elements is
    // thrown away by a repaint the user did not cause.
    win.cc.session({ git: clone(GIT) });
    expect(node(C3).getAttribute('aria-pressed')).toBe('true');
    expect(map().querySelector('.git-map-detail .git-subject').textContent).toBe('Third on main');
  });

  it('drops a selection that has fallen out of the window rather than describing a commit off screen', () => {
    openGit();
    click(node(C1));
    const shorter = clone(GIT);
    shorter.commits = shorter.commits.slice(0, 3); // C1 is gone; its children keep their parent links
    win.cc.session({ git: shorter });
    expect(map().querySelector('.git-map-detail').textContent).toContain('Select a commit');
  });

  // ── the branch in the header is a control ────────────────────────────────────────────────────────────

  it('the header branch is a button that opens the switcher, not a label', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openGit();
    const chip = panel().querySelector('.git-branch');
    expect(chip.tagName).toBe('BUTTON');
    // 2.5.3 Label in Name: the visible string is inside the spoken one, so voice control can activate it by
    // saying what it reads.
    expect(chip.textContent).toBe('main');
    expect(chip.getAttribute('aria-label')).toContain('main');
    click(chip);
    // One id, three doors — the same catalogue entry the action bar and the map fire. Never an invented
    // "switch to this branch" message: the page holds no branch-scoped action and the plugin runs no checkout.
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'branches' });
  });

  it('falls back to plain text when the host is not offering the switcher', () => {
    const noAction = clone(GIT);
    noAction.actions = [];
    win.cc.session({ git: noAction });
    openGit();
    // A button firing an id the catalogue lookup will miss is the one control on the page that cannot work,
    // and it fails silently — the warning goes to idea.log where nobody is looking.
    expect(panel().querySelector('.git-branch').tagName).toBe('SPAN');
  });

  // ── the drawing is decoration over content that is reachable without it ──────────────────────────────

  it('hides the connector layer from assistive technology, since every node states its own links', () => {
    openGit();
    expect(map().querySelector('.dg-edges').getAttribute('aria-hidden')).toBe('true');
  });

  it('gives every commit a target no smaller than WCAG 2.2 SC 2.5.8 allows', () => {
    const css = readCss();
    // The dot is 12px because that is all the drawing needs; the button is 24x24 because that is the floor.
    // Shrinking the button to the dot would look tidier and fail the criterion, which is why it is pinned
    // here rather than left to a comment.
    const rule = /\.git-map-node\s*\{[^}]*\}/.exec(css);
    expect(rule).not.toBeNull();
    expect(rule[0]).toMatch(/width:\s*24px/);
    expect(rule[0]).toMatch(/height:\s*24px/);
  });
});
