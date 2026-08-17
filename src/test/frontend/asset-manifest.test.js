// Every asset on disk is one the host actually serves — and every asset the host names is on disk.
//
// The page has no module system and no bundler. `JcefHost.buildPage` assembles ONE document out of two
// declared, ordered lists: `appNames` for the `app-*.js` modules and `CSS_PARTS` for the stylesheet parts.
// Nothing globs the directory, deliberately — order is semantics in both lists (the modules meet through
// `window.cc`/`window.CC`, and later CSS rules win), so alphabetical would be a different product.
//
// The cost of that decision is that the directory and the manifest are two independent facts, and NOTHING
// reconciles them:
//
//   - A module written, reviewed, tested and never added to `appNames` is never served. Its own frontend
//     tests load it directly through the harness and pass, because the harness loads what it is asked for;
//     the css contract scans the file like any other; the page simply never runs it. That is this
//     repository's signature defect — implemented, documented, tested and reachable from nothing — in the
//     one place where the wiring is a string in a Kotlin list rather than a call the compiler can see.
//   - A part added to `css/` and not to `CSS_PARTS` is a stylesheet the page never gets. The classes it
//     defines read as covered (`css-contract.test.js` reads the same declared list, so the part is simply
//     not among the rules it checks) and every element it styles renders unstyled in the IDE.
//
// The other direction is the same defect mirrored, and it is even quieter: `buildPage` reads each name with
// `readResource(name)?.let { … }` and SKIPS what it cannot find. A name left in the list after the file was
// renamed or deleted costs no error at build time, no error at load time, and no line in idea.log — the
// script block for it is simply absent from the document.
//
// Neither direction is observable at runtime, which is why it is a gate and not a review item: an absent
// module throws nothing (the host's own `window.cc.x && window.cc.x(…)` idiom absorbs the calls into it —
// see `bridge-contract.test.js`), and absent CSS is a layout nobody can distinguish from a design decision.
//
// Both lists are read from `JcefHost.kt` through `helpers/load.js`, the harness's ONE parser of them, so a
// second reader cannot start disagreeing with the one the tests load the page with. The comparison itself
// ([missing]) takes both sides as arguments, so the last test can drive the real detection over a synthetic
// pair whose answer is known: a verdict only ever observed green cannot be told apart from one that reports
// nothing, and this gate is green for as long as nobody makes the mistake it exists for.
const fs = require('node:fs');
const path = require('node:path');
const { appJsFiles, appModules, cssParts, JCEF } = require('./helpers/load');

/** The stylesheet parts present in `css/`, whatever the host declares. */
function cssFiles() {
  return fs.readdirSync(path.join(JCEF, 'css')).filter((f) => f.endsWith('.css'));
}

/** The entries of [these] that [those] does not contain, in the order they appear. */
function missing(these, those) {
  const declared = new Set(those);
  return these.filter((name) => !declared.has(name));
}

describe('the page manifest and the directory agree — app modules', () => {
  // Without this both assertions below are `[] ⊆ anything`: an empty directory read or an empty list parse
  // is green forever, and the parse is the fragile half (it slices Kotlin source between two literals).
  it('finds modules on disk and modules declared by the host', () => {
    expect(appJsFiles().length).toBeGreaterThan(20);
    expect(appModules().length).toBeGreaterThan(20);
  });

  it('serves every app-*.js that exists', () => {
    expect(missing(appJsFiles(), appModules())).toEqual([]);
  });

  it('declares no app-*.js that does not exist', () => {
    expect(missing(appModules(), appJsFiles())).toEqual([]);
  });
});

describe('the page manifest and the directory agree — stylesheet parts', () => {
  it('finds parts on disk and parts declared by the host', () => {
    expect(cssFiles().length).toBeGreaterThan(3);
    expect(cssParts().length).toBeGreaterThan(3);
  });

  it('serves every css/*.css that exists', () => {
    expect(missing(cssFiles(), cssParts())).toEqual([]);
  });

  it('declares no css/*.css that does not exist', () => {
    expect(missing(cssParts(), cssFiles())).toEqual([]);
  });
});

describe('the manifest comparison reaches a verdict', () => {
  // The real detection, over a case whose answer is known — in BOTH directions, because they fail for
  // different reasons (a module nobody loads / a name whose file went away) and a comparison that only ever
  // ran one way round would be half a gate. The inputs are synthetic on purpose: driving this over the real
  // lists would only re-assert that they currently match, which is what the four tests above already do.
  it('reports an undeclared file and an undelivered declaration', () => {
    const onDisk = ['app-core.js', 'app-orphan.js'];
    const declared = ['app-core.js', 'app-ghost.js'];

    expect(missing(onDisk, declared)).toEqual(['app-orphan.js']);
    expect(missing(declared, onDisk)).toEqual(['app-ghost.js']);
  });

  // The blind spots, stated rather than left to be found. This gate answers "is the file in the list", which
  // is not "is the file reachable":
  //  - ORDER is not checked here. A module listed before the namespace it extends is served and still broken;
  //    that is a load-order contract and it belongs to whoever changes the order, not to a set comparison.
  //  - A module that is declared, served, and whose every function nothing ever calls passes this and is
  //    just as dead. `bridge-contract.test.js` and `bridge-inbound.test.js` cover the host↔page half of that
  //    question; nothing covers a page-internal function no other module calls.
  //  - `shell.html`, the vendored libraries and everything else under `jcef/` are outside the two lists and
  //    outside this gate.
  it('is a membership check, not a reachability proof', () => {
    // The order-blindness above, pinned rather than described: reversing the declared list changes what the
    // page IS and nothing here notices. If this ever starts failing, the comparison has grown an opinion
    // about order and the comment above it has become a lie.
    expect(missing(appJsFiles(), [...appModules()].reverse())).toEqual([]);
  });
});
