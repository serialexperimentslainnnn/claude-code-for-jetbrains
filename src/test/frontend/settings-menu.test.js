// The ⚙ settings menu at the head of the controls row.
//
// Three things here are not style preferences and are pinned as contracts:
//
//   1. WHERE THE GEAR IS. `#views` has three writers — this module, `app-session.js` (which appends its
//      `.dash-toggles` stack from `mountToggles()`) and `app-composer-actions.js` (which builds the row) —
//      and any of them can run first. "First element of the row" therefore has to hold whichever order the
//      page happens to load in, so both orders are driven here. It is a DOM position and never a CSS
//      `order:`, because a visual order that disagrees with the DOM is a focus order that disagrees with
//      the screen (WCAG 2.2 SC 2.4.3).
//
//   2. THAT THE SWITCHES ARE REAL CONTROLS. `role="menuitemcheckbox"` + `aria-checked`, not a div with a
//      colour. jsdom lays nothing out, so the visual half of that is asserted in the stylesheet (the ✓ on
//      `.menu-item.selected`) and the programmatic half on the live DOM — WCAG 1.4.1 and 4.1.2.
//
//   3. THAT AN IDENTICAL PUSH IS A NO-OP. The host re-pushes on its own schedule and a rebuild destroys the
//      focused row: the DOM has no move, so a fresh element is a new node and whatever was focused inside
//      the old one is blurred. This is the same discipline `tabs.test.js` pins for the tab row, driven the
//      same way — by node identity, which is the only thing that can tell a repaint from a skip.
const { loadFrontend, readCss } = require('./helpers/load');

// A FACTORY, not a shared constant. Toggling a switch writes the new value back into the stashed payload —
// that stash is what the next render reads, so it has to be the live truth — which means a module-level
// literal would carry one test's clicks into the next one's expectations.
const payload = () => ({
  items: [
    { key: 'autoAcceptEdits', group: 'Chat', label: 'Auto-accept edits', on: false },
    { key: 'thinking', group: 'Chat', label: 'Extended thinking', on: true },
    { key: 'blockCredentials', group: 'Security', label: 'Block credential files', on: true },
  ],
});

describe('the ⚙ button', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const views = () => win.document.getElementById('views');
  const gear = () => views().querySelector('.settings-btn');

  it('is the FIRST element of the views row, before Chat', () => {
    expect(gear()).toBeTruthy();
    expect(views().firstElementChild).toBe(gear());
  });

  it('is a real button with a name, and says it owns a menu', () => {
    // An icon with no text has no accessible name of its own (WCAG 4.1.2), and `title` alone is not one.
    expect(gear().tagName).toBe('BUTTON');
    expect(gear().type).toBe('button');
    expect(gear().getAttribute('aria-label')).toBe('Chat settings');
    expect(gear().getAttribute('aria-haspopup')).toBe('menu');
    expect(gear().getAttribute('aria-expanded')).toBe('false');
  });

  it('stays first however the row is filled — the mount is idempotent and order-blind', () => {
    // The late case: something else got into the row before us. Re-mounting must move the gear in front of
    // it, not append a second one and not re-insert the node it already owns.
    const stack = win.document.createElement('div');
    views().insertBefore(stack, views().firstChild);
    win.CC.composer.mountSettingsButton();
    expect(views().firstElementChild).toBe(gear());
    expect(views().querySelectorAll('.settings-btn').length).toBe(1);

    // The early case, and the one that matters: an unguarded insert of a node already in place is a remove
    // plus an insert, which blurs whatever is focused inside it.
    const before = gear();
    gear().focus();
    win.CC.composer.mountSettingsButton();
    expect(gear()).toBe(before);
    expect(win.document.activeElement).toBe(before);
  });
});

describe('the ⚙ button vs. the dashboard’s own view stack', () => {
  it('keeps the head of the row when app-session.js mounts its stack afterwards', () => {
    // The real load order (`JcefHost.appNames` puts the composer family before the dashboard), driven through
    // the dashboard's own entry point rather than by calling `mountToggles` directly.
    const win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.session({});
    const row = win.document.getElementById('views');
    expect(row.querySelector('.dash-toggles')).toBeTruthy();
    expect(row.firstElementChild).toBe(row.querySelector('.settings-btn'));
    // …and the gear is genuinely ahead of the Chat button, not merely present.
    const chat = row.querySelector('.dash-exit');
    expect(row.firstElementChild.compareDocumentPosition(chat) & 4 /* FOLLOWING */).toBeTruthy();
  });
});

