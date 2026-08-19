const { loadFrontend, readCss } = require('./helpers/load');

function openDashboard(win) {
  win.cc.openDashboard && win.cc.openDashboard();
  return win.document.querySelector('.dashboard');
}

describe('dashboard — MCP servers card', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js']);
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
      expect(actions.querySelector('.btn')).not.toBeNull();
      expect(actions.querySelector('.toggle')).not.toBeNull();
    }
  });

  it('the enable/disable control is a switch with NO text label (the knob painted over the text before)', () => {
    openDashboard(win);
    const toggles = win.document.querySelectorAll('.mcp-row .toggle');
    expect(toggles.length).toBe(2);
    for (const t of toggles) {
      expect(t.textContent.trim()).toBe('');
      expect(t.getAttribute('role')).toBe('switch');
      expect(t.getAttribute('aria-label')).toBeTruthy();
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
  it('the Session view keeps only its own row-based card wide', () => {
    const win = loadFrontend(['app-session.js', 'app-composer.js']);
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
    const win = loadFrontend(['app-session.js', 'app-composer.js']);
    win.cc.session({ backgroundTasks: [{ id: 'b1', desc: 'indexing', type: 'agent' }] });
    openDashboard(win);
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

describe('dashboard — a layer over the transcript, not a swap for it', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js']);
    win.cc.session({});
  });

  const conversation = () => win.document.getElementById('conversation');

  it('leaves the transcript displayed and makes it inert, and gives it back on close', () => {
    openDashboard(win);
    expect(conversation().hasAttribute('hidden')).toBe(false);
    expect(conversation().hasAttribute('inert')).toBe(true);

    win.cc.closeDashboard();
    expect(conversation().hasAttribute('hidden')).toBe(false);
    expect(conversation().hasAttribute('inert')).toBe(false);
  });

  it('sits in the chat area — beside #conversation, and outside the dock and the tab bar', () => {
    const panel = openDashboard(win);
    expect(panel.parentElement.id).toBe('tabview');
    expect(panel.previousElementSibling.id).toBe('conversation');
    expect(win.document.getElementById('dock').contains(panel)).toBe(false);
    const work = win.document.getElementById('work');
    const view = win.document.getElementById('tabview');
    expect(work.contains(view)).toBe(true);
    expect(view.contains(win.document.getElementById('dock'))).toBe(false);
    expect(work.contains(win.document.getElementById('tabsbar'))).toBe(false);
    expect(work.contains(win.document.getElementById('dock'))).toBe(true);
  });

  it('keeps the panel where the reader left it across a rebuild', () => {
    const panel = openDashboard(win);
    const grid = panel.querySelector('.dash-inner');
    expect(grid).toBeTruthy();

    let offset = 0;
    Object.defineProperty(panel, 'scrollTop', {
      configurable: true,
      get: () => offset,
      set: (v) => {
        offset = v;
      },
    });
    const removeChild = grid.removeChild.bind(grid);
    grid.removeChild = (node) => {
      const gone = removeChild(node);
      if (!grid.firstChild) offset = 0;
      return gone;
    };
    panel.scrollTop = 240;

    const before = grid.firstChild;
    win.cc.session({});
    expect(grid.firstChild).not.toBe(before);
    expect(panel.querySelector('.dash-inner')).toBe(grid);
    expect(panel.scrollTop).toBe(240);
  });
});

describe('dashboard — the CSS that keeps the layer contained', () => {
  const css = readCss();

  function ruleBody(selector) {
    const start = css.indexOf(selector + ' {');
    if (start < 0) throw new Error('no rule for ' + selector);
    const open = css.indexOf('{', start);
    return css.slice(open + 1, css.indexOf('}', open));
  }

  it('stacks the panel and the transcript in one grid cell, with the dock in a row of its own', () => {
    expect(ruleBody('#work')).toMatch(/display:\s*grid/);
    expect(ruleBody('#work')).toMatch(/grid-template-rows:\s*minmax\(0, 1fr\) auto/);
    expect(ruleBody('#conversation')).toMatch(/grid-area:\s*1 \/ 1/);
    expect(ruleBody('.dashboard')).toMatch(/grid-area:\s*1 \/ 1/);
    expect(ruleBody('.dashboard')).toMatch(/z-index:\s*\d/);
    expect(ruleBody('#dock')).toMatch(/grid-area:\s*2 \/ 1/);
  });

  it('paints the panel opaque, because what it covers is still there', () => {
    expect(ruleBody('.dashboard')).toMatch(/background:\s*var\(--bg\)\s*;/);
    expect(ruleBody('.dashboard')).not.toMatch(/transparent/);
  });

  it('caps the conversation column nowhere: one declaration, and every consumer reads it', () => {
    expect(css).toMatch(/--col:\s*none\s*;/);
    expect(css).not.toMatch(/--col:\s*\d/);
    const uses = css.match(/var\(--col\)/g) || [];
    const caps = css.match(/max-width:\s*var\(--col\)/g) || [];
    expect(uses.length).toBe(caps.length);
  });
});
