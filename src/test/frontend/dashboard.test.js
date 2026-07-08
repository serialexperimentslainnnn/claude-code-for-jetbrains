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
  it('row-based cards (MCP, subagents, background tasks) span the full grid row via .wide', () => {
    const win = loadFrontend(['app-session.js']);
    win.cc.session({
      subagents: [{ id: 't1', desc: 'search', type: 'Explore', status: 'running', tokens: 10, tools: 1 }],
      backgroundTasks: [{ id: 'b1', desc: 'long build', type: 'bash' }],
    });
    win.cc.mcp({ servers: [{ name: 'srv', status: 'connected' }] });
    openDashboard(win);

    const titleOf = (el) => el.querySelector('.dash-title')?.textContent;
    const cards = [...win.document.querySelectorAll('.dash-card')];
    const wideTitles = cards.filter((c) => c.classList.contains('wide')).map(titleOf);
    expect(wideTitles).toEqual(expect.arrayContaining(['Subagents', 'Background tasks', 'MCP servers']));
  });

  it('renders a Background tasks card from the level signal, with a Stop control', () => {
    const win = loadFrontend(['app-session.js']);
    win.cc.session({ backgroundTasks: [{ id: 'b1', desc: 'indexing', type: 'agent' }] });
    openDashboard(win);
    const card = [...win.document.querySelectorAll('.dash-card')]
      .find((c) => c.querySelector('.dash-title')?.textContent === 'Background tasks');
    expect(card).toBeTruthy();
    expect(card.textContent).toContain('indexing');

    const sent = [];
    win.CC.send = (m) => sent.push(m);
    card.querySelector('.btn').click();
    expect(sent).toEqual([{ type: 'stopTask', taskId: 'b1' }]);
  });
});
