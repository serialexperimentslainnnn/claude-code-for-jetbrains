// JS↔CSS class contract. Every class the web app emits from a `class: '...'` literal should have a matching rule
// in app.css. This is exactly the check that would have caught `.mcp-actions` (a class the JS emitted with NO css
// rule, so the MCP row's buttons wrapped and overlapped). It guards against NEW un-styled classes; the classes
// that exist today without a dedicated rule (they inherit, or are covered by descendant selectors) are an explicit
// grandfathered baseline below — tightening that list is fine, growing it should require a deliberate edit.
const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles } = require('./helpers/load');

// Classes emitted by the JS that intentionally have no dedicated `.<class>{...}` rule today. A NEW class not in
// this set and not in app.css fails the test.
const GRANDFATHERED = new Set([
  'dash-empty', 'elicit-desc', 'elicit-extra', 'elicit-field-label', 'fold-label',
  'legend-item', 'legend-name', 'legend-swatch', 'legend-tokens', 'menu-item-label',
  'palette-list', 'perm-always', 'perm-blocked', 'perm-desc', 'perm-reason', 'perm-summary',
  'pill-vibe', 'q-block', 'q-header', 'queue-text', 'subagent-desc', 'subagent-main',
  'subagent-meta', 'tool-output',
]);

function cssClassNames() {
  const css = fs.readFileSync(path.join(JCEF, 'app.css'), 'utf8');
  return new Set([...css.matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]));
}

function jsEmittedClasses() {
  const used = new Set();
  for (const f of appJsFiles()) {
    const src = fs.readFileSync(path.join(JCEF, f), 'utf8');
    for (const m of src.matchAll(/class:\s*(["'])([^"']+)\1/g)) {
      for (const c of m[2].split(/\s+/)) {
        // Drop empty tokens and dynamic prefixes like `att-` (built as `'att-' + kind`) — not statically checkable.
        if (c && !c.endsWith('-')) used.add(c);
      }
    }
  }
  return used;
}

describe('JS↔CSS class contract', () => {
  it('every class the JS emits has a CSS rule (or is grandfathered)', () => {
    const css = cssClassNames();
    const missing = [...jsEmittedClasses()].filter((c) => !css.has(c) && !GRANDFATHERED.has(c)).sort();
    // If this fails: the JS emits a class with no `.<class>` rule in app.css — add the rule (or, if truly
    // style-free, add it to GRANDFATHERED with a reason). This caught the real `.mcp-actions` layout bug.
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
    const css = fs.readFileSync(path.join(JCEF, 'app.css'), 'utf8');
    if (/\.body\s+a\s*\{[^}]*color\s*:/.test(css)) {
      expect(css).toMatch(/\.body\s+a\.jb-link/); // the more specific override that beats it
    }
  });
});
