/* app-tabs-tree.js — the panel behind the ⋮: every agent, subagent and background task, as a menu.
 *
 * One subject: what opens when you rest on a tab, what it lists, how long it lingers and how it goes away.
 * The bar itself (the pills, the row, the scrolling) is elsewhere; this file only ever appends to
 * `document.body` and hands the picks back through the navigation the spine owns.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var T = (CC.tabbar = CC.tabbar || {});
  var h = CC.h;

  /**
   * Hover timers: a short delay before opening, and THREE SECONDS of grace before it goes.
   *
   * Three seconds because leaving the popup is not the same as being done with it: the pointer crosses the
   * gap on the way to another card, or drifts off while you read a label. Any movement over the diagram
   * cancels the countdown and starts it again, so it only ever expires when you have genuinely walked away.
   */
  /** A full second of resting on the tab before the menu appears — a glance across the bar costs nothing. */
  var HOVER_OPEN_MS = 1000;
  var HOVER_CLOSE_MS = 3000;
  /** Must outlast the CSS slide (`.tab-tree` transition) or the panel is removed mid-animation. */
  var SLIDE_MS = 160;
  var openTimer = null;
  var closeTimer = null;

  function cancelOpen() {
    if (openTimer) {
      clearTimeout(openTimer);
      openTimer = null;
    }
  }

  function cancelClose() {
    if (closeTimer) {
      clearTimeout(closeTimer);
      closeTimer = null;
    }
  }

  /**
   * Opens after [HOVER_OPEN_MS].
   *
   * The delay is what makes crossing the bar on the way somewhere else free: without it, dragging the
   * pointer over the tabs flashes the tree open and shut.
   */
  function scheduleOpen(anchor, rootId, chatId) {
    cancelClose();
    if (T.openPanel && T.openPanel.anchor === anchor) return;
    cancelOpen();
    openTimer = setTimeout(function () {
      openTimer = null;
      openTree(anchor, rootId, chatId);
    }, HOVER_OPEN_MS);
  }

  function scheduleClose() {
    cancelOpen();
    cancelClose();
    closeTimer = setTimeout(function () {
      closeTimer = null;
      closeTree();
    }, HOVER_CLOSE_MS);
  }

  /**
   * The chat's agents, subagents and background tasks, as a MENU: one row each, indented by depth.
   *
   * A list, not a diagram, and that is the whole point — the diagram belongs in the Workloads view where it
   * has room. Every popup version of it was clipped by the browser component (an HTML element cannot paint
   * outside the embedded browser, whatever the CSS says), so it was permanently too small to read. A menu
   * has nothing to clip: it needs no zoom, no dragging and no fitting, and picking one thing out of a list
   * is the actual question being asked here.
   *
   * [rootId] roots it at ONE agent — a pinned tab lists what IT started. [chatId] picks which chat's work to
   * list, so a tab you are not in still answers for itself.
   */

  function openTree(anchor, rootId, chatId) {
    closeTree(true);
    var seen = {};
    var foreign = null;
    if (chatId != null) {
      for (var ci = 0; ci < T.state.chats.length; ci++) {
        if (T.state.chats[ci] && T.state.chats[ci].id === chatId && !T.state.chats[ci].selected) {
          foreign = T.state.chats[ci];
        }
      }
    }
    // The tree being drawn: another chat's (read-only), or ours.
    var nodesOf = foreign ? foreign.tree || [] : T.state.tree;
    var tasksAll = foreign ? foreign.tasks || [] : T.state.tasks;

    function childrenOfIn(parentId) {
      return nodesOf.filter(function (n) {
        return n && (n.parent == null ? null : n.parent) === parentId;
      });
    }
    function tasksOfIn(ownerId) {
      return tasksAll.filter(function (t) {
        return t && (t.owner == null ? null : t.owner) === ownerId;
      });
    }

    function subtreeOf(agentId, depth) {
      var kids = childrenOfIn(agentId).map(function (n) {
        if (seen[n.id]) return null; // a malformed parent link must not loop
        seen[n.id] = true;
        return {
          id: n.id,
          kind: 'agent',
          // `Agent (…)` at the chat's own level, `Subagent (…)` below it — one rule, shared with the
          // Workloads diagram and with the transcript's own cards.
          label: CC.diagramLabel('agent', depth, n.label || 'Agent'),
          meta: n.type || '',
          status: n.status || null,
          running: n.status === 'running',
          selected: !foreign && T.isSelected('agent', n.id),
          title: (n.label || '') + (n.type ? '  ·  ' + n.type : '') + (n.status ? '  ·  ' + n.status : ''),
          onPick: foreign
            ? function () {
                // Another chat's agent: go to that chat first. Painting its transcript in this browser
                // would show one chat's work under another chat's tab.
                closeTree();
                T.send({ type: 'selectChat', chatId: foreign.id });
              }
            : function () {
                closeTree();
                T.showAgent(n.id);
              },
          children: subtreeOf(n.id, depth + 1),
        };
      });
      return kids.filter(Boolean).concat(tasksOfIn(agentId).map(taskNode));
    }

    function taskNode(t) {
      return {
        id: t.id,
        kind: 'task',
        label: CC.diagramLabel('task', 1, t.label || t.type || 'background'),
        meta: t.type || 'background task',
        // The host names the state (one vocabulary for every view — see JcefStatus). This module used to
        // say `done` while the dashboard said `completed`, so one task had two colours.
        status: t.status || null,
        running: !!t.running,
        selected: !foreign && T.isSelected('task', t.id),
        title: (t.label || '') + (t.running ? '  ·  running' : '  ·  finished'),
        onPick: foreign
          ? function () {
              closeTree();
              T.send({ type: 'selectChat', chatId: foreign.id });
            }
          : function () {
              closeTree();
              T.showTask(t.id);
            },
        children: [],
      };
    }

    // Looked up in the tree BEING DRAWN, not in the selected chat's. A pinned tab names the agent it is
    // pinned to, and that agent lives in whichever chat's tree we are showing — resolving it against
    // `state.tree` found nothing whenever the two differed, so the root silently fell back to the chat and
    // the popup showed the whole thing instead of that agent's subtree.
    var rootNode = null;
    if (rootId) {
      for (var ri = 0; ri < nodesOf.length; ri++) {
        if (nodesOf[ri] && nodesOf[ri].id === rootId) rootNode = nodesOf[ri];
      }
    }
    var depth = rootNode ? T.depthOf(rootNode) : 0;
    var children = subtreeOf(rootId || null, depth + 1);
    // A pinned tab whose agent started nothing shows JUST that agent — one card, no children. Falling back
    // to the whole chat here is what made a leaf tab open the entire tree, which is the opposite of what its
    // ⋮ promises.
    if (rootNode && !children.length) children = [];
    if (!rootNode) {
      // A task whose owner never became known would otherwise be invisible, and an honest gap has to be
      // VISIBLE to be honest: it hangs off the chat instead.
      children = children.concat(
        tasksAll
          .filter(function (t) {
            return (
              t &&
              t.owner != null &&
              !nodesOf.some(function (n) {
                return n && n.id === t.owner;
              })
            );
          })
          .map(taskNode)
      );
    }
    // Nothing to show only when there is no root either. A pinned tab with an empty subtree still draws its
    // own card — "this one started nothing" is an answer, and an empty popup is not.
    if (!children.length && !rootNode) return;

    var chat = foreign;
    if (!chat) {
      for (var i = 0; i < T.state.chats.length; i++) {
        if (T.state.chats[i] && T.state.chats[i].selected) chat = T.state.chats[i];
      }
    }
    var root = rootNode
      ? {
          id: rootNode.id,
          kind: 'agent',
          label: CC.diagramLabel('agent', depth, rootNode.label || 'Agent'),
          meta: rootNode.type || '',
          status: rootNode.status || null,
          running: rootNode.status === 'running',
          selected: T.isSelected('agent', rootNode.id),
          title: rootNode.label || '',
          onPick: function () {
            closeTree();
            T.showAgent(rootNode.id);
          },
          children: children,
        }
      : {
          id: '__chat',
          kind: 'chat',
          label: (chat && chat.title) || 'Chat',
          selected: !foreign && !T.selected,
          title: foreign ? 'Go to ' + (chat && chat.title) : "This chat's own transcript",
          onPick: foreign
            ? function () {
                closeTree();
                T.send({ type: 'selectChat', chatId: foreign.id });
              }
            : function () {
                closeTree();
                T.showChat();
              },
          children: children,
        };
    // A MENU, not a diagram.
    //
    // The diagram lives in the Workloads view, where it has room. Here the question is only "which one do I
    // want to open", and the honest answer to that is a list: it cannot be clipped by the browser component
    // (which is what cut every popup version of this), it needs no zoom, no drag and no fitting, and the
    // indentation carries the hierarchy well enough to pick from.
    var rows = [];
    flatten(root, 0, rows);
    var panel = h('div', { class: 'tab-menu', attrs: { role: 'listbox' } }, rows);
    // Stays while you are in it, and any movement restarts the three-second grace — the same rules the
    // diagram had, which are what make a hover-opened panel usable at all.
    panel.addEventListener('mouseenter', cancelClose);
    panel.addEventListener('mousemove', cancelClose);
    panel.addEventListener('mouseleave', scheduleClose);
    document.body.appendChild(panel);
    placeMenu(panel, anchor);
    anchor.classList.add('tab-open');
    // WHAT it is showing, not just where it hangs: the bar is rebuilt on every push from the host, several
    // times a turn, and the re-anchor below has to reopen THE SAME menu. Without these it reopened with no
    // root and no chat — i.e. the selected chat's tree — so hovering another tab showed you your own agents
    // a fraction of a second later.
    T.openPanel = {
      el: panel,
      anchor: anchor,
      rootId: rootId || null,
      chatId: chatId == null ? null : chatId,
    };
    // The panel is part of what is drawn, so opening it moves the signature. Re-stamped here — a hover opens
    // this without going through `render`, and the first push afterwards would otherwise look like a change
    // and rebuild the bar under the pointer, which is the very flicker the guard exists to stop.
    T.drawn = T.drawnSignature();
    requestAnimationFrame(function () {
      if (T.openPanel && T.openPanel.el === panel) panel.classList.add('open');
    });
  }

  /** One row per node, depth as indentation, deepest-first order preserved from the walk. */
  function flatten(node, depth, out) {
    if (!node) return;
    out.push(
      h(
        'div',
        {
          class: 'tab-menu-item' + (node.selected ? ' selected' : ''),
          style: { paddingLeft: 10 + depth * 14 + 'px' },
          attrs: { role: 'option', title: node.title || node.label, tabindex: '0' },
          on: {
            click: function () {
              closeTree();
              if (node.onPick) node.onPick();
            },
            keydown: function (ev) {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                closeTree();
                if (node.onPick) node.onPick();
              }
            },
          },
        },
        node.status
          ? h('span', { class: 'pill-dot ' + node.status, attrs: { 'aria-hidden': 'true' } })
          : null,
        h('span', { class: 'tab-menu-label', text: node.label }),
        node.meta ? h('span', { class: 'tab-menu-meta', text: node.meta }) : null
      )
    );
    (node.children || []).forEach(function (kid) {
      flatten(kid, depth + 1, out);
    });
  }

  /** Under the tab, clamped to the window. A menu is sized by its own content — nothing to measure. */
  function placeMenu(panel, anchor) {
    var r = anchor.getBoundingClientRect();
    var margin = 8;
    panel.style.position = 'fixed';
    panel.style.left = Math.max(margin, Math.min(Math.round(r.left), window.innerWidth - 320)) + 'px';
    panel.style.top = Math.round(r.bottom + 2) + 'px';
    panel.style.maxHeight = Math.round(window.innerHeight - r.bottom - margin * 2) + 'px';
  }

  // NB `position` lived here: it sized a panel around the diagram's canvas. The menu sizes itself from its
  // own rows (`placeMenu`), which is the one thing a list does better than a drawing.

  /** Slides it shut and removes it. [now] skips the animation, for a rebuild that is about to reopen. */
  function closeTree(now) {
    cancelOpen();
    cancelClose();
    if (!T.openPanel) return;
    var el = T.openPanel.el;
    if (T.openPanel.anchor) T.openPanel.anchor.classList.remove('tab-open');
    T.openPanel = null;
    // Closing moves the signature the same way opening does. `render` re-stamps at its end, so the call it
    // makes on the way to a rebuild is simply overwritten; this is for the hover-out, which touches no DOM
    // in the bar and must not leave the next push looking like a change.
    T.drawn = T.drawnSignature();
    if (now) {
      if (el.parentNode) el.parentNode.removeChild(el);
      return;
    }
    el.classList.remove('open');
    setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, SLIDE_MS);
  }

  document.addEventListener('mousedown', function (e) {
    if (!T.openPanel) return;
    if (T.openPanel.el && T.openPanel.el.contains(e.target)) return;
    if (T.openPanel.anchor && T.openPanel.anchor.contains(e.target)) return;
    closeTree();
  });
  document.addEventListener('keydown', function (e) {
    if (T.openPanel && e.key === 'Escape') {
      e.stopPropagation(); // Esc closes the panel, not the turn
      closeTree();
    }
  });

  T.openTree = openTree;
  T.closeTree = closeTree;
  T.scheduleOpen = scheduleOpen;
  T.scheduleClose = scheduleClose;
})();
