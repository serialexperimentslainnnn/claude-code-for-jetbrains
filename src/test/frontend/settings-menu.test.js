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
    gear: () => win.document.querySelector('.settings-btn'),
    menu: () => win.document.querySelector('.settings-menu'),
    /** Every entry, folded-away rows included. */
    all: () =>
      Array.from(
        q.menu().querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
      ),
    /** The heading of a group, by the name it shows. */
    head: (name) =>
      Array.from(q.menu().querySelectorAll('.menu-group-header')).find(
        (el) => el.querySelector('.menu-item-label').textContent === name
      ),
    /** A setting row, by the label it shows. */
    row: (label) => q.all().find((el) => el.textContent === label),
    /** The `.menu-group` wrapper of a group. */
    group: (name) => q.head(name).parentElement,
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

describe('the groups', () => {
  let q;
  beforeEach(() => {
    q = harness();
    q.win.cc.settingsMenu(payload());
    q.gear().click();
  });

  it('draws one group per group the host names, in the host’s own order', () => {
    const groups = Array.from(q.menu().querySelectorAll('[role="group"]'));
    expect(groups.map((g) => g.getAttribute('aria-label'))).toEqual([
      'Model',
      'Effort',
      'Security',
      'Setting sources',
    ]);
  });

  it('a heading is an entry that says what it controls and whether it is open', () => {
    // A `role="menu"` whose headings are plain text is a menu whose headings the keyboard cannot reach, and
    // a fold with no `aria-expanded` is a fold nobody is told about.
    const head = q.head('Security');
    expect(head.tagName).toBe('BUTTON');
    expect(head.getAttribute('role')).toBe('menuitem');
    expect(head.getAttribute('aria-expanded')).toBe('false');
    const region = q.menu().querySelector('#' + head.getAttribute('aria-controls'));
    expect(region.getAttribute('role')).toBe('group');
    expect(region.getAttribute('aria-label')).toBe('Security');
    expect(region.contains(q.row('Sandbox commands'))).toBe(true);
  });

  it('the groups a turn is steered with open unfolded; the long ones do not', () => {
    // Seventy rows behind a scrollbar is a worse Settings page, not a quicker one — but making the model
    // list cost a press would make the fold cost more than it saves.
    expect(q.head('Model').getAttribute('aria-expanded')).toBe('true');
    expect(q.head('Effort').getAttribute('aria-expanded')).toBe('true');
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('false');
    expect(q.head('Setting sources').getAttribute('aria-expanded')).toBe('false');
    expect(q.group('Model').classList.contains('open')).toBe(true);
    expect(q.group('Security').classList.contains('open')).toBe(false);
  });

  it('an unrecognised group starts folded — an extra press beats a popup that opens seventy rows tall', () => {
    q.win.cc.settingsMenu({ items: [{ key: 'x', group: 'Something New', label: 'X', on: false }] });
    expect(q.head('Something New').getAttribute('aria-expanded')).toBe('false');
  });

  it('a folded group is out of the keyboard rotation, not merely out of the paint', () => {
    // The whole walk, in one assertion: Security and Setting sources are folded, so ArrowDown steps from one
    // heading straight to the next and never stops on a row nobody can see.
    const walk = [];
    for (let i = 0; i < 9; i++) {
      walk.push(q.focused());
      q.key('ArrowDown');
    }
    expect(walk).toEqual([
      'Model',
      'Opus 5',
      'Sonnet',
      'Effort',
      'High',
      'Low',
      'Security',
      'Setting sourcesApplies to new chats',
      'Open Plugin Settings',
    ]);
    // …and it wraps, rather than stopping at the bottom.
    expect(q.focused()).toBe('Model');
  });

  it('folding from under the focus takes the focus and the tab stop back to the heading', () => {
    // `display: none` is what stops a browser focusing these, and it answers a focused subtree becoming
    // hidden by dropping the focus to `<body>` — out of the menu, out of the roving model, and with nothing
    // on screen saying where it went. The tab order is the page's own doing and has the same hazard: a stop
    // parked on a row that then folded away is a Tab press that lands nowhere.
    q.key('ArrowDown'); // onto a row of the unfolded Model group
    expect(q.focused()).toBe('Opus 5');
    q.head('Model').click(); // fold it from under the focus
    expect(q.win.document.activeElement).toBe(q.head('Model'));
    const tabbable = q.all().filter((e) => e.getAttribute('tabindex') === '0');
    expect(tabbable.length).toBe(1);
    expect(tabbable[0]).toBe(q.head('Model'));
    expect(q.row('Opus 5').getAttribute('tabindex')).toBe('-1');
  });

  it('Home and End land on the first and last entry the keyboard can reach', () => {
    q.key('End');
    expect(q.focused()).toBe('Open Plugin Settings');
    q.key('Home');
    expect(q.focused()).toBe('Model');
    expect(q.all().filter((r) => r.getAttribute('tabindex') === '0').length).toBe(1);
  });

  it('Right unfolds a heading and Left folds it, which is what a menu with regions does', () => {
    q.key('ArrowDown');
    q.key('ArrowDown');
    q.key('ArrowDown');
    q.key('ArrowDown');
    q.key('ArrowDown');
    q.key('ArrowDown');
    expect(q.focused()).toBe('Security');
    q.key('ArrowRight');
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('true');
    expect(q.focused()).toBe('Security'); // the heading keeps the focus; the rows are now below it
    q.key('ArrowDown');
    expect(q.focused()).toBe('Block credential files');
    q.key('ArrowUp');
    q.key('ArrowLeft');
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('false');
  });

  it('the arrows do nothing on an ordinary row — there is nothing there to open', () => {
    q.key('ArrowDown');
    expect(q.focused()).toBe('Opus 5');
    q.key('ArrowRight');
    q.key('ArrowLeft');
    expect(q.focused()).toBe('Opus 5');
    expect(q.head('Model').getAttribute('aria-expanded')).toBe('true');
  });

  it('the fold is remembered between openings of the menu', () => {
    // The popup is destroyed on every close, so without state outside it, changing two things in the same
    // group would cost the same press twice.
    q.head('Security').click();
    q.head('Model').click();
    q.gear().click(); // close
    q.gear().click(); // and open again
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('true');
    expect(q.head('Model').getAttribute('aria-expanded')).toBe('false');
  });

  it('the fold survives a rebuild, and an identical push does not disturb it', () => {
    q.head('Security').click();
    const head = q.head('Security');

    // An identical push is a skip: same node, same state.
    q.win.cc.settingsMenu(payload());
    expect(q.head('Security')).toBe(head);
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('true');

    // A structural change is a real rebuild — every node is new, and the fold still has to come back, because
    // it lives outside the DOM the rebuild destroyed.
    q.win.cc.settingsMenu({
      items: payload().items.concat([{ key: 'net', group: 'Security', label: 'Block network', on: false }]),
    });
    expect(q.head('Security')).not.toBe(head);
    expect(q.head('Security').getAttribute('aria-expanded')).toBe('true');
    expect(q.head('Model').getAttribute('aria-expanded')).toBe('true');
  });

  it('a deferred group says so in text, inside the heading its reader hears', () => {
    // A launch flag that reads as a live switch is exactly the defect this note closes, and a note carried
    // only by a colour or only by a `title` closes it for half the readers. It is a child of the button, so
    // it is part of the accessible name.
    const note = q.head('Setting sources').querySelector('.settings-defer');
    expect(note).toBeTruthy();
    expect(note.textContent).toBe('Applies to new chats');
    expect(note.getAttribute('aria-hidden')).toBeNull();
    expect(q.head('Setting sources').textContent).toContain('Applies to new chats');
    expect(q.head('Setting sources').getAttribute('title')).toBeNull();
    // …and only where the host said so.
    expect(q.head('Security').querySelector('.settings-defer')).toBeNull();
    expect(q.head('Model').querySelector('.settings-defer')).toBeNull();
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
    expect(q.menu().lastElementChild.textContent).toBe('Open Plugin Settings');
  });

  it('a switch and a choice are different roles, and each carries its state programmatically', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    expect(q.row('Opus 5').getAttribute('role')).toBe('menuitemradio');
    expect(q.row('Block credential files').getAttribute('role')).toBe('menuitemcheckbox');
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Opus 5').classList.contains('selected')).toBe(true);
    expect(q.row('Sandbox commands').classList.contains('selected')).toBe(false);
    // Real controls, so they pick up keyboard activation and the shared :focus-visible ring for free.
    expect(q.row('Opus 5').tagName).toBe('BUTTON');
    expect(q.row('Block credential files').tagName).toBe('BUTTON');
  });

  it('an omitted `type` is a switch — that default is the contract, not a guess', () => {
    q.win.cc.settingsMenu({
      items: [{ key: 'thinking', group: 'Chat', label: 'Extended thinking', on: true }],
    });
    q.gear().click();
    expect(q.row('Extended thinking').getAttribute('role')).toBe('menuitemcheckbox');
  });

  it('the whole label is kept where it can be read, since a row can be a tool name', () => {
    // The row ellipsises at a fixed width, so the untruncated text has to survive somewhere reachable.
    const long = 'allow:mcp__jetbrains__get_file_problems';
    q.win.cc.settingsMenu({ items: [{ key: long, group: 'Allowed tools', label: long, on: true }] });
    q.gear().click();
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
    const out = q.menu().lastElementChild;
    expect(out.getAttribute('role')).toBe('menuitem');
    expect(out.textContent).toBe('Open Plugin Settings');
    expect(out.previousElementSibling.getAttribute('role')).toBe('separator');
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
    q.row('Sandbox commands').click();
    expect(q.sent).toEqual([{ type: 'settingsToggle', key: 'sandbox', on: true }]);
    // Optimistic on purpose: a switch that does nothing until a round trip completes reads as broken, and
    // the host's next push is authoritative either way.
    expect(q.row('Sandbox commands').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sandbox commands').classList.contains('selected')).toBe(true);
  });

  it('toggling an ON switch sends off', () => {
    q.row('Block credential files').click();
    expect(q.sent).toEqual([{ type: 'settingsToggle', key: 'blockCredentials', on: false }]);
  });

  it('choosing a radio unchecks its group on screen and sends exactly ONE message', () => {
    // The assertion that pins exclusivity: the sibling goes off in the DOM, the other group is untouched, and
    // nothing about either rides on the wire. The host owns the group — an `on:false` per sibling would be
    // the page deciding what a group means, and several toggles racing for one gesture.
    q.row('Sonnet').click();
    expect(q.sent).toEqual([{ type: 'settingsToggle', key: 'model:sonnet', on: true }]);
    expect(q.sent.filter((m) => m.on === false)).toEqual([]);
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sonnet').classList.contains('selected')).toBe(true);
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Opus 5').classList.contains('selected')).toBe(false);
    // A different radio group is a different question and is not answered by this press.
    expect(q.row('High').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Low').getAttribute('aria-checked')).toBe('false');
  });

  it('choosing the radio that is already chosen sends nothing', () => {
    q.row('Opus 5').click();
    expect(q.sent).toEqual([]);
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('true');
  });

  it('the composite key goes back to the host LITERALLY — the page never reads inside one', () => {
    // `model:opus[1m]` is the host's spelling, brackets and all. The page receives it, hands it back and
    // validates nothing: that is what keeps the set of keys closed and knowable from one side.
    q.row('Sonnet').click();
    q.row('Opus 5').click();
    expect(q.sent.map((m) => m.key)).toEqual(['model:sonnet', 'model:opus[1m]']);
    q.head('Setting sources').click();
    q.row('user').click();
    expect(q.sent[2].key).toBe('source:user');
  });

  it('the choice survives a reopen, so the stash was updated and not only the screen', () => {
    // The stash is what the next render reads. Left stale, it would put the old choice back the moment
    // anything redrew — which a reopen does, from the payload and not from the DOM.
    q.row('Sonnet').click();
    q.gear().click();
    q.gear().click();
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('false');
  });

  it('the menu STAYS open — this is a panel of settings, not one choice', () => {
    q.row('Sonnet').click();
    expect(q.menu()).toBeTruthy();
    q.row('Low').click();
    expect(q.sent.length).toBe(2);
    expect(q.menu()).toBeTruthy();
  });

  it('announces the change, because nothing else does', () => {
    // WCAG 4.1.3: the press moves no focus and paints a glyph, so without a live-region write it is silent.
    const status = () => q.win.document.getElementById('a11y-status').textContent;
    q.row('Sandbox commands').click();
    expect(status()).toBe('Sandbox commands on');
    q.row('Sandbox commands').click();
    expect(status()).toBe('Sandbox commands off');
    q.row('Sonnet').click();
    expect(status()).toBe('Sonnet selected');
  });

  it('Open Plugin Settings asks the host and closes behind itself', () => {
    q.menu().lastElementChild.click();
    expect(q.sent).toEqual([{ type: 'openSettings' }]);
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
    expect(q.all().length).toBe(13); // 4 headings + 8 rows + the way out
  });

  it('redraws an open menu when the structure changes', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.win.cc.settingsMenu({
      items: [{ key: 'thinking', group: 'Chat', label: 'Extended thinking', on: false }],
    });
    expect(q.row('Extended thinking')).toBeTruthy();
    expect(q.row('Opus 5')).toBeUndefined();
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
    const before = q.row('Block credential files');
    q.win.cc.settingsMenu({ items: withField((it) => it.group === 'Security', { type: 'radio' }) });
    expect(q.row('Block credential files')).not.toBe(before);
    expect(q.row('Block credential files').getAttribute('role')).toBe('menuitemradio');
  });

  it('a push that changes only `deferred` DOES rebuild, and the note appears', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    expect(q.head('Security').querySelector('.settings-defer')).toBeNull();
    q.win.cc.settingsMenu({ items: withField((it) => it.group === 'Security', { deferred: true }) });
    expect(q.head('Security').querySelector('.settings-defer')).toBeTruthy();
  });

  it('a state-only push updates the rows in place, folded ones included, keeping the focus', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    const row = q.row('Sonnet');
    row.focus();
    q.win.cc.settingsMenu({
      items: payload().items.map((it) => Object.assign({}, it, { on: !it.on })),
    });
    expect(q.row('Sonnet')).toBe(row);
    expect(q.win.document.activeElement).toBe(row);
    expect(row.getAttribute('aria-checked')).toBe('true');
    // The folded group is synced too: its rows are on screen the instant it is unfolded, and a row skipped
    // here would come back showing the state it had when the group was closed.
    expect(q.row('Sandbox commands').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Block credential files').getAttribute('aria-checked')).toBe('false');
  });

  it('a rebuild puts the focus back on the same entry, row or heading, not on the top of the list', () => {
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    q.row('Sonnet').focus();
    const grown = () =>
      payload().items.concat([{ key: 'net', group: 'Security', label: 'Block network', on: false }]);
    q.win.cc.settingsMenu({ items: grown() });
    expect(q.win.document.activeElement.textContent).toBe('Sonnet');

    // A heading is an entry like any other, so it has to be restorable like one.
    q.head('Effort').focus();
    q.win.cc.settingsMenu({
      items: grown().concat([{ key: 'e2', group: 'Effort', label: 'Medium', on: false }]),
    });
    expect(q.win.document.activeElement).toBe(q.head('Effort'));
  });

  it('survives a malformed payload instead of emptying the menu of its way out', () => {
    q.win.cc.settingsMenu({ items: [{ label: 'no key here' }, null] });
    q.gear().click();
    expect(q.menu().querySelectorAll('[role="menuitemcheckbox"],[role="menuitemradio"]').length).toBe(0);
    expect(q.menu().lastElementChild.textContent).toBe('Open Plugin Settings');
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

  it('leaves no rule behind for markup the menu stopped emitting', () => {
    // The headings became pressable entries, so the label div and the section wrapper are gone. A rule for
    // markup nobody emits is this repository's signature defect wearing a stylesheet.
    expect(css).not.toMatch(/\.settings-section\b/);
    expect(css).not.toMatch(/\.settings-group\b/);
  });
});
