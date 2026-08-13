// JS↔CSS class contract. Every class the web app emits from a `class: '...'` literal should have a matching rule
// in the stylesheet. This is exactly the check that would have caught `.mcp-actions` (a class the JS emitted with
// NO css rule, so the MCP row's buttons wrapped and overlapped). It guards against NEW un-styled classes; the
// classes that exist today without a dedicated rule (they inherit, or are covered by descendant selectors) are an
// explicit grandfathered baseline below — tightening that list is fine, growing it should require a deliberate edit.
const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles, readCss } = require('./helpers/load');

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
