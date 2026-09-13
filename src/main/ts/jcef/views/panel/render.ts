(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const S = D.state;
  const h = D.h;

  function syncOptionalButtons(): void {
    const s = S.lastSession;
    const plan = s && (s.plan as { body?: unknown } | null);
    const git = s && (s.git as { available?: boolean } | null);
    const vuln = s && (s.vuln as { available?: boolean } | null);
    optionalButton(S.planBtn, 'plan', !!(plan && plan.body));
    optionalButton(S.gitBtn, 'git', !!(git && git.available));
    optionalButton(S.vulnBtn, 'security', !!(vuln && vuln.available));
  }

  function optionalButton(btn: HTMLElement | null, view: string, has: boolean): void {
    if (!btn) return;
    btn.hidden = !has && S.currentView !== view;
  }

  function openGitTabOnce(): void {
    if (S.gitOpened || !S.gitTab || D.defaultView() !== 'git') return;
    D.ensureBuilt();
    if (!S.built) return;
    S.gitOpened = true;
    S.currentView = 'git';
    S.shown = true;
    D.applyVisibility();
  }

  D.renderIfShown = function (): void {
    syncOptionalButtons();
    openGitTabOnce();
    if (S.built && S.shown) D.render();
  };

  D.render = function (): void {
    const panel = S.panel;
    const inner = S.inner;
    if (!panel || !inner) return;
    D.syncViewVisibility();
    if (D.gitChatOpen()) {
      applyGitSub();
      return;
    }
    const s = S.lastSession || {};
    const view = D.VIEWS[S.currentView] || D.VIEWS.session;
    let cards = view.cards(s).filter(Boolean) as HTMLElement[];

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

    D.reconcile(inner, cards);
    applyGitSub();
  };

  D.syncViewVisibility = function (): void {
    Object.keys(D.VIEWS).forEach(function (key) {
      const view = D.VIEWS[key];
      if (typeof view.visible === 'function') view.visible(S.shown && S.currentView === key);
    });
  };

  D.repaintGuard = function (): void {
    if (S.built && S.shown && S.currentView === 'guard') D.render();
  };

  D.repaintLog = function (): void {
    if (S.built && S.shown && S.currentView === 'log') D.render();
  };

  function applyGitSub(): void {
    const pane = typeof D.gitChatPane === 'function' ? D.gitChatPane() : null;
    const chat = D.gitChatOpen();
    if (S.inner) S.inner.hidden = chat;
    if (pane && S.panel) {
      if (pane.parentNode !== S.panel) S.panel.appendChild(pane);
      pane.hidden = !chat;
      if (chat && typeof D.gitChatShown === 'function') D.gitChatShown();
    }
  }
})();
