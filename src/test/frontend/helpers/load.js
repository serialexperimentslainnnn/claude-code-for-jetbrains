// Test harness for the JCEF web app. Loads the real, inlined app-*.js into a jsdom document with the same mount
// points shell.html provides (#app / #conversation / #dock / #permissions / #composer / #palette), then hands
// back the window so tests can drive the public `window.cc.*` / `window.CC` surface and assert on the real DOM.
//
// The modules are IIFEs that read `window`/`document`/`CC.els` at load time, so the shell DOM MUST exist before
// app-core.js runs (it captures CC.els from getElementById). We eval each file in the jsdom window global scope.
const fs = require('node:fs');
const path = require('node:path');

const JCEF = path.resolve(__dirname, '../../../main/resources/jcef');

function readApp(name) {
  return fs.readFileSync(path.join(JCEF, name), 'utf8');
}

/** The shell.html mount points, minimal but faithful. */
const SHELL = `
  <div id="app">
    <div id="conversation"><div id="empty" class="empty-state"></div></div>
    <div id="dock"><div id="permissions"></div><div id="composer"></div></div>
    <div id="palette" hidden></div>
  </div>`;

// The vendored libs shell.html loads BEFORE the app modules. Load them faithfully so CC.markdown is the real
// marked→DOMPurify→highlight pipeline (not the escape() fallback), which is what code-block decoration needs.
const VENDOR = ['purify.min.js', 'marked.min.js', 'highlight.min.js'];

/**
 * Rebuilds the shell DOM and loads the vendored libs, then app-core.js, then [files], into the current jsdom
 * window. Returns the window. Pass { vendor: false } to skip marked/DOMPurify/hljs (faster; CC.markdown then
 * escapes instead of rendering). Each test file gets its own fresh jsdom document from vitest.
 */
function loadFrontend(files = [], { vendor = true } = {}) {
  document.documentElement.innerHTML = `<head></head><body>${SHELL}</body>`;
  const seq = [...(vendor ? VENDOR : []), 'app-core.js', ...files];
  for (const f of seq) {
    // eslint-disable-next-line no-eval
    window.eval(readApp(f));
  }
  return window;
}

/** All app-*.js filenames (for the JS↔CSS contract scan). */
function appJsFiles() {
  return fs.readdirSync(JCEF).filter((f) => /^app-.*\.js$/.test(f));
}

module.exports = { loadFrontend, readApp, appJsFiles, JCEF };
