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
    const copy =
      pre.parentElement.querySelector('.code-head .copy') || body.querySelector('.code-head .copy');
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

  // A Copy button that copies and says nothing is reported as a broken Copy button — it happened. The
  // message-level buttons carry their own click handler, so they never reach the delegated code-head path
  // that flashes; both must confirm, and via the SAME helper so the wording cannot drift apart.
  it('every Copy affordance confirms with "Copied", message-level ones included', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([row(4, 0, 'ASSISTANT', 'plain answer with no code')]);

    const msgCopy = win.document.querySelector('.msg-head .copy');
    expect(msgCopy).not.toBeNull();
    expect(msgCopy.textContent).toBe('Copy');
    msgCopy.click();
    expect(msgCopy.textContent).toBe('Copied');
    expect(msgCopy.classList.contains('copied')).toBe(true);
  });

  it('the user message Copy confirms too', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([row(5, 0, 'USER', 'a prompt I typed')]);

    const userCopy = win.document.querySelector('.msg.user .copy');
    expect(userCopy).not.toBeNull();
    userCopy.click();
    expect(userCopy.textContent).toBe('Copied');
  });
});

describe('transcript — inline diff colouring', () => {
  it('a TOOL_OUTPUT with meta "diff" renders +/- lines with dl-add / dl-del classes', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(4, 0, 'TOOL', 'Edit', { meta: 'Edit', toolUseId: 'tu1' }),
      row(5, 1, 'TOOL_OUTPUT', '@@ -1 +1 @@\n-old line\n+new line\n context', {
        meta: 'diff',
        toolUseId: 'tu1',
      }),
    ]);
    const card = win.document.querySelector('[data-out-id], .tool-out, .msg.tool') || win.document.body;
    const added = card.querySelector('.diff-line.dl-add');
    const removed = card.querySelector('.diff-line.dl-del');
    expect(added).not.toBeNull();
    expect(removed).not.toBeNull();
    expect(added.textContent).toContain('new line');
    expect(removed.textContent).toContain('old line');
  });

  it('a diff on a known-extension file gets hljs syntax highlighting layered under the add/remove colour', () => {
    const win = loadFrontend(['app-transcript.js']); // vendored hljs loaded → real highlighting
    win.cc.batch([
      row(20, 0, 'TOOL', 'Edit(src/Foo.kt)', {
        meta: 'Edit',
        toolUseId: 'tu-diff-kt',
        filePath: 'src/Foo.kt',
      }),
      row(21, 1, 'TOOL_OUTPUT', '@@ -1 +1 @@\n-val x = 1\n+val x = 2', {
        meta: 'diff',
        toolUseId: 'tu-diff-kt',
      }),
    ]);
    const added = win.document.querySelector('.diff-line.dl-add');
    expect(added).not.toBeNull();
    expect(added.querySelector('.hljs-keyword, .hljs-number')).not.toBeNull();
    expect(added.textContent).toContain('val x = 2');
  });

  it('a diff with no resolvable language still renders plain coloured lines (no crash)', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(22, 0, 'TOOL', 'Edit(README)', { meta: 'Edit', toolUseId: 'tu-diff-plain' }),
      row(23, 1, 'TOOL_OUTPUT', '@@ -1 +1 @@\n-old\n+new', { meta: 'diff', toolUseId: 'tu-diff-plain' }),
    ]);
    const added = win.document.querySelector('.diff-line.dl-add');
    expect(added.textContent).toContain('new');
  });
});

// ── syntax highlighting on a file tool's plain output (Read/Write/Edit) ─────────────────────────────────────
describe("transcript — a file tool's plain output is a highlighted, copyable code block", () => {
  it("a Read on a .kt file gets code-head chrome and hljs highlighting from the file's extension", () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(24, 0, 'TOOL', 'Read(src/Foo.kt)', {
        meta: 'Read',
        toolUseId: 'tu-read-kt',
        filePath: 'src/Foo.kt',
      }),
      row(25, 1, 'TOOL_OUTPUT', 'fun main() {}', { toolUseId: 'tu-read-kt' }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-25"]');
    expect(block).not.toBeNull();
    expect(block.querySelector('.code-head .copy')).not.toBeNull();
    expect(block.querySelector('.code-lang').textContent).toBe('kotlin');
    expect(block.querySelector('.hljs-keyword')).not.toBeNull();
  });

  it('a tool with no filePath gets plain output, no code-head', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(26, 0, 'TOOL', 'Task', { meta: 'Task', toolUseId: 'tu-task' }),
      row(27, 1, 'TOOL_OUTPUT', 'subagent finished', { toolUseId: 'tu-task' }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-27"]');
    expect(block.querySelector('.code-head')).toBeNull();
    expect(block.textContent).toContain('subagent finished');
  });
});

