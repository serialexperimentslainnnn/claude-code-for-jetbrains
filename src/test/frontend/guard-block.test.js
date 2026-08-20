const { loadFrontend, readCss } = require('./helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

const DURATIONS = [
  ['5m', '5 minutes'],
  ['15m', '15 minutes'],
  ['30m', '30 minutes'],
  ['4h', '4 hours'],
  ['8h', '8 hours'],
  ['ide', 'Until IDE closes'],
  ['forever', 'Forever'],
];

function blockRow(win, rule = 'DESTRUCTIVE_IAC', extra = {}) {
  win.cc.batch([
    row(1, 0, 'SYSTEM', 'Blocked Bash: it runs a destructive command.', { blockedRule: rule, ...extra }),
  ]);
  return document.querySelector('.notice.guard-block');
}

describe('a guard block carries the control that can open the rule', () => {
  it('draws the Disable rule link on a block', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win);

    expect(block).toBeTruthy();
    expect(block.querySelector('.guard-disable-link').textContent).toBe('Disable rule');
  });

  it('draws nothing on an ordinary system notice', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(1, 0, 'SYSTEM', 'Session resumed.')]);

    expect(document.querySelector('.notice.guard-block')).toBeNull();
    expect(document.querySelector('.guard-disable-link')).toBeNull();
  });

  it('offers exactly the seven durations, in order', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win);

    const labels = [...block.querySelectorAll('.guard-disable-option')].map((b) => b.textContent);
    expect(labels).toEqual(DURATIONS.map(([, label]) => label));
  });

  it('starts closed, and opening it sends nothing', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');
    const menu = block.querySelector('.guard-disable-menu');

    expect(menu.hasAttribute('hidden')).toBe(true);
    expect(link.getAttribute('aria-expanded')).toBe('false');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(menu.hasAttribute('hidden')).toBe(false);
    expect(link.getAttribute('aria-expanded')).toBe('true');
    expect(sent).toEqual([]);
  });

  it('sends the chosen duration for the rule the block was drawn for', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const block = blockRow(win, 'REVERSE_SHELL');

    block.querySelector('.guard-disable-link').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    const options = [...document.querySelectorAll('.guard-disable-option')];
    options[2].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardSuspend', rule: 'REVERSE_SHELL', duration: '30m' }]);
  });

  it('closes itself once a duration is chosen', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    document
      .querySelector('.guard-disable-option')
      .dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(block.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
    expect(link.getAttribute('aria-expanded')).toBe('false');
  });

  it('while open it hangs off the body, not off the scrolled conversation', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');
    const menu = block.querySelector('.guard-disable-menu');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(menu.parentNode).toBe(document.body);
    expect(document.getElementById('conversation').contains(menu)).toBe(false);

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(menu.parentNode.className).toBe('guard-block-actions');
    expect(block.contains(menu)).toBe(true);
  });

  it('a cleared transcript takes the open menu with it', async () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);

    block.querySelector('.guard-disable-link').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    expect(document.body.querySelector(':scope > .guard-disable-menu')).toBeTruthy();

    win.cc.clear();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(document.body.querySelector(':scope > .guard-disable-menu')).toBeNull();
  });

  it('can be dismissed with Escape', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');
    const menu = block.querySelector('.guard-disable-menu');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    menu.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(menu.hasAttribute('hidden')).toBe(true);
  });

  it('Escape reaches it even while the focus never left the link', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    link.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(block.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
    expect(link.getAttribute('aria-expanded')).toBe('false');
  });

  it('a click anywhere else dismisses it', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    document.body.dispatchEvent(new win.MouseEvent('mousedown', { bubbles: true }));

    expect(block.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
  });

  it('scrolling the conversation away dismisses it', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);

    block.querySelector('.guard-disable-link').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    document.getElementById('conversation').dispatchEvent(new win.Event('scroll', { bubbles: false }));

    expect(block.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
  });

  it('is placed against the link on every opening, not left where the last one was', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');
    const menu = block.querySelector('.guard-disable-menu');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(menu.style.left).not.toBe('');
    expect(menu.style.top).not.toBe('');
  });

  it('floats over the conversation instead of being clipped by it', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = css.slice(css.indexOf('.guard-disable-menu {'), css.indexOf('.guard-disable-menu[hidden]'));

    expect(block).toMatch(/position:\s*fixed/);
  });

  it('paints the options in a colour the stylesheet actually defines', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const block = css.slice(
      css.indexOf('.guard-disable-option {'),
      css.indexOf('}', css.indexOf('.guard-disable-option {'))
    );
    const used = [...block.matchAll(/var\((--[\w-]+)\)/g)].map((m) => m[1]);

    expect(used.length).toBeGreaterThan(0);
    used.forEach((name) => expect(css).toMatch(new RegExp(`${name}:`)));
  });

  it('is a button, not a link — it acts rather than navigates', () => {
    const win = loadFrontend(['app-transcript.js']);
    const link = blockRow(win).querySelector('.guard-disable-link');

    expect(link.tagName).toBe('BUTTON');
    expect(link.getAttribute('type')).toBe('button');
    expect(link.getAttribute('aria-haspopup')).toBe('menu');
  });

  it('the hidden menu really is hidden — the attribute has to beat display:flex', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const at = css.indexOf('.guard-disable-menu[hidden]');

    expect(at).toBeGreaterThan(-1);
    expect(css.slice(at, css.indexOf('}', at))).toMatch(/display:\s*none/);
    expect(at).toBeGreaterThan(css.indexOf('.guard-disable-menu {'));
  });
});

