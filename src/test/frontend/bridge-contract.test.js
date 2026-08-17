// Kotlin↔JS bridge contract. The host reaches into the page with exactly one idiom:
//
//     exec("window.cc.trimRows && window.cc.trimRows({ids:[…],total:…})")
//
// That `&&` is a guard against load ordering — a module may not have registered its method yet when the host
// pushes. Its cost is that a method NO module ever registers is indistinguishable from one that has not
// registered yet: the call evaluates to `false` and returns. No exception, no console entry, nothing in
// idea.log at any level, in any IDE. The feature is simply absent, forever, while the Kotlin side is
// implemented, reviewed and covered by its own tests.
//
// This file is the gate for that: every `window.cc.<name>` named in the Kotlin sources must resolve to a real
// implementation in the page as it is actually assembled.
//
// Two design decisions, both load-bearing:
//
// 1. The call sites are DERIVED by scanning `src/main/kotlin` at test time, never listed here. A list would
//    go stale silently, and a gate that asserts a COUNT is a gate whose count gets edited when it goes red.
//
// 2. The check runs against the LOADED page, not against a grep of the JS. The modules attach their methods
//    under three different spellings today (`cc.x =`, `window.cc.x =`, and `c.x =` where `c` is a local alias
//    for `window.cc`), so a source scan has to guess at aliasing, and guessing wrong here yields a false
//    GREEN — the one outcome a gate must never produce. Loading the page answers the real question: after the
//    modules run, in `JcefHost.appNames` order, is the method there?
//
// The landmine that decides the assertion: `app-core.js` installs null-safe no-op fallbacks for thirteen of
// these names, so that a host push arriving before the modules load is absorbed rather than thrown. They make
// `typeof window.cc.x === 'function'` TRUE for those thirteen even when nothing implements them — a gate
// checking only that property would be fooled by the page's own safety net. So an empty function body counts
// as NOT implemented, and the third test below pins that the fallbacks really do have the shape this
// recognises: if they ever grow a body, this gate starts certifying dead calls and must be told.
//
// The scan and the verdict are therefore separated from the disk and from the page ([scanCalls] and
// [unimplemented] take their inputs), so the last test can drive the real detection over a case whose answer
// is known and show that it reports the miss. A gate observed only while green cannot be told apart from one
// that never reports anything.
const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, appJsFiles, readApp } = require('./helpers/load');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');

// `window.cc.<identifier>`. Prose mentions of the surface as a whole (`window.cc.*`) do not match, which is
// why the identifier class is required rather than optional.
//
// Comment lines are scanned like any other line, DELIBERATELY, and that is the opposite of the rule
// `GitReadOnlyContractTest` applies to its own subject. Both are correct: a dependency named in prose is
// genuinely not a usage of it, whereas a KDoc naming a concrete `window.cc.<method>` is a claim that the
// method exists, and that claim is worth checking. The asymmetry settles it — a false positive here costs
// one doc edit, a false negative defeats the gate. So the two policies are not an inconsistency to
// reconcile: narrowing this one to match the other would remove a detection path.
const BRIDGE_CALL = /window\.cc\.([A-Za-z_$][A-Za-z0-9_$]*)/g;

// A function taking nothing and doing nothing: the shape of `app-core.js`'s fallbacks, and of any stub.
const NO_OP = /^function\s*[A-Za-z0-9_$]*\s*\(\s*\)\s*\{\s*\}$/;

const isNoOp = (fnOrSource) => NO_OP.test(String(fnOrSource).trim());

/** Every `window.cc.<name>` in [sources] (`{ file, text }`), mapped to the `file:line` sites naming it. */
function scanCalls(sources) {
  const calls = new Map();
  for (const { file, text } of sources) {
    text.split('\n').forEach((line, index) => {
      for (const match of line.matchAll(BRIDGE_CALL)) {
        if (!calls.has(match[1])) calls.set(match[1], new Set());
        calls.get(match[1]).add(`${file}:${index + 1}`);
      }
    });
  }
  return calls;
}

/** The Kotlin host sources, in the shape [scanCalls] takes. */
function kotlinSources() {
  return fs
    .readdirSync(KOTLIN_SRC, { recursive: true })
    .filter((f) => f.endsWith('.kt'))
    .map((rel) => ({ file: rel, text: fs.readFileSync(path.join(KOTLIN_SRC, rel), 'utf8') }));
}

/** The calls [registry] does not implement — the name is absent, or bound to a method that does nothing. */
function unimplemented(calls, registry) {
  const dead = [];
  for (const [name, sites] of calls) {
    const method = registry[name];
    if (typeof method !== 'function' || isNoOp(method)) {
      dead.push(`cc.${name} — called from ${[...sites].join(', ')}`);
    }
  }
  return dead;
}

describe('Kotlin↔JS bridge — every host call reaches a real implementation', () => {
  // Without this, a scan that stops matching (the idiom changes, the sources move) turns the gate below into
  // a green that checks nothing — the failure this whole file exists to make impossible.
  it('finds the host call sites it is supposed to be checking', () => {
    expect([...scanCalls(kotlinSources()).keys()]).not.toEqual([]);
  });

  it('resolves every window.cc.<name> the host calls to an implemented method', () => {
    // Every family, so every module in `JcefHost.appNames` runs, in that order. A subset would be a page the
    // product never serves, and the method under test might live in the part left out.
    const win = loadFrontend(appJsFiles());

    expect(unimplemented(scanCalls(kotlinSources()), win.cc)).toEqual([]);
  });

  // The fallbacks are what makes `typeof … === 'function'` a useless test for these thirteen names. This
  // asserts the recognition above still matches them: if it stops, names with no implementation start
  // reading as implemented and the gate silently approves the exact defect it was built to catch.
  it('recognises app-core.js null-safe fallbacks as unimplemented', () => {
    const fallbacks = [
      ...readApp('app-core.js').matchAll(
        /typeof cc\.([A-Za-z0-9_$]+) !== 'function'\)\s*cc\.\1\s*=\s*(function\s*\([^)]*\)\s*\{[^}]*\})/g
      ),
    ];

    expect(fallbacks).not.toEqual([]);
    expect(fallbacks.filter((m) => !isNoOp(m[2])).map((m) => `cc.${m[1]}`)).toEqual([]);
  });

  // The verdict, on a case whose answer is known. The three tests above cannot establish this one: the gate
  // is green while every method exists, and a verdict seen only green is indistinguishable from a verdict
  // that reports nothing; the scan test proves calls are found, not that a miss is called a miss; the
  // fallback test proves the predicate matches, not that the predicate reaches the result. This drives the
  // real `scanCalls` and the real `unimplemented` over injected input — repeating their logic here would
  // prove only that the same regex can be written twice, and would stay green while the real pair rotted.
  it('reports a call whose method no module implements', () => {
    const missing = '__ccNoModuleImplementsThis';
    const host = [{ file: 'Synthetic.kt', text: `exec("window.cc.${missing} && window.cc.${missing}()")` }];
    const calls = scanCalls(host);
    const reported = [`cc.${missing} — called from Synthetic.kt:1`];

    // Absent from the real page, loaded exactly as the gate loads it.
    expect(unimplemented(calls, loadFrontend(appJsFiles()).cc)).toEqual(reported);
    // And present as a no-op: an empty implementation is unreachable behaviour wearing a name, so it is a
    // miss too — this is the arm that would go quiet if the empty-body rule ever stopped reaching the result.
    expect(unimplemented(calls, { [missing]: function () {} })).toEqual(reported);
  });
});
