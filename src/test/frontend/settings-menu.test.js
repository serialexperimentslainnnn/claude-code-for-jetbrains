const { loadFrontend, readCss } = require('./helpers/load');

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

function withField(match, extra) {
  return payload().items.map((it) => (match(it) ? Object.assign({}, it, extra) : it));
}

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
    expect(gear().tagName).toBe('BUTTON');
    expect(gear().type).toBe('button');
    expect(gear().getAttribute('aria-label')).toBe('Chat settings');
    expect(gear().getAttribute('aria-haspopup')).toBe('menu');
    expect(gear().getAttribute('aria-expanded')).toBe('false');
  });

  it('draws its icon inline, in the button’s own colour, with nothing fetched', () => {
    const svg = gear().querySelector('svg');
    expect(svg).toBeTruthy();
    expect(svg.getAttribute('fill')).toBe('currentColor');
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(gear().innerHTML).not.toMatch(/url\(|<image|href/);
  });

  it('stays first however the row is filled — the mount is idempotent and order-blind', () => {
    const stack = win.document.createElement('div');
    views().insertBefore(stack, views().firstChild);
    win.CC.composer.mountSettingsButton();
    expect(views().firstElementChild).toBe(gear());
    expect(views().querySelectorAll('.settings-btn').length).toBe(1);

    const before = gear();
    gear().focus();
    win.CC.composer.mountSettingsButton();
    expect(gear()).toBe(before);
    expect(win.document.activeElement).toBe(before);
  });
});

describe('the ⚙ button vs. the dashboard’s own view stack', () => {
  it('keeps the head of the row when app-session.js mounts its stack afterwards', () => {
    const win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.session({});
    const row = win.document.getElementById('views');
    expect(row.querySelector('.dash-toggles')).toBeTruthy();
    expect(row.firstElementChild).toBe(row.querySelector('.settings-btn'));
    const chat = row.querySelector('.dash-exit');
    expect(row.firstElementChild.compareDocumentPosition(chat) & 4).toBeTruthy();
  });
});

