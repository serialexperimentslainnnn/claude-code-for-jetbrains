const { loadFrontend, readCss } = require('./helpers/load');

describe('boot screen', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const boot = () => win.document.getElementById('boot');
  const app = () => win.document.getElementById('app');

  it('is declared in the static shell and starts visible', () => {
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

  it('the waiting screens belong to the CHAT — grouped with the transcript, beside it and not inside it', () => {
    const conv = win.document.getElementById('conversation');
    const view = win.document.getElementById('tabview');
    const work = win.document.getElementById('work');
    expect(conv.parentNode).toBe(view);
    for (const screen of [boot(), win.document.getElementById('auth-card')]) {
      expect(screen.parentNode).toBe(view);
      expect(conv.contains(screen)).toBe(false);
    }
    const dock = win.document.getElementById('dock');
    expect(view.contains(dock)).toBe(false);
    expect(work.contains(dock)).toBe(true);
    expect(work.contains(win.document.getElementById('tabsbar'))).toBe(false);
  });

  it('the sign-in screen replaces the spinner instead of being covered by it', () => {
    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(boot().hidden).toBe(true);
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
  });

  it('the sign-in card outranks a live session — the spinner is what startup suppresses', () => {
    win.cc.state({ starting: false, running: true, needsLogin: true });
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
    expect(boot().hidden).toBe(true);

    win.cc.state({ starting: true, running: false, needsLogin: true });
    expect(win.document.getElementById('auth-card').hidden).toBe(true);
    expect(boot().hidden).toBe(false);
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
    expect(dots.textContent).toBe('');
    expect(win.document.querySelector('.boot-title').textContent.trim()).toBe('Loading Claude Code');
  });

  it('announces the wait to assistive technology', () => {
    const region = win.document.getElementById('a11y-status');
    win.cc.state({ starting: true, running: false });
    expect(region.textContent).toContain('Loading Claude Code');
  });

  it('takes the transcript out of reach while it covers it, and gives it back', () => {
    const conv = win.document.getElementById('conversation');
    win.cc.state({ starting: true, running: false });
    expect(conv.hasAttribute('inert')).toBe(true);

    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(conv.hasAttribute('inert')).toBe(true);

    win.cc.state({ starting: false, running: true });
    expect(conv.hasAttribute('inert')).toBe(false);
  });
});

describe('waiting screens vs. the transcript lifecycle', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js', 'app-transcript.js'], { vendor: false });
  });

  it('survives cc.clear() and still renders afterwards', () => {
    win.cc.state({ starting: false, running: true });
    expect(win.document.getElementById('boot').hidden).toBe(true);

    win.cc.clear();

    expect(win.document.getElementById('boot')).toBeTruthy();
    expect(win.document.getElementById('auth-card')).toBeTruthy();
    win.cc.state({ starting: false, running: false });
    expect(win.document.getElementById('boot').hidden).toBe(false);
    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
  });

  it('clearing an emptied transcript leaves only its own idle state behind', () => {
    win.cc.clear();
    const kept = Array.from(win.document.getElementById('conversation').children).map((el) => el.id);
    expect(kept).toEqual(['empty']);
  });
});

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

  it('offers Check again — a real focusable control with a name that survives out of context', () => {
    missing();
    const btn = win.document.getElementById('boot-recheck');
    expect(btn).toBeTruthy();
    expect(card().contains(btn)).toBe(true);
    expect(btn.tagName).toBe('BUTTON');
    expect(btn.type).toBe('button');
    expect(btn.textContent).toBe('Check again');
    expect(btn.getAttribute('aria-label')).toMatch(/^Check again/);
  });

  it('clicking Check again asks the host to look again — and sends nothing else', () => {
    missing();
    win.document.getElementById('boot-recheck').click();
    expect(sent).toEqual([{ type: 'recheckBinary' }]);
  });

  it('Check again clears a stale error so the next answer is a fresh alert mutation', () => {
    missing();
    win.cc.bootPathError('Still not found.');
    expect(win.document.getElementById('boot-path-err').textContent).toContain('Still not found');
    win.document.getElementById('boot-recheck').click();
    expect(win.document.getElementById('boot-path-err').textContent).toBe('');
  });

  it('Check again has no busy state to wedge in: a second click is a second request', () => {
    missing();
    const btn = win.document.getElementById('boot-recheck');
    btn.click();
    expect(btn.disabled).toBe(false);
    expect(btn.getAttribute('aria-busy')).toBeNull();
    btn.click();
    expect(sent.filter((m) => m.type === 'recheckBinary').length).toBe(2);
  });

  it('a fresh installMethods push rebuilds the rows without leaving a second Check again', () => {
    missing();
    win.cc.meta({
      installMethods: [{ id: 'dnf', label: 'Install via dnf', display: 'sudo dnf install claude-code' }],
    });
    expect(win.document.querySelectorAll('.boot-recheck').length).toBe(1);
    expect(card().querySelectorAll('.boot-install').length).toBe(1);
  });

  it('is honoured with a live process — the binary can vanish under a running session', () => {
    win.cc.state({ starting: false, running: true, binaryMissing: true });
    expect(win.document.getElementById('boot').hidden).toBe(false);
    expect(card().hidden).toBe(false);
    win.cc.state({ starting: false, running: true, binaryMissing: true, needsLogin: true });
    expect(card().hidden).toBe(false);
    expect(win.document.getElementById('auth-card').hidden).toBe(true);
  });

  it('the card comes down when the binary appears', () => {
    missing();
    win.cc.state({ starting: true, running: false, binaryMissing: false });
    expect(card().hidden).toBe(true);
    expect(win.document.getElementById('boot-sub').textContent).toBe('Starting the agent');
  });
});

