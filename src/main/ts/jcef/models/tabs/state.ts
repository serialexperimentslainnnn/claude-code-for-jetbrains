(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const T = (CC.tabbar = CC.tabbar || ({} as TabbarNs));

  interface WalkEntry {
    id: string;
    node: TabNode;
    depth: number;
    root: string | null;
  }

  function send(msg: unknown): void {
    const fn = (window.CC || ({} as CcShared)).send;
    if (typeof fn === 'function') fn(msg);
  }

  T.state = { chats: [], tree: [], tasks: [] };

  T.selected = null;

  function bar(): HTMLElement | null {
    return document.getElementById('tabsbar');
  }

  function nodeById(id: string): TabNode | null {
    for (let i = 0; i < T.state.tree.length; i++) {
      if (T.state.tree[i] && T.state.tree[i].id === id) return T.state.tree[i];
    }
    return null;
  }

  function taskById(id: string): TabNode | null {
    for (let i = 0; i < T.state.tasks.length; i++) {
      if (T.state.tasks[i] && T.state.tasks[i].id === id) return T.state.tasks[i];
    }
    return null;
  }

  function pruneSelection(): void {
    if (!T.selected) return;
    if (T.selected.kind === 'agent') {
      if (!nodeById(T.selected.id)) T.selected = null;
    } else if (T.selected.kind === 'task') {
      if (!taskById(T.selected.id)) T.selected = null;
    }
  }

  function isSelected(kind: string, id: string): boolean {
    return !!T.selected && T.selected.kind === kind && T.selected.id === id;
  }

  function walkTree(): WalkEntry[] {
    const known: Record<string, TabNode> = Object.create(null);
    const kids: Record<string, TabNode[]> = Object.create(null);
    T.state.tree.forEach(function (n) {
      if (n && n.id) known[n.id] = n;
    });
    T.state.tree.forEach(function (n) {
      if (!n || !n.id) return;
      const parent = n.parent == null || !known[n.parent] ? '' : n.parent;
      (kids[parent] = kids[parent] || []).push(n);
    });

    const out: WalkEntry[] = [];
    const seen: Record<string, boolean> = Object.create(null);
    function walk(id: string, depth: number, root: string | null): void {
      (kids[id] || []).forEach(function (n) {
        const nid = n.id as string;
        if (seen[nid]) return;
        seen[nid] = true;
        const branch = depth === 1 ? nid : root;
        out.push({ id: nid, node: n, depth: depth, root: branch });
        walk(nid, depth + 1, branch);
      });
    }
    walk('', 1, null);
    T.state.tree.forEach(function (n) {
      if (!n || !n.id || seen[n.id]) return;
      seen[n.id] = true;
      out.push({ id: n.id, node: n, depth: 1, root: n.id });
    });
    return out;
  }

  function chatWork(): TabWork[] {
    const all = walkTree();
    const branched: Record<string, boolean> = Object.create(null);
    all.forEach(function (e) {
      if (e.depth > 1 && e.root != null) branched[e.root] = true;
    });
    const out: TabWork[] = [];
    all.forEach(function (e) {
      if (e.depth !== 1) return;
      out.push({ kind: 'agent', id: e.id, node: e.node, depth: 1, hasKids: !!branched[e.id] });
    });
    T.state.tasks.forEach(function (t) {
      if (t && t.id) out.push({ kind: 'task', id: t.id, node: t, depth: 1, hasKids: false });
    });
    return out;
  }

  function openBranches(): TabBranch[] {
    if (!T.selected || T.selected.kind !== 'agent') return [];
    const all = walkTree();
    const byId: Record<string, WalkEntry> = Object.create(null);
    all.forEach(function (e) {
      byId[e.id] = e;
    });
    const here = byId[T.selected.id];
    if (!here) return [];

    const path: WalkEntry[] = [];
    const seen: Record<string, boolean> = Object.create(null);
    let cur: WalkEntry | null = here;
    while (cur && !seen[cur.id]) {
      seen[cur.id] = true;
      path.unshift(cur);
      cur = cur.node.parent != null ? byId[cur.node.parent] || null : null;
    }

    const kidsOf: Record<string, WalkEntry[]> = Object.create(null);
    all.forEach(function (e) {
      const parent = e.node.parent != null && byId[e.node.parent] ? e.node.parent : '';
      (kidsOf[parent] = kidsOf[parent] || []).push(e);
    });

    const rows: TabBranch[] = [];
    path.forEach(function (e) {
      const kids = kidsOf[e.id] || [];
      if (!kids.length) return;
      rows.push({
        rootId: e.id,
        rootLabel: (e.node.label as string) || 'Agent',
        items: kids.map(function (k) {
          return {
            kind: 'agent',
            id: k.id,
            node: k.node,
            depth: k.depth,
            hasKids: !!(kidsOf[k.id] && kidsOf[k.id].length),
          };
        }),
      });
    });
    return rows;
  }

  T.send = send;
  T.bar = bar;
  T.nodeById = nodeById;
  T.taskById = taskById;
  T.pruneSelection = pruneSelection;
  T.isSelected = isSelected;
  T.chatWork = chatWork;
  T.openBranches = openBranches;
})();
