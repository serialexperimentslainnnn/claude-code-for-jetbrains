(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;

  const MINI_ID = 'cc-session-mini';
  const READOUT_ID = 'cc-strip-readout';
  const BARS_ID = 'cc-strip-bars';
  const MORE_ID = 'cc-strip-more';

  let mini: { root: HTMLElement; grid: HTMLElement } | null = null;

  function sessionPayload(): SessionPayload | null {
    const d = CC.dash;
    return d && typeof d.lastSession === 'function' ? d.lastSession() : null;
  }

  if (typeof CC.on === 'function') {
    CC.on('session', function () {
      renderMini();
    });
  }

  function ensureMini(): { root: HTMLElement; grid: HTMLElement } | null {
    if (mini) return mini;
    const els = CX.els;
    const readout = els && els.readout;
    const bars = els && els.usageBars;
    if (!readout || !bars || !readout.parentNode) return null;

    const grid = h('div', { class: 'dash-mini-grid', attrs: { id: MINI_ID } });
    const root = h('div', { class: 'dash-mini', attrs: { hidden: 'hidden' } }, grid);

    const parent = readout.parentNode;
    parent.insertBefore(bars, readout);
    parent.insertBefore(root, readout);
    mini = { root: root, grid: grid };
    return mini;
  }

  function renderMini(): void {
    const m = ensureMini();
    if (!m) return;
    if (!sessionPayload()) {
      m.root.setAttribute('hidden', 'hidden');
      clearMini();
    } else {
      m.root.removeAttribute('hidden');
      drawMini();
    }
    syncFold();
  }
  CX.renderMini = renderMini;

  function clearMini(): void {
    if (!mini) return;
    while (mini.grid.firstChild) mini.grid.removeChild(mini.grid.firstChild);
  }

  function fact(label: string, value: unknown): HTMLElement | null {
    if (value == null || value === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell', title: label + ': ' + value },
      h('span', { class: 'mini-key', text: label + ':' }),
      h('span', { class: 'mini-val', text: String(value) })
    );
  }

  function abbreviateHome(path: string, home: unknown): string {
    if (!path || !home) return path;
    const root = String(home).replace(/\\/g, '/').replace(/\/+$/, '');
    if (!root || String(path).replace(/\\/g, '/').slice(0, root.length) !== root) return path;
    const rest = String(path).slice(root.length);
    if (rest === '') return '~';
    return /^[/\\]/.test(rest) ? '~' + rest : path;
  }

  function workingDirFact(cwd: unknown, home: unknown): HTMLElement | null {
    if (cwd == null || cwd === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell mini-fill', title: 'Working dir: ' + cwd },
      h('span', { class: 'mini-key', text: 'Working dir:' }),
      h('span', { class: 'mini-val', text: abbreviateHome(String(cwd), home) })
    );
  }

  function organizationWorthShowing(org: unknown, email: unknown): unknown {
    if (!org || !email) return org;
    const value = String(org).trim();
    const owner = String(email).trim();
    if (value.slice(0, owner.length).toLowerCase() !== owner.toLowerCase()) return org;
    const tail = value
      .slice(owner.length)
      .trim()
      .replace(/^[^a-z0-9]+/i, '');
    return /^s?\s*(org|organi[sz]ations?)?$/i.test(tail) ? null : org;
  }

  function factLine(facts: (HTMLElement | null)[]): HTMLElement | null {
    const kept = facts.filter(Boolean) as HTMLElement[];
    return kept.length ? h('div', { class: 'mini-line' }, kept) : null;
  }

  function drawMini(): void {
    const m = mini;
    if (!m) return;
    const s = sessionPayload() || {};
    clearMini();

    const account = s.account || {};
    const lines = [
      factLine([fact('Model', s.model), workingDirFact(s.cwd, s.home)]),
      factLine([
        fact('Account', account.email),
        fact('Organization', organizationWorthShowing(account.org, account.email)),
        fact('Plan', account.plan),
        fact('Provider', account.provider),
      ]),
    ].filter(Boolean) as HTMLElement[];

    if (!lines.length) {
      m.grid.appendChild(h('div', { class: 'mini-empty', text: 'No session data yet.' }));
    } else {
      lines.forEach(function (line) {
        m.grid.appendChild(line);
      });
    }
  }

  let expanded = false;
  let moreBtn: HTMLElement | null = null;

  function foldableRow(host: HTMLElement | null | undefined, selector: string): boolean {
    return !!host && !host.hasAttribute('hidden') && host.querySelectorAll(selector).length > 2;
  }

  function anythingToFold(): boolean {
    const els = CX.els;
    if (!els) return false;
    if (foldableRow(els.readout, '.ro-item')) return true;
    if (foldableRow(els.usageBars, '.ub-item')) return true;
    if (!mini || mini.root.hasAttribute('hidden')) return false;
    const lines = mini.grid.querySelectorAll('.mini-line');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].querySelectorAll('.mini-fact').length > 2) return true;
    }
    return false;
  }

  function buildMoreBtn(): HTMLElement | null {
    const els = CX.els;
    const after = els && els.readout;
    if (!after || !after.parentNode) return null;
    const btn = h('button', {
      class: 'strip-more',
      attrs: {
        id: MORE_ID,
        type: 'button',
        'aria-expanded': 'false',
        'aria-controls': READOUT_ID + ' ' + MINI_ID + ' ' + BARS_ID,
      },
      on: {
        click: function () {
          expanded = !expanded;
          syncFold();
        },
      },
    });
    after.parentNode.insertBefore(btn, after.nextSibling);
    return btn;
  }

  function syncFold(): void {
    const els = CX.els;
    if (!els || !els.readout) return;
    els.readout.id = READOUT_ID;
    if (els.usageBars) els.usageBars.id = BARS_ID;

    const host = CC.els && CC.els.composer;
    if (host) host.classList.toggle('strip-open', expanded);

    if (!anythingToFold()) {
      if (moreBtn && moreBtn.parentNode) moreBtn.parentNode.removeChild(moreBtn);
      moreBtn = null;
      return;
    }
    if (!moreBtn) moreBtn = buildMoreBtn();
    if (!moreBtn) return;
    moreBtn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    moreBtn.textContent = expanded ? 'Show less' : 'Show more';
  }
})();
