const { loadFrontend, readCss } = require('./helpers/load');

function state(extra) {
  return {
    turnActive: false,
    interrupting: false,
    running: true,
    provider: { id: 'anthropic', label: 'Anthropic', options: [] },
    model: { label: 'Opus', options: [] },
    mode: { wire: 'default', label: 'Default', options: [] },
    effort: { label: 'Default', options: [] },
    thinking: { on: true, options: [] },
    queue: [],
    ...extra,
  };
}

describe('composer — send/stop/interrupting button', () => {
  let win;
  beforeEach(() => {
    win = loadFrontend(['app-composer.js']);
  });

  const sendBtn = () => win.CC.els.composer.querySelector('.send-btn');

  it('idle: the button is a plain Send (no stop/interrupting)', () => {
    win.cc.state(state({ turnActive: false }));
    const b = sendBtn();
    expect(b).not.toBeNull();
    expect(b.classList.contains('stop')).toBe(false);
    expect(b.classList.contains('interrupting')).toBe(false);
    expect(b.title).toBe('Send');
  });

  it('turn active: the button becomes Stop', () => {
    win.cc.state(state({ turnActive: true }));
    const b = sendBtn();
    expect(b.classList.contains('stop')).toBe(true);
    expect(b.classList.contains('interrupting')).toBe(false);
    expect(b.title).toBe('Stop');
  });

  it('interrupting: the button shows a disabled "Interrupting…" state', () => {
    win.cc.state(state({ turnActive: true, interrupting: true }));
    const b = sendBtn();
    expect(b.classList.contains('interrupting')).toBe(true);
    expect(b.title).toBe('Interrupting…');
  });

  it('clicking Stop while a turn is active sends an interrupt', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state(state({ turnActive: true }));
    sendBtn().click();
    expect(sent).toContainEqual({ type: 'interrupt' });
  });

  it('clicking while interrupting does NOT re-send (button is showing Interrupting…)', () => {
    const sent = [];
    win.CC.send = (m) => sent.push(m);
    win.cc.state(state({ turnActive: true, interrupting: true }));
    sendBtn().click();
    expect(sent.filter((m) => m.type === 'interrupt')).toEqual([]);
  });
});

const ROOT = [
  { name: 'src', path: 'src', directory: true },
  { name: 'docs', path: 'docs', directory: true },
  { name: 'README.md', path: 'README.md', directory: false },
];
const SRC = [
  { name: 'main', path: 'src/main', directory: true },
  { name: 'App.kt', path: 'src/App.kt', directory: false },
];

function browser() {
  const win = loadFrontend(['app-composer.js'], { vendor: false });
  const sent = [];
  win.CC.send = (m) => sent.push(m);
  win.cc.state(state());
  const doc = win.document;
  const labelOf = (row) => (row.querySelector('.menu-item-label') || row).textContent;
  const q = {
    win,
    sent,
    mode: 'files',
    of: (type) => sent.filter((m) => m.type === type),
    clip: () => doc.querySelector('.attach-btn'),
    menu: () => doc.querySelector('.attach-menu'),
    popups: () => doc.querySelectorAll('.menu'),
    tree: () => doc.querySelector('.tree'),
    rows: () => Array.from(doc.querySelectorAll('.tree-row')),
    labels: () => q.rows().map(labelOf),
    row: (name) => q.rows().find((r) => labelOf(r) === name),
    caret: (name) => q.row(name).querySelector('.tree-caret'),
    action: (label) =>
      Array.from(doc.querySelectorAll('.attach-list .menu-item')).find((e) => e.textContent === label),
    search: () => doc.querySelector('.attach-search'),
    back: () => doc.querySelector('.attach-back'),
    multi: () => doc.querySelector('.attach-multi'),
    done: () => doc.querySelector('.attach-done'),
    key: (k) =>
      doc.activeElement.dispatchEvent(
        new win.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true })
      ),
    type: (text) => {
      const field = q.search();
      field.value = text;
      field.dispatchEvent(new win.Event('input', { bubbles: true }));
    },
    children: (path, entries, truncated) =>
      win.cc.treeChildren({ path: path, mode: q.mode, entries: entries, truncated: !!truncated }),
    expansion: (path, paths, truncated) =>
      win.cc.treeExpansion({ path: path, mode: q.mode, paths: paths, truncated: !!truncated }),
    enter: (mode) => {
      q.mode = mode === 'directories' ? 'directories' : 'files';
      q.clip().click();
      q.action(q.mode === 'directories' ? 'Directory…' : 'Files…').click();
    },
  };
  return q;
}

