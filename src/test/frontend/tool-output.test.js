// What a tool card SHOWS: the result it got back, and the message it sent.
//
// Written from a screenshot of a `SendMessage` card. All it said was the tool's name and, under it,
// `{"success":true,"message":"Message queued for delivery to a2bd… at its next tool round.","pin":{"id":…`
// as ONE line running off the right edge of a card with no visible scrollbar. Two separate faults in one
// picture, and neither is about `SendMessage`:
//
//   1. every result that is not a diff, a command's output or a file's contents was rendered
//      `white-space: pre`, so anything long enough was simply not readable; and
//   2. nothing on the card said what had been SENT — the result of a message is not the message.
//
// The split that governs the first one is by what the row IS, never by how long it happens to be: a diff's
// marker column, a command's tabular output and a file's indentation all MEAN something, and wrapping them
// corrupts them. Everything else is prose or structure, and wraps. Those exemptions are pinned here in both
// directions, because a fix that also wrapped a diff would look right in one screenshot and be worse.
const { loadFrontend, readCss } = require('./helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

/** The `<pre>` a TOOL_OUTPUT row was routed into. */
const outBlock = (win, id) => win.document.querySelector(`[data-out-id="to-${id}"]`);

const SEND_MESSAGE_RESULT =
  '{"success":true,"message":"Message queued for delivery to a2bd4f at its next tool round.",' +
  '"pin":{"id":"a2bd4f","kind":"agent"}}';

describe('tool output — a JSON result is re-indented', () => {
  it('formats it, and detects it by CONTENT rather than by the tool name', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(300, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-json' }),
      row(301, 1, 'TOOL_OUTPUT', SEND_MESSAGE_RESULT, { toolUseId: 'tu-json' }),
    ]);

    const code = outBlock(win, 301).querySelector('code');
    // Re-indented: the one-liner became a document with a line per field.
    expect(code.textContent.split('\n').length).toBeGreaterThan(4);
    expect(code.textContent).toContain('\n  "success": true');
    // …and nothing was lost on the way through.
    expect(JSON.parse(code.textContent)).toEqual(JSON.parse(SEND_MESSAGE_RESULT));
  });

  it('gives it the fence chrome: a json label and a Copy button', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(302, 0, 'TOOL', 'mcp__thing__do', { meta: 'mcp__thing__do', toolUseId: 'tu-json2' }),
      row(303, 1, 'TOOL_OUTPUT', '{"ok":1}', { toolUseId: 'tu-json2' }),
    ]);

    const block = outBlock(win, 303);
    expect(block.querySelector('.code-lang').textContent).toBe('json');
    expect(block.querySelector('.code-head .copy')).not.toBeNull();
  });

  it('Copy hands the host the FORMATTED text — what is on screen is what is copied', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([
      row(304, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-json3' }),
      row(305, 1, 'TOOL_OUTPUT', '{"ok":1}', { toolUseId: 'tu-json3' }),
    ]);

    outBlock(win, 305).querySelector('.copy').click();
    const copy = sent.find((m) => m.type === 'copy');
    expect(copy.text).toBe('{\n  "ok": 1\n}');
  });

  it('leaves alone anything that only LOOKS like JSON', () => {
    // Both halves of the guard. The first never reaches JSON.parse (the brackets do not close, which is what
    // a truncated result looks like); the second reaches it and throws. Either way the text is shown raw,
    // which is what it was shown as before any of this existed — the fallback is never worse than the start.
    const win = loadFrontend(['app-transcript.js']);
    const truncated = '{"success":true,"message":"Message queued for delivery';
    win.cc.batch([
      row(306, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-j4' }),
      row(307, 1, 'TOOL_OUTPUT', truncated, { toolUseId: 'tu-j4' }),
      row(308, 2, 'TOOL', 'Weird', { meta: 'Weird', toolUseId: 'tu-j5' }),
      row(309, 3, 'TOOL_OUTPUT', '{not json at all}', { toolUseId: 'tu-j5' }),
    ]);

    expect(outBlock(win, 307).querySelector('code').textContent).toBe(truncated);
    expect(outBlock(win, 309).querySelector('code').textContent).toBe('{not json at all}');
    expect(outBlock(win, 307).querySelector('.code-head')).toBeNull();
  });

  it('refuses to parse a result past the size ceiling, and shows it raw', () => {
    // The ceiling is a bound on work done on the page's only thread, on every tool result. A dump big enough
    // to matter is one nobody reads inline anyway, so the cheap answer is the right one.
    const win = loadFrontend(['app-transcript.js']);
    const huge = '["' + 'x'.repeat(200000) + '"]'; // 200 004 chars, past MAX_JSON_CHARS
    win.cc.batch([
      row(310, 0, 'TOOL', 'Dump', { meta: 'Dump', toolUseId: 'tu-huge' }),
      row(311, 1, 'TOOL_OUTPUT', huge, { toolUseId: 'tu-huge' }),
    ]);

    const code = outBlock(win, 311).querySelector('code');
    expect(code.textContent).toBe(huge); // untouched: no newline was ever inserted
    expect(outBlock(win, 311).querySelector('.code-head')).toBeNull();
  });

  it('renders the result as TEXT, never as markup', () => {
    // A tool result is the output of an external process, i.e. the least trusted string on the page. It goes
    // in through textContent, so markup inside it is characters and nothing else — and this holds on the JSON
    // path too, where the string makes a round trip through a parser before it is displayed.
    const win = loadFrontend(['app-transcript.js']);
    const hostile = '{"note":"<img src=x onerror=alert(1)>"}';
    win.cc.batch([
      row(312, 0, 'TOOL', 'Fetch', { meta: 'Fetch', toolUseId: 'tu-xss' }),
      row(313, 1, 'TOOL_OUTPUT', hostile, { toolUseId: 'tu-xss' }),
    ]);

    const block = outBlock(win, 313);
    expect(block.querySelector('img')).toBeNull();
    expect(block.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});

describe('tool output — what wraps and what must not', () => {
  it('a free-flowing result wraps instead of scrolling sideways', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(320, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-w1' }),
      row(321, 1, 'TOOL_OUTPUT', SEND_MESSAGE_RESULT, { toolUseId: 'tu-w1' }),
    ]);

    expect(outBlock(win, 321).classList.contains('flow')).toBe(true);
    // jsdom lays nothing out, so the class is the observable half and the stylesheet is the other.
    const sheet = readCss();
    expect(sheet).toMatch(/\.tool-out pre\.flow code\s*\{[^}]*white-space:\s*pre-wrap/);
    expect(sheet).toMatch(/\.tool-out pre\.flow code\s*\{[^}]*overflow-wrap:\s*anywhere/);
  });

  it('a plain error wraps too — it is prose, and it always was the reported case', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(322, 0, 'TOOL', 'Glob(**/*)', { meta: 'Glob', toolUseId: 'tu-w2', state: 'ERROR' }),
      row(323, 1, 'TOOL_OUTPUT', 'Error: No such tool available: Glob.', {
        meta: 'error',
        toolUseId: 'tu-w2',
      }),
    ]);

    expect(outBlock(win, 323).classList.contains('flow')).toBe(true);
  });

  it("a command's output does NOT wrap: its columns are its meaning", () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(324, 0, 'TOOL', 'Bash(ls -la)', { meta: 'Bash', toolUseId: 'tu-w3' }),
      row(325, 1, 'TOOL_OUTPUT', 'drwxr-xr-x  2 me me 4096 file.txt', {
        meta: 'command',
        toolUseId: 'tu-w3',
      }),
    ]);

    const block = outBlock(win, 325);
    expect(block.classList.contains('command')).toBe(true);
    expect(block.classList.contains('flow')).toBe(false);
  });

  it('a diff does NOT wrap: its first column is the +/- marker', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(326, 0, 'TOOL', 'Edit(src/Foo.kt)', {
        meta: 'Edit',
        toolUseId: 'tu-w4',
        filePath: 'src/Foo.kt',
      }),
      row(327, 1, 'TOOL_OUTPUT', '@@ -1 +1 @@\n-a\n+b', { meta: 'diff', toolUseId: 'tu-w4' }),
    ]);

    const block = outBlock(win, 327);
    expect(block.classList.contains('diff')).toBe(true);
    expect(block.classList.contains('flow')).toBe(false);
  });

  it("a file's contents do NOT wrap, and keep their own highlighted chrome", () => {
    // The exemption that is decided in JS rather than by a meta tag: a Read's dump is code, and reflowing it
    // destroys the indentation that is the only structure it has.
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(328, 0, 'TOOL', 'Read(src/Foo.kt)', {
        meta: 'Read',
        toolUseId: 'tu-w5',
        filePath: 'src/Foo.kt',
      }),
      row(329, 1, 'TOOL_OUTPUT', 'fun main() {\n    println("hi")\n}', { toolUseId: 'tu-w5' }),
    ]);

    const block = outBlock(win, 329);
    expect(block.classList.contains('flow')).toBe(false);
    expect(block.querySelector('.code-lang').textContent).toBe('kotlin');
  });

  it('a result that changes shape between pushes does not keep the old variant', () => {
    // The `<pre>` is keyed by row id and REUSED, so every branch has to clear the classes of the others. A
    // result arriving first as plain text and then tagged as a command otherwise wraps and scrolls at once.
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(330, 0, 'TOOL', 'Bash(build)', { meta: 'Bash', toolUseId: 'tu-w6' }),
      row(331, 1, 'TOOL_OUTPUT', 'starting', { toolUseId: 'tu-w6' }),
    ]);
    expect(outBlock(win, 331).classList.contains('flow')).toBe(true);

    win.cc.batch([row(331, 1, 'TOOL_OUTPUT', 'done', { meta: 'command', toolUseId: 'tu-w6' })]);
    expect(outBlock(win, 331).classList.contains('flow')).toBe(false);
    expect(outBlock(win, 331).classList.contains('command')).toBe(true);
  });
});

