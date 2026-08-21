(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var core = D.core;
  var conversation = D.conversation;
  var appRoot = D.appRoot;

  var lastSession = null;
  var lastMcp = null;

  var toggleBtn = null;
  var planBtn = null;
  var gitBtn = null;
  var vulnBtn = null;
  var panel = null;
  var inner = null;
  var toggles = null;
  var shown = false;
  var built = false;

  var gitTab = false;
  var gitOpened = false;

  function renderIfShown() {
    syncOptionalButtons();
    openGitTabOnce();
    if (built && shown) render();
  }

  function syncOptionalButtons() {
    var s = lastSession;
    optionalButton(planBtn, 'plan', !!(s && s.plan && s.plan.body));
    optionalButton(gitBtn, 'git', !!(s && s.git && s.git.available));
    optionalButton(vulnBtn, 'security', !!(s && s.vuln && s.vuln.available));
  }

  function optionalButton(btn, view, has) {
    if (!btn) return;
    btn.hidden = !has && currentView !== view;
  }

  function defaultView() {
    return gitTab && lastSession && lastSession.git && lastSession.git.available ? 'git' : 'session';
  }

  function openGitTabOnce() {
    if (gitOpened || !gitTab || defaultView() !== 'git') return;
    ensureBuilt();
    if (!built) return;
    gitOpened = true;
    currentView = 'git';
    shown = true;
    applyVisibility();
  }

  function render() {
    if (!panel || !inner) return;
    syncGuardVisibility();
    if (gitChatOpen()) {
      applyGitSub();
      return;
    }
    var s = lastSession || {};
    var view = VIEWS[currentView] || VIEWS.session;
    var cards = view.cards(s).filter(Boolean);

    if (!cards.length) {
      cards = [
        h(
          'div',
          { class: 'dash-card dash-empty', attrs: { 'data-card': 'empty' } },
          h('div', { class: 'dash-title', text: view.title }),
          h('div', { class: 'stat-row' }, h('span', { class: 'stat-label', text: view.empty }))
        ),
      ];
    }

    reconcile(inner, cards);
    applyGitSub();
  }

  function keyOf(node) {
    return node && node.getAttribute ? node.getAttribute('data-card') : null;
  }

  function reconcile(container, cards) {
    var existing = Object.create(null);
    var i;
    for (i = 0; i < container.children.length; i++) {
      var key = keyOf(container.children[i]);
      if (key != null) existing[key] = container.children[i];
    }

    var ordered = [];
    for (i = 0; i < cards.length; i++) {
      var next = cards[i];
      var previous = existing[keyOf(next)];
      ordered.push(previous && previous.outerHTML === next.outerHTML ? previous : next);
    }

    for (i = container.children.length - 1; i >= 0; i--) {
      if (ordered.indexOf(container.children[i]) < 0) container.removeChild(container.children[i]);
    }

    for (i = 0; i < ordered.length; i++) {
      if (container.children[i] !== ordered[i]) container.insertBefore(ordered[i], container.children[i] || null);
    }
  }

  function syncGuardVisibility() {
    if (typeof D.guardVisible === 'function') D.guardVisible(shown && currentView === 'guard');
  }

  D.repaintGuard = function () {
    if (built && shown && currentView === 'guard') render();
  };

  function gitChatOpen() {
    return currentView === 'git' && gitSub === 'chat';
  }

  function applyGitSub() {
    var pane = typeof D.gitChatPane === 'function' ? D.gitChatPane() : null;
    var chat = gitChatOpen();
    if (inner) inner.hidden = chat;
    if (pane) {
      if (pane.parentNode !== panel) panel.appendChild(pane);
      pane.hidden = !chat;
      if (chat && typeof D.gitChatShown === 'function') D.gitChatShown();
    }
  }

  var gitSub = 'overview';

  D.gitSubView = function () {
    return gitSub;
  };

  D.setGitSubView = function (view) {
    var next = view === 'chat' ? 'chat' : 'overview';
    if (gitSub === next) return;
    gitSub = next;
    if (built && shown) render();
    var c = core();
    if (c && typeof c.announce === 'function') c.announce(next === 'chat' ? 'Git chat' : 'Git overview');
  };

  var VIEWS = {
    session: {
      title: 'Session',
      empty: 'No session data yet.',
      cards: function (s) {
        return [
          D.buildUsageCard(s.usage),
          D.buildContextCard(s.context),
          D.buildCostCard(s.cost),
          D.buildAccountCard(s.account),
          D.buildEnvCard(s),
          D.buildMcpCard(lastMcp),
        ];
      },
    },
    workloads: {
      title: 'Workloads',
      empty: 'Nothing is running: no agents, no background tasks.',
      cards: function (s) {
        return [D.buildWorkloadsCard(s)];
      },
    },
    plan: {
      title: 'Plan',
      empty: 'No plan for this session.',
      cards: function (s) {
        return [D.buildPlanCard(s.plan)];
      },
    },
    guard: {
      title: 'Guard',
      empty: 'The guard has judged nothing in this chat yet.',
      cards: function () {
        return typeof D.buildGuardCards === 'function' ? D.buildGuardCards() : [];
      },
    },
    git: {
      title: 'Git',
      empty: 'No Git repository for this project.',
      cards: function (s) {
        return [
          D.buildGitHeadCard(s.git),
          D.buildGitTopologyCard(s.git),
          D.buildGitForgeCard(s.git),
          D.buildGitActionsCard(s.git),
          D.buildGitHistoryCard(s.git),
        ];
      },
    },
    security: {
      title: 'Security',
      empty: 'No dependency manifest this build can read was found in this project.',
      cards: function (s) {
        return typeof D.buildVulnCards === 'function' ? D.buildVulnCards(s.vuln) : [];
      },
    },
  };

  function viewButton(label, view) {
    var id = view || 'session';
    return h('button', {
      class: 'dash-toggle',
      attrs: {
        type: 'button',
        'data-view': id,
        'aria-controls': 'cc-dashboard',
        'aria-expanded': 'false',
      },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown && currentView === id) {
            toggle();
            return;
          }
          currentView = id;
          if (shown) {
            render();
            markActiveButton();
          } else {
            toggle();
          }
          announceView();
        },
      },
    });
  }

  function announceView() {
    var c = core();
    if (!c || typeof c.announce !== 'function') return;
    if (!shown) {
      c.announce('Dashboard closed');
      return;
    }
    var v = VIEWS[currentView] || VIEWS.session;
    c.announce(v.title + ' view');
  }

  var currentView = 'session';

  function markActiveButton() {
    var all = document.querySelectorAll('.dash-toggle');
    for (var i = 0; i < all.length; i++) {
      var isChat = all[i].classList.contains('dash-exit');
      var isActive = shown ? !isChat && all[i].getAttribute('data-view') === currentView : isChat;
      all[i].classList.toggle('active', isActive);
      all[i].setAttribute('aria-expanded', shown ? 'true' : 'false');
      if (isActive) {
        all[i].setAttribute('aria-current', 'true');
      } else {
        all[i].removeAttribute('aria-current');
      }
    }
  }

  function build() {
    if (built) return;
    var conv = conversation();
    var root = appRoot();
    if (!conv || !root) return;
    built = true;

    panel = h('div', { class: 'dashboard', attrs: { hidden: '', id: 'cc-dashboard' } });
    if (conv.parentNode) {
      conv.parentNode.insertBefore(panel, conv.nextSibling);
    } else {
      root.appendChild(panel);
    }

    inner = h('div', { class: 'dash-inner' });
    panel.appendChild(inner);

    toggleBtn = viewButton('Session', null);
    var chatBtn = h('button', {
      class: 'dash-toggle dash-exit',
      attrs: { type: 'button', 'aria-controls': 'cc-dashboard', 'aria-expanded': 'true' },
      text: 'Chat',
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown) toggle();
          CC.send({ type: 'showChatTranscript' });
          announceView();
        },
      },
    });
    planBtn = viewButton('Plan', 'plan');
    planBtn.hidden = true;
    gitBtn = viewButton('Git', 'git');
    gitBtn.hidden = true;
    vulnBtn = viewButton('Security', 'security');
    vulnBtn.hidden = true;
    var stack = h(
      'div',
      { class: 'dash-toggles' },
      chatBtn,
      toggleBtn,
      viewButton('Workloads', 'workloads'),
      viewButton('Guard', 'guard'),
      gitBtn,
      vulnBtn,
      planBtn
    );
    toggles = stack;
    mountToggles();

    applyVisibility();
    render();
  }

  function mountToggles() {
    var into = CC.composer && CC.composer.viewsRow && CC.composer.viewsRow();
    if (into && toggles && toggles.parentNode !== into) into.appendChild(toggles);
  }
  D.mountToggles = mountToggles;

  function applyVisibility() {
    if (!panel || !toggleBtn) return;
    if (shown) {
      panel.removeAttribute('hidden');
      panel.classList.add('open');
      CC.coverTranscript('dashboard', true);
    } else {
      panel.setAttribute('hidden', '');
      panel.classList.remove('open');
      CC.coverTranscript('dashboard', false);
      currentView = defaultView();
    }
    syncGuardVisibility();
    markActiveButton();
  }

  function toggle() {
    shown = !shown;
    if (shown) render();
    applyVisibility();
  }

  D.leaveDashboard = function () {
    if (shown) toggle();
  };

  D.repaint = renderIfShown;

  D.toggleDashboard = toggle;
  D.dashboardShown = function () {
    return shown;
  };

  D.lastSession = function () {
    return lastSession;
  };

  function ensureBuilt() {
    if (!built) build();
  }

  var cc = window.cc || (window.cc = {});

  cc.session = function (payload) {
    lastSession = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    renderIfShown();
  };

  cc.mcp = function (payload) {
    lastMcp = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    renderIfShown();
  };

  var metaBefore = typeof cc.meta === 'function' ? cc.meta : null;
  cc.meta = function (m) {
    if (metaBefore) metaBefore(m);
    if (m && m.gitIntegration === true) gitTab = true;
    ensureBuilt();
    renderIfShown();
  };

  cc.showGitView = function () {
    gitTab = true;
    gitOpened = false;
    ensureBuilt();
    renderIfShown();
  };

  cc.openGuardView = function () {
    ensureBuilt();
    if (!built) return;
    currentView = 'guard';
    shown = true;
    render();
    applyVisibility();
  };

  cc.showVulnView = function () {
    ensureBuilt();
    if (!built) return;
    currentView = 'security';
    shown = true;
    render();
    applyVisibility();
    announceView();
  };

  cc.openDashboard = function () {
    ensureBuilt();
    if (!built) return;
    shown = true;
    render();
    applyVisibility();
  };

  cc.closeDashboard = function () {
    if (!built || !shown) return;
    shown = false;
    applyVisibility();
  };

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    build();
  } else {
    window.addEventListener('DOMContentLoaded', build);
  }
})();