describe('the 📎 menu — stepping into the project', () => {
  it('swaps the SAME popup for the project root, with a way back', () => {
    const q = browser();
    q.clip().click();
    expect(q.menu()).toBeTruthy();
    expect(q.popups().length).toBe(1);

    q.action('Files…').click();
    expect(q.popups().length).toBe(1);
    expect(q.tree()).toBeTruthy();
    expect(q.back()).toBeTruthy();
    expect(q.of('treeChildren')).toEqual([{ type: 'treeChildren', path: '', mode: 'files' }]);
  });

  it('the field says what it is searching now, because it was searching something else a moment ago', () => {
    const q = browser();
    q.clip().click();
    expect(q.search().getAttribute('placeholder')).toBe('Search recent files…');
    q.action('Files…').click();
    expect(q.search().getAttribute('placeholder')).toBe('Search files in project…');
    q.back().click();
    expect(q.search().getAttribute('placeholder')).toBe('Search recent files…');
  });

  it('the back arrow returns the attach actions to the same popup', () => {
    const q = browser();
    q.enter('files');
    q.back().click();
    expect(q.tree()).toBeNull();
    expect(q.action('Files…')).toBeTruthy();
    expect(q.popups().length).toBe(1);
  });

  it('the folder picker browses the same tree and says which one it is', () => {
    const q = browser();
    q.enter('directories');
    expect(q.of('treeChildren')).toEqual([{ type: 'treeChildren', path: '', mode: 'directories' }]);
    expect(q.tree().getAttribute('aria-label')).toBe('Project folders');
  });
});

describe('the tree — unfolding in place', () => {
  it('opens a folder inside the tree rather than replacing it with another screen', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    expect(q.labels()).toEqual(['src', 'docs', 'README.md']);

    q.row('src').click();
    q.children('src', SRC);
    expect(q.labels()).toEqual(['src', 'main', 'App.kt', 'docs', 'README.md']);
    expect(q.popups().length).toBe(1);
    const group = q.win.document.getElementById(q.row('src').getAttribute('aria-controls'));
    expect(group.getAttribute('role')).toBe('group');
    expect(group.contains(q.row('App.kt'))).toBe(true);
  });

  it('a closed folder puts NONE of its children in the document', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('src').click();
    q.children('src', SRC);
    q.row('src').click();

    expect(q.labels()).toEqual(['src', 'docs', 'README.md']);
    expect(q.row('src').getAttribute('aria-expanded')).toBe('false');
    const group = q.win.document.getElementById(q.row('src').getAttribute('aria-controls'));
    expect(group).toBeTruthy();
    expect(group.children.length).toBe(0);
  });

  it('asks for a folder’s children ONCE, however often it is folded', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('src').click();
    q.children('src', SRC);
    q.row('src').click();
    q.row('src').click();

    expect(q.of('treeChildren').map((m) => m.path)).toEqual(['', 'src']);
  });
});

describe('the tree — the filter', () => {
  it('opens a closed folder to show a match, and clearing it puts the tree back', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('src').click();
    q.children('src', SRC);
    q.row('src').click();

    q.type('app');
    expect(q.labels()).toEqual(['src', 'App.kt']);
    expect(q.row('src').getAttribute('aria-expanded')).toBe('true');

    q.type('');
    expect(q.labels()).toEqual(['src', 'docs', 'README.md']);
    expect(q.row('src').getAttribute('aria-expanded')).toBe('false');
  });

  it('leaves a folder the user opened exactly as they left it', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('src').click();
    q.children('src', SRC);

    q.type('readme');
    expect(q.labels()).toEqual(['README.md']);
    q.type('');
    expect(q.labels()).toEqual(['src', 'main', 'App.kt', 'docs', 'README.md']);
  });
});

describe('the tree — one press attaches', () => {
  it('a file attaches itself and closes the menu', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('README.md').click();

    expect(q.of('attachPaths')).toEqual([{ type: 'attachPaths', paths: ['README.md'] }]);
    expect(q.menu()).toBeNull();
  });

  it('a folder attaches itself in the folder picker, and merely unfolds in the file one', () => {
    const folders = browser();
    folders.enter('directories');
    folders.children('', [{ name: 'src', path: 'src', directory: true }]);
    folders.row('src').click();
    expect(folders.of('attachPaths')).toEqual([{ type: 'attachPaths', paths: ['src'] }]);

    const files = browser();
    files.enter('files');
    files.children('', ROOT);
    files.row('src').click();
    expect(files.of('attachPaths')).toEqual([]);
    expect(files.row('src').getAttribute('aria-expanded')).toBe('true');
  });
});

