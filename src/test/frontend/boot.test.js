// The boot screen: it must appear while the binary launches, and — more importantly — must always come down.
//
// It covers the whole tab and blocks input, so a stuck boot screen is a worse failure than the empty composer
// it exists to hide. These pin the three-state logic (running / starting / neither) and the fact that it is
// driven outside the composer's ensureBuilt() gate.
const { loadFrontend } = require('./helpers/load');

describe('boot screen', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const boot = () => win.document.getElementById('boot');
  const app = () => win.document.getElementById('app');

  it('is declared in the static shell and starts visible', () => {
    // Visible by DEFAULT, before any state arrives: the page loads before the process is up, so "waiting" is
    // the honest initial state. Starting hidden would flash a live-looking composer on every new tab.
    expect(boot()).toBeTruthy();
    expect(boot().hidden).toBe(false);
  });

  it('stays up while the session is starting, and blocks input', () => {
    win.cc.state({ starting: true, running: false });
    expect(boot().hidden).toBe(false);
    expect(app().classList.contains('booting')).toBe(true);
  });

  it('comes down once the process is running', () => {
    win.cc.state({ starting: true, running: false });
    win.cc.state({ starting: false, running: true });
    expect(boot().hidden).toBe(true);
    expect(app().classList.contains('booting')).toBe(false);
  });

  it('comes down when the launch FAILED — neither starting nor running', () => {
    // The regression that matters. A missing binary, a declined trust prompt or a refused remote-mount project
    // all end with both flags false. If that did not clear the screen, the tab would stay covered forever with
    // no way to reach the notification explaining why.
    win.cc.state({ starting: true, running: false });
    win.cc.state({ starting: false, running: false });
    expect(boot().hidden).toBe(true);
  });

  it('distinguishes resuming from a cold start', () => {
    win.cc.state({ starting: true, running: false, resuming: false });
    expect(win.document.getElementById('boot-sub').textContent).toBe('Starting the agent');
    win.cc.state({ starting: true, running: false, resuming: true });
    expect(win.document.getElementById('boot-sub').textContent).toBe('Resuming your session');
  });

  it('the dots are aria-hidden so the phrase is announced once, not re-read per frame', () => {
    const dots = win.document.querySelector('.boot-dots');
    expect(dots).toBeTruthy();
    expect(dots.getAttribute('aria-hidden')).toBe('true');
    // The dots live in ::after, not in the text node, so the accessible name is the stable phrase.
    expect(dots.textContent).toBe('');
    expect(win.document.querySelector('.boot-title').textContent.trim()).toBe('Loading Claude Code');
  });

  it('announces the wait to assistive technology', () => {
    const region = win.document.getElementById('a11y-status');
    win.cc.state({ starting: true, running: false });
    expect(region.textContent).toContain('Loading Claude Code');
  });
});
