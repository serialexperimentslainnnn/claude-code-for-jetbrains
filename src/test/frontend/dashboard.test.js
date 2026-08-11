// Session dashboard (app-session.js). Drives the public cc.session / cc.mcp surface and asserts the real DOM,
// covering the layout bugs fixed in 4.2.0: the MCP actions row, the enable/disable switch, and `wide` cards.
const { loadFrontend } = require('./helpers/load');

function openDashboard(win) {
  // The panel builds lazily on the first cc.session/cc.mcp; force it open so the DOM exists to assert on.
  win.cc.openDashboard && win.cc.openDashboard();
  return win.document.querySelector('.dashboard');
}

describe('dashboard — MCP servers card', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-session.js']);
    win.cc.mcp({
      servers: [
        { name: 'jetbrains-mcp-server', status: 'connected' },
        { name: 'some-other-server', status: 'disabled' },
      ],
    });
  });

  it('renders one .mcp-row per server with a .mcp-actions container (the class that was missing)', () => {
    openDashboard(win);
    const rows = win.document.querySelectorAll('.mcp-row');
    expect(rows.length).toBe(2);
    for (const row of rows) {
      const actions = row.querySelector('.mcp-actions');
      expect(actions).not.toBeNull();
      // Reconnect button + the toggle switch must both live inside the actions row.
      expect(actions.querySelector('.btn')).not.toBeNull();
      expect(actions.querySelector('.toggle')).not.toBeNull();
    }
  });

  it('the enable/disable control is a switch with NO text label (the knob painted over the text before)', () => {
    openDashboard(win);
    const toggles = win.document.querySelectorAll('.mcp-row .toggle');
    expect(toggles.length).toBe(2);
    for (const t of toggles) {
      expect(t.textContent.trim()).toBe(''); // a switch, not a labelled button
      expect(t.getAttribute('role')).toBe('switch');
      expect(t.getAttribute('aria-label')).toBeTruthy(); // accessible name lives here, not in text
    }
  });

  it('reflects enabled/disabled state via the .on class + aria-checked', () => {
    openDashboard(win);
    const rows = win.document.querySelectorAll('.mcp-row');
    const connectedToggle = rows[0].querySelector('.toggle');
    const disabledToggle = rows[1].querySelector('.toggle');
    expect(connectedToggle.classList.contains('on')).toBe(true);
    expect(connectedToggle.getAttribute('aria-checked')).toBe('true');
    expect(disabledToggle.classList.contains('on')).toBe(false);
    expect(disabledToggle.getAttribute('aria-checked')).toBe('false');
  });

  it('clicking Reconnect sends an mcpReconnect message for that server', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    openDashboard(win);
    win.document.querySelector('.mcp-row .mcp-actions .btn').click();
    expect(sent).toEqual([{ type: 'mcpReconnect', name: 'jetbrains-mcp-server' }]);
  });
});

describe('dashboard — wide cards', () => {
  // 5.5.0 moved agents and background tasks OUT of the Session view and into views of their own, so the
  // Session view's only row-based card is MCP. These assertions were updated rather than deleted: the
  // separation is the feature, and a test still expecting Subagents here would be asserting the old bug.
  it('the Session view keeps only its own row-based card wide', () => {
    const win = loadFrontend(['app-session.js']);
    win.cc.session({
      agentTree: [
        {
          agentId: 'a1',
          label: 'search',
          type: 'Explore',
          status: 'running',
          depth: 1,
          parent: null,
          running: true,
        },
      ],
      backgroundTasks: [{ id: 'b1', desc: 'long build', type: 'bash' }],
    });
    win.cc.mcp({ servers: [{ name: 'srv', status: 'connected' }] });
    openDashboard(win);

    const titleOf = (el) => el.querySelector('.dash-title')?.textContent;
    const cards = [...win.document.querySelectorAll('.dash-card')];
    const titles = cards.map(titleOf);
    expect(cards.filter((c) => c.classList.contains('wide')).map(titleOf)).toContain('MCP servers');
    expect(titles).not.toContain('Agents');
    expect(titles).not.toContain('Background tasks');
  });

  it('the Workloads view renders background tasks, with a Stop control', () => {
    const win = loadFrontend(['app-session.js']);
    win.cc.session({ backgroundTasks: [{ id: 'b1', desc: 'indexing', type: 'agent' }] });
    openDashboard(win);
    // One view now: agents, their subagents and their tasks are one tree, not three windows onto it.
    win.document.querySelector('.dash-toggle[data-view="workloads"]').click();

    const card = [...win.document.querySelectorAll('.dash-card')].find(
      (c) => c.querySelector('.dash-title')?.textContent === 'Workloads'
    );
    expect(card).toBeTruthy();
    expect(card.classList.contains('wide')).toBe(true);
    expect(card.textContent).toContain('indexing');

    const sent = [];
    win.CC.send = (m) => sent.push(m);
    card.querySelector('.btn').click();
    expect(sent).toEqual([{ type: 'stopTask', taskId: 'b1' }]);
  });
});
