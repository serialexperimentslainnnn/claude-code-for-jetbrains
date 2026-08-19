const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles, cssParts } = require('./helpers/load');

const ALLOWED_CONTROL = new Set([0x09, 0x0a, 0x0d]);

function controlBytes(file) {
  const bytes = fs.readFileSync(file);
  const found = [];
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    if (b < 0x20 && !ALLOWED_CONTROL.has(b)) found.push({ offset: i, byte: '0x' + b.toString(16) });
  }
  return found;
}

function servedFiles() {
  return [
    ...appJsFiles().map((f) => path.join(JCEF, f)),
    ...cssParts().map((p) => path.join(JCEF, 'css', p)),
    path.join(JCEF, 'shell.html'),
  ];
}

describe('the page is built from text', () => {
  it('no source the host inlines contains a control character', () => {
    const offenders = servedFiles()
      .map((file) => ({ file: path.relative(JCEF, file), hits: controlBytes(file) }))
      .filter((r) => r.hits.length)
      .map((r) => `${r.file}: ${r.hits.map((h) => `${h.byte}@${h.offset}`).join(', ')}`);
    expect(offenders).toEqual([]);
  });

  it('every inlined source decodes as UTF-8, and round-trips', () => {
    const offenders = servedFiles()
      .filter((file) => {
        const bytes = fs.readFileSync(file);
        return !Buffer.from(bytes.toString('utf8'), 'utf8').equals(bytes);
      })
      .map((file) => path.relative(JCEF, file));
    expect(offenders).toEqual([]);
  });
});
