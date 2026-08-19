const fs = require('node:fs');
const path = require('node:path');
const { appJsFiles, appModules, cssParts, JCEF } = require('./helpers/load');

function cssFiles() {
  return fs.readdirSync(path.join(JCEF, 'css')).filter((f) => f.endsWith('.css'));
}

function missing(these, those) {
  const declared = new Set(those);
  return these.filter((name) => !declared.has(name));
}

describe('the page manifest and the directory agree — app modules', () => {
  it('finds modules on disk and modules declared by the host', () => {
    expect(appJsFiles().length).toBeGreaterThan(20);
    expect(appModules().length).toBeGreaterThan(20);
  });

  it('serves every app-*.js that exists', () => {
    expect(missing(appJsFiles(), appModules())).toEqual([]);
  });

  it('declares no app-*.js that does not exist', () => {
    expect(missing(appModules(), appJsFiles())).toEqual([]);
  });
});

describe('the page manifest and the directory agree — stylesheet parts', () => {
  it('finds parts on disk and parts declared by the host', () => {
    expect(cssFiles().length).toBeGreaterThan(3);
    expect(cssParts().length).toBeGreaterThan(3);
  });

  it('serves every css/*.css that exists', () => {
    expect(missing(cssFiles(), cssParts())).toEqual([]);
  });

  it('declares no css/*.css that does not exist', () => {
    expect(missing(cssParts(), cssFiles())).toEqual([]);
  });
});

describe('the manifest comparison reaches a verdict', () => {
  it('reports an undeclared file and an undelivered declaration', () => {
    const onDisk = ['app-core.js', 'app-orphan.js'];
    const declared = ['app-core.js', 'app-ghost.js'];

    expect(missing(onDisk, declared)).toEqual(['app-orphan.js']);
    expect(missing(declared, onDisk)).toEqual(['app-ghost.js']);
  });

  it('is a membership check, not a reachability proof', () => {
    expect(missing(appJsFiles(), [...appModules()].reverse())).toEqual([]);
  });
});
