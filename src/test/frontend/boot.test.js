// The boot screen: it must appear while the binary launches, and — more importantly — must always come down.
//
// It covers the whole tab and blocks input, so a stuck boot screen is a worse failure than the empty composer
// it exists to hide. These pin the three-state logic (running / starting / neither) and the fact that it is
// driven outside the composer's ensureBuilt() gate.
const { loadFrontend, readCss } = require('./helpers/load');

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

  it('the waiting screens belong to the CHAT — grouped with the transcript, beside it and not inside it', () => {
    // Three bounds, and each one is a bug that happened.
    //
    // OUTSIDE #conversation, because that element's children belong to `cc.clear()`, which deletes them (see
    // below). INSIDE #tabview, because that group is what a chat's context IS — its transcript and the three
    // screens that stand in for it — and grouping them is what makes "switch chat" a question with one
    // answer. And #tabview is inside #work, which is what stops any of them reaching the tab bar or the
    // composer: as `inset: 0` panels over #app they covered both, so a chat could not be switched while
    // another started.
    const conv = win.document.getElementById('conversation');
    const view = win.document.getElementById('tabview');
    const work = win.document.getElementById('work');
    expect(conv.parentNode).toBe(view);
    for (const screen of [boot(), win.document.getElementById('auth-card')]) {
      expect(screen.parentNode).toBe(view);
      expect(conv.contains(screen)).toBe(false);
    }
    // …and what is NOT the chat's stays out of the group: the dock is shared by every chat and is a row of
    // its own, and the tab bar is outside #work altogether.
    const dock = win.document.getElementById('dock');
    expect(view.contains(dock)).toBe(false);
    expect(work.contains(dock)).toBe(true);
    expect(work.contains(win.document.getElementById('tabsbar'))).toBe(false);
  });

  it('the sign-in screen replaces the spinner instead of being covered by it', () => {
    // Exactly one screen up at a time, decided here rather than by stacking. The two share a z-index, which
    // says where they sit relative to the TRANSCRIPT and nothing about each other — "both up" would be two
    // screens in one cell with nothing to say which one the user is meant to answer. Needing to sign in means
    // the spinner has nothing to wait for, so it is the spinner that goes.
    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(boot().hidden).toBe(true);
    expect(win.document.getElementById('auth-card').hidden).toBe(false);
  });

  it('the sign-in card outranks a live session — the spinner is what startup suppresses', () => {
    // The gate is `starting`, not `running`. A session with a live process whose credential expired needs the
    // card more than anything else on screen: it is the only control that repairs it, and the chat behind it
    // can no longer take a turn. What startup suppresses is the FLASH, so the spinner owns that window alone.
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
    // The dots live in ::after, not in the text node, so the accessible name is the stable phrase.
    expect(dots.textContent).toBe('');
    expect(win.document.querySelector('.boot-title').textContent.trim()).toBe('Loading Claude Code');
  });

  it('announces the wait to assistive technology', () => {
    const region = win.document.getElementById('a11y-status');
    win.cc.state({ starting: true, running: false });
    expect(region.textContent).toContain('Loading Claude Code');
  });

  it('takes the transcript out of reach while it covers it, and gives it back', () => {
    // An opaque layer over content that keeps its links and buttons focusable is WCAG 2.2 SC 2.4.11 (Focus
    // Not Obscured). Declared through CC.coverTranscript because the dashboard covers the same element.
    const conv = win.document.getElementById('conversation');
    win.cc.state({ starting: true, running: false });
    expect(conv.hasAttribute('inert')).toBe(true);

    win.cc.state({ starting: false, running: false, needsLogin: true });
    expect(conv.hasAttribute('inert')).toBe(true); // the sign-in card covers it just the same

    win.cc.state({ starting: false, running: true });
    expect(conv.hasAttribute('inert')).toBe(false);
  });
});

