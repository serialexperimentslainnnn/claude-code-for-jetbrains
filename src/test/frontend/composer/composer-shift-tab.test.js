const { loadFrontend } = require('../helpers/load');

function key(win, target, init) {
  const ev = new win.KeyboardEvent('keydown', Object.assign({ bubbles: true, cancelable: true }, init));
  target.dispatchEvent(ev);
  return ev;
}

describe('Shift+Tab in the prompt', () => {
  it('is the ordinary reverse tab: nothing is prevented and nothing is sent', () => {
    const win = loadFrontend(['app-composer.js'], { vendor: false });
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state({ running: true, starting: false, queue: [] });
    const input = win.CC.els.composer.querySelector('.composer-input');
    input.focus();

    const ev = key(win, input, { key: 'Tab', shiftKey: true });

    expect(ev.defaultPrevented).toBe(false);
    expect(sent).toEqual([]);
  });

  it('Tab with no suggestion pending is the ordinary forward tab too', () => {
    const win = loadFrontend(['app-composer.js'], { vendor: false });
    win.cc.state({ running: true, starting: false, queue: [] });
    const input = win.CC.els.composer.querySelector('.composer-input');
    input.focus();

    const ev = key(win, input, { key: 'Tab' });

    expect(ev.defaultPrevented).toBe(false);
  });
});
