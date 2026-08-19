// `CC.markdown` is the ONE path in this page that turns untrusted model text into HTML, so what is asserted
// here is not that it renders — other suites cover that — but that it **fails closed**.
//
// The shape of the defect this exists for: `marked` runs first and passes raw HTML straight through, so its
// output is attacker-influenced markup that has not been sanitised yet. Both fallbacks around DOMPurify used
// to return exactly that string. A page served without `purify.min.js`, or a DOMPurify that raised on one
// pathological input, would therefore stop being sanitised entirely — and nothing would look wrong, because
// everything still rendered. That is CWE-636 on the only untrusted channel there is.
//
// So each test removes one leg of the sanitiser and asserts the same two things: the payload does not survive
// as live markup, and the text is still shown (escaped) rather than silently dropped.
const { loadFrontend } = require('./helpers/load');

/** Live markup if it is ever inserted unsanitised; inert once escaped. */
const PAYLOAD = '<img src=x onerror="alert(1)">';

/** Renders `PAYLOAD` into a detached element the way every caller does — through `innerHTML`. */
function renderInto(win, html) {
  const host = win.document.createElement('div');
  host.innerHTML = html;
  return host;
}

describe('markdown sanitising fails closed', () => {
  it('strips the handler but keeps the image when DOMPurify is present', () => {
    // The baseline, and it also proves the sanitiser is genuinely running in this harness rather than being
    // absent and passing by accident. An `<img>` is legitimate output — the config allows `data:image/` for
    // inline images on purpose — so what must not survive is the event handler, not the element.
    const win = loadFrontend(['app-transcript.js']);

    const img = renderInto(win, win.CC.markdown(PAYLOAD)).querySelector('img');

    expect(img).not.toBeNull();
    expect(img.getAttribute('onerror')).toBeNull();
  });

  it('escapes instead of emitting raw HTML when DOMPurify is missing', () => {
    // The real case: the page booted without `purify.min.js`. `marked` is still there, so `raw` holds the
    // live tag — returning it was the defect.
    const win = loadFrontend(['app-transcript.js']);
    delete win.DOMPurify;
    delete win.window.DOMPurify;

    const out = win.CC.markdown(PAYLOAD);

    expect(renderInto(win, out).querySelector('img')).toBeNull();
    // The property is "no live tag", not "the word onerror is absent": escaped text may well still read
    // `onerror="alert(1)"`, and that is the correct outcome — it is prose, not an attribute.
    expect(out).not.toMatch(/<img/i);
    // Shown, not swallowed: the user still reads what the model said.
    expect(out).toContain('&lt;img');
  });

  it('escapes instead of emitting raw HTML when sanitize throws', () => {
    // A DOMPurify that raises on one input must not be a hole for that input.
    const win = loadFrontend(['app-transcript.js']);
    win.DOMPurify.sanitize = function () {
      throw new Error('pathological input');
    };

    const out = win.CC.markdown(PAYLOAD);

    expect(renderInto(win, out).querySelector('img')).toBeNull();
    // The property is "no live tag", not "the word onerror is absent": escaped text may well still read
    // `onerror="alert(1)"`, and that is the correct outcome — it is prose, not an attribute.
    expect(out).not.toMatch(/<img/i);
    expect(out).toContain('&lt;img');
  });

  it('a script tag does not survive either fallback', () => {
    // Belt and braces on the two shapes that differ in how a parser treats them.
    const script = '<script>window.__pwned = 1;</script>';
    ['missing', 'throwing'].forEach((mode) => {
      const win = loadFrontend(['app-transcript.js']);
      if (mode === 'missing') {
        delete win.DOMPurify;
        delete win.window.DOMPurify;
      } else {
        win.DOMPurify.sanitize = function () {
          throw new Error('x');
        };
      }

      const host = renderInto(win, win.CC.markdown(script));

      expect(host.querySelector('script')).toBeNull();
      expect(win.__pwned).toBeUndefined();
    });
  });

  it('ordinary markdown still renders when the sanitiser is healthy', () => {
    // The regression guard on the fix: failing closed must not mean escaping everything, always.
    const win = loadFrontend(['app-transcript.js']);

    const out = win.CC.markdown('**bold** and `code`');

    expect(out).toContain('<strong>');
    expect(out).toContain('<code');
  });
});
