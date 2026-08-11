/**
 * The tab bar: the chats, and the one subtab you are reading — with the whole tree of subtabs a hover away.
 *
 * **Why it lives here and not in Swing.** The chat UI is this page; a Swing strip above it cannot share the
 * page's accent, type scale or transitions, so making the two look like one product means approximating one
 * in the other by hand — which is what it looked like. Here every control is a `<button>` styled from the
 * same CSS variables as the composer.
 *
 * **The shape, and the two designs it replaced.** First came one capsule per level (chats, agents,
 * subagents, background tasks) tied together by an `<svg>` thread: the bar GREW with the depth of the tree,
 * and the thread had to be re-measured on every render, on a CSS transition and on resize, so it was drawn
 * wrong whenever it was measured at the wrong moment. Then a breadcrumb: fixed height, but you had to walk
 * it one segment at a time to find anything, and the menu hung off a `▾` the size of a few pixels.
 *
 * What is here now is neither. At rest the bar shows ONE subtab — the one whose transcript is on screen —
 * or nothing at all while you are in the chat itself. Pointing at the chat's `⋮` (or at that subtab) slides
 * open the ENTIRE tree, every level at once, indented; clicking any row opens it; moving the pointer away
 * slides it shut again. One gesture, no navigation, and the bar never costs more than two rows.
 *
 * **What the host sends, and what this owns.** The host sends what EXISTS: the chat list, the agent tree flat
 * (`{id, parent, label, status, type}`) and the background tasks with their owner. This module owns what is
 * SHOWN — which single subtab is open — because round-tripping a click through the host would cost a repaint
 * of the host's model to change a selection.
 */
