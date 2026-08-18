// The composer readout: a zero is a measurement, an absence is not.
//
// These items used to be hidden until they were non-zero, which made a fresh tab show a lone "Idle" with no
// numbers — indistinguishable from a readout that failed to load. That ambiguity cost real debugging time, so
// the settled-at-zero behaviour is pinned here rather than left as a style someone tidies away.
const { loadFrontend, readCss } = require('./helpers/load');

// The stylesheet as the page sees it, comments stripped. jsdom lays nothing out and evaluates no media query,
// so `offsetWidth` is 0 everywhere and everything about columns, gaps and thresholds is only assertable as CSS
// TEXT. What the DOM can still answer — which elements exist, which classes they carry, what `aria-expanded`
// says — is asserted on the DOM. Neither half pretends to be the other.
const sheet = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

/** The body of the block opening at [from], brace-counted so a nested block is read whole, not to its
 *  first inner `}`. */
function bodyAt(from) {
  if (from < 0) throw new Error('no such block');
  let depth = 0;
  for (let i = sheet.indexOf('{', from); i < sheet.length; i++) {
    if (sheet[i] === '{') depth++;
    else if (sheet[i] === '}' && --depth === 0) return sheet.slice(sheet.indexOf('{', from) + 1, i);
  }
  throw new Error('unterminated block at ' + from);
}

/**
 * A rule's body, by exact selector.
 *
 * Anchored to the start of a line, and that is not tidiness: a selector is a SUFFIX of every longer one
 * ending in it, so an unanchored search for `.mini-fact {` finds a `.something > .mini-fact {` above it
 * and silently reads a different rule. The anchor also skips indented copies nested inside an `@media`,
 * so what comes back is always the unconditional rule.
 */
const rule = (selector) => bodyAt(sheet.indexOf('\n' + selector + ' {'));

/** The strip's one breakpoint, read whole — the fold and everything it re-parameterises live inside it. */
const foldMedia = () => bodyAt(sheet.indexOf('\n@media (max-width: 640px) {'));

describe('composer readout', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const readoutText = () => win.document.querySelector('.readout').textContent;

  it('the separators are decoration, and the digits do not shuffle', () => {
    // The counters tick several times a second. With proportional digits every item after a number shifts
    // sideways on each tick, which is what made four small metrics read as a busy line.
    expect(rule('.readout .ro-item')).toMatch(/font-variant-numeric:\s*tabular-nums/);
    // The separator is a pseudo-element ON PURPOSE: as markup it would be selectable, copyable, and counted
    // as a cell of the grid. And the alternative-text form is what keeps it out of the accessible name —
    // Chromium exposes generated content as static text, so a bare `content: '·'` is a bullet announced
    // between every metric of every row.
    expect(rule('.strip-cell::after')).toMatch(/content:\s*'·'\s*\/\s*''/);
    expect(readoutText()).not.toContain('·');
  });

  it('shows context, output and reasoning at 0 before any data arrives', () => {
    win.cc.state({ running: true, starting: false });
    const text = readoutText();
    expect(text).toContain('Context 0%');
    expect(text).toContain('0 out');
    expect(text).toContain('0 reasoning');
  });

  it('renders real values once they arrive', () => {
    win.cc.state({
      running: true,
      starting: false,
      context: { pct: 42 },
      tokensOut: 1500,
      reasoningTokens: 2400,
    });
    const text = readoutText();
    expect(text).toContain('Context 42%');
    expect(text).not.toContain('Context 0%');
    expect(text).toContain('reasoning');
  });

  it('keeps cost gated — a currency amount of zero is noise, not information', () => {
    win.cc.state({ running: true, starting: false });
    expect(readoutText()).not.toContain('$');
    win.cc.state({ running: true, starting: false, costUsd: 0.25 });
    expect(readoutText()).toContain('$0.25');
  });

  it('reports idle vs running honestly', () => {
    win.cc.state({ running: true, starting: false, turnActive: false });
    expect(readoutText()).toContain('Idle');
    win.cc.state({ running: true, starting: false, turnActive: true });
    expect(readoutText()).toContain('Running');
  });
});

// The plan-limit bars: their own row, under the readout.
//
// They were dots inline in the readout, i.e. at the end of a wrapping row of unrelated metrics — so the windows
// nearest their cap were the ones most likely to wrap out of sight. The separation is the point of the row, and
// it is what these pin: the bars are a SIBLING of .readout, not inside it.
describe('plan-limit bars', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const bars = () => win.document.querySelector('.usage-bars');
  const items = () => Array.from(bars().querySelectorAll('.ub-item'));
  const base = { running: true, starting: false };

  it('renders one labelled bar per window, outside the readout', () => {
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 13 },
        { key: 'seven_day', label: 'All models', pct: 9 },
        { key: 'model_scoped:Fable', label: 'Fable', pct: 71.25 },
      ],
    });
    expect(items()).toHaveLength(3);
    expect(items().map((el) => el.querySelector('.ub-label').textContent)).toEqual([
      'Current session',
      'All models',
      'Fable',
    ]);
    expect(items()[2].querySelector('.ub-pct').textContent).toBe('71.3%');
    // Sibling, not descendant: the readout must be able to grow without displacing them.
    expect(win.document.querySelector('.readout .ub-item')).toBeNull();
  });

  it('sets the fill width from the percentage and its colour from the level', () => {
    win.cc.state({
      ...base,
      usage: [
        { key: 'a', label: 'Low', pct: 10 },
        { key: 'b', label: 'Mid', pct: 70 },
        { key: 'c', label: 'High', pct: 90 },
      ],
    });
    const fills = items().map((el) => el.querySelector('.ub-track > i'));
    expect(fills.map((f) => f.style.width)).toEqual(['10%', '70%', '90%']);
    expect(fills.map((f) => f.className)).toEqual(['lvl-low', 'lvl-mid', 'lvl-high']);
  });

  it('clamps the BAR past 100% but never the number', () => {
    // A window the server reports over its cap is exactly the figure the user needs to read; what must not
    // happen is the fill overflowing its track.
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 103 }] });
    expect(items()[0].querySelector('.ub-track > i').style.width).toBe('100%');
    expect(items()[0].querySelector('.ub-pct').textContent).toBe('103.0%');
  });

  it('shows how long each window has left, compactly, with the sentence in the tooltip', () => {
    // A percentage alone does not say whether it is urgent: 90% with eight minutes to go and 90% with six
    // hours to go are different situations, and only the dashboard was answering that.
    const in90min = new Date(Date.now() + 90 * 60000).toISOString();
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 90, resetsAt: in90min },
        { key: 'seven_day', label: 'All models', pct: 9 },
      ],
    });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('Reset time: 1h 30m');
    // Its own line under the bar row, not a fourth item squeezed into it.
    expect(items()[0].querySelector('.ub-row .ub-reset')).toBeNull();
    expect(items()[0].querySelector('.ub-row .ub-pct')).not.toBeNull();
    expect(items()[0].getAttribute('title')).toContain('Resets in 1h 30m');
    // A window with no reset time says NOTHING. The element is emitted and left empty rather than filled
    // with a `—` or an `n/a`: a filler is a value, and the reading anyone would give it here is "resets
    // now". Empty, it produces no line box and no text, so it is absent both on screen and to a reader.
    expect(items()[1].querySelector('.ub-reset').textContent).toBe('');
    expect(items()[1].getAttribute('title')).not.toContain('Resets');
  });

  it('says "soon" once the reset time has passed rather than a negative countdown', () => {
    const past = new Date(Date.now() - 60000).toISOString();
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Over', pct: 100, resetsAt: past }] });
    expect(items()[0].querySelector('.ub-reset').textContent).toBe('Reset time: soon');
    expect(items()[0].getAttribute('title')).toContain('Resets shortly');
  });

  it('hides the row entirely when no window carries a percentage', () => {
    win.cc.state({ ...base });
    expect(bars().hasAttribute('hidden')).toBe(true);
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Unknown', pct: null }] });
    expect(bars().hasAttribute('hidden')).toBe(true);
    expect(items()).toHaveLength(0);
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Known', pct: 5 }] });
    expect(bars().hasAttribute('hidden')).toBe(false);
  });

  it('draws a window MEASURED at zero — a zero is a measurement, an absence is not', () => {
    // Reported as "the Fable indicator is gone". A per-model window sits at 0.0% for most of a week, so the
    // skip above (`typeof win.pct !== 'number'`) is the first thing suspected every time and is NOT the
    // cause: `typeof 0 === 'number'`, so a genuine zero renders — empty bar, "0.0%" beside it. Pinned here
    // so the next report can rule the page out in one run instead of by re-reading the loop. A window that
    // vanishes therefore vanished BEFORE the page, in `JcefState.compactUsageJson` or in the parser above it.
    win.cc.state({ ...base, usage: [{ key: 'model_scoped:Fable', label: 'Fable', pct: 0 }] });
    expect(bars().hasAttribute('hidden')).toBe(false);
    expect(items()).toHaveLength(1);
    expect(items()[0].querySelector('.ub-label').textContent).toBe('Fable');
    expect(items()[0].querySelector('.ub-pct').textContent).toBe('0.0%');
    expect(items()[0].querySelector('.ub-track > i').style.width).toBe('0%');
  });
});

