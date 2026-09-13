(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;

  CX.renderReadout = function (s: ComposerState): void {
    const els = CX.els;
    if (!els || !els.readout) return;
    const ro = els.readout;
    ro.innerHTML = '';
    const running = !!s.turnActive;

    const status = h(
      'span',
      { class: 'ro-item strip-cell' },
      h('span', { class: 'ro-dot' + (running ? ' running' : '') }),
      h('span', { text: running ? (s.thinkingStatus ? s.thinkingStatus : 'Running…') : 'Idle' })
    );
    ro.appendChild(status);

    const ctxPct = s.context && typeof s.context.pct === 'number' ? Math.round(s.context.pct) : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: 'Context ' + ctxPct + '%' }));

    const out = typeof s.tokensOut === 'number' ? s.tokensOut : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(out) + ' out' }));

    const reasoning = typeof s.reasoningTokens === 'number' ? s.reasoningTokens : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(reasoning) + ' reasoning' }));

    if (typeof s.costUsd === 'number' && s.costUsd > 0) {
      ro.appendChild(
        h('span', { class: 'ro-item strip-cell', text: '$' + s.costUsd.toFixed(s.costUsd < 1 ? 4 : 2) })
      );
    }

    ro.removeAttribute('hidden');
    if (running && s.thinkingStatus) ro.classList.add('thinking');
    else ro.classList.remove('thinking');

    renderUsageBars(s);
    if (typeof CX.renderMini === 'function') CX.renderMini();
  };

  function renderUsageBars(s: ComposerState): void {
    const els = CX.els;
    if (!els || !els.usageBars) return;
    const host = els.usageBars;
    host.innerHTML = '';
    const usage = Array.isArray(s.usage) ? s.usage : [];
    let shown = 0;
    for (let u = 0; u < usage.length; u++) {
      const win = usage[u] || {};
      if (typeof win.pct !== 'number') continue;
      const label = String(win.label || '');
      const pct = win.pct.toFixed(1) + '%';
      const fill = h('i', { class: usageLevel(win.pct) });
      fill.style.width = Math.max(0, Math.min(100, win.pct)) + '%';
      const reset = CC.resetInShort(win.resetsAt);
      const item = h(
        'div',
        {
          class: 'ub-item strip-cell',
          title: label + ' — ' + pct + ' used' + (reset ? ' · ' + CC.resetIn(win.resetsAt) : ''),
        },
        h(
          'div',
          { class: 'ub-row' },
          h('span', { class: 'ub-label', text: label }),
          h('span', { class: 'ub-track' }, fill),
          h('span', { class: 'ub-pct', text: pct }),
          h('span', { class: 'ub-reset', text: reset })
        )
      );
      host.appendChild(item);
      shown++;
    }
    if (shown > 0) host.removeAttribute('hidden');
    else host.setAttribute('hidden', 'hidden');
  }

  function usageLevel(pct: number): string {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  function formatTokens(n: number): string {
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }
})();
