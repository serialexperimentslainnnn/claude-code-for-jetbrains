const { loadFrontend } = require('../helpers/load');

const payload = {
  model: 'opus[1m]',
  cwd: '/home/dev/project',
  home: '/home/dev',
  account: { email: 'dev@example.com', org: 'Example', plan: 'Max', provider: 'Anthropic' },
};

describe('the session mini grid under the prompt', () => {
  it('is fed by the shared event bus, not by a trap on cc.session', () => {
    const win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.state({ running: true, starting: false });

    const descriptor = Object.getOwnPropertyDescriptor(win.cc, 'session');
    expect(typeof descriptor.value).toBe('function');
    expect(descriptor.get).toBeUndefined();

    win.cc.session(payload);
    const mini = win.document.querySelector('.dash-mini');
    expect(mini.hasAttribute('hidden')).toBe(false);
    expect(mini.textContent).toContain('opus[1m]');
  });

  it('a host that reassigns cc.session later still reaches the grid', () => {
    const win = loadFrontend(['app-composer.js', 'app-session.js'], { vendor: false });
    win.cc.state({ running: true, starting: false });
    const original = win.cc.session;
    win.cc.session = function (p) {
      original(p);
    };

    win.cc.session(payload);
    expect(win.document.querySelector('.dash-mini').textContent).toContain('dev@example.com');
  });
});