// ── command output as a copyable code block ──────────────────────────────────────────────────────────────
// meta is a space-separated tag set for TOOL_OUTPUT (see ClaudeSession.kt): "command", "error", or "command
// error" together — a failing command's stderr is still command output you want to copy. Covers Bash,
// PowerShell, and any MCP tool that executes something. The backend decides in TranscriptModel.isCommandCall,
// which is true when ToolInputScanner.commandText found a command in the tool's INPUT — the shape, not the
// tool name; the frontend only ever sees the resulting meta tag.
describe('transcript — command output renders as a copyable code block', () => {
  it("a Bash tool's output gets the code-head + Copy chrome, like a markdown fence", () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(6, 0, 'TOOL', 'Bash(ls -la)', { meta: 'Bash', toolUseId: 'tu-cmd' }),
      row(7, 1, 'TOOL_OUTPUT', 'total 8\ndrwxr-xr-x  2 me me 4096 file.txt', {
        meta: 'command',
        toolUseId: 'tu-cmd',
      }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-7"]');
    expect(block).not.toBeNull();
    expect(block.classList.contains('command')).toBe(true);
    const head = block.querySelector('.code-head');
    expect(head).not.toBeNull();
    expect(head.querySelector('.copy')).not.toBeNull();
    expect(block.querySelector('code').textContent).toContain('file.txt');
  });

  it("the Copy button copies the command's literal output (delegated handler, no per-node wiring)", () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([
      row(8, 0, 'TOOL', 'Bash(echo hi)', { meta: 'Bash', toolUseId: 'tu-cmd2' }),
      row(9, 1, 'TOOL_OUTPUT', 'hi there', { meta: 'command', toolUseId: 'tu-cmd2' }),
    ]);
    win.document.querySelector('[data-out-id="to-9"] .copy').click();
    expect(sent.some((m) => m.type === 'copy' && m.text === 'hi there')).toBe(true);
  });

  it('a FAILED command ("command error") still renders as a copyable code block', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(10, 0, 'TOOL', 'Bash(false)', { meta: 'Bash', toolUseId: 'tu-cmd3' }),
      row(11, 1, 'TOOL_OUTPUT', 'command not found: nope', { meta: 'command error', toolUseId: 'tu-cmd3' }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-11"]');
    expect(block.classList.contains('command')).toBe(true);
    expect(block.querySelector('.code-head .copy')).not.toBeNull();
  });

  it('a non-command tool\'s plain output is untouched — no code-head, no "command" class', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(12, 0, 'TOOL', 'Read(src/Foo.kt)', { meta: 'Read', toolUseId: 'tu-read' }),
      row(13, 1, 'TOOL_OUTPUT', 'file contents here', { toolUseId: 'tu-read' }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-13"]');
    expect(block.classList.contains('command')).toBe(false);
    expect(block.querySelector('.code-head')).toBeNull();
    expect(block.querySelector('code').textContent).toBe('file contents here');
  });

  it('a plain error (no command tag) still renders as plain text, not a code block', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(14, 0, 'TOOL', 'WebFetch(https://x)', { meta: 'WebFetch', toolUseId: 'tu-fetch' }),
      row(15, 1, 'TOOL_OUTPUT', 'fetch failed', { meta: 'error', toolUseId: 'tu-fetch' }),
    ]);
    const block = win.document.querySelector('[data-out-id="to-15"]');
    expect(block.classList.contains('command')).toBe(false);
    expect(block.querySelector('.code-head')).toBeNull();
  });
});

