// JS↔CSS class contract. Every class the web app emits from a `class: '...'` literal should have a matching rule
// in the stylesheet. This is exactly the check that would have caught `.mcp-actions` (a class the JS emitted with
// NO css rule, so the MCP row's buttons wrapped and overlapped). It guards against NEW un-styled classes; the
// classes that exist today without a dedicated rule (they inherit, or are covered by descendant selectors) are an
// explicit grandfathered baseline below — tightening that list is fine, growing it should require a deliberate edit.
const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles, readCss } = require('./helpers/load');
const { stripComments } = require('./helpers/source');

// Classes emitted by the JS that intentionally have no dedicated `.<class>{...}` rule today. A NEW class not in
// this set and not in the stylesheet fails the test.
const GRANDFATHERED = new Set([
  'dash-empty',
  'elicit-desc',
  'elicit-extra',
  'elicit-field-label',
  'fold-label',
  'legend-item',
  'legend-name',
  'legend-swatch',
  'legend-tokens',
  'menu-item-label',
  'palette-list',
  'perm-always',
  'perm-blocked',
  'perm-desc',
  'perm-reason',
  'perm-summary',
  'pill-vibe',
  'q-block',
  'q-header',
  'queue-text',
  // NB `subagent-desc` / `subagent-main` / `subagent-meta` lived here until 5.5.0. They were not
  // exceptions: the CSS defined `.sub-desc` / `.sub-main` / `.sub-meta` and the JS emitted the longer
  // names, so the rules never applied and the rows rendered as one run-on line. Grandfathering them turned
  // a rendering bug into an approved exception — exactly what this list must not be used for.
  'tool-output',
]);

function cssClassNames() {
  return new Set([...readCss().matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]));
}

/**
 * The classes ONE source emits — the prose it is documented with is not one of them.
 *
 * `stripComments` is shared with the two bridge gates (`helpers/source.js`, which is where the reasoning
 * lives): this repository documents by quoting the code a comment is about, so a paragraph explaining how a
 * class is emitted reads exactly like the emission, and the gate would go red over a sentence.
 *
 * Separate from the sweep below so the scanner itself can be driven over a case whose answer is known.
 */
