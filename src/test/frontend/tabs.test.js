const { loadFrontend, readCss } = require('./helpers/load');

describe('tab bar', () => {
  let win;
  let sent;

  const bar = () => win.document.getElementById('tabsbar');

  const subCapsule = () => bar().querySelector('.subtab-capsule:not(.branch-capsule)');
  const branchCapsule = () => bar().querySelector('.branch-capsule');
  const branchCapsules = () => Array.from(bar().querySelectorAll('.branch-capsule'));
  const branchPills = () =>
    branchCapsules().reduce((all, cap) => all.concat(Array.from(cap.querySelectorAll('.pill-wrap'))), []);
  const branchLabels = (level) => {
    const cap = branchCapsules()[level || 0];
    return cap
      ? Array.from(cap.querySelectorAll('.pill-wrap')).map((p) => p.querySelector('.pill-label').textContent)
      : [];
  };
  const branchOwners = () => branchCapsules().map((c) => c.getAttribute('aria-label'));
  const subPills = () => Array.from(subCapsule() ? subCapsule().querySelectorAll('.pill-wrap') : []);
  const subLabels = () => subPills().map((p) => p.querySelector('.pill-label').textContent);
  const openSubPill = () => bar().querySelector('.subtab-capsule .pill-wrap.selected');
  const subPill = (name) =>
    subPills()
      .concat(branchPills())
      .find((p) => p.querySelector('.pill-label').textContent === name);
  const chatTab = (title) =>
    Array.from(bar().querySelectorAll('.tab-capsule .pill-wrap')).find(
      (p) => p.querySelector('.pill-label').textContent === title
    );
  const subtab = () => {
    const open = openSubPill();
    if (!open) return null;
    const text = open.querySelector('.pill-label').textContent;
    return text === 'Chat' ? null : text;
  };
  const subtabName = () => {
    const open = openSubPill();
    return open ? open.querySelector('.pill').getAttribute('aria-label') : null;
  };

  const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

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

  it('at rest it shows the chats and what the CHAT started', () => {
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    expect(subtab()).toBe(null);
    expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
    expect(branchCapsule()).toBe(null);
  });

  it('a subagent is not in the chat’s row: it appears under the agent that invoked it', () => {
    expect(subLabels()).not.toContain('Dependencias de desarrollo');

    click(subPill('Inventario de dependencias').querySelector('.pill'));

    expect(bar().querySelectorAll('.tab-row').length).toBe(3);
    expect(branchLabels()).toEqual(['Dependencias de desarrollo']);
    expect(branchLabels()).not.toContain('Dependencias sin usar');
    expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
  });

  it('the agent that owns the branch says so, and it is not the same thing as being open', () => {
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    const owner = subPill('Inventario de dependencias');
    expect(owner.classList.contains('branch-open')).toBe(true);
    expect(owner.querySelector('.pill').getAttribute('aria-expanded')).toBe('true');
    expect(branchCapsule().getAttribute('aria-label')).toBe('Started by Inventario de dependencias');
  });

  it('opening a subagent opens ITS OWN row below, and the row above does not move', () => {
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    const before = branchLabels(0);

    click(subPill('Dependencias de desarrollo').querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: 'b' });
    expect(branchLabels(0)).toEqual(before);
    expect(bar().querySelectorAll('.tab-row').length).toBe(4);
    expect(branchLabels(1)).toEqual(['Dependencias sin usar']);
    expect(subtab()).toBe('Dependencias de desarrollo');
    expect(branchOwners()).toEqual([
      'Started by Inventario de dependencias',
      'Started by Dependencias de desarrollo',
    ]);
    expect(subPill('Inventario de dependencias').classList.contains('branch-open')).toBe(true);
    expect(subPill('Inventario de dependencias').classList.contains('selected')).toBe(false);
    expect(subPill('Dependencias de desarrollo').querySelector('.pill').getAttribute('aria-expanded')).toBe(
      'true'
    );
  });

  it('a leaf opens no row of its own, so nothing empties under the click', () => {
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    click(subPill('Dependencias de desarrollo').querySelector('.pill'));
    click(subPill('Dependencias sin usar').querySelector('.pill'));

    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: 'c' });
    expect(subtab()).toBe('Dependencias sin usar');
    expect(subtabName()).toBe('Subagent (Dependencias sin usar)  ·  completed');
    expect(bar().querySelectorAll('.tab-row').length).toBe(4);
    expect(branchLabels(1)).toEqual(['Dependencias sin usar']);
    expect(subPill('Dependencias sin usar').querySelector('.pill').getAttribute('aria-expanded')).toBeNull();
  });

  it('the depth is not capped: a fourth level gets a fourth row', () => {
    win.cc.tabs({
      chats: CHATS,
      tree: TREE.concat([
        { id: 'd', parent: 'c', label: 'Cuarto nivel', type: 'Explore', status: 'running' },
        { id: 'e', parent: 'd', label: 'Quinto nivel', type: 'Explore', status: 'running' },
      ]),
      tasks: [],
    });
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    click(subPill('Dependencias de desarrollo').querySelector('.pill'));
    click(subPill('Dependencias sin usar').querySelector('.pill'));
    click(subPill('Cuarto nivel').querySelector('.pill'));

    expect(bar().querySelectorAll('.tab-row').length).toBe(6);
    expect(branchLabels(3)).toEqual(['Quinto nivel']);
    expect(branchOwners()[3]).toBe('Started by Cuarto nivel');
    expect(subtab()).toBe('Cuarto nivel');
  });

  it('the branch row goes away when you go back to the chat', () => {
    click(subPill('Inventario de dependencias').querySelector('.pill'));
    expect(branchCapsule()).not.toBe(null);

    click(subPills()[0].querySelector('.pill'));
    expect(branchCapsule()).toBe(null);
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('an agent that started nothing opens no row, and does not claim it could', () => {
    win.cc.tabs({
      chats: CHATS,
      tree: [{ id: 'solo', parent: null, label: 'Nada que delegar', status: 'running' }],
      tasks: [],
    });
    const pill = subPill('Nada que delegar');
    expect(pill.querySelector('.pill').hasAttribute('aria-expanded')).toBe(false);

    click(pill.querySelector('.pill'));
    expect(branchCapsule()).toBe(null);
    expect(bar().querySelectorAll('.tab-row').length).toBe(2);
  });

  it('an agent with children says it can be opened BEFORE it is opened', () => {
    expect(subPill('Inventario de dependencias').querySelector('.pill').getAttribute('aria-expanded')).toBe(
      'false'
    );
    expect(subPill('BT: npm run dev').querySelector('.pill').hasAttribute('aria-expanded')).toBe(false);
  });

  it('the open subtab goes back to the chat, with a blank agentId', () => {
    win.cc.revealAgentTab('b');
    expect(subtab()).toBe('Dependencias de desarrollo');
    click(openSubPill().querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('the Chat pill is the way back, and it is always in the same place', () => {
    win.cc.revealAgentTab('b');
    expect(subLabels()[0]).toBe('Chat');
    click(subPills()[0].querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'selectAgent', agentId: '' });
    expect(subtab()).toBe(null);
  });

  it('a background task is a destination too, and it is shown `BT:` but SAID in full', () => {
    click(subPill('BT: npm run dev').querySelector('.pill'));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 't1' });
    expect(subtab()).toBe('BT: npm run dev');
    expect(subtabName()).toContain('Background Task (npm run dev)');
    expect(subtabName()).not.toContain('BT:');
    expect(openSubPill().querySelector('.pill').getAttribute('title')).toContain('Background Task (');
  });

  it('an AGENT keeps its bare name — only the task is prefixed', () => {
    const pill = subPill('Inventario de dependencias').querySelector('.pill');
    expect(pill.textContent).toBe('Inventario de dependencias');
    expect(pill.getAttribute('aria-label')).toContain('Agent (Inventario de dependencias)');
  });

  it('only the OPEN agent subtab carries a close, and closing it is a transcript gesture', () => {
    win.cc.revealAgentTab('b');
    const open = openSubPill();
    expect(open.querySelector('.pill-x')).not.toBe(null);
    const others = subPills()
      .concat(branchPills())
      .filter((p) => p !== open);
    expect(others.length).toBeGreaterThan(0);
    for (const p of others) {
      expect(p.querySelector('.pill-x')).toBe(null);
    }
    sent = [];
    click(open.querySelector('.pill-x'));
    expect(sent).toEqual([{ type: 'closeAgent', agentId: 'b' }]);
  });

  it('a background task subtab has no close at all', () => {
    win.cc.revealTaskTab('t1');
    expect(subtab()).toBe('BT: npm run dev');
    expect(openSubPill().querySelector('.pill-x')).toBe(null);
  });

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
    expect(sent).toEqual([{ type: 'closeChat', chatId: '2' }]);
  });

  it('pressing the × does not also select the chat it is closing', () => {
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

  it('the × is named after the chat it closes, not just "Close"', () => {
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    expect(chatTab('Chat 2').querySelector('.pill-x').getAttribute('aria-label')).toContain('Chat 2');
  });

  it('the close is a real button, and a SIBLING of the pill rather than a child of it', () => {
    win.cc.revealAgentTab('b');
    const ctl = openSubPill().querySelector('.pill-x');
    expect(ctl).not.toBe(null);
    expect(ctl.tagName).toBe('BUTTON');
    expect(ctl.getAttribute('type')).toBe('button');
    expect(ctl.getAttribute('aria-label')).toBeTruthy();
    expect(ctl.parentElement.closest('button')).toBe(null);
  });

  it('the oval is declared on the WRAPPER, which is what puts the × inside the tab', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = (selector) => {
      const at = css.indexOf('\n' + selector + ' {');
      return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
    };
    expect(block('.pill-wrap')).toMatch(/border:\s*1px solid/);
    expect(block('.pill-wrap')).toMatch(/border-radius:\s*var\(--radius-pill\)/);
    expect(block('.pill-wrap')).toMatch(/[\s;]background:/);
    expect(block('.pill-wrap.selected')).toMatch(/border-color:\s*var\(--accent\)/);
    expect(block('.tab-capsule .pill')).toMatch(/border:\s*none/);
    expect(block('.tab-capsule .pill')).toMatch(/background:\s*none/);
    expect(block('.tab-capsule .pill')).toMatch(/padding:\s*0/);
    expect(block('.tab-capsule .pill')).toMatch(/flex:\s*1 1 auto/);
    expect(block('.tab-capsule .pill')).toMatch(/align-self:\s*stretch/);
    expect(block('.tab-capsule .pill')).toMatch(/height:\s*auto/);
    expect(block('.tab-capsule .pill:focus-visible')).toMatch(/outline:\s*none/);
    expect(block('.tab-capsule .pill-wrap:has(.pill:focus-visible)')).toMatch(/outline:\s*2px solid/);
    expect(css).toMatch(/forced-colors: active[\s\S]*?\.pill-wrap:has\(\.pill:focus-visible\)/);
    const wrap = chatTab('Chat 1');
    expect(wrap.classList.contains('pill-wrap')).toBe(true);
    expect(wrap.querySelector('.pill').parentElement).toBe(wrap);
    expect(wrap.querySelector('.pill-x').parentElement).toBe(wrap);
  });

  it('two chats with wildly different titles get the SAME box — width is not a function of the text', () => {
    const long = 'Take this repository and tell me everything that is wrong with it';
    win.cc.tabs({
      chats: [
        { id: '1', title: long, selected: true },
        { id: '2', title: 'test' },
      ],
      tree: [],
      tasks: [],
    });
    const wrapA = chatTab(long);
    const wrapB = chatTab('test');
    const a = wrapA.querySelector('.pill');
    const b = wrapB.querySelector('.pill');
    const shape = (el) =>
      el.className
        .split(/\s+/)
        .filter((c) => c && c !== 'selected')
        .sort()
        .join(' ');
    expect(shape(wrapA)).toBe(shape(wrapB));
    expect(wrapA.getAttribute('style')).toBe(null);
    expect(wrapB.getAttribute('style')).toBe(null);
    expect(shape(a)).toBe(shape(b));
    expect(a.getAttribute('style')).toBe(null);
    expect(b.getAttribute('style')).toBe(null);
    expect(a.querySelector('.pill-label').textContent).toBe(long);
    expect(a.getAttribute('title')).toBe(long);
    expect(a.getAttribute('aria-label')).toBe(long);
  });

  it('two subtabs with wildly different labels get the SAME box — that row is fixed-width now too', () => {
    const long = 'Revisa el guard de permisos y dime exactamente qué regla falla';
    win.cc.tabs({
      chats: CHATS,
      tree: [
        { id: 'a', parent: null, label: 'x', type: 'Explore', status: 'completed' },
        { id: 'b', parent: null, label: long, type: 'general-purpose', status: 'running' },
      ],
      tasks: [],
    });
    const shortWrap = subPill('x');
    const wideWrap = subPill(long);
    const short = shortWrap.querySelector('.pill');
    const wide = wideWrap.querySelector('.pill');
    const shape = (el) =>
      el.className
        .split(/\s+/)
        .filter((c) => c && c !== 'selected' && c !== 'branch-open')
        .sort()
        .join(' ');
    expect(shape(shortWrap)).toBe(shape(wideWrap));
    expect(shortWrap.getAttribute('style')).toBe(null);
    expect(wideWrap.getAttribute('style')).toBe(null);
    expect(shape(short)).toBe(shape(wide));
    expect(short.getAttribute('style')).toBe(null);
    expect(wide.getAttribute('style')).toBe(null);
    expect(wide.querySelector('.pill-label').textContent).toBe(long);
    expect(wide.getAttribute('aria-label')).toContain(long);
    expect(wide.getAttribute('title')).toContain(long);
  });

  it('the host can reveal an agent, and clearing it returns to the chat', () => {
    win.cc.revealAgentTab('b');
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

  it('a push that changes nothing visible does not rebuild the bar', () => {
    const before = bar().querySelector('.tab-capsule');
    win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).toBe(before);
    win.cc.tabs({ chats: [{ id: '1', title: 'Renamed', selected: true }], tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
  });

  it('a repaint keeps whatever else lives in the bar', () => {
    const other = win.document.createElement('div');
    other.id = 'a-tenant-of-the-bar';
    bar().appendChild(other);
    const before = bar().querySelector('.tab-capsule');
    win.cc.tabs({ chats: [{ id: '1', title: 'Renamed', selected: true }], tree: TREE, tasks: TASKS });
    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
    expect(win.document.getElementById('a-tenant-of-the-bar')).not.toBe(null);
  });

  it('the overflow is bounded and reachable — the CSS contract that kept breaking', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = (selector) => {
      const at = css.indexOf(selector + ' {');
      return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
    };
    for (const sel of ['#tabsbar', '.tab-rows', '.tab-row']) {
      expect(block(sel), `${sel} must bound its width`).toMatch(/max-width:\s*100%/);
    }
    expect(block('.tab-capsule')).toMatch(/overflow-x:\s*auto/);
    expect(block('.branch-capsule')).toMatch(/margin-left:\s*16px/);
    expect(block('.branch-capsule')).toMatch(/max-width:\s*calc\(100% - 16px\)/);
    expect(block('.tab-capsule')).toMatch(/cursor:\s*grab/);
    expect(block('.tab-capsule.dragging')).toMatch(/scroll-behavior:\s*auto/);
    expect(block('.tab-capsule:not(.subtab-capsule) .pill-wrap')).toMatch(/[\s;]width:\s*\d+px/);
    expect(block('.subtab-capsule .pill-wrap')).toMatch(/[\s;]width:\s*\d+px/);
    expect(block('.pill-wrap')).not.toMatch(/max-width:/);
  });

  it('the row is dragged by grabbing it, and the drag does not switch chat on release', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 200));
    expect(capsule.scrollLeft).toBe(100);
    expect(capsule.classList.contains('dragging')).toBe(true);
    win.document.dispatchEvent(at('mouseup', 200));
    expect(capsule.classList.contains('dragging')).toBe(false);

    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent).toEqual([]);
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1);
  });

  it('a movement below the threshold is a click, not a drag', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 298));
    win.document.dispatchEvent(at('mouseup', 298));
    expect(capsule.scrollLeft).toBe(0);
    sent.length = 0;
    click(bar().querySelector('.pill'));
    expect(sent.length).toBe(1);
  });

  it('the wheel scrolls the chats, because a vertical wheel does not move a horizontal row', () => {
    const capsule = bar().querySelector('.tab-capsule');
    Object.defineProperty(capsule, 'scrollWidth', { value: 900, configurable: true });
    Object.defineProperty(capsule, 'clientWidth', { value: 300, configurable: true });
    capsule.scrollLeft = 0;
    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(capsule.scrollLeft).toBe(120);
  });

  it('the wheel asks for an INSTANT scroll, and consecutive ticks accumulate', () => {
    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const asked = [];
    capsule.scrollTo = (opts) => {
      asked.push(opts);
      capsule.scrollLeft = opts.left;
    };

    const first = new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true });
    capsule.dispatchEvent(first);
    expect(asked).toEqual([{ left: 120, behavior: 'instant' }]);
    expect(first.defaultPrevented).toBe(true);

    capsule.dispatchEvent(new win.WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true }));
    expect(asked[1].left).toBe(240);
  });

  it('repainting the bar does not pile up another document-wide drag listener', () => {
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

    expect(bar().querySelector('.tab-capsule')).not.toBe(before);
    expect(added.filter((t) => t === 'mousemove' || t === 'mouseup')).toEqual([]);

    const capsule = bar().querySelector('.tab-capsule');
    capsule.scrollLeft = 0;
    const at = (type, x) =>
      new win.MouseEvent(type, { clientX: x, button: 0, bubbles: true, cancelable: true });
    capsule.dispatchEvent(at('mousedown', 300));
    win.document.dispatchEvent(at('mousemove', 240));
    expect(capsule.scrollLeft).toBe(60);
    win.document.dispatchEvent(at('mouseup', 240));
  });

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

      push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
      const after = bar().querySelector('.tab-capsule');
      expect(after).not.toBe(before);
      expect(after.scrollLeft).toBe(180);
    });

    it('asks for the restore INSTANTLY, because the capsule scrolls smoothly', () => {
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
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function (opts) {
        if (this.closest('.subtab-capsule')) return;
        centred.push([this.querySelector('.pill-label').textContent, opts.inline]);
      };
      try {
        setup();
        push(MANY);
        expect(centred).toEqual([['Chat 1', 'center']]);

        push(MANY.map((c) => ({ ...c, title: c.title + '!' })));
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

  describe('the subtabs row', () => {
    it('lists the chat, the agents the CHAT started, and its background tasks', () => {
      expect(subLabels()).toEqual(['Chat', 'Inventario de dependencias', 'BT: npm run dev']);
      expect(bar().querySelectorAll('.tab-row').length).toBe(2);
    });

    it('keeps a background task at the CHAT’s level whoever started it', () => {
      expect(subLabels()).toContain('BT: npm run dev');
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(branchLabels()).not.toContain('BT: npm run dev');
      expect(subLabels()).toContain('BT: npm run dev');
    });

    it('draws a task whose owner is not in the tree rather than hiding it', () => {
      win.cc.tabs({
        chats: CHATS,
        tree: [],
        tasks: [{ id: 't9', owner: 'long-gone', label: 'pytest -x', type: 'bash', running: true }],
      });
      expect(subLabels()).toEqual(['Chat', 'BT: pytest -x']);
    });

    it('paints the state word the HOST sent, and never derives one', () => {
      const dotOf = (name) => subPill(name).querySelector('.pill-dot');
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot running');
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(dotOf('Dependencias de desarrollo').className).toBe('pill-dot running');
      click(subPill('Dependencias de desarrollo').querySelector('.pill'));
      expect(dotOf('Dependencias sin usar').className).toBe('pill-dot completed');
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'stopped' } : n)),
        tasks: TASKS,
      });
      expect(dotOf('Inventario de dependencias').className).toBe('pill-dot stopped');
    });

    it('says the state in words too, not only in the colour of the dot', () => {
      const pill = subPill('Inventario de dependencias').querySelector('.pill');
      expect(pill.getAttribute('aria-label')).toContain('running');
      expect(pill.getAttribute('title')).toContain('running');
      expect(pill.getAttribute('aria-label')).toContain('Inventario de dependencias');
    });

    it('a chat that started nothing has no subtabs row at all', () => {
      win.cc.tabs({ chats: CHATS, tree: [], tasks: [] });
      expect(subCapsule()).toBe(null);
      expect(bar().querySelectorAll('.tab-row').length).toBe(1);
    });

    it('an identical push does not rebuild it', () => {
      const before = subCapsule();
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      win.cc.tabs({ chats: CHATS, tree: TREE, tasks: TASKS });
      expect(subCapsule()).toBe(before);
    });

    const manyUnder = (n) => {
      const tree = [{ id: 'root', parent: null, label: 'El que reparte', status: 'running' }];
      for (let i = 0; i < n; i++) {
        tree.push({ id: 'n' + i, parent: 'root', label: 'Agent ' + i, status: 'running' });
      }
      return tree;
    };

    it('an identical push does not rebuild EITHER row at dozens of pills', () => {
      const many = manyUnder(84);
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      click(subPill('El que reparte').querySelector('.pill'));
      const before = subCapsule();
      const beforeBranch = branchCapsule();
      expect(branchPills().length).toBe(84);

      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      expect(subCapsule()).toBe(before);
      expect(branchCapsule()).toBe(beforeBranch);

      win.cc.tabs({
        chats: CHATS,
        tree: many.map((n) => (n.id === 'n40' ? { ...n, status: 'completed' } : n)),
        tasks: [],
      });
      expect(branchCapsule()).not.toBe(beforeBranch);
      expect(subPill('Agent 40').querySelector('.pill-dot.completed')).not.toBe(null);
    });

    it('work churning inside a CLOSED branch does not repaint the bar at all', () => {
      const many = manyUnder(84);
      win.cc.tabs({ chats: CHATS, tree: many, tasks: [] });
      const before = subCapsule();
      expect(branchCapsule()).toBe(null);

      win.cc.tabs({
        chats: CHATS,
        tree: many.map((n) => (n.id === 'n40' ? { ...n, status: 'completed' } : n)),
        tasks: [],
      });
      expect(subCapsule()).toBe(before);
    });

    it('the FIRST subagent of a closed agent does repaint it — the pill starts disclosing', () => {
      win.cc.tabs({ chats: CHATS, tree: [TREE[0]], tasks: [] });
      const before = subCapsule();
      expect(subPill('Inventario de dependencias').querySelector('.pill').hasAttribute('aria-expanded')).toBe(
        false
      );

      win.cc.tabs({ chats: CHATS, tree: [TREE[0], TREE[1]], tasks: [] });
      expect(subCapsule()).not.toBe(before);
      expect(subPill('Inventario de dependencias').querySelector('.pill').getAttribute('aria-expanded')).toBe(
        'false'
      );
    });

    it('an agent that FINISHES repaints it — the failure the guard would hide', () => {
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subPill('Inventario de dependencias').querySelector('.pill-dot.completed')).not.toBe(null);
    });

    it('an agent the CHAT starts repaints it, and gets its own pill', () => {
      const before = subCapsule();
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: null, label: 'Nuevo', type: 'Explore', status: 'running' }]),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subLabels()).toContain('Nuevo');
    });

    it('a subagent appearing inside an OPEN row repaints it and gets its own pill', () => {
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      click(subPill('Dependencias de desarrollo').querySelector('.pill'));
      const before = branchCapsules()[1];
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: 'b', label: 'Nieto', type: 'Explore', status: 'running' }]),
        tasks: TASKS,
      });
      expect(branchCapsules()[1]).not.toBe(before);
      expect(branchLabels(1)).toContain('Nieto');
      expect(bar().querySelectorAll('.tab-row').length).toBe(4);
    });

    it('a subagent appearing under an agent whose row is CLOSED does not repaint the bar', () => {
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      const before = branchCapsules()[0];
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.concat([{ id: 'd', parent: 'c', label: 'Bisnieto', status: 'running' }]),
        tasks: TASKS,
      });
      expect(branchCapsules()[0]).toBe(before);
      expect(bar().querySelectorAll('.tab-row').length).toBe(3);
    });

    it('opening a subtab repaints it — that pill is the one wearing the accent', () => {
      const before = subCapsule();
      win.cc.revealAgentTab('c');
      expect(subCapsule()).not.toBe(before);
      expect(subtab()).toBe('Dependencias sin usar');
    });

    it('carries its OWN offset across a repaint', () => {
      const before = subCapsule();
      before.scrollLeft = 140;
      bar().querySelector('.tab-capsule').scrollLeft = 60;
      win.cc.tabs({
        chats: CHATS,
        tree: TREE.map((n) => (n.id === 'a' ? { ...n, status: 'completed' } : n)),
        tasks: TASKS,
      });
      expect(subCapsule()).not.toBe(before);
      expect(subCapsule().scrollLeft).toBe(140);
      expect(bar().querySelector('.tab-capsule').scrollLeft).toBe(60);
    });

    it('re-centres the open subtab when the SELECTION changes, and not on every repaint', () => {
      const centred = [];
      const rafBefore = win.requestAnimationFrame;
      win.requestAnimationFrame = (fn) => fn();
      win.Element.prototype.scrollIntoView = function () {
        if (this.closest('.subtab-capsule')) centred.push(this.querySelector('.pill-label').textContent);
      };
      try {
        win.cc.revealAgentTab('b');
        expect(centred).toEqual(['Dependencias de desarrollo']);

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

    it('drags, wheels and swallows the ending click exactly like the row above it', () => {
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

      sent.length = 0;
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      expect(sent).toEqual([]);
    });

    it('brings a focused pill into view, so tabbing cannot leave focus off-screen', () => {
      click(subPill('Inventario de dependencias').querySelector('.pill'));
      click(subPill('Dependencias de desarrollo').querySelector('.pill'));
      const seen = [];
      win.Element.prototype.scrollIntoView = function () {
        seen.push(this.className);
      };
      try {
        subPill('BT: npm run dev')
          .querySelector('.pill')
          .dispatchEvent(new win.FocusEvent('focusin', { bubbles: true }));
        subPill('Dependencias sin usar')
          .querySelector('.pill')
          .dispatchEvent(new win.FocusEvent('focusin', { bubbles: true }));
      } finally {
        delete win.Element.prototype.scrollIntoView;
      }
      expect(seen.length).toBe(2);
      expect(seen.every((c) => c.includes('pill'))).toBe(true);
    });

    it('the pills are sized to a declared scale, capped and ellipsised', () => {
      const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
      const block = (selector) => {
        const at = css.indexOf('\n' + selector + ' {');
        return at < 0 ? '' : css.slice(at, css.indexOf('}', at));
      };
      const chats = '.tab-capsule:not(.subtab-capsule)';
      const subtabs = '.subtab-capsule';
      expect(block('.pill')).toMatch(/height:\s*24px/);
      expect(block('.pill')).toMatch(/font-size:\s*11\.5px/);
      expect(block(`${chats} .pill-wrap`)).toMatch(/width:\s*123px/);
      expect(block('.pill-wrap')).toMatch(/flex:\s*0 0 auto/);
      expect(block('.tab-capsule .pill')).toMatch(/min-width:\s*0/);
      expect(block(`${chats} .pill-wrap`)).toMatch(/height:\s*22px/);
      expect(block(`${chats} .pill-wrap`)).toMatch(/padding:\s*0 2px 0 8px/);
      expect(block(`${chats} .pill`)).toMatch(/font-size:\s*10\.5px/);
      expect(block(`${chats} .pill-wrap.selected`)).toMatch(/height:\s*27px/);
      expect(block(`${chats} .pill.selected`)).toMatch(/font-size:\s*11\.5px/);
      expect(block(`${chats} .pill-wrap.selected`)).not.toMatch(/[\s;]width:/);
      expect(block(`${chats} .pill-wrap.selected`)).not.toMatch(/[\s;]padding:/);
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/width:\s*121px/);
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/height:\s*20px/);
      expect(block(`${subtabs} .pill-wrap`)).toMatch(/padding:\s*0 7px/);
      expect(block(`${subtabs} .pill`)).toMatch(/font-size:\s*10\.5px/);
      expect(block(`${subtabs} .pill-wrap.selected`)).toMatch(/height:\s*25px/);
      expect(block(`${subtabs} .pill.selected`)).toMatch(/font-size:\s*11\.5px/);
      expect(block(`${subtabs} .pill-wrap.selected`)).not.toMatch(/[\s;]width:/);
      expect(block(`${subtabs} .pill-wrap.selected`)).not.toMatch(/[\s;]padding:/);
      expect(block('.pill-wrap')).toMatch(/border-radius:\s*var\(--radius-pill\)/);
      expect(block('.pill-wrap.selected')).toMatch(/border-color:\s*var\(--accent\)/);
      expect(block('.pill.selected')).toMatch(/font-weight:\s*600/);
      expect(block('.pill.selected')).toMatch(/[\s;]color:\s*var\(--accent\)/);
      expect(block('.pill-label')).toMatch(/text-overflow:\s*ellipsis/);
      expect(block('.pill-label')).toMatch(/overflow:\s*hidden/);
      expect(block('.pill-label')).toMatch(/min-width:\s*0/);
      expect(block('.pill-wrap')).not.toMatch(/max-width:/);
      expect(block(`${chats} .pill-wrap`)).not.toMatch(/max-width:/);
      expect(block(`${subtabs} .pill-wrap`)).not.toMatch(/max-width:/);
      expect(block('.pill-x')).toMatch(/width:\s*20px/);
      expect(block('.pill-x')).toMatch(/height:\s*20px/);
      expect(block(`${chats} .pill-x`)).toMatch(/width:\s*24px/);
      expect(block(`${chats} .pill-x`)).toMatch(/height:\s*24px/);
      expect(block('.pill-x:hover')).not.toMatch(/background:/);
    });
  });

  it('there is no ⋮ and no pin anywhere in the document', () => {
    win.cc.tabs({
      chats: [
        { id: '1', title: 'Chat 1', selected: true },
        { id: '2', title: 'Chat 2' },
      ],
      tree: TREE,
      tasks: TASKS,
    });
    win.cc.revealAgentTab('b');
    expect(win.document.querySelectorAll('.pill-more, .pill-pin').length).toBe(0);
    expect(win.document.querySelectorAll('.tab-menu, .tab-tree').length).toBe(0);
    sent = [];
    Array.from(win.document.querySelectorAll('#tabsbar button')).forEach(click);
    expect(sent.map((m) => m.type)).not.toContain('pinSubtab');
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).not.toMatch(/\.pill-pin|\.pill-more|\.tab-menu|\.tab-tree/);
  });
});
