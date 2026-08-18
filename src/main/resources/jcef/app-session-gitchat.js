/* app-session-gitchat.js — the Git conversation's TRANSCRIPT, embedded in the Git view.
 *
 * One subject: painting a SECOND session's rows inside this browser, in the view that acts on the repository.
 *
 * **It is the transcript and nothing else.** It had a text box and a permission region of its own for about
 * an hour, and that was the wrong reading of "embed the chat": the page already HAS a composer and already
 * has a place where request cards appear, both of them shared by every destination, and a second set of both
 * is a second thing to style, to keep in sync and to get subtly wrong — which is exactly how it looked. What
 * makes this a chat is that the ONE composer sends here while this pane is up (`scope: 'git'`, the same
 * mechanism a card's answer already uses) and the ONE permission region shows its cards.
 *
 * The Git chat used to be an ordinary tab. Being one put it in the row with the user's own conversations,
 * made its startup paint the full-window "Loading Claude Code" screen over whatever chat they were in, and
 * left the Git view able to do no more than send them somewhere else — press *Commit with Claude* and the
 * turn happened in a tab that was not on screen. It is a conversation ABOUT the repository, so it belongs in
 * the view of the repository, with its permission cards, because every turn in it runs with forced approval
 * (`ClaudeSession.gitIntegration`): a pane that could show the conversation but not the card is a pane you
 * cannot finish anything from.
 *
 * WHAT THIS FILE DOES NOT DO, on purpose: it does not render a row and it does not render a card. Rows come
 * from `CC.transcript.createRow`/`updateRow` and cards from `CC.permissions.render` — the same builders the
 * main transcript and the dock use, given a container and a registry of their own. A second renderer would
 * be a second place for a diff or a command to be displayed wrongly, which for a card that gates `git push`
 * is not a cosmetic risk. What is genuinely this file's is PLACEMENT and LIFECYCLE: where the rows go, which
 * session answers a card, and what the pane says while the binary is still coming up.
 *
 * The pane is a persistent node (`app-session.js` hides it rather than rebuilding it): it holds a text box
 * with a caret and cards with half-filled fields, and the host pushes several times a turn.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------
  /** The pane, built once on first use. */
  var pane = null;
  var rowsEl = null;
  var statusEl = null;

  /** id → row record, and toolUseId → card element. This conversation's own, never the main transcript's. */
  var rows = new Map();
  var cards = new Map();

  /** The last payload the host pushed, drawn when the pane is next on screen. */
  var last = null;
  /** Whether [last] has been drawn — the pane is hidden most of the time and drawing it then is pure waste. */
  var drawn = false;

  function tx() {
    return CC.transcript || null;
  }

  // ---------------------------------------------------------------------------
  // The pane
  // ---------------------------------------------------------------------------
  /**
   * Builds the pane on first ask and returns it thereafter.
   *
   * Lazily, and that is not an optimisation: `app-session.js` calls this on every visibility pass, including
   * in every browser that never opens the Git view at all.
   */
  function gitChatPane() {
    if (pane) return pane;
    if (typeof h !== 'function') return null;

    rowsEl = h('div', {
      class: 'gitchat-rows',
      // A log rather than a live region: the rows stream in continuously and announcing each delta would
      // talk over everything else. What IS announced is the thing that blocks the user — the permission
      // card, by `app-permissions.js` — and the starting/idle line below.
      attrs: { role: 'log', 'aria-label': 'Git conversation', 'aria-live': 'off' },
    });
    statusEl = h('div', { class: 'gitchat-status', attrs: { role: 'status', 'aria-live': 'polite' } });

    pane = h(
      'div',
      { class: 'gitchat', attrs: { hidden: '' } },
      // The same strip the Overview heads itself with, lit on Chat — one builder, so the two panes cannot
      // disagree about which destinations exist.
      typeof D.gitViewTabs === 'function' ? D.gitViewTabs('chat') : null,
      rowsEl,
      statusEl
    );
    return pane;
  }

  /**
   * Whether the composer is talking to the Git conversation right now.
   *
   * The ONE composer serves whichever destination is on screen — that is what makes this an embedded chat
   * rather than a second chat UI — so it asks here, and tags its turn with `scope: 'git'` when the answer is
   * yes. The same tag a request card's answer already carries, routed by the same line host-side.
   */
  CC.gitChatActive = function () {
    return (
      typeof D.gitSubView === 'function' &&
      D.gitSubView() === 'chat' &&
      typeof D.dashboardShown === 'function' &&
      D.dashboardShown()
    );
  };

  // ---------------------------------------------------------------------------
  // Painting
  // ---------------------------------------------------------------------------
  /**
   * Draws [last] into the pane.
   *
   * Called only while the pane is on screen. The host pushes on every streamed delta of a turn that is
   * running whether or not anyone is looking at it, and the Git view is not where most sessions spend their
   * time — so a hidden pane stashes and draws on show, the same discipline the dashboard itself keeps.
   */
  function draw() {
    if (!pane) return;
    drawn = true;
    var payload = last;

    if (!payload) {
      clearRows();
      setStatus('');
      return;
    }

    renderRows(Array.isArray(payload.rows) ? payload.rows : []);

    // The one lifecycle this pane shows. With no tab there is no boot screen — which is the point, the
    // "Loading Claude Code" panel used to cover the whole window for a chat nobody had opened — so the wait
    // is stated here, where the press that caused it happened.
    setStatus(payload.starting ? 'Starting Claude for this repository…' : '');
  }

  function setStatus(text) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.hidden = !text;
  }

  function clearRows() {
    rows.clear();
    cards.clear();
    if (!rowsEl) return;
    while (rowsEl.firstChild) rowsEl.removeChild(rowsEl.firstChild);
  }

  /**
   * Upserts every entry, then places the rows in the payload's order.
   *
   * FLAT: no nesting into an Agent card, unlike the main transcript. This conversation runs `git` commands
   * under forced approval and does not spawn agents; a nesting pass would be a container lookup that never
   * finds anything, on every delta.
   */
  function renderRows(entries) {
    var T = tx();
    if (!rowsEl || !T || typeof T.createRow !== 'function') return;

    var stick = nearBottom();
    var ordered = [];
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      if (!entry || entry.id == null) continue;

      // A tool result belongs INSIDE the card that produced it, and only becomes a row of its own when
      // there is no such card — a result whose card was trimmed away, say.
      if (entry.speaker === 'TOOL_OUTPUT' && T.routeToolOutput(entry, cards)) continue;

      var rec = rows.get(entry.id);
      if (rec && rec.speaker !== entry.speaker) {
        if (rec.el && rec.el.parentNode) rec.el.parentNode.removeChild(rec.el);
        rows.delete(entry.id);
        rec = null;
      }
      if (!rec) {
        rec = T.createRow(entry, cards);
        rows.set(entry.id, rec);
      }
      // `false`: jump-to-code resolution answers by looking the row up in the MAIN transcript's registry,
      // which these rows are not in, so every settled row would cost a request whose reply lands nowhere.
      T.updateRow(rec, entry, false);
      if (rec.el) ordered.push(rec.el);
    }

    place(ordered);
    if (stick) rowsEl.scrollTop = rowsEl.scrollHeight;
  }

  /**
   * Puts the rows in order, TOUCHING NOTHING already in place.
   *
   * `appendChild` on a node that is already where it belongs is not a no-op: the DOM has no move, so it is a
   * removal followed by an insertion, and the subtree comes back with its scroll at zero and its focus gone.
   * On a pane the host repaints several times a turn that is the difference between reading a diff and
   * being thrown back to its first line.
   */
  function place(ordered) {
    for (var i = 0; i < ordered.length; i++) {
      if (rowsEl.children[i] !== ordered[i]) {
        rowsEl.insertBefore(ordered[i], rowsEl.children[i] || null);
      }
    }
    // Anything past the payload's length is a row the host no longer sends.
    while (rowsEl.children.length > ordered.length) {
      rowsEl.removeChild(rowsEl.lastChild);
    }
  }

  var NEAR_BOTTOM = 60;
  function nearBottom() {
    if (!rowsEl) return true;
    return rowsEl.scrollHeight - rowsEl.scrollTop - rowsEl.clientHeight <= NEAR_BOTTOM;
  }

  // ---------------------------------------------------------------------------
  // Wiring
  // ---------------------------------------------------------------------------
  D.gitChatPane = gitChatPane;

  /** The panel has just put the pane on screen: draw whatever arrived while it was hidden. */
  D.gitChatShown = function () {
    if (!drawn) draw();
  };

  /**
   * Host: the Git conversation changed — `{running, starting, rows}`, or null when there is not one yet.
   *
   * **`rows` is always the WHOLE conversation, never a delta, and it reaches every open chat's browser** —
   * not only the one whose user typed. `renderRows`/`place` rely on the first half (anything past the
   * payload's length is dropped, so a delta would delete the conversation) and the user relies on the second:
   * there is one Git conversation per project and it has to look the same from whichever chat you open the
   * view in. It used to arrive only in a page that had already acted on the chat, which left every other page
   * drawing an empty pane over a conversation that was already running.
   *
   * No `permissions` here: its cards go to the page's ONE permission region with the rest, tagged with the
   * conversation they belong to. See the file header.
   */
  window.cc = window.cc || {};
  window.cc.gitChat = function (payload) {
    last = payload && typeof payload === 'object' ? payload : null;
    drawn = false;
    var open = typeof D.gitSubView === 'function' && D.gitSubView() === 'chat';
    var shown = typeof D.dashboardShown === 'function' && D.dashboardShown();
    if (pane && open && shown) draw();
  };
})();