describe('opening and closing', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    win.CC.send = () => {};
  });

  const gear = () => win.document.querySelector('.settings-btn');
  const menu = () => win.document.querySelector('.settings-menu');
  const entries = () => Array.from(menu().querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"]'));
  const key = (target, k) =>
    target.dispatchEvent(new win.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));

  it('opens on click, declares itself a menu, and flips aria-expanded', () => {
    expect(menu()).toBeNull();
    gear().click();
    expect(menu()).toBeTruthy();
    expect(menu().getAttribute('role')).toBe('menu');
    expect(menu().getAttribute('aria-label')).toBe('Chat settings');
    expect(gear().getAttribute('aria-expanded')).toBe('true');
  });

  it('mounts on document.body so it escapes the work area it hangs off', () => {
    // `#work` is a stacking context with declared ranks and the dock clips its own overflow, so a popup
    // anchored at the bottom of the page and opening upwards cannot live inside it — the same reason the
    // pill and attach menus mount here. It is placed against its own anchor and capped by `.menu`'s
    // max-height, which is what keeps it off the tab row (WCAG 2.2 SC 2.4.11).
    gear().click();
    expect(menu().parentNode).toBe(win.document.body);
    expect(win.document.getElementById('work').contains(menu())).toBe(false);
  });

  it('a second click closes it', () => {
    gear().click();
    gear().click();
    expect(menu()).toBeNull();
    expect(gear().getAttribute('aria-expanded')).toBe('false');
  });

  it('Escape closes it and hands the focus back to the ⚙', () => {
    gear().click();
    const first = entries()[0];
    expect(win.document.activeElement).toBe(first);
    key(first, 'Escape');
    expect(menu()).toBeNull();
    expect(win.document.activeElement).toBe(gear());
  });

  it('a press anywhere outside closes it', () => {
    gear().click();
    win.document.body.dispatchEvent(new win.MouseEvent('mousedown', { bubbles: true }));
    expect(menu()).toBeNull();
    expect(gear().getAttribute('aria-expanded')).toBe('false');
  });

  it('arrows and Home/End move the focus, because role="menu" promises they do', () => {
    // ARIA that announces a widget it does not behave like is worse than no ARIA: declaring `role="menu"`
    // is what makes this keyboard model a requirement rather than a nicety.
    win.cc.settingsMenu(payload());
    gear().click();
    const rows = entries();
    expect(win.document.activeElement).toBe(rows[0]);
    key(rows[0], 'ArrowDown');
    expect(win.document.activeElement).toBe(rows[1]);
    key(rows[1], 'ArrowUp');
    expect(win.document.activeElement).toBe(rows[0]);
    key(rows[0], 'End');
    expect(win.document.activeElement).toBe(rows[rows.length - 1]);
    key(rows[rows.length - 1], 'Home');
    expect(win.document.activeElement).toBe(rows[0]);
    // Roving tabindex: exactly one entry is in the tab order at a time.
    expect(entries().filter((r) => r.getAttribute('tabindex') === '0').length).toBe(1);
  });
});

