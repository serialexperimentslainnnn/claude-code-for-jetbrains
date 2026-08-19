(function () {
  'use strict';

  var c = window.cc || (window.cc = {});
  var CC = window.CC || {};
  var T = (CC.tabbar = CC.tabbar || {});
  var h = CC.h;

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

  function subtabPill(w, expanded) {
    var node = w.node;
    var isAgent = w.kind === 'agent';
    var name = node.label || (isAgent ? 'Agent' : node.type || 'background');
    var status = node.status || null;
    var open = T.isSelected(w.kind, w.id);
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

  function selectedChatId() {
    for (var i = 0; i < T.state.chats.length; i++) {
      if (T.state.chats[i] && T.state.chats[i].selected) return T.state.chats[i].id;
    }
    return null;
  }

  var centred = Object.create(null);

  function ownsARow(branches, agentId) {
    for (var i = 0; i < branches.length; i++) {
      if (branches[i].rootId === agentId) return true;
    }
    return false;
  }

  function wireRow(capsule, priorScroll, slot, aimedAt) {
    T.wheelToScroll(capsule);
    T.dragToScroll(capsule);
    T.keepFocusVisible(capsule);
    if (priorScroll) T.scrollLeftTo(capsule, priorScroll);
    if (centred[slot] === aimedAt) return;
    centred[slot] = aimedAt;
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
    var rows = host.querySelector('.tab-rows');
    if (!rows) {
      rows = h('div', { class: 'tab-rows' });
      host.insertBefore(rows, host.firstChild);
    }
    var priorCapsule = rows.querySelector('.tab-capsule');
    var priorScroll = priorCapsule ? priorCapsule.scrollLeft : 0;
    var priorSubs = rows.querySelector('.subtab-capsule');
    var priorSubScroll = priorSubs ? priorSubs.scrollLeft : 0;
    var priorBranchScroll = Object.create(null);
    Array.prototype.forEach.call(rows.querySelectorAll('.branch-capsule'), function (el) {
      var owner = el.getAttribute('data-branch');
      if (owner) priorBranchScroll[owner] = el.scrollLeft;
    });
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    host = rows;

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

    var branches = T.openBranches();
    var work = T.chatWork();
    if (work.length) {
      var subPills = [
        T.pill({
          label: 'Chat',
          title: "This chat's own transcript",
          selected: !T.selected,
          onClick: showChat,
        }),
      ];
      work.forEach(function (w) {
        var expandable = w.kind === 'agent' && w.hasKids;
        subPills.push(subtabPill(w, expandable ? ownsARow(branches, w.id) : null));
      });
      var subs = h('div', { class: 'tab-capsule subtab-capsule' }, subPills);
      host.appendChild(h('div', { class: 'tab-row' }, subs));
      wireRow(subs, priorSubScroll, 'sub', T.selected ? T.selected.kind + ':' + T.selected.id : '');
    }

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

    T.drawn = T.drawnSignature();
  }

  c.tabs = function (payload) {
    var p = payload || {};
    T.state.chats = Array.isArray(p.chats) ? p.chats : [];
    T.state.tree = Array.isArray(p.tree) ? p.tree : [];
    T.state.tasks = Array.isArray(p.tasks) ? p.tasks : [];
    render();
  };

  c.revealAgentTab = function (agentId) {
    if (!agentId) return;
    T.selected = { kind: 'agent', id: agentId };
    render();
  };

  c.revealTaskTab = function (taskId) {
    if (!taskId) return;
    T.selected = { kind: 'task', id: taskId };
    render();
  };

  c.clearAgentSelection = function () {
    T.selected = null;
    render();
  };
})();