function classesIn(src) {
  const used = new Set();
  for (const m of stripComments(src).matchAll(/class:\s*(["'])([^"']+)\1/g)) {
    for (const c of m[2].split(/\s+/)) {
      // Drop empty tokens and dynamic prefixes like `att-` (built as `'att-' + kind`) — not statically checkable.
      if (c && !c.endsWith('-')) used.add(c);
    }
  }
  return used;
}

function jsEmittedClasses() {
  const used = new Set();
  for (const f of appJsFiles()) {
    for (const c of classesIn(fs.readFileSync(path.join(JCEF, f), 'utf8'))) used.add(c);
  }
  return used;
}

/**
 * The scanner, driven on its own. It decides what "the JS emits this class" MEANS, so every way it can be
 * wrong is a way the contract above can be wrong — in one direction loudly, in the other silently.
 *
 * `String.raw` because the fixture contains a real regex literal with escaped slashes, and an ordinary
 * template would hand the scanner a source with the backslashes already gone — testing a shape that never
 * reaches it.
 */
const SCANNER_FIXTURE = String.raw`
// WRITTEN OUT, because the gate scans for a class key followed by: class: 'quoted-in-a-line-comment'.
/* and the same trap in the block form: class: 'quoted-in-a-block-comment' */
h('a', { href: 'https://example.com//x', class: 'after-a-url' });
if (/^https?:\/\//i.test(u)) h('b', { class: 'after-a-regex' });
h('c', { class: 'two tokens' });
h('d', { class: 'att-' + kind });
`;

describe('the scanner that decides what counts as an emission', () => {
  const found = classesIn(SCANNER_FIXTURE);

  it('prose that quotes an emission is not one, in either comment form', () => {
    // The repository documents by quoting the code it is explaining, so this is the normal case and not an
    // unlucky line: a comment about how a class is emitted reads exactly like the emission.
    expect(found.has('quoted-in-a-line-comment')).toBe(false);
    expect(found.has('quoted-in-a-block-comment')).toBe(false);
  });

  it('a string holding `//` is still code, and what follows it on the line is still scanned', () => {
    // The whole reason the strip is a token walk. A regex that deleted from `//` to the end of the line
    // would swallow the class on THIS line, and a missing class is a gate that passes while protecting
    // nothing — the failure the comment fix must not buy.
    expect(found.has('after-a-url')).toBe(true);
  });

  it('a regex literal with an escaped slash does not swallow the rest of its line', () => {
    // Not hypothetical: `app-permissions.js` tests URLs with exactly this literal.
    expect(found.has('after-a-regex')).toBe(true);
  });

  it('still splits a multi-class literal and still skips a concatenated one', () => {
    expect(found.has('two')).toBe(true);
    expect(found.has('tokens')).toBe(true);
    expect(found.has('att-')).toBe(false);
  });
});

describe('JS↔CSS class contract', () => {
  it('every class the JS emits has a CSS rule (or is grandfathered)', () => {
    const css = cssClassNames();
    const missing = [...jsEmittedClasses()].filter((c) => !css.has(c) && !GRANDFATHERED.has(c)).sort();
    // If this fails: the JS emits a class with no `.<class>` rule anywhere in the concatenated `css/*.css`
    // parts (`readCss` reads them off `JcefHost.CSS_PARTS`) — add the rule to the part it belongs to, or, if
    // truly style-free, add it to GRANDFATHERED with a reason. This caught the real `.mcp-actions` layout bug.
    expect(missing).toEqual([]);
  });

  it('grandfathered classes are still actually emitted by the JS (no stale entries)', () => {
    const used = jsEmittedClasses();
    const stale = [...GRANDFATHERED].filter((c) => !used.has(c)).sort();
    expect(stale).toEqual([]);
  });

  /**
   * Specificity, not markup — a class of bug the DOM tests are blind to. `.body a` paints ordinary Markdown links
   * with the coral accent and OUT-SPECIFIES a bare `.jb-link`, so jump-to-code links inside model text rendered
   * coral while the identical ones on tool cards (outside `.body`) rendered blue. The override must stay.
   */
  it('jump-to-code links out-specify the generic .body a rule', () => {
    const css = readCss();
    if (/\.body\s+a\s*\{[^}]*color\s*:/.test(css)) {
      expect(css).toMatch(/\.body\s+a\.jb-link/); // the more specific override that beats it
    }
  });
});

/**
 * The stacking contract: which layer may cover which, stated in the stylesheet instead of emerging from
 * document order. jsdom lays nothing out, so the numbers themselves are the only thing there is to assert —
 * and they are the thing that broke twice, both times the same way: a waiting screen laid over the whole of
 * `#app` covered the chat tabs, and covered the composer once the tabs were spared.
 *
 * What makes those failures unrepeatable is not the numbers but the SCOPE: `#work` is a stacking context, so
 * a layer added inside the work area competes with its siblings there and with nothing else. The tab bar is
 * outside that context, and the dock outranks the cell the layers share.
 */
describe('layer containment', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

  /** The body of one top-level rule — exact selector plus ` {`, so `#work` is not `#work > *`. */
  function ruleBody(selector) {
    const at = css.indexOf(selector + ' {');
    if (at < 0) throw new Error('no rule for ' + selector);
    return css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  }
  const zIndexOf = (selector) => {
    const m = /z-index:\s*(-?\d+)/.exec(ruleBody(selector));
    if (!m) throw new Error('no z-index on ' + selector);
    return Number(m[1]);
  };

  it('the work area is a stacking context, so a layer inside it cannot out-number the tab bar', () => {
    // A positioned element with an explicit z-index opens one; `auto` would leave every number inside the
    // work area competing at the root, which is where 100s and 120s came from.
    expect(ruleBody('#work')).toMatch(/position:\s*relative/);
    expect(zIndexOf('#work')).toBe(0);
    expect(ruleBody('#tabsbar')).toMatch(/position:\s*relative/);
    expect(zIndexOf('#tabsbar')).toBeGreaterThan(zIndexOf('#work'));
  });

  it('the dock outranks every layer over the transcript, and the transcript is the floor', () => {
    // A grid row is not a clip: without this the only thing keeping a layer off the composer is that the
    // dock happens to come later in the document.
    expect(zIndexOf('#conversation')).toBe(0);
    expect(zIndexOf('.dashboard')).toBeGreaterThan(zIndexOf('#conversation'));
    expect(zIndexOf('#dock')).toBeGreaterThan(zIndexOf('.dashboard'));
  });

  it('the find bar is anchored inside the work area, never over the tab bar', () => {
    // `fixed` put it 12px below the top of the WINDOW, which is the tab row; in a narrow tool window it is
    // nearly as wide as that row, so a tab focused with the search open could be hidden outright — WCAG 2.2
    // SC 2.4.11. Anchored inside #work it is 12px below the tabs and cannot reach them at any width, which
    // is a different kind of guarantee from a z-index that happens to be lower.
    expect(ruleBody('.find-bar')).toMatch(/position:\s*absolute/);
    expect(ruleBody('.find-bar')).not.toMatch(/position:\s*fixed/);
    expect(zIndexOf('.find-bar')).toBeGreaterThan(zIndexOf('#dock'));
    expect(zIndexOf('.find-bar')).toBeLessThan(10);
  });

  it('the palette hangs off the prompt card and only ever upwards', () => {
    // Two failures are pinned here, in the two directions this has been wrong.
    //
    // It was `position: fixed` held clear of the composer by `padding-bottom: 72px` — a guess at a height
    // that changes with every pill, queued prompt and attachment chip, so the list covered the box you were
    // typing into exactly when you had most in it. `fixed` also measured against the VIEWPORT rather than the
    // tool window, the same mistake the find bar made. Then it was a row of the dock, which fixed the overlap
    // and put it above the plan-limit meters, a block away from the box it completes — and, being in the
    // flow, it pushed the card down as it opened, moving the text under the cursor.
    //
    // `bottom: 100%` against the CARD is what settles both: it is anchored to the thing it completes, and it
    // can only ever grow over what is above it. Covering the meters is fine; covering the composer is the bug.
    const palette = ruleBody('#palette');
    expect(palette).toMatch(/position:\s*absolute/);
    expect(palette).not.toMatch(/position:\s*fixed/);
    expect(palette).toMatch(/bottom:\s*100%/);
    // …which only means anything if the card is the containing block.
    expect(ruleBody('.composer-card')).toMatch(/position:\s*relative/);
    // Bounded in rows rather than in viewport height: `46vh` was half the window on a tall IDE.
    expect(ruleBody('.palette-box')).toMatch(/max-height:\s*calc\([\d.]+ \* var\(--palette-row\)\)/);
  });
});
