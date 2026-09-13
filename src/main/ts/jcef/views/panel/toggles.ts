(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const S = D.state;
  const h = D.h;

  D.viewButton = function (label: string, view: string | null): HTMLElement {
    const id = view || 'session';
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
        click: function (ev: Event) {
          ev.preventDefault();
          if (S.shown && S.currentView === id) {
            D.toggle();
            return;
          }
          S.currentView = id;
          if (S.shown) {
            D.render();
            D.markActiveButton();
          } else {
            D.toggle();
          }
          D.announceView();
        },
      },
    });
  };

  D.announceView = function (): void {
    const c = D.core();
    if (!c || typeof c.announce !== 'function') return;
    if (!S.shown) {
      c.announce('Dashboard closed');
      return;
    }
    const v = D.VIEWS[S.currentView] || D.VIEWS.session;
    c.announce(v.title + ' view');
  };

  D.markActiveButton = function (): void {
    const all = document.querySelectorAll<HTMLElement>('.dash-toggle');
    for (let i = 0; i < all.length; i++) {
      const isChat = all[i].classList.contains('dash-exit');
      const isActive = S.shown ? !isChat && all[i].getAttribute('data-view') === S.currentView : isChat;
      all[i].classList.toggle('active', isActive);
      all[i].setAttribute('aria-expanded', S.shown ? 'true' : 'false');
      if (isActive) {
        all[i].setAttribute('aria-current', 'true');
      } else {
        all[i].removeAttribute('aria-current');
      }
    }
  };

  D.mountToggles = function (): void {
    const into = CC.composer && CC.composer.viewsRow && CC.composer.viewsRow();
    if (into && S.toggles && S.toggles.parentNode !== into) into.appendChild(S.toggles);
  };
})();
