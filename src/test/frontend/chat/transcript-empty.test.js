const { loadFrontend } = require('../helpers/load');

describe('the welcome screen', () => {
  it('survives a batch that carries no rows', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.clear();
    const empty = win.document.getElementById('empty');
    expect(empty.hidden).toBe(false);

    win.cc.batch([]);
    expect(empty.hidden).toBe(false);

    win.cc.batch({ entries: [] });
    expect(empty.hidden).toBe(false);
  });

  it('leaves once a row arrives', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.clear();
    win.cc.batch([{ id: 1, speaker: 'USER', text: 'hello', state: 'FINISHED' }]);
    expect(win.document.getElementById('empty').hidden).toBe(true);
  });
});
