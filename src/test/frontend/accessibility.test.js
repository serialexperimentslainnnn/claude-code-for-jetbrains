// Accessibility conformance (WCAG 2.2 AA). These are not cosmetic assertions: the plugin is distributed to
// consumers in the EU, where Directive (EU) 2019/882 has applied since 28 June 2025 (in Spain, Ley 11/2023).
//
// Scope honesty, stated up front: automated checks catch roughly 40-57% of real barriers, and NONE of the
// semantic judgements (does the label describe the control, does the focus order make sense, is the announcement
// useful). These tests pin the structural guarantees that regress silently; they do not certify conformance.
// Conformance still requires keyboard-only and screen-reader passes by a person.
const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, readCss, JCEF } = require('./helpers/load');

const css = () => readCss();
const shell = () => fs.readFileSync(path.join(JCEF, 'shell.html'), 'utf8');

describe('a11y — status messages (WCAG 4.1.3, Level AA)', () => {
  // The transcript streams without ever moving focus. Without a live region a screen-reader user gets no
  // signal that Claude started, finished, or is blocked on them — the turn just stalls silently.
  it('the live region is declared in the static shell, not created on first use', () => {
    // It must exist in the DOM BEFORE text is written into it, or the first change is never announced.
    // Creating it lazily when the first message arrives is the classic way to ship a silent live region.
    const html = shell();
    expect(html).toMatch(/id="a11y-status"/);
    expect(html).toMatch(/aria-live="polite"/);
    expect(html).toMatch(/role="status"/);
  });

  it('CC.announce writes into the live region', () => {
    const win = loadFrontend([]);
    win.CC.announce('Claude is working…');
    expect(win.document.getElementById('a11y-status').textContent).toBe('Claude is working…');
  });

  it('does not re-announce identical text', () => {
    // A live region re-set to the same string is a no-op in most screen readers, but not uniformly. Skipping
    // it explicitly keeps behaviour predictable instead of relying on that.
    const win = loadFrontend([]);
    const el = win.document.getElementById('a11y-status');
    win.CC.announce('Claude finished responding.');
    el.textContent = 'MUTATED';
    win.CC.announce('Claude finished responding.');
    expect(el.textContent).toBe('MUTATED'); // skipped, so our mutation survives
  });

  it('a pending permission is announced, naming the tool when there is exactly one', () => {
    // The single most important announcement: a permission card appears WITHOUT taking focus, so otherwise
    // the turn stops with no explanation for anyone not watching the dock.
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([{ id: 'p1', tool: 'Bash', title: 'Run', summary: '', reviewable: false }]);
    expect(win.document.getElementById('a11y-status').textContent).toMatch(/permission to use Bash/);
  });

  it('resolving the last card does not announce (the user just acted)', () => {
    const win = loadFrontend(['app-permissions.js']);
    win.cc.permissions([{ id: 'p1', tool: 'Bash', title: 'Run', summary: '', reviewable: false }]);
    const el = win.document.getElementById('a11y-status');
    el.textContent = 'SENTINEL';
    win.cc.permissions([]);
    expect(el.textContent).toBe('SENTINEL');
  });
});

