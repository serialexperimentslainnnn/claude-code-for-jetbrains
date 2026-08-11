// Test harness for the JCEF web app. Loads the real, inlined app-*.js into a jsdom document with the same mount
// points shell.html provides (#app / #conversation / #dock / #permissions / #composer / #palette), then hands
// back the window so tests can drive the public `window.cc.*` / `window.CC` surface and assert on the real DOM.
//
// The modules are IIFEs that read `window`/`document`/`CC.els` at load time, so the shell DOM MUST exist before
// app-core.js runs (it captures CC.els from getElementById). We eval each file in the jsdom window global scope.
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

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
 *
 * Parsed with a real HTML parser rather than regexes. The previous version matched `<body>` and stripped
 * `<script>` with patterns, and CodeQL flagged both as high severity — correctly: `<script[\s\S]*?<\/script>`
 * does not match `</script >` with a space, so a regex "sanitiser" that looks right silently is not. The risk
 * here was low (this reads OUR shell.html off disk, and the goal is load control, not sanitisation), but the
 * fix for parsing HTML with regexes is not a better regex — it is the parser that is already a dependency of
 * this harness.
 */
function shellBody() {
  const html = fs.readFileSync(path.join(JCEF, 'shell.html'), 'utf8');
  // `includeNodeLocations: false` and no `runScripts`: this instance only reads structure, so nothing in the
  // parsed document can execute — the scripts are removed below and the modules are injected by the caller.
  const parsed = new JSDOM(html);
  const body = parsed.window.document.body;
  if (!body) throw new Error('helpers/load: could not find <body> in shell.html');
  body.querySelectorAll('script').forEach((node) => node.remove());
  return body.innerHTML;
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

/**
 * The whole stylesheet, as the page sees it: the parts of `css/`, concatenated in CASCADE ORDER.
 *
 * Read from `JcefHost.CSS_PARTS`, not from a list kept here, and not from `readdirSync` — order is semantics
 * in CSS (later rules win), so a harness that sorted alphabetically would be testing a stylesheet the
 * product never serves, and a part added to the host but not to the tests would go unchecked.
 */
function readCss() {
  const host = fs.readFileSync(
    path.resolve(__dirname, '../../../main/kotlin/dev/lain/claudejb/ui/jcef/JcefHost.kt'),
    'utf8'
  );
  // The DECLARATION, not the first mention: `CSS_PARTS` is also used in `buildPage` above it.
  const block = host.slice(host.indexOf('val CSS_PARTS = listOf('));
  const parts = block.slice(0, block.indexOf(')')).match(/"([\w-]+\.css)"/g);
  if (!parts) throw new Error('helpers/load: could not read CSS_PARTS from JcefHost.kt');
  return parts
    .map((quoted) => fs.readFileSync(path.join(JCEF, 'css', quoted.replace(/"/g, '')), 'utf8'))
    .join('\n');
}

module.exports = { loadFrontend, readApp, appJsFiles, readCss, JCEF };
