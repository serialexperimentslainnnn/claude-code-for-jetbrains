/* app-composer-readout.js — the session line above the prompt box.
 *
 * One subject: the numbers a running session shows about itself — status, context, tokens, cost — and the
 * plan-limit bars on the row under them.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  // ---- readout --------------------------------------------------------------
  // Session-usage line: a running/idle status dot + context %, tokens out, cost. Always visible.
  CX.renderReadout = function (s) {
    var els = CX.els;
    if (!els || !els.readout) return;
    var ro = els.readout;
    ro.innerHTML = '';
    var running = !!s.turnActive;

    var status = h(
      'span',
      { class: 'ro-item' },
      h('span', { class: 'ro-dot' + (running ? ' running' : '') }),
      h('span', { text: running ? (s.thinkingStatus ? s.thinkingStatus : 'Running…') : 'Idle' })
    );
    ro.appendChild(status);

    // These three are ALWAYS rendered, settling at 0 rather than being omitted until they are non-zero.
    // Hiding an item until it has a value makes "nothing has happened yet" look identical to "this failed to
    // load", which is exactly how the readout read on a fresh tab: a lone "Idle" and no numbers. A zero is a
    // measurement; an absence is not.
    var ctxPct = s.context && typeof s.context.pct === 'number' ? Math.round(s.context.pct) : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: 'Context ' + ctxPct + '%' }));

    var out = typeof s.tokensOut === 'number' ? s.tokensOut : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: formatTokens(out) + ' out' }));

    var reasoning = typeof s.reasoningTokens === 'number' ? s.reasoningTokens : 0;
    ro.appendChild(h('span', { class: 'ro-item', text: formatTokens(reasoning) + ' reasoning' }));

    // Cost stays gated: unlike the counters above it is a currency amount, and "$0.0000" on every idle tab is
    // noise rather than information — there is no ambiguity to resolve, since a session that has spent nothing
    // has nothing to report.
    if (typeof s.costUsd === 'number' && s.costUsd > 0) {
      ro.appendChild(h('span', { class: 'ro-item', text: '$' + s.costUsd.toFixed(s.costUsd < 1 ? 4 : 2) }));
    }

    // NB no sign-out control here. The readout is a wrapping flex row of metrics, so a button pushed to its
    // far end drops onto a second line the moment the numbers fill the width. Log out lives in the tool
    // window's title bar (ClaudeToolWindowFactory.SignOutAction) and on the dashboard's account row.
    ro.removeAttribute('hidden');
    if (running && s.thinkingStatus) ro.classList.add('thinking');
    else ro.classList.remove('thinking');

    renderUsageBars(s);
  };

  /**
   * Plan limits, one labelled bar per window, on their own row directly under the readout.
   *
   * They used to be dots inline in the readout, which put them at the end of a wrapping row of unrelated
   * metrics: the windows that matter most (the ones nearest their cap) were the ones most likely to be pushed
   * onto a second line or off the visible width. A bar reads the fill at a glance where a dot only reads a
   * colour, and giving them their own row means the length of the status line can no longer displace them.
   *
   * The row is a `repeat(auto-fit, minmax(…, 1fr))` grid, so it is one bar per column at full tool-window
   * width and reflows to fewer, still-full-width columns as the window narrows — never a fixed track that
   * leaves dead space on the right or overflows on the left.
   */
  function renderUsageBars(s) {
    var els = CX.els;
    if (!els || !els.usageBars) return;
    var host = els.usageBars;
    host.innerHTML = '';
    var usage = Array.isArray(s.usage) ? s.usage : [];
    var shown = 0;
    for (var u = 0; u < usage.length; u++) {
      var win = usage[u] || {};
      if (typeof win.pct !== 'number') continue;
      var label = String(win.label || '');
      var pct = win.pct.toFixed(1) + '%';
      // The BAR is clamped to 0..100 so a server figure past its cap cannot overflow the track; the TEXT is
      // not, because a window reported at 103% is exactly the number the user needs to see.
      var fill = h('i', { class: usageLevel(win.pct) });
      fill.style.width = Math.max(0, Math.min(100, win.pct)) + '%';
      // How long the window has left, on its OWN line under the bar. A percentage without it says how much is
      // spent but not whether that matters: 90% with eight minutes to go and 90% with six hours to go are
      // different situations, and only the dashboard was answering that. Under the bar rather than beside it
      // because the bar row is already three items wide per window — a fourth turned the countdown into the
      // first thing to be squeezed out, which is the one case where it matters most.
      var reset = CC.resetInShort(win.resetsAt);
      var item = h(
        'div',
        {
          class: 'ub-item',
          title: label + ' — ' + pct + ' used' + (reset ? ' · ' + CC.resetIn(win.resetsAt) : ''),
        },
        h(
          'div',
          { class: 'ub-row' },
          h('span', { class: 'ub-label', text: label }),
          h('span', { class: 'ub-track' }, fill),
          h('span', { class: 'ub-pct', text: pct })
        )
      );
      if (reset) item.appendChild(h('span', { class: 'ub-reset', text: 'Reset time: ' + reset }));
      host.appendChild(item);
      shown++;
    }
    if (shown > 0) host.removeAttribute('hidden');
    else host.setAttribute('hidden', 'hidden');
  }

  /**
   * Quota severity, shared by the readout dot and the dashboard bar so the two can never disagree about
   * whether you are in trouble: blue under 65%, amber under 85%, red at or above it.
   *
   * Duplicated in app-session.js by NAME on purpose — these are two independently-loaded scripts with no
   * module system between them, so the CSS class names are the contract, and css-contract.test.js pins them.
   */
  function usageLevel(pct) {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  function formatTokens(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }
})();