// ── the command ITSELF as a copyable code block, ALWAYS visible (not gated by collapse) ─────────────────────
// Distinct from the block above: that one is the command's OUTPUT (TOOL_OUTPUT), still behind the collapse
// toggle. This is the command TEXT — entry.command (JcefBridge "command" field, from SensitiveGuard.commandText)
// renders it as its own code-head+Copy block in .tool-cmd, a SIBLING of .tool-out (not nested inside it), so
// it's visible whether the card is open or collapsed. The header no longer carries the raw command text — it
// just names the tool ("Bash"), and the card gets a `cmd-tool` class for its own distinct look.
describe('transcript — the executed command renders as its own always-visible code block', () => {
  it('a Bash tool with entry.command gets a command-src block in .tool-cmd, visible while collapsed', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(16, 0, 'TOOL', 'Bash(grep -R foo src)', {
        meta: 'Bash',
        toolUseId: 'tu-src1',
        command: 'grep -R foo src',
      }),
      row(17, 1, 'TOOL_OUTPUT', 'src/Foo.kt:1:foo', { meta: 'command', toolUseId: 'tu-src1' }),
    ]);
    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('open')).toBe(false); // collapsed by default
    expect(card.classList.contains('cmd-tool')).toBe(true);
    const srcBlock = card.querySelector('.tool-cmd pre.command-src');
    expect(srcBlock).not.toBeNull();
    expect(srcBlock.querySelector('code').textContent).toBe('grep -R foo src');
    expect(srcBlock.querySelector('.code-head .copy')).not.toBeNull();
    // The command block is NOT inside .tool-out (which stays hidden until expanded) — it's a sibling.
    expect(card.querySelector('.tool-out pre.command-src')).toBeNull();
    // The header no longer shows the raw command text — just the tool name.
    const nameEl = win.document.querySelector('.tool-head .name');
    expect(nameEl.textContent).toBe('Bash');
  });

  it('Copy on the command-src block copies the command text (delegated handler)', () => {
    const win = loadFrontend(['app-transcript.js']);
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.batch([
      row(18, 0, 'TOOL', 'Bash(echo hi)', { meta: 'Bash', toolUseId: 'tu-src2', command: 'echo hi' }),
    ]);
    win.document.querySelector('pre.command-src .copy').click();
    expect(sent.some((m) => m.type === 'copy' && m.text === 'echo hi')).toBe(true);
  });

  it('a tool without entry.command (e.g. Read) gets no command-src block, no cmd-tool class, full label', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(19, 0, 'TOOL', 'Read(src/Foo.kt)', { meta: 'Read', toolUseId: 'tu-src3', filePath: 'src/Foo.kt' }),
    ]);
    expect(win.document.querySelector('pre.command-src')).toBeNull();
    expect(win.document.querySelector('.tool').classList.contains('cmd-tool')).toBe(false);
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
      row(10, 0, 'TOOL', 'Read(src/main/Foo.kt)', {
        meta: 'Read',
        toolUseId: 't1',
        filePath: 'src/main/Foo.kt',
      }),
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
      'jb://open?file=' + encodeURIComponent('src/main/Foo.kt') + '&line=12'
    );
    expect(win.document.body.textContent).toContain('ghost/Nope.kt'); // still there, just not a link
  });

  /** Regression: a resolved token was linkified as a plain substring, so `src/main/ui` (a real directory) lit up
   *  INSIDE `src/main/ui/Fantasma.kt` (a file that does not exist), and `Session` inside `ClaudeSession`. */
  it('a token is only linked as a whole token, never inside a longer path or word', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.CC.send = () => {};
    win.cc.batch([
      row(
        18,
        0,
        'ASSISTANT',
        'The dir `src/main/ui`, the ghost `src/main/ui/Fantasma.kt`, and `ClaudeSession`.'
      ),
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

// A tool card must carry a STATE CLASS the moment it appears, not only once something updates it.
//
// This is the regression these tests exist for: the binary emits NO `tool_progress` for an ordinary Bash call
// (verified live — zero progress frames across a 12-second `sleep`), so a running tool row is inserted once
// with state LOADING and then nothing touches it again until its result lands. If the insert path does not
// apply the class, the card sits grey for the whole call and the fade/spin never runs — which is exactly what
// a user sees, with no error anywhere to explain it.
describe('transcript — tool state class on FIRST render', () => {
  it('a TOOL row inserted as LOADING gets the .loading class (no second update needed)', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(90, 0, 'TOOL', 'sleep 30', { meta: 'Bash', toolUseId: 't90', state: 'LOADING' })]);

    const card = win.document.querySelector('.tool');
    expect(card).not.toBeNull();
    expect(card.classList.contains('loading')).toBe(true);
  });

  it('a TOOL row inserted as RUNNING gets the .running class', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(91, 0, 'TOOL', 'build', { meta: 'Bash', toolUseId: 't91', state: 'RUNNING' })]);

    expect(win.document.querySelector('.tool').classList.contains('running')).toBe(true);
  });

  it('the state class is replaced, not accumulated, when the result lands', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([row(92, 0, 'TOOL', 'sleep 1', { meta: 'Bash', toolUseId: 't92', state: 'LOADING' })]);
    win.cc.batch([row(92, 0, 'TOOL', 'sleep 1', { meta: 'Bash', toolUseId: 't92', state: 'FINISHED' })]);

    const card = win.document.querySelector('.tool');
    expect(card.classList.contains('done')).toBe(true);
    expect(card.classList.contains('loading')).toBe(false);
  });
});