// The regression that put this file's structural test where it is. The waiting screens were rows INSIDE
// #conversation, and `cc.clear()` — which runs on every switch between a chat, an agent and a task, and on
// every session stop — removes that element's children. So the first clear deleted all three screens from the
// document, permanently, and nothing said so: `renderBoot` looks its element up and returns when it is
// absent. A tab that later lost its binary, its login or its process showed no screen at all, and the only
// thing that appeared to fix it was opening a new chat, i.e. a page that had not cleared yet.
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
    // #empty is `cc.clear()`'s own idle state and the ONE child it is allowed to keep. A second survivor
    // means something is being stored in an element that does not own it — which is the bug above.
    win.cc.clear();
    const kept = Array.from(win.document.getElementById('conversation').children).map((el) => el.id);
    expect(kept).toEqual(['empty']);
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

  // "Check again" shipped as a documented button with a live host handler and NO web code emitting the
  // message — the same shape as the `/login` terminal launch that was dead for a whole release. The Kotlin
  // side (JcefBridge "recheckBinary" → OnboardingController.recheckBinary → pushBootError) was reachable only
  // from a unit test, so the user who ran the install command in their own terminal had nothing to click.
  it('offers Check again — a real focusable control with a name that survives out of context', () => {
    missing();
    const btn = win.document.getElementById('boot-recheck');
    expect(btn).toBeTruthy();
    expect(card().contains(btn)).toBe(true);
    // A native <button>: it is what picks up the `:where(a, button, …):focus-visible` ring and keyboard
    // activation for free. A styled <div> here would be invisible to both.
    expect(btn.tagName).toBe('BUTTON');
    expect(btn.type).toBe('button');
    expect(btn.textContent).toBe('Check again');
    // WCAG 2.5.3: the accessible name must START with the visible label, so speech input still works.
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
    // The host discovers a vanished binary while a session is up, pushes `binaryMissing` and stops that
    // session as a separate hop, so the page really is sent both at once. Gating this card on `!running`
    // suppressed the only control that repairs it for exactly as long as that window lasts.
    win.cc.state({ starting: false, running: true, binaryMissing: true });
    expect(win.document.getElementById('boot').hidden).toBe(false);
    expect(card().hidden).toBe(false);
    // And it outranks the sign-in card: signing in is meaningless without a binary.
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

  it('is a function of the pushed state alone — needsLogin raises it, the install card wins over it', () => {
    expect(card().hidden).toBe(true);
    win.cc.state({ running: false, needsLogin: true });
    expect(card().hidden).toBe(false);
    win.cc.state({ running: false, needsLogin: true, binaryMissing: true });
    expect(card().hidden).toBe(true); // signing in is meaningless without a binary
    // No "raise it anyway" door: with the state saying we are signed in, the card stays down. The host's
    // boot watcher re-derives needsLogin every few seconds, so a credential that disappears raises it there.
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

// How a waiting screen ARRIVES, and what reducing motion has to do to it.
//
// Both screens assemble row by row instead of the whole panel landing at once. That is a sequence, and a
// sequence is the one shape a reduced-motion reset gets wrong when it only flattens durations: a delay is not
// a duration, so the rows still take their turn — each one blank for its beat, then a snap.
//
// These read the stylesheet as text on purpose. jsdom applies no stylesheet, has no layout and no animation
// clock, so an assertion that claimed to watch the entrance play would be watching nothing; the declarations
// are what is real here. What jsdom CAN observe — the class the host toggles, and the absence of any inline
// style scripting the entrance — is asserted against the live DOM.
describe('waiting screens — the sequenced entrance', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js'], { vendor: false });
  });

  const boot = () => win.document.getElementById('boot');
  const authCard = () => win.document.getElementById('auth-card');
  const sheet = () => readCss();

  it('stages each row against the screen it belongs to, not against a fixed clock', () => {
    // A row's delay is its screen's grace plus its place in the order, so ONE rule serves a screen that waits
    // before it draws and one that does not. `animation-delay` is a longhand after the shorthand because the
    // shorthand resets it; swapping the two lines silently drops every beat.
    expect(sheet()).toMatch(/\.boot-inner > \*,\s*\.auth-inner > \*/);
    expect(sheet()).toContain(
      'animation-delay: calc(var(--wait-grace) + var(--wait-beat, 0) * var(--wait-step))'
    );
  });

  it('the loading screen keeps its grace; the sign-in card has none', () => {
    // A start that takes 100 ms still never paints a panel. The sign-in card is not a wait — it is the
    // control that repairs the session, raised only once the host has decided it is needed, so it starts
    // on its first frame.
    expect(sheet()).toMatch(/#boot\s*\{[^}]*--wait-grace:\s*0\.35s/);
    expect(sheet()).toMatch(/#auth-card\s*\{[^}]*--wait-grace:\s*0s/);
  });

  it('every sign-in step shares one beat, because position would lie about which one is showing', () => {
    // All five `.auth-step` containers are in the markup at once and all but one carry `hidden`. Staggering
    // by position would hand the error step five beats for being sixth in a list of which exactly one is
    // ever displayed — so the beat is declared on the class, and nothing here counts children.
    expect(sheet()).toMatch(/\.boot-sub,\s*\.auth-step\s*\{\s*--wait-beat:\s*2;/);
    expect(sheet()).not.toMatch(/\.auth-step:nth-/);
    expect(sheet()).not.toMatch(/\.(boot|auth)-inner > :nth-child/);
  });

  it('the mark keeps its entrance AND its breathing', () => {
    // `animation` is a shorthand, so one rule owns both: naming only the breath drops the entrance every
    // other row gets, and naming only the entrance leaves the screen still.
    const from = sheet().indexOf('.boot-mark {');
    const rule = sheet().slice(from, sheet().indexOf('}', from));
    expect(rule).toContain('fade var(--wait-in) var(--ease) var(--wait-grace) both');
    expect(rule).toContain('bootBreathe');
  });

  it('reducing motion zeroes the DELAY, not merely the duration', () => {
    // The hole: a `both`-fill animation holds its element at the from-state for the whole of its delay.
    // Flatten only the duration and the sequence survives — every row waits out its beat invisible, then
    // appears instantly, and the loading screen sits blank through its grace first. Comments are stripped
    // so this reads the rules rather than the prose that explains them.
    const rules = sheet().replace(/\/\*[\s\S]*?\*\//g, '');
    const from = rules.indexOf('body.reduced-motion *,');
    const block = rules.slice(from, rules.indexOf('}', from));
    expect(block).toContain('animation-delay: 0s !important');
    expect(block).toContain('transition-delay: 0s !important');
    expect(block).toContain('animation-duration: 0.001ms !important');
  });

  it('the entrance is declarative, so a screen that comes back replays it with no script', () => {
    // The replay mechanism is the `hidden` attribute: `display: none !important` leaves the element with no
    // box, and an element with no box has no running animations, so restoring it starts them fresh. Nothing
    // may script that instead — an inline animation is invisible to the stylesheet, and therefore to the
    // reduced-motion reset that is supposed to be able to flatten it.
    expect(sheet()).toMatch(/\[hidden\]\s*\{[^}]*display:\s*none\s*!important/);

    win.cc.state({ starting: false, running: true });
    expect(boot().hidden).toBe(true);
    win.cc.state({ starting: true, running: false });
    expect(boot().hidden).toBe(false);

    expect(boot().getAttribute('style')).toBeNull();
    expect(win.document.querySelector('.boot-mark').getAttribute('style')).toBeNull();
  });

  it('the host decides, and the class it sets sits above both screens', () => {
    // Reduced motion is never read from the browser here: JCEF renders off-screen and the media query answers
    // for nobody. The host pushes it, and it lands on <body> — which has to be an ancestor of both waiting
    // screens, or the reset that flattens their sequence never reaches them.
    win.cc.theme({ reducedMotion: true });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(true);
    expect(win.document.body.contains(boot())).toBe(true);
    expect(win.document.body.contains(authCard())).toBe(true);

    win.cc.theme({ reducedMotion: false });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(false);
  });
});
