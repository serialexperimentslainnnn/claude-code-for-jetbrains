const { loadFrontend, readCss } = require('./helpers/load');

const ITEM = 100;
const RESERVED = 40;
const TOGGLE = 30;

function state() {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [{ id: 'anthropic', label: 'Anthropic' }] },
    model: {
      label: 'Opus 5 with 1M context',
      options: [{ value: 'opus[1m]', label: 'Opus 5', selected: true }],
    },
    mode: {
      wire: 'bypassPermissions',
      label: 'Bypass permissions',
      options: [{ wire: 'default', label: 'Default' }],
    },
    effort: { label: 'High', options: [{ value: 'high', label: 'High', selected: true }] },
    thinking: { on: true, label: 'Thinking on', options: [{ on: false, label: 'Off' }] },
    queue: [],
  };
}

function measureWith(win, avail) {
  const calls = [];
  win.CC.composer.overflowMeasure = function (row, items, reserved, toggle) {
    const key = row.id === 'controls' ? 'controls' : 'bar';
    const ends = items.map((_, i) => (i + 1) * ITEM);
    const used = ends.length ? ends[ends.length - 1] : 0;
    const reservedWidth = reserved.length * RESERVED;
    const toggleWidth = toggle ? TOGGLE : 0;
    calls.push(key);
    return {
      available: avail[key],
      overflowing: used + reservedWidth + toggleWidth > avail[key],
      ends,
      reserved: reservedWidth,
      toggle: toggleWidth,
    };
  };
  return calls;
}

describe('what fits — the decision, with no DOM anywhere near it', () => {
  let fit;
  beforeEach(() => {
    fit = loadFrontend(['app-composer.js'], { vendor: false }).CC.composer.overflowFit;
  });

  it('collects nothing, and offers no ⋮, while the row is not overflowing', () => {
    expect(
      fit({ available: 1000, ends: [100, 200, 300], reserved: 40, toggle: 0, overflowing: false })
    ).toEqual({
      visible: 3,
      toggle: false,
    });
  });

  it('collects from the END, keeping the head of the row', () => {
    const plan = fit({
      available: 350,
      ends: [100, 200, 300, 400],
      reserved: 0,
      toggle: 30,
      overflowing: true,
    });
    expect(plan).toEqual({ visible: 3, toggle: true });
  });

  it('counts the ⋮ itself, so the button that holds the rest is not the thing hanging off the edge', () => {
    const row = { available: 440, ends: [100, 200, 300, 400, 500], reserved: 40, overflowing: true };
    expect(fit(Object.assign({ toggle: 0 }, row)).visible).toBe(4);
    expect(fit(Object.assign({ toggle: TOGGLE }, row)).visible).toBe(3);
  });

  it('spends the reserved tail before offering anything space', () => {
    const row = { available: 260, ends: [100, 200, 300], toggle: TOGGLE, overflowing: true };
    expect(fit(Object.assign({ reserved: 0 }, row)).visible).toBe(2);
    expect(fit(Object.assign({ reserved: RESERVED }, row)).visible).toBe(1);
  });

  it('narrowed past the impossible, it collects everything and still shows the ⋮', () => {
    expect(fit({ available: 60, ends: [100, 200], reserved: 40, toggle: 30, overflowing: true })).toEqual({
      visible: 0,
      toggle: true,
    });
  });

  it('lets the row out of overflow when the ⋮ is the only thing that no longer fits', () => {
    expect(fit({ available: 250, ends: [100, 200], reserved: 40, toggle: 30, overflowing: true })).toEqual({
      visible: 2,
      toggle: false,
    });
  });
});

