const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, appJsFiles, readApp } = require('./helpers/load');
const { stripComments } = require('./helpers/source');

const KOTLIN_SRC = path.resolve(__dirname, '../../main/kotlin');

const BRIDGE_CALL = /window\.cc\.([A-Za-z_$][A-Za-z0-9_$]*)/g;

const NO_OP = /^function\s*[A-Za-z0-9_$]*\s*\(\s*\)\s*\{\s*\}$/;

const isNoOp = (fnOrSource) => NO_OP.test(String(fnOrSource).trim());

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

function kotlinSources() {
  return fs
    .readdirSync(KOTLIN_SRC, { recursive: true })
    .filter((f) => f.endsWith('.kt'))
    .map((rel) => ({ file: rel, text: fs.readFileSync(path.join(KOTLIN_SRC, rel), 'utf8') }));
}

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
  it('finds the host call sites it is supposed to be checking', () => {
    expect([...scanCalls(kotlinSources()).keys()]).not.toEqual([]);
  });

  it('reads the code and not the prose around it, without moving the line numbers', () => {
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
    const win = loadFrontend(appJsFiles());

    expect(unimplemented(scanCalls(kotlinSources()), win.cc)).toEqual([]);
  });

  it('recognises app-core.js null-safe fallbacks as unimplemented', () => {
    const fallbacks = [
      ...readApp('app-core.js').matchAll(
        /typeof cc\.([A-Za-z0-9_$]+) !== 'function'\)\s*cc\.\1\s*=\s*(function\s*\([^)]*\)\s*\{[^}]*\})/g
      ),
    ];

    expect(fallbacks).not.toEqual([]);
    expect(fallbacks.filter((m) => !isNoOp(m[2])).map((m) => `cc.${m[1]}`)).toEqual([]);
  });

  it('reports a call whose method no module implements', () => {
    const missing = '__ccNoModuleImplementsThis';
    const host = [{ file: 'Synthetic.kt', text: `exec("window.cc.${missing} && window.cc.${missing}()")` }];
    const calls = scanCalls(host);
    const reported = [`cc.${missing} — called from Synthetic.kt:1`];

    expect(unimplemented(calls, loadFrontend(appJsFiles()).cc)).toEqual(reported);
    expect(unimplemented(calls, { [missing]: function () {} })).toEqual(reported);
  });
});
