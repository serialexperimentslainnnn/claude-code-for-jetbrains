// The composer readout: a zero is a measurement, an absence is not.
//
// These items used to be hidden until they were non-zero, which made a fresh tab show a lone "Idle" with no
// numbers — indistinguishable from a readout that failed to load. That ambiguity cost real debugging time, so
// the settled-at-zero behaviour is pinned here rather than left as a style someone tidies away.
const { loadFrontend, readCss } = require('./helpers/load');

describe('composer readout', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const readoutText = () => win.document.querySelector('.readout').textContent;

  it('the separators are decoration, and the digits do not shuffle', () => {
    // The counters tick several times a second. With proportional digits every item after a number shifts
    // sideways on each tick, which is what made four small metrics read as a busy line.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = css.slice(css.indexOf('.readout .ro-item {'));
    expect(block.slice(0, block.indexOf('}'))).toMatch(/font-variant-numeric:\s*tabular-nums/);
    // The separator is a pseudo-element ON PURPOSE: as markup it would be selectable, copyable, and read
    // aloud between every metric by a screen reader.
    expect(css).toMatch(/\.readout \.ro-item:not\(:last-child\)::after/);
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
    // A window with no reset time gets no element at all — an empty slot would read as "resets now".
    expect(items()[1].querySelector('.ub-reset')).toBeNull();
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

// The session view in miniature: the dashboard's own cards, folded into the same metrics strip.
//
// Two properties are the whole feature and both are pinned below. It is SHUT by default and builds nothing
// while shut — the host pushes a session payload several times a turn, and the panel this reuses had exactly
// that bug (`renderIfShown`). And the cards are the dashboard's builders, called by name: a reduced copy
// would be a second answer to "what does this session cost", drifting from the first the day either changes.
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
  const body = () => win.document.querySelector('.dash-mini-body');
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

  it('is lines of label-and-value, always visible, with no control to reveal them', () => {
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
    // No disclosure. It existed only while this block was the Session panel's six cards; a handful of short
    // facts does not need a click, a state to remember and a second thing to keep accessible.
    expect(win.document.querySelector('.dash-mini-btn')).toBeNull();
    expect(body().hasAttribute('hidden')).toBe(false);
  });

  it('spans the strip, so it lines up with the plan bars above it', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const at = css.indexOf('.mini-line {');
    const block = css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
    expect(block).toMatch(/justify-content:\s*space-between/);
    expect(block).toMatch(/flex-wrap:\s*wrap/);
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
    win.cc.session(payload);
    const dir = Array.from(win.document.querySelectorAll('.dash-mini-grid .mini-fact')).find((f) =>
      f.textContent.startsWith('Working dir:')
    );
    expect(dir.getAttribute('title')).toBe('Working dir: /home/dev/project');
  });

  it('is a row of the dock and cannot cover anything', () => {
    // The composer's own bug class: every waiting screen and panel that ever covered the transcript, the tabs
    // or the prompt box did it by leaving the flow. This one is a sibling of the plan-limit bars, so the
    // transcript gives up the space and nothing is laid over anything.
    win.cc.session(payload);
    expect(fold().previousSibling).toBe(win.document.querySelector('.usage-bars'));
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const at = css.indexOf('.dash-mini {');
    const block = css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
    expect(block).not.toMatch(/position:\s*(fixed|absolute)/);
    expect(block).not.toMatch(/z-index/);
  });

  it('is bounded and scrolls, and its value column cannot widen the dock', () => {
    // In the flow, height is the only way it can hurt: uncapped, a long session would grow the dock until the
    // transcript had nowhere to be. Width is the other: a working directory is one unbreakable token, so
    // without `overflow-wrap` it pushes the whole strip past the composer.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const rule = (selector) => {
      const at = css.indexOf(selector + ' {');
      return css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
    };
    expect(rule('.dash-mini-body')).toMatch(/max-height:\s*min\(/);
    expect(rule('.dash-mini-body')).toMatch(/overflow-y:\s*auto/);
    // A working directory is one long unbreakable token: it is clipped with an ellipsis (the full text is in
    // the pair's tooltip) rather than allowed to widen the strip past the composer.
    expect(rule('.mini-val')).toMatch(/text-overflow:\s*ellipsis/);
    expect(rule('.mini-val')).toMatch(/min-width:\s*0/);
  });
});
