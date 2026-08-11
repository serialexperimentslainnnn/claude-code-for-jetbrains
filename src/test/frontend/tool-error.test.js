// Where does a FAILED tool's error text actually land, and can it be read?
//
// Written because the answer was not obvious from the source: `.tool-out` is `display:none` until the card is
// `.open`, yet a reported screenshot showed the error visible on a collapsed card — so either failed cards
// auto-open, or the text renders somewhere else entirely. Guessing at an unobservable DOM has cost this project
// three wrong diagnoses before; this measures it instead.
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
    // Measured, not assumed: the error lives INSIDE the card's collapsible output.
    expect(block.closest('.tool-out')).not.toBeNull();
    // …which is why the card must open itself. Collapsed, the whole message was "the header is red".
    expect(card.classList.contains('open')).toBe(true);
  });

  it('does not re-open a failed card the user collapsed', () => {
    // applyToolState runs on every state push, so a naive `add('open')` would undo a deliberate collapse on the
    // next frame — the kind of fight-the-user bug that is very annoying and very easy to ship.
    const win = loadFrontend(['app-transcript.js']);
    const tool = row(50, 0, 'TOOL', 'Glob(x)', { meta: 'Glob', toolUseId: 'tu-x', state: 'ERROR' });
    win.cc.batch([tool, row(51, 1, 'TOOL_OUTPUT', LONG_ERROR, { meta: 'error', toolUseId: 'tu-x' })]);

    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('open')).toBe(true);
    card.classList.remove('open'); // the user collapses it
    win.cc.batch([tool]); // another state push arrives
    expect(card.classList.contains('open')).toBe(false);
  });

  it('the error text wraps rather than scrolling out of view', () => {
    // The actionable half of that message ("find files with `find` via the Bash tool instead") sits at the end
    // of a very long single line. With the default `overflow:auto` it was clipped past the right edge of a card
    // nobody had reason to think was scrollable.
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
    // The wrap rule is scoped to .tool.failed, so a healthy card is untouched by it.
    expect(css()).not.toMatch(/^\.tool-out pre code\s*\{[^}]*white-space:\s*pre-wrap/m);
  });
});