describe('the composer bar', () => {
  let win;
  let sent;
  let avail;
  let calls;

  const ITEMS = 8;

  const toggle = () => win.document.querySelector('.composer-bar .overflow-btn');
  const overflowMenu = () => win.document.querySelector('.menu[role="menu"]');
  const labels = (root) =>
    Array.prototype.slice.call(root.querySelectorAll('.menu-item-label')).map((el) => el.textContent);
  const collapsed = () =>
    Array.prototype.slice.call(win.document.querySelectorAll('.composer-bar .cc-collapsed'));

  function narrowTo(width) {
    avail.bar = width;
    win.CC.composer.refreshOverflow(true);
  }

  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
    avail = { controls: 5000, bar: 5000 };
    calls = measureWith(win, avail);
    win.cc.state(state());
  });

  it('has no ⋮ at all while everything fits — not a disabled one, and not a gap held open for one', () => {
    expect(toggle()).toBeNull();
    expect(collapsed()).toEqual([]);
  });

  it('collects the tail of the row and puts the ⋮ at its end, in front of the send button', () => {
    narrowTo(400);
    const btn = toggle();
    expect(btn).not.toBeNull();
    expect(btn.nextElementSibling).toBe(win.document.querySelector('.send-btn'));
    expect(collapsed().length).toBe(ITEMS - 3);
  });

  it('is a real menu button and says so before it is pressed', () => {
    narrowTo(400);
    expect(toggle().tagName).toBe('BUTTON');
    expect(toggle().getAttribute('aria-label')).toBe('More composer controls');
    expect(toggle().getAttribute('aria-haspopup')).toBe('menu');
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
  });

  it('takes what it collected out of the keyboard, not merely out of the paint', () => {
    narrowTo(400);
    const hidden = collapsed();
    expect(hidden.length).toBeGreaterThan(0);
    hidden.forEach((el) => expect(el.classList.contains('cc-collapsed')).toBe(true));
    expect(readCss()).toMatch(/\.cc-collapsed[^{]*\{[^}]*display:\s*none\s*!important/);
  });

  it('never collects the send button, however impossible the width', () => {
    narrowTo(60);
    const send = win.document.querySelector('.send-btn');
    expect(send.classList.contains('cc-collapsed')).toBe(false);
    expect(send.parentNode).toBe(win.document.querySelector('.bar-right'));
    expect(collapsed().length).toBe(ITEMS);
    win.document
      .querySelector('.composer-bar .overflow-btn')
      .dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(labels(overflowMenu())).not.toContain('Send');
  });

  it('lists what it collected, in the order it was in, and says which setting each pill is', () => {
    narrowTo(400);
    toggle().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    const menu = overflowMenu();
    expect(menu).not.toBeNull();
    expect(menu.getAttribute('role')).toBe('menu');
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(labels(menu)).toEqual([
      'Mode: Bypass permissions',
      'Effort: High',
      'Thinking: Thinking on',
      'Auto-follow scrolling',
      'Vibe Mode',
    ]);
  });

  it('activating an entry does exactly what the control does — because it IS the control', () => {
    narrowTo(400);
    toggle().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    const vibe = Array.prototype.slice
      .call(overflowMenu().querySelectorAll('[role="menuitem"]'))
      .filter((el) => el.textContent === 'Vibe Mode')[0];
    vibe.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent).toContainEqual({ type: 'changeVibe', on: true });
    expect(overflowMenu()).toBeNull();
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
  });

  it('opens a collected pill’s own menu against the ⋮, which is where the press happened', () => {
    narrowTo(400);
    toggle().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    const mode = Array.prototype.slice
      .call(overflowMenu().querySelectorAll('[role="menuitem"]'))
      .filter((el) => el.textContent.indexOf('Mode:') === 0)[0];
    mode.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(overflowMenu()).toBeNull();
    const pillMenu = win.document.querySelector('.menu[role="listbox"]');
    expect(pillMenu).not.toBeNull();
    expect(win.CC.composer.openMenu.anchor).toBe(toggle());
  });

  it('answers the keyboard the way `role="menu"` promises', () => {
    narrowTo(400);
    const btn = toggle();
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    const menu = overflowMenu();
    const rows = Array.prototype.slice.call(menu.querySelectorAll('[role="menuitem"]'));
    expect(win.document.activeElement).toBe(rows[0]);
    expect(rows.filter((r) => r.getAttribute('tabindex') === '0').length).toBe(1);
    menu.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(win.document.activeElement).toBe(rows[1]);
    menu.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    expect(win.document.activeElement).toBe(rows[rows.length - 1]);
    menu.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    expect(win.document.activeElement).toBe(rows[0]);
    menu.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(overflowMenu()).toBeNull();
    expect(win.document.activeElement).toBe(btn);
  });

  it('an identical push measures nothing and rebuilds nothing', () => {
    narrowTo(400);
    const btn = toggle();
    const before = calls.length;
    win.cc.state(state());
    expect(calls.length).toBe(before);
    expect(toggle()).toBe(btn);
  });

  it('a push that changes a label is measured again — a label is a width', () => {
    narrowTo(400);
    const before = calls.length;
    const next = state();
    next.model.label = 'Sonnet';
    win.cc.state(next);
    expect(calls.length).toBeGreaterThan(before);
  });

  it('gives the row back when it is widened, and the ⋮ with it', () => {
    narrowTo(400);
    expect(toggle()).not.toBeNull();
    narrowTo(5000);
    expect(toggle()).toBeNull();
    expect(collapsed()).toEqual([]);
  });

  it('takes the ⋮ away when the ⋮ is the last thing that does not fit', () => {
    narrowTo(400);
    expect(toggle()).not.toBeNull();
    narrowTo(850);
    expect(toggle()).toBeNull();
    expect(collapsed()).toEqual([]);
  });
});