// The second half of the screenshot: the card said what came back and never what was sent.
//
// Shaped exactly like the command block (`.tool-cmd` / `entry.command`), because it is the same idea about a
// different payload — and, like it, the HOST decides which input key holds the text. The page renders
// `entry.message` and parses no tool input: which keys count is a security decision that belongs beside the
// rules that read the same input, not in the browser.
describe('tool output — the message a call SENDS is shown without expanding the card', () => {
  const withMessage = (win, id, message) =>
    win.cc.batch([
      row(id, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-m' + id, message }),
    ]);

  it('renders it as its own block, outside the collapse toggle', () => {
    const win = loadFrontend(['app-transcript.js']);
    withMessage(win, 340, 'Please re-run the failing test and report the first assertion that fails.');

    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('open')).toBe(false); // collapsed, as every tool card starts
    expect(card.classList.contains('msg-tool')).toBe(true);

    const block = card.querySelector('.tool-msg pre.message-src');
    expect(block).not.toBeNull();
    expect(block.querySelector('code').textContent).toBe(
      'Please re-run the failing test and report the first assertion that fails.'
    );
    // A SIBLING of .tool-out, never inside it — .tool-out is display:none until the card is open.
    expect(card.querySelector('.tool-out pre.message-src')).toBeNull();
    expect(block.closest('.tool-out')).toBeNull();
  });

  it('is copyable, through the same delegated handler as every other block', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    withMessage(win, 341, 'ping');

    expect(win.document.querySelector('pre.message-src .code-lang').textContent).toBe('message');
    win.document.querySelector('pre.message-src .copy').click();
    expect(sent.some((m) => m.type === 'copy' && m.text === 'ping')).toBe(true);
  });

  it('shows the message verbatim — it is model-authored text, not markup', () => {
    const win = loadFrontend(['app-transcript.js']);
    withMessage(win, 342, 'Use <b>bold</b> and `code` literally');

    const block = win.document.querySelector('pre.message-src');
    expect(block.querySelector('b')).toBeNull();
    expect(block.textContent).toContain('Use <b>bold</b> and `code` literally');
  });

  it('is not appended twice when the row is pushed again', () => {
    // The block is built once, with the card, because the message is fixed for the life of the row. A later
    // push is a state change (RUNNING → FINISHED) and must not grow a second copy of what was sent.
    const win = loadFrontend(['app-transcript.js']);
    withMessage(win, 343, 'only once');
    win.cc.batch([
      row(343, 0, 'TOOL', 'SendMessage', {
        meta: 'SendMessage',
        toolUseId: 'tu-m343',
        message: 'only once',
        state: 'FINISHED',
      }),
    ]);

    expect(win.document.querySelectorAll('pre.message-src').length).toBe(1);
  });

  it('a tool with no message gets no block and no accent, and takes up no space', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(344, 0, 'TOOL', 'Read(src/Foo.kt)', { meta: 'Read', toolUseId: 'tu-m344' })]);

    expect(win.document.querySelector('pre.message-src')).toBeNull();
    expect(win.document.querySelector('.tool').classList.contains('msg-tool')).toBe(false);
    // The container is always present; `:empty` is what keeps it from costing a border and a gap.
    expect(win.document.querySelector('.tool-msg')).not.toBeNull();
    expect(readCss()).toMatch(/\.tool-msg:empty\s*\{[^}]*display:\s*none/);
  });
});

// The transcript's running text is no longer capped, and the removal is worth a guard: it was a single
// declaration, it is invisible until someone widens the tool window, and it was added once already on an
// argument (WCAG 2.1 SC 1.4.8, ~80 characters) that reads perfectly well in a review.
//
// What made it wrong here is that a transcript is not a document column: it alternates prose with things that
// are deliberately full-width — tool cards, diffs, tables — so capping only the paragraphs left the column
// ragged, a card spanning the pane and the sentence beside it stopping halfway. The reader also already
// controls the measure by dragging the tool window, which is 1.4.8's own intent, and the AA criterion that
// binds (1.4.10 Reflow) is satisfied by text that fills its container and wraps.
describe('transcript prose — nothing caps the running text', () => {
  it('no rule limits the width of a paragraph in a message body', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    // `[\s,]` after the `p` so the selector token really is `p`: without it this reads `.body pre` and
    // `.body pre code` as hits and would be asserting about the wrong rules entirely.
    expect(css).toMatch(/\.body p\s*\{/); // the rule still exists…
    expect(css).not.toMatch(/\.body p[\s,][^{]*\{[^}]*max-width/); // …and decides nothing about width
  });
});
