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

// ── jump-to-code links (jb://open) ───────────────────────────────────────────────────────────────────────
// Two halves of the same feature: a file tool's card links its path straight away (the host already told us the
// path is real), while paths/symbols *guessed* in model text are only linked after the host confirms them via
// cc.links — so a path that doesn't exist never becomes a dead link.

describe('transcript — jump-to-code on tool cards', () => {
  it('a file tool renders its project-relative path as a jb://open link inside the label', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(10, 0, 'TOOL', 'Read(src/main/Foo.kt)', { meta: 'Read', toolUseId: 't1', filePath: 'src/main/Foo.kt' }),
    ]);

    const a = win.document.querySelector('a.jb-link');
    expect(a).not.toBeNull();
    expect(a.textContent).toBe('src/main/Foo.kt');
    expect(a.getAttribute('href')).toBe('jb://open?file=' + encodeURIComponent('src/main/Foo.kt'));
    // The tool name around the link survives — the label still reads Read(<path>).
    expect(a.parentNode.textContent).toBe('Read(src/main/Foo.kt)');
  });

  it('a tool row without a filePath is rendered plainly — no link', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(11, 0, 'TOOL', 'Bash(ls -la)', { meta: 'Bash', toolUseId: 't2' })]);
    expect(win.document.querySelector('a.jb-link')).toBeNull();
  });
});

describe('transcript — jump-to-code in model text', () => {
  it('a settled ASSISTANT row asks the host to resolve the path/symbol candidates it found', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([row(12, 0, 'ASSISTANT', 'See `src/main/Foo.kt` — it calls `PermissionBroker`.')]);

    const req = sent.find((m) => m.type === 'resolveLinks');
    expect(req).toBeDefined();
    expect(req.rowId).toBe(12);
    expect(req.paths).toContain('src/main/Foo.kt');
    expect(req.symbols).toContain('PermissionBroker');
  });

  it('only the tokens the host confirmed become links; the rest stay plain text', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([row(13, 0, 'ASSISTANT', 'See `src/main/Foo.kt` and `ghost/Nope.kt`.')]);

    // Host resolved only the first one (the second file does not exist).
    win.cc.links({ rowId: 13, links: [{ token: 'src/main/Foo.kt', path: 'src/main/Foo.kt', line: 12 }] });

    const links = win.document.querySelectorAll('a.jb-link');
    expect(links.length).toBe(1);
    expect(links[0].textContent).toBe('src/main/Foo.kt');
    expect(links[0].getAttribute('href')).toBe(
      'jb://open?file=' + encodeURIComponent('src/main/Foo.kt') + '&line=12',
    );
    expect(win.document.body.textContent).toContain('ghost/Nope.kt'); // still there, just not a link
  });

  /** Regression: a resolved token was linkified as a plain substring, so `src/main/ui` (a real directory) lit up
   *  INSIDE `src/main/ui/Fantasma.kt` (a file that does not exist), and `Session` inside `ClaudeSession`. */
  it('a token is only linked as a whole token, never inside a longer path or word', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([
      row(18, 0, 'ASSISTANT', 'The dir `src/main/ui`, the ghost `src/main/ui/Fantasma.kt`, and `ClaudeSession`.'),
    ]);
    win.cc.links({
      rowId: 18,
      links: [
        { token: 'src/main/ui', path: 'src/main/ui' },
        { token: 'Session', path: 'src/Session.kt' },
      ],
    });

    const links = win.document.querySelectorAll('a.jb-link');
    // Exactly ONE link: the standalone directory. Not the prefix of the ghost path, not inside ClaudeSession.
    expect(links.length).toBe(1);
    expect(links[0].textContent).toBe('src/main/ui');
    expect(links[0].parentNode.textContent).toBe('src/main/ui'); // the whole code span, nothing dangling
    expect(win.document.body.textContent).toContain('src/main/ui/Fantasma.kt');
    expect(win.document.body.textContent).toContain('ClaudeSession');
  });

  it('every occurrence of a token in a row is linked, not just the first', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([row(19, 0, 'ASSISTANT', 'Edit a.kt, then re-read a.kt, then run a.kt again.')]);
    win.cc.links({ rowId: 19, links: [{ token: 'a.kt', path: 'a.kt' }] });

    expect(win.document.querySelectorAll('a.jb-link').length).toBe(3);
  });

  it('cc.links is null-safe and never throws on an unknown row or a junk payload', () => {
    const win = loadFrontend(['app-transcript.js']);
    expect(() => win.cc.links(null)).not.toThrow();
    expect(() => win.cc.links({})).not.toThrow();
    expect(() => win.cc.links({ rowId: 999, links: [{ token: 'x', path: 'x' }] })).not.toThrow();
  });
});

describe('transcript — jump-to-code for directories', () => {
  it('directories are offered as candidates, with or without a trailing slash', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([
      row(14, 0, 'ASSISTANT', 'Look in `build/`, `~/.claude` and `src/main/kotlin`, see `src/main/Foo.kt`.'),
    ]);

    const req = sent.find((m) => m.type === 'resolveLinks');
    expect(req.paths).toContain('build/');
    expect(req.paths).toContain('~/.claude'); // anchored → no trailing slash needed
    expect(req.paths).toContain('src/main/kotlin'); // several segments → no trailing slash needed
    expect(req.paths).toContain('src/main/Foo.kt');
  });

  /** The bug this guards: a long path used to match only up to its last slash, linking the prefix and leaving the
   *  final segment dangling outside the link (`src/main/kotlin/dev/ui` → link on `src/main/kotlin/dev/` + `ui`). */
  it('a path is captured WHOLE — never chopped into a linked prefix plus a dangling last segment', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([row(16, 0, 'ASSISTANT', 'It lives in `src/main/kotlin/dev/lain/claudejb/ui`.')]);

    const req = sent.find((m) => m.type === 'resolveLinks');
    expect(req.paths).toContain('src/main/kotlin/dev/lain/claudejb/ui');
    expect(req.paths).not.toContain('src/main/kotlin/dev/lain/claudejb/'); // the prefix is NOT a candidate
  });

  it('a bare word is never treated as a path', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([row(17, 0, 'ASSISTANT', 'Run the `build` task, it is `slow`.')]);

    const req = sent.find((m) => m.type === 'resolveLinks');
    const paths = req ? req.paths : [];
    expect(paths).not.toContain('build');
    expect(paths).not.toContain('slow');
  });

  it('a directory the host confirmed becomes a link', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([row(15, 0, 'ASSISTANT', 'It lands in `build/distributions/`.')]);
    win.cc.links({ rowId: 15, links: [{ token: 'build/distributions/', path: 'build/distributions' }] });

    const a = win.document.querySelector('a.jb-link');
    expect(a).not.toBeNull();
    expect(a.getAttribute('href')).toBe('jb://open?file=' + encodeURIComponent('build/distributions'));
  });
});
