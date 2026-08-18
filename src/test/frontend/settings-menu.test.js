// The ⚙ settings menu at the head of the controls row.
//
// Five things here are not style preferences and are pinned as contracts:
//
//   1. WHERE THE GEAR IS. `#views` has three writers — this module, `app-session.js` (which appends its
//      `.dash-toggles` stack from `mountToggles()`) and `app-composer-actions.js` (which builds the row) —
//      and any of them can run first. "First element of the row" therefore has to hold whichever order the
//      page happens to load in, so both orders are driven here. It is a DOM position and never a CSS
//      `order:`, because a visual order that disagrees with the DOM is a focus order that disagrees with
//      the screen (WCAG 2.2 SC 2.4.3).
//
//   2. THAT THE ROWS ARE REAL CONTROLS. `role="menuitemcheckbox"`/`role="menuitemradio"` + `aria-checked`,
//      and `role="menuitem"` + `aria-expanded` on a heading — not a div with a colour. jsdom lays nothing
//      out, so the visual half is asserted in the stylesheet (the ✓ on `.menu-item.selected`, the ● on a
//      chosen radio) and the programmatic half on the live DOM — WCAG 1.4.1 and 4.1.2.
//
//   3. THAT A FOLDED GROUP IS GONE FROM THE KEYBOARD, not merely from the paint. `display: none` is
//      invisible to jsdom and invisible to a roving tabindex, so a row that is only hidden by CSS is a row
//      the arrow keys still stop on and Tab can still reach — a menu that behaves nothing like it looks.
//
//   4. THAT ONE CHOICE SENDS ONE MESSAGE. A radio group is resolved by the HOST; the page unchecks the
//      siblings on screen and says nothing about them on the wire. An `on:false` per sibling would be the
//      page deciding what a group means, and several toggles for one gesture applied in arrival order.
//
//   5. THAT AN IDENTICAL PUSH IS A NO-OP. The host re-pushes on its own schedule and a rebuild destroys the
//      focused row: the DOM has no move, so a fresh element is a new node and whatever was focused inside
//      the old one is blurred. This is the same discipline `tabs.test.js` pins for the tab row, driven the
//      same way — by node identity, which is the only thing that can tell a repaint from a skip.
const { loadFrontend, readCss } = require('./helpers/load');

// A FACTORY, not a shared constant. Acting on a row writes the new value back into the stashed payload —
// that stash is what the next render reads, so it has to be the live truth — which means a module-level
// literal would carry one test's clicks into the next one's expectations.
//
// The shape mirrors the real menu: two radio groups that start unfolded (Model, Effort), a switch group that
// starts folded (Security), and a deferred group that starts folded (Setting sources). The Model keys carry
// the composite form the host actually sends, brackets included, because they are handed straight back.
const payload = () => ({
  items: [
    { key: 'model:opus[1m]', group: 'Model', label: 'Opus 5', on: true, type: 'radio' },
    { key: 'model:sonnet', group: 'Model', label: 'Sonnet', on: false, type: 'radio' },
    { key: 'effort:high', group: 'Effort', label: 'High', on: true, type: 'radio' },
    { key: 'effort:low', group: 'Effort', label: 'Low', on: false, type: 'radio' },
    { key: 'blockCredentials', group: 'Security', label: 'Block credential files', on: true },
    { key: 'sandbox', group: 'Security', label: 'Sandbox commands', on: false },
    { key: 'source:user', group: 'Setting sources', label: 'user', on: true, deferred: true },
    { key: 'source:project', group: 'Setting sources', label: 'project', on: false, deferred: true },
  ],
});

/** One item of a payload, with a field changed — used to push a structure that differs in exactly one way. */
function withField(match, extra) {
  return payload().items.map((it) => (match(it) ? Object.assign({}, it, extra) : it));
}

/**
 * The real menu's shape for the one group that goes two levels deep: the security rules, each carrying its
 * category as a `sub`. Kept apart from [payload] on purpose — every other group has no `sub` at all, and the
 * tests above are what pin that those still behave exactly as they did before a second level existed.
 */
