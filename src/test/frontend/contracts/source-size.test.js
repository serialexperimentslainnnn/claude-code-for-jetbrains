const fs = require('node:fs');
const path = require('node:path');

const CEILING = 250;
const TS_ROOT = path.resolve(__dirname, '../../../main/ts/jcef');
const CSS_ROOT = path.resolve(__dirname, '../../../main/resources/jcef/css');

const OVERSIZE = new Set([]);

function lineCount(file) {
  return (fs.readFileSync(file, 'utf8').match(/\n/g) || []).length;
}

function walk(dir, ext) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return walk(full, ext);
    return entry.name.endsWith(ext) ? [full] : [];
  });
}

function sources() {
  const ts = walk(TS_ROOT, '.ts').filter((f) => !f.endsWith('.d.ts'));
  return [...ts, ...walk(CSS_ROOT, '.css')];
}

describe('source size', () => {
  it('every TypeScript module and stylesheet stays under the ceiling', () => {
    const offenders = sources()
      .filter((f) => !OVERSIZE.has(path.basename(f)) && lineCount(f) > CEILING)
      .map((f) => `${path.relative(process.cwd(), f)}: ${lineCount(f)}`);
    expect(offenders).toEqual([]);
  });

  it('the allowlist names only files that are still over the ceiling', () => {
    const stale = [...OVERSIZE].filter((name) => {
      const file = path.join(CSS_ROOT, name);
      return !fs.existsSync(file) || lineCount(file) <= CEILING;
    });
    expect(stale).toEqual([]);
  });
});
