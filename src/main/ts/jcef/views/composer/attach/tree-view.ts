(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const h = CX.h;

  let groupSeq = 0;

  const MODES: Record<string, { title: string; search: string }> = {
    files: { title: 'Project files', search: 'Search files in project…' },
    directories: { title: 'Project folders', search: 'Search folders in project…' },
  };

  AT.buildTreeView = function (body: HTMLElement): void {
    const tree = AT.tree;
    if (!tree) return;
    const conf = MODES[tree.mode] || MODES.files;

    const back = h(
      'button',
      {
        class: 'attach-back',
        title: 'Back',
        attrs: { type: 'button', 'aria-label': 'Back to the attach menu' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            AT.leaveTree();
          },
        },
      },
      h('span', { text: '←', attrs: { 'aria-hidden': 'true' } })
    );
    const multi = h('button', {
      class: 'attach-multi',
      title: 'Select multiple',
      text: 'Multiple',
      attrs: {
        type: 'button',
        'aria-label': 'Select multiple',
        'aria-pressed': tree.multi ? 'true' : 'false',
      },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          AT.setMulti(!tree.multi);
        },
      },
    });
    const head = h(
      'div',
      { class: 'attach-head' },
      back,
      h('span', { class: 'attach-title', text: conf.title }),
      multi
    );
    if (tree.multi) head.appendChild(AT.doneButton());

    const search = h('input', {
      class: 'attach-search',
      attrs: { type: 'text', placeholder: conf.search, 'aria-label': conf.search },
    }) as HTMLInputElement;
    search.value = tree.query;
    search.addEventListener('input', function () {
      tree.query = search.value.toLowerCase();
      AT.renderTree();
    });

    const list = h('div', { class: 'attach-list' });
    list.appendChild(
      h('div', {
        class: 'tree',
        attrs: {
          role: 'tree',
          'aria-label': conf.title,
          'aria-multiselectable': tree.multi ? 'true' : 'false',
        },
      })
    );

    body.appendChild(head);
    body.appendChild(search);
    body.appendChild(list);
    AT.renderTree();
  };

  AT.doneButton = function (): HTMLElement {
    const n = AT.selectedCount();
    const btn = h('button', {
      class: 'attach-done',
      text: 'Attach ' + n,
      attrs: { type: 'button' },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          AT.confirmSelection();
        },
      },
    });
    if (!n) btn.setAttribute('disabled', 'disabled');
    return btn;
  };

  AT.setMulti = function (on: boolean): void {
    const tree = AT.tree;
    if (!tree) return;
    tree.multi = !!on;
    if (!tree.multi) tree.sel = {};
    const body = AT.bodyEl();
    if (!body) return;
    body.innerHTML = '';
    AT.buildTreeView(body);
    AT.reposition();
    if (CC.announce) CC.announce(tree.multi ? 'Multiple selection on' : 'Multiple selection off');
  };

  AT.treeEl = function (): HTMLElement | null {
    const body = AT.bodyEl();
    return body ? body.querySelector<HTMLElement>('.tree') : null;
  };

  AT.renderTree = function (): void {
    const tree = AT.tree;
    const root = AT.treeEl();
    if (!tree || !root) return;
    const list = root.parentNode as HTMLElement | null;
    const scroll = list ? list.scrollTop : 0;
    const active = document.activeElement as TreeRow | null;
    const hadFocus = root.contains(active);
    const wanted = hadFocus && active && active.__ccPath != null ? active.__ccPath : null;

    AT.resetMatches();
    root.setAttribute('aria-multiselectable', tree.multi ? 'true' : 'false');
    root.innerHTML = '';
    fillChildren(root, '', 1);

    if (list) list.scrollTop = scroll;
    const target = (wanted != null && AT.rowByPath(wanted)) || null;
    if (hadFocus) AT.rows.focus(target || AT.visibleRows()[0]);
    else AT.rows.set(AT.visibleRows()[0]);
    AT.reposition();
  };

  function fillChildren(into: HTMLElement, path: string, level: number): void {
    const tree = AT.tree;
    if (!tree) return;
    const node = tree.dirs[path];
    if (!node || node.pending) {
      into.appendChild(noteRow('Loading…', level));
      return;
    }
    if (!node.entries) return;
    const shown = node.entries.filter(AT.visibleEntry);
    if (!shown.length) {
      into.appendChild(noteRow(tree.query ? 'Nothing matches here.' : 'Nothing to attach here.', level));
      return;
    }
    shown.forEach(function (entry) {
      into.appendChild(nodeFor(entry, level));
    });
    if (node.truncated) {
      const more = 'Only the first ' + shown.length + ' are shown — this folder holds more.';
      into.appendChild(noteRow(more, level));
    }
  }

  function noteRow(text: string, level: number): HTMLElement {
    const row = h('div', {
      class: 'menu-item tree-row tree-note',
      text: text,
      attrs: { role: 'treeitem', 'aria-disabled': 'true', 'aria-level': String(level), tabindex: '-1' },
    });
    row.style.setProperty('--level', String(level));
    return row;
  }

  function nodeFor(entry: TreeEntry, level: number): HTMLElement {
    const tree = AT.tree as TreeState;
    const isDir = !!entry.directory;
    const open = isDir && AT.openFor(entry.path);
    const wrap = h('div', { class: 'tree-node' + (open ? ' open' : '') });
    const row = h('button', {
      class: 'menu-item settings-item tree-row',
      title: entry.path,
      attrs: {
        type: 'button',
        role: 'treeitem',
        'aria-level': String(level),
        tabindex: '-1',
      },
      on: {
        click: function (e: MouseEvent) {
          e.preventDefault();
          e.stopPropagation();
          AT.onRowPress(entry, e);
        },
      },
    }) as TreeRow;
    row.style.setProperty('--level', String(level));
    row.__ccPath = entry.path;
    row.__ccDir = isDir;
    row.appendChild(h('span', { class: 'tree-caret', attrs: { 'aria-hidden': 'true' } }));
    row.appendChild(
      h('span', {
        class: 'attach-icon',
        html: isDir ? AT.folderGlyph() : AT.fileIconGlyph(AT.extOf(entry.name)),
      })
    );
    row.appendChild(h('span', { class: 'menu-item-label', text: String(entry.name || entry.path) }));
    if (isDir && tree.capped[entry.path]) {
      row.appendChild(h('span', { class: 'tree-cap', text: 'Too many' }));
    }
    AT.applyRowState(row, entry);

    if (isDir) {
      const id = 'tree-group-' + ++groupSeq;
      row.setAttribute('aria-expanded', open ? 'true' : 'false');
      row.setAttribute('aria-controls', id);
      const kids = h('div', {
        class: 'tree-children',
        attrs: { role: 'group', 'aria-label': String(entry.name || entry.path), id: id },
      });
      if (open) fillChildren(kids, entry.path, level + 1);
      wrap.appendChild(row);
      wrap.appendChild(kids);
      return wrap;
    }
    wrap.appendChild(row);
    return wrap;
  }
})();