describe('what the menu contains', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    win.CC.send = () => {};
  });

  const gear = () => win.document.querySelector('.settings-btn');
  const menu = () => win.document.querySelector('.settings-menu');
  const checkboxes = () => Array.from(menu().querySelectorAll('[role="menuitemcheckbox"]'));
  const lastEntry = () => menu().lastElementChild;

  it('explains itself when the host has pushed nothing — never an empty popup', () => {
    gear().click();
    const empty = menu().querySelector('.settings-empty');
    expect(empty).toBeTruthy();
    expect(empty.textContent).toContain('No quick settings yet');
    // Still an ENTRY, so menu navigation reaches it — loose text inside a menu is text a screen-reader
    // user never arrives at.
    expect(empty.getAttribute('role')).toBe('menuitem');
    expect(empty.getAttribute('aria-disabled')).toBe('true');
    // And the door to the full page is there regardless: a menu with nothing to do in it is still useful.
    expect(lastEntry().textContent).toBe('Open Plugin Settings');
  });

  it('draws one group per group the host names, in the host’s own order', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    const groups = Array.from(menu().querySelectorAll('[role="group"]'));
    expect(groups.map((g) => g.getAttribute('aria-label'))).toEqual(['Chat', 'Security']);
    // The visible heading is aria-hidden: the group already carries the name, and announcing it twice is
    // how a two-item group reads as four things.
    expect(groups[0].querySelector('.settings-group').textContent).toBe('Chat');
    expect(groups[0].querySelector('.settings-group').getAttribute('aria-hidden')).toBe('true');
    expect(groups[0].querySelectorAll('[role="menuitemcheckbox"]').length).toBe(2);
    expect(groups[1].querySelectorAll('[role="menuitemcheckbox"]').length).toBe(1);
  });

  it('each switch carries its state programmatically, not only in a colour', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    const rows = checkboxes();
    expect(rows.map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'true', 'true']);
    expect(rows.map((r) => r.classList.contains('selected'))).toEqual([false, true, true]);
    expect(rows.map((r) => r.textContent)).toEqual([
      'Auto-accept edits',
      'Extended thinking',
      'Block credential files',
    ]);
    // A real control, so it picks up keyboard activation and the shared :focus-visible ring for free.
    rows.forEach((r) => expect(r.tagName).toBe('BUTTON'));
  });

  it('the visible "on" mark is a glyph, not a hue — WCAG 1.4.1', () => {
    // jsdom paints nothing, so the non-colour half of the state lives in the stylesheet and is read there.
    // `.menu-item.selected::after` is the ✓ the pill menus already use; the accent colour is the second,
    // redundant signal and must never be the only one.
    expect(readCss()).toMatch(/\.menu-item\.selected::after\s*\{[^}]*content:\s*'✓'/);
  });

  it('a separator with a real role precedes the way out to the full settings', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    const out = lastEntry();
    expect(out.getAttribute('role')).toBe('menuitem');
    expect(out.textContent).toBe('Open Plugin Settings');
    expect(out.previousElementSibling.getAttribute('role')).toBe('separator');
  });
});

describe('what a press sends', () => {
  let win, sent;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.settingsMenu(payload());
    win.document.querySelector('.settings-btn').click();
  });

  const menu = () => win.document.querySelector('.settings-menu');
  const checkboxes = () => Array.from(menu().querySelectorAll('[role="menuitemcheckbox"]'));

  it('toggling sends the key and the NEW value, and flips the row at once', () => {
    const row = checkboxes()[0];
    row.click();
    expect(sent).toEqual([{ type: 'settingsToggle', key: 'autoAcceptEdits', on: true }]);
    // Optimistic on purpose: a switch that does nothing until a round trip completes reads as broken, and
    // the host's next push is authoritative either way.
    expect(row.getAttribute('aria-checked')).toBe('true');
    expect(row.classList.contains('selected')).toBe(true);
  });

  it('toggling an ON setting sends off', () => {
    checkboxes()[1].click();
    expect(sent).toEqual([{ type: 'settingsToggle', key: 'thinking', on: false }]);
  });

  it('the menu STAYS open — these are independent switches, not one choice', () => {
    checkboxes()[0].click();
    expect(menu()).toBeTruthy();
    checkboxes()[2].click();
    expect(sent.length).toBe(2);
  });

  it('announces the change, because nothing else does', () => {
    // WCAG 4.1.3: the flip moves no focus and paints a glyph, so without a live-region write it is silent.
    checkboxes()[0].click();
    expect(win.document.getElementById('a11y-status').textContent).toBe('Auto-accept edits on');
    checkboxes()[0].click();
    expect(win.document.getElementById('a11y-status').textContent).toBe('Auto-accept edits off');
  });

  it('Open Plugin Settings asks the host and closes behind itself', () => {
    menu().lastElementChild.click();
    expect(sent).toEqual([{ type: 'openSettings' }]);
    expect(win.document.querySelector('.settings-menu')).toBeNull();
    // No focus return here, unlike Escape: this entry hands the user to another window, and dragging the
    // caret back to the gear first would take it away from wherever that window puts it.
    expect(win.document.querySelector('.settings-btn').getAttribute('aria-expanded')).toBe('false');
  });
});

