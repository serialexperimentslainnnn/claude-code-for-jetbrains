(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const W = (D.workloads = D.workloads || ({} as WorkloadsNs));
  const send = D.send;

  function revealAndLeave(agentId: string, chatId?: unknown): void {
    send({ type: 'revealAgent', agentId: agentId || '', chatId: chatId == null ? '' : String(chatId) });
    D.leaveDashboard();
  }

  function revealTaskAndLeave(taskId: unknown, chatId: unknown): void {
    if (taskId == null) return;
    send({
      type: 'revealBackgroundTask',
      taskId: taskId,
      chatId: chatId == null ? '' : String(chatId),
    });
    D.leaveDashboard();
  }

  W.chatNode = function (chat: WorkloadChat) {
    const nodes = Array.isArray(chat.tree) ? (chat.tree.filter(Boolean) as WorkloadAgent[]) : [];
    const list = Array.isArray(chat.tasks) ? (chat.tasks.filter(Boolean) as WorkloadTask[]) : [];
    const seen: Record<string, boolean> = {};

    function tasksOf(agentId: string | null): WorkloadTask[] {
      return list.filter(function (t) {
        return (t.agentId == null ? null : t.agentId) === agentId;
      });
    }
    function childrenOf(agentId: string | null): WorkloadAgent[] {
      return nodes
        .filter(function (n) {
          return (n.parent == null ? null : n.parent) === agentId;
        })
        .filter(function (n) {
          const key = String(n.agentId);
          if (seen[key]) return false;
          seen[key] = true;
          return true;
        });
    }

    function agentNode(a: WorkloadAgent, depth: number): DiagramNode {
      const agentId = a.agentId == null ? null : a.agentId;
      return {
        id: a.agentId,
        kind: 'agent',
        label: CC.diagramLabel('agent', depth, a.label != null ? String(a.label) : 'Agent'),
        meta: a.type ? String(a.type) : '',
        status: a.status ? String(a.status) : null,
        running: !!a.running,
        title: (a.chain || (a.label as string | null)) as string | null,
        onPick: function () {
          if (a.agentId) revealAndLeave(a.agentId, chat.chatId);
        },
        children: childrenOf(agentId)
          .map(function (child) {
            return agentNode(child, depth + 1);
          })
          .concat(
            tasksOf(agentId).map(function (t) {
              return W.taskNode(t, chat.chatId);
            })
          ),
      };
    }

    const kids = childrenOf(null).map(function (a) {
      return agentNode(a, 1);
    });
    const loose = tasksOf(null).concat(
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
          return W.taskNode(t, chat.chatId);
        })
      ),
    };
  };

  W.taskNode = function (t: WorkloadTask, chatId: unknown): DiagramNode {
    const running = t.running !== false;
    const type = t.type != null ? String(t.type) : '';
    const shown = (t.desc != null && String(t.desc)) || type || 'background';
    return {
      id: t.id as string | number | null | undefined,
      kind: 'task',
      label: CC.diagramShown('task', 1, shown),
      name: CC.diagramLabel('task', 1, shown),
      meta: type || 'background task',
      status: t.status || null,
      running: running,
      title: t.chain || (t.desc as string | null) || 'Background task',
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
  };
})();
