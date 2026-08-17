/* app-session-workloads.js — the Workloads diagram, and the window it is drawn under.
 *
 * One subject: everything that is running, as ONE picture — every chat, its agents, their agents, and the
 * background tasks each of them started — plus the rule that every node in it is a place you can go.
 *
 * The drawing itself is not here: `CC.diagram`/`CC.panView` (app-core-diagram.js) own the layout, the edges
 * and the pan/zoom. This file owns the SHAPE — what hangs off what — and what a click on a node means.
 *
 * The retention window is the one setting this view CANNOT be read without: it decides which finished work is
 * still listed, so a diagram that has aged everything out and a project that never ran anything look
 * identical. The control for it therefore sits on the card, and — like every other choice on this page — the
 * host sends both the options and the one in force; nothing here invents a value it could then ask the rule
 * to apply.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  /** The `<select>`'s id, so its `<label>` can point at it. One dashboard per page, so one id is enough. */
  var WINDOW_SELECT_ID = 'cc-workload-window';

  /**
   * Goes to that agent's tab AND leaves the dashboard.
   *
   * Leaving is half the action: selecting a tab behind an open panel changes something the user cannot see,
   * so the link appeared to do nothing. What you asked for was to go and read it, and reading happens in the
   * chat area the panel is covering.
   *
   * An empty [agentId] means the chat's own transcript — a background task the binary never attributed to
   * an agent still ran somewhere, and that somewhere is the chat.
   */
  function revealAndLeave(agentId, chatId) {
    // The chat it belongs to travels WITH it: this diagram spans every chat, but the message lands on the
    // panel that is on screen. Without the id that panel searched its own session for somebody else's agent,
    // found nothing, and the click did nothing at all.
    send({ type: 'revealAgent', agentId: agentId || '', chatId: chatId == null ? '' : String(chatId) });
    D.leaveDashboard();
  }

  /** Same contract as [revealAndLeave], for a background task's own view. */
  function revealTaskAndLeave(taskId, chatId) {
    if (taskId == null) return;
    send({
      type: 'revealBackgroundTask',
      taskId: taskId,
      chatId: chatId == null ? '' : String(chatId),
    });
    D.leaveDashboard();
  }

  // NB the hierarchy used to be spelled INSIDE the label — first `|_`, then spaces, then CSS indentation.
  // All three were the same mistake in different clothes: a shape expressed as text wraps, misaligns and
  // cannot be styled. It is a real diagram now (CC.diagram): cards positioned by arithmetic, joined by
  // curves drawn from those same coordinates.

  /**
   * The retention-window control, or null when the host sent no options.
   *
   * NULL RATHER THAN AN EMPTY CONTROL, deliberately: a `<select>` with nothing in it is a dead affordance
   * that looks live — the user opens it, finds nothing and has no way to tell whether the feature is broken
   * or the list is genuinely empty. Absent says the one true thing, which is that this page was not told
   * about any windows.
   *
   * A NATIVE `<select>` rather than the page's own popup-menu machinery, and the reasons are not stylistic.
   * (1) Its popup is drawn by the browser OUTSIDE the document, so it cannot be clipped — the dashboard is a
   * scroll container (`overflow-y: auto`), and a menu built as an absolutely-positioned div inside this card
   * would be cut off by it or would have to escape to `document.body` and then be kept in position by hand.
   * (2) Keyboard operation, type-ahead, Esc and the whole listbox role arrive with the element instead of
   * being a set of ARIA attributes this file would have to keep correct. (3) The page's custom menus exist
   * because their rows carry icons, descriptions and check marks; these rows are one plain word each, so the
   * machinery would buy nothing and cost the accessibility it already has.
   *
   * The accessible name comes from a real `<label for=…>`, not from `aria-label`: the visible words and the
   * spoken ones are then the same string by construction, and the label is a click target for the control.
   */
  function windowControl(spec) {
    var options = spec && Array.isArray(spec.options) ? spec.options.filter(Boolean) : [];
    if (!options.length) return null;
    // `== null`, never a truthiness test: the "everything" window is worth 0, and `if (spec.minutes)` would
    // drop exactly the choice that exists to hide nothing.
    var current = spec.minutes == null ? null : Number(spec.minutes);

    var select = h('select', {
      class: 'wl-window-select',
      attrs: { id: WINDOW_SELECT_ID },
      on: {
        change: function (ev) {
          var minutes = Number(ev.target.value);
          if (!isFinite(minutes)) return;
          send({ type: 'setWorkloadWindow', minutes: minutes });
        },
      },
    });
    if (!select) return null;

    options.forEach(function (option) {
      var minutes = Number(option.minutes);
      if (!isFinite(minutes)) return;
      // The label is the host's word for that window, painted as sent — the same rule as every status on
      // this page. A second copy of the wording here is how a menu comes to offer "15 min" for a rule that
      // is actually applying something else.
      var text = option.label != null ? String(option.label) : String(minutes);
      select.appendChild(h('option', { text: text, attrs: { value: String(minutes) } }));
    });
    if (current != null) select.value = String(current);

    return h(
      'div',
      { class: 'wl-window' },
      h('label', {
        class: 'wl-window-label',
        attrs: { for: WINDOW_SELECT_ID },
        text: 'Keep finished workloads listed for',
      }),
      select
    );
  }

  /**
   * Everything running, as ONE diagram: every chat, its agents, their agents, and the tasks each started.
   *
   * It was three views — Agents, Subagents, Background tasks — and they were three views of the same tree:
   * to find out whether an agent had spawned anything you switched view, lost the parent, and read a
   * breadcrumb to work out where you were. Then it was one already-expanded list, which was right about the
   * data and wrong about the drawing: rows at different x with nothing joining them read as a ragged list.
   *
   * Now it is a diagram, rooted at the CHATS — that is the honest root, since a chat is what starts
   * everything below it — and every node is a destination: click a chat, an agent, a subagent or a task and
   * you go there. It pans by dragging, like a diagram editor, because a tree that outgrows the panel should
   * be moved around rather than scrolled through two scrollbars.
   *
   * [payload] may carry `workloads` (every chat) or just this session's own `agentTree`/`backgroundTasks`;
   * the second is drawn under a single root so the shape is the same either way. `workloadWindow` is the
   * retention window the host filtered all of that with, plus the windows it will accept instead.
   */
  function buildWorkloadsCard(payload) {
    var chats =
      Array.isArray(payload.workloads) && payload.workloads.length
        ? payload.workloads.filter(Boolean)
        : [
            {
              chatId: null,
              title: 'This chat',
              selected: true,
              tree: Array.isArray(payload.agentTree) ? payload.agentTree.filter(Boolean) : [],
              tasks: Array.isArray(payload.backgroundTasks) ? payload.backgroundTasks.filter(Boolean) : [],
            },
          ];

    var control = windowControl(payload.workloadWindow);
    var roots = chats.map(chatNode).filter(function (n) {
      // A chat that started nothing is not a workload; drawing it would be a card saying "nothing here".
      return n.children.length > 0;
    });

    var canvas = roots.length ? CC.diagram(roots) : null;
    // THE CONTROL OUTLIVES THE DIAGRAM, and that is the case it exists for. With a narrow window the view
    // empties out as work finishes, and returning null here handed the panel over to the generic "nothing is
    // running" card — which carries no control, so the one action that would bring the work back was missing
    // at precisely the moment it was needed. With no control to draw there is nothing to keep the card for,
    // and the generic empty state is the right answer again.
    if (!canvas) return control ? card('Workloads', [control, emptyNote()], true, 'workloads') : null;
    // Keyed, so the dashboard's frequent rebuilds restore where you left the diagram instead of re-fitting.
    var view = CC.panView(canvas, 'Workloads diagram — drag to move, wheel to zoom', 'workloads');
    // The card is not in the document yet, so the viewport has no size: fit on the next frame, when it has.
    // Two frames, because the dashboard reveals the panel with its own transition.
    requestAnimationFrame(function () {
      if (view.__fit) view.__fit();
      requestAnimationFrame(function () {
        if (view.__fit) view.__fit();
      });
    });
    return card('Workloads', [control, view], true, 'workloads');
  }

  /**
   * What the card says when the window admits nothing.
   *
   * Deliberately a different sentence from the panel's own empty state, which means "no agents, no
   * background tasks": here the window is on screen right beside it, so the honest statement is about the
   * window rather than about the session — the work may well exist and simply be older than the choice made
   * two lines above.
   */
  function emptyNote() {
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: 'Nothing to show in this window.' })
    );
  }

  /** One chat, with everything it started underneath it. Every node is a destination. */
  function chatNode(chat) {
    var nodes = Array.isArray(chat.tree) ? chat.tree.filter(Boolean) : [];
    var list = Array.isArray(chat.tasks) ? chat.tasks.filter(Boolean) : [];
    var seen = {};

    function tasksOf(agentId) {
      return list.filter(function (t) {
        return (t.agentId == null ? null : t.agentId) === agentId;
      });
    }
    function childrenOf(agentId) {
      return nodes
        .filter(function (n) {
          return (n.parent == null ? null : n.parent) === agentId;
        })
        .filter(function (n) {
          if (seen[n.agentId]) return false; // a malformed parent link must not loop
          seen[n.agentId] = true;
          return true;
        });
    }

    function agentNode(a, depth) {
      return {
        id: a.agentId,
        kind: 'agent',
        // `Agent (…)` / `Subagent (…)` — the same naming the transcript card uses, so the same work does not
        // have two names depending on which panel you read it in.
        label: CC.diagramLabel('agent', depth, a.label != null ? String(a.label) : 'Agent'),
        meta: a.type ? String(a.type) : '',
        status: a.status ? String(a.status) : null,
        running: !!a.running,
        title: a.chain || a.label,
        onPick: function () {
          if (a.agentId) revealAndLeave(a.agentId, chat.chatId);
        },
        children: childrenOf(a.agentId)
          .map(function (child) {
            return agentNode(child, depth + 1);
          })
          .concat(
            tasksOf(a.agentId).map(function (t) {
              return taskNode(t, chat.chatId);
            })
          ),
      };
    }

    var kids = childrenOf(null).map(function (a) {
      return agentNode(a, 1);
    });
    // Tasks the chat itself started hang off the chat, and a task whose owner never became known would
    // otherwise be invisible — an honest gap has to be VISIBLE to be honest.
    var loose = tasksOf(null).concat(
      list.filter(function (t) {
        return (
          t.agentId != null &&
          !nodes.some(function (n) {
            return n.agentId === t.agentId;
          })
        );
      })
    );

    return {
      id: chat.chatId,
      kind: 'chat',
      label: chat.title != null ? String(chat.title) : 'Chat',
      selected: !!chat.selected,
      title: 'Go to this chat',
      onPick: function () {
        if (chat.chatId != null) send({ type: 'selectChat', chatId: chat.chatId });
        revealAndLeave('');
      },
      children: kids.concat(
        loose.map(function (t) {
          return taskNode(t, chat.chatId);
        })
      ),
    };
  }

  /**
   * One background task as a diagram node.
   *
   * Running AND finished ones are drawn: the host sends its own record, not the binary's live set, because
   * that set is a LEVEL signal — a task that ends stops being listed, and the node (with its output) used to
   * disappear at the exact moment there was something to read.
   *
   * The click opens the TASK's own view — what it is, who started it, its command and its output — not its
   * owner's transcript. Sending the user to the owner is what made this node look inert.
   */
  function taskNode(t, chatId) {
    var running = t.running !== false;
    var type = t.type != null ? String(t.type) : '';
    return {
      id: t.id,
      kind: 'task',
      // `Background Task (…)`, holding the model's description of the job or, failing that, the command.
      label: CC.diagramLabel('task', 1, (t.desc != null && String(t.desc)) || type || 'background'),
      meta: type || 'background task',
      // Named by the host, like every other state on the page (see JcefStatus).
      status: t.status || null,
      running: running,
      title: t.chain || t.desc || 'Background task',
      onPick: function () {
        revealTaskAndLeave(t.id, chatId);
      },
      // A finished task keeps its node and loses its Stop: there is nothing left to stop, and a button that
      // does nothing is worse than no button.
      action: running
        ? {
            label: 'Stop',
            onClick: function () {
              if (t.id != null) send({ type: 'stopTask', taskId: t.id });
            },
          }
        : null,
      children: [],
    };
  }

  D.buildWorkloadsCard = buildWorkloadsCard;
})();