describe('host pushes while the menu is open and while it is shut', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    win.CC.send = () => {};
  });

  const gear = () => win.document.querySelector('.settings-btn');
  const menu = () => win.document.querySelector('.settings-menu');
  const checkboxes = () => Array.from(menu().querySelectorAll('[role="menuitemcheckbox"]'));

  it('stashes a payload that arrives while shut, and draws it on the next open', () => {
    win.cc.settingsMenu(payload());
    expect(menu()).toBeNull(); // nothing drawn, nothing thrown
    gear().click();
    expect(checkboxes().length).toBe(3);
  });

  it('redraws an open menu when the structure changes', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    win.cc.settingsMenu({
      items: [{ key: 'thinking', group: 'Chat', label: 'Extended thinking', on: false }],
    });
    expect(checkboxes().length).toBe(1);
    expect(checkboxes()[0].getAttribute('aria-checked')).toBe('false');
  });

  it('an identical push does NOT rebuild the rows', () => {
    // The rule that costs a caret when it is broken: the DOM has no move, so rebuilding is a remove plus an
    // insert and the focused row is blurred by it. Node identity is the only way to tell a skip from a
    // repaint that happens to produce the same markup.
    win.cc.settingsMenu(payload());
    gear().click();
    const before = checkboxes();
    win.cc.settingsMenu(payload()); // a structurally identical payload, and a different object
    const after = checkboxes();
    expect(after[0]).toBe(before[0]);
    expect(after[2]).toBe(before[2]);
  });

  it('a state-only push updates the rows in place, keeping the focus where it was', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    const row = checkboxes()[1];
    row.focus();
    win.cc.settingsMenu({
      items: [
        { key: 'autoAcceptEdits', group: 'Chat', label: 'Auto-accept edits', on: true },
        { key: 'thinking', group: 'Chat', label: 'Extended thinking', on: false },
        { key: 'blockCredentials', group: 'Security', label: 'Block credential files', on: true },
      ],
    });
    expect(checkboxes()[1]).toBe(row);
    expect(win.document.activeElement).toBe(row);
    expect(checkboxes().map((r) => r.getAttribute('aria-checked'))).toEqual(['true', 'false', 'true']);
  });

  it('a rebuild puts the focus back on the same setting, not on the top of the list', () => {
    win.cc.settingsMenu(payload());
    gear().click();
    checkboxes()[2].focus();
    // A new entry appears: the structure really changed, so the rows are rebuilt.
    win.cc.settingsMenu({
      items: payload().items.concat([
        { key: 'sandbox', group: 'Security', label: 'Sandbox commands', on: false },
      ]),
    });
    expect(checkboxes().length).toBe(4);
    expect(win.document.activeElement.textContent).toBe('Block credential files');
  });

  it('survives a malformed payload instead of emptying the menu of its way out', () => {
    win.cc.settingsMenu({ items: [{ label: 'no key here' }, null] });
    gear().click();
    expect(menu().querySelectorAll('[role="menuitemcheckbox"]').length).toBe(0);
    expect(menu().lastElementChild.textContent).toBe('Open Plugin Settings');
  });
});

describe('the row’s CSS contract', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

  /** The body of one top-level rule — exact selector plus ` {`, so a prefix cannot match a longer one. */
  function ruleBody(selector) {
    const at = css.indexOf(selector + ' {');
    if (at < 0) throw new Error('no rule for ' + selector);
    return css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  }

  it('the gear’s place in the row is structural, never a CSS reorder', () => {
    // `order: -1` would put it on the left and leave it last in the tab sequence — WCAG 2.2 SC 2.4.3. The
    // position comes from `insertBefore`, so this rule must never grow one.
    expect(ruleBody('.composer-views .settings-btn')).not.toMatch(/\border\s*:/);
    expect(ruleBody('.composer-views .settings-btn')).not.toMatch(/flex-direction/);
  });

  it('the gear is flat and divided like the rest of the row, not a rounded action icon', () => {
    const rule = ruleBody('.composer-views .settings-btn');
    expect(rule).toMatch(/border-radius:\s*0/);
    // `.dash-toggle + .dash-toggle` cannot reach across the `.dash-toggles` wrapper, so the hairline between
    // the gear and Chat has to be declared on the gear itself.
    expect(rule).toMatch(/border-right:\s*1px solid var\(--border\)/);
  });

  it('the open state is drawn from aria-expanded, so there is only one copy of the fact', () => {
    expect(css).toMatch(/\.composer-views \.settings-btn\[aria-expanded='true'\]/);
  });

  it('the button rows do not out-order the .menu-item colour that marks a switch as on', () => {
    // `.settings-item` resets what a <button> brings (width, alignment, font family, border, background) and
    // must NOT declare `color` or `font-size`: same specificity and later in the cascade, either one would
    // beat `.menu-item` and flatten the checked accent.
    const rule = ruleBody('.settings-item');
    expect(rule).toMatch(/background:\s*transparent/);
    expect(rule).toMatch(/text-align:\s*left/);
    expect(rule).not.toMatch(/\bcolor\s*:/);
    expect(rule).not.toMatch(/font-size\s*:/);
  });
});
