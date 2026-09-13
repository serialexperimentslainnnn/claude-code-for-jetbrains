(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const S = D.state;
  const h = D.h;
  const conversation = D.conversation;
  const appRoot = D.appRoot;

  function build(): void {
    if (S.built) return;
    const conv = conversation();
    const root = appRoot();
    if (!conv || !root) return;
    S.built = true;

    const panel = h('div', { class: 'dashboard', attrs: { hidden: '', id: 'cc-dashboard' } });
    S.panel = panel;
    if (conv.parentNode) {
      conv.parentNode.insertBefore(panel, conv.nextSibling);
    } else {
      root.appendChild(panel);
    }

    S.inner = h('div', { class: 'dash-inner' });
    panel.appendChild(S.inner);

    S.toggleBtn = D.viewButton('Session', null);
    const chatBtn = h('button', {
      class: 'dash-toggle dash-exit',
      attrs: { type: 'button', 'aria-controls': 'cc-dashboard', 'aria-expanded': 'true' },
      text: 'Chat',
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          if (S.shown) toggle();
          CC.send({ type: 'showChatTranscript' });
          D.announceView();
        },
      },
    });
    S.planBtn = D.viewButton('Plan', 'plan');
    S.planBtn.hidden = true;
    S.gitBtn = D.viewButton('Git', 'git');
    S.gitBtn.hidden = true;
    S.vulnBtn = D.viewButton('Vulnerabilities', 'security');
    S.vulnBtn.hidden = true;
    S.toggles = h(
      'div',
      { class: 'dash-toggles' },
      chatBtn,
      D.viewButton('Workloads', 'workloads'),
      S.gitBtn,
      D.viewButton('Guard', 'guard'),
      S.vulnBtn,
      D.viewButton('Log', 'log'),
      S.toggleBtn,
      S.planBtn
    );
    D.mountToggles();

    applyVisibility();
    D.render();
  }

  function applyVisibility(): void {
    if (!S.panel || !S.toggleBtn) return;
    if (S.shown) {
      S.panel.removeAttribute('hidden');
      S.panel.classList.add('open');
      CC.coverTranscript('dashboard', true);
    } else {
      S.panel.setAttribute('hidden', '');
      S.panel.classList.remove('open');
      CC.coverTranscript('dashboard', false);
      S.currentView = D.defaultView();
    }
    D.syncViewVisibility();
    D.markActiveButton();
  }
  D.applyVisibility = applyVisibility;

  function toggle(): void {
    S.shown = !S.shown;
    if (S.shown) D.render();
    applyVisibility();
  }
  D.toggle = toggle;

  D.leaveDashboard = function (): void {
    if (S.shown) toggle();
  };

  D.repaint = D.renderIfShown;

  D.toggleDashboard = toggle;
  D.dashboardShown = function (): boolean {
    return S.shown;
  };

  function ensureBuilt(): void {
    if (!S.built) build();
  }
  D.ensureBuilt = ensureBuilt;

  function openView(view: string): void {
    ensureBuilt();
    if (!S.built) return;
    S.currentView = view;
    S.shown = true;
    D.render();
    applyVisibility();
  }

  const cc = (window.cc = window.cc || {});

  cc.session = function (payload?: unknown): void {
    S.lastSession = payload && typeof payload === 'object' ? (payload as SessionPayload) : null;
    ensureBuilt();
    D.renderIfShown();
    if (typeof CC.emit === 'function') CC.emit('session', S.lastSession);
  };

  cc.mcp = function (payload?: unknown): void {
    S.lastMcp = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    D.renderIfShown();
  };

  const metaBefore = typeof cc.meta === 'function' ? cc.meta : null;
  cc.meta = function (m?: unknown): void {
    if (metaBefore) metaBefore(m);
    const meta = m as { gitIntegration?: unknown } | null;
    if (meta && meta.gitIntegration === true) S.gitTab = true;
    ensureBuilt();
    D.renderIfShown();
  };

  cc.showGitView = function (): void {
    S.gitTab = true;
    S.gitOpened = false;
    ensureBuilt();
    D.renderIfShown();
  };

  cc.openGuardView = function (): void {
    openView('guard');
  };

  cc.showVulnView = function (): void {
    openView('security');
    D.announceView();
  };

  cc.openDashboard = function (): void {
    ensureBuilt();
    if (!S.built) return;
    S.shown = true;
    D.render();
    applyVisibility();
  };

  cc.closeDashboard = function (): void {
    if (!S.built || !S.shown) return;
    S.shown = false;
    applyVisibility();
  };

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    build();
  } else {
    window.addEventListener('DOMContentLoaded', build);
  }
})();
