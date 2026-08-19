(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  var WINDOW_SELECT_ID = 'cc-workload-window';

  function revealAndLeave(agentId, chatId) {
    send({ type: 'revealAgent', agentId: agentId || '', chatId: chatId == null ? '' : String(chatId) });
    D.leaveDashboard();
  }

  function revealTaskAndLeave(taskId, chatId) {
    if (taskId == null) return;
    send({
      type: 'revealBackgroundTask',
      taskId: taskId,
      chatId: chatId == null ? '' : String(chatId),
    });
    D.leaveDashboard();
  }

  function windowControl(spec) {
    var options = spec && Array.isArray(spec.options) ? spec.options.filter(Boolean) : [];
    if (!options.length) return null;
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
      return n.children.length > 0;
    });

    var canvas = roots.length ? CC.diagram(roots) : null;
    if (!canvas) return control ? card('Workloads', [control, emptyNote()], true, 'workloads') : null;
    var view = CC.panView(canvas, 'Workloads diagram — drag to move, wheel to zoom', 'workloads');
    requestAnimationFrame(function () {
      if (view.__fit) view.__fit();
      requestAnimationFrame(function () {
        if (view.__fit) view.__fit();
      });
    });
    return card('Workloads', [control, view], true, 'workloads');
  }

  function emptyNote() {
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: 'Nothing to show in this window.' })
    );
  }

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
          if (seen[n.agentId]) return false;
          seen[n.agentId] = true;
          return true;
        });
    }

    function agentNode(a, depth) {
      return {
        id: a.agentId,
        kind: 'agent',
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

  function taskNode(t, chatId) {
    var running = t.running !== false;
    var type = t.type != null ? String(t.type) : '';
    return {
      id: t.id,
      kind: 'task',
      label: CC.diagramShown('task', 1, (t.desc != null && String(t.desc)) || type || 'background'),
      name: CC.diagramLabel('task', 1, (t.desc != null && String(t.desc)) || type || 'background'),
      meta: type || 'background task',
      status: t.status || null,
      running: running,
      title: t.chain || t.desc || 'Background task',
      onPick: function () {
        revealTaskAndLeave(t.id, chatId);
      },
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
