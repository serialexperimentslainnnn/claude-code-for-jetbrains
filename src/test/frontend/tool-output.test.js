const { loadFrontend, readCss } = require('./helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

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
    expect(code.textContent.split('\n').length).toBeGreaterThan(4);
    expect(code.textContent).toContain('\n  "success": true');
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
    const win = loadFrontend(['app-transcript.js']);
    const huge = '["' + 'x'.repeat(200000) + '"]';
    win.cc.batch([
      row(310, 0, 'TOOL', 'Dump', { meta: 'Dump', toolUseId: 'tu-huge' }),
      row(311, 1, 'TOOL_OUTPUT', huge, { toolUseId: 'tu-huge' }),
    ]);

    const code = outBlock(win, 311).querySelector('code');
    expect(code.textContent).toBe(huge);
    expect(outBlock(win, 311).querySelector('.code-head')).toBeNull();
  });

  it('renders the result as TEXT, never as markup', () => {
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

describe('tool output — the message a call SENDS is shown without expanding the card', () => {
  const withMessage = (win, id, message) =>
    win.cc.batch([
      row(id, 0, 'TOOL', 'SendMessage', { meta: 'SendMessage', toolUseId: 'tu-m' + id, message }),
    ]);

  it('renders it as its own block, outside the collapse toggle', () => {
    const win = loadFrontend(['app-transcript.js']);
    withMessage(win, 340, 'Please re-run the failing test and report the first assertion that fails.');

    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('open')).toBe(false);
    expect(card.classList.contains('msg-tool')).toBe(true);

    const block = card.querySelector('.tool-msg pre.message-src');
    expect(block).not.toBeNull();
    expect(block.querySelector('code').textContent).toBe(
      'Please re-run the failing test and report the first assertion that fails.'
    );
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
    expect(win.document.querySelector('.tool-msg')).not.toBeNull();
    expect(readCss()).toMatch(/\.tool-msg:empty\s*\{[^}]*display:\s*none/);
  });
});

describe('transcript prose — nothing caps the running text', () => {
  it('no rule limits the width of a paragraph in a message body', () => {
    const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).toMatch(/\.body p\s*\{/);
    expect(css).not.toMatch(/\.body p[\s,][^{]*\{[^}]*max-width/);
  });
});
