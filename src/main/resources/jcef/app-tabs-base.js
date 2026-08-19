(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});

  function send(msg) {
    var fn = (window.CC || {}).send;
    if (typeof fn === 'function') fn(msg);
  }

  T.state = { chats: [], tree: [], tasks: [] };

  T.selected = null;

  function bar() {
    return document.getElementById('tabsbar');
  }

  function nodeById(id) {
    for (var i = 0; i < T.state.tree.length; i++) {
      if (T.state.tree[i] && T.state.tree[i].id === id) return T.state.tree[i];
    }
    return null;
  }

  function taskById(id) {
    for (var i = 0; i < T.state.tasks.length; i++) {
      if (T.state.tasks[i] && T.state.tasks[i].id === id) return T.state.tasks[i];
    }
    return null;
  }

  function pruneSelection() {
    if (!T.selected) return;
    if (T.selected.kind === 'agent') {
      if (!nodeById(T.selected.id)) T.selected = null;
    } else if (T.selected.kind === 'task') {
      if (!taskById(T.selected.id)) T.selected = null;
    }
  }

  function isSelected(kind, id) {
    return !!T.selected && T.selected.kind === kind && T.selected.id === id;
  }

  function walkTree() {
    var known = Object.create(null);
    var kids = Object.create(null);
    T.state.tree.forEach(function (n) {
      if (n && n.id) known[n.id] = n;
    });
    T.state.tree.forEach(function (n) {
      if (!n || !n.id) return;
      var parent = n.parent == null || !known[n.parent] ? '' : n.parent;
      (kids[parent] = kids[parent] || []).push(n);
    });

    var out = [];
    var seen = Object.create(null);
    function walk(id, depth, root) {
      (kids[id] || []).forEach(function (n) {
        if (seen[n.id]) return;
        seen[n.id] = true;
        var branch = depth === 1 ? n.id : root;
        out.push({ id: n.id, node: n, depth: depth, root: branch });
        walk(n.id, depth + 1, branch);
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

  function chatWork() {
    var all = walkTree();
    var branched = Object.create(null);
    all.forEach(function (e) {
      if (e.depth > 1) branched[e.root] = true;
    });
    var out = [];
    all.forEach(function (e) {
      if (e.depth !== 1) return;
      out.push({ kind: 'agent', id: e.id, node: e.node, depth: 1, hasKids: !!branched[e.id] });
    });
    T.state.tasks.forEach(function (t) {
      if (t && t.id) out.push({ kind: 'task', id: t.id, node: t, depth: 1, hasKids: false });
    });
    return out;
  }

  function openBranches() {
    if (!T.selected || T.selected.kind !== 'agent') return [];
    var all = walkTree();
    var byId = Object.create(null);
    all.forEach(function (e) {
      byId[e.id] = e;
    });
    var here = byId[T.selected.id];
    if (!here) return [];

    var path = [];
    var seen = Object.create(null);
    var cur = here;
    while (cur && !seen[cur.id]) {
      seen[cur.id] = true;
      path.unshift(cur);
      cur = cur.node.parent != null ? byId[cur.node.parent] : null;
    }

    var kidsOf = Object.create(null);
    all.forEach(function (e) {
      var parent = e.node.parent != null && byId[e.node.parent] ? e.node.parent : '';
      (kidsOf[parent] = kidsOf[parent] || []).push(e);
    });

    var rows = [];
    path.forEach(function (e) {
      var kids = kidsOf[e.id] || [];
      if (!kids.length) return;
      rows.push({
        rootId: e.id,
        rootLabel: e.node.label || 'Agent',
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
