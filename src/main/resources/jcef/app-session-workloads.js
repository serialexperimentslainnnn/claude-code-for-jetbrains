/* app-session-workloads.js — the Workloads diagram.
 *
 * One subject: everything that is running, as ONE picture — every chat, its agents, their agents, and the
 * background tasks each of them started — plus the rule that every node in it is a place you can go.
 *
 * The drawing itself is not here: `CC.diagram`/`CC.panView` (app-core-diagram.js) own the layout, the edges
 * and the pan/zoom. This file owns the SHAPE — what hangs off what — and what a click on a node means.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var send = D.send;
  var card = D.card;

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
   * the second is drawn under a single root so the shape is the same either way.
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

    var roots = chats.map(chatNode).filter(function (n) {
      // A chat that started nothing is not a workload; drawing it would be a card saying "nothing here".
      return n.children.length > 0;
    });
    if (!roots.length) return null;

    var canvas = CC.diagram(roots);
    if (!canvas) return null;
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
    return card('Workloads', [view], true, 'workloads');
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
