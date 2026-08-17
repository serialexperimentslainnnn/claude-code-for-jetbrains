/* app-composer-readout.js — the session line above the prompt box.
 *
 * One subject: the numbers a running session shows about itself — status, context, tokens, cost — the
 * plan-limit bars on the row under them, and the fold that puts the dashboard's own cards in the same strip.
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
    // The composer exists by now, so this is the earliest moment the fold below can be mounted. Idempotent,
    // and a no-op until a session payload has actually arrived.
    renderMini();
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
      // UNKNOWN, not zero — and the distinction is the whole reason this is a `typeof` test and not `!win.pct`.
      // A window reported at a genuine 0% IS rendered (`typeof 0 === 'number'`), bar empty and text "0.0%",
      // for the same reason the three counters above settle at zero: a zero is a measurement. What is skipped
      // is a window carrying NO percentage at all, which the host does not currently send either
      // (`JcefState.compactUsageJson` drops it, deliberately: this row is a glance, and a "—" that can never
      // resolve costs more than one fewer indicator). The dashboard card keeps those and shows them as "—",
      // which is where the distinction is worth a row.
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

  // ---- the session view, in miniature ---------------------------------------
  //
  // The dashboard's Session cards, folded into this same strip so the numbers can be read without leaving the
  // chat. SHUT BY DEFAULT behind one button: six cards is a panel, and a panel that is always open is the
  // transcript's space spent on something nobody asked to see.
  //
  // Two rules it inherits from the panel, both learned by breaking them there:
  //   · shut means NOT BUILT. The host pushes a session payload several times a turn, and the dashboard used
  //     to rebuild itself while hidden "to keep the DOM fresh" — laying out cards for a panel nobody was
  //     looking at. While this is shut the payload is stashed and nothing is drawn (see `renderIfShown` in
  //     app-session.js).
  //   · a container that gets rebuilt loses where the reader was. The body is its own scroll box and the grid
  //     inside it is rebuilt from nothing on every push, so the offset is read before the teardown and put
  //     back after.
  //
  // The cards themselves are the SAME builders the dashboard uses (`CC.dash.build*Card`), never reduced
  // copies: a card that changed in one place and not the other would be two answers to one question. Density
  // is a CSS concern and lives on `.dash-mini` in dashboard.css.

  /** The block's id, so anything that needs to point at it can. */
  var MINI_ID = 'cc-session-mini';

  /** `{ root, body, grid }` once the composer exists; null before that. */
  var mini = null;

  /**
   * The session payload — asked of the dashboard, never copied.
   *
   * The mini fold draws the dashboard's own cards, so it must draw them from the dashboard's own payload.
   * Keeping a second copy here is how two surfaces come to disagree: they would only ever differ by one
   * missed push, which is exactly the state nobody thinks to test.
   */
  function sessionPayload() {
    var d = CC.dash;
    return d && typeof d.lastSession === 'function' ? d.lastSession() : null;
  }

  /**
   * Redraws the fold whenever a session payload goes past, without editing the file that owns it.
   *
   * `app-session.js` implements `cc.session` and loads AFTER this file (`JcefHost.appNames`), assigning it
   * outright — so a wrapper installed here at load time would simply be overwritten, and one installed on
   * `DOMContentLoaded` would make the order of two listeners the contract (and would never run in the test
   * harness, which evals the modules into an already-loaded document).
   *
   * The SLOT is therefore taken: `cc.session` becomes an accessor whose setter records whoever installs the
   * real implementation, and whose getter returns one stable wrapper that calls straight through and then
   * redraws. Nothing downstream can tell the difference — still a function of one argument, still
   * enumerable, and the dashboard is still the only thing that decides what the payload MEANS. This is a
   * notification, not a second owner of the data.
   */
  (function watchSessionPayload() {
    var cc = window.cc || (window.cc = {});
    var present = typeof cc.session === 'function' ? cc.session : null;
    // IDEMPOTENT: every other module here reassigns its own methods, so re-evaluating one into a page that
    // already ran it is harmless. This one READS what is there, so a tap taking over from an earlier tap
    // would chain to a closure holding the previous document's nodes. The marker breaks that chain — the
    // page evaluates each module once, the test harness re-evaluates them per test.
    var inner = present && present.ccSessionTap ? null : present;
    function wrapper(payload) {
      if (inner) inner.call(cc, payload);
      renderMini();
    }
    wrapper.ccSessionTap = true;
    try {
      Object.defineProperty(cc, 'session', {
        configurable: true,
        enumerable: true,
        get: function () {
          return wrapper;
        },
        set: function (fn) {
          inner = typeof fn === 'function' ? fn : null;
        },
      });
    } catch (e) {
      // A frozen or exotic `cc` keeps its dashboard and simply never gets the fold. The composer must not
      // fail to load over a view that is an extra.
    }
  })();

  /**
   * Builds the block once, directly under the plan-limit bars. Null until the composer exists.
   *
   * NO disclosure control. It had one — a *More info* button — and the button was there because the block
   * behind it was the Session panel's six cards, which is a panel and not a strip. What is left is five short
   * facts about who is signed in and what this session is running; a control to reveal five lines costs a
   * click, a state to remember and a second thing to keep accessible, and buys nothing.
   */
  function ensureMini() {
    if (mini) return mini;
    var els = CX.els;
    var after = els && els.usageBars;
    if (!after || !after.parentNode) return null;

    var grid = h('div', { class: 'dash-mini-grid' });
    var body = h('div', { class: 'dash-mini-body', attrs: { id: MINI_ID } }, grid);
    var root = h('div', { class: 'dash-mini', attrs: { hidden: 'hidden' } }, body);

    after.parentNode.insertBefore(root, after.nextSibling);
    mini = { root: root, body: body, grid: grid };
    return mini;
  }

  /** Shows the block once there is a session to describe, and redraws it on every push. */
  function renderMini() {
    var m = ensureMini();
    if (!m) return;
    if (!sessionPayload()) {
      // Nothing to say yet. Hidden outright rather than left as an empty frame: an empty box reads as a
      // failure to load, which is the one thing it is not.
      m.root.setAttribute('hidden', 'hidden');
      clearMini();
      return;
    }
    m.root.removeAttribute('hidden');
    drawMini();
  }

  function clearMini() {
    if (!mini) return;
    while (mini.grid.firstChild) mini.grid.removeChild(mini.grid.firstChild);
  }

  /** A count, grouped — or null when the host sent none, which is not the same as zero. */
  /** One `label: value` pair. An absent value draws nothing at all — a dash is not information. */
  function fact(label, value) {
    if (value == null || value === '') return null;
    return h(
      'span',
      { class: 'mini-fact', title: label + ': ' + value },
      h('span', { class: 'mini-key', text: label + ':' }),
      h('span', { class: 'mini-val', text: String(value) })
    );
  }

  /** A line of the block: the facts that are present, laid out across it. Nothing when they all are absent. */
  function factLine(facts) {
    var kept = facts.filter(Boolean);
    return kept.length ? h('div', { class: 'mini-line' }, kept) : null;
  }

  /**
   * The block's OWN format, and deliberately not the Session view's cards.
   *
   * Two passes got this wrong before settling here, and both are worth stating because the pull towards them
   * is strong. Reusing `buildUsageCard`/`buildContextCard`/… repeated what is already on screen — the plan
   * bars and the context figure are the lines directly above — and dropped page furniture into a strip:
   * bordered boxes and 12px type under a row of flat 11px readout items. Making them a two-column table then
   * fixed the furniture and kept the wrong shape: a column of labels down the left is a reference table, and
   * this is five short facts that belong on two lines.
   *
   * So: `label: value` pairs, flowing, in the readout's own type — the same grammar as the line above it. The
   * numbers that MOVE (tokens, cost, quota, context) stay up there where they are watched; these are the
   * standing facts, which are looked up.
   */
  function drawMini() {
    var m = mini;
    if (!m) return;
    var s = sessionPayload() || {};
    // Read before the teardown, put back after: the body is rebuilt from nothing on every push, and a fresh
    // subtree is born at offset 0.
    var offset = m.body.scrollTop;
    clearMini();

    var account = s.account || {};
    var lines = [
      factLine([fact('Model', s.model), fact('Working dir', s.cwd)]),
      factLine([
        fact('Account', account.email),
        fact('Organization', account.org),
        fact('Plan', account.plan),
        fact('Provider', account.provider),
      ]),
    ].filter(Boolean);

    if (!lines.length) {
      m.grid.appendChild(h('div', { class: 'mini-empty', text: 'No session data yet.' }));
    } else {
      lines.forEach(function (line) {
        m.grid.appendChild(line);
      });
    }
    m.body.scrollTop = offset;
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
