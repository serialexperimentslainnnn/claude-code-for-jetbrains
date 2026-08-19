const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, appJsFiles, readApp } = require('./helpers/load');
const { stripComments } = require('./helpers/source');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');
const BRIDGE_KT = path.join(KOTLIN_SRC, 'dev/lain/claudejb/ui/jcef/JcefBridge.kt');

const PARSED_TYPE = /^\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*->/gm;

const TYPE_KEY = /\btype\s*:\s*([^,\n}]+)/g;
const QUOTED = /'([^']+)'|"([^"]+)"/g;

function parsedTypes() {
  return [...stripComments(fs.readFileSync(BRIDGE_KT, 'utf8')).matchAll(PARSED_TYPE)].map((m) => m[1]);
}

function pageSources() {
  return appJsFiles().map(readApp);
}

function pageTypeStrings(sources = pageSources()) {
  const strings = new Set();
  for (const text of sources) {
    for (const key of stripComments(text).matchAll(TYPE_KEY)) {
      for (const quoted of key[1].matchAll(QUOTED)) strings.add(quoted[1] || quoted[2]);
    }
  }
  return strings;
}

function unsent(parsed, sent) {
  return parsed.filter((type) => !sent.has(type)).map((type) => '"' + type + '"');
}

function kotlinText() {
  return fs
    .readdirSync(KOTLIN_SRC, { recursive: true })
    .filter((f) => f.endsWith('.kt'))
    .map((rel) => stripComments(fs.readFileSync(path.join(KOTLIN_SRC, rel), 'utf8')))
    .join('\n');
}

function pageCallsTo(name, sources) {
  const call = new RegExp('\\.' + name + '\\s*\\(');
  return sources.some((text) => call.test(stripComments(text)));
}

function uncalled(registry, hostText, sources = pageSources()) {
  const host = stripComments(hostText);
  return Object.keys(registry)
    .filter((name) => typeof registry[name] === 'function')
    .filter((name) => !host.includes('window.cc.' + name))
    .filter((name) => !pageCallsTo(name, sources))
    .map((name) => 'cc.' + name);
}

describe('Kotlin↔JS bridge — page→host', () => {
  it('finds the inbound message types it is supposed to be checking', () => {
    expect(parsedTypes().length).toBeGreaterThan(20);
  });

  it('has no parsed message type the page never sends', () => {
    expect(unsent(parsedTypes(), pageTypeStrings())).toEqual([]);
  });

  it('reports a parsed type nothing sends', () => {
    const sent = pageTypeStrings();

    expect(unsent(['ready', '__ccNoModuleSendsThis'], sent)).toEqual(['"__ccNoModuleSendsThis"']);
  });

  it('a type written in prose is a description of a message, not one being sent', () => {
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
    const win = loadFrontend(appJsFiles());

    expect(uncalled(win.cc, kotlinText())).toEqual([]);
  });

  it('reports a method neither side calls', () => {
    const registry = { __ccNobodyCallsThis: function () {}, trimRows: function () {} };

    expect(uncalled(registry, 'exec("window.cc.trimRows()")')).toEqual(['cc.__ccNobodyCallsThis']);
  });

  it('a method whose only mention on either side is a comment is still uncalled', () => {
    const registry = { __ccOnlyDescribed: function () {}, __ccReallyCalled: function () {} };
    const host = '/** Was window.cc.__ccOnlyDescribed. */ exec("window.cc.__ccReallyCalled()")';
    const page = ['// the transcript used to call cc.__ccOnlyDescribed(rows) from here', 'cc.other();'];

    expect(uncalled(registry, host, page)).toEqual(['cc.__ccOnlyDescribed']);
  });
});