function harness() {
  const win = loadFrontend(['app-composer.js'], { vendor: false });
  const sent = [];
  win.CC.send = (m) => sent.push(m);
  const q = {
    win,
    sent,
    toggles: () => sent.filter((m) => m && m.type !== 'settingsRefresh'),
    gear: () => win.document.querySelector('.settings-btn'),
    menu: () => win.document.querySelector('.settings-menu'),
    all: () =>
      Array.from(
        q.menu().querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"],[role="menuitemradio"]')
      ),
    entry: (name) =>
      Array.from(q.menu().querySelectorAll('.settings-group-entry')).find(
        (el) => el.querySelector('.menu-item-label').textContent === name
      ),
    enter: (name) => q.entry(name).click(),
    back: () => q.menu().querySelector('.attach-back'),
    body: () => q.menu().querySelector('.settings-body'),
    title: () => {
      const el = q.menu().querySelector('.attach-title');
      return el ? el.textContent : null;
    },
    row: (label) => q.all().find((el) => el.textContent === label),
    key: (k) =>
      win.document.activeElement.dispatchEvent(
        new win.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true })
      ),
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
    expect(q.row('Opus 5')).toBeUndefined();
    expect(q.title()).toBeNull();
    expect(q.back()).toBeNull();
  });

  it('a section entry is an entry that says it leads somewhere', () => {
    const entry = q.entry('Security');
    expect(entry.tagName).toBe('BUTTON');
    expect(entry.getAttribute('role')).toBe('menuitem');
    expect(entry.getAttribute('aria-haspopup')).toBe('menu');
    expect(entry.getAttribute('aria-expanded')).toBeNull();
  });

  it('the section’s rows are in a container the stylesheet actually SHOWS', () => {
    q.enter('Security');
    const region = q.menu().querySelector('[role="group"]');
    expect(region.className).toBe('settings-section-items');
    expect(region.className).not.toContain('menu-group-items');
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
    const region = q.menu().querySelector('[role="group"]');
    expect(region.getAttribute('aria-label')).toBe('Security');
    expect(region.contains(q.row('Sandbox commands'))).toBe(true);
    expect(q.entry('Model')).toBeUndefined();
    expect(q.row('Opus 5')).toBeUndefined();
    expect(q.menu().textContent).not.toContain('Open Plugin Settings');
  });

  it('back returns to the list, on the entry you came through', () => {
    q.enter('Security');
    q.back().click();
    expect(q.title()).toBeNull();
    expect(q.entry('Model')).toBeTruthy();
    expect(q.win.document.activeElement).toBe(q.entry('Security'));
  });

  it('a section is entered fresh every time the menu is opened', () => {
    q.enter('Security');
    q.gear().click();
    q.gear().click();
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
    expect(q.focused()).toBe('Block credential files');
    q.key('ArrowLeft');
    expect(q.title()).toBeNull();
    expect(q.focused()).toBe('Security');
  });

  it('Escape leaves one level, and only dismisses from the list', () => {
    q.enter('Security');
    q.key('Escape');
    expect(q.menu()).toBeTruthy();
    expect(q.title()).toBeNull();
    q.key('Escape');
    expect(q.menu()).toBeNull();
    expect(q.win.document.activeElement).toBe(q.gear());
  });

  it('Right does nothing on a setting row — there is nothing there to enter', () => {
    q.enter('Model');
    expect(q.focused()).toBe('Opus 5');
    q.key('ArrowRight');
    expect(q.focused()).toBe('Opus 5');
    expect(q.title()).toBe('Model');
  });

  it('a push that lands mid-section leaves the reader in it', () => {
    q.enter('Security');
    const row = q.row('Sandbox commands');

    q.win.cc.settingsMenu(payload());
    expect(q.title()).toBe('Security');
    expect(q.row('Sandbox commands')).toBe(row);

    q.win.cc.settingsMenu({
      items: payload().items.concat([{ key: 'net', group: 'Security', label: 'Block network', on: false }]),
    });
    expect(q.title()).toBe('Security');
    expect(q.row('Sandbox commands')).not.toBe(row);
    expect(q.row('Block network')).toBeTruthy();
  });

  it('asks the host to re-read the stored settings on open and on entering a section', () => {
    const fresh = harness();
    fresh.win.cc.settingsMenu(payload());
    expect(fresh.sent).toEqual([]);

    fresh.gear().click();
    expect(fresh.sent).toEqual([{ type: 'settingsRefresh' }]);
    expect(fresh.entry('Model')).toBeTruthy();

    fresh.enter('Security');
    expect(fresh.sent).toEqual([{ type: 'settingsRefresh' }, { type: 'settingsRefresh' }]);
    fresh.row('Sandbox commands').click();
    expect(fresh.sent.filter((m) => m.type === 'settingsRefresh')).toHaveLength(2);
  });

  it('a deferred section says so in text, on the entry, before you go in', () => {
    const note = q.entry('Setting sources').querySelector('.settings-defer');
    expect(note).toBeTruthy();
    expect(note.textContent).toBe('Applies to new chats');
    expect(note.getAttribute('aria-hidden')).toBeNull();
    expect(q.entry('Setting sources').textContent).toContain('Applies to new chats');
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
    expect(q.row('Block the temp folder').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Block shell writes').getAttribute('aria-checked')).toBe('true');
  });

  it('comes back out ONE level at a time, landing on the entry it came through', () => {
    q.enter('Security');
    q.entry('Sensitive data').click();
    expect(q.title()).toBe('Sensitive data');

    q.back().click();
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
    q.win.cc.settingsMenu({
      items: nestedPayload().items.map((it) =>
        it.key === 'rule:SECRET_DUMPING_COMMANDS' ? Object.assign({}, it, { sub: 'Filesystem boundary' }) : it
      ),
    });
    expect(q.row('Block dangerous commands')).toBeUndefined();
  });

  it('a group with no sub is untouched by the level existing', () => {
    q.enter('Setting sources');
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
    expect(empty.getAttribute('role')).toBe('menuitem');
    expect(empty.getAttribute('aria-disabled')).toBe('true');
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
    const long = 'allow:mcp__jetbrains__get_file_problems';
    q.win.cc.settingsMenu({ items: [{ key: long, group: 'Allowed tools', label: long, on: true }] });
    q.gear().click();
    q.enter('Allowed tools');
    expect(q.row(long).getAttribute('title')).toBe(long);
  });

  it('the visible "on" mark is a glyph, not a hue — WCAG 1.4.1', () => {
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
    expect(q.row('Sandbox commands').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sandbox commands').classList.contains('selected')).toBe(true);
  });

  it('toggling an ON switch sends off', () => {
    q.enter('Security');
    q.row('Block credential files').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'blockCredentials', on: false }]);
  });

  it('choosing a radio unchecks its group on screen and sends exactly ONE message', () => {
    q.enter('Model');
    q.row('Sonnet').click();
    expect(q.toggles()).toEqual([{ type: 'settingsToggle', key: 'model:sonnet', on: true }]);
    expect(q.toggles().filter((m) => m.on === false)).toEqual([]);
    expect(q.row('Sonnet').getAttribute('aria-checked')).toBe('true');
    expect(q.row('Sonnet').classList.contains('selected')).toBe(true);
    expect(q.row('Opus 5').getAttribute('aria-checked')).toBe('false');
    expect(q.row('Opus 5').classList.contains('selected')).toBe(false);
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
    expect(q.title()).toBe('Model');
    q.back().click();
    q.enter('Effort');
    q.row('Low').click();
    expect(q.toggles().length).toBe(2);
    expect(q.menu()).toBeTruthy();
  });

  it('announces the change, because nothing else does', () => {
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
    expect(q.menu()).toBeNull();
    q.gear().click();
    expect(q.all().length).toBe(5);
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
    q.win.cc.settingsMenu(payload());
    q.gear().click();
    const before = q.all();
    q.win.cc.settingsMenu(payload());
    const after = q.all();
    expect(after[0]).toBe(before[0]);
    expect(after[after.length - 1]).toBe(before[before.length - 1]);
  });

  it('a push that changes only `type` DOES rebuild, or a choice would stay painted as a switch', () => {
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

  function ruleBody(selector) {
    const at = css.indexOf(selector + ' {');
    if (at < 0) throw new Error('no rule for ' + selector);
    return css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  }

  it('the gear’s place in the row is structural, never a CSS reorder', () => {
    expect(ruleBody('.composer-views .settings-btn')).not.toMatch(/\border\s*:/);
    expect(ruleBody('.composer-views .settings-btn')).not.toMatch(/flex-direction/);
  });

  it('the gear is flat and divided like the rest of the row, not a rounded action icon', () => {
    const rule = ruleBody('.composer-views .settings-btn');
    expect(rule).toMatch(/border-radius:\s*0/);
    expect(rule).toMatch(/border-right:\s*1px solid var\(--border\)/);
  });

  it('the open state is drawn from aria-expanded, so there is only one copy of the fact', () => {
    expect(css).toMatch(/\.composer-views \.settings-btn\[aria-expanded='true'\]/);
  });

  it('the button rows do not out-order the .menu-item colour that marks a row as on', () => {
    const rule = ruleBody('.settings-item');
    expect(rule).toMatch(/background:\s*transparent/);
    expect(rule).toMatch(/text-align:\s*left/);
    expect(rule).not.toMatch(/\bcolor\s*:/);
    expect(rule).not.toMatch(/font-size\s*:/);
  });

  it('the deferred note is a rule of its own and cannot be squeezed out by the caret', () => {
    expect(ruleBody('.settings-defer')).toMatch(/margin-left:\s*auto/);
    expect(ruleBody('.settings-defer + .menu-group-caret')).toMatch(/margin-left:\s*0/);
  });

  it('the row label is what is capped, not the panel', () => {
    const rule = ruleBody('.settings-menu .menu-item-label');
    expect(rule).toMatch(/max-width:\s*\d+px/);
    expect(rule).toMatch(/text-overflow:\s*ellipsis/);
  });

  it('reuses the drill-down classes for the second level, adding no CSS of its own', () => {
    expect(css).not.toMatch(/\.settings-sub\b/);
    expect(css).not.toMatch(/\.settings-subgroup\b/);
  });

  it('leaves no rule behind for markup the menu stopped emitting', () => {
    expect(css).not.toMatch(/\.settings-menu \.menu-group\b/);
    expect(css).not.toMatch(/\.settings-menu \.menu-group-header\b/);
    expect(css).not.toMatch(/\.settings-section \{/);
  });
});