describe('the controls row', () => {
  let win;
  let sent;
  let avail;

  const actions = () => win.document.getElementById('actions');
  const toggle = () => win.document.querySelector('#controls .overflow-btn');
  const menu = () => win.document.querySelector('.menu[role="menu"]');

  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
    avail = { controls: 5000, bar: 5000 };
    measureWith(win, avail);
    win.cc.state(state());
  });

  function narrowTo(width) {
    avail.controls = width;
    win.CC.composer.refreshOverflow(true);
  }

  it('treats the view buttons as items, never the stack that holds them', () => {
    const views = win.document.querySelectorAll('.dash-toggles .dash-toggle');
    expect(views.length).toBeGreaterThan(1);
    narrowTo(200);
    const gone = win.document.querySelectorAll('#controls .cc-collapsed');
    expect(gone.length).toBeGreaterThan(0);
    expect(win.document.querySelector('.dash-toggles').classList.contains('cc-collapsed')).toBe(false);
  });

  it('puts its ⋮ at the end of the row, where the actions are', () => {
    narrowTo(200);
    expect(toggle()).not.toBeNull();
    expect(actions().lastElementChild).toBe(toggle());
    expect(toggle().getAttribute('aria-label')).toBe('More chat controls');
  });

  it('collects from the end, so the action icons go before the views', () => {
    narrowTo(500);
    const logout = win.document.querySelector('[aria-label="Log out of Claude"]');
    const chat = win.document.querySelector('.dash-toggle.dash-exit');
    expect(logout.classList.contains('cc-collapsed')).toBe(true);
    expect(chat.classList.contains('cc-collapsed')).toBe(false);
  });

  function openMenuRows() {
    narrowTo(200);
    toggle().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return Array.prototype.slice.call(menu().querySelectorAll('[role="menuitem"]'));
  }

  const byLabel = (rows, name) => rows.filter((r) => r.textContent === name)[0];

  it('an entry presses the real button, because it IS the real button', () => {
    const rows = openMenuRows();
    byLabel(rows, 'Log out of Claude').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent).toContainEqual({ type: 'logout' });

    byLabel(rows, 'Close this chat').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent).toContainEqual({ type: 'closeThisChat' });
  });

  it('carries every control of the row, named as the row names it', () => {
    const rows = openMenuRows();
    ['New chat', 'Browse slash commands', 'Git', 'Close this chat', 'Log out of Claude'].forEach((name) => {
      expect(byLabel(rows, name), name).toBeTruthy();
    });
  });
});

describe('the stylesheet holds up its end', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
  const rule = (selector) => new RegExp(selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[^{]*\\{[^}]*');

  it('both rows are one line that clips, because a wrapping row never overflows', () => {
    ['.composer-controls', '.composer-bar'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'flex-wrap:\\s*nowrap'));
      expect(css).toMatch(new RegExp(rule(sel).source + 'overflow:\\s*hidden'));
    });
  });

  it('a collected control is gone, not faded', () => {
    expect(css).toMatch(new RegExp(rule('.cc-collapsed').source + 'display:\\s*none\\s*!important'));
  });

  it('the send button neither shrinks nor is asked to', () => {
    expect(css).toMatch(new RegExp(rule('.send-btn').source + 'flex:\\s*0 0 auto'));
    expect(css).toMatch(new RegExp(rule('.overflow-btn').source));
  });

  it('the groups inside a row do not shrink either, or the measurement is of overlapping controls', () => {
    ['.composer-views', '.bar-left', '.dash-toggles'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'flex:\\s*0 0 auto'));
    });
    ['.composer-actions', '.bar-right'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'margin-left:\\s*auto'));
    });
  });
});
