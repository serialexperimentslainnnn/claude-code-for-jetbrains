/* app-composer-readout.js — the session line above the prompt box.
 *
 * One subject: the numbers a running session shows about itself — status, context, tokens, cost — the
 * standing facts on the lines under them, the plan-limit bars, and the disclosure that folds the strip's
 * third and fourth columns away when the tool window is too narrow to hold four.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  // `strip-cell` is on every cell of every row of the strip, and it is written out in full at each of the
  // seven places rather than concatenated from a constant. Both halves of that are deliberate.
  //
  // ONE CLASS, because two behaviours in `composer.css` hang off it and both are about the COLUMN rather than
  // about any row: the `·` painted in the gap to the cell's right, and the fold that hides the third and
  // fourth column of all five rows at once. A rule that reached the rows by their own class names would be
  // three chances to hide a plan bar and leave its countdown behind.
  //
  // WRITTEN OUT, because `css-contract.test.js` finds the classes this page emits by scanning the sources
  // for a class key whose value is a plain STRING LITERAL. A name assembled at run time — the shared part in
  // a constant, joined on with a `+` — is invisible to it, and four classes would drop out of the gate that
  // exists to catch a class with no rule behind it: the cheapest form of this repository's signature defect,
  // bought for one less repeated word.
  //
  // NB this paragraph deliberately DESCRIBES that syntax rather than quoting it. The scanner drops comments
  // before it looks, so a quotation here no longer confuses it — but prose shaped like an emission is a trap
  // for the next reader for the same reason it was one for the gate.

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
      { class: 'ro-item strip-cell' },
      h('span', { class: 'ro-dot' + (running ? ' running' : '') }),
      h('span', { text: running ? (s.thinkingStatus ? s.thinkingStatus : 'Running…') : 'Idle' })
    );
    ro.appendChild(status);

    // These three are ALWAYS rendered, settling at 0 rather than being omitted until they are non-zero.
    // Hiding an item until it has a value makes "nothing has happened yet" look identical to "this failed to
    // load", which is exactly how the readout read on a fresh tab: a lone "Idle" and no numbers. A zero is a
    // measurement; an absence is not.
    var ctxPct = s.context && typeof s.context.pct === 'number' ? Math.round(s.context.pct) : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: 'Context ' + ctxPct + '%' }));

    var out = typeof s.tokensOut === 'number' ? s.tokensOut : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(out) + ' out' }));

    var reasoning = typeof s.reasoningTokens === 'number' ? s.reasoningTokens : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(reasoning) + ' reasoning' }));

    // Cost stays gated: unlike the counters above it is a currency amount, and "$0.0000" on every idle tab is
    // noise rather than information — there is no ambiguity to resolve, since a session that has spent nothing
    // has nothing to report.
    if (typeof s.costUsd === 'number' && s.costUsd > 0) {
      ro.appendChild(
        h('span', { class: 'ro-item strip-cell', text: '$' + s.costUsd.toFixed(s.costUsd < 1 ? 4 : 2) })
      );
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
      // How long the window has left, BESIDE its own percentage rather than on a line of its own. A percentage
      // without it says how much is spent but not whether that matters: 90% with eight minutes to go and 90%
      // with six hours to go are different situations. It had a row to itself, under the bars, and that row
      // cost the strip a whole line to repeat the word "Reset time:" three times — so the countdown is now the
      // last item of its window's own line, where it reads as the qualifier it is, and the strip is one row
      // shorter. The label it used to need is gone with the row: next to `72.0%`, `3h 2m` is unambiguous, and
      // the spelled-out form is still in the cell's `title` for anyone who wants it.
      var reset = CC.resetInShort(win.resetsAt);
      // ONE cell holds the bar AND its countdown, which is what makes the two inseparable rather than merely
      // adjacent. Whatever hides a column — today the narrow-window fold, tomorrow something else — acts on
      // cells, so it cannot take a window's bar and leave the countdown that qualifies it, or the reverse: a
      // countdown belonging to somebody else's bar is worse than no countdown at all.
      var item = h(
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
          // ALWAYS appended, and EMPTY when the window carries no reset time. The emptiness is the
          // load-bearing half: a filler like `—` or `n/a` is a value, and the one reading anyone would give it
          // here is "resets now". With no text the span produces no box at all, so it is absent content to a
          // screen reader and nothing on screen — the countdown simply does not exist for that window, which
          // is what it should say.
          h('span', { class: 'ub-reset', text: reset })
        )
      );
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

  /**
   * Ids for the other two rows, and for the disclosure — the fold's `aria-controls` needs a name for each
   * container it opens.
   *
   * They are assigned from here rather than where the elements are built (`app-composer.js`) for the same
   * reason the fold lives here: this file owns every cell of the strip, and an id that only one file uses is
   * an id that file should be the one to write. Assigning is idempotent — it is the same string every time.
   */
  var READOUT_ID = 'cc-strip-readout';
  var BARS_ID = 'cc-strip-bars';
  var MORE_ID = 'cc-strip-more';

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
   * Builds the standing-facts block once AND puts the whole strip in its order. Null until the composer
   * exists.
   *
   * THE ORDER IS THE DOM'S, NOT `order:`. Top to bottom the strip reads: the plan-limit bars with each
   * window's countdown under it, then the standing facts, then the status line closest to the prompt box.
   * `app-composer.js` appends `.readout` and `.usage-bars` in the opposite order, so two of the three are
   * MOVED here rather than painted elsewhere: a visual order that disagrees with the DOM is a tab order that
   * disagrees with the screen (WCAG 2.2 SC 2.4.3), and `order:`/`flex-direction: column-reverse` buy the
   * look and leave the sequence behind. The same rule is written out in app-composer-settings.js.
   *
   * ROWS FOUR AND FIVE MOVE AS ONE and are not touched here at all: a countdown lives INSIDE its own
   * `.ub-item`, so `.usage-bars` carries every bar and every reset with it, each still under the window it
   * belongs to. Ordering them as two rows would be the one way to get a reset under somebody else's bar.
   *
   * `insertBefore` on a node already in the tree is a remove plus an insert, which this repository has a
   * rule about — it blurs a focused input inside the subtree and resets a scroll offset. Neither applies:
   * this runs ONCE, from the guard above, before anything in these rows can be focused, and the rows hold
   * no focusable element and no scroll container. `moveBefore()` would be the tool if that stopped being
   * true.
   *
   * NO disclosure control on this block. It had one — a *More info* button — and the button was there
   * because the block behind it was the Session panel's six cards, which is a panel and not a strip. What is
   * left is a handful of short facts; a control to reveal them costs a click, a state to remember and a
   * second thing to keep accessible, and buys nothing. (The strip's own `Show more` is a different control
   * with a different job — see the fold below.)
   *
   * NO scroll box either. There was a `.dash-mini-body` with `max-height` and `overflow-y: auto` around the
   * grid, inherited from the six-card version — and an element with `overflow` cannot be `display: contents`,
   * i.e. it cannot let its rows sit on the strip's own columns, which is the whole point now. It is safe to
   * drop rather than merely inconvenient: what it guarded against was a block that grows, and this one
   * cannot — it is two lines of at most four facts, so its height is bounded by construction instead of by
   * a cap.
   */
  function ensureMini() {
    if (mini) return mini;
    var els = CX.els;
    var readout = els && els.readout;
    var bars = els && els.usageBars;
    if (!readout || !bars || !readout.parentNode) return null;

    var grid = h('div', { class: 'dash-mini-grid', attrs: { id: MINI_ID } });
    var root = h('div', { class: 'dash-mini', attrs: { hidden: 'hidden' } }, grid);

    // Bars (and their resets, riding inside them) → the facts → the status line.
    var parent = readout.parentNode;
    parent.insertBefore(bars, readout);
    parent.insertBefore(root, readout);
    mini = { root: root, grid: grid };
    return mini;
  }

  /**
   * Shows the block once there is a session to describe, and redraws it on every push.
   *
   * The fold is resynced from HERE and from nowhere else, because this is the one function both push paths
   * reach: a state push arrives through `renderReadout`, which ends by calling this, and a session push
   * arrives through the `cc.session` tap above, which calls it directly. The early return is the only case
   * with nothing to sync — there is no composer yet, so there are no cells and no button.
   */
  function renderMini() {
    var m = ensureMini();
    if (!m) return;
    if (!sessionPayload()) {
      // Nothing to say yet. Hidden outright rather than left as an empty frame: an empty box reads as a
      // failure to load, which is the one thing it is not.
      m.root.setAttribute('hidden', 'hidden');
      clearMini();
    } else {
      m.root.removeAttribute('hidden');
      drawMini();
    }
    syncFold();
  }

  function clearMini() {
    if (!mini) return;
    while (mini.grid.firstChild) mini.grid.removeChild(mini.grid.firstChild);
  }

  /** One `label: value` pair. An absent value draws nothing at all — a dash is not information. */
  function fact(label, value) {
    if (value == null || value === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell', title: label + ': ' + value },
      h('span', { class: 'mini-key', text: label + ':' }),
      h('span', { class: 'mini-val', text: String(value) })
    );
  }

  /**
   * [path] written against the user's home as `~`, or [path] unchanged when it does not live there.
   *
   * PRESENTATION, NEVER THE DATUM. The pair's `title` keeps the absolute path in full, because `~` is a
   * convention and a path someone copies has to be one a shell in any state will resolve.
   *
   * THE HOME HAS TO BE GIVEN, and the whole rule is that prefix and nothing else. A regex over `/home/<x>`
   * would shorten ANOTHER user's home to `~`, which does not read as "shortened" — it reads as a different
   * directory, and the one place this string appears is the answer to "where is this session working". The
   * match is therefore a real prefix ending ON A SEGMENT BOUNDARY: `/home/dev` is not the home of
   * `/home/developer/x`, and the boundary test is what says so. Separators are normalised for the COMPARISON
   * only and the remainder keeps the ones it came with, so a Windows path stays a Windows path.
   *
   * With no home given it returns the path untouched — the fail-safe direction, because the cost of not
   * abbreviating is a longer string and the cost of abbreviating wrongly is a false one.
   */
  function abbreviateHome(path, home) {
    if (!path || !home) return path;
    var root = String(home).replace(/\\/g, '/').replace(/\/+$/, '');
    if (!root || String(path).replace(/\\/g, '/').slice(0, root.length) !== root) return path;
    var rest = String(path).slice(root.length);
    if (rest === '') return '~';
    return /^[/\\]/.test(rest) ? '~' + rest : path;
  }

  /**
   * The working directory — the one fact that is a filesystem path, and the only one that is special.
   *
   * Three specialities, and they are all the same problem: a path is the longest thing on this strip and the
   * only value with no natural break in it. It is SHOWN against `~` (above); it keeps the absolute path in
   * its `title`; and it carries `mini-fill`, which lets it use the columns standing empty to its right
   * instead of clipping while there is room beside it.
   *
   * A BUILDER OF ITS OWN, RATHER THAN A FLAG ON `fact`. Two reasons, and the second is mechanical.
   * Declaring the placement at the call site is the point — the line's own class is decided by the JS and
   * not by `kept.length`, precisely so a line cannot change width in silence, and "this fact may use the
   * free columns" is the same kind of decision and gets stated the same way. And `css-contract.test.js`
   * finds the classes this page emits by scanning for `class: '…'` STRING LITERALS: `class: fill ? a : b`
   * matches nothing, so a flag on `fact` would take `mini-fill` out of the gate that exists to catch a class
   * with no rule behind it.
   */
  function workingDirFact(cwd, home) {
    if (cwd == null || cwd === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell mini-fill', title: 'Working dir: ' + cwd },
      h('span', { class: 'mini-key', text: 'Working dir:' }),
      h('span', { class: 'mini-val', text: abbreviateHome(String(cwd), home) })
    );
  }

  /**
   * The organization, or null when it is only the account name again.
   *
   * The criterion is "do not print, a centimetre apart, what the pair beside it already says" — NOT "filter
   * out this one string". A personal account has no organization anyone chose, so the provider generates one
   * out of the account itself; the row then reads as the email twice, on exactly the accounts with least to
   * say. A team's organization is a name somebody picked, and there it is the only place that name appears,
   * so it stays.
   *
   * The test is therefore structural and normalised (trimmed, case-insensitive): the value must START with
   * the email, and what follows must be nothing but a possessive tail — punctuation, an optional `s`, and at
   * most the generic word for organization. Anything longer is a name someone wrote (`dev@acme.com Platform
   * Team`) and is kept. The suffix belongs to the provider and can change its wording, so the rule
   * deliberately errs towards SHOWING a value it does not recognise: hiding a real organization loses a
   * fact, while failing to hide a generated one costs a line that reads slightly long.
   */
  function organizationWorthShowing(org, email) {
    if (!org || !email) return org;
    var value = String(org).trim();
    var owner = String(email).trim();
    if (value.slice(0, owner.length).toLowerCase() !== owner.toLowerCase()) return org;
    // Leading punctuation is dropped by class rather than matched by shape, which is what keeps the straight
    // and the curly apostrophe — and whatever else a provider puts there — from each needing their own
    // literal in a regex, where the two are indistinguishable to a reader reviewing the diff.
    var tail = value
      .slice(owner.length)
      .trim()
      .replace(/^[^a-z0-9]+/i, '');
    return /^s?\s*(org|organi[sz]ations?)?$/i.test(tail) ? null : org;
  }

  /**
   * A line of the block: the facts that are present, one per column. Nothing when they all are absent.
   *
   * Every line is the same shape, because the strip is ONE four-column grid (`--strip-cols`, declared on
   * `#composer`) and a line places its facts on it a cell at a time. A line with fewer facts than columns
   * leaves the remainder EMPTY rather than widening its cells: a short row that stretches to fill is a row
   * with a column layout of its own, and several of those side by side is precisely what made the strip
   * unreadable. Nothing here decides a width, so there is nothing to pass in.
   */
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
   * So: `label: value` pairs in the readout's own type — the same grammar as the line above it. The numbers
   * that MOVE (tokens, cost, quota, context) stay up there where they are watched; these are the standing
   * facts, which are looked up. The two lines share ONE grid so their pairs align vertically (see
   * `.dash-mini-grid` in dashboard.css for what that replaced and why); the only thing decided here is which
   * line is which, through the class each carries.
   */
  function drawMini() {
    var m = mini;
    if (!m) return;
    var s = sessionPayload() || {};
    // NB no scroll offset to carry across the rebuild any more: the block is no longer its own scroll
    // container (see `ensureMini`), so there is no position for a teardown to lose.
    clearMini();

    var account = s.account || {};
    var lines = [
      // `s.home` is the user's home as the HOST reports it, and there is deliberately no fallback: the page
      // cannot know whose home a `/home/<name>` is, and guessing turns a shortened path into a wrong one.
      factLine([fact('Model', s.model), workingDirFact(s.cwd, s.home)]),
      factLine([
        fact('Account', account.email),
        // Dropped when it is only the account name again (see `organizationWorthShowing`) — a decision about
        // what to SHOW, not about the payload, which still carries it. The line keeps its class and its one
        // pair per column when that happens: the column left free is deliberate, because re-flowing three
        // pairs to fill the width would move `Plan` and `Provider` off the gridlines they share with the
        // line above, which is the misalignment this grid exists to remove.
        fact('Organization', organizationWorthShowing(account.org, account.email)),
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
  }

  // ---- the fold: two columns, one button ------------------------------------
  //
  // FOUR COLUMNS ALWAYS, and when the strip is too narrow to hold four, columns three and four are hidden
  // behind a `Show more` that reveals them in place. Nothing reorders itself and nothing is dropped: what a
  // reader learned about where a value sits stays true at every width.
  //
  // THE UNIT OF HIDING IS THE COLUMN, ACROSS ALL FIVE ROWS. Hiding cell 3 hides the reasoning counter, the
  // `Plan` fact, the third plan window's bar and that window's countdown, together, because they are the
  // third cell of their respective rows. That is the whole reason the mechanism is one class on one kind of
  // cell (`strip-cell`) and a `:nth-child` rule in `composer.css`: anything that decided cell by cell would
  // eventually hide a bar and keep its reset, and a countdown under somebody else's bar is a wrong answer
  // rather than a missing one.
  //
  // THIS IS NOT THE `More info` DISCLOSURE THAT WAS REMOVED FROM THIS STRIP, and the difference is the only
  // thing that makes it worth having. That one hid facts that FIT: a click charged for nothing, a state to
  // remember and a second thing to keep accessible. This one appears only when information is genuinely cut
  // off — so it never costs a click that buys nothing, and its presence is itself the signal that there is
  // something you are not being shown. Removing it "because we removed that once" would restore the fold's
  // absence, not the earlier decision.

  /** Open or shut. A module variable, deliberately — see [syncFold]. */
  var expanded = false;

  /** The disclosure, once there is something for it to disclose; null the rest of the time. */
  var moreBtn = null;

  /** Whether [host] is on screen and has a cell that the fold would hide, i.e. a third one. */
  function foldableRow(host, selector) {
    return !!host && !host.hasAttribute('hidden') && host.querySelectorAll(selector).length > 2;
  }

  /**
   * Is there anything behind the button at all?
   *
   * Asked of the rows that are actually on screen, because a row carrying the `hidden` attribute has cells
   * in the DOM and nothing to show — counting those would put up a button that reveals a row the user cannot
   * see either way.
   *
   * The facts are counted PER LINE and not per block: they are two lines sharing one grid, so `Plan` is the
   * third cell of the account line, not the fifth cell of the block, and it is the line that decides which
   * column a cell lands in.
   */
  function anythingToFold() {
    var els = CX.els;
    if (!els) return false;
    if (foldableRow(els.readout, '.ro-item')) return true;
    if (foldableRow(els.usageBars, '.ub-item')) return true;
    if (!mini || mini.root.hasAttribute('hidden')) return false;
    var lines = mini.grid.querySelectorAll('.mini-line');
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].querySelectorAll('.mini-fact').length > 2) return true;
    }
    return false;
  }

  /**
   * Builds the disclosure, once, as the LAST row of the strip.
   *
   * A disclosure normally sits before the content it reveals, and this one cannot: it controls three
   * separate containers interleaved with each other, so no position precedes them all. `aria-controls`
   * carries that relationship instead — which is what the attribute is for when adjacency is impossible —
   * and the cells it reveals appear beside the cells they belong with, which is worth more to a reader than
   * being adjacent to the button that revealed them.
   *
   * It is anchored to `.readout` because the readout is the LAST row of the strip (see `ensureMini`), not
   * because it has anything to do with the status line. Anchoring it to whichever row happens to be last is
   * the thing to keep true if the order changes again — pinned to `.usage-bars`, which used to be last, it
   * would now sit in the middle of the strip.
   */
  function buildMoreBtn() {
    var els = CX.els;
    var after = els && els.readout;
    if (!after || !after.parentNode) return null;
    var btn = h('button', {
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

  /**
   * Reapplies the whole fold — the class, the button's existence, its label and its state — on every push.
   *
   * OPEN OR SHUT IS NOT RECORDED ON ANY CELL, which is what makes it survive the host's pushes. Those arrive
   * several times a turn and each one throws away and rebuilds every cell in the strip; a state written onto
   * the cells would be destroyed by exactly the redraw it has to outlive. It lives in one module variable
   * and in one class on `#composer`, an element nothing here ever rebuilds — the same shape as `viewState`
   * in `app-core-diagram.js`, and for the same reason. A cell built a moment ago is folded or not purely by
   * WHERE IT SITS, because the hiding is a `:nth-child` rule, so a rebuild cannot desynchronise it.
   *
   * THE BUTTON IS BUILT ONLY WHEN A ROW ACTUALLY HAS A THIRD CELL, and that is the half a media query cannot
   * do. The stylesheet answers "is the strip narrow"; this answers "is there anything behind the button".
   * Only both together make its presence mean what it looks like it means, and neither alone is enough — a
   * `Show more` over nothing is worse than no button at all, because the one thing it communicates
   * reliably is that information is being withheld.
   */
  function syncFold() {
    var els = CX.els;
    if (!els || !els.readout) return;
    els.readout.id = READOUT_ID;
    if (els.usageBars) els.usageBars.id = BARS_ID;

    var host = CC.els && CC.els.composer;
    if (host) host.classList.toggle('strip-open', expanded);

    if (!anythingToFold()) {
      // Removed, not disabled and not left as a reserved gap: with everything on screen there is nothing for
      // it to do, and a control that is present but inert is a question the reader has to answer.
      if (moreBtn && moreBtn.parentNode) moreBtn.parentNode.removeChild(moreBtn);
      moreBtn = null;
      return;
    }
    if (!moreBtn) moreBtn = buildMoreBtn();
    if (!moreBtn) return;
    moreBtn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    // The label carries the state as well as `aria-expanded` does, because a control whose state is only
    // programmatic is a control only some readers can see the state of (WCAG 1.4.1 — and colour is not even
    // being used here, so text is all there is).
    moreBtn.textContent = expanded ? 'Show less' : 'Show more';
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
