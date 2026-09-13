(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  AT.rowByPath = function (path: string): TreeRow | null {
    const root = AT.treeEl();
    if (!root) return null;
    const rows = root.querySelectorAll<TreeRow>('.tree-row');
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].__ccPath === path) return rows[i];
    }
    return null;
  };

  AT.visibleRows = function (): TreeRow[] {
    const root = AT.treeEl();
    return root ? Array.prototype.slice.call(root.querySelectorAll('.tree-row')) : [];
  };

  AT.rows = CX.roving(function () {
    return AT.visibleRows();
  });

  function parentRow(path: string): TreeRow | null {
    const cut = String(path).lastIndexOf('/');
    return cut > 0 ? AT.rowByPath(String(path).slice(0, cut)) : null;
  }

  AT.onMenuKey = function (e: KeyboardEvent): void {
    if (AT.view !== 'tree') {
      if (e.key === 'Escape' || e.key === 'Esc') {
        e.preventDefault();
        CX.closeMenu();
      }
      return;
    }
    if (e.key === 'Escape' || e.key === 'Esc') {
      e.preventDefault();
      e.stopPropagation();
      AT.leaveTree();
      return;
    }
    const row = document.activeElement as TreeRow | null;
    if (!row || !row.classList || !row.classList.contains('tree-row')) {
      if (e.key === 'ArrowDown' || e.key === 'Down') {
        e.preventDefault();
        AT.rows.focus(AT.visibleRows()[0]);
      }
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'Down') {
      e.preventDefault();
      AT.rows.step(1);
    } else if (e.key === 'ArrowUp' || e.key === 'Up') {
      e.preventDefault();
      AT.rows.step(-1);
    } else if (e.key === 'ArrowRight' || e.key === 'Right') {
      e.preventDefault();
      onRight(row);
    } else if (e.key === 'ArrowLeft' || e.key === 'Left') {
      e.preventDefault();
      onLeft(row);
    } else if (e.key === 'Home') {
      e.preventDefault();
      AT.rows.focus(AT.visibleRows()[0]);
    } else if (e.key === 'End') {
      const all = AT.visibleRows();
      e.preventDefault();
      AT.rows.focus(all[all.length - 1]);
    }
  };

  function onRight(row: TreeRow): void {
    if (!row.__ccDir || row.__ccPath == null) return;
    const path = row.__ccPath;
    if (row.getAttribute('aria-expanded') !== 'true') {
      AT.setOpen(path, true);
      AT.rows.focus(AT.rowByPath(path));
      return;
    }
    const all = AT.visibleRows();
    const at = all.indexOf(AT.rowByPath(path) as TreeRow);
    if (at >= 0 && at + 1 < all.length) AT.rows.focus(all[at + 1]);
  }

  function onLeft(row: TreeRow): void {
    if (row.__ccPath == null) return;
    const path = row.__ccPath;
    if (row.__ccDir && row.getAttribute('aria-expanded') === 'true') {
      AT.setOpen(path, false);
      AT.rows.focus(AT.rowByPath(path));
      return;
    }
    const up = parentRow(path);
    if (up) AT.rows.focus(up);
  }
})();