// The page half of the transcript memory cap. `TranscriptModel` drops the OLDEST rows past MAX_ENTRIES and
// calls `window.cc.trimRows({ids:[…], total:N})` through `ChatTranscriptView.trimNotice`, where `total` is the
// CUMULATIVE count over the life of the transcript, not the size of this batch.
//
// The two edge cases below are the whole reason the contract is shaped this way, and each has a caller:
//   - `ids: []` is `ChatTranscriptView` on (re)attach, restating the count after `cc.clear()` took the notice
//     away — it must refresh the notice and remove NOTHING (neither "remove everything" nor a no-op);
//   - `total: 0` means the transcript has never been trimmed, so the row is absent, not present and empty.
describe('transcript — trimmed-rows notice (cc.trimRows)', () => {
  const three = () => [
    row(101, 0, 'USER', 'first'),
    row(102, 1, 'ASSISTANT', 'second'),
    row(103, 2, 'USER', 'third'),
  ];

  it('removes exactly the named rows and states the cumulative total', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [101, 102], total: 2 });

    const bodies = [...win.document.querySelectorAll('#conversation .msg .body')];
    expect(bodies.map((b) => b.textContent)).toEqual(['third']);
    const notice = win.document.querySelector('.trim-notice');
    expect(notice).not.toBeNull();
    expect(notice.textContent).toContain('2 earlier rows were dropped');
  });

  it('an EMPTY id list refreshes the notice and removes nothing', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [], total: 40 });

    expect(win.document.querySelectorAll('#conversation .msg').length).toBe(3);
    expect(win.document.querySelector('.trim-notice').textContent).toContain('40 earlier rows were dropped');
  });

  it('a total of 0 means NO notice at all — absent, not present and empty', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [], total: 0 });

    expect(win.document.querySelector('.trim-notice')).toBeNull();
    expect(win.document.querySelectorAll('#conversation .msg').length).toBe(3);
  });

  it('a later trim UPDATES the one notice instead of appending a second', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [101], total: 1 });
    const first = win.document.querySelector('.trim-notice');
    win.cc.trimRows({ ids: [102], total: 2 });

    expect(win.document.querySelectorAll('.trim-notice').length).toBe(1);
    expect(win.document.querySelector('.trim-notice')).toBe(first); // the same node, rewritten
    expect(first.textContent).toContain('2 earlier rows were dropped');
  });

  it('a total of 1 reads as one row, not "1 rows"', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [101], total: 1 });

    expect(win.document.querySelector('.trim-notice').textContent).toContain('1 earlier row was dropped');
  });

  it('the notice sits at the HEAD of the transcript — it stands for what came before', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [101], total: 1 });
    // A row pushed afterwards must not land above it.
    win.cc.batch([row(104, 3, 'USER', 'fourth')]);

    const first = win.document.querySelector('#conversation > .trim-notice, #conversation > .msg');
    expect(first.classList.contains('trim-notice')).toBe(true);
  });

  // 4.1.3 Status Messages: the rows vanish without focus ever moving, so a screen-reader user gets no signal
  // unless the page says so. It goes through the SHARED live region declared in shell.html — never a second
  // one — and it is announced ONCE, when the notice appears: past the cap nearly every added row trims one,
  // and a live region rewritten that often talks over itself and gets switched off.
  it('announces the first trim through the shared live region, and does not re-announce on every later trim', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [101], total: 1 });

    const region = win.document.getElementById('a11y-status');
    expect(region.textContent).toContain('1 earlier row was dropped');

    win.cc.trimRows({ ids: [102], total: 2 });
    expect(region.textContent).toContain('1 earlier row was dropped'); // unchanged; only the row updates
    expect(win.document.querySelector('.trim-notice').textContent).toContain('2 earlier rows were dropped');
  });

  it('an id the page never had is harmless', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch(three());
    win.cc.trimRows({ ids: [999], total: 1 });

    expect(win.document.querySelectorAll('#conversation .msg').length).toBe(3);
    expect(win.document.querySelector('.trim-notice')).not.toBeNull();
  });
});

