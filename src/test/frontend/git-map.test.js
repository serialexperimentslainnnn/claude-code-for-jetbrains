// The Git view's commit graph: the lane assignment underneath it, and the list it is drawn into.
//
// Four classes of rule are pinned here, and each one is a thing that would otherwise regress in silence:
//  - the LANE ASSIGNMENT is arithmetic, and it is tested as arithmetic (`CC.dash.gitLanes`) rather than
//    through the pixel positions it becomes: the FIRST parent continues its lane, a merge's other parents
//    open lanes of their own, a lane stops being reserved the moment the commit it waited for is drawn, and a
//    parent outside the window reserves nothing at all. Get any of those wrong and the result is still a
//    picture — just of a history the repository does not have;
//  - the graph is drawn from REAL topology or not at all: one row per commit the payload carries, one stroke
//    per link it actually names, and never a line to a commit that is not there. An invented fork is
//    indistinguishable on screen from a real one, which is why it has to be impossible to draw;
//  - what is cut is SAID. A truncated graph presented as complete is the same defect as an invented one, and
//    the note is the only thing standing between the two;
//  - colour carries nothing on its own (WCAG 1.4.1). The branch is a text tag on its row, a merge says the
//    word, which ref is checked out is programmatic (`aria-current`), and the drawing itself is `aria-hidden`
//    because every relationship in it is already in the row's own text.
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
    // Points at a commit older than the window: it must not become a tag that names a row nobody can see, and
    // it must be counted out loud.
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
  /** The same entries the card builds, minus the DOM: what the pure assignment is given. */
  const entriesOf = (commits) => commits.map((c) => ({ hash: c.hash, parents: c.parents, commit: c }));

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    win.cc.session({ git: clone(GIT) });
  });

  // ── the lane assignment, as arithmetic ───────────────────────────────────────────────────────────────

  it('keeps the mainline straight: the first parent continues the lane', () => {
    const model = lanes(entriesOf(GIT.commits));
    const laneOf = (hash) => model.rows.filter((r) => r.hash === hash)[0].lane;
    // The merge, its FIRST parent, and that one's parent all share a lane; only the branch that was merged
    // in is pushed aside. Without that rule the mainline wanders into whichever slot happened to be free and
    // the picture stops being readable at exactly the moment there is something to read.
    expect(laneOf(C4)).toBe(0);
    expect(laneOf(C3)).toBe(0);
    expect(laneOf(C2A)).toBe(0);
    expect(laneOf(C2B)).toBe(1);
    // Two lines of development, so two lanes — never one per commit and never one left over.
    expect(model.lanes).toBe(2);
  });

  it('a merge books a lane for every other parent, and says which lanes leave its dot', () => {
    const model = lanes(entriesOf(GIT.commits));
    const merge = model.rows[0];
    expect(merge.merge).toBe(true);
    // Its own lane continues to the first parent; the second parent opens the lane the side branch is drawn
    // in. Both are `down`, which is what makes the row draw two lines out of one dot.
    expect(merge.down).toEqual([0, 1]);
    expect(merge.up).toEqual([]); // nothing points at the newest commit
    expect(merge.through).toEqual([]);
  });

  it('a fork ends every lane that was waiting for it, and frees the ones it did not take', () => {
    const model = lanes(entriesOf(GIT.commits));
    const root = model.rows[4];
    // Both branches lead back to c1: two lanes arrive and terminate at this one dot. `up` is what draws that.
    expect(root.hash).toBe(C1);
    expect(root.up).toEqual([0, 1]);
    expect(root.down).toEqual([]); // a root has no parents, so nothing leaves it
    // …and the lane it did not take is released rather than left reserved for a commit already drawn. Left
    // reserved, it would step every later line one lane further right for the rest of the graph.
    expect(model.lanes).toBe(2);
  });

  it('reuses a freed lane rather than growing the graph forever', () => {
    // A line that ends (`c` is a root), then an unrelated older line. The second one belongs in lane 0: the
    // first has finished with it. A graph that never reuses a lane is one whose gutter grows without bound.
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
    // No lane is held open for a commit this window cannot show, and no edge leaves the dot towards one: a
    // line trailing off the row would say "continues" in exactly the same ink as a line that ends.
    expect(model.rows[0].down).toEqual([]);
    expect(model.rows[1].down).toEqual([]);
    expect(model.lanes).toBe(1);
  });

  it('lays out every entry exactly once, in the order it was given', () => {
    const model = lanes(entriesOf(GIT.commits));
    expect(model.rows.map((r) => r.hash)).toEqual([C4, C3, C2B, C2A, C1]);
    expect(model.rows.map((r) => r.index)).toEqual([0, 1, 2, 3, 4]);
  });

  // ── the list it is drawn into ────────────────────────────────────────────────────────────────────────

  it('draws one row per commit, and no second card competing with it', () => {
    openGit();
    expect(history()).not.toBeNull();
    expect(history().querySelectorAll('.git-commit').length).toBe(5);
    // The horizontal branch map is gone, not hidden: one history, one picture of it. Two cards drawing the
    // same commits from two payload readings is how they come to disagree.
    expect(panel().querySelector('[data-card="git-map"]')).toBeNull();
  });

  it('draws one stroke per line crossing a row, and nothing for a link the payload does not carry', () => {
    openGit();
    const strokes = (hash) => row(hash).querySelectorAll('.git-edge').length;
    // The merge: its own lane down to the first parent, plus the branch it absorbed. Nothing arrives from
    // above — it is the newest commit.
    expect(strokes(C4)).toBe(2);
    // A commit on the mainline while the side branch is open: in, out, and the side lane passing by.
    expect(strokes(C3)).toBe(3);
    // The root: both lanes arrive and end here, and nothing continues past it.
    expect(strokes(C1)).toBe(2);
  });

  it('hides the drawing from assistive technology, since every row states its own relationships', () => {
    openGit();
    expect(row(C4).querySelector('.git-graph').getAttribute('aria-hidden')).toBe('true');
    // The lane is a colour, and the palette repeats: `data-lane` is the SLOT, which is all the stylesheet can
    // reasonably know. It is decoration, and nothing depends on it being unique.
    expect(row(C2B).querySelector('.git-dot-node').getAttribute('data-lane')).toBe('1');
  });

  it('says "merge" in words, because a wider dot is no more audible than a colour', () => {
    openGit();
    // WCAG 1.4.1: the drawing marks a merge by shape and the checked-out commit by hue, and neither survives
    // forced colours or a screen reader. The subject of a merge does not always say so either.
    expect(row(C4).querySelector('.git-merge').textContent).toBe('merge');
    expect(row(C1).querySelector('.git-merge')).toBeNull();
  });

  it('names each branch on the row it points at, and only where it can point', () => {
    openGit();
    expect(ref(C4, 'main').querySelector('.git-ref-name').textContent).toBe('main');
    expect(ref(C3, 'origin/main').querySelector('.git-ref-kind').textContent).toBe('remote');
    // Which one you are on is an attribute, not the accent alone (1.4.1 / 4.1.2), and "HEAD" is the word for
    // everyone the accent does not reach.
    expect(ref(C4, 'main').getAttribute('aria-current')).toBe('true');
    expect(ref(C4, 'main').querySelector('.git-ref-kind').textContent).toBe('HEAD');
    expect(ref(C3, 'origin/main').getAttribute('aria-current')).toBeNull();
    // `archive/2024` sits on a commit outside the window: there is no row to hang it on, so it is not drawn.
    expect(history().querySelectorAll('.git-ref').length).toBe(2);
  });

  it('a branch tag opens the platform switcher — the same entry the header chip fires', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openGit();
    const tag = ref(C4, 'main');
    expect(tag.tagName).toBe('BUTTON');
    // 2.5.3 Label in Name: the whole visible label is inside the spoken one, so voice control can activate it
    // by saying what it reads.
    expect(tag.getAttribute('aria-label')).toBe('main HEAD — switch branch');
    click(tag);
    // ONE catalogue id, never an invented "check out this branch": the page holds no branch-scoped action and
    // the plugin runs no checkout.
    expect(sent.pop()).toEqual({ type: 'gitAction', id: 'branches' });
  });

  it('falls back to plain text when the host is not offering the switcher', () => {
    const noAction = clone(GIT);
    noAction.actions = [];
    win.cc.session({ git: noAction });
    openGit();
    // A button firing an id the catalogue lookup will miss is the one control on the page that cannot work,
    // and it fails silently — the warning goes to idea.log where nobody is looking.
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
    // It goes through the same lane assignment as everything else, with HEAD's commit as its parent — so it
    // is drawn ON the line it will actually land on instead of floating above the graph.
    expect(nodes[0].querySelectorAll('.git-edge').length).toBe(1);
    expect(row(C4).querySelectorAll('.git-edge').length).toBe(3); // …and that line now arrives from above
  });

  it('names the window it is showing and counts the branches outside it', () => {
    openGit();
    const note = history().querySelector('.git-note').textContent;
    // Always states the window, even when nothing was truncated: a complete-looking picture read as the whole
    // history is exactly the misreading this card must not cause. "every branch" is a statement about the
    // data — the host walks every ref — and it is why a commit made on another branch can appear here.
    expect(note).toContain('Showing the newest 5 commits across every branch.');
    expect(note).toContain('1 branch points outside this window.');
  });

  it('says so when the window is too short to reach a branch point, instead of just looking flat', () => {
    const flat = clone(GIT);
    // Two commits of one line, with the fork and every other tip older than the window. This is the shape a
    // release branch that has been linear for longer than the limit produces — and on screen it is exactly
    // what a broken graph looks like, so the card has to say which of the two it is.
    flat.commits = [commit(C3, 'Third on main', [C2A]), commit(C2A, 'Second on main', [C1])];
    win.cc.session({ git: flat });
    openGit();
    const note = history().querySelector('.git-note').textContent;
    expect(history().querySelectorAll('.git-commit').length).toBe(2);
    expect(note).toContain('2 branches point outside this window.');
    expect(note).toContain('Every commit here is on one line; no branch point falls inside this window.');
    // …and it is only said when it is true: the full fixture has a fork on screen and needs no excuse for it.
    win.cc.session({ git: clone(GIT) });
    expect(history().querySelector('.git-note').textContent).not.toContain('on one line');
  });

  it('says when a line continues past the oldest commit drawn, rather than drawing it', () => {
    const shorter = clone(GIT);
    shorter.commits = shorter.commits.slice(0, 3); // c1 is gone; its children keep their parent links
    win.cc.session({ git: shorter });
    openGit();
    expect(history().querySelector('.git-note').textContent).toContain(
      'Lines continuing past the oldest commit shown have no edge drawn.'
    );
  });

  // ── the stylesheet the drawing depends on ────────────────────────────────────────────────────────────

  it('declares the vertical rhythm the graph is drawn on', () => {
    const css = readCss();
    // No gap BETWEEN rows, and it is not a density preference: each row draws its own slice of the lanes
    // inside its own gutter, so a gap is space no SVG covers — it dashes every lane with a hole at each
    // commit. The separation is the body's padding, which the gutter stretches over.
    expect(/\.git-rail\s*\{[^}]*\}/.exec(css)[0]).toMatch(/gap:\s*0\b/);
    expect(/\.git-body\s*\{[^}]*\}/.exec(css)[0]).toMatch(/padding:\s*6px 0/);
    // The dot sits at the row's middle whatever the row's height turns out to be…
    expect(/\.git-dot-node\s*\{[^}]*\}/.exec(css)[0]).toMatch(/top:\s*50%/);
    // …which only holds because the drawing is STRETCHED to the row rather than sized in pixels, and the
    // viewBox's own midpoint is therefore the same place. Shorter rows are the case that would break it.
    openGit();
    const svg = row(C3).querySelector('.git-graph');
    expect(svg.getAttribute('preserveAspectRatio')).toBe('none');
    expect(svg.getAttribute('viewBox')).toMatch(/ 100$/);
  });

  it('keeps the per-commit actions reachable by keyboard, and off the row they hang from', () => {
    const css = readCss();
    const strip = /\.git-commit-actions\s*\{[^}]*\}/.exec(css)[0];
    // OUT OF FLOW: the row is exactly as tall hovered as not, so the list cannot shift under the pointer and
    // the commit you were about to press cannot move. Faded is not free — as a line of its own this strip was
    // the tallest thing in a two-line row, on every commit at once.
    expect(strip).toMatch(/position:\s*absolute/);
    // Hidden by OPACITY only. `display: none` and `visibility: hidden` take a control out of the tab order,
    // and one that exists only under a pointer is a WCAG 2.1.1 failure that nothing on screen reports.
    expect(strip).toMatch(/opacity:\s*0/);
    expect(strip).not.toMatch(/display:\s*none/);
    expect(strip).not.toMatch(/visibility:\s*hidden/);
    // …so Tab has to bring it back — `:hover` alone strands it for anyone without a mouse — but keyed on the
    // STRIP and never on the row. A row-level reveal paints the strip across that row's own branch tag at the
    // exact moment the tag takes focus, in any tool window narrow enough for the labels to reach it: SC 2.4.11
    // (Focus Not Obscured), with the obscured thing being the control the user has just tabbed onto. Keyed on
    // the strip, the only focus that can summon it is focus already inside it, and that button paints on top.
    expect(css).toMatch(/\.git-commit-actions:focus-within/);
    expect(css).not.toMatch(/\.git-commit:focus-within\s+\.git-commit-actions/);
    // The row itself still lights up on focus. That is the affordance, and it covers nothing.
    expect(css).toMatch(/\.git-commit:focus-within\s*\{/);

    openGit();
    const buttons = Array.from(row(C3).querySelectorAll('button'));
    expect(buttons.every((b) => b.getAttribute('type') === 'button')).toBe(true);
    // Tab order is the row's reading order: what the row says first, then what can be done about it. The
    // strip is the last thing in the body and is painted at that row's right-hand end.
    expect(buttons[0].classList.contains('git-ref')).toBe(true);
    expect(buttons[buttons.length - 1].classList.contains('git-commit-action')).toBe(true);
    expect(row(C3).querySelector('.git-body').lastElementChild.classList.contains('git-commit-actions')).toBe(
      true
    );
  });

  it('gives a branch tag a target no smaller than WCAG 2.2 SC 2.5.8 allows', () => {
    const css = readCss();
    // It is a control sitting inside a line of running text, which is exactly where the 24px floor gets
    // quietly traded away for a tidier row.
    const rule = /\.git-ref\s*\{[^}]*\}/.exec(css);
    expect(rule).not.toBeNull();
    expect(rule[0]).toMatch(/min-height:\s*24px/);
    expect(rule[0]).toMatch(/min-width:\s*24px/);
  });

  it('declares one colour per palette slot the layout can hand it', () => {
    const css = readCss();
    // The page emits `data-lane` modulo the palette size. A slot with no rule is a lane drawn in the fallback
    // grey, which reads as "these commits are unrelated" — and nothing in a DOM assertion would notice.
    for (let slot = 0; slot < 6; slot += 1) {
      expect(css).toContain(".git-gutter [data-lane='" + slot + "']");
    }
    // …and the strokes are kept at their declared weight through the row's vertical stretch. Without this the
    // graph thins to invisibility on a tall row, which is a contrast failure that only appears with data.
    expect(/\.git-edge\s*\{[^}]*\}/.exec(css)[0]).toMatch(/vector-effect:\s*non-scaling-stroke/);
  });
});