const nestedPayload = () => ({
  items: [
    { key: 'model:opus[1m]', group: 'Model', label: 'Opus 5', on: true, type: 'radio' },
    {
      key: 'rule:CREDENTIALS',
      group: 'Security',
      sub: 'Sensitive data',
      label: 'Block credential files',
      on: true,
    },
    {
      key: 'rule:SECRET_DUMPING_COMMANDS',
      group: 'Security',
      sub: 'Sensitive data',
      label: 'Block dangerous commands',
      on: true,
    },
    {
      key: 'rule:TEMP_DIR',
      group: 'Security',
      sub: 'Filesystem boundary',
      label: 'Block the temp folder',
      on: false,
    },
    {
      key: 'rule:SHELL_FILE_WRITE',
      group: 'Security',
      sub: 'Filesystem boundary',
      label: 'Block shell writes',
      on: true,
    },
    { key: 'source:user', group: 'Setting sources', label: 'user', on: true, deferred: true },
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

  it('draws its icon inline, in the button’s own colour, with nothing fetched', () => {
    // The page is served under a hash-pinned CSP with no asset pipeline behind it: a `url()`, an `<image>` or
    // an `href` here is a resource that never arrives, and it fails silently. The glyph is decorative — the
    // name is on the button — so it is hidden rather than announced as a second thing.
    const svg = gear().querySelector('svg');
    expect(svg).toBeTruthy();
    expect(svg.getAttribute('fill')).toBe('currentColor');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(gear().innerHTML).not.toMatch(/url\(|<image|href/);
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

/**
 * Everything below drives the same shape: open the menu over the fixture payload and act on it. The helpers
 * are shared through a factory rather than copied, because a query that drifts between blocks is a query that
 * silently stops covering what its block claims to cover.
 */
function harness() {
  const win = loadFrontend(['app-composer.js'], { vendor: false });
  const sent = [];
  win.CC.send = (m) => sent.push(m);
  const q = {
    win,
    sent,
    /**
     * What the menu sent MINUS the refreshes.
     *
     * `settingsRefresh` rides on the same channel and is emitted by opening the popup and by stepping into a
     * section, so a bare `sent` mixes "the user changed something" with "the page asked whether anything had
     * changed elsewhere". The tests about what a press SENDS mean the first, and are read through this; the
     * refreshes have a test of their own, which is the only place they are asserted.
     */
    toggles: () => sent.filter((m) => m && m.type !== 'settingsRefresh'),
    gear: () => win.document.querySelector('.settings-btn'),
    menu: () => win.document.querySelector('.settings-menu'),
    /** Every entry of the panel on screen — which, since the drill-down, is every entry there is. */
    all: () =>
      Array.from(
        q.menu().querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
      ),
    /** A section's row on the ROOT panel, by the name it shows. */
    entry: (name) =>
      Array.from(q.menu().querySelectorAll('.settings-group-entry')).find(
        (el) => el.querySelector('.menu-item-label').textContent === name
      ),
    /** Go into a section, the way a user does. */
    enter: (name) => q.entry(name).click(),
    /** The back arrow of a section panel, absent on the root one. */
    back: () => q.menu().querySelector('.attach-back'),
    /**
     * The panel itself — the element replaced whole on every step, and therefore the one that animates.
     *
     * Tests ask it for "the last entry" rather than asking the popup: the popup's last child is the panel,
     * and a test reading `menu().lastElementChild` would be asserting about the wrapper instead of about the
     * row it means.
     */
    body: () => q.menu().querySelector('.settings-body'),
    /** The title of the section panel on screen, or null at the root. */
    title: () => {
      const el = q.menu().querySelector('.attach-title');
      return el ? el.textContent : null;
    },
    /** A setting row, by the label it shows. Only the section on screen has any. */
    row: (label) => q.all().find((el) => el.textContent === label),
    key: (k) =>
      win.document.activeElement.dispatchEvent(
        new win.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true })
      ),
    /** What the focused entry reads as — a heading's own note is part of it, deliberately. */
    focused: () => win.document.activeElement.textContent,
  };
  return q;
}

describe('opening and closing', () => {
  let q;
  beforeEach(() => {
    q = harness();
  });

  it('opens on click, declares itself a menu, and flips aria-expanded', () => {
    expect(q.menu()).toBeNull();
    q.gear().click();
    expect(q.menu()).toBeTruthy();
    expect(q.menu().getAttribute('role')).toBe('menu');
    expect(q.menu().getAttribute('aria-label')).toBe('Chat settings');
    expect(q.gear().getAttribute('aria-expanded')).toBe('true');
  });

  it('mounts on document.body so it escapes the work area it hangs off', () => {
    // `#work` is a stacking context with declared ranks and the dock clips its own overflow, so a popup
    // anchored at the bottom of the page and opening upwards cannot live inside it — the same reason the
    // pill and attach menus mount here. It is placed against its own anchor and capped by `.menu`'s
    // max-height, which is what keeps it off the tab row (WCAG 2.2 SC 2.4.11).
    q.gear().click();
    expect(q.menu().parentNode).toBe(q.win.document.body);
    expect(q.win.document.getElementById('work').contains(q.menu())).toBe(false);
  });

  it('a second click closes it', () => {
    q.gear().click();
    q.gear().click();
    expect(q.menu()).toBeNull();
    expect(q.gear().getAttribute('aria-expanded')).toBe('false');
  });

  it('Escape closes it and hands the focus back to the ⚙', () => {
    q.gear().click();
    expect(q.win.document.activeElement).toBe(q.all()[0]);
    q.key('Escape');
    expect(q.menu()).toBeNull();
    expect(q.win.document.activeElement).toBe(q.gear());
  });

  it('a press anywhere outside closes it', () => {
    q.gear().click();
    q.win.document.body.dispatchEvent(new q.win.MouseEvent('mousedown', { bubbles: true }));
    expect(q.menu()).toBeNull();
    expect(q.gear().getAttribute('aria-expanded')).toBe('false');
  });
});

describe('the sections', () => {
  let q;
  beforeEach(() => {
    q = harness();
    q.win.cc.settingsMenu(payload());
    q.gear().click();
  });

  it('opens on the list of sections, one per group the host names, in the host’s own order', () => {
    const entries = Array.from(q.menu().querySelectorAll('.settings-group-entry'));
    expect(entries.map((e) => e.querySelector('.menu-item-label').textContent)).toEqual([
      'Model',
      'Effort',
      'Security',
      'Setting sources',
    ]);
    // And ONLY the sections: no setting row is on the root panel, which is the whole difference from the
    // accordion this replaced — there, opening one group inserted its rows between the headings and pushed
    // every section below it down the screen.
    expect(q.row('Opus 5')).toBeUndefined();
    expect(q.title()).toBeNull();
    expect(q.back()).toBeNull();
  });

  it('a section entry is an entry that says it leads somewhere', () => {
    // `aria-haspopup` and NOT `aria-expanded`: the latter promises a region that opens in place, under the
    // heading, with the rest of the menu still around it — which is precisely what this stopped doing.
    const entry = q.entry('Security');
    expect(entry.tagName).toBe('BUTTON');
    expect(entry.getAttribute('role')).toBe('menuitem');
    expect(entry.getAttribute('aria-haspopup')).toBe('menu');
    expect(entry.getAttribute('aria-expanded')).toBeNull();
  });

  it('the section’s rows are in a container the stylesheet actually SHOWS', () => {
    // The defect this pins, and the reason it is asserted against CSS text: the panel first reused
    // `.menu-group-items`, the pill menus' accordion body, which is `display: none` unless a
    // `.menu-group.open` ancestor reveals it. This panel has no such ancestor, so every section opened to a
    // header with nothing under it — rows present, correct and invisible. jsdom lays nothing out, so every
    // DOM assertion in this file stayed green while the menu was unusable.
    q.enter('Security');
    const region = q.menu().querySelector('[role="group"]');
    expect(region.className).toBe('settings-section-items');
    expect(region.className).not.toContain('menu-group-items');
    // Its own rule, and one that shows it. The accordion's is the counter-example, quoted here so the two
    // cannot be confused again: `.menu-group-items` is hidden until a parent opts in.
    const sheet = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const body = (sel) => {
      const at = sheet.indexOf('\n' + sel + ' {');
      return at < 0 ? null : sheet.slice(sheet.indexOf('{', at) + 1, sheet.indexOf('}', at));
    };
    expect(body('.settings-section-items')).toMatch(/display:\s*block/);
    expect(body('.menu-group-items')).toMatch(/display:\s*none/);
  });

  it('pressing one REPLACES the panel with that section, behind a back arrow', () => {
    q.enter('Security');
    expect(q.title()).toBe('Security');
    expect(q.back()).toBeTruthy();
    expect(q.back().getAttribute('aria-label')).toBe('Back to all settings');
    // Its rows are there, in a region that carries the section's name…
    const region = q.menu().querySelector('[role="group"]');
    expect(region.getAttribute('aria-label')).toBe('Security');
    expect(region.contains(q.row('Sandbox commands'))).toBe(true);
    // …and nothing else is: not the other sections, and not the other sections' rows.
    expect(q.entry('Model')).toBeUndefined();
    expect(q.row('Opus 5')).toBeUndefined();
    // Nor the way out of the page, which inside a section would sit one row from the way back.
    expect(q.menu().textContent).not.toContain('Open Plugin Settings');
  });

  it('back returns to the list, on the entry you came through', () => {
    q.enter('Security');
    q.back().click();
    expect(q.title()).toBeNull();
    expect(q.entry('Model')).toBeTruthy();
    // Not the top of the list: the next section you want is usually a neighbour of the one you just left.
    expect(q.win.document.activeElement).toBe(q.entry('Security'));
  });

  it('a section is entered fresh every time the menu is opened', () => {
    // The accordion remembered which groups were unfolded, and remembering the open SECTION would be the same
    // complaint arriving by the other door: the popup would open somewhere the user cannot see the reason for.
    q.enter('Security');
    q.gear().click(); // close
    q.gear().click(); // and open again
    expect(q.title()).toBeNull();
    expect(q.entry('Security')).toBeTruthy();
  });

  it('a section the harness has never heard of is still a section', () => {
    q.win.cc.settingsMenu({ items: [{ key: 'x', group: 'Something New', label: 'X', on: false }] });
    expect(q.entry('Something New')).toBeTruthy();
    q.enter('Something New');
    expect(q.title()).toBe('Something New');
    expect(q.row('X')).toBeTruthy();
  });

  it('the keyboard walks the panel on screen, and only that panel', () => {
    // There is nothing to exclude any more: a section that is not on screen is not in the DOM, so a row of it
    // cannot be stepped onto, cannot hold the tab stop and cannot be reached by Tab. The accordion needed a
    // filter for exactly that, because `display: none` is invisible to jsdom and to a roving tabindex alike.
    const walk = [];
    for (let i = 0; i < 5; i++) {
      walk.push(q.focused());
      q.key('ArrowDown');
    }
    expect(walk).toEqual([
      'Model',
      'Effort',
      'Security',
      'Setting sourcesApplies to new chats',
      'Open Plugin Settings',
    ]);
    // …and it wraps, rather than stopping at the bottom.
    expect(q.focused()).toBe('Model');

    q.enter('Security');
    expect(q.all().map((e) => e.textContent)).toEqual(['←', 'Block credential files', 'Sandbox commands']);
  });

  it('Home and End land on the first and last entry of the panel on screen', () => {
    q.key('End');
    expect(q.focused()).toBe('Open Plugin Settings');
    q.key('Home');
    expect(q.focused()).toBe('Model');
    expect(q.all().filter((r) => r.getAttribute('tabindex') === '0').length).toBe(1);
  });

  it('Right enters a section and Left comes back, which is what a menu with submenus does', () => {
    q.key('ArrowDown');
    q.key('ArrowDown');
    expect(q.focused()).toBe('Security');
    q.key('ArrowRight');
    expect(q.title()).toBe('Security');
    // The focus lands on the first ROW, past the back arrow: it is what the press was for.
    expect(q.focused()).toBe('Block credential files');
    q.key('ArrowLeft');
    expect(q.title()).toBeNull();
    expect(q.focused()).toBe('Security');
  });

  it('Escape leaves one level, and only dismisses from the list', () => {
    // The submenu behaviour of the ARIA menu pattern. Anything else makes the deepest panel the one place
    // where the reader's habitual key throws the whole navigation away.
    q.enter('Security');
    q.key('Escape');
    expect(q.menu()).toBeTruthy();
    expect(q.title()).toBeNull();
    q.key('Escape');
    expect(q.menu()).toBeNull();
    expect(q.win.document.activeElement).toBe(q.gear());
  });

  it('Right does nothing on a setting row — there is nothing there to enter', () => {
    // Entering already lands on the first row, past the back arrow, so this is where the focus is.
    q.enter('Model');
    expect(q.focused()).toBe('Opus 5');
    q.key('ArrowRight');
    expect(q.focused()).toBe('Opus 5');
    expect(q.title()).toBe('Model');
  });

  it('a push that lands mid-section leaves the reader in it', () => {
    q.enter('Security');
    const row = q.row('Sandbox commands');

    // An identical push is a skip: same node, same panel.
    q.win.cc.settingsMenu(payload());
    expect(q.title()).toBe('Security');
    expect(q.row('Sandbox commands')).toBe(row);

    // A structural change is a real rebuild — every node is new — and the panel still has to be the one the
    // reader was in, because `view` lives outside the DOM the rebuild destroyed.
    q.win.cc.settingsMenu({
      items: payload().items.concat([{ key: 'net', group: 'Security', label: 'Block network', on: false }]),
    });
    expect(q.title()).toBe('Security');
    expect(q.row('Sandbox commands')).not.toBe(row);
    expect(q.row('Block network')).toBeTruthy();
  });

  it('asks the host to re-read the stored settings on open and on entering a section', () => {
    // The settings live in the IDE's PasswordSafe, which is application-wide, and the host's copy is loaded
    // once per service: with two IDEs open, everything this menu shows is the truth as of whenever this
    // process last read it. Stale is not merely old here — a toggle is a read-modify-write over the stored
    // document, so flipping a stale row flips the value it DISPLAYS rather than the value stored.
    //
    // The refreshes are counted from a fresh harness, because `beforeEach` has already opened the menu once.
    const fresh = harness();
    fresh.win.cc.settingsMenu(payload());
    expect(fresh.sent).toEqual([]);

    fresh.gear().click();
    expect(fresh.sent).toEqual([{ type: 'settingsRefresh' }]);
    // Drawn FIRST and refreshed after: waiting on a keychain read before painting would make the gear feel
    // broken, and the answer arrives as an ordinary push that redraws only what changed.
    expect(fresh.entry('Model')).toBeTruthy();

    fresh.enter('Security');
    expect(fresh.sent).toEqual([{ type: 'settingsRefresh' }, { type: 'settingsRefresh' }]);
    // …and once per step in, not once per press inside the section: what is on screen was just re-read.
    fresh.row('Sandbox commands').click();
    expect(fresh.sent.filter((m) => m.type === 'settingsRefresh')).toHaveLength(2);
  });

  it('a deferred section says so in text, on the entry, before you go in', () => {
    // A launch flag that reads as a live switch is exactly the defect this note closes, and a note carried
    // only by a colour or only by a `title` closes it for half the readers. It is a child of the button, so
    // it is part of the accessible name — and it is on the ENTRY because "these apply to new chats" is what
    // decides whether it is worth going in at all.
    const note = q.entry('Setting sources').querySelector('.settings-defer');
    expect(note).toBeTruthy();
    expect(note.textContent).toBe('Applies to new chats');
    expect(note.getAttribute('aria-hidden')).toBeNull();
    expect(q.entry('Setting sources').textContent).toContain('Applies to new chats');
    // …and only where the host said so.
    expect(q.entry('Security').querySelector('.settings-defer')).toBeNull();
    expect(q.entry('Model').querySelector('.settings-defer')).toBeNull();
  });
});

describe('a group that goes two levels deep', () => {
  let q;
  beforeEach(() => {
    q = harness();
    q.win.cc.settingsMenu(nestedPayload());
    q.gear().click();
  });

  it('shows the categories, not the rules, when you step into the group', () => {
    q.enter('Security');
    expect(q.title()).toBe('Security');
    // The sub-levels are entries, and none of the eleven rules is on this panel: a wall of switches is what
    // the level exists to avoid.
    const subs = Array.from(q.menu().querySelectorAll('.settings-group-entry')).map(
      (el) => el.querySelector('.menu-item-label').textContent
    );
    expect(subs).toEqual(['Sensitive data', 'Filesystem boundary']);
    expect(q.row('Block credential files')).toBeUndefined();
  });

  it('shows only that category’s rules one level further in', () => {
    q.enter('Security');
    q.entry('Filesystem boundary').click();
    expect(q.title()).toBe('Filesystem boundary');
    expect(q.row('Block the temp folder')).toBeTruthy();
    expect(q.row('Block shell writes')).toBeTruthy();
    expect(q.row('Block credential files')).toBeUndefined();
    // The state travels with the row, and the polarity is the host's: `on:false` is a rule switched OFF.
    expect(q.row('Block the temp folder').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Block shell writes').getAttribute('aria-checked')).toBe('true');
  });

  it('comes back out ONE level at a time, landing on the entry it came through', () => {
    q.enter('Security');
    q.entry('Sensitive data').click();
    expect(q.title()).toBe('Sensitive data');

    q.back().click();
    // Back to the categories, NOT to the root: Escape and the back arrow are one step, which is the submenu
    // behaviour of the ARIA menu pattern. Anything else throws away two levels of navigation from inside one.
    expect(q.title()).toBe('Security');
    expect(q.focused()).toContain('Sensitive data');

    q.back().click();
    expect(q.title()).toBe(null);
    expect(q.focused()).toContain('Security');
  });

  it('Escape leaves one level, and only dismisses the popup at the root', () => {
    q.enter('Security');
    q.entry('Sensitive data').click();
    q.key('Escape');
    expect(q.title()).toBe('Security');
    q.key('Escape');
    expect(q.title()).toBe(null);
    q.key('Escape');
    expect(q.menu()).toBe(null);
  });

  it('Right enters a sub-level and Left comes back, the same as the first level', () => {
    q.enter('Security');
    q.entry('Filesystem boundary').focus();
    q.key('ArrowRight');
    expect(q.title()).toBe('Filesystem boundary');
    q.key('ArrowLeft');
    expect(q.title()).toBe('Security');
  });

  it('a press inside a sub-level sends the host’s key verbatim, once', () => {
    q.enter('Security');
    q.entry('Filesystem boundary').click();
    q.row('Block the temp folder').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'rule:TEMP_DIR', on: true }]);
  });

  it('a push that moves a row to another category is a structural change, so the panel is redrawn', () => {
    q.enter('Security');
    q.entry('Sensitive data').click();
    expect(q.row('Block dangerous commands')).toBeTruthy();
    // Only `sub` differs. If it were absent from the signature, the panel would compare equal and keep drawing
    // a row that no longer belongs to it.
    q.win.cc.settingsMenu({
      items: nestedPayload().items.map((it) =>
        it.key === 'rule:SECRET_DUMPING_COMMANDS' ? Object.assign({}, it, { sub: 'Filesystem boundary' }) : it
      ),
    });
    expect(q.row('Block dangerous commands')).toBeUndefined();
  });

  it('a group with no sub is untouched by the level existing', () => {
    q.enter('Setting sources');
    // Straight to the rows, no intermediate panel: exactly as before, which is what makes the wire field
    // optional rather than a migration.
    expect(q.title()).toBe('Setting sources');
    expect(q.row('user')).toBeTruthy();
  });
});

describe('what the menu contains', () => {
  let q;
  beforeEach(() => {
    q = harness();
  });

  it('explains itself when the host has pushed nothing — never an empty popup', () => {
    q.gear().click();
    const empty = q.menu().querySelector('.settings-empty');
    expect(empty).toBeTruthy();
    expect(empty.textContent).toContain('No quick settings yet');
    // Still an ENTRY, so menu navigation reaches it — loose text inside a menu is text a screen-reader
    // user never arrives at.
    expect(empty.getAttribute('role')).toBe('menuitem');
    expect(empty.getAttribute('aria-disabled')).toBe('true');
    // And the door to the full page is there regardless: a menu with nothing to do in it is still useful.
    expect(q.body().lastElementChild.textContent).toBe('Open Plugin Settings');
  });

  it('a switch and a choice are different roles, and each carries its state programmatically', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.enter('Model');
    expect(q.row('Opus 5').getAttribute('role')).toBe('menuitemradio');
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Opus 5').classList.contains('selected')).toBe(true);
    // Real controls, so they pick up keyboard activation and the shared :focus-visible ring for free.
    expect(q.row('Opus 5').tagName).toBe('BUTTON');

    q.back().click();
    q.enter('Security');
    expect(q.row('Block credential files').getAttribute('role')).toBe('menuitemcheckbox');
    expect(q.row('Sandbox commands').classList.contains('selected')).toBe(false);
    expect(q.row('Block credential files').tagName).toBe('BUTTON');
  });

  it('an omitted `type` is a switch — that default is the contract, not a guess', () => {
    q.win.cc.settingsMenu({
      items: [{ key: 'thinking', group: 'Chat', label: 'Extended thinking', on: true }],
    });
    q.gear().click();
    q.enter('Chat');
    expect(q.row('Extended thinking').getAttribute('role')).toBe('menuitemcheckbox');
  });

  it('the whole label is kept where it can be read, since a row can be a tool name', () => {
    // The row ellipsises at a fixed width, so the untruncated text has to survive somewhere reachable.
    const long = 'allow:mcp__jetbrains__get_file_problems';
    q.win.cc.settingsMenu({ items: [{ key: long, group: 'Allowed tools', label: long, on: true }] });
    q.gear().click();
    q.enter('Allowed tools');
    expect(q.row(long).getAttribute('title')).toBe(long);
  });

  it('the visible "on" mark is a glyph, not a hue — WCAG 1.4.1', () => {
    // jsdom paints nothing, so the non-colour half of the state lives in the stylesheet and is read there.
    // `.menu-item.selected::after` is the ✓ the pill menus already use; the accent colour is the second,
    // redundant signal and must never be the only one.
    expect(readCss()).toMatch(/\.menu-item\.selected::after\s*\{[^}]*content:\s*'✓'/);
  });

  it('a chosen radio wears a mark of its own, so a choice is not read as a switch', () => {
    expect(readCss()).toMatch(
      /\.settings-item\[role='menuitemradio'\]\.selected::after\s*\{[^}]*content:\s*'●'/
    );
  });

  it('a separator with a real role precedes the way out to the full settings', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    const out = q.body().lastElementChild;
    expect(out.getAttribute('role')).toBe('menuitem');
    expect(out.textContent).toBe('Open Plugin Settings');
    expect(out.previousElementSibling.getAttribute('role')).toBe('separator');
    // On the ROOT panel only: inside a section it would be a way out of the page sitting one row from the
    // way back to the list.
    q.enter('Model');
    expect(q.body().textContent).not.toContain('Open Plugin Settings');
  });
});