describe('sign-in card', () => {
  let win, sent;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
    sent = [];
    win.CC.send = (m) => sent.push(m);
  });

  const card = () => win.document.getElementById('auth-card');
  const step = (name) => card().querySelector('.auth-step[data-step="' + name + '"]');

  it('is a function of the pushed state alone — needsLogin raises it, the install card wins over it', () => {
    expect(card().hidden).toBe(true);
    win.cc.state({ running: false, needsLogin: true });
    expect(card().hidden).toBe(false);
    win.cc.state({ running: false, needsLogin: true, binaryMissing: true });
    expect(card().hidden).toBe(true);
    win.cc.state({ running: false, needsLogin: false });
    expect(card().hidden).toBe(true);
    expect(win.cc.showAuth).toBeUndefined();
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
    expect(win.document.getElementById('auth-url')).toBeNull();
    expect(win.document.getElementById('auth-url-open').disabled).toBe(false);
    expect(win.document.getElementById('auth-code')).toBeTruthy();
    expect(win.document.getElementById('auth-code-label').textContent).toContain('optional');
    win.document.getElementById('auth-url-copy').click();
    expect(sent).toContainEqual({ type: 'copy', text: 'https://claude.ai/oauth/authorize?x=1' });
    win.document.getElementById('auth-url-open').click();
    expect(sent).toContainEqual({ type: 'open', url: 'https://claude.ai/oauth/authorize?x=1' });

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

describe('waiting screens — the sequenced entrance', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const boot = () => win.document.getElementById('boot');
  const authCard = () => win.document.getElementById('auth-card');
  const sheet = () => readCss();

  it('stages each row against the screen it belongs to, not against a fixed clock', () => {
    expect(sheet()).toMatch(/\.boot-inner > \*,\s*\.auth-inner > \*/);
    expect(sheet()).toContain(
      'animation-delay: calc(var(--wait-grace) + var(--wait-beat, 0) * var(--wait-step))'
    );
  });

  it('the loading screen keeps its grace; the sign-in card has none', () => {
    expect(sheet()).toMatch(/#boot\s*\{[^}]*--wait-grace:\s*0\.35s/);
    expect(sheet()).toMatch(/#auth-card\s*\{[^}]*--wait-grace:\s*0s/);
  });

  it('every sign-in step shares one beat, because position would lie about which one is showing', () => {
    expect(sheet()).toMatch(/\.boot-sub,\s*\.auth-step\s*\{\s*--wait-beat:\s*2;/);
    expect(sheet()).not.toMatch(/\.auth-step:nth-/);
    expect(sheet()).not.toMatch(/\.(boot|auth)-inner > :nth-child/);
  });

  it('the mark keeps its entrance AND its breathing', () => {
    const from = sheet().indexOf('.boot-mark {');
    const rule = sheet().slice(from, sheet().indexOf('}', from));
    expect(rule).toContain('fade var(--wait-in) var(--ease) var(--wait-grace) both');
    expect(rule).toContain('bootBreathe');
  });

  it('reducing motion zeroes the DELAY, not merely the duration', () => {
    const rules = sheet().replace(/\/\*[\s\S]*?\*\//g, '');
    const from = rules.indexOf('body.reduced-motion *,');
    const block = rules.slice(from, rules.indexOf('}', from));
    expect(block).toContain('animation-delay: 0s !important');
    expect(block).toContain('transition-delay: 0s !important');
    expect(block).toContain('animation-duration: 0.001ms !important');
  });

  it('the entrance is declarative, so a screen that comes back replays it with no script', () => {
    expect(sheet()).toMatch(/\[hidden\]\s*\{[^}]*display:\s*none\s*!important/);

    win.cc.state({ starting: false, running: true });
    expect(boot().hidden).toBe(true);
    win.cc.state({ starting: true, running: false });
    expect(boot().hidden).toBe(false);

    expect(boot().getAttribute('style')).toBeNull();
    expect(win.document.querySelector('.boot-mark').getAttribute('style')).toBeNull();
  });

  it('the host decides, and the class it sets sits above both screens', () => {
    win.cc.theme({ reducedMotion: true });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(true);
    expect(win.document.body.contains(boot())).toBe(true);
    expect(win.document.body.contains(authCard())).toBe(true);

    win.cc.theme({ reducedMotion: false });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(false);
  });
});
