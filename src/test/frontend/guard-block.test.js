const { loadFrontend, readCss } = require('./helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

const DURATIONS = [
  ['5m', '5 minutes'],
  ['15m', '15 minutes'],
  ['30m', '30 minutes'],
  ['4h', '4 hour'],
  ['8h', '8 hour'],
  ['ide', 'Until IDE closes'],
  ['forever', 'Forever'],
];

function blockRow(win, rule = 'DESTRUCTIVE_IAC') {
  win.cc.batch([row(1, 0, 'SYSTEM', 'Blocked Bash: it runs a destructive command.', { blockedRule: rule })]);
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
    const options = [...block.querySelectorAll('.guard-disable-option')];
    options[2].dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardSuspend', rule: 'REVERSE_SHELL', duration: '30m' }]);
  });

  it('closes itself once a duration is chosen', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    const block = blockRow(win);
    const link = block.querySelector('.guard-disable-link');

    link.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    block
      .querySelector('.guard-disable-option')
      .dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(block.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
    expect(link.getAttribute('aria-expanded')).toBe('false');
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
