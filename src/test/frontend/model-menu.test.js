const { loadFrontend } = require('./helpers/load');

function state(modelOptions) {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [] },
    model: { label: 'Opus 5', options: modelOptions },
    mode: { wire: 'default', label: 'Default', options: [] },
    effort: { label: 'Default', options: [] },
    thinking: { on: true, options: [] },
    queue: [],
  };
}

const CURRENT = [
  { value: 'opus[1m]', label: 'Opus 5 with 1M context', selected: true },
  { value: 'sonnet', label: 'Sonnet 5', selected: false },
];
const LEGACY = [
  { value: 'claude-opus-4-7', label: 'Opus 4.7', selected: false, group: 'other' },
  { value: 'claude-3-5-sonnet', label: 'Sonnet 3.5', selected: false, group: 'other' },
];

describe('composer — the model menu groups older models', () => {
  let win;

  function openModelMenu() {
    const pill =
      win.document.querySelector('.pill-model') || win.document.querySelector('[data-pill="model"]');
    if (pill) pill.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    return win.document.querySelector('.menu');
  }

  beforeEach(() => {
    win = loadFrontend(['app-composer.js']);
  });

  test('current models stay in the flat list and older ones move into the group', () => {
    win.cc.state(state(CURRENT.concat(LEGACY)));
    const menu = openModelMenu();
    expect(menu).not.toBeNull();
    const group = menu.querySelector('.menu-group');
    expect(group).not.toBeNull();
    expect(group.querySelector('.menu-group-header').textContent).toContain('Other models');
    expect(menu.querySelectorAll(':scope > .menu-item').length).toBe(CURRENT.length);
    expect(group.querySelectorAll('.menu-group-items .menu-item').length).toBe(LEGACY.length);
  });

  test('the group starts collapsed, and open when the selected model is inside it', () => {
    win.cc.state(state(CURRENT.concat(LEGACY)));
    let menu = openModelMenu();
    expect(menu).not.toBeNull();
    expect(menu.querySelector('.menu-group').classList.contains('open')).toBe(false);

    const selectedLegacy = [
      { value: 'opus[1m]', label: 'Opus 5 with 1M context', selected: false },
      { value: 'claude-opus-4-7', label: 'Opus 4.7', selected: true, group: 'other' },
    ];
    win = loadFrontend(['app-composer.js']);
    win.cc.state(state(selectedLegacy));
    menu = openModelMenu();
    expect(menu).not.toBeNull();
    expect(menu.querySelector('.menu-group').classList.contains('open')).toBe(true);
  });

  test('a menu with no tagged options renders exactly as before', () => {
    win.cc.state(state(CURRENT));
    const menu = openModelMenu();
    expect(menu).not.toBeNull();
    expect(menu.querySelector('.menu-group')).toBeNull();
    expect(menu.querySelectorAll('.menu-item').length).toBe(CURRENT.length);
  });
});