describe('the tree — multiple selection', () => {
  it('says that it changed what a row does', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    expect(q.multi().getAttribute('aria-pressed')).toBe('false');
    expect(q.tree().getAttribute('aria-multiselectable')).toBe('false');
    expect(q.row('README.md').getAttribute('aria-selected')).toBeNull();

    q.multi().click();
    expect(q.multi().getAttribute('aria-pressed')).toBe('true');
    expect(q.tree().getAttribute('aria-multiselectable')).toBe('true');
    expect(q.row('README.md').getAttribute('aria-selected')).toBe('false');
    expect(q.done().textContent).toBe('Attach 0');
    expect(q.done().hasAttribute('disabled')).toBe(true);
  });

  it('marking a folder marks what is under it, and the count is the EXPANDED one', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();

    q.row('src').click();
    expect(q.of('treeExpand')).toEqual([{ type: 'treeExpand', path: 'src', mode: 'files' }]);
    q.expansion('src', ['src/App.kt', 'src/main/Foo.kt', 'src/main/Bar.kt']);

    expect(q.done().textContent).toBe('Attach 3');
    expect(q.row('src').getAttribute('aria-selected')).toBe('true');
  });

  it('a folder with only part of its children marked is indeterminate', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();
    q.row('src').click();
    q.expansion('src', ['src/App.kt', 'src/main/Foo.kt', 'src/main/Bar.kt']);
    q.caret('src').click();
    q.children('src', SRC);

    q.row('App.kt').click();
    expect(q.done().textContent).toBe('Attach 2');
    expect(q.row('src').getAttribute('aria-selected')).toBe('false');
    expect(q.row('src').getAttribute('aria-checked')).toBe('mixed');
  });

  it('the selection survives folding the folder it was made in', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();
    q.caret('src').click();
    q.children('src', SRC);

    q.row('App.kt').click();
    expect(q.done().textContent).toBe('Attach 1');
    q.caret('src').click();
    expect(q.row('App.kt')).toBeUndefined();
    expect(q.done().textContent).toBe('Attach 1');
    q.caret('src').click();
    expect(q.row('App.kt').getAttribute('aria-selected')).toBe('true');
  });

  it('refuses a folder over the ceiling, on its own row, instead of attaching half of it', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();

    q.row('docs').click();
    q.expansion('docs', ['docs/a.md', 'docs/b.md'], true);
    expect(q.done().textContent).toBe('Attach 0');
    expect(q.row('docs').querySelector('.tree-cap')).toBeTruthy();

    q.row('docs').click();
    expect(q.of('treeExpand').length).toBe(1);
  });

  it('Done attaches the whole batch in ONE message and closes', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();
    q.row('README.md').click();
    q.row('src').click();
    q.expansion('src', ['src/App.kt']);
    expect(q.done().textContent).toBe('Attach 2');

    q.done().click();
    expect(q.of('attachPaths')).toEqual([{ type: 'attachPaths', paths: ['README.md', 'src/App.kt'] }]);
    expect(q.menu()).toBeNull();
  });

  it('leaving the mode without pressing Done discards', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();
    q.row('README.md').click();
    expect(q.done().textContent).toBe('Attach 1');

    q.multi().click();
    expect(q.done()).toBeNull();
    q.multi().click();
    expect(q.done().textContent).toBe('Attach 0');
    expect(q.of('attachPaths')).toEqual([]);
  });
});

describe('the tree — keyboard', () => {
  it('the arrows walk what is VISIBLE, and Right/Left open and close', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.rows()[0].focus();
    expect(q.win.document.activeElement).toBe(q.row('src'));

    q.key('ArrowDown');
    expect(q.win.document.activeElement).toBe(q.row('docs'));

    q.key('ArrowRight');
    expect(q.row('docs').getAttribute('aria-expanded')).toBe('true');
    expect(q.win.document.activeElement).toBe(q.row('docs'));
    q.key('ArrowLeft');
    expect(q.row('docs').getAttribute('aria-expanded')).toBe('false');
  });

  it('exactly one row is in the tab order', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.rows()[0].focus();
    q.key('ArrowDown');
    const tabbable = q.rows().filter((r) => r.getAttribute('tabindex') === '0');
    expect(tabbable.length).toBe(1);
    expect(tabbable[0]).toBe(q.row('docs'));
  });

  it('Escape in the tree goes BACK; only the attach actions close the menu', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.rows()[0].focus();

    q.key('Escape');
    expect(q.tree()).toBeNull();
    expect(q.menu()).toBeTruthy();
    expect(q.action('Files…')).toBeTruthy();

    q.search().focus();
    q.key('Escape');
    expect(q.menu()).toBeNull();
  });
});

