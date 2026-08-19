const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const JCEF = path.resolve(__dirname, '../../../main/resources/jcef');

function readApp(name) {
  return fs.readFileSync(path.join(JCEF, name), 'utf8');
}

function shellBody() {
  const html = fs.readFileSync(path.join(JCEF, 'shell.html'), 'utf8');
  const parsed = new JSDOM(html);
  const body = parsed.window.document.body;
  if (!body) throw new Error('helpers/load: could not find <body> in shell.html');
  body.querySelectorAll('script').forEach((node) => node.remove());
  return body.innerHTML;
}

const VENDOR = ['purify.min.js', 'marked.min.js', 'highlight.min.js'];

function jcefHostSource() {
  return fs.readFileSync(
    path.resolve(__dirname, '../../../main/kotlin/dev/lain/claudejb/ui/jcef/JcefHost.kt'),
    'utf8'
  );
}

function declaredList(name, entry) {
  const host = jcefHostSource();
  const block = host.slice(host.indexOf(`val ${name} = listOf(`)).replace(/\/\/[^\n]*/g, '');
  const found = block.slice(0, block.indexOf(')')).match(entry);
  if (!found) throw new Error(`helpers/load: could not read ${name} from JcefHost.kt`);
  return found.map((quoted) => quoted.replace(/"/g, ''));
}

function appModules() {
  return declaredList('appNames', /"(app-[\w.-]+\.js)"/g);
}

function cssParts() {
  return declaredList('CSS_PARTS', /"([\w-]+\.css)"/g);
}

function familyOf(name) {
  const m = /^app-([a-z]+)/.exec(name);
  return m ? m[1] : name;
}

function loadFrontend(files = [], { vendor = true } = {}) {
  document.documentElement.innerHTML = `<head></head><body>${shellBody()}</body>`;
  const wanted = new Set(['core', ...files.map(familyOf)]);
  const seq = [...(vendor ? VENDOR : []), ...appModules().filter((f) => wanted.has(familyOf(f)))];
  for (const f of seq) {
    window.eval(readApp(f));
  }
  return window;
}

function appJsFiles() {
  return fs.readdirSync(JCEF).filter((f) => /^app-.*\.js$/.test(f));
}

function readCss() {
  return cssParts()
    .map((part) => fs.readFileSync(path.join(JCEF, 'css', part), 'utf8'))
    .join('\n');
}

module.exports = { loadFrontend, readApp, appJsFiles, appModules, cssParts, readCss, JCEF };
