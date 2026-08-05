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

/**
 * The shell DOM, extracted from the REAL `shell.html` rather than hand-copied.
 *
 * This used to be a hardcoded approximation, and it drifted: `shell.html` gained the `#a11y-status` live
 * region and the harness did not, so tests exercised a DOM the product does not have. That is the worst
 * failure mode a test harness has — it does not fail loudly, it quietly tests something else.
 *
 * Reading the real file means a mount point added to the shell is available to tests automatically, and a
 * mount point *removed* from the shell breaks the tests that depended on it, which is exactly right.
 *
 * `<script>` tags are stripped: the loader below injects the app modules itself, in a controlled order, and
 * jsdom would otherwise try to fetch them off disk.
 */
function shellBody() {
  const html = fs.readFileSync(path.join(JCEF, 'shell.html'), 'utf8');
  const body = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  if (!body) throw new Error('helpers/load: could not find <body> in shell.html');
  return body[1].replace(/<script[\s\S]*?<\/script>/gi, '');
}

// The vendored libs shell.html loads BEFORE the app modules. Load them faithfully so CC.markdown is the real
// marked→DOMPurify→highlight pipeline (not the escape() fallback), which is what code-block decoration needs.
const VENDOR = ['purify.min.js', 'marked.min.js', 'highlight.min.js'];

/**
 * Rebuilds the shell DOM and loads the vendored libs, then app-core.js, then [files], into the current jsdom
 * window. Returns the window. Pass { vendor: false } to skip marked/DOMPurify/hljs (faster; CC.markdown then
 * escapes instead of rendering). Each test file gets its own fresh jsdom document from vitest.
 */
function loadFrontend(files = [], { vendor = true } = {}) {
  document.documentElement.innerHTML = `<head></head><body>${shellBody()}</body>`;
  const seq = [...(vendor ? VENDOR : []), 'app-core.js', ...files];
  for (const f of seq) {
    window.eval(readApp(f));
  }
  return window;
}

/** All app-*.js filenames (for the JS↔CSS contract scan). */
function appJsFiles() {
  return fs.readdirSync(JCEF).filter((f) => /^app-.*\.js$/.test(f));
}

module.exports = { loadFrontend, readApp, appJsFiles, JCEF };
