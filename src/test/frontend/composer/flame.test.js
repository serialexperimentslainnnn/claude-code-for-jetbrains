const { loadFrontend } = require('../helpers/load');

function state(extra = {}) {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    guardOn: true,
    godModeOn: false,
    provider: { id: 'anthropic', label: 'Anthropic', options: [{ id: 'anthropic', label: 'Anthropic' }] },
    model: { label: 'Opus 5', options: [{ value: 'opus[1m]', label: 'Opus 5', selected: true }] },
    mode: { wire: 'default', label: 'Default', options: [{ wire: 'default', label: 'Default' }] },
    effort: { label: 'High', options: [{ value: 'high', label: 'High', selected: true }] },
    thinking: { on: true, label: 'Thinking on', options: [{ on: false, label: 'Off' }] },
    queue: [],
    ...extra,
  };
}

function mount(extra) {
  const win = loadFrontend(['app-composer.js']);
  const sent = [];
  win.CC.send = (m) => sent.push(m);
  win.CC.composer.send = (m) => sent.push(m);
  win.cc.state(state(extra));
  const flame = document.querySelector('.bar-right button[aria-label="Claude God Mode"]');
  return { win, sent, flame };
}

function click(win, el) {
  el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
}

function menuItems() {
  return Array.from(document.querySelectorAll('.god-mode-menu:not([hidden]) [role="menuitem"]'));
}

describe('the flame says whether Claude God Mode is on', () => {
  it('stands right of the Remote Control phone', () => {
    const { flame } = mount();
    expect(flame).toBeTruthy();
    expect(flame.tagName).toBe('BUTTON');
    expect(flame.type).toBe('button');
    expect(flame.previousElementSibling.getAttribute('aria-label')).toBe('Remote Control');
  });

  it('is drawn inline, from nothing fetched', () => {
    const { flame } = mount();
    const svg = flame.querySelector('svg');
    expect(svg).toBeTruthy();
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(flame.innerHTML).not.toMatch(/url\(|<image|href/);
  });

  it('is unlit and says so while God Mode is off', () => {
    const { flame } = mount();
    expect(flame.classList.contains('active')).toBe(false);
    expect(flame.title).toContain('off');
  });

  it('lights from the host and goes out again', () => {
    const { win, flame } = mount();
    const unlit = flame.innerHTML;

    win.cc.state(state({ godModeOn: true }));
    expect(flame.classList.contains('active')).toBe(true);
    expect(flame.title).toContain('on');
    expect(flame.innerHTML).not.toBe(unlit);

    win.cc.state(state({ godModeOn: false }));
    expect(flame.classList.contains('active')).toBe(false);
    expect(flame.innerHTML).toBe(unlit);
  });

  it('a click opens a menu of two: switch God Mode, and configure it', () => {
    const { win, flame } = mount();
    click(win, flame);

    expect(menuItems().map((b) => b.textContent)).toEqual(['Turn God Mode on', 'Configure God Mode']);
    expect(flame.getAttribute('aria-expanded')).toBe('true');
  });

  it('the switch asks the host for the godMode flag, and reads off when it is on', () => {
    const { win, sent, flame } = mount();
    click(win, flame);
    click(win, menuItems()[0]);

    expect(sent).toEqual([{ type: 'settingsToggle', key: 'godMode', on: true }]);
    expect(menuItems()).toEqual([]);

    win.cc.state(state({ godModeOn: true }));
    click(win, flame);
    expect(menuItems()[0].textContent).toBe('Turn God Mode off');
    click(win, menuItems()[0]);
    expect(sent[1]).toEqual({ type: 'settingsToggle', key: 'godMode', on: false });
  });

  it('configure opens the ⚙ menu on the Claude God Mode group, not a menu of its own', () => {
    const { win, flame } = mount();
    win.cc.settingsMenu({
      items: [
        { key: 'model:opus[1m]', group: 'Model', label: 'Opus 5', on: true, type: 'radio' },
        { key: 'godMode', group: 'Claude God Mode', label: 'Claude becomes one with your IDE', on: false },
      ],
    });

    click(win, flame);
    click(win, menuItems()[1]);

    const menu = document.querySelector('.settings-menu');
    expect(menu).toBeTruthy();
    expect(menu.querySelector('.attach-title').textContent).toBe('Claude God Mode');
    expect(document.querySelectorAll('.settings-menu').length).toBe(1);
    expect(menuItems()).toEqual([]);
  });
});
