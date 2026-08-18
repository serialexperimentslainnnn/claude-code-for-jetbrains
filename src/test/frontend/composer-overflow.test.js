// The ⋮ at the end of the two control rows (app-composer-base.js, wired by app-composer-actions.js and
// app-composer.js).
//
// A tool window is often three hundred pixels wide, and `Opus 5 with 1M context` next to `Bypass permissions`
// fills a row on its own — so the tail of each row used to sit outside the window, unreachable. What is pinned
// here is not the arithmetic for its own sake but the four things that make this feature either work or lie:
//
//   1. MEASURING IS NOT DECIDING. `CX.overflowFit` is arithmetic over numbers and is tested as such;
//      `CX.overflowMeasure` is the only part that reads layout, and it is substituted below. jsdom lays
//      nothing out — every rect is zero and `scrollWidth` never exceeds `clientWidth` — so a decision welded
//      to the DOM read is a decision no test can reach, and this one decides what the user can see.
//
//   2. THE ⋮ PAYS FOR ITSELF. Deciding what fits without counting the button that holds the rest is how a row
//      keeps one item too many and hangs the ⋮ off its own edge.
//
//   3. WHAT IS COLLECTED IS GONE FOR THE KEYBOARD. A control that is not on screen may not be in the tab
//      order. `display: none` is invisible to jsdom, so the DOM half is asserted on the class and the visual
//      half in the stylesheet — the same split `settings-menu.test.js` uses for a folded group.
//
//   4. THE SEND BUTTON IS NOT A CANDIDATE, IN EITHER SENSE. It is never collected — it is the screen's
//      primary action — and it never narrows to make room: in a flex row the negative space is shared out by
//      base × shrink factor, so a send button without `flex: 0 0 auto` gives up its own width before anything
//      is collected, and the row looks broken before it starts working. The first half is behaviour and is
//      driven here; the second is a declaration and is asserted in the stylesheet.
const { loadFrontend, readCss } = require('./helpers/load');

// Every collectible control is ITEM wide, the reserved tail is RESERVED, the ⋮ is TOGGLE — round numbers, so
// the expected cut is arithmetic anyone can redo in their head rather than a fixture to trust.
const ITEM = 100;
const RESERVED = 40;
const TOGGLE = 30;

/**
 * The composer state, with every pill labelled and offering options.
 *
 * A pill with no label is hidden by `renderPills` and is therefore not a candidate at all, and one with no
 * options is disabled — both are real states, and neither is the one this file is about.
 */
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

