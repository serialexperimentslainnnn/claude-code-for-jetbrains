/**
 * The tab bar: the chats, and every subtab of the one you are reading.
 *
 * Owns: the tab bar — the chats, what the open chat started, and what the agent you opened started.
 *
 * **Why it lives here and not in Swing.** The chat UI is this page; a Swing strip above it cannot share the
 * page's accent, type scale or transitions, so making the two look like one product means approximating one
 * in the other by hand — which is what it looked like. Here every control is a `<button>` styled from the
 * same CSS variables as the composer.
 *
 * **The shape, and the four designs it replaced.** First came one capsule per level (chats, agents,
 * subagents, background tasks) tied together by an `<svg>` thread: the bar GREW with the depth of the tree,
 * and the thread had to be re-measured on every render, on a CSS transition and on resize, so it was drawn
 * wrong whenever it was measured at the wrong moment. Then a breadcrumb: fixed height, but you had to walk it
 * one segment at a time to find anything. Then a flat row of the same subtabs with the whole tree a hover
 * away behind a `⋮` on every tab — two ways to reach one destination, one of which had to be discovered.
 * Then that row with the `⋮` removed and every depth poured into it, which is where this arrived from: at the
 * dozens this exists for, a strip mixing a chat's own agents with somebody else's subagents says nothing
 * about who started what, and reads as one undifferentiated run of pills.
 *
 * What is here now is **two fixed rows plus ONE ROW PER OPEN LEVEL**: the chats · the agents and background
 * tasks the CHAT started · then, for each agent you open on the way down, the agents IT started.
 *
 * **One level per row, as deep as the tree goes.** It was one row holding the whole subtree of the depth-1
 * ancestor, flattened, which bounded the bar at three rows — and that bound was the defect: a subagent of a
 * subagent was visible in that row but selecting it opened nothing, so the hierarchy stopped at two levels
 * however deep the work really went. Depth is not bounded by the protocol, and an agent that spawns agents
 * that spawn agents is the session this feature exists for.
 *
 * The two properties the flattened row was chosen for are kept, and by construction rather than by care.
 * **The row you clicked in does not move**: picking a sibling changes which of ITS children are drawn below,
 * never the row that holds it, because that row is the children of the same parent either way. **Nothing
 * empties under the pointer**: a leaf starts no row, so opening one leaves every row above it as it was
 * instead of blanking the last one. See `app-tabs-base.js` `openBranches`.
 *
 * What it costs is height, and the cost is bounded by what the READER opened rather than by the tree: rows
 * appear one per press and go when you step back out. Each is named after the agent whose children it holds
 * (`aria-label`), because an indent says nothing to anyone not looking at the screen and nothing at all when
 * two rows sit at the same one. The whole shape of the work at once is still the Workloads diagram, which has
 * the room to draw it.
 *
 * **What the host sends, and what this owns.** The host sends what EXISTS: the chat list, the agent tree flat
 * (`{id, parent, label, status, type}`) and the background tasks. This module owns what is SHOWN — which
 * single subtab is open — because round-tripping a click through the host would cost a repaint of the host's
 * model to change a selection.
 *
 * **The family.** This file is the SPINE: navigation, the row itself, and the Kotlin-facing methods. Three
 * companions own one subject each and hang off the shared `CC.tabbar` namespace, which `app-tabs-base.js`
 * creates and which therefore loads first — `app-tabs-guard.js` (the flicker guard, whole),
 * `app-tabs-pill.js` (one tab as a widget) and `app-tabs-scroll.js` (drag, wheel and focus on the row).
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

  /**
   * ONE subtab as a pill: an agent of the chat, a subagent inside an open branch, or a background task.
   *
   * **An agent shows its bare name**, and its KIND rides in the tooltip and the accessible name
   * (`Agent (…)` / `Subagent (…)` — the vocabulary `CC.diagramLabel` owns, shared with the Workloads
   * diagram). A row of twelve pills each beginning with `Agent (` spends the only scarce thing a tool window
   * has on the word that repeats, and at a fixed pill width it spends it out of the title.
   *
   * **A background task shows `BT: …`**, and that is the one place this row prefixes anything. Bare, a task
   * is indistinguishable from an agent — `npm run dev` could be either — and unlike an agent's kind, which
   * the row it sits in already implies, nothing else on the pill says this is a process with output rather
   * than a conversation. The prefix costs four characters of the title and is the only non-colour signal
   * there is. It comes from `CC.diagramShown`, the same function the Workloads diagram calls, so the two
   * cannot drift; the SPOKEN name stays `Background Task (…)`, because a screen reader would read `BT:` out
   * as two letters.
   *
   * **The state travels three ways on purpose** — the dot's colour, the word in the tooltip, and the same word
   * in the accessible name — because colour alone carries nothing to a colour-blind reader or a screen reader
   * (WCAG 2.2 SC 1.4.1). The word is whatever the HOST sent (`JcefStatus`: `running|completed|failed|stopped`);
   * nothing here derives one, which is the rule that ended the bar saying `done` where the dashboard said
   * `completed` for the same task.
   *
   * **Only the OPEN subtab carries a close**, and only an agent has one: it is the one control that acts on
   * what you are reading, and forty more targets a few pixels wide in a 21px row is what giving every pill its
   * own would cost. A background task's row is not something the user dismisses — it is the plugin's record of
   * a task and it ages out with the retention window.
   */
  function subtabPill(w, expanded) {
    var node = w.node;
    var isAgent = w.kind === 'agent';
    var name = node.label || (isAgent ? 'Agent' : node.type || 'background');
    var status = node.status || null;
    var open = T.isSelected(w.kind, w.id);
    return T.pill({
      // Seen: the bare name for an agent, `BT: …` for a task. Said: always the full kind (see above).
      label: isAgent ? name : CC.diagramShown(w.kind, w.depth, name),
      title: CC.diagramLabel(w.kind, w.depth, name) + (status ? '  ·  ' + status : ''),
      status: status,
      selected: open,
      // Set only on the chat's own agents, and only when there is a branch to disclose — `null` leaves the
      // attribute off, which is what a pill that opens nothing has to say.
      expanded: expanded,
      // Clicking the one already open goes back to the chat's own transcript, which makes the `Chat` pill a
      // convenience rather than the only way out.
      onClick: function () {
        if (open) showChat();
        else if (isAgent) showAgent(w.id);
        else showTask(w.id);
      },
      onClose:
        open && isAgent
          ? function () {
              T.send({ type: 'closeAgent', agentId: w.id });
            }
          : null,
    });
  }

  /** Which chat is on screen — the one the row centres on, and only when it CHANGES (see `render`). */
  function selectedChatId() {
    for (var i = 0; i < T.state.chats.length; i++) {
      if (T.state.chats[i] && T.state.chats[i].selected) return T.state.chats[i].id;
    }
    return null;
  }

  /**
   * What each row was last AIMED at — the open chat's id, `kind:id` of the open subtab (`''` for the chat's
   * own transcript), and one slot per branch row, keyed by the agent whose children it holds. Absent until
   * the first draw, which is why the first draw centres.
   *
   * `Object.create(null)`, because branch slots are built from ids the binary chose: on a plain object an
   * agent called `constructor` would find a function on the prototype and the row would never re-centre.
   */
  var centred = Object.create(null);

  /** Whether one of the open rows holds [agentId]'s children — which is what the pill announces. */
  function ownsARow(branches, agentId) {
    for (var i = 0; i < branches.length; i++) {
      if (branches[i].rootId === agentId) return true;
    }
    return false;
  }

  /**
   * Fits [capsule] out as a row that can be READ: the wheel translated, the row grabbable, focus kept in
   * view, the reader's offset put back, and the open pill centred when the selection changed.
   *
   * Both rows, through one function, because they are rebuilt equally often and every one of these was
   * learned on the chats' row and then owed to the subtabs' — which is the row that now holds the dozens, so
   * a second copy of any of it would rot exactly where it matters most. [priorScroll] is read before the
   * teardown (a fresh element is born at offset 0) and restored INSTANTLY: `.tab-capsule` declares
   * `scroll-behavior: smooth`, so a bare assignment would glide the row from zero on every repaint, and a
   * snap replaced by a slide is not the fix.
   *
   * [aimedAt] is what the centring is keyed on, remembered under [slot]. Centring rather than "bring it barely
   * into view" is what makes a long row usable without a scrollbar: whichever pill you pick lands in the
   * middle, so its neighbours on both sides are one click away and the row moves under you as you work. It
   * happens on a CHANGE and on the first draw, never on every repaint — re-aiming on a push that merely
   * renamed a chat or moved an agent's status undid the drag just restored above, which is the same yank by
   * another route.
   */
  function wireRow(capsule, priorScroll, slot, aimedAt) {
    T.wheelToScroll(capsule);
    T.dragToScroll(capsule);
    T.keepFocusVisible(capsule);
    if (priorScroll) T.scrollLeftTo(capsule, priorScroll);
    if (centred[slot] === aimedAt) return;
    centred[slot] = aimedAt;
    // After the row is in the document: it has no width before that.
    requestAnimationFrame(function () {
      var open = capsule.querySelector('.pill-wrap.selected');
      if (open && open.scrollIntoView) open.scrollIntoView({ block: 'nearest', inline: 'center' });
    });
  }

  function render() {
    var host = T.bar();
    if (!host) return;
    T.pruneSelection();
    if (T.drawnSignature() === T.drawn && host.querySelector('.tab-row')) return;
    // Our rows live in their OWN container, and the clear below is scoped to it. `#tabsbar` is a `<nav>` in
    // the shared shell, not this module's element: anything else mounted into it would be deleted by a
    // blanket clear on the next agent event, several times a turn. That is not hypothetical — the dashboard's
    // view switcher was a sibling here until it moved into the composer, and a blanket clear did exactly that
    // to it.
    var rows = host.querySelector('.tab-rows');
    if (!rows) {
      rows = h('div', { class: 'tab-rows' });
      host.insertBefore(rows, host.firstChild);
    }
    // Where the reader had each row, read BEFORE it is thrown away — see [wireRow]. Each capsule is found by
    // its own class rather than by index: `.tab-capsule` matches the chats' one first, and asking for "the
    // second one" would silently become "the chats' one" on any page where the subtab row is not drawn.
    var priorCapsule = rows.querySelector('.tab-capsule');
    var priorScroll = priorCapsule ? priorCapsule.scrollLeft : 0;
    var priorSubs = rows.querySelector('.subtab-capsule');
    var priorSubScroll = priorSubs ? priorSubs.scrollLeft : 0;
    // One offset per branch row, keyed by the agent whose children that row holds — NOT by position. Opening
    // a deeper level inserts a row below the ones already there, and a positional read would hand row N's
    // offset to row N+1, which is the reader's place applied to somebody else's row.
    var priorBranchScroll = Object.create(null);
    Array.prototype.forEach.call(rows.querySelectorAll('.branch-capsule'), function (el) {
      var owner = el.getAttribute('data-branch');
      if (owner) priorBranchScroll[owner] = el.scrollLeft;
    });
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    host = rows;

    // 1. The chats. Every page draws the whole list and marks its own. ALWAYS visible — this row is the
    //    handle everything else hangs off, and hiding it would leave the page with no way back.
    var chatPills = T.state.chats.map(function (chat) {
      return T.pill({
        label: chat.title,
        selected: !!chat.selected,
        status: chat.attention ? 'attention' : null,
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
      host.appendChild(h('div', { class: 'tab-row' }, capsule));
      wireRow(capsule, priorScroll, 'chat', selectedChatId());
    }

    // 2. What the CHAT started: its own agents, then its background tasks (`chatWork`, app-tabs-base.js).
    //    Top level only — a subagent lives in a row below, under the agent that invoked it.
    var branches = T.openBranches();
    var work = T.chatWork();
    if (work.length) {
      // The chat itself leads the row, so "back to the conversation" is always a target and always in the same
      // place. Without it the only way out is clicking the subtab you are already in, which is a gesture
      // nobody guesses.
      var subPills = [
        T.pill({
          label: 'Chat',
          title: "This chat's own transcript",
          selected: !T.selected,
          onClick: showChat,
        }),
      ];
      work.forEach(function (w) {
        // Three states, and the third is the point: no attribute when this pill opens nothing, `false` when
        // it could, `true` when one of the rows below is ITS children. An agent stays marked as open while
        // you read something further down its branch, because its row is still on screen.
        var expandable = w.kind === 'agent' && w.hasKids;
        subPills.push(subtabPill(w, expandable ? ownsARow(branches, w.id) : null));
      });
      // No modifier class on the ROW: `.subtab-capsule` is what carries the difference and what the CSS and
      // the tests both key on, and a second class that nothing styles is a class that goes stale unnoticed.
      var subs = h('div', { class: 'tab-capsule subtab-capsule' }, subPills);
      host.appendChild(h('div', { class: 'tab-row' }, subs));
      wireRow(subs, priorSubScroll, 'sub', T.selected ? T.selected.kind + ':' + T.selected.id : '');
    }

    // 3. The OPEN BRANCH, one row per level: the children of each agent open above it, down the path to
    //    whatever you are reading (`openBranches`). There are as many rows as the tree is deep, because
    //    depth is not bounded by the protocol and an agent that spawns agents that spawn agents is the
    //    session this feature exists for. Absent whenever there is nothing to put in a row — you are in the
    //    chat, or in an agent that started nothing — because a row that takes height and says nothing is
    //    worse than no row.
    //
    //    Each is the same capsule as row 2 (`subtab-capsule`, so they all read at the same size) plus the
    //    class for the indent that says it belongs to the row above it.
    //
    //    `aria-label` names the owner of each row, because indentation is not information: without it a
    //    screen-reader user meets several unlabelled runs of tabs with no way to tell whose they are. It is
    //    also what tells two rows apart when they hold agents with the same label.
    //
    //    The scroll offsets are kept PER ROW, keyed by the agent whose children the row holds rather than by
    //    position: opening a deeper level inserts a row below and must not hand row N's offset to row N+1.
    branches.forEach(function (branch) {
      var branchPills = branch.items.map(function (w) {
        return subtabPill(w, w.hasKids ? ownsARow(branches, w.id) : null);
      });
      var kids = h(
        'div',
        {
          class: 'tab-capsule subtab-capsule branch-capsule',
          attrs: { 'aria-label': 'Started by ' + branch.rootLabel, 'data-branch': branch.rootId },
        },
        branchPills
      );
      host.appendChild(h('div', { class: 'tab-row' }, kids));
      wireRow(
        kids,
        priorBranchScroll[branch.rootId] || 0,
        'branch:' + branch.rootId,
        branch.rootId + '/' + (T.selected ? T.selected.id : '')
      );
    });

    T.bar().hidden = !chatPills.length && !work.length;

    // Stamped LAST, never before the rebuild: a stamp taken earlier would claim a bar this one is not.
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
