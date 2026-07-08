// Transcript rendering (app-transcript.js). Drives cc.batch([...]) and asserts the DOM, covering the bugs from
// the 4.0.4 / 4.2.0 passes: user prompts rendered VERBATIM (never Markdown), the code-block Copy affordance, and
// coloured inline diffs. Batch item shape mirrors JcefBridge.entryJson: { id, order, speaker, text, meta?, ... }.
const { loadFrontend } = require('./helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

describe('transcript — user prompts render verbatim', () => {
  it('a USER row shows raw text via textContent, never Markdown', () => {
    const win = loadFrontend(['app-transcript.js']);
    const raw = '**bold** # heading `code` *italic*\n    indented';
    win.cc.batch([row(1, 0, 'USER', raw)]);

    const body = win.document.querySelector('.msg.user .body');
    expect(body).not.toBeNull();
    // Literal text preserved…
    expect(body.textContent).toBe(raw);
    // …and NOT parsed into markup (no <strong>/<em>/<h1> from marked).
    expect(body.querySelector('strong, em, h1, code')).toBeNull();
    expect(body.innerHTML).not.toContain('<strong>');
  });
});

describe('transcript — assistant Markdown + code blocks', () => {
  it('an ASSISTANT row renders Markdown and decorates code blocks with a Copy control', () => {
    const win = loadFrontend(['app-transcript.js']); // vendored marked/DOMPurify loaded → real markdown
    win.cc.batch([row(2, 0, 'ASSISTANT', 'Here:\n\n```js\nconst x = 1;\n```')]);

    const body = win.document.querySelector('.msg.assistant .body');
    expect(body).not.toBeNull();
    const pre = body.querySelector('pre');
    expect(pre).not.toBeNull();
    // Decoration: a code-head with a Copy affordance.
    const copy = pre.parentElement.querySelector('.code-head .copy') || body.querySelector('.code-head .copy');
    expect(copy).not.toBeNull();
    expect(pre.querySelector('code').textContent).toContain('const x = 1;');
  });

  it('the delegated Copy handler sends the code text to the host (the listener survives serialization)', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([row(3, 0, 'ASSISTANT', '```\nhello world\n```')]);

    const copy = win.document.querySelector('.code-head .copy');
    expect(copy).not.toBeNull();
    copy.click(); // delegated document handler resolves the sibling <code> text
    expect(sent.some((m) => m.type === 'copy' && /hello world/.test(m.text))).toBe(true);
  });
});

describe('transcript — inline diff colouring', () => {
  it('a TOOL_OUTPUT with meta "diff" renders +/- lines with dl-add / dl-del classes', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(4, 0, 'TOOL', 'Edit', { meta: 'Edit', toolUseId: 'tu1' }),
      row(5, 1, 'TOOL_OUTPUT', '@@ -1 +1 @@\n-old line\n+new line\n context', { meta: 'diff', toolUseId: 'tu1' }),
    ]);
    const card = win.document.querySelector('[data-out-id], .tool-out, .msg.tool') || win.document.body;
    const added = card.querySelector('.diff-line.dl-add');
    const removed = card.querySelector('.diff-line.dl-del');
    expect(added).not.toBeNull();
    expect(removed).not.toBeNull();
    expect(added.textContent).toContain('new line');
    expect(removed.textContent).toContain('old line');
  });
});
