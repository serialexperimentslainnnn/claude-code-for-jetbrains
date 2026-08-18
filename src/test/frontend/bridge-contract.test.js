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
const { stripComments } = require('./helpers/source');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');

// `window.cc.<identifier>`. Prose mentions of the surface as a whole (`window.cc.*`) do not match, which is
// why the identifier class is required rather than optional.
//
// COMMENTS ARE NOT SCANNED, and what settles it is that `bridge-inbound.test.js` reads the SAME Kotlin text
// with the opposite consequence. Here a name found only in prose demands an implementation for a call nobody
// makes — noise, a red over a sentence. There, the same mention is taken as PROOF that the host calls the
// method, which silences the dead-method check: a `cc.<name>` nothing calls stays green because a KDoc
// mentions it. One text cannot be code in one gate and prose in the other, and between a false red and a
// false green the direction that HIDES a defect decides which reading both of them take.
//
// What that gives up is worth naming rather than glossing: a KDoc claiming a `window.cc.<method>` that does
// not exist is no longer caught here. It never was a claim about the product's behaviour — the code is — and
// a stale sentence costs a doc edit, which is not a trade against the check that finds dead code.
//
// The strip is a token walk and never a regex (`helpers/source.js`), and that is load-bearing HERE rather
// than merely careful: every one of these calls lives inside a Kotlin string literal, so a strip that ate
// string interiors would delete the whole call surface at once and leave this gate green over nothing.
const BRIDGE_CALL = /window\.cc\.([A-Za-z_$][A-Za-z0-9_$]*)/g;

// A function taking nothing and doing nothing: the shape of `app-core.js`'s fallbacks, and of any stub.
const NO_OP = /^function\s*[A-Za-z0-9_$]*\s*\(\s*\)\s*\{\s*\}$/;

const isNoOp = (fnOrSource) => NO_OP.test(String(fnOrSource).trim());

/**
 * Every `window.cc.<name>` in [sources] (`{ file, text }`), mapped to the `file:line` sites naming it.
 *
 * The text arrives raw and is stripped HERE rather than by whoever read it, so the synthetic case at the
 * bottom of this file drives the same reading the real one does. The strip keeps the line structure — a
 * block comment leaves its newlines behind — because these citations are the only thing a reader has to go
 * on, and a lexer that renumbered a file would point every one of them somewhere else.
 */
function scanCalls(sources) {
  const calls = new Map();
  for (const { file, text } of sources) {
    const code = stripComments(text);
    code.split('\n').forEach((line, index) => {
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

  it('reads the code and not the prose around it, without moving the line numbers', () => {
    // Three claims, and the fixture is one Kotlin file because they are one behaviour. A KDoc naming a
    // method is not a call — the sibling gate reads the same text as proof that one exists, and one text
    // cannot be code there and prose here. A real call IS found, and it is found inside a string literal,
    // which is where every one of them lives. And the citation still points at the right line: the block
    // comment leaves its newlines behind, so the call is reported at 4 and not at 2.
    const source = [
      {
        file: 'Synthetic.kt',
        text: [
          '/** Pushes the rows the transcript has dropped.',
          ' *  Was `window.cc.__ccOnlyInAKdoc`, before the tab bar owned it. */',
          'fun push() =',
          '    exec("window.cc.__ccARealCall(1)") // window.cc.__ccOnlyInALineComment',
        ].join('\n'),
      },
    ];
    const calls = scanCalls(source);

    expect([...calls.keys()]).toEqual(['__ccARealCall']);
    expect([...calls.get('__ccARealCall')]).toEqual(['Synthetic.kt:4']);
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
