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
const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, appJsFiles, readApp } = require('./helpers/load');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');
const BRIDGE_KT = path.join(KOTLIN_SRC, 'dev/lain/claudejb/ui/jcef/JcefBridge.kt');

// A `when` branch over a message type: `"pickFiles" -> Msg.PickFiles`. The whole inbound surface is written
// this way, in the parse* functions of the one file — which is also why the scan can be this narrow.
const PARSED_TYPE = /^\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*->/gm;

// A `type:` key in an object literal, and every string in whatever it is set to. The value is not always a
// literal — the account row sends `type: acct.loggedIn ? 'logout' : 'loginSubscription'` — so both arms are
// collected rather than parsed.
const TYPE_KEY = /\btype\s*:\s*([^,\n}]+)/g;
const QUOTED = /'([^']+)'|"([^"]+)"/g;

/** Every message type the Kotlin bridge knows how to parse. */
function parsedTypes() {
  return [...fs.readFileSync(BRIDGE_KT, 'utf8').matchAll(PARSED_TYPE)].map((m) => m[1]);
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
 */
function pageTypeStrings() {
  const strings = new Set();
  for (const file of appJsFiles()) {
    for (const key of readApp(file).matchAll(TYPE_KEY)) {
      for (const quoted of key[1].matchAll(QUOTED)) strings.add(quoted[1] || quoted[2]);
    }
  }
  return strings;
}

/** The types in [parsed] that nothing in [sent] ever puts on the wire. */
function unsent(parsed, sent) {
  return parsed.filter((type) => !sent.has(type)).map((type) => '"' + type + '"');
}

/** The Kotlin sources as one string — where `window.cc.<name>` calls live. */
function kotlinText() {
  return fs
    .readdirSync(KOTLIN_SRC, { recursive: true })
    .filter((f) => f.endsWith('.kt'))
    .map((rel) => fs.readFileSync(path.join(KOTLIN_SRC, rel), 'utf8'))
    .join('\n');
}

/** The page's own uses of the surface: `cc.foo(`, under any alias of `window.cc` a module gives it. */
function pageCallsTo(name) {
  const call = new RegExp('\\.' + name + '\\s*\\(');
  return appJsFiles().some((file) => call.test(readApp(file)));
}

/** The [registry] methods that neither the host nor the page ever calls. */
function uncalled(registry, hostText) {
  return Object.keys(registry)
    .filter((name) => typeof registry[name] === 'function')
    .filter((name) => !hostText.includes('window.cc.' + name))
    .filter((name) => !pageCallsTo(name))
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
});
