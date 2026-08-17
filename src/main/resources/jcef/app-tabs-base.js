/* app-tabs-base.js — the tab bar's shared state and lookups.
 *
 * One subject: what the bar KNOWS — the last payload from the host, which subtab is open, whether a tree panel
 * is up, and the handful of questions the other files ask about all three.
 *
 * The namespace is `CC.tabbar` (there is no module system here, so that object IS the interface between these
 * scripts) and not `CC.tabs`: the method the host calls is `cc.tabs`, and two objects one letter apart
 * (`cc` / `CC`) each holding a `tabs` is a trap rather than an interface.
 *
 * Load order inside the family is deliberate: this file FIRST (it creates the namespace and the state every
 * other file reads), then the flicker guard, the tree panel, the pill and the scroller, then `app-tabs.js`
 * LAST — it owns `render` and the two Kotlin-facing methods, and reaches for all of them.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});

  /** Resolved per call, not captured at load: the module must not hold a stale reference to the bridge. */
  function send(msg) {
    var fn = (window.CC || {}).send;
    if (typeof fn === 'function') fn(msg);
  }

  /** Last payload from the host, so a re-render after a click needs no round-trip. */
  T.state = { chats: [], tree: [], tasks: [] };

  /** What is on screen: `null` (the chat itself), or `{kind:'agent'|'task', id}`. */
  T.selected = null;

  /** The open tree panel: `{el, anchor}`. At most one, like every other popup on the page. */
  T.openPanel = null;

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

  // NB the tree is walked through `parent` links, and a background task through its `owner` — the two fields
  // the host already sends. `openTree` builds its own `childrenOfIn`/`tasksOfIn` over WHICHEVER chat is being
  // shown (this one, or the tab you are hovering), which is why there is no module-level version any more.

  /** Drops a selection whose subject is no longer in the payload. */
  function pruneSelection() {
    if (!T.selected) return;
    // One `else if`, not two `if`s: the first branch can null the selection, and the second would then read
    // `.kind` off it. Caught by the tests, and it would have thrown on any payload that dropped an agent.
    if (T.selected.kind === 'agent') {
      if (!nodeById(T.selected.id)) T.selected = null;
    } else if (T.selected.kind === 'task') {
      if (!taskById(T.selected.id)) T.selected = null;
    }
  }

  function isSelected(kind, id) {
    return !!T.selected && T.selected.kind === kind && T.selected.id === id;
  }

  /** How deep an agent hangs: 1 = the chat's own. Walks the parent links, guarded against a malformed loop. */
  function depthOf(node) {
    var depth = 1;
    var cur = node;
    var guard = 0;
    while (cur && cur.parent && guard++ < 64) {
      cur = nodeById(cur.parent);
      if (cur) depth++;
    }
    return depth;
  }

  /** True when this chat has started anything at all — i.e. when there is a tree to show. */
  function hasWork() {
    return T.state.tree.length > 0 || T.state.tasks.length > 0;
  }

  /**
   * The selected chat's work in the order the SUBTAB ROW draws it: every agent depth-first, so a subagent
   * follows the agent that started it, then the background tasks.
   *
   * ONE walk, exported, because two things need it — the row itself and the flicker guard's signature — and
   * two traversals that have to agree is how a row that never refreshes ships: the guard would describe an
   * order the row does not draw, an identical-looking signature would skip the repaint, and an agent that
   * finished would keep its running colour forever. Same trap `openTree` names about its own children/tasks
   * lookups, answered the same way.
   *
   * Each entry is `{kind:'agent'|'task', id, node, depth}`. [depth] follows [depthOf]: 1 = the chat's own, so
   * `CC.diagramLabel` says `Agent (…)` there and `Subagent (…)` below it, exactly as the tree panel and the
   * Workloads diagram do.
   */
  function workOrder() {
    var out = [];
    var seen = {};
    var known = {};
    T.state.tree.forEach(function (n) {
      if (n && n.id) known[n.id] = n;
    });
    // A parent the payload does not carry is treated as no parent at all: the retention window drops nodes by
    // age, so a live child of an expired parent is a real case, and hanging it at the top level is what keeps
    // it VISIBLE. Dropping it would hide running work behind a bookkeeping detail.
    function childrenOf(id) {
      return T.state.tree.filter(function (n) {
        if (!n || !n.id) return false;
        var parent = n.parent == null || !known[n.parent] ? null : n.parent;
        return parent === id;
      });
    }
    function walk(id, depth) {
      childrenOf(id).forEach(function (n) {
        if (seen[n.id]) return; // a malformed parent link must not loop
        seen[n.id] = true;
        out.push({ kind: 'agent', id: n.id, node: n, depth: depth });
        walk(n.id, depth + 1);
      });
    }
    walk(null, 1);
    // Whatever a cycle in the parent links kept out of the walk. An agent the host sent and the bar refuses to
    // draw is the worse failure: the row is how you reach its transcript at all.
    T.state.tree.forEach(function (n) {
      if (!n || !n.id || seen[n.id]) return;
      seen[n.id] = true;
      out.push({ kind: 'agent', id: n.id, node: n, depth: 1 });
    });
    T.state.tasks.forEach(function (t) {
      if (t && t.id) out.push({ kind: 'task', id: t.id, node: t, depth: 1 });
    });
    return out;
  }

  T.send = send;
  T.bar = bar;
  T.nodeById = nodeById;
  T.taskById = taskById;
  T.pruneSelection = pruneSelection;
  T.isSelected = isSelected;
  T.depthOf = depthOf;
  T.hasWork = hasWork;
  T.workOrder = workOrder;
})();
