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

  it('stays up when the launch FAILED — the chat is not reachable without a session', () => {
    // The invariant, and it outranks the older reading of this case. A declined trust prompt or a refused
    // remote-mount project ends with both flags false; showing the chat then hands the user a composer with
    // no process behind it, which is how the dashboard came up half-empty. The explaining notification is an
    // IDE toast outside this view, so it is visible either way.
    win.cc.state({ starting: true, running: false });
    win.cc.state({ starting: false, running: false });
    expect(boot().hidden).toBe(false);
  });

  it('a session that dies returns to the loading screen', () => {
    win.cc.state({ starting: false, running: true });
    expect(boot().hidden).toBe(true);
    win.cc.state({ starting: false, running: false });
    expect(boot().hidden).toBe(false);
  });

  it('the overlays cover the work area, never the chat tabs', () => {
    // A chat that is still starting used to hide the tab bar with it, so you could not switch to another
    // chat while one booted — and switching is exactly what you do while you wait. Both overlays live
    // inside #work (the transcript + composer), which is the containing block their `inset: 0` resolves
    // against; #tabsbar is a sibling ABOVE it.
    const work = win.document.getElementById('work');
    expect(work).not.toBe(null);
    expect(work.contains(boot())).toBe(true);
    expect(work.contains(win.document.getElementById('auth-card'))).toBe(true);
    expect(work.contains(win.document.getElementById('tabsbar'))).toBe(false);
  });

  it('the sign-in screen replaces the spinner instead of being covered by it', () => {
    // #boot is z-index 60 and the auth card 55, so "both up" means the user stares at a spinner while the
    // card they need is underneath it. Exactly one screen at a time.
    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(boot().hidden).toBe(true);
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
  });

  it('the sign-in card comes down once the session is running', () => {
    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
    win.cc.state({ starting: false, running: true, needsLogin: true });
    expect(win.document.getElementById('auth-card').hidden).toBe(true);
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

// The FOURTH boot state: no `claude` binary. The overlay stays up but swaps the spinner for the
// install/path card — a decision to make, not a wait to sit through.
describe('missing-binary card', () => {
  let win, sent;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.meta({
      installMethods: [
        {
          id: 'sh',
          label: 'Install via the official script',
          display: 'curl -fsSL https://claude.ai/install.sh | bash',
          shell: 'bash',
        },
        { id: 'apt', label: 'Install via apt', display: 'sudo apt install claude-code', shell: 'bash' },
      ],
    });
  });

  const card = () => win.document.getElementById('boot-missing');
  const missing = () => win.cc.state({ starting: false, running: false, binaryMissing: true });

  it('shows the card (and keeps the boot overlay up) when the binary is missing', () => {
    missing();
    expect(win.document.getElementById('boot').hidden).toBe(false);
    expect(card().hidden).toBe(false);
    expect(win.document.getElementById('boot').classList.contains('missing')).toBe(true);
  });

  it('renders one row per method: the button, the exact command, and its Copy', () => {
    missing();
    const rows = card().querySelectorAll('.boot-install');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.boot-install-btn').textContent).toBe('Install via the official script');
    expect(rows[0].querySelector('.boot-install-cmd').textContent).toContain('install.sh | bash');
    expect(rows[0].querySelector('.boot-install-hint-label').textContent).toContain('bash');
    expect(rows[1].querySelector('.boot-install-copy')).toBeTruthy();
  });

  it('clicking Install sends the method and flips the button to Installing…', () => {
    missing();
    const btn = card().querySelector('.boot-install-btn[data-method="apt"]');
    btn.click();
    expect(sent).toContainEqual({ type: 'installClaude', method: 'apt' });
    expect(btn.textContent).toBe('Installing…');
    expect(btn.getAttribute('aria-busy')).toBe('true');
  });

  it('Copy sends the exact command without running anything', () => {
    missing();
    card().querySelector('.boot-install-copy').click();
    expect(sent).toContainEqual({ type: 'copy', text: 'curl -fsSL https://claude.ai/install.sh | bash' });
    expect(sent.filter((m) => m.type === 'installClaude')).toEqual([]);
  });

  it('the path row submits and a host error resets the Installing state', () => {
    missing();
    const input = win.document.getElementById('boot-path');
    input.value = '/opt/claude/claude';
    win.document.getElementById('boot-path-use').click();
    expect(sent).toContainEqual({ type: 'setBinaryPath', path: '/opt/claude/claude' });

    card().querySelector('.boot-install-btn').click();
    win.cc.bootPathError('That runs, but it isn’t Claude Code.');
    expect(win.document.getElementById('boot-path-err').textContent).toContain('isn’t Claude Code');
    expect(card().querySelector('.boot-install-btn').getAttribute('aria-busy')).toBe('false');
  });

  it('the card comes down when the binary appears', () => {
    missing();
    win.cc.state({ starting: true, running: false, binaryMissing: false });
    expect(card().hidden).toBe(true);
    expect(win.document.getElementById('boot-sub').textContent).toBe('Starting the agent');
  });
});