/**
 * The substituted measurement layer, and the whole reason the helper has one.
 *
 * It reports the same geometry the browser would for a row of fixed-width controls: `ends[i]` is the right
 * edge of item i (gaps included, because it is a position and not a width), the tail is what sits after the
 * run, and `overflowing` is the browser's own answer — computed here the same way, from whether the run plus
 * its tail is wider than the row. `avail` is mutable so a test can narrow a row and force one pass.
 */
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
    // Not derived from the numbers: with free space the right-hand group is pushed flush against the end by
    // an auto margin, which inflates the last end to about the full width. Whether a row overflows is a fact
    // the browser already knows, and the fixture reports it.
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
    // The arithmetic, in full, because `ends` are right edges and not widths: 440 available minus the 40 of
    // the reserved tail leaves 400, and the fourth item ends at exactly 400 — so four fit, and the fifth
    // never does. Charge the row for the ⋮ as well (440 − 40 − 30 = 370) and the fourth stops fitting too.
    // One item is the whole difference between a working row and a button hanging off its edge.
    //
    // FIVE items, and the fifth is load-bearing rather than padding. With only four the run would end at 400
    // and the row would fit entirely once the ⋮ went — which is a different rule (the last case in this
    // block), it answers before this one, and it would return 4 in both columns. That is exactly how this
    // assertion was first written, and it proved nothing.
    const row = { available: 440, ends: [100, 200, 300, 400, 500], reserved: 40, overflowing: true };
    expect(fit(Object.assign({ toggle: 0 }, row)).visible).toBe(4);
    expect(fit(Object.assign({ toggle: TOGGLE }, row)).visible).toBe(3);
  });

  it('spends the reserved tail before offering anything space', () => {
    // 260 available less the 30 of the ⋮ leaves 230, so two items fit (the second ends at 200). Reserve the
    // send button's 40 on top and the budget is 190: the second no longer fits, and the tail is the only
    // thing that changed. That subtraction IS the promise that the send button never gives up its width.
    //
    // Three items for the same reason as above: the run has to overflow the row even with no ⋮ in it, or the
    // answer becomes "drop the ⋮ and everything fits" and neither column moves.
    const row = { available: 260, ends: [100, 200, 300], toggle: TOGGLE, overflowing: true };
    expect(fit(Object.assign({ reserved: 0 }, row)).visible).toBe(2);
    expect(fit(Object.assign({ reserved: RESERVED }, row)).visible).toBe(1);
  });

  it('narrowed past the impossible, it collects everything and still shows the ⋮', () => {
    // The reserved tail is what survives: the send button keeps its whole width and everything else goes.
    expect(fit({ available: 60, ends: [100, 200], reserved: 40, toggle: 30, overflowing: true })).toEqual({
      visible: 0,
      toggle: true,
    });
  });

  it('lets the row out of overflow when the ⋮ is the only thing that no longer fits', () => {
    // Measured WITH the button in the row (`toggle > 0`). Without this branch the ⋮ is its own reason to
    // exist: the row overflows because of the width it occupies, so it stays, so the row overflows.
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

  // clip · provider · model · mode · effort · thinking · auto-follow · Vibe Mode — in row order, which is the
  // order they are collected from the end of, and the order the menu lists them in.
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
    // 400 − 40 (send) − 30 (⋮) = 330, so three items stay: the clip, Provider and Model.
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
    // `.cc-collapsed` is `display: none`, which is what removes a control from the tab order and from the
    // accessibility tree. jsdom applies no stylesheet, so the rule itself is asserted below — the two halves
    // together are the claim; either one alone is a control nobody can see but Tab still stops on.
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
    // Row order, not menu order: a menu that reorders makes the reader search for what was on screen a moment
    // ago. And a row reading only "High" is an entry nobody can act on, which is why the pill carries its own
    // name into the menu.
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
    // One handler, on the button: the entry presses it rather than repeating what it does.
    expect(sent).toContainEqual({ type: 'changeVibe', on: true });
    expect(overflowMenu()).toBeNull();
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
  });

  it('opens a collected pill’s own menu against the ⋮, which is where the press happened', () => {
    // A pill anchors its popup to ITSELF, and a collected pill is `display: none` — it has no position, so
    // its menu would open in the corner of the page.
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
    // Exactly one entry is in the tab order at a time, so Tab enters the menu once and then leaves it.
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
    // Focus goes back where it was taken from (WCAG 2.4.3), not to the <body> the removed rows leave behind.
    expect(win.document.activeElement).toBe(btn);
  });

  it('an identical push measures nothing and rebuilds nothing', () => {
    narrowTo(400);
    const btn = toggle();
    const before = calls.length;
    win.cc.state(state());
    // The signature covers what is drawn — labels and enablement — so a push that changed neither cannot
    // change the answer, and the row is not read at all. The ⋮ is the same node: a fresh one would have taken
    // the focus of anyone standing on it, and torn the anchor out from under an open menu.
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
    // The width at which the button becomes its own reason to exist, and the only one where it can. Eight
    // controls end at 800 and the send button reserves 40, so at 850 the row fits — but not with a 30-wide ⋮
    // in it (870), so it still reports overflow, and the naive answer keeps the button, which keeps the
    // overflow, which keeps the button. The row has to come out of it: nothing collected, no ⋮.
    //
    // Widening to 5000 (above) does NOT reach this: there the row simply stops overflowing and the answer
    // comes from the other branch. This is the only case that reaches this one through the DOM.
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
    // The dashboard's family too: it fills `#views` with its own `.dash-toggles` stack on its own schedule,
    // and that wrapper is exactly the thing the row must look THROUGH — collecting it would take all five
    // views behind one press at once.
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
    // Collected from the end, so the action icons go before the views, and the ⚙ at the head of the row is
    // the last thing standing. What must never happen is the whole stack disappearing as one item.
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
    // Nine controls at a hundred each and a thirty-wide ⋮: at 500 the four at the head survive — the ⚙ and
    // the first views — and the five action icons at the other end are what goes.
    narrowTo(500);
    const logout = win.document.querySelector('[aria-label="Log out of Claude"]');
    const chat = win.document.querySelector('.dash-toggle.dash-exit');
    expect(logout.classList.contains('cc-collapsed')).toBe(true);
    expect(chat.classList.contains('cc-collapsed')).toBe(false);
  });

  /** The rows of the open ⋮ menu, with the row narrow enough that every action icon is behind it. */
  function openMenuRows() {
    narrowTo(200);
    toggle().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return Array.prototype.slice.call(menu().querySelectorAll('[role="menuitem"]'));
  }

  /**
   * An entry, found by the control's accessible name.
   *
   * Read off the ENTRY's own text rather than matched against a list built here, because the entry does not
   * invent a name: it reads the control's. That indirection is the property worth keeping — a menu that
   * composed its own labels could drift from the row, and the same control would then answer two different
   * descriptions depending on how wide the tool window happened to be.
   */
  const byLabel = (rows, name) => rows.filter((r) => r.textContent === name)[0];

  it('an entry presses the real button, because it IS the real button', () => {
    const rows = openMenuRows();
    byLabel(rows, 'Log out of Claude').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent).toContainEqual({ type: 'logout' });

    byLabel(rows, 'Close this chat').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(sent).toContainEqual({ type: 'closeThisChat' });
  });

  it('carries every control of the row, named as the row names it', () => {
    // One open, one set of rows: the ⋮ TOGGLES, so pressing it again to look a second time would shut the
    // menu and leave nothing to read.
    //
    // Nothing in this row can refuse — all five act on the chat the page belongs to, so each is available
    // whenever there is a chat at all. If one ever can, its entry has to carry the refusal AND its reason:
    // a control that goes grey without saying why is a defect this row already shipped once, and behind the
    // ⋮ it would be a defect only the narrow-window user ever sees.
    const rows = openMenuRows();
    ['New chat', 'Browse slash commands', 'Git', 'Close this chat', 'Log out of Claude'].forEach((name) => {
      expect(byLabel(rows, name), name).toBeTruthy();
    });
  });
});

