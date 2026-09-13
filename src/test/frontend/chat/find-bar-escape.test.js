const { loadFrontend } = require('../helpers/load');

function key(win, target, init) {
  const ev = new win.KeyboardEvent('keydown', Object.assign({ bubbles: true, cancelable: true }, init));
  target.dispatchEvent(ev);
  return ev;
}

describe('Escape with the find bar open', () => {
  it('closes the bar only when the focus is inside it, and reaches everyone else otherwise', () => {
    const win = loadFrontend(['app-transcript.js']);
    key(win, win.document.body, { key: 'f', ctrlKey: true });
    const bar = win.document.querySelector('.find-bar');
    const input = bar.querySelector('.find-input');
    expect(bar.hidden).toBe(false);

    const seen = [];
    win.document.addEventListener('keydown', (e) => seen.push(e.key));
    const outside = win.document.createElement('textarea');
    win.document.body.appendChild(outside);
    outside.focus();
    key(win, outside, { key: 'Escape' });
    expect(seen).toEqual(['Escape']);
    expect(bar.hidden).toBe(false);

    input.focus();
    key(win, input, { key: 'Escape' });
    expect(bar.hidden).toBe(true);
  });
});
