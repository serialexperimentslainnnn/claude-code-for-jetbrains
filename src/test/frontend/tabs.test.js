// The tab bar: the chats, the subtabs of the one you are in, and the tree behind the ⋮.
//
// Three designs came before this one — a capsule per level joined by a measured <svg> thread, then a
// breadcrumb, then a hover menu per segment — and the rules pinned here are what survived them:
//  - the bar is at most TWO rows whatever the depth of the tree; the tree lives in a popup;
//  - the second row is FLAT — every agent, subagent and background task of the chat on screen, one click
//    each, led by the chat itself. It is the quick way in and out, not a second tree;
//  - the popup is the WHOLE tree at once (every level), not one menu per level, and it stays;
//  - the ⋮ is a real control with a hit area, and hover opens the same thing;
//  - a chat that started nothing has no ⋮ and no second row;
//  - going back to the chat's own transcript says so with an EMPTY agentId — the host reads a blank id as
//    "the chat", never as an agent whose id is the empty string;
//  - a subtab pill shows the BARE name and is announced with its kind and its state, because a row of pills
//    all beginning with `Agent (` spends the width on the word that repeats — and because colour alone is
//    not allowed to be the only carrier of a state (WCAG 2.2 SC 1.4.1).
const { loadFrontend, readCss } = require('./helpers/load');

