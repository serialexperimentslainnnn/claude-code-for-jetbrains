// Session dashboard (app-session.js). Drives the public cc.session / cc.mcp surface and asserts the real DOM,
// covering the layout bugs fixed in 4.2.0: the MCP actions row, the enable/disable switch, and `wide` cards.
const { loadFrontend, readCss } = require('./helpers/load');

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

describe('dashboard — a layer over the transcript, not a swap for it', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-session.js']);
    win.cc.session({});
  });

  const conversation = () => win.document.getElementById('conversation');

  it('leaves the transcript displayed and makes it inert, and gives it back on close', () => {
    // Taking the transcript out of display is what lost the reader's place: a scroll container that is not
    // displayed loses its scrollTop, so returning from the dashboard landed at the top of the conversation.
    // Covered instead of hidden, it keeps its box and its offset — and `inert` is the other half, because a
    // transcript still painted underneath would keep its links focusable while invisible (WCAG 2.2 SC
    // 2.4.11) and would still be read out over the panel.
    openDashboard(win);
    expect(conversation().hasAttribute('hidden')).toBe(false);
    expect(conversation().hasAttribute('inert')).toBe(true);

    win.cc.closeDashboard();
    expect(conversation().hasAttribute('hidden')).toBe(false);
    expect(conversation().hasAttribute('inert')).toBe(false);
  });

  it('sits in the conversation area — beside #conversation, and outside the dock and the tab bar', () => {
    const panel = openDashboard(win);
    expect(panel.parentElement.id).toBe('work');
    expect(panel.previousElementSibling.id).toBe('conversation');
    expect(win.document.getElementById('dock').contains(panel)).toBe(false);
    // The tab bar is not even inside the work area, so nothing placed in that area can reach it.
    const work = win.document.getElementById('work');
    expect(work.contains(win.document.getElementById('tabsbar'))).toBe(false);
    expect(work.contains(win.document.getElementById('dock'))).toBe(true);
  });

  it('keeps the panel where the reader left it across a rebuild', () => {
    const panel = openDashboard(win);
    // jsdom has no layout, so `scrollTop` there is an inert property: it survives having its content emptied,
    // which is the one thing the browser does NOT do — a scroll container with nothing left in it has nowhere
    // to be, and the offset is gone. An accessor that keeps the value would therefore pass with the restore
    // deleted (it did), so the loss is modelled: emptying the panel drops the offset, exactly as it does on
    // screen. What is asserted is that the code read the offset before the rebuild and wrote it back after.
    let offset = 0;
    Object.defineProperty(panel, 'scrollTop', {
      configurable: true,
      get: () => offset,
      set: (v) => {
        offset = v;
      },
    });
    const removeChild = panel.removeChild.bind(panel);
    panel.removeChild = (node) => {
      const gone = removeChild(node);
      if (!panel.firstChild) offset = 0;
      return gone;
    };
    panel.scrollTop = 240;

    const before = panel.firstChild;
    win.cc.session({}); // the host pushes several of these a turn, and each one rebuilds the panel
    expect(panel.firstChild).not.toBe(before);
    expect(panel.scrollTop).toBe(240);
  });
});

describe('dashboard — the CSS that keeps the layer contained', () => {
  // The stylesheet as the page sees it: the parts of css/, in the cascade order the host declares.
  const css = readCss();

  /** The body of one top-level rule. Exact selector plus ` {`, so `.dashboard` is not `.dashboard > *`. */
  function ruleBody(selector) {
    const start = css.indexOf(selector + ' {');
    if (start < 0) throw new Error('no rule for ' + selector);
    const open = css.indexOf('{', start);
    return css.slice(open + 1, css.indexOf('}', open));
  }

  it('stacks the panel and the transcript in one grid cell, with the dock in a row of its own', () => {
    expect(ruleBody('#work')).toMatch(/display:\s*grid/);
    expect(ruleBody('#work')).toMatch(/grid-template-rows:\s*minmax\(0, 1fr\) auto/);
    // Same cell: the panel covers the transcript instead of replacing it…
    expect(ruleBody('#conversation')).toMatch(/grid-area:\s*1 \/ 1/);
    expect(ruleBody('.dashboard')).toMatch(/grid-area:\s*1 \/ 1/);
    expect(ruleBody('.dashboard')).toMatch(/z-index:\s*\d/);
    // …and the dock is a row the panel's cell does not reach, so the composer cannot be obscured.
    expect(ruleBody('#dock')).toMatch(/grid-area:\s*2 \/ 1/);
  });

  it('paints the panel opaque, because what it covers is still there', () => {
    expect(ruleBody('.dashboard')).toMatch(/background:\s*var\(--bg\)\s*;/);
    expect(ruleBody('.dashboard')).not.toMatch(/transparent/);
  });

  it('caps the conversation column nowhere: one declaration, and every consumer reads it', () => {
    expect(css).toMatch(/--col:\s*none\s*;/);
    expect(css).not.toMatch(/--col:\s*\d/);
    // Every use is a `max-width`, which is what makes the declaration the only place the width is decided.
    const uses = css.match(/var\(--col\)/g) || [];
    const caps = css.match(/max-width:\s*var\(--col\)/g) || [];
    expect(uses.length).toBe(caps.length);
  });
});
