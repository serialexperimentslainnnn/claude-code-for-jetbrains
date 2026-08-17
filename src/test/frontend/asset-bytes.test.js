// Every byte the page is built from is TEXT — no control characters in any source the host inlines.
//
// **This is a gate for a failure that nothing else in the suite could see, and it took an afternoon.** Two
// modules were written with a literal U+0000 between quotes, as a field separator. Everything downstream then
// went wrong quietly and in a way that pointed nowhere:
//
//  - the files stopped being text, so `git diff` reported `Bin 4585 -> 5781` and `grep` went silent on them —
//    two signals that were on screen for hours and read as noise;
//  - the HTML parser rewrites U+0000 to U+FFFD while reading a `<script>`, so the text the browser hashes
//    stopped being the text `JcefHost` hashed. The page is served under a hash-pinned CSP, so Chromium
//    REFUSED both scripts. `app-tabs-guard.js` never defined `T.drawnSignature`, so `render()` threw on every
//    push and the tab bar never appeared; `app-composer-settings.js` never ran, so there was no gear button;
//  - and none of it surfaced. A CSP refusal is not an `error` event, and an exception inside
//    `executeJavaScript` fires nothing and reaches no log. Meanwhile every frontend test stayed green,
//    because jsdom evaluates the module text directly: no CSP, no hashes, no parser.
//
// So the check is on the BYTES, which is the only layer where the defect is visible at all. Tabs and newlines
// are text; a carriage return is tolerated (a Windows checkout is not a defect, and `.gitattributes` governs
// that). Anything else — NUL, escapes, unit separators — is a character that must be written `\u….` in source
// rather than typed, and that is the rule this enforces.
const fs = require('node:fs');
const path = require('node:path');
const { JCEF, appJsFiles, cssParts } = require('./helpers/load');

/** Byte values that are legal in a source file: printable, tab, LF, CR. */
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

/** Everything the host inlines into the page: the app modules, the stylesheet parts and the shell. */
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
    // If this fails: a non-printable character was TYPED into a source instead of written as an escape. Find
    // it at the offset above and replace it with `String.fromCharCode(n)` or a `\u` escape. Do not "fix" it by
    // allowing the byte here — the CSP hash mismatch it causes is silent, and so is everything after it.
    expect(offenders).toEqual([]);
  });

  it('every inlined source decodes as UTF-8, and round-trips', () => {
    // A lone surrogate or a truncated sequence has the same consequence as a control byte: the host decodes
    // the bytes to a String, hashes THAT, and the browser re-decodes the served text. Any byte sequence that
    // does not survive the round trip breaks the hash and the script is refused.
    const offenders = servedFiles()
      .filter((file) => {
        const bytes = fs.readFileSync(file);
        return !Buffer.from(bytes.toString('utf8'), 'utf8').equals(bytes);
      })
      .map((file) => path.relative(JCEF, file));
    expect(offenders).toEqual([]);
  });
});
