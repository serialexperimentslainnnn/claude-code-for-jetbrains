const { loadFrontend, readCss } = require('./helpers/load');

const css = () => readCss();

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

const LONG_ERROR =
  'Error: No such tool available: Glob. Glob is not available in this session — ' +
  'find files with `find` via the Bash tool instead.';

describe('failed tool cards', () => {
  it('marks the card failed and routes the error into its output node', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(40, 0, 'TOOL', 'Glob(src/**/*.test.js)', {
        meta: 'Glob',
        toolUseId: 'tu-glob',
        state: 'ERROR',
      }),
      row(41, 1, 'TOOL_OUTPUT', LONG_ERROR, { meta: 'error', toolUseId: 'tu-glob' }),
    ]);

    const card = win.document.querySelector('.tool');
    expect(card).not.toBeNull();
    expect(card.classList.contains('failed')).toBe(true);

    const block = win.document.querySelector('[data-out-id="to-41"]');
    expect(block).not.toBeNull();
    expect(block.textContent).toContain('No such tool available');
    expect(block.closest('.tool-out')).not.toBeNull();
    expect(card.classList.contains('open')).toBe(true);
  });

  it('does not re-open a failed card the user collapsed', () => {
    const win = loadFrontend(['app-transcript.js']);
    const tool = row(50, 0, 'TOOL', 'Glob(x)', { meta: 'Glob', toolUseId: 'tu-x', state: 'ERROR' });
    win.cc.batch([tool, row(51, 1, 'TOOL_OUTPUT', LONG_ERROR, { meta: 'error', toolUseId: 'tu-x' })]);

    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('open')).toBe(true);
    card.classList.remove('open');
    win.cc.batch([tool]);
    expect(card.classList.contains('open')).toBe(false);
  });

  it('the error text wraps rather than scrolling out of view', () => {
    const sheet = css();
    expect(sheet).toMatch(/\.tool\.failed \.tool-out pre code\s*\{[^}]*white-space:\s*pre-wrap/);
    expect(sheet).toMatch(/\.tool\.failed \.tool-out pre code\s*\{[^}]*overflow-wrap:\s*anywhere/);
  });

  it('normal (non-failed) output still scrolls instead of wrapping — code must keep its alignment', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(42, 0, 'TOOL', 'Bash(ls)', { meta: 'Bash', toolUseId: 'tu-ok' }),
      row(43, 1, 'TOOL_OUTPUT', 'a  b  c', { meta: 'command', toolUseId: 'tu-ok' }),
    ]);
    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('failed')).toBe(false);
    expect(css()).not.toMatch(/^\.tool-out pre code\s*\{[^}]*white-space:\s*pre-wrap/m);
  });
});