// The sign-in card: raised by needsLogin (or proactively by the host), driven step by step by cc.authState.
describe('sign-in card', () => {
  let win, sent;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
  });

  const card = () => win.document.getElementById('auth-card');
  const step = (name) => card().querySelector('.auth-step[data-step="' + name + '"]');

  it('appears on needsLogin and on cc.showAuth, and the install card wins over it', () => {
    expect(card().hidden).toBe(true);
    win.cc.state({ running: false, needsLogin: true });
    expect(card().hidden).toBe(false);
    win.cc.state({ running: false, needsLogin: true, binaryMissing: true });
    expect(card().hidden).toBe(true); // signing in is meaningless without a binary
    // showAuth() is the post-install nudge, and it lands on a tab with no session yet — a running session
    // means the sign-in it would ask for has already happened.
    win.cc.state({ running: false, needsLogin: false });
    win.cc.showAuth();
    expect(card().hidden).toBe(false);
  });

  it('subscription click moves to waiting and asks the host to start the flow', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.document.getElementById('auth-sub').click();
    expect(sent).toContainEqual({ type: 'loginSubscription' });
    expect(step('waiting').hidden).toBe(false);
    expect(step('idle').hidden).toBe(true);
  });

  it('Console click starts the org sign-in — the route that mints its own API key', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.document.getElementById('auth-console').click();
    expect(sent).toContainEqual({ type: 'loginConsole' });
    expect(step('waiting').hidden).toBe(false);
  });

  it('the API key field is collapsed behind a disclosure — a button is the primary route now', () => {
    win.cc.state({ running: false, needsLogin: true });
    const fields = win.document.getElementById('auth-key-fields');
    const toggle = win.document.getElementById('auth-key-toggle');
    expect(fields.hidden).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    expect(fields.hidden).toBe(false);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    // Still fully wired once revealed: the key route must not become second-class by being hidden.
    const input = win.document.getElementById('auth-key');
    input.value = 'sk-ant-secret';
    win.document.getElementById('auth-key-use').click();
    expect(sent).toContainEqual({ type: 'useApiKey', key: 'sk-ant-secret' });
    expect(input.value).toBe('');

    toggle.click();
    expect(fields.hidden).toBe(true);
  });

  it('url and code events both land on the ONE browser step — the code is optional, not a stage', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.cc.authState({ step: 'url', url: 'https://claude.ai/oauth/authorize?x=1' });
    expect(step('browser').hidden).toBe(false);
    // The URL is never rendered as text — it is 200 characters of query string. Two buttons instead.
    expect(win.document.getElementById('auth-url')).toBeNull();
    expect(win.document.getElementById('auth-url-open').disabled).toBe(false);
    // The optional code field is already on this screen, before any code event arrives.
    expect(win.document.getElementById('auth-code')).toBeTruthy();
    expect(win.document.getElementById('auth-code-label').textContent).toContain('optional');
    win.document.getElementById('auth-url-copy').click();
    expect(sent).toContainEqual({ type: 'copy', text: 'https://claude.ai/oauth/authorize?x=1' });
    win.document.getElementById('auth-url-open').click();
    expect(sent).toContainEqual({ type: 'open', url: 'https://claude.ai/oauth/authorize?x=1' });

    // The binary asking for the code stays on the SAME step, re-framed — no screen jump.
    win.cc.authState({ step: 'code' });
    expect(step('browser').hidden).toBe(false);
    expect(win.document.getElementById('auth-code-label').textContent).toContain('authorization code');
  });

  it('the browser buttons are inert until the host supplies a URL, and a restart drops the old one', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.cc.authState({ step: 'waiting' });
    expect(win.document.getElementById('auth-url-open').disabled).toBe(true);

    win.cc.authState({ step: 'url', url: 'https://claude.ai/oauth/authorize?x=1' });
    expect(win.document.getElementById('auth-url-open').disabled).toBe(false);

    // Restarting the flow invalidates that URL — its consent page belongs to an attempt that is over.
    win.cc.authState({ step: 'waiting' });
    expect(win.document.getElementById('auth-url-open').disabled).toBe(true);
  });

  it('the code input submits and is CLEARED — a secret must not linger in the DOM', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.cc.authState({ step: 'code' });
    const input = win.document.getElementById('auth-code');
    input.value = 'AUTH-CODE-42';
    win.document.getElementById('auth-code-use').click();
    expect(sent).toContainEqual({ type: 'submitLoginCode', code: 'AUTH-CODE-42' });
    expect(input.value).toBe('');
    expect(step('verifying').hidden).toBe(false);
  });

  it('verifying has its own Cancel, so a hung submit is never a dead end', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.cc.authState({ step: 'verifying' });
    win.document.getElementById('auth-cancel-verify').click();
    expect(sent).toContainEqual({ type: 'cancelLogin' });
    expect(step('idle').hidden).toBe(false);
  });

  it('the API key input submits and is cleared too', () => {
    win.cc.state({ running: false, needsLogin: true });
    const input = win.document.getElementById('auth-key');
    input.value = 'sk-ant-test';
    win.document.getElementById('auth-key-use').click();
    expect(sent).toContainEqual({ type: 'useApiKey', key: 'sk-ant-test' });
    expect(input.value).toBe('');
  });

  it('dismiss tells the host and an error shows its message with a retry back to idle', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.document.getElementById('auth-dismiss').click();
    expect(sent).toContainEqual({ type: 'dismissAuth' });

    win.cc.authState({ step: 'error', message: 'The sign-in finished but no token was captured' });
    expect(win.document.getElementById('auth-error').textContent).toContain('no token was captured');
    win.document.getElementById('auth-retry').click();
    expect(step('idle').hidden).toBe(false);
  });

  it('comes down when needsLogin clears', () => {
    win.cc.state({ running: false, needsLogin: true });
    win.cc.state({ running: true, needsLogin: false });
    expect(card().hidden).toBe(true);
  });
});