// What the auto-follow ASKS FOR, which is the only part of it this harness can see.
//
// jsdom lays nothing out and scrolls nothing: `Element.prototype` carries no scrollTo/scrollBy/scrollIntoView
// at all, and scrollTop/scrollLeft are plain properties with none of the CSSOM semantics. So the effect of a
// scroll is unobservable here BY CONSTRUCTION, and asserting a position would be asserting on the assignment
// the test itself made. What IS observable, and is exactly the thing that was wrong, is the argument the call
// carries: `#conversation` declares `scroll-behavior: smooth`, and both a bare `scrollTop` assignment and a
// `behavior: 'auto'` scroll take their behaviour from that declaration — so the case deliberately routed away
// from smooth animated anyway. Naming the behaviour at the call site is the fix, and it is what is pinned.
describe('transcript — the auto-follow names its scroll behaviour', () => {
  // Geometry and the frame callback, both of which jsdom lacks. `distance` = scrollHeight - scrollTop -
  // clientHeight, which is what the module branches on; the frame callback is made synchronous so the scroll
  // happens inside the call instead of a frame later.
  const armed = (distance) => {
    const win = loadFrontend(['app-transcript.js'], { vendor: false });
    const c = win.document.getElementById('conversation');
    Object.defineProperty(c, 'scrollHeight', { value: distance, configurable: true });
    Object.defineProperty(c, 'clientHeight', { value: 0, configurable: true });
    c.scrollTop = 0;
    win.requestAnimationFrame = (fn) => {
      fn();
      return 0;
    };
    const asked = [];
    c.scrollTo = (opts) => asked.push(opts);
    return { win, c, asked };
  };

  const NEAR = 40; // inside NEAR_BOTTOM (80): the transcript is following without being told to
  const FAR = 1200; // past SMOOTH_SCROLL_MAX_PX (400): the jump at the end of a restore

  it('a small step glides — the one case that is meant to animate', () => {
    const { win, asked } = armed(NEAR);
    win.cc.batch([row(900, 0, 'ASSISTANT', 'a delta')]);

    expect(asked.length).toBe(1);
    expect(asked[0].behavior).toBe('smooth');
  });

  it('a long jump asks for INSTANT, and does not leave the answer to the stylesheet', () => {
    // The defect. This branch exists to jump — a fixed-duration animation over a long distance is a crawl the
    // user waits out, and re-issuing one every streaming delta re-aims it so it never arrives. It used to be
    // written `c.scrollTop = c.scrollHeight`, which inherits `scroll-behavior: smooth` from #conversation and
    // therefore did the exact opposite of what it says. 'auto' would not fix it: 'auto' IS "ask the CSS".
    const { win, asked } = armed(FAR);
    win.CC.emit('follow', true); // past NEAR_BOTTOM, so follow is what keeps it sticking
    asked.length = 0;
    win.cc.batch([row(901, 0, 'ASSISTANT', 'a delta')]);

    expect(asked.length).toBe(1);
    expect(asked[0].behavior).toBe('instant');
    expect(asked[0].behavior).not.toBe('auto');
  });

  it('reduced motion makes even the small step instant', () => {
    // WCAG 2.3.3. The preference is host-driven (`CC.reducedMotion`, set by cc.theme) because JCEF renders
    // off-screen and the media query answers "reduce" for everyone. Before the fix this case was the sharpest
    // one: switching motion OFF routed the scroll to the branch that animated.
    const { win, asked } = armed(NEAR);
    win.cc.theme({ reducedMotion: true });
    asked.length = 0;
    win.cc.batch([row(902, 0, 'ASSISTANT', 'a delta')]);

    expect(asked.length).toBe(1);
    expect(asked[0].behavior).toBe('instant');
  });

  it('and it still scrolls where scrollTo does not exist', () => {
    // The capability guard is not decoration: jsdom is that environment, so this is the path the rest of the
    // suite runs on. Removing the guard would throw here rather than fail an assertion.
    const win = loadFrontend(['app-transcript.js'], { vendor: false });
    const c = win.document.getElementById('conversation');
    expect(typeof c.scrollTo).toBe('undefined');
    Object.defineProperty(c, 'scrollHeight', { value: NEAR, configurable: true });
    win.requestAnimationFrame = (fn) => {
      fn();
      return 0;
    };
    win.cc.batch([row(903, 0, 'ASSISTANT', 'a delta')]);

    expect(c.scrollTop).toBe(NEAR);
  });
});