(function () {
  'use strict';

  var c = window.cc || (window.cc = {});
  var CC = window.CC || {};
  var h = CC.h;

  /** Resolved per call, not captured at load: the module must not hold a stale reference to the bridge. */
  function send(msg) {
    var fn = (window.CC || {}).send;
    if (typeof fn === 'function') fn(msg);
  }

  /** Last payload from the host, so a re-render after a click needs no round-trip. */
  var state = { chats: [], tree: [], tasks: [] };

  /** What is on screen: `null` (the chat itself), or `{kind:'agent'|'task', id}`. */
  var selected = null;

  /** The open tree panel: `{el, anchor}`. At most one, like every other popup on the page. */
  var openPanel = null;

  function bar() {
    return document.getElementById('tabsbar');
  }

  function nodeById(id) {
    for (var i = 0; i < state.tree.length; i++) {
      if (state.tree[i] && state.tree[i].id === id) return state.tree[i];
    }
    return null;
  }

  function taskById(id) {
    for (var i = 0; i < state.tasks.length; i++) {
      if (state.tasks[i] && state.tasks[i].id === id) return state.tasks[i];
    }
    return null;
  }

  // NB the tree is walked through `parent` links, and a background task through its `owner` — the two fields
  // the host already sends. `openTree` builds its own `childrenOfIn`/`tasksOfIn` over WHICHEVER chat is being
  // shown (this one, or the tab you are hovering), which is why there is no module-level version any more.

  /** Drops a selection whose subject is no longer in the payload. */
  function pruneSelection() {
    if (!selected) return;
    // One `else if`, not two `if`s: the first branch can null the selection, and the second would then read
    // `.kind` off it. Caught by the tests, and it would have thrown on any payload that dropped an agent.
    if (selected.kind === 'agent') {
      if (!nodeById(selected.id)) selected = null;
    } else if (selected.kind === 'task') {
      if (!taskById(selected.id)) selected = null;
    }
  }

  function isSelected(kind, id) {
    return !!selected && selected.kind === kind && selected.id === id;
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

  // ---------------------------------------------------------------------------
  // navigation

  function showChat() {
    selected = null;
    send({ type: 'selectAgent', agentId: '' });
    render();
  }

  function showAgent(agentId) {
    selected = { kind: 'agent', id: agentId };
    send({ type: 'selectAgent', agentId: agentId });
    render();
  }

  function showTask(taskId) {
    selected = { kind: 'task', id: taskId };
    send({ type: 'revealBackgroundTask', taskId: taskId });
    render();
  }

  // ---------------------------------------------------------------------------
  // the tree panel

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
    if (openPanel && openPanel.anchor === anchor) return;
    cancelOpen();
    openTimer = setTimeout(function () {
      openTimer = null;
      openTree(anchor, false, rootId, chatId);
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

  function openTree(anchor, instant, rootId, chatId) {
    closeTree(true);
    var seen = {};
    var foreign = null;
    if (chatId != null) {
      for (var ci = 0; ci < state.chats.length; ci++) {
        if (state.chats[ci] && state.chats[ci].id === chatId && !state.chats[ci].selected) {
          foreign = state.chats[ci];
        }
      }
    }
    // The tree being drawn: another chat's (read-only), or ours.
    var nodesOf = foreign ? foreign.tree || [] : state.tree;
    var tasksAll = foreign ? foreign.tasks || [] : state.tasks;

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
          selected: !foreign && isSelected('agent', n.id),
          title: (n.label || '') + (n.type ? '  ·  ' + n.type : '') + (n.status ? '  ·  ' + n.status : ''),
          onPick: foreign
            ? function () {
                // Another chat's agent: go to that chat first. Painting its transcript in this browser
                // would show one chat's work under another chat's tab.
                closeTree();
                send({ type: 'selectChat', chatId: foreign.id });
              }
            : function () {
                closeTree();
                showAgent(n.id);
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
        selected: !foreign && isSelected('task', t.id),
        title: (t.label || '') + (t.running ? '  ·  running' : '  ·  finished'),
        onPick: foreign
          ? function () {
              closeTree();
              send({ type: 'selectChat', chatId: foreign.id });
            }
          : function () {
              closeTree();
              showTask(t.id);
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
    var depth = rootNode ? depthOf(rootNode) : 0;
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
      for (var i = 0; i < state.chats.length; i++) {
        if (state.chats[i] && state.chats[i].selected) chat = state.chats[i];
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
          selected: isSelected('agent', rootNode.id),
          title: rootNode.label || '',
          onPick: function () {
            closeTree();
            showAgent(rootNode.id);
          },
          children: children,
        }
      : {
          id: '__chat',
          kind: 'chat',
          label: (chat && chat.title) || 'Chat',
          selected: !foreign && !selected,
          title: foreign ? 'Go to ' + (chat && chat.title) : "This chat's own transcript",
          onPick: foreign
            ? function () {
                closeTree();
                send({ type: 'selectChat', chatId: foreign.id });
              }
            : function () {
                closeTree();
                showChat();
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
    openPanel = { el: panel, anchor: anchor, rootId: rootId || null, chatId: chatId == null ? null : chatId };
    requestAnimationFrame(function () {
      if (openPanel && openPanel.el === panel) panel.classList.add('open');
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
    if (!openPanel) return;
    var el = openPanel.el;
    if (openPanel.anchor) openPanel.anchor.classList.remove('tab-open');
    openPanel = null;
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
    if (!openPanel) return;
    if (openPanel.el && openPanel.el.contains(e.target)) return;
    if (openPanel.anchor && openPanel.anchor.contains(e.target)) return;
    closeTree();
  });
  document.addEventListener('keydown', function (e) {
    if (openPanel && e.key === 'Escape') {
      e.stopPropagation(); // Esc closes the panel, not the turn
      closeTree();
    }
  });

  // ---------------------------------------------------------------------------
  // rendering

  function pill(opts) {
    var kids = [];
    if (opts.status) {
      // A dot, not a colour on the text: state has to survive both a colour-blind reader and a screen reader,
      // and the label spells it out in words.
      kids.push(h('span', { class: 'pill-dot ' + opts.status, attrs: { 'aria-hidden': 'true' } }));
    }
    kids.push(h('span', { class: 'pill-label', text: opts.label }));
    var btn = h(
      'button',
      {
        class: 'pill' + (opts.selected ? ' selected' : ''),
        attrs: {
          type: 'button',
          title: opts.title || opts.label,
          // The bar is a set of tab-like controls: say which one is current rather than leaving it to colour.
          'aria-current': opts.selected ? 'true' : 'false',
        },
        on: { click: opts.onClick },
      },
      kids
    );
    // Which chat this pill IS, so a repaint can put an open menu back on the same tab (see `render`).
    btn.__chatId = opts.menuChat == null ? null : opts.menuChat;
    if (opts.onMenu) attachMenu(btn, opts.menuRoot || null, opts.menuChat || null);
    // The pin: turn what you are reading into a chat tab of its own.
    //
    // A subtab is a VIEW — one browser painting somebody else's transcript — and it is gone the moment you
    // look at something else. Pinning is how you say "I am going to keep coming back to this one", and what
    // you get back is a real tab, alongside the chats, that stays put.
    if (opts.onPin) {
      btn.appendChild(
        h('span', {
          class: 'pill-pin',
          attrs: {
            role: 'button',
            tabindex: '0',
            'aria-label': 'Pin ' + opts.label + ' as a tab',
            title: 'Pin as a tab',
          },
          text: '⇱',
          on: {
            click: function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              opts.onPin();
            },
            keydown: function (ev) {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                ev.stopPropagation();
                opts.onPin();
              }
            },
          },
        })
      );
    }
    if (opts.onClose) {
      btn.appendChild(
        h('span', {
          class: 'pill-x',
          attrs: { role: 'button', tabindex: '0', 'aria-label': 'Close ' + opts.label, title: 'Close' },
          text: '×',
          on: {
            click: function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              opts.onClose();
            },
            keydown: function (ev) {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                ev.stopPropagation();
                opts.onClose();
              }
            },
          },
        })
      );
    }
    return btn;
  }

  /**
   * The ⋮ that opens the tree, plus the hover that opens it without aiming at anything.
   *
   * Both, deliberately. Hover is the fast path and how the panel is discovered at all; the ⋮ is what makes
   * it a real control — visible before you touch it, reachable from the keyboard, and usable on a device
   * that has no hover at all.
   */
  function attachMenu(host, rootId, chatId) {
    var dots = h('span', {
      class: 'pill-more',
      attrs: {
        role: 'button',
        tabindex: '0',
        'aria-haspopup': 'listbox',
        'aria-label': 'Show every subtab',
        title: 'Every agent and background task this chat started',
      },
      text: '⋮',
      on: {
        click: function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          if (openPanel && openPanel.anchor === host) closeTree();
          else openTree(host, false, rootId, chatId);
        },
        keydown: function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ') {
            ev.preventDefault();
            ev.stopPropagation();
            openTree(host, false, rootId, chatId);
          }
        },
      },
    });
    host.appendChild(dots);
    // Resting on the tab for a second opens the same menu — same rules as before: it stays for three seconds
    // after you leave, and any movement over it starts that over.
    host.addEventListener('mouseenter', function () {
      scheduleOpen(host, rootId, chatId);
    });
    host.addEventListener('mouseleave', scheduleClose);
  }

  /** Past this many pixels the gesture is a DRAG, not a click on the tab you happened to press. */
  var DRAG_SLOP = 4;

  /**
   * Grab the row and move it, the way you would move a physical row of tabs.
   *
   * There is no scrollbar to aim at (the platform one is a grey slab across a rounded capsule), the wheel
   * is not discoverable, and clicking your way along a row of twenty is tedious — so the row itself is the
   * handle. Selecting a chat also centres it (see `render`), which means ordinary use keeps the row roughly
   * where you can reach it and dragging is for the rest.
   *
   * The click that ENDS a drag is swallowed: releasing over a tab must not also switch to that chat. Mouse
   * events rather than pointer events on purpose — `setPointerCapture` does not exist in jsdom, and a
   * gesture nobody can test is a gesture that breaks silently.
   */
  function dragToScroll(el) {
    var from = null;
    var moved = false;
    el.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      from = { x: ev.clientX, scroll: el.scrollLeft };
      moved = false;
    });
    document.addEventListener('mousemove', function (ev) {
      if (!from) return;
      var dx = ev.clientX - from.x;
      if (!moved && Math.abs(dx) < DRAG_SLOP) return;
      moved = true;
      el.classList.add('dragging');
      el.scrollLeft = from.scroll - dx;
      ev.preventDefault();
    });
    document.addEventListener('mouseup', function () {
      from = null;
      el.classList.remove('dragging');
    });
    // Capture phase, so it runs before the pill's own handler. `moved` is cleared here rather than on
    // mouseup: the click arrives after it, and clearing it early would let the chat switch anyway.
    el.addEventListener(
      'click',
      function (ev) {
        if (!moved) return;
        moved = false;
        ev.stopPropagation();
        ev.preventDefault();
      },
      true
    );
  }

  /** True when this chat has started anything at all — i.e. when there is a tree to show. */
  function hasWork() {
    return state.tree.length > 0 || state.tasks.length > 0;
  }

  /** The label of whatever is open, for the single subtab the bar shows at rest. */
  function selectedPill() {
    if (!selected) return null;
    if (selected.kind === 'agent') {
      var node = nodeById(selected.id);
      if (!node) return null;
      return pill({
        label: CC.diagramLabel('agent', depthOf(node), node.label || 'Agent'),
        title: (node.label || '') + (node.type ? '  ·  ' + node.type : ''),
        status: node.status || null,
        selected: true,
        onMenu: true,
        // Hovering THIS shows what THIS started, not the whole chat: the pointer is on it, so that is the
        // question being asked.
        menuRoot: node.id,
        onClick: showChat, // clicking the open subtab goes back to the chat's own transcript
        onPin: function () {
          send({ type: 'pinSubtab', agentId: node.id });
        },
        onClose: function () {
          send({ type: 'closeAgent', agentId: node.id });
        },
      });
    }
    var task = taskById(selected.id);
    if (!task) return null;
    return pill({
      label: CC.diagramLabel('task', 1, task.label || task.type || 'background'),
      title: (task.label || '') + (task.running ? '  ·  running' : '  ·  finished'),
      status: task.status || null,
      selected: true,
      onMenu: true,
      onClick: showChat,
      onPin: function () {
        send({ type: 'pinSubtab', taskId: task.id });
      },
    });
  }

  /**
   * What the bar actually DRAWS, as a string — everything else in the payload is invisible here.
   *
   * The host pushes the whole bar on every agent event, several times a turn, and almost all of those
   * pushes change nothing you can see: an agent's transcript grew, a token landed. Rebuilding the DOM
   * anyway is what made the row flicker under the pointer, and it cost a full rebuild per event.
   */
  function signature() {
    var parts = state.chats.map(function (chat) {
      var work = chat.selected ? hasWork() : (chat.tree || []).length > 0 || (chat.tasks || []).length > 0;
      return [chat.id, chat.title, !!chat.selected, !!chat.attention, chat.pinned || '', work].join('');
    });
    if (selected) {
      var node = selected.kind === 'agent' ? nodeById(selected.id) : taskById(selected.id);
      parts.push(
        node
          ? [selected.kind, selected.id, node.label || '', node.status || '', !!node.running].join('')
          : selected.kind + '' + selected.id + 'gone'
      );
    }
    return parts.join('');
  }

  /** The last drawn signature, so an identical push is a no-op instead of a rebuild. */
  var drawn = null;

  function render() {
    var host = bar();
    if (!host) return;
    // Skipped only while nothing is open: an open menu shows the tree itself, and that DOES change on the
    // pushes this guard is filtering out — an agent finishing while you read it has to say so.
    pruneSelection();
    var now = signature();
    if (!openPanel && now === drawn && host.querySelector('.tab-row')) return;
    drawn = now;
    // Was the tree open? It has to SURVIVE this repaint.
    //
    // The host pushes the whole bar on every agent event, several times a turn, and this used to close the
    // panel each time — so the popup vanished from under the pointer while you were reading it, and the
    // longer the tree the more certain that was. The bar is rebuilt (the old ⋮ is gone with it), so the
    // panel is re-anchored to the new one afterwards rather than kept pointing at a detached node.
    // Was the tree open, and on WHAT? Both, or the reopen below silently changes which chat you are looking
    // at. The panel is captured before `closeTree` clears it.
    var reopen = openPanel && { rootId: openPanel.rootId, chatId: openPanel.chatId };
    closeTree(true);
    // Our rows live in their OWN container: the dashboard's view switcher is a sibling in this bar
    // (app-session.js puts it there so it stops floating over the transcript), and a blanket clear of the
    // bar would delete it on the next agent event — several times a turn.
    var rows = host.querySelector('.tab-rows');
    if (!rows) {
      rows = h('div', { class: 'tab-rows' });
      host.insertBefore(rows, host.firstChild);
    }
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    host = rows;

    // 1. The chats. Every page draws the whole list and marks its own. ALWAYS visible — this row is the
    //    handle everything else hangs off, and hiding it would leave the page with no way back.
    var chatPills = state.chats.map(function (chat) {
      // EVERY tab that has started something gets a ⋮, not just the selected one: what a chat is running
      // does not pause because you are reading a different tab, and needing to select it first to find out
      // is the opposite of what a tab bar is for. The host now sends each chat's own tree (see
      // JcefTabsData), so hovering any tab draws that chat's diagram.
      var mine = chat.selected ? hasWork() : (chat.tree || []).length > 0 || (chat.tasks || []).length > 0;
      return pill({
        label: chat.title,
        selected: !!chat.selected,
        status: chat.attention ? 'attention' : null,
        onMenu: mine || !!chat.pinned,
        menuChat: chat.id,
        // A tab pinned to a subagent roots its diagram at THAT agent: it is that agent's tab, so its ⋮ has
        // to answer "what did this one start", not "what did the whole chat start".
        menuRoot: chat.pinned || null,
        onClick: function () {
          if (chat.selected) showChat();
          else send({ type: 'selectChat', chatId: chat.id });
        },
        onClose: function () {
          send({ type: 'closeChat', chatId: chat.id });
        },
      });
    });
    if (chatPills.length) {
      var capsule = h('div', { class: 'tab-capsule' }, chatPills);
      // A vertical wheel does NOT move a horizontal scroller in Chromium, so with enough chats the capsule
      // overflowed with no way to reach the far end — the tabs were simply unreachable. Translating the
      // gesture is the whole fix; `preventDefault` stops the page from scrolling underneath instead.
      capsule.addEventListener('wheel', function (ev) {
        var delta = Math.abs(ev.deltaY) > Math.abs(ev.deltaX) ? ev.deltaY : ev.deltaX;
        if (!delta) return;
        // No overflow check: a measurement that says "nothing to scroll" when there is leaves the row inert
        // with no way to tell why. Scrolling a box that cannot scroll costs nothing — `scrollLeft` simply
        // does not move — so `preventDefault` is conditioned on it having actually moved instead.
        var before = capsule.scrollLeft;
        capsule.scrollLeft += delta;
        if (capsule.scrollLeft !== before) ev.preventDefault();
      });
      dragToScroll(capsule);
      // A tab that TAKES FOCUS is scrolled into view. Without this, tabbing along a row wider than the
      // window moves focus onto pills that are scrolled out of sight or half-covered by the edge fade —
      // WCAG 2.2 SC 2.4.7 (Focus Visible) and 2.4.11 (Focus Not Obscured). One listener on the container,
      // not one per pill, so it survives the rebuild.
      capsule.addEventListener('focusin', function (ev) {
        if (ev.target && ev.target.scrollIntoView)
          ev.target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      });
      host.appendChild(h('div', { class: 'tab-row' }, capsule));
      // The selected tab is CENTRED, after the row is in the document (it has no width before that).
      // Centring rather than "bring it barely into view" is what makes a long row usable without a
      // scrollbar: whichever chat you pick lands in the middle, so its neighbours on both sides are one
      // click away and the row moves under you as you work. Dragging covers the rest.
      requestAnimationFrame(function () {
        var open = capsule.querySelector('.pill.selected');
        if (open && open.scrollIntoView) open.scrollIntoView({ block: 'nearest', inline: 'center' });
      });
    }

    // 2. The open subtab — ONE row, and only while something other than the chat is on screen. At rest the
    //    bar says what you are reading and nothing else; the rest of the tree is a hover away.
    var current = selectedPill();
    if (current) {
      host.appendChild(
        h(
          'div',
          { class: 'tab-row trail' },
          h('span', { class: 'trail-label', text: 'Subtab' }),
          h('div', { class: 'tab-capsule' }, [current])
        )
      );
    }

    bar().hidden = !chatPills.length && !current;

    // Re-anchor the panel to the ⋮ this repaint just created, showing THE SAME THING. Rebuilt in place, with
    // its content refreshed — which is the point: an agent that finished while you were looking at the tree
    // should say so. The anchor is the ⋮ of the chat the menu belongs to, not simply the first one in the
    // bar: those differ the moment you hover a tab that is not the selected one.
    if (reopen) {
      var anchor = null;
      if (reopen.chatId != null) {
        var pills = rows.querySelectorAll('.tab-capsule .pill');
        for (var pi = 0; pi < pills.length; pi++) {
          if (pills[pi].__chatId === reopen.chatId) anchor = pills[pi].querySelector('.pill-more');
        }
      }
      if (!anchor) anchor = rows.querySelector('.pill-more');
      if (anchor) openTree(anchor, true, reopen.rootId, reopen.chatId);
    }
  }

  // ---------------------------------------------------------------------------
  // host API

  /** The host pushes the whole bar: `{chats, tree, tasks}`. Null-safe — a partial payload renders what it has. */
  c.tabs = function (payload) {
    var p = payload || {};
    state.chats = Array.isArray(p.chats) ? p.chats : [];
    state.tree = Array.isArray(p.tree) ? p.tree : [];
    state.tasks = Array.isArray(p.tasks) ? p.tasks : [];
    render();
  };

  /** The host reveals an agent (from a transcript card, or a notification): that becomes the open subtab. */
  c.revealAgentTab = function (agentId) {
    if (!agentId) return;
    selected = { kind: 'agent', id: agentId };
    render();
  };

  /** The host went back to the chat's own transcript: nothing is open any more. */
  c.clearAgentSelection = function () {
    selected = null;
    render();
  };
})();