describe('tab bar', () => {
  let win;
  let sent;

  const bar = () => win.document.getElementById('tabsbar');
  const dots = () => bar().querySelector('.pill-more');
  const cards = () => Array.from(win.document.querySelectorAll('.tab-menu .tab-menu-item'));
  const cardText = () => cards().map((el) => el.textContent);

  /** The subtabs row's capsule — the row carries no modifier class, this is what identifies it. */
  const subCapsule = () => bar().querySelector('.subtab-capsule');
  /**
   * A TAB is the `.pill-wrap`: the chat's own `<button class="pill">` plus its controls as SIBLINGS.
   *
   * They used to be spans inside that button, which is interactive content nested where the content model
   * forbids it — invisible to assistive technology, and not reliably clickable in Chromium, which is what
   * made the × dead. So these helpers select the wrapper, and a control is found on IT and never under the
   * pill: a helper that still reached inside would keep passing while the markup that broke came back.
   */
  const subPills = () => Array.from(subCapsule() ? subCapsule().querySelectorAll('.pill-wrap') : []);
  const subLabels = () => subPills().map((p) => p.querySelector('.pill-label').textContent);
  const openSubPill = () => subCapsule() && subCapsule().querySelector('.pill-wrap.selected');
  /** The tab for a chat by its visible title, wherever its controls live. */
  const chatTab = (title) =>
    Array.from(bar().querySelectorAll('.tab-capsule .pill-wrap')).find(
      (p) => p.querySelector('.pill-label').textContent === title
    );
  /** The subtab whose transcript is on screen — null while that is the chat's own. */
  const subtab = () => {
    const open = openSubPill();
    if (!open) return null;
    const text = open.querySelector('.pill-label').textContent;
    return text === 'Chat' ? null : text;
  };
  /** How the open subtab is ANNOUNCED: the kind and the state live here, not in the visible text. */
  const subtabName = () => {
    const open = openSubPill();
    return open ? open.getAttribute('aria-label') : null;
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

  it('at rest it shows the chats and the subtabs of the one you are in', () => {
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    // Nothing but the chat's own transcript is open, and the row says so with its first pill rather than by
    // being absent: a row that appears and disappears moves the transcript under the pointer.
    expect(subtab()).toBe(null);
    expect(subLabels()[0]).toBe('Chat');
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
    expect(subtab()).toBe('Dependencias sin usar');
    // The kind and the state are in the accessible name, the way every other view says them.
    expect(subtabName()).toBe('Subagent (Dependencias sin usar)  ·  completed');
    // Two rows for a tree three levels deep: the bar's height does not follow the tree's depth.
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('the open subtab goes back to the chat, with a blank agentId', () => {
    win.cc.revealAgentTab('b');
    expect(subtab()).toBe('Dependencias de desarrollo');
    click(openSubPill());
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('the Chat pill is the way back, and it is always in the same place', () => {
    win.cc.revealAgentTab('b');
    expect(subLabels()[0]).toBe('Chat');
    click(subPills()[0]);
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('a background task is a destination too', () => {
    click(dots());
    click(cards().find((el) => el.textContent.includes('npm run dev')));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 't1' });
    expect(subtab()).toBe('npm run dev');
    expect(subtabName()).toContain('Background Task (npm run dev)');
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
    const pin = openSubPill().querySelector('.pill-pin');
    // A subtab is a VIEW — this browser painting somebody else's transcript — so it is gone the moment you
    // look elsewhere. The pin is how you keep one.
    expect(pin).not.toBe(null);
    click(pin);
    expect(sent.pop()).toEqual({ type: 'pinSubtab', agentId: 'b' });
  });

  it('only the OPEN subtab carries controls', () => {
    // The row can hold a dozen pills. Giving every one of them a ⋮, a pin and a × would put forty targets a
    // few pixels wide in a 24px row — and the controls only ever act on what you are reading anyway. These
    // are exactly what the single-subtab row this replaced already offered.
    win.cc.revealAgentTab('b');
    const open = openSubPill();
    expect(open.querySelector('.pill-pin')).not.toBe(null);
    expect(open.querySelector('.pill-more')).not.toBe(null);
    expect(open.querySelector('.pill-x')).not.toBe(null);
    const others = subPills().filter((p) => p !== open);
    expect(others.length).toBeGreaterThan(0);
    for (const p of others) {
      expect(p.querySelector('.pill-pin, .pill-more, .pill-x')).toBe(null);
    }
  });

  /**
   * The × closes the chat — the whole path, from the click to the message.
   *
   * **This test did not exist, and that is why the button spent a day doing nothing.** There were three
   * references to `.pill-x` in this file and all three were about its SHAPE: that it exists on the open
   * subtab, that it does not exist on the others, and that its box is 20px in the stylesheet. Nothing ever
   * pressed it. A control whose only job is to send one message needs a test that presses it and reads the
   * message, or "it renders" and "it works" are the same green.
   */
  it('the × on a chat sends closeChat for THAT chat, and nothing else', () => {
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    const pill = chatTab('Chat 2');
    sent = [];
    click(pill.querySelector('.pill-x'));
    // Exactly one message, and it names the chat the × belongs to — not the selected one, which is the
    // mistake a handler that reads the current selection instead of its own row would make.
    expect(sent).toEqual([{ type: 'closeChat', chatId: '2' }]);
  });

  it('pressing the × does not also select the chat it is closing', () => {
    // The × sits inside the pill's own hit area. Without `stopPropagation` the click reaches the pill too,
    // so closing a background chat would first switch to it — a visible jump to a tab that is going away.
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    const pill = chatTab('Chat 2');
    sent = [];
    click(pill.querySelector('.pill-x'));
    expect(sent.map((m) => m.type)).not.toContain('selectChat');
  });

  it('the × is reachable and named for assistive technology, not just visible', () => {
    // A `<span role="button">` INSIDE a `<button>` is interactive content nested in a place the content
    // model forbids, and the `button` role is Children Presentational: a conforming browser deletes the
    // descendants from the accessibility tree, so the × is never announced and tabbing to it lands on a
    // control with no name (SC 4.1.2). Whatever the markup becomes, these two have to hold.
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    const pill = chatTab('Chat 2');
    const x = pill.querySelector('.pill-x');
    expect(x.getAttribute('aria-label')).toContain('Chat 2');
    expect(x.closest('button')).toBe(null);
  });

  it('an open subtab lists what THAT agent started, not the whole chat', () => {
    win.cc.revealAgentTab('b');
    click(openSubPill().querySelector('.pill-more'));
    const text = cardText().join(' ');
    // Rooted at the hovered agent: its own child, yes; its parent and its parent's siblings, no — the
    // pointer is on it, so that is the question being asked.
    expect(text).toContain('Dependencias de desarrollo');
    expect(text).toContain('Dependencias sin usar');
    expect(text).not.toContain('Inventario de dependencias');
  });

  it('the host can reveal an agent, and closing it returns to the chat', () => {
    win.cc.revealAgentTab('b');
    // The VISIBLE text is the bare name; the kind and the state are in the accessible name instead. A dozen
    // pills all beginning "Subagent (" spend the only scarce thing in a tool window — width — on the word
    // that repeats.
    expect(subtab()).toBe('Dependencias de desarrollo');
    expect(subtabName()).toContain('Subagent (Dependencias de desarrollo)');
    win.cc.clearAgentSelection();
    expect(subtab()).toBe(null);
  });

  it('an agent that vanished from the payload takes the selection with it', () => {
    win.cc.revealAgentTab('b');
    win.cc.tabs({ chats: CHATS, tree: [TREE[0]], tasks: TASKS });
    expect(subtab()).toBe(null);
  });

  it('a repaint keeps the hovered chat’s menu, not the selected chat’s', () => {
    // The host rebuilds the bar several times a turn. The reopen used to re-anchor to the FIRST ⋮ with no
    // chat id, i.e. the selected chat — so hovering another tab showed you your own agents a fraction of a
    // second later, which is exactly what was reported.
    const other = {
      id: '2',
      title: 'Chat 2',
      selected: false,
      tree: [{ id: 'z', parent: null, label: 'Suyo' }],
    };
    win.cc.tabs({ chats: [CHATS[0], other], tree: TREE, tasks: TASKS });
    const otherPill = Array.from(bar().querySelectorAll('.pill')).find((p) =>
      p.textContent.includes('Chat 2')
    );
    click(otherPill.querySelector('.pill-more'));
    expect(cardText().join(' ')).toContain('Suyo');
    // A push arrives while the menu is open — with something changed, so the bar really is rebuilt.
    win.cc.tabs({ chats: [{ ...CHATS[0], attention: true }, other], tree: TREE, tasks: TASKS });
    expect(cardText().join(' ')).toContain('Suyo');
    expect(cardText().join(' ')).not.toContain('Inventario de dependencias');
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

  it('a push that changes nothing does not rebuild the bar UNDER AN OPEN MENU either', () => {
    // The reported flicker. The skip used to be waived outright whenever a panel was open, so while the
    // pointer rested on a tab EVERY push rebuilt the whole bar and then re-anchored and reopened the panel
    // under the cursor — several times a second with agents running. Identity of both nodes is the check:
    // a rebuild replaces the capsule, and the reopen replaces the panel.
    click(dots());
    const capsule = bar().querySelector('.tab-capsule');
    const panel = win.document.querySelector('.tab-menu');
    expect(panel).not.toBe(null);
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).toBe(capsule);
    expect(win.document.querySelector('.tab-menu')).toBe(panel);
  });

  it('but a change INSIDE the open menu still repaints it — an agent that finished says so', () => {
    // The other half, and the reason the guard was blunt in the first place: the panel draws the tree, so a
    // status the panel is showing has to reach it. It is a change; the signature now says so.
    click(dots());
    const panel = win.document.querySelector('.tab-menu');
    expect(cardText().join(' ')).not.toContain('completed');
    const finished = TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n));
    win.cc.tabs({ chats: CHATS, tree: finished, tasks: TASKS });
    expect(win.document.querySelector('.tab-menu')).not.toBe(panel); // repainted, and reopened on the new bar
    expect(cards().some((el) => el.querySelector('.pill-dot.completed'))).toBe(true);
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

  it('the overflow is bounded and reachable — the CSS contract that kept breaking', () => {
    // Reported twice, and jsdom lays nothing out, so only the stylesheet can be asserted. Two ways this
    // failed in practice, both invisible to every other test:
    //   1. A container that does not bound its width lets the capsule GROW instead of overflowing, so the
    //      extra tabs are painted outside the tool window and no amount of scrolling reaches them.
    //   2. The scrollbar was hidden (`scrollbar-width: none`) on the grounds that the wheel would do — and
    //      when the wheel did not, there was no way left to even see that there was more.
    // Comments stripped first: the rules below are EXPLAINED at length right above them, and matching raw
    // text would assert against the explanation rather than against the code.
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = (selector) => {
      const at = css.indexOf(selector + ' {');
      return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
    };
    for (const sel of ['#tabsbar', '.tab-rows', '.tab-row']) {
      expect(block(sel), `${sel} must bound its width`).toMatch(/max-width:\s*100%/);
    }
    expect(block('.tab-capsule')).toMatch(/overflow-x:\s*auto/);
    // No scrollbar to aim at — the row is grabbed, wheeled, or moved by centring the chat you select. The
    // grab needs a cursor that says so, and the drag must not fight the smooth scrolling.
    expect(block('.tab-capsule')).toMatch(/cursor:\s*grab/);
    expect(block('.tab-capsule.dragging')).toMatch(/scroll-behavior:\s*auto/);
    // A tab is capped, or one named after a long first prompt fills the bar on its own.
    expect(block('.tab-capsule .pill')).toMatch(/max-width:/);
  });

  it('the row is dragged by grabbing it, and the drag does not switch chat on release', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 200)); // 100px to the left
    expect(capsule.scrollLeft).toBe(100);
    expect(capsule.classList.contains('dragging')).toBe(true);
    win.document.dispatchEvent(at('mouseup', 200));
    expect(capsule.classList.contains('dragging')).toBe(false);

    // Releasing over a tab must NOT also select that chat: the click that ends a drag is swallowed.
    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent).toEqual([]);
    // ...and the very next click, with no drag before it, works normally again.
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1);
  });

  it('a movement below the threshold is a click, not a drag', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 298)); // 2px — a hand resting, not a drag
    win.document.dispatchEvent(at('mouseup', 298));
    expect(capsule.scrollLeft).toBe(0);
    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1); // the chat still selects
  });

  // NB this one PASSES WHILE BLIND, and that is worth knowing rather than fixing: jsdom has no scroll methods
  // on Element and treats scrollLeft as a plain property, so it exercises the fallback and can say nothing
  // about the behaviour the real browser applies. It pins the translation of the gesture, no more. The
  // assertion that covers what actually broke is the next test, which looks at the argument.
  it('the wheel scrolls the chats, because a vertical wheel does not move a horizontal row', () => {
    const capsule = bar().querySelector('.tab-capsule');
    // jsdom lays nothing out, so the overflow is simulated. The handler does not measure it — a measurement
    // that says "nothing to scroll" when there is leaves the row inert with no way to tell why — but a row
    // with somewhere to go is the situation being described.
    Object.defineProperty(capsule, 'scrollWidth', { value: 900, configurable: true });
    Object.defineProperty(capsule, 'clientWidth', { value: 300, configurable: true });
    capsule.scrollLeft = 0;
    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(capsule.scrollLeft).toBe(120);
  });

  it('the wheel asks for an INSTANT scroll, and consecutive ticks accumulate', () => {
    // `.tab-capsule` declares `scroll-behavior: smooth`, and an assignment to scrollLeft takes its behaviour
    // from that declaration: it starts an animation and leaves the offset reading where the animation BEGAN.
    // Two things broke on that, both invisible to the test above. The read-back could not tell whether the row
    // had moved, so the gesture never claimed the wheel; and the next tick computed its target from the stale
    // offset, so fast wheeling re-aimed at the same place instead of walking along the row. Naming the
    // behaviour is the fix — 'auto' would not be, since 'auto' means "ask the CSS".
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const asked = [];
    // A real instant scroll has landed by the time the call returns; a smooth one has not, which is the whole
    // bug. Modelling the instant one is what lets the second tick be asserted at all.
    capsule.scrollTo = (opts) => {
      asked.push(opts);
      capsule.scrollLeft = opts.left;
    };

    const first = new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true });
    capsule.dispatchEvent(first);
    expect(asked).toEqual([{ left: 120, behavior: 'instant' }]);
    // Having moved, the gesture belongs to the row: the page must not scroll underneath it as well.
    expect(first.defaultPrevented).toBe(true);

    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(asked[1].left).toBe(240);
  });

  it('repainting the bar does not pile up another document-wide drag listener', () => {
    // The drag continues over the whole document once it starts, so half of it cannot live on the capsule.
    // The bar builds a NEW capsule on every repaint — several times a turn — and a `document` listener added
    // per capsule outlives the element it was added for: an unbounded pile, each handler running on every
    // mouse move and each holding a discarded row alive. Those two halves are registered once, for the page.
    const before = bar().querySelector('.tab-capsule');
    const added = [];
    const real = win.document.addEventListener.bind(win.document);
    win.document.addEventListener = (type, fn, opts) => {
      added.push(type);
      return real(type, fn, opts);
    };
    try {
      for (let i = 0; i < 5; i++) {
        win.cc.tabs({ chats: [{ id: '1', title: `Chat ${i}`, selected: true }], tree: TREE, tasks: TASKS });
      }
    } finally {
      win.document.addEventListener = real;
    }

    // The repaints were real ones — a rebuild replaces the capsule — so the count below is about the
    // listeners and not about a render that never happened.
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
    expect(added.filter((t) => t === 'mousemove' || t === 'mouseup')).toEqual([]);

    // ...and the row that survived all that is still draggable: the fix is one registration, not none.
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 240));
    expect(capsule.scrollLeft).toBe(60);
    win.document.dispatchEvent(at('mouseup', 240));
  });

  // The row is rebuilt from nothing on every repaint (the test above proves the capsule is a NEW element), and
  // a new element starts at offset zero. So everything the reader did to the row — the drag, the wheel — was
  // undone several times a turn, and then the selected tab was re-centred on top of that, which aimed the row
  // at the tab they had deliberately scrolled away from.
  describe('the row stays where the reader put it', () => {
    const MANY = [
      { id: '1', title: 'Chat 1', selected: true },
      { id: '2', title: 'Chat 2' },
      { id: '3', title: 'Chat 3' },
    ];
    const push = (chats, tree) => win.cc.tabs({ chats, tree: tree || TREE, tasks: TASKS });

    it('carries the offset across a repaint', () => {
      push(MANY);
      const before = bar().querySelector('.tab-capsule');
      before.scrollLeft = 180;

      // A repaint with the SAME selection: an agent event, a renamed chat — several times a turn.
      push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
      const after = bar().querySelector('.tab-capsule');
      expect(after).not.toBe(before); // a real rebuild, or the assertion below proves nothing
      expect(after.scrollLeft).toBe(180);
    });

    it('asks for the restore INSTANTLY, because the capsule scrolls smoothly', () => {
      // `.tab-capsule` declares `scroll-behavior: smooth`, which a bare assignment obeys: the row would GLIDE
      // back into place on every repaint instead of simply being where it was. jsdom has no scroll methods on
      // Element, so the call has to be observed on a stub — the same technique the wheel test uses.
      push(MANY);
      bar().querySelector('.tab-capsule').scrollLeft = 200;
      const asked = [];
      win.Element.prototype.scrollTo = function (opts) {
        asked.push(opts);
        this.scrollLeft = opts.left;
      };
      try {
        push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
      } finally {
        delete win.Element.prototype.scrollTo;
      }
      expect(asked).toEqual([{ left: 200, behavior: 'instant' }]);
    });

    it('re-centres the selected chat when the selection CHANGES, and not on every repaint', () => {
      // jsdom has no `scrollIntoView` at all, and the code tests for it before calling — so the stub is what
      // makes the centring observable, and its absence is what the module already tolerates. The frame
      // callback is run inline for the same reason: the centring is deliberately deferred to one, and a
      // real frame is not something a test can wait for.
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function (opts) {
        // The CHAT row only. The subtab row centres its own open pill on the same terms, in its own capsule
        // and with its own test — collecting both here would make this assertion fail whenever that row
        // simply did its job, which is a test measuring the wrong thing rather than a defect.
        if (this.closest('.subtab-capsule')) return;
        centred.push([this.querySelector('.pill-label').textContent, opts.inline]);
      };
      try {
        setup(); // a fresh page: the first draw has to start the row aimed at the open chat
        push(MANY);
        expect(centred).toEqual([['Chat 1', 'center']]);

        push(MANY.map((c) => ({ ...c, title: c.title + '!' }))); // same chat open — leave the row alone
        expect(centred.length).toBe(1);

        push([
          { id: '1', title: 'Chat 1' },
          { id: '2', title: 'Chat 2', selected: true },
          { id: '3', title: 'Chat 3' },
        ]);
        expect(centred.length).toBe(2);
        expect(centred[1]).toEqual(['Chat 2', 'center']);
      } finally {
        delete win.Element.prototype.scrollIntoView;
        win.requestAnimationFrame = rafBefore;
      }
    });
  });

  // The second row: everything the chat on screen started, flat, one click each. The guard is the thing to
  // watch here — `render` returns early on an unchanged signature, so a row the signature does not describe is
  // a row that is drawn once and then frozen for the rest of the session.
  describe('the subtabs row', () => {
    it('lists the chat and everything it started, in the order the tree is walked', () => {
      // Depth-first, so a subagent follows the agent that started it, then the background tasks. Same walk the
      // guard reads (`T.workOrder`): two traversals that have to agree is how a stale row ships.
      expect(subLabels()).toEqual([
        'Chat',
        'Inventario de dependencias',
        'Dependencias de desarrollo',
        'Dependencias sin usar',
        'npm run dev',
      ]);
      // Still two rows: this is a flat quick-access row, not a second tree.
      expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    });

    it('paints the state word the HOST sent, and never derives one', () => {
      // One vocabulary for every view (`JcefStatus`: running|completed|failed|stopped). The bar used to say
      // `done` where the dashboard said `completed`, so one task had two colours and two CSS rules.
      const dotOf = (name) =>
        subPills()
          .find((p) => p.querySelector('.pill-label').textContent === name)
          .querySelector('.pill-dot');
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot running');
      expect(dotOf('Dependencias sin usar').className).toBe('pill-dot completed');
      // An unknown word is painted as it arrives rather than mapped onto one of ours: the host owns the
      // vocabulary, and a page that "corrects" it is how the two views drifted apart in the first place.
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'stopped' } : n)),
        tasks: TASKS,
      });
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot stopped');
    });

    it('says the state in words too, not only in the colour of the dot', () => {
      // WCAG 2.2 SC 1.4.1: colour is never the only carrier. The word rides in the accessible name and in the
      // tooltip, which is also what a voice-control user has to say to reach the pill.
      const pill = subPills().find(
        (p) => p.querySelector('.pill-label').textContent === 'Inventario de dependencias'
      );
      expect(pill.getAttribute('aria-label')).toContain('running');
      expect(pill.getAttribute('title')).toContain('running');
      // And the visible text is contained in the accessible name (SC 2.5.3 Label in Name).
      expect(pill.getAttribute('aria-label')).toContain('Inventario de dependencias');
    });

    it('a chat that started nothing has no subtabs row at all', () => {
      win.cc.tabs({ chats: CHATS, tree: [], tasks: [] });
      expect(subCapsule()).toBe(null);
      expect(bar().querySelectorAll('.tab-row').length).toBe(1);
    });

    // The three DOM-identity tests, the same shape as the ones over the chats' row above. This row is the one
    // the guard was most likely to freeze, because the whole reason it exists is to show work that MOVES.
    it('an identical push does not rebuild it', () => {
      const before = subCapsule();
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      expect(subCapsule()).toBe(before);
    });

    it('an agent that FINISHES repaints it — the failure the guard would hide', () => {
      // Without the row in the signature this push looks identical to the last one: the agent would keep its
      // running colour for the rest of the session, and nothing on screen would say otherwise.
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(
        subPills()
          .find((p) => p.querySelector('.pill-label').textContent === 'Inventario de dependencias')
          .querySelector('.pill-dot.completed')
      ).not.toBe(null);
    });

    it('an agent that STARTS repaints it, and gets its own pill', () => {
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: 'a', label: 'Nuevo', type: 'Explore', status: 'running' }]),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subLabels()).toContain('Nuevo');
    });

    it('carries its OWN offset across a repaint', () => {
      // Two capsules in the bar now, each with its own reader position. The subtab row is read by class and
      // not by index for exactly this reason: `.tab-capsule` matches the chats' one first.
      const before = subCapsule();
      before.scrollLeft = 140;
      bar().querySelector('.tab-capsule').scrollLeft = 60;
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      // A real rebuild, or the two assertions below prove nothing at all.
      expect(subCapsule()).not.toBe(before);
      expect(subCapsule().scrollLeft).toBe(140);
      expect(bar().querySelector('.tab-capsule').scrollLeft).toBe(60);
    });

    it('re-centres the open subtab when the SELECTION changes, and not on every repaint', () => {
      // Same rule as the chats' row, and the same reason: re-aiming on every push undoes the drag that was
      // just restored, which makes the row unreadable while a turn is running.
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function () {
        if (this.closest('.subtab-capsule')) centred.push(this.querySelector('.pill-label').textContent);
      };
      try {
        win.cc.revealAgentTab('b');
        expect(centred).toEqual(['Dependencias de desarrollo']);

        // A push that only moves a status: same subtab open, leave the row where the reader put it.
        win.cc.tabs({
          chats: CHATS,
          tree: TREE.map((n) => (n.id === 'c' ? { ...n, status: 'failed' } : n)),
          tasks: TASKS,
        });
        expect(centred.length).toBe(1);

        win.cc.revealAgentTab('c');
        expect(centred).toEqual(['Dependencias de desarrollo', 'Dependencias sin usar']);
      } finally {
        delete win.Element.prototype.scrollIntoView;
        win.requestAnimationFrame = rafBefore;
      }
    });

    it('is scrollable and bounded like the row above it', () => {
      // It can hold a dozen pills in a tool window a few hundred pixels wide, so it needs every one of the
      // gestures the chats' row needed — the wheel translated, the row grabbable — or its far end is
      // unreachable. Registered on the capsule, so the check is that the capsule got them.
      const capsule = subCapsule();
      capsule.scrollLeft = 0;
      capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 90, bubbles: true, cancelable: true }));
      expect(capsule.scrollLeft).toBe(90);

      const at = (type, x) =>
        new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
      capsule.dispatchEvent(at('mousedown', 300));
      win.document.dispatchEvent(at('mousemove', 260));
      expect(capsule.scrollLeft).toBe(130);
      win.document.dispatchEvent(at('mouseup', 260));
    });

    it('the pills are sized to the composer register, capped and ellipsised', () => {
      // jsdom lays nothing out, so only the stylesheet can be asserted. The cap is what stops one chat named
      // after a long first prompt filling the bar; the height is what makes the bar read as the same product
      // as the composer's control row (`.composer-bar .pill`: 26px, `0 9px`, 11.5px).
      const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
      // Anchored at the start of a line, unlike the looser lookup the older test uses: `.pill {` is a
      // SUBSTRING of several composer selectors, and matching one of those would assert the composer's
      // numbers while claiming to assert the tab bar's.
      const block = (selector) => {
        const at = css.indexOf('\n' + selector + ' {');
        return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
      };
      expect(block('.pill')).toMatch(/height:\s*24px/);
      expect(block('.pill')).toMatch(/font-size:\s*11\.5px/);
      expect(block('.subtab-capsule .pill')).toMatch(/height:/);
      // The invariants that kept breaking, restated so a size change cannot quietly take them with it.
      expect(block('.pill-label')).toMatch(/text-overflow:\s*ellipsis/);
      expect(block('.tab-capsule .pill')).toMatch(/max-width:/);
      // A control inside a pill keeps a declared box rather than whatever the glyph measures.
      expect(block('.pill-x')).toMatch(/width:\s*20px/);
      expect(block('.pill-x')).toMatch(/height:\s*20px/);
    });
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
