// The WHOLE page, loaded the way the host serves it, doing the first thing it is asked to do.
//
// Every other suite here loads the two or three modules its subject needs. That is the right default — it
// keeps a failure pointing at one file — and it has one blind spot, which is the one this exists for: a
// module that only misbehaves when its neighbours are present, and a throw that happens with the page half
// built. Both are invisible to a focused test and both take the UI down.
//
// **The failure that produced this file.** `#tabsbar` is `hidden` in the static shell and `app-tabs.js`
// clears that attribute at the END of its render, after the rows are built. Anything that throws in between
// leaves a bar that is fully populated and never shown — no error on screen, no error in `idea.log` (the
// plugin does not pipe the CEF console there), and no red test, because every tabs test passes with the
// modules it loads. The symptom reported was "where are the tabs?", and there was nothing to look at.
//
// So this asserts the state a live page is in after one ordinary push: no uncaught error, and the things
// that are supposed to be on screen are on screen.
const { loadFrontend, appModules } = require('./helpers/load');

const CHATS = [
  { id: '1', title: 'A chat', selected: true },
  { id: '2', title: 'Another chat' },
];

describe('the whole page comes up', () => {
  let win;
  let errors;

  beforeEach(() => {
    // Every module in `JcefHost.appNames`, in that order — the page the host actually serves. Vendor libs
    // included: `app-core.js` reaches for DOMPurify and marked at load, and a page without them takes a
    // different branch from the real one.
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
    // `hidden` is the state the shell ships; SHOWING it is the last thing render does, so this assertion is
    // the one that catches a throw anywhere earlier in that function.
    expect(bar.hidden).toBe(false);
    expect(errors).toEqual([]);
    const labels = Array.from(bar.querySelectorAll('.tab-capsule .pill .pill-label')).map(
      (n) => n.textContent
    );
    expect(labels).toEqual(['A chat', 'Another chat']);
  });

  it('shows the tab bar for the payload the HOST actually emits', () => {
    // Not a hand-made fixture: this is the shape `JcefTabsData.tabsJson` builds — every chat carries its OWN
    // `tree` and `tasks` (so hovering a tab you are not in shows what THAT chat started), and the selected
    // chat's tree is repeated at the top level, which is what the bar's own rows are built from. A test that
    // omits the per-chat keys exercises a payload the product never sends, and that is the difference
    // between a green suite and a tool window with no tabs in it.
    win.cc.tabs({
      chats: [
        { id: 'chat-0', title: 'Chat 1', selected: true, attention: false, tree: [], tasks: [] },
        { id: 'chat-1', title: 'Chat 2', selected: false, attention: false, tree: [], tasks: [] },
      ],
      tree: [],
      tasks: [],
    });
    expect(errors).toEqual([]);
    expect(win.document.getElementById('tabsbar').hidden).toBe(false);
  });

  it('shows the tab bar for a chat that has started nothing', () => {
    // The ordinary case, and the one the reported failure was in: one chat, no agents, no background tasks.
    // The subtab row has only the `Chat` pill to draw, which is the shortest path through the newest code.
    win.cc.tabs({ chats: [CHATS[0]], tree: [], tasks: [] });
    expect(win.document.getElementById('tabsbar').hidden).toBe(false);
    expect(errors).toEqual([]);
  });

  it('builds the composer with its view buttons and the settings button beside them', () => {
    win.cc.state({ running: true, starting: false });
    const views = win.document.getElementById('views');
    expect(views).toBeTruthy();
    // The ⚙ is the FIRST thing in the row, before `Chat` — the two are mounted by different families and
    // either can run first, so this is the assertion that the ordering does not depend on which did.
    expect(views.firstElementChild.classList.contains('settings-btn')).toBe(true);
    const labels = Array.from(views.querySelectorAll('.dash-toggle')).map((b) => b.textContent);
    expect(labels).toContain('Chat');
    expect(errors).toEqual([]);
  });
});