describe('what a press sends', () => {
  let q;
  beforeEach(() => {
    q = harness();
    q.win.cc.settingsMenu(payload());
    q.gear().click();
  });

  it('toggling a switch sends the key and the NEW value, and flips the row at once', () => {
    q.enter('Security');
    q.row('Sandbox commands').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'sandbox', on: true }]);
    // Optimistic on purpose: a switch that does nothing until a round trip completes reads as broken, and
    // the host's next push is authoritative either way.
    expect(q.row('Sandbox commands').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sandbox commands').classList.contains('selected')).toBe(true);
  });

  it('toggling an ON switch sends off', () => {
    q.enter('Security');
    q.row('Block credential files').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'blockCredentials', on: false }]);
  });

  it('choosing a radio unchecks its group on screen and sends exactly ONE message', () => {
    // The assertion that pins exclusivity: the sibling goes off in the DOM, and nothing about it rides on the
    // wire. The host owns the group — an `on:false` per sibling would be the page deciding what a group
    // means, and several toggles racing for one gesture.
    q.enter('Model');
    q.row('Sonnet').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'model:sonnet', on: true }]);
    expect(q.toggles().filter((m) => m.on === false)).toEqual([]);
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sonnet').classList.contains('selected')).toBe(true);
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Opus 5').classList.contains('selected')).toBe(false);
    // A different radio group is a different question and is not answered by this press — which the panels
    // make structural rather than a claim: the answer is still there when you go and look at it.
    q.back().click();
    q.enter('Effort');
    expect(q.row('High').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Low').getAttribute('aria-checked')).toBe('false');
  });

  it('choosing the radio that is already chosen sends nothing', () => {
    q.enter('Model');
    q.row('Opus 5').click();
    expect(q.toggles()).toEqual([]);
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('true');
  });

  it('the composite key goes back to the host LITERALLY — the page never reads inside one', () => {
    // `model:opus[1m]` is the host's spelling, brackets and all. The page receives it, hands it back and
    // validates nothing: that is what keeps the set of keys closed and knowable from one side.
    q.enter('Model');
    q.row('Sonnet').click();
    q.row('Opus 5').click();
    expect(q.toggles().map((m) => m.key)).toEqual(['model:sonnet', 'model:opus[1m]']);
    q.back().click();
    q.enter('Setting sources');
    q.row('user').click();
    expect(q.toggles()[2].key).toBe('source:user');
  });

  it('the choice survives a reopen, so the stash was updated and not only the screen', () => {
    // The stash is what the next render reads. Left stale, it would put the old choice back the moment
    // anything redrew — which a reopen does, from the payload and not from the DOM.
    q.enter('Model');
    q.row('Sonnet').click();
    q.gear().click();
    q.gear().click();
    q.enter('Model');
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('false');
  });

  it('the menu STAYS open — this is a panel of settings, not one choice', () => {
    q.enter('Model');
    q.row('Sonnet').click();
    expect(q.menu()).toBeTruthy();
    expect(q.title()).toBe('Model'); // and in the same section, so the second change is the next press
    q.back().click();
    q.enter('Effort');
    q.row('Low').click();
    expect(q.toggles().length).toBe(2);
    expect(q.menu()).toBeTruthy();
  });

  it('announces the change, because nothing else does', () => {
    // WCAG 4.1.3: the press moves no focus and paints a glyph, so without a live-region write it is silent.
    const status = () => q.win.document.getElementById('a11y-status').textContent;
    q.enter('Security');
    q.row('Sandbox commands').click();
    expect(status()).toBe('Sandbox commands on');
    q.row('Sandbox commands').click();
    expect(status()).toBe('Sandbox commands off');
    q.back().click();
    q.enter('Model');
    q.row('Sonnet').click();
    expect(status()).toBe('Sonnet selected');
  });

  it('Open Plugin Settings asks the host and closes behind itself', () => {
    q.body().lastElementChild.click();
    expect(q.toggles()).toEqual([{ type: 'openSettings' }]);
    expect(q.menu()).toBeNull();
    // No focus return here, unlike Escape: this entry hands the user to another window, and dragging the
    // caret back to the gear first would take it away from wherever that window puts it.
    expect(q.gear().getAttribute('aria-expanded')).toBe('false');
  });
});

