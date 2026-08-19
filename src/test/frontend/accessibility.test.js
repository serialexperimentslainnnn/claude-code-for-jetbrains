const fs = require('node:fs');
const path = require('node:path');
const { loadFrontend, readCss, JCEF } = require('./helpers/load');

const css = () => readCss();
const shell = () => fs.readFileSync(path.join(JCEF, 'shell.html'), 'utf8');

describe('a11y — status messages (WCAG 4.1.3, Level AA)', () => {
  it('the live region is declared in the static shell, not created on first use', () => {
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
    const win = loadFrontend([]);
    const el = win.document.getElementById('a11y-status');
    win.CC.announce('Claude finished responding.');
    el.textContent = 'MUTATED';
    win.CC.announce('Claude finished responding.');
    expect(el.textContent).toBe('MUTATED');
  });

  it('a pending permission is announced, naming the tool when there is exactly one', () => {
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
    const sheet = css();
    expect(sheet).toMatch(/:focus-visible/);
    expect(sheet).toMatch(/\.find-input:focus-visible/);
  });

  it('the focus ring is honoured under forced-colors instead of being overridden', () => {
    expect(css()).toMatch(/@media \(forced-colors: active\)/);
  });

  it('the view switcher is laid over nothing at all (SC 2.4.11 Focus Not Obscured)', () => {
    const sheet = css();
    const rule = sheet.slice(
      sheet.indexOf('.composer-controls {'),
      sheet.indexOf('.composer-views .dash-toggles')
    );
    expect(rule).not.toMatch(/position:\s*(absolute|fixed)/);
    expect(rule).toMatch(/display:\s*flex/);
    expect(sheet).not.toMatch(/\.tab-row[^{]*\{[^}]*padding-right/);
  });

  it('the visually-hidden helper keeps the node in the accessibility tree', () => {
    const sheet = css();
    const rule = sheet.slice(sheet.indexOf('.visually-hidden'));
    expect(rule).not.toMatch(/display:\s*none/);
    expect(rule).not.toMatch(/visibility:\s*hidden/);
    expect(rule).toMatch(/clip-path/);
  });
});

describe('a11y — target size (WCAG 2.5.8, Level AA) — one declared exception', () => {
  const block = (selector) => {
    const sheet = css().replace(/\/\*[\s\S]*?\*\//g, '');
    const at = sheet.indexOf('\n' + selector + ' {');
    return at < 0 ? '' : sheet.slice(at, sheet.indexOf('}', at));
  };

  it('the subtab row’s close is 20×20 — SHORT of the 24×24 the criterion asks for', () => {
    expect(block('.pill-x')).toMatch(/width:\s*20px/);
    expect(block('.pill-x')).toMatch(/height:\s*20px/);
  });

  it('and it is the ONLY one: on the chats’ row, where the pill has room, the gap is closed', () => {
    const chats = '.tab-capsule:not(.subtab-capsule)';
    expect(block(`${chats} .pill-x`)).toMatch(/width:\s*24px/);
    expect(block(`${chats} .pill-x`)).toMatch(/height:\s*24px/);
  });

  it('no OTHER tab control quietly joins the exception', () => {
    const sheet = css().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(sheet).not.toMatch(/\.pill-more|\.pill-pin/);
  });
});

describe('a11y — motion and language', () => {
  it('neutralises every animation when motion is reduced', () => {
    const sheet = css();
    expect(sheet).toMatch(/body\.reduced-motion \*,\s*body\.reduced-motion \*::before/);
  });

  it('declares a document language (WCAG 3.1.1, Level A)', () => {
    expect(shell()).toMatch(/<html[^>]+lang=/);
  });
});

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
    const rules = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(rules).toContain('body.reduced-motion *');
    expect(rules).not.toContain('@media (prefers-reduced-motion');
  });
});
