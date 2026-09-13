(function () {
  'use strict';

  const c = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const T = (CC.tabbar = CC.tabbar || ({} as TabbarNs));
  const h = CC.h;

  function showChat(): void {
    T.selected = null;
    T.send({ type: 'selectAgent', agentId: '' });
    render();
  }

  function showAgent(agentId: string): void {
    T.selected = { kind: 'agent', id: agentId };
    T.send({ type: 'selectAgent', agentId: agentId });
    render();
  }

  function showTask(taskId: string): void {
    T.selected = { kind: 'task', id: taskId };
    T.send({ type: 'revealBackgroundTask', taskId: taskId });
    render();
  }

  T.showChat = showChat;
  T.showAgent = showAgent;
  T.showTask = showTask;

  function subtabPill(w: TabWork, expanded: boolean | null): HTMLElement {
    const node = w.node;
    const isAgent = w.kind === 'agent';
    const name = (node.label as string) || (isAgent ? 'Agent' : (node.type as string) || 'background');
    const status = (node.status as string) || null;
    const open = T.isSelected(w.kind, w.id);
    return T.pill({
      label: isAgent ? name : CC.diagramShown(w.kind, w.depth, name),
      title: CC.diagramLabel(w.kind, w.depth, name) + (status ? '  ·  ' + status : ''),
      status: status,
      selected: open,
      expanded: expanded,
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

  function selectedChatId(): unknown {
    for (let i = 0; i < T.state.chats.length; i++) {
      if (T.state.chats[i] && T.state.chats[i].selected) return T.state.chats[i].id;
    }
    return null;
  }

  let centred: Record<string, unknown> = Object.create(null);
  let lastCentred: Record<string, unknown> = centred;

  function ownsARow(branches: TabBranch[], agentId: string): boolean {
    for (let i = 0; i < branches.length; i++) {
      if (branches[i].rootId === agentId) return true;
    }
    return false;
  }

  function wireRow(capsule: HTMLElement, priorScroll: number, slot: string, aimedAt: unknown): void {
    T.wheelToScroll(capsule);
    T.dragToScroll(capsule);
    T.keepFocusVisible(capsule);
    if (priorScroll) T.scrollLeftTo(capsule, priorScroll);
    centred[slot] = aimedAt;
    if (lastCentred[slot] === aimedAt) return;
    requestAnimationFrame(function () {
      const open = capsule.querySelector('.pill-wrap.selected');
      if (open && open.scrollIntoView) open.scrollIntoView({ block: 'nearest', inline: 'center' });
    });
  }

  function render(): void {
    const bar = T.bar();
    if (!bar) return;
    T.pruneSelection();
    if (T.drawnSignature() === T.drawn && bar.querySelector('.tab-row')) return;
    let rows = bar.querySelector<HTMLElement>('.tab-rows');
    if (!rows) {
      rows = h('div', { class: 'tab-rows' });
      bar.insertBefore(rows, bar.firstChild);
    }
    const priorCapsule = rows.querySelector('.tab-capsule');
    const priorScroll = priorCapsule ? priorCapsule.scrollLeft : 0;
    const priorSubs = rows.querySelector('.subtab-capsule');
    const priorSubScroll = priorSubs ? priorSubs.scrollLeft : 0;
    const priorBranchScroll: Record<string, number> = Object.create(null);
    rows.querySelectorAll('.branch-capsule').forEach(function (el) {
      const owner = el.getAttribute('data-branch');
      if (owner) priorBranchScroll[owner] = el.scrollLeft;
    });
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    const host = rows;
    lastCentred = centred;
    centred = Object.create(null);

    const chatPills = T.state.chats.map(function (chat) {
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
      const capsule = h('div', { class: 'tab-capsule' }, chatPills);
      host.appendChild(h('div', { class: 'tab-row' }, capsule));
      wireRow(capsule, priorScroll, 'chat', selectedChatId());
    }

    const branches = T.openBranches();
    const work = T.chatWork();
    if (work.length) {
      const subPills = [
        T.pill({
          label: 'Chat',
          title: "This chat's own transcript",
          selected: !T.selected,
          onClick: showChat,
        }),
      ];
      work.forEach(function (w) {
        const expandable = w.kind === 'agent' && w.hasKids;
        subPills.push(subtabPill(w, expandable ? ownsARow(branches, w.id) : null));
      });
      const subs = h('div', { class: 'tab-capsule subtab-capsule' }, subPills);
      host.appendChild(h('div', { class: 'tab-row' }, subs));
      wireRow(subs, priorSubScroll, 'sub', T.selected ? T.selected.kind + ':' + T.selected.id : '');
    }

    branches.forEach(function (branch) {
      const branchPills = branch.items.map(function (w) {
        return subtabPill(w, w.hasKids ? ownsARow(branches, w.id) : null);
      });
      const kids = h(
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

    bar.hidden = !chatPills.length && !work.length;

    T.drawn = T.drawnSignature();
  }

  c.tabs = function (payload?: unknown): void {
    const p = (payload || {}) as { chats?: unknown; tree?: unknown; tasks?: unknown };
    T.state.chats = Array.isArray(p.chats) ? (p.chats as TabChat[]) : [];
    T.state.tree = Array.isArray(p.tree) ? (p.tree as TabNode[]) : [];
    T.state.tasks = Array.isArray(p.tasks) ? (p.tasks as TabNode[]) : [];
    render();
  };

  c.revealAgentTab = function (agentId?: unknown): void {
    if (!agentId) return;
    T.selected = { kind: 'agent', id: String(agentId) };
    render();
  };

  c.revealTaskTab = function (taskId?: unknown): void {
    if (!taskId) return;
    T.selected = { kind: 'task', id: String(taskId) };
    render();
  };

  c.clearAgentSelection = function (): void {
    T.selected = null;
    render();
  };
})();
