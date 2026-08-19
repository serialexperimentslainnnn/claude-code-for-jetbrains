const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles, readCss } = require('./helpers/load');
const { stripComments } = require('./helpers/source');

const GRANDFATHERED = new Set([
  'attach-body',
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
  'tool-output',
]);

function cssClassNames() {
  return new Set([...readCss().matchAll(/\.([a-zA-Z][\w-]*)/g)].map((m) => m[1]));
}

function classesIn(src) {
  const used = new Set();
  for (const m of stripComments(src).matchAll(/class:\s*(["'])([^"']+)\1/g)) {
    for (const c of m[2].split(/\s+/)) {
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
    expect(found.has('quoted-in-a-line-comment')).toBe(false);
    expect(found.has('quoted-in-a-block-comment')).toBe(false);
  });

  it('a string holding `//` is still code, and what follows it on the line is still scanned', () => {
    expect(found.has('after-a-url')).toBe(true);
  });

  it('a regex literal with an escaped slash does not swallow the rest of its line', () => {
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
    expect(missing).toEqual([]);
  });

  it('grandfathered classes are still actually emitted by the JS (no stale entries)', () => {
    const used = jsEmittedClasses();
    const stale = [...GRANDFATHERED].filter((c) => !used.has(c)).sort();
    expect(stale).toEqual([]);
  });

  it('jump-to-code links out-specify the generic .body a rule', () => {
    const css = readCss();
    if (/\.body\s+a\s*\{[^}]*color\s*:/.test(css)) {
      expect(css).toMatch(/\.body\s+a\.jb-link/);
    }
  });
});

describe('layer containment', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

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
    expect(ruleBody('#work')).toMatch(/position:\s*relative/);
    expect(zIndexOf('#work')).toBe(0);
    expect(ruleBody('#tabsbar')).toMatch(/position:\s*relative/);
    expect(zIndexOf('#tabsbar')).toBeGreaterThan(zIndexOf('#work'));
  });

  it('the dock outranks every layer over the transcript, and the transcript is the floor', () => {
    expect(zIndexOf('#conversation')).toBe(0);
    expect(zIndexOf('.dashboard')).toBeGreaterThan(zIndexOf('#conversation'));
    expect(zIndexOf('#dock')).toBeGreaterThan(zIndexOf('.dashboard'));
  });

  it('the find bar is anchored inside the work area, never over the tab bar', () => {
    expect(ruleBody('.find-bar')).toMatch(/position:\s*absolute/);
    expect(ruleBody('.find-bar')).not.toMatch(/position:\s*fixed/);
    expect(zIndexOf('.find-bar')).toBeGreaterThan(zIndexOf('#dock'));
    expect(zIndexOf('.find-bar')).toBeLessThan(10);
  });

  it('the palette hangs off the prompt card and only ever upwards', () => {
    const palette = ruleBody('#palette');
    expect(palette).toMatch(/position:\s*absolute/);
    expect(palette).not.toMatch(/position:\s*fixed/);
    expect(palette).toMatch(/bottom:\s*100%/);
    expect(ruleBody('.composer-card')).toMatch(/position:\s*relative/);
    expect(ruleBody('.palette-box')).toMatch(/max-height:\s*calc\([\d.]+ \* var\(--palette-row\)\)/);
  });
});
