const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const JCEF = path.resolve(__dirname, '../../../main/resources/jcef');
const EMIT = path.resolve(__dirname, '../../../../build/web/jcef');

function appDir(name) {
  return fs.existsSync(path.join(EMIT, name)) ? EMIT : JCEF;
}

function appPath(name) {
  return path.join(appDir(name), name);
}

function readApp(name) {
  return fs.readFileSync(appPath(name), 'utf8');
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

function pageAssemblySource() {
  return fs.readFileSync(
    path.resolve(__dirname, '../../../main/kotlin/dev/lain/claudejb/view/jcef/PageAssembly.kt'),
    'utf8'
  );
}

function declaredList(name, entry) {
  const source = pageAssemblySource();
  const block = source.slice(source.indexOf(`val ${name} = listOf(`)).replace(/\/\/[^\n]*/g, '');
  const found = block.slice(0, block.indexOf(')')).match(entry);
  if (!found) throw new Error(`helpers/load: could not read ${name} from PageAssembly.kt`);
  return found.map((quoted) => quoted.replace(/"/g, ''));
}

function appModules() {
  return declaredList('appNames', /"([\w-]+(?:\/[\w-]+)*\.js)"/g);
}

function cssParts() {
  return declaredList('CSS_PARTS', /"([\w-]+(?:\/[\w-]+)*\.css)"/g);
}

const LEGACY_FAMILY = {
  core: ['core'],
  transcript: ['chat'],
  composer: ['composer'],
  permissions: ['permissions'],
  session: ['panel', 'session', 'workloads', 'git', 'guard', 'vuln', 'log'],
  tabs: ['tabs'],
};

const MVC = new Set(['models', 'views', 'controllers']);

const ALWAYS = ['core', 'boot'];

function featureOf(name) {
  const parts = name.split('/');
  return MVC.has(parts[0]) ? parts[1] : parts[0];
}

function featuresOf(requested) {
  const legacy = /^app-([a-z]+)/.exec(requested);
  if (legacy) return LEGACY_FAMILY[legacy[1]] || [legacy[1]];
  return [featureOf(requested)];
}

function loadFrontend(files = [], { vendor = true } = {}) {
  document.documentElement.innerHTML = `<head></head><body>${shellBody()}</body>`;
  const wanted = new Set([...ALWAYS, ...files.flatMap(featuresOf)]);
  const seq = [...(vendor ? VENDOR : []), ...appModules().filter((f) => wanted.has(featureOf(f)))];
  for (const f of seq) {
    window.eval(readApp(f));
  }
  return window;
}

const SOURCES = path.resolve(__dirname, '../../../main/ts/jcef');

function modulesUnder(root, prefix = '') {
  if (!fs.existsSync(root)) return [];
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const relative = prefix + entry.name;
    if (entry.isDirectory())
      return entry.name === 'types' ? [] : modulesUnder(path.join(root, entry.name), relative + '/');
    return entry.name.endsWith('.ts') && !entry.name.endsWith('.d.ts')
      ? [relative.replace(/\.ts$/, '.js')]
      : [];
  });
}

function appJsFiles() {
  return modulesUnder(SOURCES).sort();
}

function readCss() {
  return cssParts()
    .map((part) => fs.readFileSync(path.join(JCEF, 'css', part), 'utf8'))
    .join('\n');
}

module.exports = { loadFrontend, readApp, appPath, appJsFiles, appModules, cssParts, readCss, JCEF, EMIT };
