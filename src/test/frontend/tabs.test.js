// The tab bar: the chats, the one open subtab, and the diagram behind the ⋮.
//
// Three designs came before this one — a capsule per level joined by a measured <svg> thread, then a
// breadcrumb, then a hover menu per segment — and the rules pinned here are what survived them:
//  - the bar is at most TWO rows whatever the depth of the tree; the tree lives in a popup;
//  - the popup is the WHOLE tree at once (every level), not one menu per level;
//  - the ⋮ is a real control with a hit area, and hover opens the same thing;
//  - a chat that started nothing has no ⋮ and no second row;
//  - going back to the chat's own transcript says so with an EMPTY agentId — the host reads a blank id as
//    "the chat", never as an agent whose id is the empty string.
const { loadFrontend } = require('./helpers/load');

describe('tab bar', () => {
  let win;
  let sent;

  const bar = () => win.document.getElementById('tabsbar');
  const dots = () => bar().querySelector('.pill-more');
  const cards = () => Array.from(win.document.querySelectorAll('.tab-menu .tab-menu-item'));
  const cardText = () => cards().map((el) => el.textContent);
  const subtab = () => {
    const row = bar().querySelector('.tab-row.trail');
    return row ? row.querySelector('.pill-label').textContent : null;
  };
  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  const hover = (el) => el.dispatchEvent(new win.MouseEvent('mouseenter', { bubbles: false }));

  const TREE = [
    {
      id: 'a',
      parent: null,
      label: 'Inventario de dependencias',
      type: 'general-purpose',
      status: 'running',
    },
    { id: 'b', parent: 'a', label: 'Dependencias de desarrollo', type: 'general-purpose', status: 'running' },
    { id: 'c', parent: 'b', label: 'Dependencias sin usar', type: 'Explore', status: 'completed' },
  ];
  const TASKS = [{ id: 't1', owner: 'a', label: 'npm run dev', type: 'bash', running: true }];
  const CHATS = [{ id: '1', title: 'Chat 1', selected: true }];

  const setup = () => {
    win = loadFrontend(['app-tabs.js'], { vendor: false });
    sent = [];
    win.CC.send = (msg) => sent.push(msg);
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
  };

  beforeEach(setup);

  it('a chat that started nothing is one row, with no ⋮', () => {
    win.cc.tabs({ chats: CHATS, tree: [], tasks: [] });
    expect(bar().querySelectorAll('.tab-row').length).toBe(1);
    expect(dots()).toBe(null);
  });

  it('at rest it shows the chats and nothing else', () => {
    expect(bar().querySelectorAll('.tab-row').length).toBe(1);
    expect(subtab()).toBe(null);
    expect(dots()).not.toBe(null);
  });

  it('the ⋮ opens the whole tree at once — every level, plus the chat it hangs from', () => {
    click(dots());
    const text = cardText();
    expect(text.length).toBe(5); // chat + 3 agents + 1 task
    expect(text.join(' ')).toContain('Chat 1');
    expect(text.join(' ')).toContain('Inventario de dependencias');
    expect(text.join(' ')).toContain('Dependencias de desarrollo');
    expect(text.join(' ')).toContain('Dependencias sin usar'); // three levels deep, no walking
    expect(text.join(' ')).toContain('npm run dev');
  });

  it('a node deep in the tree is one click away, and becomes the open subtab', () => {
    click(dots());
    const deep = cards().find((el) => el.textContent.includes('Dependencias sin usar'));
    click(deep);
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: 'c' });
    // The open subtab names itself the way every other view does: what it is, then its title.
    expect(subtab()).toBe('Subagent (Dependencias sin usar)');
    // Two rows for a tree three levels deep: the bar's height does not follow the tree's depth.
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('the open subtab goes back to the chat, with a blank agentId', () => {
    win.cc.revealAgentTab('b');
    expect(subtab()).toBe('Subagent (Dependencias de desarrollo)');
    click(bar().querySelector('.tab-row.trail .pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('a background task is a destination too', () => {
    click(dots());
    click(cards().find((el) => el.textContent.includes('npm run dev')));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 't1' });
    expect(subtab()).toBe('Background Task (npm run dev)');
  });

  it('hovering the chat tab opens the menu — after a delay, not instantly', () => {
    // The module captures `setTimeout` when it loads, so the fake clock has to be installed BEFORE it.
    vi.useFakeTimers();
    try {
      setup();
      // The hover target is the whole tab, not the ⋮: pointing at the tab is the gesture, the ⋮ is what
      // makes the same thing reachable by click, by keyboard and on a device with no hover at all.
      hover(bar().querySelector('.pill'));
      // A FULL SECOND of resting on the tab: a glance across the bar on the way somewhere else costs nothing.
      vi.advanceTimersByTime(400);
      expect(win.document.querySelectorAll('.tab-menu').length).toBe(0);
      vi.advanceTimersByTime(800);
      expect(win.document.querySelectorAll('.tab-menu').length).toBe(1);
      expect(cardText().join(' ')).toContain('Inventario de dependencias');
    } finally {
      vi.useRealTimers();
    }
  });

  it('the menu lingers for three seconds, and any movement over it starts that over', () => {
    vi.useFakeTimers();
    try {
      setup();
      hover(bar().querySelector('.pill'));
      vi.advanceTimersByTime(1200);
      const panel = win.document.querySelector('.tab-menu');
      expect(panel).not.toBe(null);

      // Leaving arms the countdown — three seconds, not a flick: the pointer crosses that gap on the way to
      // another card all the time.
      panel.dispatchEvent(new win.MouseEvent('mouseleave', { bubbles: false }));
      vi.advanceTimersByTime(2500);
      expect(win.document.querySelectorAll('.tab-menu').length).toBe(1);

      // Moving over the diagram cancels it, and it stays for as long as you keep working in there.
      panel.dispatchEvent(new win.MouseEvent('mousemove', { bubbles: false }));
      vi.advanceTimersByTime(2900);
      expect(win.document.querySelectorAll('.tab-menu').length).toBe(1);

      // Walk away for good and it goes (plus the slide-out before it is removed).
      panel.dispatchEvent(new win.MouseEvent('mouseleave', { bubbles: false }));
      vi.advanceTimersByTime(3000 + 200);
      expect(win.document.querySelectorAll('.tab-menu').length).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('the open subtab can be pinned as a tab of its own', () => {
    win.cc.revealAgentTab('b');
    const pin = bar().querySelector('.tab-row.trail .pill-pin');
    // A subtab is a VIEW — this browser painting somebody else's transcript — so it is gone the moment you
    // look elsewhere. The pin is how you keep one.
    expect(pin).not.toBe(null);
    click(pin);
    expect(sent.pop()).toEqual({ type: 'pinSubtab', agentId: 'b' });
  });

  it('an open subtab lists what THAT agent started, not the whole chat', () => {
    win.cc.revealAgentTab('b');
    click(bar().querySelector('.tab-row.trail .pill-more'));
    const text = cardText().join(' ');
    // Rooted at the hovered agent: its own child, yes; its parent and its parent's siblings, no — the
    // pointer is on it, so that is the question being asked.
    expect(text).toContain('Dependencias de desarrollo');
    expect(text).toContain('Dependencias sin usar');
    expect(text).not.toContain('Inventario de dependencias');
  });

  it('the host can reveal an agent, and closing it returns to the chat', () => {
    win.cc.revealAgentTab('b');
    expect(subtab()).toBe('Subagent (Dependencias de desarrollo)');
    win.cc.clearAgentSelection();
    expect(subtab()).toBe(null);
  });

  it('an agent that vanished from the payload takes the selection with it', () => {
    win.cc.revealAgentTab('b');
    win.cc.tabs({ chats: CHATS, tree: [TREE[0]], tasks: TASKS });
    expect(subtab()).toBe(null);
  });

  it('a push that changes nothing visible does not rebuild the bar', () => {
    // The host pushes the whole bar on every agent event, several times a turn, and nearly all of those
    // change nothing you can see — an agent's transcript grew. Rebuilding anyway made the row flicker under
    // the pointer. Identity of the DOM node is the check: a rebuild replaces it.
    const before = bar().querySelector('.tab-capsule');
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).toBe(before);
    // A real change still redraws.
    win.cc.tabs({ chats: [{ id: '1', title: 'Renamed', selected: true }], tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
  });

  it('a repaint keeps whatever else lives in the bar', () => {
    // The dashboard's view buttons (Chat · Session · Workloads) are appended to this bar so they stop
    // floating over the transcript. The bar is rebuilt on every agent event, several times a turn, and a
    // blanket clear used to take them with it.
    const stack = win.document.createElement('div');
    stack.className = 'dash-toggles';
    bar().appendChild(stack);
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.dash-toggles')).not.toBe(null);
  });

  it('the wheel scrolls the chats, because a vertical wheel does not move a horizontal row', () => {
    const capsule = bar().querySelector('.tab-capsule');
    // jsdom lays nothing out, so overflow is simulated: the handler must consult it before scrolling.
    Object.defineProperty(capsule, 'scrollWidth', { value: 900, configurable: true });
    Object.defineProperty(capsule, 'clientWidth', { value: 300, configurable: true });
    capsule.scrollLeft = 0;
    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(capsule.scrollLeft).toBe(120);
  });

  it('a running node carries its state as a class, like the tool card it belongs to', () => {
    click(dots());
    // The menu says state with a dot, in the same colours the transcript uses.
    const running = cards().find((el) => el.textContent.includes('Inventario de dependencias'));
    const finished = cards().find((el) => el.textContent.includes('Dependencias sin usar'));
    expect(running.querySelector('.pill-dot.running')).not.toBe(null);
    expect(finished.querySelector('.pill-dot.completed')).not.toBe(null);
  });
});