describe('a guard block can also put the command on the whitelist', () => {
  it('offers the link when the block names a command', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win, 'DESTRUCTIVE_IAC', { command: 'terraform destroy' });

    expect(block.querySelector('.guard-whitelist-link').textContent).toBe('Whitelist Command');
  });

  it('offers nothing to whitelist when the block names no command', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win, 'CREDENTIALS');

    expect(block.querySelector('.guard-whitelist-link')).toBeNull();
  });

  it('sends the exact command and the rule that blocked it', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const block = blockRow(win, 'DESTRUCTIVE_GIT', { command: 'git push --force' });

    block
      .querySelector('.guard-whitelist-link')
      .dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardWhitelist', rule: 'DESTRUCTIVE_GIT', command: 'git push --force' }]);
  });

  it('is a button, not a link — an anchor would be swallowed by the link router', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win, 'DESTRUCTIVE_IAC', { command: 'terraform destroy' });
    const link = block.querySelector('.guard-whitelist-link');

    expect(link.tagName).toBe('BUTTON');
    expect(link.getAttribute('type')).toBe('button');
  });

  it('sits beside Disable rule rather than replacing it', () => {
    const win = loadFrontend(['app-transcript.js']);
    const block = blockRow(win, 'DESTRUCTIVE_IAC', { command: 'terraform destroy' });
    const actions = block.querySelector('.guard-block-actions');

    expect(actions.querySelector('.guard-disable-link')).toBeTruthy();
    expect(actions.querySelector('.guard-whitelist-link')).toBeTruthy();
  });
});

describe('a bypass is a warning, not a silence', () => {
  function bypassRow(win, rule = 'DESTRUCTIVE_IAC', extra = {}) {
    win.cc.batch([
      row(
        1,
        0,
        'SYSTEM',
        'Allowed Bash: Block infrastructure teardown matched — it runs a destructive operation.',
        {
          bypassedRule: rule,
          ...extra,
        }
      ),
    ]);
    return document.querySelector('.notice.guard-bypass');
  }

  it('draws a warning row when a rule matched and the call ran anyway', () => {
    const win = loadFrontend(['app-transcript.js']);
    const notice = bypassRow(win);

    expect(notice).toBeTruthy();
    expect(notice.textContent).toContain('runs a destructive operation');
  });

  it('offers to put the guard back on when that is why the call ran', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const notice = bypassRow(win, 'TEMP_DIR', { bypassAction: 'enableGuard' });
    const link = notice.querySelector('.guard-whitelist-link');

    expect(link.textContent).toBe('Enable Sensitive Guard');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardMaster', on: true, duration: '', rule: 'TEMP_DIR' }]);
  });

  it('offers to withdraw the authorisation when that is why the call ran', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    const notice = bypassRow(win, 'DESTRUCTIVE_IAC', {
      bypassAction: 'revokeApproval',
      command: 'terraform destroy',
    });
    const link = notice.querySelector('.guard-whitelist-link');

    expect(link.textContent).toBe('Disable this authorization');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([
      { type: 'guardRevokeApproval', rule: 'DESTRUCTIVE_IAC', command: 'terraform destroy' },
    ]);
  });

  it('offers nothing when there is nothing left standing to undo', () => {
    const win = loadFrontend(['app-transcript.js']);
    const notice = bypassRow(win);

    expect(
      notice.querySelector('.guard-whitelist-link'),
      'a card answered once is over, and a whitelist entry belongs on its own page'
    ).toBeNull();
  });

  it('is not the red block row — nothing was stopped', () => {
    const win = loadFrontend(['app-transcript.js']);
    bypassRow(win);

    expect(document.querySelector('.notice.guard-block')).toBeNull();
    expect(document.querySelector('.guard-disable-link')).toBeNull();
  });

  it('an ordinary system notice is still an ordinary system notice', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(1, 0, 'SYSTEM', 'Session resumed.')]);

    expect(document.querySelector('.notice.guard-bypass')).toBeNull();
  });

  it('is painted in the warning colour the stylesheet defines, not the danger one', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    const at = css.indexOf('.notice.guard-bypass');
    const rule = css.slice(at, css.indexOf('}', at));

    expect(at).toBeGreaterThan(-1);
    expect(rule).toContain('var(--warning)');
    expect(rule).not.toContain('var(--danger)');
    expect(css).toMatch(/--warning:\s*#/);
  });
});
