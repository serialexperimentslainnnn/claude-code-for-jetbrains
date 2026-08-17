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
 *
 * **The family.** This file is the SPINE: navigation, the row itself, and the two Kotlin-facing methods. Five
 * companions own one subject each and hang off the shared `CC.tabbar` namespace, which `app-tabs-base.js`
 * creates and which therefore loads first — `app-tabs-guard.js` (the flicker guard, whole),
 * `app-tabs-tree.js` (the hover panel), `app-tabs-pill.js` (one tab as a widget) and `app-tabs-scroll.js`
 * (drag, wheel and focus on the row).
 */
(function () {
  'use strict';

  var c = window.cc || (window.cc = {});
  var CC = window.CC || {};
  var T = (CC.tabbar = CC.tabbar || {});
  var h = CC.h;

  // ---------------------------------------------------------------------------
  // navigation

  function showChat() {
    T.selected = null;
    T.send({ type: 'selectAgent', agentId: '' });
    render();
  }

  function showAgent(agentId) {
    T.selected = { kind: 'agent', id: agentId };
    T.send({ type: 'selectAgent', agentId: agentId });
    render();
  }

  function showTask(taskId) {
    T.selected = { kind: 'task', id: taskId };
    T.send({ type: 'revealBackgroundTask', taskId: taskId });
    render();
  }

  T.showChat = showChat;
  T.showAgent = showAgent;
  T.showTask = showTask;

  // ---------------------------------------------------------------------------
  // rendering

  /** The label of whatever is open, for the single subtab the bar shows at rest. */
  function selectedPill() {
    if (!T.selected) return null;
    if (T.selected.kind === 'agent') {
      var node = T.nodeById(T.selected.id);
      if (!node) return null;
      return T.pill({
        label: CC.diagramLabel('agent', T.depthOf(node), node.label || 'Agent'),
        title: (node.label || '') + (node.type ? '  ·  ' + node.type : ''),
        status: node.status || null,
        selected: true,
        onMenu: true,
        // Hovering THIS shows what THIS started, not the whole chat: the pointer is on it, so that is the
        // question being asked.
        menuRoot: node.id,
        onClick: showChat, // clicking the open subtab goes back to the chat's own transcript
        onPin: function () {
          T.send({ type: 'pinSubtab', agentId: node.id });
        },
        onClose: function () {
          T.send({ type: 'closeAgent', agentId: node.id });
        },
      });
    }
    var task = T.taskById(T.selected.id);
    if (!task) return null;
    return T.pill({
      label: CC.diagramLabel('task', 1, task.label || task.type || 'background'),
      title: (task.label || '') + (task.running ? '  ·  running' : '  ·  finished'),
      status: task.status || null,
      selected: true,
      onMenu: true,
      onClick: showChat,
      onPin: function () {
        T.send({ type: 'pinSubtab', taskId: task.id });
      },
    });
  }

  /** Which chat is on screen — the one the row centres on, and only when it CHANGES (see `render`). */
  function selectedChatId() {
    for (var i = 0; i < T.state.chats.length; i++) {
      if (T.state.chats[i] && T.state.chats[i].selected) return T.state.chats[i].id;
    }
    return null;
  }

  /** The chat the row was last aimed at. Null until the first draw, which is why the first draw centres. */
  var centredOn = null;

  function render() {
    var host = T.bar();
    if (!host) return;
    // Applies with a menu open TOO — see [panelSignature] for why that is safe now, and why waiving it was
    // the flicker. An agent finishing while you read the tree still repaints it: that is a change, and the
    // signature says so.
    T.pruneSelection();
    if (T.drawnSignature() === T.drawn && host.querySelector('.tab-row')) return;
    // Was the tree open? It has to SURVIVE this repaint.
    //
    // The host pushes the whole bar on every agent event, several times a turn, and this used to close the
    // panel each time — so the popup vanished from under the pointer while you were reading it, and the
    // longer the tree the more certain that was. The bar is rebuilt (the old ⋮ is gone with it), so the
    // panel is re-anchored to the new one afterwards rather than kept pointing at a detached node.
    // Was the tree open, and on WHAT? Both, or the reopen below silently changes which chat you are looking
    // at. The panel is captured before `closeTree` clears it.
    var reopen = T.openPanel && { rootId: T.openPanel.rootId, chatId: T.openPanel.chatId };
    T.closeTree(true);
    // Our rows live in their OWN container: the dashboard's view switcher is a sibling in this bar
    // (app-session.js puts it there so it stops floating over the transcript), and a blanket clear of the
    // bar would delete it on the next agent event — several times a turn.
    var rows = host.querySelector('.tab-rows');
    if (!rows) {
      rows = h('div', { class: 'tab-rows' });
      host.insertBefore(rows, host.firstChild);
    }
    // Where the reader had the row, read BEFORE it is thrown away. The capsule is rebuilt from nothing on
    // every repaint — several times a turn — and a new element starts at offset zero, so a row dragged or
    // wheeled to its far end snapped back to the left while an agent event landed. There is no scrollbar to
    // aim at, so the offset is the only record of where the reader was.
    var priorCapsule = rows.querySelector('.tab-capsule');
    var priorScroll = priorCapsule ? priorCapsule.scrollLeft : 0;
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    host = rows;

    // 1. The chats. Every page draws the whole list and marks its own. ALWAYS visible — this row is the
    //    handle everything else hangs off, and hiding it would leave the page with no way back.
    var chatPills = T.state.chats.map(function (chat) {
      // EVERY tab that has started something gets a ⋮, not just the selected one: what a chat is running
      // does not pause because you are reading a different tab, and needing to select it first to find out
      // is the opposite of what a tab bar is for. The host now sends each chat's own tree (see
      // JcefTabsData), so hovering any tab draws that chat's diagram.
      var mine = chat.selected ? T.hasWork() : (chat.tree || []).length > 0 || (chat.tasks || []).length > 0;
      return T.pill({
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
          else T.send({ type: 'selectChat', chatId: chat.id });
        },
        onClose: function () {
          T.send({ type: 'closeChat', chatId: chat.id });
        },
      });
    });
    if (chatPills.length) {
      var capsule = h('div', { class: 'tab-capsule' }, chatPills);
      T.wheelToScroll(capsule);
      T.dragToScroll(capsule);
      T.keepFocusVisible(capsule);
      host.appendChild(h('div', { class: 'tab-row' }, capsule));
      // Put it back before the frame is painted, and INSTANTLY: `.tab-capsule` declares
      // `scroll-behavior: smooth`, so a bare assignment would glide the row from zero to here on every
      // repaint — the snap replaced by a slide is not the fix.
      if (priorScroll) T.scrollLeftTo(capsule, priorScroll);
      // The selected tab is CENTRED, after the row is in the document (it has no width before that).
      // Centring rather than "bring it barely into view" is what makes a long row usable without a
      // scrollbar: whichever chat you pick lands in the middle, so its neighbours on both sides are one
      // click away and the row moves under you as you work. Dragging covers the rest.
      //
      // On a CHANGE of selection, and on the first draw — never on every repaint. Centring the selected tab
      // again on a push that merely renamed a chat or moved an agent's status undid the drag that had just
      // been restored above, which is the same yank by another route: the row cannot be read while a turn is
      // running if every event re-aims it at the tab you are not looking at.
      var openChat = selectedChatId();
      if (openChat !== centredOn) {
        centredOn = openChat;
        requestAnimationFrame(function () {
          var open = capsule.querySelector('.pill.selected');
          if (open && open.scrollIntoView) open.scrollIntoView({ block: 'nearest', inline: 'center' });
        });
      }
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

    T.bar().hidden = !chatPills.length && !current;

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
      if (anchor) T.openTree(anchor, reopen.rootId, reopen.chatId);
    }
    // Stamped LAST, never before the rebuild: the reopen above can end with no panel at all (nothing left to
    // anchor it to), and a stamp taken earlier would claim a bar this one is not.
    T.drawn = T.drawnSignature();
  }

  // ---------------------------------------------------------------------------
  // host API

  /** The host pushes the whole bar: `{chats, tree, tasks}`. Null-safe — a partial payload renders what it has. */
  c.tabs = function (payload) {
    var p = payload || {};
    T.state.chats = Array.isArray(p.chats) ? p.chats : [];
    T.state.tree = Array.isArray(p.tree) ? p.tree : [];
    T.state.tasks = Array.isArray(p.tasks) ? p.tasks : [];
    render();
  };

  /** The host reveals an agent (from a transcript card, or a notification): that becomes the open subtab. */
  c.revealAgentTab = function (agentId) {
    if (!agentId) return;
    T.selected = { kind: 'agent', id: agentId };
    render();
  };

  /**
   * The host revealed a background TASK. Its own hook, because a task is not an agent: it has no transcript
   * and no node in the tree, so `revealAgentTab` cannot stand in for it.
   *
   * Without this the task's view was painted and the bar was never told, so no pill appeared for it — the
   * click looked like it had done nothing, and there was no pill to click to get back out again.
   */
  c.revealTaskTab = function (taskId) {
    if (!taskId) return;
    T.selected = { kind: 'task', id: taskId };
    render();
  };

  /** The host went back to the chat's own transcript: nothing is open any more. */
  c.clearAgentSelection = function () {
    T.selected = null;
    render();
  };
})();
