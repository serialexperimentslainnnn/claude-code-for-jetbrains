const { loadFrontend } = require('../helpers/load');

function dropImage(win, card, file) {
  const ev = new win.Event('drop', { bubbles: true, cancelable: true });
  Object.defineProperty(ev, 'dataTransfer', { value: { files: [file] } });
  card.dispatchEvent(ev);
}

describe('an image dropped on the composer that cannot be read', () => {
  it('is reported instead of vanishing silently', () => {
    const win = loadFrontend(['app-composer.js'], { vendor: false });
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state({ running: true, starting: false, queue: [] });
    win.FileReader = class {
      readAsDataURL() {
        if (this.onerror) this.onerror(new win.Event('error'));
      }
    };

    const card = win.CC.els.composer.querySelector('.composer-card');
    dropImage(win, card, new win.File(['x'], 'broken.png', { type: 'image/png' }));

    expect(sent.filter((m) => m.type === 'attach')).toEqual([]);
    expect(win.document.getElementById('a11y-status').textContent).toContain('broken.png');
    expect(sent.some((m) => m.type === 'diag' && /broken\.png/.test(m.report))).toBe(true);
  });

  it('an image that reads fine is attached as before', () => {
    const win = loadFrontend(['app-composer.js'], { vendor: false });
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state({ running: true, starting: false, queue: [] });
    win.FileReader = class {
      readAsDataURL() {
        this.result = 'data:image/png;base64,QUJD';
        if (this.onload) this.onload(new win.Event('load'));
      }
    };

    const card = win.CC.els.composer.querySelector('.composer-card');
    dropImage(win, card, new win.File(['x'], 'fine.png', { type: 'image/png' }));

    expect(sent).toContainEqual({ type: 'attach', name: 'fine.png', mediaType: 'image/png', base64: 'QUJD' });
  });
});
