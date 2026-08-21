const { loadFrontend } = require('./helpers/load');

const ENTRY = (over = {}) => ({
  id: '1|CREDENTIALS|DENIED|tu_1',
  tab: 'blocked',
  at: 1700000000000,
  verdict: 'DENIED',
  verdictLabel: 'Refused',
  rule: 'CREDENTIALS',
  ruleLabel: 'Block credential files',
  category: 'Sensitive data',
  explainable: true,
  tool: 'Bash',
  detail: 'reads a credential file',
  command: 'cat ~/.ssh/id_ed25519',
  ...over,
});

const PAYLOAD = (over = {}) => ({
  recording: true,
  window: { kept: 3, max: 500, recorded: 3, dropped: 0, missing: 0 },
  tabs: [
    { id: 'blocked', label: 'Blocked', count: 2 },
    { id: 'allowed', label: 'Allowed', count: 1 },
    { id: 'whitelisted', label: 'Whitelisted', count: 0 },
    { id: 'disabled', label: 'Disabled', count: 0 },
  ],
  entries: [
    ENTRY(),
    ENTRY({
      id: 'b2',
      at: 1700000001000,
      rule: 'DESTRUCTIVE_GIT',
      ruleLabel: 'Block destructive Git',
      command: 'git push --force',
    }),
    ENTRY({
      id: 'a1',
      tab: 'allowed',
      verdict: 'ALLOWED',
      verdictLabel: 'Allowed',
      explainable: false,
      command: 'ls',
      viaLabel: 'Approved in this chat',
    }),
  ],
  ...over,
});

