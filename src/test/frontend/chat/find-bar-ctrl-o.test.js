const { loadFrontend } = require('../helpers/load');

function key(win, target, init) {
  const ev = new win.KeyboardEvent('keydown', Object.assign({ bubbles: true, cancelable: true }, init));
  target.dispatchEvent(ev);
  return ev;
}

describe('Ctrl+O', () => {
  it('toggles the reasoning folds from the transcript, never from a text field', () => {
    const win = loadFrontend(['app-transcript.js']);
    const toggled = vi.fn();
    win.cc.toggleReasoning = toggled;
    const field = win.document.createElement('input');
    win.document.body.appendChild(field);

    key(win, field, { key: 'o', ctrlKey: true });
    expect(toggled).not.toHaveBeenCalled();

    key(win, win.document.body, { key: 'o', ctrlKey: true });
    expect(toggled).toHaveBeenCalledTimes(1);
  });

  it('has a harmless fallback before the transcript module defines the toggle', () => {
    const win = loadFrontend([], { vendor: false });
    expect(typeof win.cc.toggleReasoning).toBe('function');
    expect(() => win.cc.toggleReasoning()).not.toThrow();
  });
});
