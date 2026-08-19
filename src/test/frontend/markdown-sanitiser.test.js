const { loadFrontend } = require('./helpers/load');

const PAYLOAD = '<img src=x onerror="alert(1)">';

function renderInto(win, html) {
  const host = win.document.createElement('div');
  host.innerHTML = html;
  return host;
}

describe('markdown sanitising fails closed', () => {
  it('strips the handler but keeps the image when DOMPurify is present', () => {
    const win = loadFrontend(['app-transcript.js']);

    const img = renderInto(win, win.CC.markdown(PAYLOAD)).querySelector('img');

    expect(img).not.toBeNull();
    expect(img.getAttribute('onerror')).toBeNull();
  });

  it('escapes instead of emitting raw HTML when DOMPurify is missing', () => {
    const win = loadFrontend(['app-transcript.js']);
    delete win.DOMPurify;
    delete win.window.DOMPurify;

    const out = win.CC.markdown(PAYLOAD);

    expect(renderInto(win, out).querySelector('img')).toBeNull();
    expect(out).not.toMatch(/<img/i);
    expect(out).toContain('&lt;img');
  });

  it('escapes instead of emitting raw HTML when sanitize throws', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.DOMPurify.sanitize = function () {
      throw new Error('pathological input');
    };

    const out = win.CC.markdown(PAYLOAD);

    expect(renderInto(win, out).querySelector('img')).toBeNull();
    expect(out).not.toMatch(/<img/i);
    expect(out).toContain('&lt;img');
  });

  it('a script tag does not survive either fallback', () => {
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
    const win = loadFrontend(['app-transcript.js']);

    const out = win.CC.markdown('**bold** and `code`');

    expect(out).toContain('<strong>');
    expect(out).toContain('<code');
  });
});