// The standing facts: who is signed in, what is running, where — two lines of the same metrics strip.
//
// Deliberately NOT the Session panel's cards, and not a two-column table: both were tried and both are pinned
// against below. The cards repeated the plan bars and the context figure that are already on screen a
// centimetre above, and dropped page furniture into a strip; the table fixed the furniture and kept the wrong
// shape, since a column of labels down the left is a reference work and this is five short facts. What is left
// is `label: value` pairs in the readout's own grammar, on the readout's own columns, drawn from the
// dashboard's payload rather than from a second copy of it — two surfaces disagreeing about who is signed in
// is a bug nobody would think to look for.
describe('mini session view', () => {
  const payload = {
    model: 'opus[1m]',
    cwd: '/home/dev/project',
    version: '2.1.226',
    usage: { plan: 'Max', windows: [{ key: 'five_hour', label: 'Current session', pct: 13 }] },
    context: { used: 40000, max: 200000, pct: 20, categories: [{ name: 'System prompt', tokens: 4000 }] },
    cost: { input: 100, output: 200, usd: 0.42 },
    account: { email: 'dev@example.com', plan: 'Max' },
  };

  let win;
  beforeEach(() => {
    // The dashboard family too: the fold is a consumer of ITS builders, so a composer-only page is a page
    // where the feature cannot exist. Loading both is what makes the reuse testable at all.
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.state({ running: true, starting: false });
  });

  const fold = () => win.document.querySelector('.dash-mini');
  /** The block's lines, each as `[label, value]` pairs — scoped to the block, never to the panel. */
  const lines = () =>
    Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line')).map((line) =>
      Array.from(line.querySelectorAll('.mini-fact')).map((f) => [
        f.querySelector('.mini-key').textContent,
        f.querySelector('.mini-val').textContent,
      ])
    );
  const table = () => lines().reduce((all, l) => all.concat(l), []);

  it('stays out of the way until there is a session to describe', () => {
    expect(fold().hasAttribute('hidden')).toBe(true);
    win.cc.session(payload);
    expect(fold().hasAttribute('hidden')).toBe(false);
  });

  it('is lines of label-and-value with no disclosure of its own', () => {
    win.cc.session(payload);
    expect(lines()).toEqual([
      [
        ['Model:', 'opus[1m]'],
        ['Working dir:', '/home/dev/project'],
      ],
      [
        ['Account:', 'dev@example.com'],
        ['Plan:', 'Max'],
      ],
    ]);
    // No disclosure OF ITS OWN, and no scroll box either. Both existed only while this block was the Session
    // panel's six cards: a handful of short facts does not need a click to be seen, and a block whose height
    // is bounded by construction does not need a cap. The strip's `Show more` is a different control with a
    // different job — it appears only when four columns do not fit, and it acts on every row at once, not on
    // this block (see the `strip fold` suite).
    expect(win.document.querySelector('.dash-mini-btn')).toBeNull();
    expect(win.document.querySelector('.dash-mini-body')).toBeNull();
    expect(sheet).not.toMatch(/\.dash-mini-body/);
  });

  /**
   * The two lines are laid on ONE grid, which is the only thing that makes their pairs line up vertically.
   *
   * Each line used to be its own flex row with `justify-content: space-between`. That distributes the
   * leftover width between the items of THAT line, so a line of two pairs and a line of four spread their
   * gaps differently and nothing aligned between them — a grid drawn without a grid, sitting directly under
   * `.usage-bars`, which is a real one. jsdom lays nothing out, so the stylesheet is where this is pinned.
   */
  it('lays both lines on one grid instead of distributing each line on its own', () => {
    expect(rule('.dash-mini-grid')).toMatch(/display:\s*grid/);
    // The columns are the STRIP's, not this row's — see the strip test below for why that matters.
    expect(rule('.dash-mini-grid')).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
    // `display: contents` is what puts the pairs of BOTH lines on those columns rather than in two boxes.
    expect(rule('.mini-line')).toMatch(/display:\s*contents/);
    // The shape that made the old rule wrong is gone, not merely overridden by something later.
    expect(rule('.mini-line')).not.toMatch(/justify-content/);
    expect(rule('.mini-line')).not.toMatch(/display:\s*flex/);
  });

  it('never widens a cell to fill a short line, whatever else it lets one cell do', () => {
    win.cc.session(payload);
    const drawn = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'));
    // No LINE-level modifier decides widths, and no cell is stretched to take up slack: a row that spread
    // its cells to fill the width would give itself a column layout of its own, and rows with their own
    // layouts is the whole defect. The `mini-wide`/`span 2` pair that used to do exactly that is gone.
    expect(drawn.map((l) => l.className)).toEqual(['mini-line', 'mini-line']);
    expect(sheet).not.toMatch(/\.mini-wide/);
    // The rule that places every cell spans nothing. The one cell that may reach into the columns already
    // empty beside it is a DECLARED exception with its own class, tested on its own below — and it is not
    // the same thing as widening, because it moves where one item ends and no track at all.
    expect(rule('.mini-fact')).not.toMatch(/grid-column/);
    expect(rule('.mini-line > .mini-fact:first-child')).not.toMatch(/span/);
    // Two facts on a four-column grid is therefore two cells, not two half-width ones.
    expect(lines()[0]).toHaveLength(2);
    // And every line starts a new row: without this a short first line would leave room the next one flows
    // into, which is the two lines merging back into one — the defect the grid exists to prevent.
    expect(rule('.mini-line > .mini-fact:first-child')).toMatch(/grid-column-start:\s*1/);
  });

  /**
   * The working directory is written against the home as `~`, and only against the user's OWN home.
   *
   * The reported state was `Working dir: /home/dexperiments/pki/mat…` — cut in half, because the cell is a
   * quarter of a narrow tool window and a path is the longest value on the strip.
   *
   * The rule is that one prefix and nothing else. A regex over `/home/<name>` would shorten ANOTHER user's
   * home to `~`, and that does not read as "shortened": `~` means *this* user's home, so the one string that
   * answers "where is this session working" would be answering it wrongly. The page cannot infer whose home
   * a `/home/<name>` is, so the host has to say — and with nothing said, nothing is abbreviated.
   */
  it('writes the working directory against the home, and only against the real one', () => {
    const shownFor = (cwd, home) => {
      win.cc.session({ ...payload, cwd, home });
      return table().find((pair) => pair[0] === 'Working dir:')[1];
    };
    expect(shownFor('/home/dexperiments/pki/matrix-ca', '/home/dexperiments')).toBe('~/pki/matrix-ca');
    expect(shownFor('/home/dexperiments', '/home/dexperiments')).toBe('~');
    // Another user's home merely STARTS with the same two segments' worth of characters.
    expect(shownFor('/home/someone-else/pki', '/home/dexperiments')).toBe('/home/someone-else/pki');
    // …and a home is not a prefix of a longer name: `/home/dev` is not the home of `/home/developer`. The
    // match has to end on a segment boundary, which is the whole difference between the two lines.
    expect(shownFor('/home/developer/x', '/home/dev')).toBe('/home/developer/x');
    // A Windows path keeps the separators it came with; only the comparison is normalised.
    expect(shownFor('C:\\Users\\bob\\proj', 'C:\\Users\\bob')).toBe('~\\proj');
    // THE FAIL-SAFE DIRECTION, and it is the one that matters: with no home from the host nothing is
    // abbreviated. Not abbreviating costs a longer string; abbreviating wrongly costs a false one.
    expect(shownFor('/home/dexperiments/pki', undefined)).toBe('/home/dexperiments/pki');
    // And the empty string is "not known" too, never a home that happens to be empty — as a prefix it
    // matches EVERY path, so treating it as one would abbreviate all of them at once. The host says the
    // same at its end (`LinkResolver.userHome` answers null for a blank property, and `JcefSessionData`
    // puts that as JSON null), so this is the contract held from both sides rather than trusted from one.
    expect(shownFor('/home/dexperiments/pki', '')).toBe('/home/dexperiments/pki');
    expect(shownFor('/home/dexperiments/pki', null)).toBe('/home/dexperiments/pki');
  });

  it('keeps the absolute path whole, because `~` is a convention and not a path', () => {
    // `~` is resolved by a shell, against whoever is running it. Anything that leaves this strip — pasted
    // into a terminal on another machine, quoted in an issue — has to be the absolute path, so the pair's
    // tooltip carries it in full and the abbreviation stays a thing the eye is shown.
    win.cc.session({ ...payload, cwd: '/home/dexperiments/pki/matrix-ca', home: '/home/dexperiments' });
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.getAttribute('title')).toBe('Working dir: /home/dexperiments/pki/matrix-ca');
    expect(dir.querySelector('.mini-val').textContent).toBe('~/pki/matrix-ca');
    // The key is never what gives way — a cell reading `Working d…` has lost the part that said what it was.
    expect(dir.querySelector('.mini-key').textContent).toBe('Working dir:');
  });

  /**
   * It may use the columns that are already empty beside it — and no gridline moves.
   *
   * Those two are the whole test, because the second is what stops this becoming the misalignment the shared
   * grid was built to remove. `grid-column-end` moves where this ITEM stops and says nothing about the
   * tracks; and the tracks are `minmax(0, 1fr)`, sized from the free space and never from their content, so
   * no item's width can feed back into them. Every other row of the strip keeps its verticals exactly.
   */
  it('lets the working directory use the empty columns beside it without moving a gridline', () => {
    win.cc.session(payload);
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    // DECLARED by the JS at the one call site that means it, not derived from how many facts survived — the
    // same discipline as the line's own class, and for the same reason: a line must not change width in
    // silence because a value happened to be absent.
    expect(dir.classList.contains('mini-fill')).toBe(true);
    expect(win.document.querySelectorAll('.dash-mini-grid .mini-fill')).toHaveLength(1);
    // BOTH lines, and the end line alone was the bug: with an `auto` start, a definite end gives the item a
    // span of ONE (CSS Grid §8.3), so it was placed in the LAST track — the path pinned against the right
    // edge, one column wide and clipping, with three empty columns between it and `Model`.
    const fill = rule('.mini-line > .mini-fact.mini-fill:nth-child(2):last-child');
    expect(fill).toMatch(/grid-column:\s*2 \/ -1/);
    // Still no template on the cell: it changes where the ITEM ends, never a track, which is what keeps the
    // other rows' verticals still.
    expect(fill).not.toMatch(/grid-template-columns/);
    // `:nth-child(2)` is what makes the `2` honest — the cell before it is pinned to column 1 — and
    // `:last-child` is a precondition, not the decision: a fact cannot spill over a neighbour, so the fill
    // stops on its own if anything is ever placed after it.
    expect(sheet).toMatch(/\.mini-line > \.mini-fact:first-child \{/);
    expect(sheet).toMatch(/\.mini-fact\.mini-fill:nth-child\(2\):last-child \{/);
    // And the tracks stay content-independent and shared, which is what keeps the other four rows still.
    expect(rule('#composer')).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    ['.readout', '.usage-bars', '.dash-mini-grid'].forEach((row) => {
      expect(rule(row)).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
    });
  });

  /**
   * The organization is dropped when it is only the account name again — and kept whenever it might not be.
   *
   * A personal account has no organization anybody chose, so the provider generates one out of the account
   * itself: the row then prints the address twice, side by side, on exactly the accounts with least to say.
   * A team's is a name someone picked and is the only place that name appears, so the rule has to fail
   * towards showing — the suffix is the provider's to reword, and hiding a real organization loses a fact
   * where failing to hide a generated one costs a slightly long line.
   */
  it('drops an organization that is only the account name again, and keeps one somebody named', () => {
    const orgKeys = (org) => {
      win.cc.session({ ...payload, account: { email: 'dev@example.com', org, plan: 'Max' } });
      return table().map((r) => r[0]);
    };
    expect(orgKeys("dev@example.com's Organization")).not.toContain('Organization:');
    // Normalised, not matched literally: case, spacing, the spelling of the word and the shape of the
    // apostrophe are all the provider's to choose, and none of them changes what the value says.
    expect(orgKeys('  DEV@EXAMPLE.COM’s organisation ')).not.toContain('Organization:');
    expect(orgKeys('dev@example.com')).not.toContain('Organization:');
    // …and everything it cannot account for is shown.
    expect(orgKeys('Acme Corp')).toContain('Organization:');
    expect(orgKeys('dev@example.com Platform Team')).toContain('Organization:');
    expect(orgKeys('Dev Example Ltd')).toContain('Organization:');
  });

  it('leaves the freed column free rather than re-flowing the line that lost a fact', () => {
    // Widening three pairs to fill the row would move `Plan` and `Provider` off the gridlines they share
    // with the rows above — the misalignment this grid exists to remove, arriving as a tidy-up.
    win.cc.session({
      ...payload,
      account: {
        email: 'dev@example.com',
        org: "dev@example.com's Organization",
        plan: 'Max',
        provider: 'firstParty',
      },
    });
    const drawn = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'));
    expect(drawn.map((l) => l.className)).toEqual(['mini-line', 'mini-line']);
    expect(lines()[1].map((pair) => pair[0])).toEqual(['Account:', 'Plan:', 'Provider:']);
  });

  it('is two facts on the first line and three on the second, leaving the spare columns empty', () => {
    // Four columns, and a row that does not fill them leaves the rest EMPTY: two facts is two cells and two
    // gaps, three is three cells and one gap. Nothing stretches to take up the slack.
    win.cc.session({
      ...payload,
      account: { email: 'dev@example.com', plan: 'Max', provider: 'firstParty' },
    });
    expect(lines().map((l) => l.length)).toEqual([2, 3]);
  });

  it('keeps a fourth fact inside the same grid instead of starting another one', () => {
    // A team account has an organization worth showing, so that line has four facts in a three-track system.
    // It wraps onto the next row of the SAME grid — the one thing it must not do is widen the line or give
    // itself a fourth column, which is how the strip came to have three column systems in the first place.
    win.cc.session({
      ...payload,
      account: { email: 'dev@example.com', org: 'Acme Corp', plan: 'Max', provider: 'firstParty' },
    });
    expect(lines().map((l) => l.length)).toEqual([2, 4]);
    const narrow = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-line'))[1];
    expect(narrow.className).toBe('mini-line');
    expect(rule('.dash-mini-grid')).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
  });

  /**
   * ONE system for the whole strip: same four columns, same gap, same type size, same inset — every row.
   *
   * The rows are siblings with no wrapper around them, so nothing FORCES them to agree and for a while they
   * did not. This is the test that would have caught it.
   *
   * What failed here was never a row. It was that each row answered the same question for itself: four type
   * sizes and three column systems, none of them a value anyone got wrong. So a test asserting literals
   * would have passed on all four sizes and all three grids; it has to compare the rows with EACH OTHER, and
   * assert that they DEFER to one declaration rather than that they happen to agree today.
   *
   * The gap is in here and not in a test of its own, because it is part of the column system and not a
   * decoration on top of it: a `1fr` track is `(100% − 3 × gap) / 4`, so two grids with the same template
   * and different gaps have EVERY vertical in a different place. The bars were on 14px and the facts on
   * 18px, i.e. the templates matched and the rows still did not.
   */
  it('has one column system, one gap, one type size and one inset, declared once for every row', () => {
    const rows = ['.readout', '.usage-bars', '.dash-mini-grid'];
    // Nobody hardcodes a size: each row takes the strip's, and everything inside them inherits it.
    rows.forEach((selector) => {
      expect(rule(selector)).not.toMatch(/font-size:\s*[\d.]+px/);
      expect(rule(selector)).toMatch(/font-size:\s*var\(--strip-font\)/);
    });
    // The countdown and the empty note used to carry their own 11px. They inherit now, which is what makes
    // "one size" true rather than "four rules that happen to agree today".
    expect(rule('.usage-bars .ub-reset')).not.toMatch(/font-size/);
    expect(rule('.mini-empty')).not.toMatch(/font-size/);
    // Same tracks and same GAP on all three, both axes taken from the one declaration and never written out.
    rows.forEach((selector) => {
      expect(rule(selector)).toMatch(/grid-template-columns:\s*var\(--strip-cols\)/);
      expect(rule(selector)).toMatch(/gap:\s*var\(--strip-row\) var\(--strip-gap\)/);
    });
    // …and every row shares the start, so the strip has one left edge instead of four that match. The
    // disclosure is a row of the strip too, and lines up with them.
    ['.readout', '.usage-bars', '.dash-mini', '.strip-more'].forEach((selector) => {
      expect(rule(selector)).toMatch(/margin:[^;]*var\(--strip-inset\)/);
    });
    // And the values exist exactly once, on the rows' common ancestor.
    const strip = rule('#composer');
    expect(strip).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    expect(strip).toMatch(/--strip-font:\s*11px/);
    expect(strip).toMatch(/--strip-gap:\s*16px/);
    expect(strip).toMatch(/--strip-inset:\s*4px/);
    expect(strip).toMatch(/--strip-row:\s*1px/);
    expect(strip).toMatch(/--strip-line:\s*1\.5/);
  });

  /**
   * ONE distance between rows, and one line height — the vertical half of the same system.
   *
   * The columns were unified and the rhythm was not, so a strip of five rows of one readout was separated by
   * four different distances: 1px between a bar and its own countdown, 3px between the two lines of facts,
   * and 8px twice over where a BLOCK's bottom margin was doing the work of a row gap. Each was right for its
   * own box and wrong for the strip, which is the same shape of defect as four type sizes — so it is asserted
   * the same way, by comparing the rows against each other and against the one declaration, never against a
   * literal that would pass on any four values that happened to agree.
   *
   * The line height is in here rather than in a test of its own because equal gaps between rows of unequal
   * height are still an unequal rhythm: with the facts on `1.5` and the other rows inheriting the page's
   * `1.6`, the pitch drifts by about a pixel per row at 11px — small, cumulative, and unnameable on screen.
   *
   * `.readout`'s bottom margin is deliberately NOT `--strip-row`, and that is the one exception worth
   * pinning: it is the last row, and what sits under it is the prompt card, which is a different thing and
   * not the next line of the same readout.
   */
  it('has one distance between rows and one line height, declared once for every row', () => {
    ['.readout', '.usage-bars', '.dash-mini-grid'].forEach((selector) => {
      expect(rule(selector)).toMatch(/gap:\s*var\(--strip-row\) var\(--strip-gap\)/);
      expect(rule(selector)).toMatch(/line-height:\s*var\(--strip-line\)/);
      expect(rule(selector)).not.toMatch(/line-height:\s*[\d.]+;/);
    });
    // A bar and its own countdown are a row boundary like any other — and the pair the value came from.
    expect(rule('.usage-bars .ub-item')).toMatch(/gap:\s*var\(--strip-row\)/);
    // The two blocks whose bottom margin IS a row gap: what follows each is the next row of the readout.
    ['.usage-bars', '.dash-mini'].forEach((selector) => {
      expect(rule(selector)).toMatch(/margin:\s*0 var\(--strip-inset\) var\(--strip-row\)/);
    });
    // …and the one that is not: the readout ends the strip, and the prompt card is not a row of it.
    expect(rule('.readout')).toMatch(/margin:\s*0 var\(--strip-inset\) 8px/);
  });

  /**
   * The five rows, in order, and a short row that stops rather than stretching.
   *
   * Top to bottom: the plan bars, each window's countdown under its own bar, the two lines of standing
   * facts, and the status line last — closest to the box you type in, which is where what a turn is doing
   * belongs rather than four rows above it.
   *
   * IT IS THE DOM'S ORDER AND THIS TEST READS THE DOM, deliberately. `order:` and `column-reverse` would
   * paint the same picture and leave the tab order in the sequence the elements were appended in, which is a
   * focus order that disagrees with the screen (WCAG 2.2 SC 2.4.3) and is invisible to a test that asserts
   * on CSS. Two of the three are MOVED into place by `ensureMini`, so what is pinned here is where they
   * ended up, not the rule that put them there.
   */
  it('is five rows in one order, and a short row stops instead of filling the width', () => {
    // TWO payloads, and they are not interchangeable — which is why this test was once asserting about a row
    // that was not there. `cc.state` carries `usage` as an ARRAY of plan windows and is what draws rows four
    // and five; `cc.session` carries `usage` as `{ plan, windows }` for the dashboard's own card, and never
    // reaches this row. The fixture pushes only the second, so the bars row sat in the DOM empty and
    // `hidden`, and every claim made about it was true of nothing.
    const in90min = new Date(Date.now() + 90 * 60000).toISOString();
    win.cc.state({
      running: true,
      starting: false,
      usage: [{ key: 'five_hour', label: 'Current session', pct: 13, resetsAt: in90min }],
    });
    win.cc.session(payload);
    const strip = win.document.getElementById('composer');
    const rowOf = (selector) => Array.prototype.indexOf.call(strip.children, strip.querySelector(selector));
    // Every row is a real position FIRST. `indexOf` answers -1 for an element that is not there and -1 is
    // less than everything, so an ordering assertion over an absent row passes BY ABSENCE — the same shape
    // of hole as the one this test was carrying, one level down.
    const order = ['.usage-bars', '.dash-mini', '.readout'].map(rowOf);
    order.forEach((at) => expect(at).toBeGreaterThanOrEqual(0));
    expect(order).toEqual([...order].sort((a, b) => a - b));
    // …and the disclosure stays the LAST row of the strip, after the row that is now last, rather than
    // stranded in the middle where it was anchored to whichever row used to be at the bottom.
    expect(rowOf('.strip-more')).toBeGreaterThan(rowOf('.readout'));
    // Rows two and three are the two fact lines, in that order and no other.
    expect(lines().map((l) => l.map((pair) => pair[0]))).toEqual([
      ['Model:', 'Working dir:'],
      ['Account:', 'Plan:'],
    ]);
    // Rows four and five are one cell per window: the bar, and the countdown stacked under it. DRAWN, not
    // merely present — the row hides itself when no window carries a percentage, and a hidden row is not a
    // row of the strip.
    const bars = win.document.querySelector('.usage-bars');
    expect(bars.hasAttribute('hidden')).toBe(false);
    const bar = bars.querySelector('.ub-item');
    expect(bar.querySelector('.ub-row .ub-track')).not.toBeNull();
    // Row five has to carry a countdown to be a row: the element is emitted for every window and left empty
    // when there is no reset time, and an empty one produces no line at all.
    expect(bar.querySelector('.ub-reset').textContent).toBe('Reset time: 1h 30m');
    // A short row leaves the remaining columns EMPTY. Two facts is two cells, and nothing widens to take up
    // the slack — a row that stretches its last cell has a column layout of its own, which is the defect.
    expect(lines()[0]).toHaveLength(2);
    expect(rule('.mini-fact')).not.toMatch(/grid-column/);
    expect(sheet).not.toMatch(/\.mini-wide/);
  });

  /**
   * It does not reflow: it CLIPS. Four columns at every width, and every cell cuts its own text.
   *
   * The chain is four declarations and it only works whole, which is why one assertion covers all of them
   * rather than one test each. `min-width: 0` is the one that is invisible when missing: a grid item's
   * automatic minimum is its min-content width, so a cell without it refuses to go below its longest word
   * and pushes the track wider — the strip then overflows sideways instead of clipping, in the dock, which
   * is the one place a horizontal scrollbar cannot appear. It is the same mechanism as `.dg-meta` in the
   * diagram, and it failed there first.
   */
  it('clips every cell instead of reflowing, and clips the value rather than its key', () => {
    // `1fr` and not `25%`: four percentages plus three gaps exceed the container. And `minmax(0, 1fr)`,
    // because a bare `1fr` floors at min-content, which is the whole path.
    expect(rule('#composer')).toMatch(/--strip-cols:\s*repeat\(4, minmax\(0, 1fr\)\)/);
    expect(rule('#composer')).not.toMatch(/25%/);
    // Each cell of the strip clips.
    ['.mini-fact', '.usage-bars .ub-item'].forEach((cell) => {
      expect(rule(cell)).toMatch(/min-width:\s*0/);
      expect(rule(cell)).toMatch(/overflow:\s*hidden/);
    });
    // Inside a pair, the VALUE gives way and the key never does — a cell reading `Working d…` has lost the
    // only part that said what it was.
    expect(rule('.mini-val')).toMatch(/min-width:\s*0/);
    expect(rule('.mini-val')).toMatch(/overflow:\s*hidden/);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.mini-val')).toMatch(/white-space:\s*nowrap/);
    expect(rule('.mini-key')).toMatch(/flex:\s*0 0 auto/);
    // The label of a plan window clips the same way; its percentage is the datum and does not.
    expect(rule('.usage-bars .ub-label')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.usage-bars .ub-pct')).toMatch(/flex:\s*0 0 auto/);
  });

  it('separates two pairs by more than it separates a key from its own value', () => {
    // Nothing else groups `Account:` with the address after it — no box, no rule, no colour — so proximity
    // is doing the whole job, and it only does it when the two distances are obviously different. The outer
    // distance is read from `--strip-gap` rather than from this row, because the row no longer owns it.
    const between = Number(/--strip-gap:\s*([\d.]+)px/.exec(rule('#composer'))[1]);
    const within = Number(/gap:\s*([\d.]+)px/.exec(rule('.mini-fact'))[1]);
    expect(between).toBeGreaterThanOrEqual(within * 3);
  });

  /**
   * The rule this block exists under, and the reason it is not the panel's cards.
   *
   * The plan-limit bars and the context figure are the lines directly ABOVE it, and the token counters and
   * the cost are in the readout line above those. Drawing any of them here — which is what reusing
   * `buildUsageCard`/`buildContextCard`/`buildCostCard` did — puts the same numbers on screen twice, a
   * centimetre apart. What belongs here is what the strip does not already say.
   */
  it('never repeats what the strip above it already shows', () => {
    win.cc.session(payload);
    const keys = table().map((r) => r[0]);
    // The plan windows and the context percentage are the two lines directly above; the counters they are
    // made of belong here, but never the bars themselves nor the figures the readout line already prints.
    expect(keys).not.toContain('Current session:');
    expect(keys).not.toContain('Context:');
    expect(keys).not.toContain('Cost:');
    expect(win.document.querySelectorAll('.dash-mini-grid .usage-track')).toHaveLength(0);
    expect(win.document.querySelectorAll('.dash-mini-grid .seg-bar')).toHaveLength(0);
    // …and no panel furniture came with it: this is a strip, not a page.
    expect(win.document.querySelectorAll('.dash-mini-grid .dash-card')).toHaveLength(0);
  });

  it('shows the LAST payload, however many arrive', () => {
    win.cc.session(payload);
    win.cc.session({ ...payload, model: 'sonnet' });
    win.cc.session({ ...payload, model: 'haiku' });
    expect(table()).toContainEqual(['Model:', 'haiku']);
    expect(table()).not.toContainEqual(['Model:', 'opus[1m]']);
  });

  it('omits a fact the host did not send rather than drawing a dash', () => {
    win.cc.session({ model: 'opus[1m]' });
    expect(lines()).toEqual([[['Model:', 'opus[1m]']]]);
  });

  it('carries the full value in a tooltip, because the value itself is clipped', () => {
    // A working directory is one long unbreakable token. It is allowed to be cut rather than to widen the
    // strip past the composer, so the whole thing has to be reachable some other way.
    const long = '/home/dev/PycharmProjects/claude-code-for-jetbrains/src/main/resources/jcef/css';
    win.cc.session({ ...payload, cwd: long });
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.getAttribute('title')).toBe('Working dir: ' + long);
    // The clipping is VISUAL, and this is the half that matters for anything that is not a pair of eyes: the
    // value stays whole in the DOM, so a screen reader still reads the path a narrow tool window cuts off.
    // The home is not abbreviated here because none was sent — that is a separate, declared substitution and
    // it is the only thing that ever makes this text differ from the path, tested above.
    expect(dir.querySelector('.mini-val').textContent).toBe(long);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('is a row of the dock and cannot cover anything', () => {
    // The composer's own bug class: every waiting screen and panel that ever covered the transcript, the tabs
    // or the prompt box did it by leaving the flow. This one is a sibling of the other strip rows — between
    // the plan bars and the status line — so the transcript gives up the space and nothing is laid over
    // anything.
    win.cc.session(payload);
    expect(fold().previousSibling).toBe(win.document.querySelector('.usage-bars'));
    expect(fold().nextSibling).toBe(win.document.querySelector('.readout'));
    expect(rule('.dash-mini')).not.toMatch(/position:\s*(fixed|absolute)/);
    expect(rule('.dash-mini')).not.toMatch(/z-index/);
  });

  it('is bounded by construction, and its value column cannot widen the dock', () => {
    // Height was the only way a row of the dock can hurt, and it is answered by the block being two lines of
    // at most four facts rather than by a cap: the scroll box that used to wrap the grid is gone, because an
    // element with `overflow` cannot be `display: contents` and therefore cannot let its rows sit on the
    // strip's own columns. Width is the other way: a working directory is one unbreakable token, so it is
    // clipped with an ellipsis (the full text is in the pair's tooltip) rather than allowed to widen the
    // strip past the composer.
    expect(rule('.mini-line')).toMatch(/display:\s*contents/);
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.mini-val')).toMatch(/min-width:\s*0/);
  });
});

// The column separator: a `·` on every boundary of every row.
//
// What fails here is not a missing bullet. It is a bullet that is THERE and points at an empty column — after
// the last cell of a short line, or after the last column of the grid, where there is no neighbour and no gap
// for it to sit in. A separator that separates nothing reads as part of the value in front of it, which is
// exactly how the old flex-line version failed, and is why every rule below is about where it STOPS.
describe('strip separators', () => {
  let win;
  const base = { running: true, starting: false };
  const payload = {
    model: 'opus[1m]',
    cwd: '/home/dev/project',
    account: { email: 'dev@example.com', plan: 'Max', provider: 'firstParty' },
  };

  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    const soon = new Date(Date.now() + 6e5).toISOString();
    win.cc.state({
      ...base,
      usage: [
        { key: 'five_hour', label: 'Current session', pct: 13, resetsAt: soon },
        { key: 'seven_day', label: 'All models', pct: 9 },
        { key: 'model_scoped:Fable', label: 'Fable', pct: 71 },
      ],
    });
    win.cc.session(payload);
  });

  /** One flag per element matching [selector]: is it a cell of the strip? Length is part of the answer. */
  const cellFlags = (selector) =>
    Array.from(win.document.querySelectorAll(selector)).map((el) => el.classList.contains('strip-cell'));

  it('marks every cell of every row as a cell, and nothing else', () => {
    // One class for all five rows, because the boundary rule is the same one everywhere and because the fold
    // hides a COLUMN: three selector families would be three chances for the rows to drift apart.
    expect(cellFlags('.readout .ro-item')).toEqual([true, true, true, true]);
    expect(cellFlags('.dash-mini-grid .mini-fact')).toEqual([true, true, true, true, true]);
    expect(cellFlags('.usage-bars .ub-item')).toEqual([true, true, true]);
    // What is INSIDE a cell is not one. The countdown in particular: it shares its window's cell with the
    // bar, which is what makes the two inseparable (see the plan-window test below).
    expect(cellFlags('.usage-bars .ub-reset')).toEqual([false, false, false]);
    expect(cellFlags('.dash-mini-grid .mini-val')).toEqual([false, false, false, false, false]);
  });

  /**
   * It lives in the GAP, and it costs the cell no width.
   *
   * The cell claims the gap as padding and gives the same amount back as a negative margin, so the content
   * box is still exactly the track. That is not a flourish: with four columns in a tool window there is no
   * width to spare, and a separator taking room inside the cell would be paid for by the ellipsis — it would
   * arrive one character earlier, on the value it exists to help read.
   *
   * And it has to be inside the PADDING box specifically, because every cell here clips with
   * `overflow: hidden` and overflow clips to the padding box. A pseudo-element pushed out past the cell with
   * a negative offset — the obvious way to write it — is never painted, with no error and no symptom other
   * than the glyph not being there.
   */
  it('is painted in the gap without taking a pixel from the cell', () => {
    expect(rule('.strip-cell')).toMatch(/padding-right:\s*var\(--strip-gap\)/);
    expect(rule('.strip-cell')).toMatch(/margin-right:\s*calc\(-1 \* var\(--strip-gap\)\)/);
    // Positioned inside that padding, not outside the cell: `right: 0` with the gap's own width.
    expect(rule('.strip-cell::after')).toMatch(/position:\s*absolute/);
    expect(rule('.strip-cell::after')).toMatch(/right:\s*0/);
    expect(rule('.strip-cell::after')).toMatch(/width:\s*var\(--strip-gap\)/);
    expect(rule('.strip-cell::after')).not.toMatch(/right:\s*-/);
    expect(rule('.strip-cell::after')).not.toMatch(/calc\(-/);
    // The cell stays a positioned ancestor AND stays a clipping one — dropping `overflow: hidden` so a
    // negative offset could escape is the other route, and it is the one that puts a horizontal scrollbar in
    // the dock the first time a session runs in a deep directory.
    expect(rule('.strip-cell')).toMatch(/position:\s*relative/);
    expect(rule('.usage-bars .ub-item')).toMatch(/overflow:\s*hidden/);
    expect(rule('.mini-fact')).toMatch(/overflow:\s*hidden/);
  });

  it('never dangles: no separator after the last cell with content, nor in the last column', () => {
    // Two different things, and the second is the one that is easy to miss. `:last-child` is the last cell
    // WITH CONTENT of its row — `Model | Working dir` must not end in two bullets hanging over the empty
    // columns three and four. `:nth-child(4n)` is the cell in the last column, which has no gap to its right
    // at all: left alone it would claim padding past the grid's own edge and paint the glyph outside it.
    const given = bodyAt(sheet.indexOf('\n.strip-cell:last-child,'));
    expect(given).toMatch(/padding-right:\s*0/);
    expect(given).toMatch(/margin-right:\s*0/);
    expect(bodyAt(sheet.indexOf('\n.strip-cell:last-child::after,'))).toMatch(/content:\s*none/);
    // Both cases, named in both rules — a suppression that only zeroed the glyph would leave the padding
    // behind, and the last column would still be able to widen the row past the grid.
    expect(sheet).toMatch(/\.strip-cell:nth-child\(4n\)\s*\{/);
    expect(sheet).toMatch(/\.strip-cell:nth-child\(4n\)::after/);
  });

  it('drops the separator that pointed at a folded column, by the same rule', () => {
    // Two columns means the boundary falls after every second cell, so the `·` that used to point at column
    // three goes away with the columns it was pointing at. Re-parameterising the one rule is what makes that
    // automatic; a second rule listing the cases would be a second thing to keep in step.
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(2n\)::after \{\s*content: none;/);
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(2n\) \{[^}]*padding-right:\s*0/);
  });

  it('keys every positional rule on a row, never on the strip, so moving a row breaks nothing', () => {
    // The rows are reordered in the DOM (bars → facts → status). What could have broken silently is the
    // positional CSS: a `:nth-child` counting `#composer`'s OWN children would be counting the rows
    // themselves, so swapping two of them would hide the wrong column or leave a `·` hanging past the last
    // cell with content — with no error and nothing on screen naming the cause.
    // Every positional rule is scoped to a ROW instead, and a `.strip-cell`'s parent is always `.readout`,
    // `.usage-bars` or a `.mini-line`, so a cell's index cannot be changed by where its row sits.
    expect(sheet).not.toMatch(/#composer\s*>[^{,]*:(nth-child|first-child|last-child)/);
    [
      '.strip-cell:last-child',
      '.strip-cell:nth-child(4n)',
      '.mini-line > .mini-fact:first-child',
      '.mini-line > .mini-fact.mini-fill:nth-child(2):last-child',
      '.readout .ro-item:first-child',
    ].forEach((selector) => expect(sheet).toContain(selector));
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(n\s*\+\s*3\)/);
  });

  it('is decoration and stays out of the accessible name', () => {
    // Chromium — the only engine this page runs in — exposes generated content as static text and folds it
    // into accname, so a bare `content: '·'` is a bullet announced between every metric of every row. The
    // alternative-text form is what declares it decoration; it is also why the glyph is in the stylesheet
    // and not in the DOM, where it would be selectable, copyable and a cell of the grid in its own right.
    expect(rule('.strip-cell::after')).toMatch(/content:\s*'·'\s*\/\s*''/);
    ['.readout', '.dash-mini', '.usage-bars'].forEach((row) => {
      expect(win.document.querySelector(row).textContent).not.toContain('·');
    });
    expect(rule('.strip-cell::after')).toMatch(/pointer-events:\s*none/);
  });

  /**
   * The user's invariant, stated so it survives the mechanism changing: for every plan-limit window, its bar
   * and its reset are both visible or both hidden — never one and not the other.
   *
   * It is guaranteed structurally rather than by a rule that has to be remembered: the two are ONE cell, so
   * there is nothing that can act on one of them. A countdown left behind by a hidden bar does not read as a
   * missing countdown — it reads as that window's neighbour's countdown, which is a wrong answer.
   */
  it('keeps a plan window whole: its bar and its countdown are one cell', () => {
    const items = Array.from(win.document.querySelectorAll('.usage-bars .ub-item'));
    expect(items).toHaveLength(3);
    items.forEach((item) => {
      expect(item.querySelector('.ub-row .ub-track')).not.toBeNull();
      expect(item.querySelector('.ub-reset')).not.toBeNull();
      // The cell — the thing anything hiding a column acts on — is the item that holds both.
      expect(item.classList.contains('strip-cell')).toBe(true);
    });
    // …and nothing anywhere hides either half on its own. The fold names the cell class and no row's own
    // class, which is what makes "hide column three" mean the same thing in all five rows at once.
    expect(sheet).not.toMatch(/\.ub-reset[^{]*\{[^}]*display:\s*none/);
    expect(sheet).not.toMatch(/\.ub-row[^{]*\{[^}]*display:\s*none/);
    expect(foldMedia()).toMatch(/:not\(\.strip-open\) \.strip-cell:nth-child\(n\s*\+\s*3\)/);
    expect(foldMedia()).not.toMatch(/\.ub-|\.ro-item|\.mini-fact/);
  });
});

// The fold: four columns always, and a `Show more` when four do not fit.
//
// The defect this replaced was a reflow — the same width answered by twice the height, unasked and with no way
// back, spending the dock's space exactly when there is least of it. What replaces it must not become the OTHER
// failure, which this strip has also already had: a disclosure over information that fits, charging a click for
// nothing. So the button's presence has to mean "something is being withheld", and that is two separate
// conditions — the window is narrow (CSS) and a row really has a third cell (JS) — neither of which is enough
// alone.
describe('strip fold', () => {
  let win;
  const base = { running: true, starting: false };

  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
  });

  const btn = () => win.document.querySelector('.strip-more');
  const push = () => {
    win.cc.state({ ...base, usage: [{ key: 'a', label: 'Current session', pct: 13 }] });
    win.cc.session({ model: 'opus[1m]', cwd: '/home/dev/project', account: { email: 'dev@example.com' } });
  };

  it('does not exist while there is nothing behind it', () => {
    // A fresh tab, before the host has said anything: the strip's rows are empty or hidden, so there is no
    // third cell anywhere and a `Show more` would be a promise of information that does not exist. This is
    // the half a media query cannot answer — it knows the window is narrow and nothing else.
    expect(btn()).toBeNull();
    push();
    expect(btn()).not.toBeNull();
  });

  it('is only rendered where four columns do not fit', () => {
    // The other half. Outside the breakpoint it is `display: none`: not dimmed, not a reserved gap, not a
    // disabled control — its PRESENCE is the signal, and a control that is always there says nothing by
    // appearing. `display` is also what takes it out of the tab order, which an opacity would not.
    expect(rule('.strip-more')).toMatch(/display:\s*none/);
    expect(rule('.strip-more')).not.toMatch(/visibility/);
    expect(rule('.strip-more')).not.toMatch(/opacity/);
    expect(foldMedia()).toMatch(/\.strip-more \{\s*display: flex;/);
    // Block-level and shrink-wrapped: `inline-flex` would look identical and drop the bottom margin, and a
    // full-width button is a strip of invisible click target above the prompt box.
    expect(rule('.strip-more')).toMatch(/width:\s*fit-content/);
  });

  it('opens and shuts, and says which it is in text as well as programmatically', () => {
    push();
    const composer = win.document.getElementById('composer');
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(btn().textContent).toBe('Show more');
    expect(composer.classList.contains('strip-open')).toBe(false);

    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent).toBe('Show less');
    expect(composer.classList.contains('strip-open')).toBe(true);

    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(btn().getAttribute('aria-expanded')).toBe('false');
    expect(composer.classList.contains('strip-open')).toBe(false);
  });

  it('survives a repush of the same payload, and never grows a second button', () => {
    // The host pushes several times a turn and every one of them rebuilds every cell in the strip. Open or
    // shut is therefore recorded on `#composer`, which nothing here rebuilds, and nowhere on the cells — a
    // state written onto them would be destroyed by exactly the redraw it has to outlive.
    push();
    btn().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    push();
    push();
    expect(win.document.querySelectorAll('.strip-more')).toHaveLength(1);
    expect(btn().getAttribute('aria-expanded')).toBe('true');
    expect(btn().textContent).toBe('Show less');
    expect(win.document.getElementById('composer').classList.contains('strip-open')).toBe(true);
  });

  it('hides whole columns, out of sight and out of the tab order', () => {
    // `display: none` and nothing softer: a cell that is not on screen must not be reachable with Tab nor
    // readable by a screen reader (WCAG 2.4.3 — an item in the tab order that cannot be seen is a focus stop
    // that goes nowhere). jsdom evaluates no media query, so this is asserted on the rule itself.
    expect(foldMedia()).toMatch(/\.strip-cell:nth-child\(n\s*\+\s*3\) \{\s*display: none;/);
    expect(foldMedia()).not.toMatch(/visibility:\s*hidden/);
    expect(foldMedia()).not.toMatch(/opacity:\s*0/);
    // Two columns, not one and not a reflow to a third row: the pairing survives and the cells clip.
    expect(foldMedia()).toMatch(/--strip-cols:\s*repeat\(2, minmax\(0, 1fr\)\)/);
  });

  it('names three containers that exist, so nothing it controls can be a dangling reference', () => {
    // A disclosure normally precedes what it reveals; this one controls three separate containers
    // interleaved with each other, so no position precedes them all and `aria-controls` carries the
    // relationship instead. Which only works if every id resolves.
    push();
    const ids = btn().getAttribute('aria-controls').split(/\s+/);
    expect(ids).toHaveLength(3);
    ids.forEach((id) => expect(win.document.getElementById(id)).not.toBeNull());
    expect(btn().getAttribute('type')).toBe('button');
  });

  it('cannot animate past the reduced-motion switch', () => {
    // The page's motion switch is `body.reduced-motion`, set by the host, NOT `prefers-reduced-motion` —
    // JCEF renders off-screen and cannot answer that query. The rule that implements it is a universal one,
    // so the fold is covered by construction and needs no opt-in of its own; what it must not do is animate
    // by some means that rule cannot reach.
    const universal = bodyAt(sheet.indexOf('\nbody.reduced-motion *,'));
    expect(universal).toMatch(/transition-duration:\s*0\.001ms\s*!important/);
    expect(universal).toMatch(/animation-duration:\s*0\.001ms\s*!important/);
    expect(rule('.strip-more')).not.toMatch(/animation/);
    expect(foldMedia()).not.toMatch(/animation|transition/);
  });
});
