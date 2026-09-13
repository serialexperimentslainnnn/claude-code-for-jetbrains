const { loadFrontend } = require('../helpers/load');

function row(id, order, speaker, text, extra = {}) {
  return { id, order, speaker, text, state: 'FINISHED', elapsed: 0, ...extra };
}

function withOwnCall(win, output) {
  win.cc.batch([
    row(1, 0, 'TOOL', 'code ▸ find_symbols', {
      meta: 'mcp__code__run',
      toolUseId: 't1',
      message: 'query: Mcp',
    }),
    row(2, 1, 'TOOL_OUTPUT', output, { meta: 'toon', toolUseId: 't1' }),
  ]);
  return win.document.querySelector('.tool');
}

describe('a result from one of our own servers is drawn from its data, not pasted as text', () => {
  it('uniform rows become a table whose file column opens the editor at the line', () => {
    const win = loadFrontend(['app-transcript.js']);
    const card = withOwnCall(
      win,
      JSON.stringify({
        query: 'Mcp',
        truncated: false,
        symbols: [
          { name: 'McpServer', kind: 'Class', file: 'src/A.kt', line: 11, column: 7, in: 'model.mcp' },
          { name: 'McpServerTest', kind: 'Class', file: 'src/B.kt', line: 20, column: 7, in: '' },
        ],
      })
    );

    const table = card.querySelector('.tool-out .toon table.toon-table');
    expect(table).not.toBeNull();
    expect([...table.querySelectorAll('th')].map((th) => th.textContent)).toEqual([
      'name',
      'kind',
      'file',
      'in',
    ]);
    const link = table.querySelector('tbody tr td.toon-file a.jb-link');
    expect(link.textContent).toBe('src/A.kt:11');
    expect(link.getAttribute('href')).toBe('jb://open?file=src%2FA.kt&line=11');
    expect(card.querySelector('.tool-out pre')).toBeNull();
  });

  it('a batch result becomes one collapsible sub-card per item, its path a link, a read as a code block, a failure marked', () => {
    const win = loadFrontend(['app-transcript.js']);
    const card = withOwnCall(
      win,
      JSON.stringify({
        count: 3,
        failed: 1,
        items: [
          { path: 'src/A.kt', lines: 2, from: 1, to: 2, text: 'fun a()\nfun b()' },
          { path: 'missing.kt', error: 'no such path: missing.kt' },
          { query: 'Mcp', count: 1, matches: [{ file: 'src/B.kt', line: 3 }] },
        ],
      })
    );

    const items = card.querySelectorAll('.tool-out .toon-items > details.toon-item');
    expect(items.length).toBe(3);
    expect(items[0].querySelector('summary a.jb-link').getAttribute('href')).toBe(
      'jb://open?file=src%2FA.kt'
    );
    expect(items[0].querySelector('pre.toon-code code').textContent).toBe('fun a()\nfun b()');
    expect(items[0].querySelector('.toon-fields').textContent).not.toContain('fun a()');
    expect(items[1].classList.contains('toon-item-error')).toBe(true);
    expect(items[1].querySelector('summary .toon-item-err').textContent).toBe('no such path: missing.kt');
    expect(items[2].querySelector('summary').textContent).toBe('Mcp');
    expect(items[2].querySelector('table.toon-table td.toon-file a.jb-link').textContent).toBe('src/B.kt:3');
  });

  it('the scalars around the table stay readable as fields, and a flag reads as a mark', () => {
    const win = loadFrontend(['app-transcript.js']);
    const card = withOwnCall(
      win,
      JSON.stringify({ path: 'src/A.kt', count: 2, truncated: true, problems: [] })
    );

    const keys = [...card.querySelectorAll('.toon-fields .toon-key')].map((k) => k.textContent);
    expect(keys).toEqual(['path', 'count', 'truncated', 'problems']);
    expect(card.querySelector('.toon-truncated').textContent).toBe('✓');

    const off = withOwnCall(win, JSON.stringify({ rows: [{ id: 'a', enabled: false }] }));
    expect(off.querySelector('table.toon-table td.toon-enabled').textContent).toBe('✗');
  });

  it('rows with different shapes fall back to a list of field blocks', () => {
    const win = loadFrontend(['app-transcript.js']);
    const card = withOwnCall(win, JSON.stringify({ symbols: [{ name: 'a', line: 1 }, { name: 'b' }] }));

    expect(card.querySelector('table')).toBeNull();
    expect(card.querySelectorAll('.toon-list > li .toon-fields').length).toBe(2);
  });

  it('a multi-line value keeps its lines, and a unified diff is drawn as a diff', () => {
    const win = loadFrontend(['app-transcript.js']);
    const diff = 'diff --git a/x.kt b/x.kt\n--- a/x.kt\n+++ b/x.kt\n@@ -1,2 +1,2 @@\n-old\n+new\n same';
    const card = withOwnCall(win, JSON.stringify({ path: 'x.kt', files: 1, diff: diff, notes: 'one\ntwo' }));

    const block = card.querySelector('.toon-diff .toon-diff');
    expect(block).not.toBeNull();
    expect(block.querySelector('.toon-diff-add').textContent).toBe('+new\n');
    expect(block.querySelector('.toon-diff-del').textContent).toBe('-old\n');
    expect(block.querySelector('.toon-diff-hunk').textContent).toBe('@@ -1,2 +1,2 @@\n');
    expect(card.querySelector('.toon-notes pre.toon-block').textContent).toBe('one\ntwo');
    expect(card.querySelector('.toon-field.toon-nested .toon-key').textContent).toBe('diff');
  });

  it('what is not JSON is shown as it came', () => {
    const win = loadFrontend(['app-transcript.js']);
    const card = withOwnCall(win, 'error: nothing here');

    expect(card.querySelector('.tool-out .toon pre').textContent).toBe('error: nothing here');
  });

  it('the call itself is on the card while it runs: the tool label and its arguments', () => {
    const win = loadFrontend(['app-transcript.js']);
    win.cc.batch([
      row(1, 0, 'TOOL', 'code ▸ find_symbols', {
        meta: 'mcp__code__run',
        toolUseId: 't1',
        message: 'query: Mcp',
        state: 'RUNNING',
      }),
    ]);
    const card = win.document.querySelector('.tool');

    expect(card.querySelector('.name').textContent).toBe('code ▸ find_symbols');
    expect(card.querySelector('.tool-msg .message-src code').textContent).toBe('query: Mcp');
    card.querySelector('.tool-head').dispatchEvent(new win.Event('click', { bubbles: true }));
    expect(card.classList.contains('open')).toBe(true);
  });
});