describe('the Guard view', () => {
  let win;
  let sent;

  const panel = () => win.document.querySelector('.dashboard');
  const openView = (name) => {
    const btn = win.document.querySelector('.dash-toggle[data-view="' + name + '"]');
    btn.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return btn;
  };
  const tabs = () => Array.from(panel().querySelectorAll('.guard-tab'));
  const entries = () => Array.from(panel().querySelectorAll('.guard-entry'));
  const rules = () => entries().map((e) => e.querySelector('.guard-rule').textContent);
  const alarms = () => Array.from(panel().querySelectorAll('.guard-alarm')).map((e) => e.textContent);

  beforeEach(() => {
    win = loadFrontend(['app-session.js', 'app-composer.js'], { vendor: false });
    sent = [];
    win.__ccSend = (json) => sent.push(JSON.parse(json));
  });

  it('has a button of its own in the views row, always, and it is not hidden behind a precondition', () => {
    const btn = win.document.querySelector('.dash-toggle[data-view="guard"]');
    expect(btn).not.toBeNull();
    expect(btn.hidden).toBe(false);
    expect(btn.textContent).toBe('Guard');
  });

  it('opening it asks the host for the log — the page never guesses at the store', () => {
    openView('guard');
    expect(sent.filter((m) => m.type === 'guardLog').length).toBe(1);
  });

  it('asks once per visit, not once per repaint', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    win.cc.guard(PAYLOAD());
    expect(sent.filter((m) => m.type === 'guardLog').length).toBe(1);

    openView('session');
    openView('guard');
    expect(sent.filter((m) => m.type === 'guardLog').length).toBe(2);
  });

  it('cc.openGuardView goes straight there, which is how the host and a block notice open it', () => {
    win.cc.openGuardView();
    expect(panel().hasAttribute('hidden')).toBe(false);
    expect(win.document.querySelector('.dash-toggle[data-view="guard"]').getAttribute('aria-current')).toBe(
      'true'
    );
  });

  it('draws the four tabs the host sent, in the order it sent them, with their counts', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    expect(tabs().map((t) => t.textContent)).toEqual([
      'Blocked (2)',
      'Allowed (1)',
      'Whitelisted (0)',
      'Disabled (0)',
    ]);
  });

  it('shows one tab at a time, and switching tab switches the entries', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    expect(rules()).toEqual(['Block credential files', 'Block destructive Git']);

    tabs()[1].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(rules()).toEqual(['Block credential files']);
    expect(entries()[0].textContent).toContain('Allowed');
  });

  it('says nothing landed here rather than drawing an empty list', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    tabs()[2].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(entries()).toEqual([]);
    expect(panel().querySelector('.guard-empty')).not.toBeNull();
  });

  it('states the window it is showing, and that the ring is per project rather than per chat', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    const note = panel().querySelector('.guard-note').textContent;
    expect(note).toContain('3');
    expect(note).toContain('500');
    expect(note).toMatch(/project/);
  });

  it('SAYS SO when the store is not taking alerts — a security log that loses entries quietly is worse than none', () => {
    openView('guard');
    win.cc.guard(PAYLOAD({ recording: false }));

    const alarm = panel().querySelector('.guard-alarm');
    expect(alarm).not.toBeNull();
    expect(alarm.getAttribute('role')).toBe('alert');
    expect(alarm.textContent).toContain('NOT');
  });

  it('keeps that alarm on screen in every tab, because it is true in every tab', () => {
    openView('guard');
    win.cc.guard(PAYLOAD({ recording: false }));
    tabs()[3].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(panel().querySelector('.guard-alarm')).not.toBeNull();
  });

  it('counts what was dropped on the way to the store, and what is no longer in the list', () => {
    openView('guard');
    win.cc.guard(PAYLOAD({ window: { kept: 1, max: 500, recorded: 9, dropped: 4, missing: 4 } }));

    expect(alarms().join(' ')).toContain('4');
    expect(
      Array.from(panel().querySelectorAll('.guard-note'))
        .map((e) => e.textContent)
        .join(' ')
    ).toContain('are not in this list');
  });

  it('draws no alarm when nothing was lost', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    expect(alarms()).toEqual([]);
  });

  it('treats the logged command as text — it is model output and it can contain anything', () => {
    const nasty = '</code></pre><img src=x onerror="window.__pwned=1">';
    openView('guard');
    win.cc.guard(PAYLOAD({ entries: [ENTRY({ command: nasty })] }));

    const block = panel().querySelector('.guard-cmd code');
    expect(block.textContent).toBe(nasty);
    expect(panel().querySelector('img')).toBeNull();
    expect(win.__pwned).toBeUndefined();
  });

  it('escapes the rule and the detail too, not just the command', () => {
    openView('guard');
    win.cc.guard(PAYLOAD({ entries: [ENTRY({ ruleLabel: '<b>rule</b>', detail: '<i>matched</i>' })] }));
    expect(panel().querySelector('.guard-rule').textContent).toBe('<b>rule</b>');
    expect(panel().querySelectorAll('.guard-rule b').length).toBe(0);
  });

  it('offers to ask Claude about a blocked entry, and sends the id the host gave it', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    const ask = entries()[0].querySelector('.guard-ask');
    expect(ask.tagName).toBe('BUTTON');
    ask.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent.pop()).toEqual({ type: 'guardExplain', id: '1|CREDENTIALS|DENIED|tu_1' });
  });

  it('offers nothing to ask about an entry the host did not mark explainable', () => {
    openView('guard');
    win.cc.guard(PAYLOAD());
    tabs()[1].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(entries()[0].querySelector('.guard-ask')).toBeNull();
  });

  it('paints the verdict word the host sent and derives none of its own', () => {
    openView('guard');
    win.cc.guard(PAYLOAD({ entries: [ENTRY({ verdict: 'ASKED', verdictLabel: 'Asked you' })] }));
    const badge = panel().querySelector('.guard-verdict');
    expect(badge.textContent).toBe('Asked you');
    expect(badge.classList.contains('guard-asked')).toBe(true);
  });

  it('says it is still reading rather than reporting a window of zero it was never told', () => {
    openView('guard');
    expect(panel().textContent).toContain('Reading the guard log');
    expect(panel().textContent).not.toContain('last 0');
  });
});

describe('a guard block in the transcript is a door onto the log', () => {
  it('carries a control that opens the Guard view', () => {
    const win = loadFrontend(['app-transcript.js', 'app-session.js', 'app-composer.js'], {
      vendor: false,
    });
    win.cc.batch([
      {
        id: 1,
        order: 0,
        speaker: 'SYSTEM',
        text: 'Blocked Bash: it reads a credential file.',
        state: 'FINISHED',
        elapsed: 0,
        blockedRule: 'CREDENTIALS',
      },
    ]);

    const link = win.document.querySelector('.notice.guard-block .guard-log-link');
    expect(link).not.toBeNull();
    expect(link.tagName).toBe('BUTTON');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(win.document.querySelector('.dashboard').hasAttribute('hidden')).toBe(false);
    expect(win.document.querySelector('.dash-toggle[data-view="guard"]').getAttribute('aria-current')).toBe(
      'true'
    );
  });

  it('so does a bypass warning, which is the other half of the same log', () => {
    const win = loadFrontend(['app-transcript.js', 'app-session.js', 'app-composer.js'], {
      vendor: false,
    });
    win.cc.batch([
      {
        id: 1,
        order: 0,
        speaker: 'SYSTEM',
        text: 'A rule matched and the call ran anyway.',
        state: 'FINISHED',
        elapsed: 0,
        bypassedRule: 'CREDENTIALS',
      },
    ]);

    expect(win.document.querySelector('.notice.guard-bypass .guard-log-link')).not.toBeNull();
  });
});
