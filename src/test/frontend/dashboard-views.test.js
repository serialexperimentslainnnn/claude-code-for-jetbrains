const { loadFrontend, readCss } = require('./helpers/load');

describe('dashboard views', () => {
  let win;

  const openView = (name) => {
    const btn = win.document.querySelector('.dash-toggle[data-view="' + name + '"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return btn;
  };
  const panel = () => win.document.querySelector('.dashboard');
  const titles = () => Array.from(panel().querySelectorAll('.dash-title')).map((el) => el.textContent);
  const nodes = () => Array.from(panel().querySelectorAll('.dg-card')).map((r) => r.textContent);

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    win.cc.session({
      context: { used: 10, max: 100, pct: 10, categories: [] },
      agentTree: [
        {
          agentId: 'agent-a',
          label: 'Translate the docs',
          type: 'general-purpose',
          status: 'running',
          depth: 1,
          parent: null,
          chain: 'Chat 1  ›  Translate the docs',
          running: true,
        },
        {
          agentId: 'agent-b',
          label: 'Check links',
          type: 'general-purpose',
          status: 'completed',
          depth: 2,
          parent: 'agent-a',
          chain: 'Chat 1  ›  Translate the docs  ›  Check links',
          running: false,
        },
      ],
      backgroundTasks: [
        {
          id: 'task-1',
          desc: 'npm run dev',
          type: 'bash',
          running: true,
          agentId: 'agent-a',
          chain: 'Chat 1  ›  Translate the docs',
        },
      ],
    });
  });

  it('shows only the view that was asked for', () => {
    openView('workloads');
    expect(titles()).toEqual(['Workloads']);
    openView('session');
    expect(titles()).not.toContain('Workloads');
  });

  it('draws the whole tree as a diagram: the chat, its agents, their agents and the tasks they started', () => {
    openView('workloads');
    const cards = nodes();
    expect(cards.length).toBe(4);
    expect(cards.join(' ')).toContain('Translate the docs');
    expect(cards.join(' ')).toContain('npm run dev');
    expect(cards.join(' ')).toContain('Check links');
  });

  it('draws every node it was given, once, without overlapping siblings', () => {
    const tree = [];
    for (let i = 0; i < 6; i++) {
      tree.push({ agentId: 'a' + i, label: 'Agent ' + i, type: 'general-purpose', parent: null });
      for (let j = 0; j < 3; j++) {
        tree.push({ agentId: 'a' + i + 'b' + j, label: 'Sub ' + i + j, type: 'Explore', parent: 'a' + i });
      }
    }
    const tasks = [{ id: 't', desc: 'npm run dev', type: 'bash', running: true, agentId: 'a0' }];
    win.cc.session({ workloads: [{ chatId: '1', title: 'Chat 1', selected: true, tree, tasks }] });
    openView('workloads');

    const cards = Array.from(panel().querySelectorAll('.dg-card'));
    expect(cards.length).toBe(tree.length + tasks.length + 1);
    tree.forEach((n) => {
      expect(cards.filter((c) => c.textContent.includes(n.label + ')')).length).toBe(1);
    });

    const byRow = {};
    cards.forEach((c) => {
      const y = c.style.top;
      (byRow[y] = byRow[y] || []).push({
        x: parseInt(c.style.left, 10),
        w: parseInt(c.style.width, 10),
      });
    });
    Object.keys(byRow).forEach((y) => {
      const row = byRow[y].sort((p, q) => p.x - q.x);
      for (let i = 1; i < row.length; i++) {
        expect(row[i].x).toBeGreaterThanOrEqual(row[i - 1].x + row[i - 1].w);
      }
    });
  });

  it('a node says WHAT it is before it says its title', () => {
    openView('workloads');
    const text = nodes();
    expect(text.some((t) => t.startsWith('Agent (Translate the docs)'))).toBe(true);
    expect(text.some((t) => t.startsWith('Subagent (Check links)'))).toBe(true);
  });

  it('a background task is SHOWN `BT:` and CALLED `Background Task (…)`', () => {
    openView('workloads');
    const card = Array.from(panel().querySelectorAll('.dg-card.task')).find((c) =>
      c.textContent.includes('npm run dev')
    );
    expect(card).toBeTruthy();
    expect(card.textContent.startsWith('BT: npm run dev')).toBe(true);
    expect(card.getAttribute('aria-label')).toBe('Background Task (npm run dev)');
    const agent = Array.from(panel().querySelectorAll('.dg-card.agent')).find((c) =>
      c.textContent.includes('Translate the docs')
    );
    expect(agent.hasAttribute('aria-label')).toBe(false);
  });

  it('is a left-to-right tree: children RIGHT of their parent, and the parent centred on them', () => {
    openView('workloads');
    const cards = Array.from(panel().querySelectorAll('.dg-card'));
    const at = (label) => {
      const el = cards.find((c) => c.textContent.includes(label));
      const x = parseFloat(el.style.left);
      const w = parseFloat(el.style.width);
      return { x, w, mid: x + w / 2, y: parseFloat(el.style.top) };
    };
    const chat = at('This chat');
    const agent = at('Translate the docs');
    const task = at('npm run dev');
    const sub = at('Check links');

    expect(agent.x).toBeGreaterThanOrEqual(chat.x + chat.w);
    expect(sub.x).toBeGreaterThanOrEqual(agent.x + agent.w);
    expect(task.x).toBe(sub.x);
    expect(Math.abs(task.y - sub.y)).toBeGreaterThanOrEqual(28);
    expect(agent.y + 28 / 2).toBe((sub.y + 28 / 2 + (task.y + 28 / 2)) / 2);
    expect(panel().querySelectorAll('.dg-edges path').length).toBe(3);
  });

  describe('how wide a node is', () => {
    const sheet = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const rule = (selector) => {
      const at = sheet.indexOf('\n' + selector + ' {');
      if (at < 0) throw new Error('no rule for ' + selector);
      return sheet.slice(sheet.indexOf('{', at) + 1, sheet.indexOf('}', at));
    };
    const shrinkOf = (selector) => {
      const body = rule(selector);
      const found = /flex-shrink:\s*([\d.]+)/.exec(body) || /flex:\s*[\d.]+\s+([\d.]+)/.exec(body);
      if (!found) throw new Error('no flex-shrink declared on ' + selector);
      return Number(found[1]);
    };
    const widths = () =>
      Array.from(panel().querySelectorAll('.dg-card')).map((c) => parseFloat(c.style.width));
    const cardFor = (text) =>
      Array.from(panel().querySelectorAll('.dg-card')).find((c) => c.textContent.includes(text));

    it('caps a long label at 230px instead of letting one card own the canvas', () => {
      win.cc.session({
        workloads: [
          {
            chatId: '1',
            title: 'Chat 1',
            selected: true,
            tree: [
              {
                agentId: 'a',
                label: 'Translate the entire documentation set into Spanish',
                type: 'general-purpose',
              },
            ],
            tasks: [],
          },
        ],
      });
      openView('workloads');
      const agent = cardFor('Translate the entire documentation set into Spanish');
      expect(parseFloat(agent.style.width)).toBe(230);
      expect(agent.getAttribute('title')).toBeTruthy();
      expect(Math.max(...widths())).toBeLessThanOrEqual(230);
    });

    it('never draws a card narrower than 96px, however little it has to say', () => {
      win.cc.session({
        workloads: [
          {
            chatId: '1',
            title: 'C',
            selected: true,
            tree: [{ agentId: 'a', label: 'D', type: '' }],
            tasks: [],
          },
        ],
      });
      openView('workloads');
      expect(parseFloat(cardFor('C').style.width)).toBe(96);
      expect(Math.min(...widths())).toBeGreaterThanOrEqual(96);
    });

    it('fits a node that has both a meta and an action, by shrinking the meta and never the action', () => {
      win.cc.session({
        workloads: [
          {
            chatId: '1',
            title: 'Chat 1',
            selected: true,
            tree: [],
            tasks: [
              {
                id: 't1',
                desc: 'reindex the entire monorepo and rebuild every cache',
                type: 'long-running-shell-command',
                running: true,
                status: 'running',
              },
            ],
          },
        ],
      });
      openView('workloads');
      const task = panel().querySelector('.dg-card.task');
      expect(parseFloat(task.style.width)).toBe(230);
      expect(task.querySelector('.dg-meta')).not.toBeNull();
      expect(task.textContent).toContain('Stop');
      expect(shrinkOf('.dg-meta')).toBeGreaterThan(0);
      expect(rule('.dg-meta')).toMatch(/min-width:\s*0/);
      expect(shrinkOf('.dg-action')).toBe(0);
      expect(rule('.dg-label')).toMatch(/min-width:\s*0/);
      expect(rule('.dg-label')).toMatch(/text-overflow:\s*ellipsis/);
    });
  });

  it('every node is a destination: a chat, an agent and a task each send the host somewhere', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('workloads');
    const card = (label) =>
      Array.from(panel().querySelectorAll('.dg-card')).find((c) => c.textContent.includes(label));

    card('Check links').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.pop()).toEqual({ type: 'revealAgent', agentId: 'agent-b', chatId: '' });
    openView('workloads');
    card('npm run dev').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 'task-1', chatId: '' });
  });

  it('draws EVERY open chat, not just the one you are looking at', () => {
    win.cc.session({
      workloads: [
        {
          chatId: '1',
          title: 'Chat 1',
          selected: true,
          tree: [{ agentId: 'a', label: 'Translate the docs', type: 'general-purpose', status: 'running' }],
          tasks: [],
        },
        {
          chatId: '2',
          title: 'Chat 2',
          selected: false,
          tree: [{ agentId: 'z', label: 'Audit the TODOs', type: 'Explore', status: 'completed' }],
          tasks: [{ id: 't9', desc: 'npm run build', type: 'bash', running: false }],
        },
      ],
    });
    openView('workloads');
    const text = nodes().join(' ');
    expect(text).toContain('Chat 1');
    expect(text).toContain('Chat 2');
    expect(text).toContain('Translate the docs');
    expect(text).toContain('Audit the TODOs');
    expect(text).toContain('npm run build');
  });

  it("clicking another chat's node goes to that chat", () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.session({
      workloads: [
        {
          chatId: '2',
          title: 'Chat 2',
          selected: false,
          tree: [{ agentId: 'z', label: 'Audit the TODOs', type: 'Explore', status: 'completed' }],
          tasks: [],
        },
      ],
    });
    openView('workloads');
    const chatCard = Array.from(panel().querySelectorAll('.dg-card.chat')).find((c) =>
      c.textContent.includes('Chat 2')
    );
    chatCard.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.some((m) => m.type === 'selectChat' && m.chatId === '2')).toBe(true);
  });

  it('a task whose owner was never resolved is still drawn, hanging off the chat', () => {
    win.cc.session({
      agentTree: [],
      backgroundTasks: [{ id: 'orphan', desc: 'unattributed', type: 'bash', running: false }],
    });
    openView('workloads');
    expect(nodes().join(' ')).toContain('unattributed');
  });

  it('an empty view says which view is empty', () => {
    win.cc.session({ agentTree: [], backgroundTasks: [] });
    openView('workloads');
    expect(titles()).toEqual(['Workloads']);
    expect(panel().textContent).toContain('Nothing is running');
  });

  it('keeps Chat on screen above the views and lights exactly one button', () => {
    const chat = () => win.document.querySelector('.dash-exit');
    const lit = () =>
      Array.from(win.document.querySelectorAll('.dash-toggle.active')).map((b) => b.textContent);

    expect(chat().hidden).toBe(false);
    expect(lit()).toEqual(['Chat']);

    openView('workloads');
    expect(chat().hidden).toBe(false);
    expect(lit()).toEqual(['Workloads']);

    const visibleLabels = () =>
      Array.from(win.document.querySelectorAll('.dash-toggles button'))
        .filter((b) => !b.hidden)
        .map((b) => b.textContent);
    expect(visibleLabels()).toEqual(['Chat', 'Session', 'Workloads']);

    chat().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(panel().hasAttribute('hidden')).toBe(true);
    expect(lit()).toEqual(['Chat']);
  });

  it('the Plan button appears only when there is a plan, and its arrival IS the notice', () => {
    const planBtn = () =>
      Array.from(win.document.querySelectorAll('.dash-toggles button')).find((b) => b.textContent === 'Plan');
    win.cc.session({ plan: null });
    expect(planBtn().hidden).toBe(true);

    win.cc.session({ plan: { body: '## Plan\n\n1. Do the thing', path: '/tmp/plan.md' } });
    expect(planBtn().hidden).toBe(false);

    openView('plan');
    expect(panel().textContent).toContain('Do the thing');
    expect(panel().textContent).toContain('/tmp/plan.md');

    win.cc.session({ plan: null });
    expect(planBtn().hidden).toBe(true);
    expect(
      Array.from(win.document.querySelectorAll('.dash-toggle.active')).map((b) => b.textContent)
    ).not.toContain('Plan');
  });

  it('one press switches, and pressing the open view returns to the chat', () => {
    openView('workloads');
    expect(panel().hasAttribute('hidden')).toBe(false);
    openView('workloads');
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

  it('the buttons carry their state programmatically, not just in colour', () => {
    const btn = openView('workloads');
    expect(btn.getAttribute('aria-expanded')).toBe('true');
    expect(btn.getAttribute('aria-current')).toBe('true');
    expect(btn.getAttribute('aria-controls')).toBe('cc-dashboard');
    expect(win.document.getElementById('cc-dashboard')).not.toBeNull();
    const other = win.document.querySelector('.dash-toggle[data-view="session"]');
    expect(other.hasAttribute('aria-current')).toBe(false);
  });

  it('announces the view, because the panel swaps content without moving focus', () => {
    const spoken = [];
    win.CC.announce = (m) => spoken.push(m);
    openView('workloads');
    expect(spoken.join(' ')).toContain('Workloads');
  });

  it('an agent node is a real button and reveals its tab', () => {
    const sent = [];
    win.__ccSend = (json) => sent.push(JSON.parse(json));
    openView('workloads');
    const node = Array.from(panel().querySelectorAll('.dg-card.agent')).find((c) =>
      c.textContent.includes('Translate the docs')
    );
    expect(node.tagName).toBe('BUTTON');
    node.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.pop()).toEqual({ type: 'revealAgent', agentId: 'agent-a', chatId: '' });
  });

  it('a background task node opens the TASK view, not its owner transcript', () => {
    const sent = [];
    win.__ccSend = (json) => sent.push(JSON.parse(json));
    openView('workloads');
    const node = panel().querySelector('.dg-card.task');
    expect(node.tagName).toBe('BUTTON');
    node.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 'task-1', chatId: '' });
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

  it('a finished background task keeps its node and loses its Stop button', () => {
    win.cc.session({
      agentTree: [],
      backgroundTasks: [
        { id: 'live', desc: 'npm run dev', type: 'bash', running: true, status: 'running' },
        { id: 'done', desc: 'build', type: 'bash', running: false, status: 'completed' },
      ],
    });
    openView('workloads');
    const tasks = panel().querySelectorAll('.dg-card.task');
    expect(tasks.length).toBe(2);
    expect(tasks[0].textContent).toContain('Stop');
    expect(tasks[1].textContent).not.toContain('Stop');
    expect(tasks[1].classList.contains('completed')).toBe(true);
  });

  it('a running node wears the same state class as the tool card it belongs to', () => {
    openView('workloads');
    const running = Array.from(panel().querySelectorAll('.dg-card')).find((c) =>
      c.textContent.includes('Translate the docs')
    );
    expect(running.classList.contains('running')).toBe(true);
  });

  describe('the retention window control', () => {
    const MINUTES = [5, 10, 15, 30, 60, 120, 240, 0];
    const OPTIONS = MINUTES.map((minutes) => ({ minutes, label: 'window-' + minutes }));

    const control = () => panel().querySelector('.wl-window');
    const select = () => panel().querySelector('.wl-window-select');
    const setWindow = (spec) => {
      win.cc.session({
        agentTree: [{ agentId: 'a', label: 'Docs', type: 'general-purpose', status: 'running' }],
        backgroundTasks: [],
        workloadWindow: spec,
      });
    };
    const withWindow = (spec) => {
      setWindow(spec);
      openView('workloads');
    };

    it('offers every window the host sent, in the order it sent them', () => {
      withWindow({ minutes: 15, options: OPTIONS });
      const rendered = Array.from(select().options);
      expect(rendered.map((o) => o.value)).toEqual(MINUTES.map(String));
      expect(rendered.map((o) => o.textContent)).toEqual(OPTIONS.map((o) => o.label));
    });

    it('shows the window that is in force', () => {
      withWindow({ minutes: 30, options: OPTIONS });
      expect(select().value).toBe('30');
    });

    it('shows "all" when that is the window in force, because 0 is falsy', () => {
      withWindow({ minutes: 0, options: OPTIONS });
      expect(select().value).toBe('0');
    });

    it('has an accessible name from a real label, not from the words next to it', () => {
      withWindow({ minutes: 15, options: OPTIONS });
      const label = control().querySelector('.wl-window-label');
      expect(label.getAttribute('for')).toBe(select().getAttribute('id'));
      expect(select().getAttribute('id')).toBeTruthy();
    });

    it('picking a window tells the host, in minutes', () => {
      const sent = [];
      win.__ccSend = (json) => sent.push(JSON.parse(json));
      withWindow({ minutes: 15, options: OPTIONS });
      const el = select();
      el.value = '120';
      el.dispatchEvent(new win.Event('change', { bubbles: true }));
      expect(sent.pop()).toEqual({ type: 'setWorkloadWindow', minutes: 120 });
    });

    it('picking "all" sends 0 rather than nothing at all', () => {
      const sent = [];
      win.__ccSend = (json) => sent.push(JSON.parse(json));
      withWindow({ minutes: 15, options: OPTIONS });
      const el = select();
      el.value = '0';
      el.dispatchEvent(new win.Event('change', { bubbles: true }));
      expect(sent.pop()).toEqual({ type: 'setWorkloadWindow', minutes: 0 });
    });

    it('renders no control at all when the host sent no options', () => {
      withWindow(undefined);
      expect(control()).toBeNull();
      setWindow({ minutes: 15, options: [] });
      expect(control()).toBeNull();
    });

    it('survives the diagram: the control is still there when the window admits nothing', () => {
      win.cc.session({
        agentTree: [],
        backgroundTasks: [],
        workloadWindow: { minutes: 5, options: OPTIONS },
      });
      openView('workloads');
      expect(titles()).toEqual(['Workloads']);
      expect(select()).not.toBeNull();
      expect(panel().querySelectorAll('.dg-card').length).toBe(0);
    });
  });
});
