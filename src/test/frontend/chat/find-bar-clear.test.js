const { loadFrontend } = require('../helpers/load');

function key(win, target, init) {
  const ev = new win.KeyboardEvent('keydown', Object.assign({ bubbles: true, cancelable: true }, init));
  target.dispatchEvent(ev);
  return ev;
}

describe('clearing the transcript', () => {
  it('also clears the search the find bar was showing', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([{ id: 1, speaker: 'ASSISTANT', text: 'alpha beta alpha', state: 'FINISHED' }]);
    key(win, win.document.body, { key: 'f', ctrlKey: true });
    const bar = win.document.querySelector('.find-bar');
    const input = bar.querySelector('.find-input');
    input.value = 'alpha';
    input.dispatchEvent(new win.Event('input', { bubbles: true }));
    expect(bar.querySelector('.find-count').textContent).toBe('1 / 2');

    win.cc.clear();

    expect(input.value).toBe('');
    expect(bar.querySelector('.find-count').textContent).toBe('');
    expect(bar.hidden).toBe(true);
  });
});
