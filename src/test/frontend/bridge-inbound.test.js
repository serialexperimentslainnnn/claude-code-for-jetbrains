// Kotlin↔JS bridge contract, the OTHER two directions.
//
// `bridge-contract.test.js` next door checks host→page: every `window.cc.<name>` the Kotlin sources call
// resolves to a real method in the assembled page. That leaves the two directions that let a half-wired
// bridge survive a release, and both of them have:
//
//   page→host: `JcefBridge` parses a message `type` no module ever sends. The parse is written, the router
//              has a branch for it, `JcefBridgeTest` proves the parse works — and the string never arrives.
//              (Real: `"palette"`, parsed since 4.0.0, routed to `{}`, sent by nothing.)
//
//   host→page: the page registers a `cc.<name>` nobody calls. It reads as implemented from inside the page
//              and as absent from outside it. (Real: `cc.showAuth`, `cc.diagnostics`.)
//
// Neither is visible at runtime: an unsent message type costs nothing, and an uncalled method is just an
// object property. Nothing throws, nothing logs, and the feature is simply not there — this repository's
// signature defect, in the one place where two languages meet and no compiler spans the gap.
//
// Same two rules as its neighbour. The inputs are DERIVED at test time (a list would go stale silently), and
// the last test drives the real detection over a synthetic case whose answer is known, because a verdict
// only ever observed green cannot be told apart from one that reports nothing.
//
// AND THE PROSE IS NOT PART OF THE EVIDENCE. Every question here is answered by "does this string appear in
// the sources", and a mention in a comment answers yes — so this file's own failure mode, turned on itself:
// a KDoc naming `window.cc.foo` counts as the host calling it, a paragraph writing `type: 'palette'` counts
// as the page sending it, and both of the defects below go quiet the moment somebody documents them. That is
// worse than the same blindness next door, where prose can only ever add a demand: here it REMOVES one. So
// every source is read as code (`helpers/source.js`), and each scan strips its own input rather than
// trusting whoever read it — which is also what lets the synthetic cases at the bottom drive the real thing.
const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, appJsFiles, readApp } = require('./helpers/load');
const { stripComments } = require('./helpers/source');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');
const BRIDGE_KT = path.join(KOTLIN_SRC, 'dev/lain/claudejb/ui/jcef/JcefBridge.kt');

// A `when` branch over a message type: `"cycleMode" -> Msg.CycleMode`. The whole inbound surface is written
// this way, in the parse* functions of the one file — which is also why the scan can be this narrow.
const PARSED_TYPE = /^\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*->/gm;

// A `type:` key in an object literal, and every string in whatever it is set to. The value is not always a
// literal — the account row sends `type: acct.loggedIn ? 'logout' : 'loginSubscription'` — so both arms are
// collected rather than parsed.
const TYPE_KEY = /\btype\s*:\s*([^,\n}]+)/g;
const QUOTED = /'([^']+)'|"([^"]+)"/g;

/** Every message type the Kotlin bridge knows how to parse. */
function parsedTypes() {
  return [...stripComments(fs.readFileSync(BRIDGE_KT, 'utf8')).matchAll(PARSED_TYPE)].map((m) => m[1]);
}

/** The app sources as they are handed to the scans below: raw, because each of them strips its own input. */
function pageSources() {
  return appJsFiles().map(readApp);
}

/**
 * Every string the page uses as a `type`, from the app modules as `JcefHost.appNames` declares them.
 *
 * Deliberately NOT narrowed to the inside of a `CC.send(…)` call: payloads are built in several shapes (a
 * literal at the call, an object returned by a pill builder, a table of descriptors), and a scan that only
 * recognised one of them would report the others as unsent — a false RED that gets the gate edited.
 *
 * The cost is a few DOM values in the set (`type: 'button'` on a created element), which can only ever make
 * this check more lenient, and only for a message named after one of them.
 *
 * A `type:` written in a COMMENT is not in the set, and that one is not a leniency to accept: it would mark
 * a message as sent because somebody described it, which is exactly how `"palette"` survives being written
 * about for a year and a half without ever being sent.
 */
function pageTypeStrings(sources = pageSources()) {
  const strings = new Set();
  for (const text of sources) {
    for (const key of stripComments(text).matchAll(TYPE_KEY)) {
      for (const quoted of key[1].matchAll(QUOTED)) strings.add(quoted[1] || quoted[2]);
    }
  }
  return strings;
}

/** The types in [parsed] that nothing in [sent] ever puts on the wire. */
function unsent(parsed, sent) {
  return parsed.filter((type) => !sent.has(type)).map((type) => '"' + type + '"');
}

