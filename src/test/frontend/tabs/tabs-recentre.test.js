const { loadFrontend } = require('../helpers/load');

const TREE = [
  { id: 'a', parent: null, label: 'Root agent', type: 'general-purpose', status: 'running' },
  { id: 'b', parent: 'a', label: 'Child agent', type: 'general-purpose', status: 'running' },
];

describe('re-centring a branch row', () => {
  it('happens again when the same branch is closed and reopened on the same agent', () => {
    const win = loadFrontend(['app-tabs.js'], { vendor: false });
    win.CC.send = () => {};
    const centred = [];
    win.requestAnimationFrame = (fn) => fn();
    win.Element.prototype.scrollIntoView = function () {
      if (this.closest('.branch-capsule')) centred.push(this.querySelector('.pill-label').textContent);
    };
    try {
      win.cc.tabs({ chats: [{ id: '1', title: 'Chat 1', selected: true }], tree: TREE, tasks: [] });

      win.CC.tabbar.showAgent('b');
      expect(centred).toEqual(['Child agent']);

      win.CC.tabbar.showChat();
      expect(win.document.querySelector('.branch-capsule')).toBeNull();

      win.CC.tabbar.showAgent('b');
      expect(centred).toEqual(['Child agent', 'Child agent']);
    } finally {
      delete win.Element.prototype.scrollIntoView;
    }
  });
});
