(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const send = CX.send;

  AT.selectedCount = function (): number {
    return AT.tree ? Object.keys(AT.tree.sel).length : 0;
  };

  AT.confirmSelection = function (): void {
    const tree = AT.tree;
    if (!tree) return;
    const paths = Object.keys(tree.sel);
    if (!paths.length) return;
    CX.closeMenu();
    send({ type: 'attachPaths', paths: paths });
  };

  AT.dirState = function (path: string): string {
    const tree = AT.tree;
    if (!tree) return 'none';
    const prefix = path + '/';
    let inside = 0;
    for (const p in tree.sel) {
      if (!Object.prototype.hasOwnProperty.call(tree.sel, p)) continue;
      if (p === path || p.indexOf(prefix) === 0) inside++;
    }
    if (!inside) return 'none';
    const exp = tree.exp[path];
    return exp && inside >= exp.length ? 'all' : 'mixed';
  };

  AT.applyRowState = function (row: HTMLElement, entry: TreeEntry): void {
    const tree = AT.tree;
    if (!tree) return;
    if (!tree.multi) {
      row.removeAttribute('aria-selected');
      row.removeAttribute('aria-checked');
      return;
    }
    if (!entry.directory) {
      row.setAttribute('aria-selected', tree.sel[entry.path] ? 'true' : 'false');
      row.removeAttribute('aria-checked');
      return;
    }
    const state = AT.dirState(entry.path);
    row.setAttribute('aria-selected', state === 'all' ? 'true' : 'false');
    if (state === 'mixed') row.setAttribute('aria-checked', 'mixed');
    else row.removeAttribute('aria-checked');
  };

  AT.syncSelection = function (): void {
    const root = AT.treeEl();
    if (!root) return;
    const rows = root.querySelectorAll<TreeRow>('.tree-row');
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      if (row.__ccPath == null) continue;
      AT.applyRowState(row, { path: row.__ccPath, directory: row.__ccDir });
    }
    const body = AT.bodyEl();
    const head = body ? body.querySelector('.attach-head') : null;
    const done = head ? head.querySelector('.attach-done') : null;
    if (head && done) head.replaceChild(AT.doneButton(), done);
  };

  AT.markPaths = function (paths: string[], on: boolean): void {
    const tree = AT.tree;
    if (!tree) return;
    for (let i = 0; i < paths.length; i++) {
      if (on) tree.sel[paths[i]] = true;
      else delete tree.sel[paths[i]];
    }
  };

  AT.onRowPress = function (entry: TreeEntry, e: MouseEvent): void {
    const tree = AT.tree;
    if (!tree) return;
    const target = e && (e.target as HTMLElement | null);
    const onCaret = !!(target && target.classList && target.classList.contains('tree-caret'));
    if (entry.directory && (onCaret || (!tree.multi && tree.mode === 'files'))) {
      AT.setOpen(entry.path, !AT.openFor(entry.path));
      return;
    }
    if (!tree.multi) {
      CX.closeMenu();
      send({ type: 'attachPaths', paths: [entry.path] });
      return;
    }
    if (!entry.directory) {
      if (tree.sel[entry.path]) delete tree.sel[entry.path];
      else tree.sel[entry.path] = true;
      AT.syncSelection();
      return;
    }
    toggleFolder(entry);
  };

  AT.setOpen = function (path: string, on: boolean): void {
    const tree = AT.tree;
    if (!tree) return;
    if (on) {
      tree.open[path] = true;
      AT.requestChildren(path);
    } else {
      delete tree.open[path];
    }
    AT.renderTree();
  };

  function toggleFolder(entry: TreeEntry): void {
    const tree = AT.tree;
    if (!tree) return;
    const path = entry.path;
    if (AT.dirState(path) === 'all' && tree.exp[path]) {
      AT.markPaths(tree.exp[path], false);
      AT.syncSelection();
      return;
    }
    if (tree.capped[path]) {
      AT.announceCap(entry);
      return;
    }
    if (tree.exp[path]) {
      AT.markPaths(tree.exp[path], true);
      AT.syncSelection();
      return;
    }
    send({ type: 'treeExpand', path: path, mode: tree.mode });
  }

  AT.announceCap = function (entry: TreeEntry): void {
    if (!CC.announce) return;
    CC.announce(
      String(entry.name || entry.path) + ' holds more than can be attached at once — open it and pick inside.'
    );
  };

  cc.treeExpansion = function (payload?: unknown): void {
    const tree = AT.tree;
    const p = payload as { mode?: unknown; path?: unknown; paths?: unknown; truncated?: unknown } | null;
    if (!tree || !p || typeof p !== 'object') return;
    if (String(p.mode || '') !== tree.mode) return;
    const path = p.path != null ? String(p.path) : '';
    const paths = (Array.isArray(p.paths) ? p.paths : []).filter(AT.isText);
    if (p.truncated) {
      tree.capped[path] = true;
      AT.renderTree();
      AT.announceCap({ name: path.slice(path.lastIndexOf('/') + 1), path: path });
      return;
    }
    tree.exp[path] = paths;
    AT.markPaths(paths, true);
    AT.syncSelection();
  };
})();
