const { loadFrontend, appModules } = require('./helpers/load');

const CHATS = [
  { id: '1', title: 'A chat', selected: true },
  { id: '2', title: 'Another chat' },
];

describe('the whole page comes up', () => {
  let win;
  let errors;

  beforeEach(() => {
    win = loadFrontend(appModules());
    errors = [];
    win.addEventListener('error', (e) => errors.push(String(e.error || e.message)));
    win.CC.send = () => {};
  });

  it('loads every module without one of them throwing', () => {
    expect(errors).toEqual([]);
  });

  it('shows the tab bar on the first push, with a pill per chat', () => {
    win.cc.tabs({ chats: CHATS, tree: [], tasks: [] });
    const bar = win.document.getElementById('tabsbar');
    expect(bar.hidden).toBe(false);
    expect(errors).toEqual([]);
    const labels = Array.from(bar.querySelectorAll('.tab-capsule .pill .pill-label')).map(
      (n) => n.textContent
    );
    expect(labels).toEqual(['A chat', 'Another chat']);
  });

  it('shows the tab bar for the payload the HOST actually emits', () => {
    win.cc.tabs({
      chats: [
        { id: 'chat-0', title: 'Chat 1', selected: true, attention: false },
        { id: 'chat-1', title: 'Chat 2', selected: false, attention: false },
      ],
      tree: [],
      tasks: [],
    });
    expect(errors).toEqual([]);
    expect(win.document.getElementById('tabsbar').hidden).toBe(false);
  });

  it('shows the tab bar for a chat that has started nothing', () => {
    win.cc.tabs({ chats: [CHATS[0]], tree: [], tasks: [] });
    expect(win.document.getElementById('tabsbar').hidden).toBe(false);
    expect(errors).toEqual([]);
  });

  it('builds the composer with its view buttons and the settings button beside them', () => {
    win.cc.state({ running: true, starting: false });
    const views = win.document.getElementById('views');
    expect(views).toBeTruthy();
    expect(views.firstElementChild.classList.contains('settings-btn')).toBe(true);
    const labels = Array.from(views.querySelectorAll('.dash-toggle')).map((b) => b.textContent);
    expect(labels).toContain('Chat');
    expect(errors).toEqual([]);
  });
});