// The find bar: where it is allowed to be, and the three things it does that have each been broken once.
//
// ORDER MATTERS HERE, and it is the harness rather than the product: every `loadFrontend` re-evaluates the
// modules into the SAME window, so each load leaves another `document` keydown listener behind, holding its
// own (now detached) find bar. Escape is answered by the FIRST of them that has an open bar, and that handler
// stops the event dead — so a test that leaves a bar open swallows the Escape of every test after it. The
// Escape case therefore runs before the two that open one. In the page there is exactly one instance.
describe('transcript — the find bar', () => {
  const openFind = (win) =>
    win.document.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true }));
  const bar = (win) => win.document.querySelector('.find-bar');

  it('Escape closes it without interrupting the running turn', () => {
    // The composer's Escape handler sits on its textarea and sends `interrupt`. The find bar's own handler is
    // on `document` in the CAPTURE phase and stops the event there, which is what keeps closing a find bar
    // from also killing the turn — and it is the reason this survives the bar moving in the DOM at all.
    const win = loadFrontend(['app-transcript.js', 'app-composer.js'], { vendor: false });
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state({ turnActive: true });
    openFind(win);
    expect(bar(win).hidden).toBe(false);

    const input = win.document.querySelector('.composer-card textarea, #composer textarea');
    expect(input).not.toBeNull();
    input.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(bar(win).hidden).toBe(true);
    expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
  });

  it('is mounted in the work area, so it cannot cover the tab row', () => {
    const win = loadFrontend(['app-transcript.js'], { vendor: false });
    openFind(win);
    const found = bar(win);
    expect(found).not.toBeNull();
    expect(found.hidden).toBe(false);
    // On the BODY it was positioned against the viewport, and in a narrow tool window it spanned the whole
    // width at the top — over the tabs. Focus that lands on something covered is WCAG 2.2 SC 2.4.11, and the
    // fix is structural rather than a z-index kept out of their way: `#work` does not contain `#tabsbar`, so
    // a bar inside it cannot reach the tabs whatever the stylesheet later says.
    expect(found.parentElement.id).toBe('work');
    expect(win.document.getElementById('work').contains(win.document.getElementById('tabsbar'))).toBe(false);
  });

  it('Enter and Shift+Enter walk the matches, and bring the active one into view', () => {
    const win = loadFrontend(['app-transcript.js'], { vendor: false });
    win.cc.batch([row(910, 0, 'ASSISTANT', 'needle one'), row(911, 1, 'ASSISTANT', 'needle two')]);
    openFind(win);

    // jsdom has no scrollIntoView at all and the module guards for it, so the stub is what makes "it went to
    // the hit" observable — the counter alone would pass with the scroll deleted.
    const into = [];
    win.Element.prototype.scrollIntoView = function (opts) {
      into.push([this.textContent, opts.block]);
    };
    try {
      const input = bar(win).querySelector('.find-input');
      input.value = 'needle';
      input.dispatchEvent(new win.Event('input', { bubbles: true }));

      const count = () => bar(win).querySelector('.find-count').textContent;
      expect(count()).toBe('1 / 2');
      expect(into.length).toBe(1); // a fresh query goes to the first hit

      input.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      expect(count()).toBe('2 / 2');
      input.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true }));
      expect(count()).toBe('1 / 2');
      expect(into.length).toBe(3);
      expect(into[2]).toEqual(['needle', 'center']);
    } finally {
      delete win.Element.prototype.scrollIntoView;
    }
  });
});