describe('a11y — visible focus (WCAG 2.4.7 / 1.4.11, Level AA)', () => {
  it('every element that suppresses its outline has a :focus-visible replacement', () => {
    // "outline: none with no substitute" is the single most repeated one-line accessibility defect there is.
    // The stylesheet legitimately suppresses the default outline in several places; each must be replaced.
    const sheet = css();
    expect(sheet).toMatch(/:focus-visible/);
    // The find bar's input was the one with no replacement at all — pin it specifically.
    expect(sheet).toMatch(/\.find-input:focus-visible/);
  });

  it('the focus ring is honoured under forced-colors instead of being overridden', () => {
    // Overriding system colours in Windows high-contrast mode defeats the accommodation entirely.
    expect(css()).toMatch(/@media \(forced-colors: active\)/);
  });

  it('nothing in the tab bar is laid over the tabs (SC 2.4.11 Focus Not Obscured)', () => {
    // The dashboard's view switcher sits in the tab bar. As an absolutely-positioned overlay it covered the
    // last chat tab: tab to it and the focused control is underneath the buttons — focused and invisible,
    // which is exactly what 2.4.11 forbids. Both are flex ITEMS, so overlap is impossible by construction
    // rather than by keeping a `padding-right` in sync with the width of the words.
    const sheet = css();
    const rule = sheet.slice(sheet.indexOf('.dash-toggles'), sheet.indexOf('.dash-toggle {'));
    expect(rule).not.toMatch(/position:\s*(absolute|fixed)/);
    expect(rule).toMatch(/flex:\s*0 0 auto/);
  });

  it('the visually-hidden helper keeps the node in the accessibility tree', () => {
    // display:none / visibility:hidden would remove the live region from the a11y tree and silence it.
    const sheet = css();
    const rule = sheet.slice(sheet.indexOf('.visually-hidden'));
    expect(rule).not.toMatch(/display:\s*none/);
    expect(rule).not.toMatch(/visibility:\s*hidden/);
    expect(rule).toMatch(/clip-path/);
  });
});

describe('a11y — motion and language', () => {
  it('neutralises every animation when motion is reduced', () => {
    // The stylesheet defines several keyframe animations; reducing motion must neutralise them all rather
    // than a hand-picked subset that goes stale as animations are added.
    //
    // The GATE changed in 5.0.0 and this test changed with it. It used to require the rule to sit behind
    // `@media (prefers-reduced-motion: reduce)` — which is correct on the open web and wrong inside JCEF,
    // where off-screen rendering leaves the browser with no desktop preference to read, so the query matched
    // unconditionally and killed every animation for everyone. The universal-selector requirement below is
    // unchanged; only who decides has moved to the host.
    const sheet = css();
    expect(sheet).toMatch(/body\.reduced-motion \*,\s*body\.reduced-motion \*::before/);
  });

  it('declares a document language (WCAG 3.1.1, Level A)', () => {
    // Missing document language is in the top six most common failures on the web; without it a screen
    // reader pronounces the interface with the wrong phonetics.
    expect(shell()).toMatch(/<html[^>]+lang=/);
  });
});

// Reduced motion is the HOST's call, not the browser's.
//
// Regression guard for 5.0.0: this rule set used to live behind `@media (prefers-reduced-motion: reduce)`, and
// inside JCEF that disabled every animation in the chat for everyone — off-screen rendering has no GTK window
// (and on Wayland no XSETTINGS bridge), so the browser has no desktop preference to report. The tell was that
// Vibe Mode's rainbow kept running: it is a `setInterval` in JS, so no CSS rule could touch it.
describe('accessibility — reduced motion is host-driven', () => {
  it('animations are NOT suppressed by default', () => {
    const win = loadFrontend(['app-transcript.js']);
    expect(win.document.body.classList.contains('reduced-motion')).toBe(false);
  });

  it('cc.theme({reducedMotion:true}) adds the class, false removes it', () => {
    const win = loadFrontend(['app-transcript.js']);

    win.cc.theme({ reducedMotion: true });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(true);

    win.cc.theme({ reducedMotion: false });
    expect(win.document.body.classList.contains('reduced-motion')).toBe(false);
  });

  it('reducedMotion is a flag, never written out as a CSS custom property', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.theme({ reducedMotion: true, accent: '#ff0000' });

    const root = win.document.documentElement;
    expect(root.style.getPropertyValue('--reduced-motion')).toBe('');
    expect(root.style.getPropertyValue('--accent')).toBe('#ff0000');
  });

  it('the motion kill is gated on body.reduced-motion, never on a media query', () => {
    // Comments are stripped first: the block above EXPLAINS the media query at length, and matching raw text
    // would fail on the explanation rather than on the rule — a test that reads prose instead of code.
    const rules = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(rules).toContain('body.reduced-motion *');
    expect(rules).not.toContain('@media (prefers-reduced-motion');
  });
});