describe('host pushes while the menu is open and while it is shut', () => {
  let q;
  beforeEach(() => {
    q = harness();
  });

  it('stashes a payload that arrives while shut, and draws it on the next open', () => {
    q.win.cc.settingsMenu(payload());
    expect(q.menu()).toBeNull(); // nothing drawn, nothing thrown
    q.gear().click();
    expect(q.all().length).toBe(5); // 4 sections + the way out
  });

  it('redraws an open menu when the structure changes', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.win.cc.settingsMenu({
      items: [{ key: 'thinking', group: 'Chat', label: 'Extended thinking', on: false }],
    });
    expect(q.entry('Chat')).toBeTruthy();
    expect(q.entry('Model')).toBeUndefined();
  });

  it('an identical push does NOT rebuild the rows', () => {
    // The rule that costs a caret when it is broken: the DOM has no move, so rebuilding is a remove plus an
    // insert and the focused row is blurred by it. Node identity is the only way to tell a skip from a
    // repaint that happens to produce the same markup.
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    const before = q.all();
    q.win.cc.settingsMenu(payload()); // a structurally identical payload, and a different object
    const after = q.all();
    expect(after[0]).toBe(before[0]);
    expect(after[after.length - 1]).toBe(before[before.length - 1]);
  });

  it('a push that changes only `type` DOES rebuild, or a choice would stay painted as a switch', () => {
    // The role is decided at build time, so `type` has to be in the structure signature. Left out, the host
    // could turn a group into a set of choices and the page would keep drawing independent switches.
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.enter('Security');
    const before = q.row('Block credential files');
    q.win.cc.settingsMenu({ items: withField((it) => it.group === 'Security', { type: 'radio' }) });
    expect(q.row('Block credential files')).not.toBe(before);
    expect(q.row('Block credential files').getAttribute('role')).toBe('menuitemradio');
  });

  it('a push that changes only `deferred` DOES rebuild, and the note appears', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    expect(q.entry('Security').querySelector('.settings-defer')).toBeNull();
    q.win.cc.settingsMenu({ items: withField((it) => it.group === 'Security', { deferred: true }) });
    expect(q.entry('Security').querySelector('.settings-defer')).toBeTruthy();
  });

  it('a state-only push updates the rows in place, keeping the focus', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.enter('Model');
    const row = q.row('Sonnet');
    row.focus();
    q.win.cc.settingsMenu({
      items: payload().items.map((it) => Object.assign({}, it, { on: !it.on })),
    });
    expect(q.row('Sonnet')).toBe(row);
    expect(q.win.document.activeElement).toBe(row);
    expect(row.getAttribute('aria-checked')).toBe('true');
    // A section that is not on screen holds no rows to update — it is not in the DOM — and its state is read
    // off the payload when it is built, which is what going in and looking proves.
    q.back().click();
    q.enter('Security');
    expect(q.row('Sandbox commands').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Block credential files').getAttribute('aria-checked')).toBe('false');
  });

  it('a rebuild puts the focus back on the same entry, row or section, not on the top of the list', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    const grown = () =>
      payload().items.concat([{ key: 'net', group: 'Security', label: 'Block network', on: false }]);

    // A section entry is an entry like any other, so it has to be restorable like one.
    q.entry('Effort').focus();
    q.win.cc.settingsMenu({ items: grown() });
    expect(q.win.document.activeElement).toBe(q.entry('Effort'));

    q.enter('Model');
    q.row('Sonnet').focus();
    q.win.cc.settingsMenu({
      items: grown().concat([{ key: 'm3', group: 'Model', label: 'Haiku', on: false, type: 'radio' }]),
    });
    expect(q.title()).toBe('Model');
    expect(q.win.document.activeElement.textContent).toBe('Sonnet');
  });

  it('survives a malformed payload instead of emptying the menu of its way out', () => {
    q.win.cc.settingsMenu({ items: [{ label: 'no key here' }, null] });
    q.gear().click();
    expect(q.menu().querySelectorAll('[role="menuitemcheckbox"],[role="menuitemradio"]').length).toBe(0);
    expect(q.body().lastElementChild.textContent).toBe('Open Plugin Settings');
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

  it('the button rows do not out-order the .menu-item colour that marks a row as on', () => {
    // `.settings-item` resets what a <button> brings (width, alignment, font family, border, background) and
    // must NOT declare `color` or `font-size`: same specificity and later in the cascade, either one would
    // beat `.menu-item` and flatten the checked accent.
    const rule = ruleBody('.settings-item');
    expect(rule).toMatch(/background:\s*transparent/);
    expect(rule).toMatch(/text-align:\s*left/);
    expect(rule).not.toMatch(/\bcolor\s*:/);
    expect(rule).not.toMatch(/font-size\s*:/);
  });

  it('the deferred note is a rule of its own and cannot be squeezed out by the caret', () => {
    // Both margins would otherwise be `auto`, which splits the free space and parks the note in the middle
    // of the heading instead of at its end.
    expect(ruleBody('.settings-defer')).toMatch(/margin-left:\s*auto/);
    expect(ruleBody('.settings-defer + .menu-group-caret')).toMatch(/margin-left:\s*0/);
  });

  it('the row label is what is capped, not the panel', () => {
    // `positionMenu` writes an inline `max-width` on the popup, and an inline style wins — a cap declared on
    // `.settings-menu` would simply not be the one in force when a tool name is longer than the popup.
    const rule = ruleBody('.settings-menu .menu-item-label');
    expect(rule).toMatch(/max-width:\s*\d+px/);
    expect(rule).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('reuses the drill-down classes for the second level, adding no CSS of its own', () => {
    // The sub-level is the same gesture as the first level, so it is the same class — `.settings-group-entry`
    // for the entry and `.settings-section-items` for the rows. A new class here would be a second popup
    // language one press deeper, which is exactly what this stylesheet was consolidated to end.
    expect(css).not.toMatch(/\.settings-sub\b/);
    expect(css).not.toMatch(/\.settings-subgroup\b/);
  });

  it('leaves no rule behind for markup the menu stopped emitting', () => {
    // A rule for markup nobody emits is this repository's signature defect wearing a stylesheet. The
    // accordion went when the menu started drilling down, so this menu emits no `.menu-group` wrapper and no
    // `.menu-group-header` — and the rules that dressed them for THIS popup went with them. The base
    // `.menu-group` rules stay, because the pill menus do still fold.
    expect(css).not.toMatch(/\.settings-menu \.menu-group\b/);
    expect(css).not.toMatch(/\.settings-menu \.menu-group-header\b/);
    // `.settings-section-items` IS emitted (it is the open section's rows) — which is why this asserts the
    // exact old class rather than a prefix of the new one. A `\b` after `section` matches the `-` in
    // `section-items`, and a test that fails on the thing it was written to allow is a test nobody trusts.
    expect(css).not.toMatch(/\.settings-section \{/);
  });
});