describe('the 📎 tree — the stylesheet holds up its end', () => {
  const css = readCss().replace(/\/\*[\s\S]*?\*\//g, '');

  function ruleBody(selector) {
    const at = css.indexOf(selector + ' {');
    if (at < 0) throw new Error('no rule for ' + selector);
    return css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  }

  it('the renderer writes the depth and the stylesheet decides what it is worth', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.row('src').click();
    q.children('src', SRC);

    expect(q.row('src').style.getPropertyValue('--level')).toBe('1');
    expect(q.row('main').style.getPropertyValue('--level')).toBe('2');
    expect(ruleBody('.tree-row')).toMatch(/padding-left:\s*calc\([^)]*var\(--level/);
  });

  it('a level is folded on its CONTAINER, the way a settings group is', () => {
    expect(ruleBody('.tree-children')).toMatch(/display:\s*none/);
    expect(ruleBody('.tree-node.open > .tree-children')).toMatch(/display:\s*block/);
  });

  it('the caret is drawn from `aria-expanded`, never from a class the row also has to carry', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    const before = q.row('src').className;
    q.row('src').click();
    q.children('src', SRC);
    expect(q.row('src').getAttribute('aria-expanded')).toBe('true');
    expect(q.row('src').className).toBe(before);

    expect(css).toMatch(
      /\.tree-row\[aria-expanded\]:not\(\[aria-expanded='true'\]\)[^{]*\{[^}]*content:\s*'▸'/
    );
    expect(css).toMatch(/\.tree-row\[aria-expanded='true'\][^{]*\{[^}]*content:\s*'▾'/);
    expect(ruleBody('.tree-caret')).toMatch(/flex:\s*0 0 10px/);
  });

  it('a mark is a glyph and not a tone, and it is not announced a second time', () => {
    expect(css).toMatch(/\.tree-row\[aria-selected='true'\]::after\s*\{[^}]*content:\s*'✓'\s*\/\s*''/);
    expect(css).toMatch(/\.tree-row\[aria-checked='mixed'\]::after\s*\{[^}]*content:\s*'–'\s*\/\s*''/);
  });

  it('only the label claims the free space, so the badge and the mark cannot split it', () => {
    const q = browser();
    q.enter('files');
    q.children('', ROOT);
    q.multi().click();
    q.row('docs').click();
    q.expansion('docs', ['docs/a.md', 'docs/b.md'], true);
    q.caret('docs').click();
    q.children('docs', [{ name: 'a.md', path: 'docs/a.md', directory: false }]);
    q.row('a.md').click();

    expect(q.row('docs').querySelector('.tree-cap')).toBeTruthy();
    expect(q.row('docs').getAttribute('aria-checked')).toBe('mixed');

    expect(ruleBody('.tree .menu-item-label')).toMatch(/flex:\s*1 1 auto/);
    expect(ruleBody('.tree-cap')).not.toMatch(/margin-left/);
    expect(css).not.toMatch(/\.tree-row\[aria-(selected|checked)[^{]*::after\s*\{[^}]*margin-left/);
  });

  it('the label is capped on the LABEL, because an inline max-width on the popup would win', () => {
    expect(ruleBody('.tree .menu-item-label')).toMatch(/text-overflow:\s*ellipsis/);
    expect(ruleBody('.tree .menu-item-label')).toMatch(/min-width:\s*0/);
  });

  it('the multiple-selection toggle says it is on with more than a hue', () => {
    const rule = ruleBody(".attach-multi[aria-pressed='true']");
    expect(rule).toMatch(/color:\s*var\(--accent\)/);
    expect(rule).toMatch(/font-weight:/);
  });

  it('stepping between the two views slides, and the direction has a rule', () => {
    const q = browser();
    q.clip().click();
    q.action('Files…').click();
    expect(q.win.document.querySelector('.attach-body').className).toMatch(/attach-from-right/);
    q.back().click();
    expect(q.win.document.querySelector('.attach-body').className).toMatch(/attach-from-left/);

    expect(css).toMatch(/@keyframes attach-in-right\s*\{/);
    expect(css).toMatch(/@keyframes attach-in-left\s*\{/);
    expect(ruleBody('.attach-from-right')).toMatch(/animation:\s*attach-in-right/);
    expect(ruleBody('.attach-from-left')).toMatch(/animation:\s*attach-in-left/);
  });
});
