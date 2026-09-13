(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const send = CX.send;

  function newDir(): TreeDir {
    return { entries: null, pending: false, truncated: false };
  }

  AT.enterTree = function (mode: string): void {
    const menu = AT.menuEl();
    if (!menu) return;
    AT.view = 'tree';
    AT.tree = {
      mode: mode,
      multi: false,
      query: '',
      dirs: { '': newDir() },
      open: {},
      sel: {},
      exp: {},
      capped: {},
    };
    AT.requestChildren('');
    AT.renderMenu(menu, 'attach-from-right');
  };

  AT.leaveTree = function (): void {
    const menu = AT.menuEl();
    if (!menu) return;
    AT.view = 'root';
    AT.tree = null;
    AT.renderMenu(menu, 'attach-from-left');
  };

  AT.requestChildren = function (path: string): void {
    const tree = AT.tree;
    if (!tree) return;
    const node = tree.dirs[path] || (tree.dirs[path] = newDir());
    if (node.entries || node.pending) return;
    node.pending = true;
    send({ type: 'treeChildren', path: path, mode: tree.mode });
  };

  let matchCache: Record<string, boolean> | null = null;

  AT.resetMatches = function (): void {
    matchCache = {};
  };

  AT.matchesQuery = function (entry: TreeEntry): boolean {
    const tree = AT.tree;
    if (!tree) return false;
    return (
      String(entry.name || '')
        .toLowerCase()
        .indexOf(tree.query) !== -1
    );
  };

  AT.hasMatch = function (path: string): boolean {
    const tree = AT.tree;
    if (!tree) return false;
    if (!matchCache) matchCache = {};
    if (Object.prototype.hasOwnProperty.call(matchCache, path)) return matchCache[path];
    matchCache[path] = false;
    const node = tree.dirs[path];
    let found = false;
    if (node && node.entries) {
      for (let i = 0; i < node.entries.length && !found; i++) {
        const e = node.entries[i];
        found = AT.matchesQuery(e) || (!!e.directory && AT.hasMatch(e.path));
      }
    }
    matchCache[path] = found;
    return found;
  };

  AT.openFor = function (path: string): boolean {
    const tree = AT.tree;
    if (!tree) return false;
    if (path === '') return true;
    if (tree.open[path]) return true;
    return tree.query !== '' && AT.hasMatch(path);
  };

  AT.visibleEntry = function (entry: TreeEntry): boolean {
    const tree = AT.tree;
    if (!tree) return false;
    if (!tree.query) return true;
    return AT.matchesQuery(entry) || (!!entry.directory && AT.hasMatch(entry.path));
  };

  cc.treeChildren = function (payload?: unknown): void {
    const tree = AT.tree;
    const p = payload as { mode?: unknown; path?: unknown; entries?: unknown; truncated?: unknown } | null;
    if (!tree || !p || typeof p !== 'object') return;
    if (String(p.mode || '') !== tree.mode) return;
    const path = p.path != null ? String(p.path) : '';
    const node = tree.dirs[path] || (tree.dirs[path] = newDir());
    node.pending = false;
    node.entries = (Array.isArray(p.entries) ? (p.entries as TreeEntry[]) : []).filter(function (e) {
      return e && AT.isText(e.path) && AT.isText(e.name);
    });
    node.truncated = !!p.truncated;
    if (AT.view === 'tree') AT.renderTree();
  };
})();
