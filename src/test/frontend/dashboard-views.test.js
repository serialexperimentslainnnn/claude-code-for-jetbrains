// The dashboard's two views and the rows that link to a tab.
//
// Two classes of rule are pinned here, and both came from bugs seen on screen:
//  - the switcher behaves like a switcher (one press per action, the view you asked for, an honest empty
//    state) — the first version scrolled a single panel to an anchor, so pressing "Agents" with no agents
//    left the Session cards up and looked broken;
//  - the controls are real controls (WCAG 2.2 AA): a <button> rather than a div wearing role="button",
//    programmatic state rather than colour alone, and a spoken announcement when the panel swaps content
//    without the focus moving (4.1.3 Status Messages).
//
// **Workloads replaced three views.** Agents, Subagents and Background tasks were three windows onto one
// tree: to see whether an agent had spawned anything you switched view, lost the parent, and read a
// breadcrumb to work out where you were. One already-expanded tree answers all three questions at once.
const { loadFrontend } = require('./helpers/load');

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
    // Session keeps the session data and NONE of the agent data — that separation is the whole point.
    openView('session');
    expect(titles()).not.toContain('Workloads');
  });

  it('draws the whole tree as a diagram: the chat, its agents, their agents and the tasks they started', () => {
    openView('workloads');
    const cards = nodes();
    expect(cards.length).toBe(4); // the chat + two agents + one task
    expect(cards.join(' ')).toContain('Translate the docs');
    expect(cards.join(' ')).toContain('npm run dev');
    expect(cards.join(' ')).toContain('Check links');
  });

  /**
   * EVERY node in the payload is drawn, and no two overlap.
   *
   * The layout dedups by id while it walks (a malformed parent link must not loop), and a dedup that marks
   * a node as seen at the wrong moment silently drops branches — which is exactly what "half the items are
   * missing" looks like from outside. Counting is the only assertion that catches it; jsdom lays nothing
   * out, so it cannot be caught by looking.
   */
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
    expect(cards.length).toBe(tree.length + tasks.length + 1); // + the chat itself
    tree.forEach((n) => {
      expect(cards.filter((c) => c.textContent.includes(n.label + ')')).length).toBe(1);
    });

    // No two cards on the same row may overlap: their spans are disjoint.
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
    // `Agent (…)` / `Subagent (…)` / `Background Task (…)`: the model's title is a sentence about the job
    // and says nothing about what kind of thing is running it — and the transcript card that spawned the
    // work is already labelled this way, so the same work must not have two names.
    expect(text.some((t) => t.startsWith('Agent (Translate the docs)'))).toBe(true);
    expect(text.some((t) => t.startsWith('Subagent (Check links)'))).toBe(true);
    expect(text.some((t) => t.startsWith('Background Task (npm run dev)'))).toBe(true);
  });

  it('is a left-to-right tree: children RIGHT of their parent, and the parent centred on them', () => {
    openView('workloads');
    const cards = Array.from(panel().querySelectorAll('.dg-card'));
    const at = (label) => {
      const el = cards.find((c) => c.textContent.includes(label));
      // parseFloat, not parseInt: a centre is often fractional, and truncating it here would force this
      // assertion to be written with a tolerance — i.e. would hide whether the layout is actually exact.
      const x = parseFloat(el.style.left);
      const w = parseFloat(el.style.width);
      // Cards are sized to their own text, so "centred" is about CENTRES, not left edges.
      return { x, w, mid: x + w / 2, y: parseFloat(el.style.top) };
    };
    const chat = at('This chat');
    const agent = at('Translate the docs');
    const task = at('npm run dev');
    const sub = at('Check links');

    // Depth is the COLUMN: every generation sits strictly to the right of the one that spawned it, and a
    // whole level shares one x, so the columns line up instead of stair-stepping.
    expect(agent.x).toBeGreaterThanOrEqual(chat.x + chat.w);
    expect(sub.x).toBeGreaterThanOrEqual(agent.x + agent.w);
    expect(task.x).toBe(sub.x);
    // Siblings stack vertically and never overlap.
    expect(Math.abs(task.y - sub.y)).toBeGreaterThanOrEqual(28); // NODE_H
    // A parent is centred on the span of its children — exactly, not approximately. That is the one rule the
    // whole layout rests on, so it is asserted as an equality.
    expect(agent.y + 28 / 2).toBe((sub.y + 28 / 2 + (task.y + 28 / 2)) / 2);
    // One connector per child, and they exist at all: a `color-mix()` over 100% once made the stroke invalid,
    // so every path was there and none of them painted.
    expect(panel().querySelectorAll('.dg-edges path').length).toBe(3);
  });

  it('every node is a destination: a chat, an agent and a task each send the host somewhere', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openView('workloads');
    const card = (label) =>
      Array.from(panel().querySelectorAll('.dg-card')).find((c) => c.textContent.includes(label));

    card('Check links').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    // The CHAT travels with it. This diagram spans every chat, but the message lands on the panel that is on
    // screen; without the id that panel searched its own session for another chat's agent and the click did
    // nothing — while the same node in the tab bar's popup worked, because there the owner is always the
    // panel receiving it.
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
    // What is running does not belong to the tab you happen to be looking at: agents keep going in the
    // other chats, and a view that showed only the selected one answered the question with a fraction of
    // the truth.
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
    // An honest gap has to be VISIBLE to be honest: hiding it would read as "no background work ran".
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

    // Chat is one of the places you can be, not a mode of the others, so it is always drawn — and with the
    // dashboard closed it is the one lit, because then the chat IS the view you are looking at.
    expect(chat().hidden).toBe(false);
    expect(lit()).toEqual(['Chat']);

    openView('workloads');
    expect(chat().hidden).toBe(false);
    expect(lit()).toEqual(['Workloads']);

    // The Plan button is in the DOM but HIDDEN: most sessions never write a plan, and a permanent button
    // whose view reads "No plan for this session" is the panel answering a question nobody asked.
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
    // No plan: present in the DOM, hidden. This is the discoverability fix — the plan used to be a card
    // buried among the Session stats, where the person who asked for it could not find it.
    win.cc.session({ plan: null });
    expect(planBtn().hidden).toBe(true);

    win.cc.session({ plan: { body: '## Plan\n\n1. Do the thing', path: '/tmp/plan.md' } });
    expect(planBtn().hidden).toBe(false);

    openView('plan');
    expect(panel().textContent).toContain('Do the thing');
    // The path is provenance, so it is shown — but the body is what the view is for.
    expect(panel().textContent).toContain('/tmp/plan.md');

    // Leaving plan mode is a real transition, not an error: the button goes, and the panel must not be
    // left showing a view with no lit button and no way back to it.
    win.cc.session({ plan: null });
    expect(planBtn().hidden).toBe(true);
    expect(
      Array.from(win.document.querySelectorAll('.dash-toggle.active')).map((b) => b.textContent)
    ).not.toContain('Plan');
  });

  it('one press switches, and pressing the open view returns to the chat', () => {
    openView('workloads');
    expect(panel().hasAttribute('hidden')).toBe(false);
    // THE BUG: the first button used to rename itself to "Chat" while another view was active, so the
    // button that said "Chat" was not the one that went there and it took two presses.
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
    // Not a div wearing role="button": the element that already has focus, Enter and Space.
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
    // A task is a process, not a conversation: what you clicked to read is ITS output. Sending the user to
    // whoever launched it — which is what this node used to do — either did nothing visible or moved them
    // somewhere unrelated.
    node.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent.pop()).toEqual({ type: 'revealBackgroundTask', taskId: 'task-1', chatId: '' });
    // And going there means leaving the dashboard: selecting behind an open panel changes nothing you see.
    expect(panel().hasAttribute('hidden')).toBe(true);
  });

  it('a finished background task keeps its node and loses its Stop button', () => {
    win.cc.session({
      agentTree: [],
      // `status` is named by the host (JcefStatus), not derived here: this module said `completed` while the
      // tab bar said `done` for the same task, so one thing had two colours depending on where you read it.
      backgroundTasks: [
        { id: 'live', desc: 'npm run dev', type: 'bash', running: true, status: 'running' },
        { id: 'done', desc: 'build', type: 'bash', running: false, status: 'completed' },
      ],
    });
    openView('workloads');
    const tasks = panel().querySelectorAll('.dg-card.task');
    // The bug this pins: `background_tasks_changed` is a LEVEL signal, so a task that ended stopped being
    // listed and its node vanished at the exact moment its output was worth reading.
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
    // Same class, same CSS animation (`toolRun`) as a running tool card in the transcript: one visual
    // language for one fact, instead of two ways of saying "this is still going".
    expect(running.classList.contains('running')).toBe(true);
  });

  /**
   * The retention window, changeable from the view it governs.
   *
   * The window decides which finished work is still listed, so with it set narrow the diagram empties out and
   * "everything aged out" is indistinguishable from "nothing ever ran". Until now the only way to widen it
   * was Settings ▸ Claude Code ▸ Model, which is not where anyone looks when a picture goes blank.
   *
   * The host decides the words and the values; the page paints what it is sent. The fixture below therefore
   * carries deliberately UNREAL labels: a test that hardcoded the product's wording would quietly become a
   * second copy of `WorkloadWindow.label`, which is the exact duplication the payload exists to prevent —
   * what belongs here is that eight options in, eight options out, in order.
   */
  describe('the retention window control', () => {
    const MINUTES = [5, 10, 15, 30, 60, 120, 240, 0];
    const OPTIONS = MINUTES.map((minutes) => ({ minutes, label: 'window-' + minutes }));

    const control = () => panel().querySelector('.wl-window');
    const select = () => panel().querySelector('.wl-window-select');
    /** Pushes a payload with one running agent and the given window. Does not touch which view is open. */
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
      // The sentinel is worth 0, so any truthiness test on `minutes` — here or on the way in — silently
      // leaves the control blank on the one setting that shows everything.
      withWindow({ minutes: 0, options: OPTIONS });
      expect(select().value).toBe('0');
    });

    it('has an accessible name from a real label, not from the words next to it', () => {
      withWindow({ minutes: 15, options: OPTIONS });
      const label = control().querySelector('.wl-window-label');
      // A `<label for=…>` rather than `aria-label`: the visible words and the spoken ones are then one
      // string, and the label is a click target. A `for` pointing at nothing is silent — it renders
      // identically and names nothing.
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
      // Absent, not empty. A `<select>` with nothing in it is a dead affordance that looks live: opened, it
      // says nothing, and the user cannot tell a broken feature from an empty list.
      withWindow(undefined);
      expect(control()).toBeNull();
      // A second push, with the view already open, so this is a re-render rather than a first paint: an
      // options array that arrives empty is the same answer as one that never arrived.
      setWindow({ minutes: 15, options: [] });
      expect(control()).toBeNull();
    });

    it('survives the diagram: the control is still there when the window admits nothing', () => {
      // THE CASE IT EXISTS FOR. With nothing to draw the view used to fall back to the panel's generic empty
      // card, which carries no control — so the one action that brings the work back was missing exactly
      // when the window had hidden it.
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
