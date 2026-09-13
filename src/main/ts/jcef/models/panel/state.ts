(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));

  const S: DashPanelState = {
    lastSession: null,
    lastMcp: null,
    toggleBtn: null,
    planBtn: null,
    gitBtn: null,
    vulnBtn: null,
    panel: null,
    inner: null,
    toggles: null,
    shown: false,
    built: false,
    gitTab: false,
    gitOpened: false,
    gitSub: 'overview',
    currentView: 'session',
  };
  D.state = S;

  D.VIEWS = {
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
          D.buildMcpCard(S.lastMcp),
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
      visible: function (shown) {
        if (typeof D.guardVisible === 'function') D.guardVisible(shown);
      },
    },
    log: {
      title: 'Log',
      empty: 'Nothing has been logged yet.',
      cards: function () {
        return typeof D.buildLogCards === 'function' ? D.buildLogCards() : [];
      },
      visible: function (shown) {
        if (typeof D.logVisible === 'function') D.logVisible(shown);
      },
    },
    git: {
      title: 'Git',
      empty: 'No Git repository for this project.',
      cards: function (s) {
        return [
          D.buildGitHeadCard(s.git),
          D.buildGitTopologyCard(s.git),
          D.buildGitActionsCard(s.git),
          D.buildGitHistoryCard(s.git),
        ];
      },
    },
    security: {
      title: 'Vulnerabilities',
      empty: 'No dependency manifest this build can read was found in this project.',
      cards: function (s) {
        return typeof D.buildVulnCards === 'function' ? D.buildVulnCards(s.vuln) : [];
      },
    },
  };

  D.defaultView = function (): string {
    const s = S.lastSession;
    const git = s && (s.git as { available?: boolean } | null);
    return S.gitTab && git && git.available ? 'git' : 'session';
  };

  D.gitChatOpen = function (): boolean {
    return S.currentView === 'git' && S.gitSub === 'chat';
  };

  D.gitSubView = function (): string {
    return S.gitSub;
  };

  D.setGitSubView = function (view: string): void {
    const next = view === 'chat' ? 'chat' : 'overview';
    if (S.gitSub === next) return;
    S.gitSub = next;
    if (S.built && S.shown) D.render();
    const c = D.core();
    if (c && typeof c.announce === 'function') c.announce(next === 'chat' ? 'Git chat' : 'Git overview');
  };

  D.lastSession = function (): SessionPayload | null {
    return S.lastSession;
  };
})();