/**
 * The Kotlin sources as one string — where `window.cc.<name>` calls live.
 *
 * Stripped per FILE and then joined, not the other way round: an unterminated construct at the end of one
 * file would otherwise be read into the next one's first line, and the boundary between two files is not a
 * place where a lexer should have to be right.
 */
function kotlinText() {
  return fs
    .readdirSync(KOTLIN_SRC, { recursive: true })
    .filter((f) => f.endsWith('.kt'))
    .map((rel) => stripComments(fs.readFileSync(path.join(KOTLIN_SRC, rel), 'utf8')))
    .join('\n');
}

/** The page's own uses of the surface: `cc.foo(`, under any alias of `window.cc` a module gives it. */
function pageCallsTo(name, sources) {
  const call = new RegExp('\\.' + name + '\\s*\\(');
  return sources.some((text) => call.test(stripComments(text)));
}

/**
 * The [registry] methods that neither the host nor the page ever calls.
 *
 * Both texts are stripped here, and both are the same claim: a name written in a comment is a description of
 * a call, not one. `stripComments` is memoised and idempotent, so a caller that already read its sources as
 * code pays a map lookup for saying so twice.
 */
function uncalled(registry, hostText, sources = pageSources()) {
  const host = stripComments(hostText);
  return Object.keys(registry)
    .filter((name) => typeof registry[name] === 'function')
    .filter((name) => !host.includes('window.cc.' + name))
    .filter((name) => !pageCallsTo(name, sources))
    .map((name) => 'cc.' + name);
}

describe('Kotlin↔JS bridge — page→host', () => {
  // Without this the comparison below is `[] ⊆ anything`, which is green forever.
  it('finds the inbound message types it is supposed to be checking', () => {
    expect(parsedTypes().length).toBeGreaterThan(20);
  });

  it('has no parsed message type the page never sends', () => {
    expect(unsent(parsedTypes(), pageTypeStrings())).toEqual([]);
  });

  // The verdict, over the page as it really is, on a type whose answer is known — `"palette"` was exactly
  // this, and shipped for a year and a half.
  it('reports a parsed type nothing sends', () => {
    const sent = pageTypeStrings();

    expect(unsent(['ready', '__ccNoModuleSendsThis'], sent)).toEqual(['"__ccNoModuleSendsThis"']);
  });

  it('a type written in prose is a description of a message, not one being sent', () => {
    // The dangerous direction: a comment here does not add a demand, it withdraws one. Documenting a message
    // would mark it as sent and take the check off exactly the type somebody just wrote a paragraph about —
    // which is the shape `"palette"` had, described in the router and never put on the wire by anything.
    const page = [
      "// Opened by the composer: send({ type: 'only-in-a-line-comment' })",
      '/** and the block form, `{ type: "only-in-a-block-comment" }`, which the router still parses. */',
      "CC.send({ type: 'really-sent' });",
    ].join('\n');
    const sent = pageTypeStrings([page]);

    expect([...sent]).toEqual(['really-sent']);
    expect(unsent(['only-in-a-line-comment', 'only-in-a-block-comment', 'really-sent'], sent)).toEqual([
      '"only-in-a-line-comment"',
      '"only-in-a-block-comment"',
    ]);
  });
});

describe('Kotlin↔JS bridge — host→page', () => {
  it('has no cc.<name> the host and the page both ignore', () => {
    // Every family, in `JcefHost.appNames` order: a method registered by a module left out would read as
    // absent, which is the opposite mistake and just as wrong.
    const win = loadFrontend(appJsFiles());

    expect(uncalled(win.cc, kotlinText())).toEqual([]);
  });

  // The verdict, on a case whose answer is known: the assertions above are green while the bridge is whole,
  // and a green verdict proves nothing about whether the detection reaches a result.
  it('reports a method neither side calls', () => {
    const registry = { __ccNobodyCallsThis: function () {}, trimRows: function () {} };

    expect(uncalled(registry, 'exec("window.cc.trimRows()")')).toEqual(['cc.__ccNobodyCallsThis']);
  });

  it('a method whose only mention on either side is a comment is still uncalled', () => {
    // Both arms of the filter, because a comment on either side is enough to silence the whole check: the
    // host arm looks for `window.cc.<name>` in the Kotlin, the page arm for `.<name>(` in the modules. A
    // method kept alive by a KDoc is dead code that reads as wired, which is what this file exists to find.
    const registry = { __ccOnlyDescribed: function () {}, __ccReallyCalled: function () {} };
    const host = '/** Was window.cc.__ccOnlyDescribed. */ exec("window.cc.__ccReallyCalled()")';
    const page = ['// the transcript used to call cc.__ccOnlyDescribed(rows) from here', 'cc.other();'];

    expect(uncalled(registry, host, page)).toEqual(['cc.__ccOnlyDescribed']);
  });
});
