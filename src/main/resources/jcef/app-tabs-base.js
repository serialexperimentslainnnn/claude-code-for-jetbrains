/* app-tabs-base.js — the tab bar's shared state and lookups.
 *
 * Owns: the tab bar's shared state and lookups.
 *
 * One subject: what the bar KNOWS — the last payload from the host, which subtab is open, and the handful of
 * questions the other files ask about both.
 *
 * The namespace is `CC.tabbar` (there is no module system here, so that object IS the interface between these
 * scripts) and not `CC.tabs`: the method the host calls is `cc.tabs`, and two objects one letter apart
 * (`cc` / `CC`) each holding a `tabs` is a trap rather than an interface.
 *
 * Load order inside the family is deliberate: this file FIRST (it creates the namespace and the state every
 * other file reads), then the flicker guard, the pill and the scroller, then `app-tabs.js` LAST — it owns
 * `render` and the Kotlin-facing methods, and reaches for all of them.
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

  /**
   * ONE walk of the agent tree, depth-first, and the only one. Everything either row draws is a filter of it.
   *
   * Each entry is `{id, node, depth, root}`. [depth] is 1 for an agent the chat itself started and one more
   * per level below, which is what makes `CC.diagramLabel` say `Agent (…)` at the top and `Subagent (…)`
   * under it. [root] is the DEPTH-1 ANCESTOR — itself for a depth-1 agent — and it is the whole reason this
   * function returns what it does: it turns "which branch is this in?" into a field comparison, for the row
   * that draws a branch and for the guard that has to describe it, without either walking the tree again.
   * Two traversals that have to agree is how a row that never refreshes ships.
   *
   * **Linear, not quadratic.** The children are indexed once instead of re-filtering the whole payload per
   * node. The guard calls this on EVERY push from the host — several times a turn — and the session this
   * feature exists for runs dozens of agents, so an `O(n²)` walk here is paid on pushes that change nothing.
   *
   * `Object.create(null)` for both maps: the keys are ids from the binary, and a plain object would answer
   * `known['constructor']` with a function.
   */
  function walkTree() {
    var known = Object.create(null);
    var kids = Object.create(null);
    T.state.tree.forEach(function (n) {
      if (n && n.id) known[n.id] = n;
    });
    // A parent the payload does not carry is treated as no parent at all: the retention window drops nodes by
    // age, so a live child of an expired parent is a real case, and hanging it at the top level is what keeps
    // it VISIBLE. Dropping it would hide running work behind a bookkeeping detail.
    T.state.tree.forEach(function (n) {
      if (!n || !n.id) return;
      var parent = n.parent == null || !known[n.parent] ? '' : n.parent;
      (kids[parent] = kids[parent] || []).push(n);
    });

    var out = [];
    var seen = Object.create(null);
    function walk(id, depth, root) {
      (kids[id] || []).forEach(function (n) {
        if (seen[n.id]) return; // a malformed parent link must not loop
        seen[n.id] = true;
        var branch = depth === 1 ? n.id : root;
        out.push({ id: n.id, node: n, depth: depth, root: branch });
        walk(n.id, depth + 1, branch);
      });
    }
    walk('', 1, null);
    // Whatever a cycle in the parent links kept out of the walk, hoisted to the top level. An agent the host
    // sent and the bar refuses to draw is the worse failure: a row is how you reach its transcript at all,
    // and inside a cycle there is no depth-1 ancestor for a branch row to hang it from.
    T.state.tree.forEach(function (n) {
      if (!n || !n.id || seen[n.id]) return;
      seen[n.id] = true;
      out.push({ id: n.id, node: n, depth: 1, root: n.id });
    });
    return out;
  }

  /**
   * The SECOND row: the agents the CHAT started, then its background tasks.
   *
   * Top level only — a subagent belongs under the agent that invoked it, and reaching it means opening that
   * agent (see [openBranches]). Flattening every depth into this row is what put a chat's whole tree in one
   * strip, which is unreadable at the dozens this feature exists for and says nothing about who started what.
   *
   * **Background tasks stay HERE, at the chat's level, whoever started them.** A task is not a conversation:
   * it has no transcript and nothing under it, it is the plugin's own record of a running process, and it
   * routinely outlives the agent that spawned it — which is why the registry exists at all. Filing it under
   * that agent would make a live task's output unreachable whenever its owner's branch is closed, and the
   * output is the entire point of the row. Its owner is still on the card in the Workloads diagram.
   *
   * Each entry is `{kind:'agent'|'task', id, node, depth}`, shaped like [openBranches]'s so one pill builder
   * draws both rows.
   */
  function chatWork() {
    var all = walkTree();
    // Which of these started something. It is what the pill announces as `aria-expanded`, so a screen-reader
    // user is told which agents can be opened into a branch BEFORE clicking one — and it is a boolean, so
    // the churn of statuses inside a closed branch still moves nothing the bar draws.
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

  /**
   * The rows BELOW the chat's own: one per open level, each holding the children of the agent open above it.
   *
   * **ONE LEVEL PER ROW, and as many rows as the tree is deep.** It used to be a single row holding the whole
   * subtree of the depth-1 ancestor, flattened — which made a sub-subagent visible but gave it no row of its
   * own, so selecting one opened nothing and the bar stopped at three rows however deep the work went. Depth
   * is not bounded by the protocol and an agent that spawns agents that spawn agents is the session this
   * feature exists for, so the bar follows it down.
   *
   * The rows are the PATH from the depth-1 ancestor to the open subtab, inclusive, and a node contributes a
   * row only when it started something. Two properties fall out of that, and they are the ones the flattened
   * row was chosen for in the first place:
   *  - **the row you clicked in does not move.** Picking a sibling changes which of ITS children are drawn
   *    below, never the row that holds it, because that row is the children of the same parent either way;
   *  - **nothing empties under the pointer.** A leaf starts no row, so opening one leaves every row above it
   *    exactly as it was rather than replacing the last one with a blank strip.
   *
   * Returns `[]` for a selection that is not an agent, for one the payload no longer carries, and for a
   * selected agent that started nothing — a row that takes height and says nothing is worse than no row.
   */
  function openBranches() {
    if (!T.selected || T.selected.kind !== 'agent') return [];
    var all = walkTree();
    var byId = Object.create(null);
    all.forEach(function (e) {
      byId[e.id] = e;
    });
    var here = byId[T.selected.id];
    if (!here) return [];

    // Up to the top, then read back down. The seen-set is the cycle guard: the parent links come from the
    // binary, and a malformed one must shorten the path rather than hang the page.
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
