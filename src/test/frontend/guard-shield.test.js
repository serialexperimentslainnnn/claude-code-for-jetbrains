const { loadFrontend } = require('./helpers/load');

const DURATION_LABELS = [
  '5 minutes',
  '15 minutes',
  '30 minutes',
  '4 hours',
  '8 hours',
  'Until IDE closes',
  'Forever',
];

function state(extra = {}) {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    guardOn: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [{ id: 'anthropic', label: 'Anthropic' }] },
    model: {
      label: 'Opus 5 with 1M context',
      options: [{ value: 'opus[1m]', label: 'Opus 5', selected: true }],
    },
    mode: {
      wire: 'default',
      label: 'Default',
      options: [{ wire: 'default', label: 'Default' }],
    },
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
  const shield = document.querySelector('.bar-right [aria-label="Sensitive Guard"]');
  return { win, sent, shield };
}

describe('the shield says what is protecting the machine, and can stand it down', () => {
  it('sits immediately to the left of auto-scroll', () => {
    const { shield } = mount();

    expect(shield).toBeTruthy();
    expect(shield.nextElementSibling.getAttribute('aria-label')).toBe('Auto-follow scrolling');
  });

  it('is drawn from the host, not from a click', () => {
    const { win, shield } = mount();

    expect(shield.classList.contains('active')).toBe(true);

    win.cc.state(state({ guardOn: false }));

    expect(
      shield.classList.contains('active'),
      'the page must paint what it is told, never what it assumed it did'
    ).toBe(false);
  });

  it('says which way the click goes, in the tooltip', () => {
    const { win, shield } = mount();

    expect(shield.title).toContain('on');

    win.cc.state(state({ guardOn: false }));

    expect(shield.title).toContain('OFF');
  });

  it('asks for how long before standing down, and sends nothing until answered', () => {
    const { win, sent, shield } = mount();

    shield.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    const options = [...document.querySelectorAll('.guard-disable-option')];
    expect(options.map((o) => o.textContent)).toEqual(DURATION_LABELS);
    expect(sent, 'opening the menu commits to nothing').toEqual([]);
  });

  it('sends the duration that was chosen', () => {
    const { win, sent, shield } = mount();

    shield.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
    document
      .querySelectorAll('.guard-disable-option')[1]
      .dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardMaster', on: false, duration: '15m' }]);
  });

  it('switching it back on is one click and asks nothing', () => {
    const { win, sent, shield } = mount({ guardOn: false });

    shield.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

    expect(sent).toEqual([{ type: 'guardMaster', on: true, duration: '' }]);
    expect(document.querySelector('.guard-disable-menu').hasAttribute('hidden')).toBe(true);
  });

});