/**
 * The half of this feature that lives in the stylesheet, and without which the JS is measuring a row that
 * cannot overflow and hiding controls that stay visible.
 */
describe('the stylesheet holds up its end', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
  const rule = (selector) => new RegExp(selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[^{]*\\{[^}]*');

  it('both rows are one line that clips, because a wrapping row never overflows', () => {
    // The old behaviour: `flex-wrap: wrap`, so the tail dropped to a second line instead of being collected.
    // `scrollWidth > clientWidth` — the browser's own answer to "does this fit" — is only true of a row that
    // is `nowrap` AND clips, so both declarations are load-bearing rather than cosmetic.
    ['.composer-controls', '.composer-bar'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'flex-wrap:\\s*nowrap'));
      expect(css).toMatch(new RegExp(rule(sel).source + 'overflow:\\s*hidden'));
    });
  });

  it('a collected control is gone, not faded', () => {
    expect(css).toMatch(new RegExp(rule('.cc-collapsed').source + 'display:\\s*none\\s*!important'));
  });

  it('the send button neither shrinks nor is asked to', () => {
    // `flex: 0 0 auto` is the second half of "the send button is reserved": without it the negative space is
    // shared out by base × shrink factor and the button narrows before anything is ever collected.
    expect(css).toMatch(new RegExp(rule('.send-btn').source + 'flex:\\s*0 0 auto'));
    expect(css).toMatch(new RegExp(rule('.overflow-btn').source));
  });

  it('the groups inside a row do not shrink either, or the measurement is of overlapping controls', () => {
    // A group that shrinks lets its own children spill over the group beside it, so their positions stop
    // being a sequence and the "what fits" arithmetic is reading a layout that does not exist. The right-hand
    // group is held at the end by an auto margin instead, which collapses to nothing the moment the row is
    // full — which is exactly when the measurement has to be exact.
    ['.composer-views', '.bar-left', '.dash-toggles'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'flex:\\s*0 0 auto'));
    });
    ['.composer-actions', '.bar-right'].forEach((sel) => {
      expect(css).toMatch(new RegExp(rule(sel).source + 'margin-left:\\s*auto'));
    });
  });
});
