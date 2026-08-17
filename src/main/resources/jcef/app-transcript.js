/* app-transcript.js — the transcript itself: the row registry and the pass that places each row.
 *
 * Implements cc.batch(entries) and cc.clear() plus the search hook per §TRANSCRIPT.
 * Relies ONLY on globals created by app-core.js (window.CC): h, escape, markdown,
 * on/emit, els, send. Vanilla ES2019, addEventListener only, null-safe.
 *
 * This file owns the transcript's SPINE: the row registry, the upsert/reposition pass and the two
 * Kotlin-facing methods. Four companions own one subject each and hang off the shared `CC.transcript`
 * namespace, which is created here and must therefore load first — `app-transcript-rows.js` (the
 * per-speaker builders), `app-transcript-tools.js` (tool cards and their output), `app-transcript-links.js`
 * (jump-to-code) and `app-transcript-find.js` (search and the find bar).
 */
(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  // The transcript's own namespace: what the spine shares with its four companions, and what they hand back.
  // There is no module system here (each file is a hash-pinned <script>), so this object IS the interface.
  var TX = (CC.transcript = CC.transcript || {});

  // ---- shared helpers (degrade gracefully if core not yet ready) ----------
  function CCobj() {
    return window.CC || {};
  }
  function el(tag, props) {
    var C = CCobj();
    if (C.h) {
      return C.h(tag, props);
    }
    // minimal fallback hyperscript
    var node = document.createElement(tag);
    props = props || {};
    if (props.class) {
      node.className = props.class;
    }
    if (props.text != null) {
      node.textContent = String(props.text);
    }
    if (props.html != null) {
      node.innerHTML = props.html;
    }
    if (props.title != null) {
      node.title = String(props.title);
    }
    if (props.attrs) {
      for (var a in props.attrs) {
        if (Object.prototype.hasOwnProperty.call(props.attrs, a)) {
          node.setAttribute(a, props.attrs[a]);
        }
      }
    }
    if (props.on) {
      for (var ev in props.on) {
        if (Object.prototype.hasOwnProperty.call(props.on, ev)) {
          node.addEventListener(ev, props.on[ev]);
        }
      }
    }
    return node;
  }
  function md(text) {
    var C = CCobj();
    if (C.markdown) {
      try {
        return C.markdown(text == null ? '' : text);
      } catch (e) {
        /* fall through */
      }
    }
    return esc(text);
  }
  function esc(text) {
    var C = CCobj();
    if (C.escape) {
      return C.escape(text == null ? '' : text);
    }
    var d = document.createElement('div');
    d.textContent = text == null ? '' : String(text);
    return d.innerHTML;
  }
  function safeSend(obj) {
    var C = CCobj();
    if (C.send) {
      try {
        C.send(obj);
      } catch (e) {
        /* ignore */
      }
    }
  }
  function conversationEl() {
    var C = CCobj();
    var node = (C.els && C.els.conversation) || document.getElementById('conversation');
    return node || null;
  }
  function emptyEl() {
    return document.getElementById('empty');
  }

  // ---- state --------------------------------------------------------------
  // The host's memory cap on transcript rows, mirrored here so the notice can state the number the model
  // actually enforces instead of a second number that drifts from it. Pinned to `TranscriptModel.MAX_ENTRIES`
  // by TranscriptCapContractTest — the two constants cannot diverge without the build going red.
  var MAX_ENTRIES = 2000;

  // id -> { el, speaker, bodyNode, kind, text, meta, state, toolUseId }
  var rows = new Map();
  // toolUseId -> tool card element (the .tool node)
  var toolCards = new Map();

  TX.el = el;
  TX.safeSend = safeSend;
  TX.conversationEl = conversationEl;
  TX.rows = rows;
  TX.toolCards = toolCards;
  // The row engine, reusable by anything that paints a conversation into a container of its own. Assigned
  // after the two functions are declared (below), not here.

  // ---- autoscroll / follow -----------------------------------------------
  var followFlag = false;
  var NEAR_BOTTOM = 80;
  var scrollScheduled = false;

  // Above this distance, jump instead of gliding. A smooth scroll is a fixed-duration animation regardless of
  // distance, so over a long one it becomes a crawl the user simply waits through — the opposite of smooth.
  var SMOOTH_SCROLL_MAX_PX = 400;

  function isNearBottom() {
    var c = conversationEl();
    if (!c) {
      return true;
    }
    var distance = c.scrollHeight - c.scrollTop - c.clientHeight;
    return distance <= NEAR_BOTTOM;
  }
  function scheduleScroll(stick) {
    if (!stick) {
      return;
    }
    if (scrollScheduled) {
      return;
    }
    scrollScheduled = true;
    var raf =
      window.requestAnimationFrame ||
      function (fn) {
        return setTimeout(fn, 16);
      };
    raf(function () {
      scrollScheduled = false;
      var c = conversationEl();
      if (!c) {
        return;
      }
      // A new row takes its full height the instant it is inserted, so the content below it jumps down by that
      // height while the row itself is still fading in — the layout shift and the animation are two separate
      // motions, and the mismatch is what reads as "brusque". Scrolling smoothly puts the two on the same
      // timeline: the space opens at roughly the pace the row appears.
      //
      // Only for a SMALL step, and never mid-stream: a smooth scroll across a long distance (jumping to the
      // bottom of a restored transcript) is a slow crawl the user has to wait out, and re-issuing one every
      // streaming delta cancels and restarts it constantly, which stalls the scroll entirely.
      //
      // BOTH cases name their behaviour, and the instant one is the reason: `#conversation` carries
      // `scroll-behavior: smooth` in the stylesheet, and neither `element.scrollTop = …` nor a `behavior:'auto'`
      // scroll overrides that — both take the computed value of the property, so the case this code routes AWAY
      // from smooth would animate anyway, reduced motion included. `instant` is the value that overrides the
      // property. The `scrollTo` capability test is not decoration: jsdom has no scroll methods on Element, so
      // the assignment below is what the test harness runs.
      var distance = c.scrollHeight - c.scrollTop - c.clientHeight;
      var smooth = distance > 0 && distance < SMOOTH_SCROLL_MAX_PX && !CC.reducedMotion;
      if (typeof c.scrollTo === 'function') {
        c.scrollTo({ top: c.scrollHeight, behavior: smooth ? 'smooth' : 'instant' });
      } else {
        c.scrollTop = c.scrollHeight;
      }
    });
  }

  // ---- body rendering -----------------------------------------------------
  function setBody(rec, text) {
    var body = rec.bodyNode;
    if (!body) {
      return;
    }
    var kind = rec.kind;
    if (kind === 'md') {
      body.innerHTML = md(text);
      body.__rawText = text == null ? '' : String(text);
    } else if (kind === 'pre') {
      body.textContent = text == null ? '' : String(text);
    } else {
      // text / tool name
      body.textContent = text == null ? '' : String(text);
      body.__rawText = text == null ? '' : String(text);
    }
  }

  // ---- upsert -------------------------------------------------------------

  /**
   * Builds the DOM for one entry — the ONLY place a transcript row is made.
   *
   * [cards] is the tool-card registry the row registers itself in, and it is a parameter rather than this
   * module's map for one reason: the Git view embeds a SECOND conversation (`app-session-gitchat.js`) whose
   * rows must be built by this function and tracked by a registry of its own. Two sessions' `tool_use_id`s
   * share no namespace, so one map would let a Bash output from the Git chat land in the main transcript's
   * card. Everything else about a row — its markup, its icon, its command block — is identical, which is
   * exactly why this is a parameter and not a second copy of the builder.
   */
  function createRow(entry, cards) {
    if (!cards) {
      cards = toolCards;
    }
    var rec = TX.builderFor(entry.speaker, entry);
    rec.speaker = entry.speaker;
    rec.toolUseId = entry.toolUseId || null;
    if (entry.speaker === 'TOOL' && entry.toolUseId) {
      rec.outNode = rec.el.__outNode || rec.el.querySelector('.tool-out');
      rec.el.__toolUseId = entry.toolUseId;
      cards.set(entry.toolUseId, rec.el);
      // A tool card starts collapsed and toggles on click. An Agent/Task card is the exception: since 5.5.0
      // the agent's work lives in its own tab, so the card LINKS there instead of expanding onto nothing.
      if (entry.meta === 'Task' || entry.meta === 'Agent') {
        rec.el.__isAgentCard = true;
        rec.el.classList.add('agent-link');
      }
      // The host can ask for a card to start OPEN. A background task's view is nothing but its command and
      // its output, so shipping it collapsed hides the one thing the tab was opened for.
      if (entry.open) {
        rec.el.classList.add('open');
      }
    }
    if (entry.speaker === 'TOOL') {
      var icNode = rec.el.querySelector('.ic');
      if (icNode) {
        icNode.innerHTML = TX.toolIconSvg(entry.meta);
      }
      // The raw command text (Bash/PowerShell/MCP…) is fixed for the life of this row — render it once, as its
      // own copyable code block RIGHT UNDER THE HEADER (.tool-cmd, a sibling of .tool-out — not gated by the
      // collapse toggle), so you see what ran without expanding the card. `cmd-tool` gives the whole card its
      // own look (a distinct accent), and the header keeps just the tool name — no raw command text there.
      if (entry.command) {
        TX.renderCommandBlock(rec.el.__cmdNode, entry.command);
        rec.el.classList.add('cmd-tool');
      }
      // Fixed for the row's life — remembered so a LATER TOOL_OUTPUT (Read/Write/Edit file content, or a diff)
      // can pick the right hljs language from the file extension instead of guessing or staying unhighlighted.
      rec.el.__filePath = entry.filePath || null;
    }
    return rec;
  }

  /**
   * Refreshes an existing row from a newer entry. [links] false skips jump-to-code resolution.
   *
   * The Git view's embedded conversation passes false: link resolution is an async round-trip to the host
   * that answers by looking the row up in THIS module's registry, which the Git pane's rows are not in — so
   * every settled assistant row there would cost a request whose reply lands nowhere.
   */
  function updateRow(rec, entry, links) {
    // refresh body text (tool name for TOOL). A file tool renders its path as a jump-to-code link.
    if (rec.speaker === 'TOOL' && entry.title) {
      // A label the host worked out AFTER the row existed: an Agent card starts life saying "Agent" and
      // gains "Agent (Inventory of dependencies)" once the binary has written that agent's sidecar. `meta`
      // stays the tool's NAME — it is what the icon and the reviewable/command checks read.
      setBody(rec, entry.title);
    } else if (rec.speaker === 'TOOL' && entry.command) {
      // Command-executing tools show the command in their own code block (.tool-cmd, see createRow) — the
      // title just names the tool, it doesn't also cram the raw command text in there.
      setBody(rec, entry.meta || entry.text);
    } else if (rec.speaker === 'TOOL' && entry.filePath) {
      TX.renderToolLabel(rec.bodyNode, entry.text, entry.filePath);
    } else {
      setBody(rec, entry.text);
      // Model prose/code spans: ask the host to confirm which paths/symbols are real, then link those. Only for
      // settled assistant rows — doing it per streaming delta would spam the host and fight the re-render.
      if (links !== false && rec.speaker === 'ASSISTANT' && entry.state !== 'RUNNING') {
        TX.requestLinks(rec, entry);
      }
    }
    rec.text = entry.text;
    rec.meta = entry.meta;
    rec.state = entry.state;
    if (rec.speaker === 'TOOL') {
      TX.applyToolState(rec.el, entry.state, entry.meta);
      TX.applyToolElapsed(rec.el, entry.state, entry.elapsed);
      if (rec.el.__diffBtn) {
        rec.el.__diffBtn.hidden = !entry.reviewable;
      }
      if (rec.el.__restoreBtn) {
        rec.el.__restoreBtn.hidden = !entry.reviewable;
      }
    }
    if (rec.speaker === 'MEMORY' && rec.el.__label) {
      var title = entry.meta && String(entry.meta).trim() ? String(entry.meta) : '🧠 Recalled memories';
      rec.el.__label.textContent = title;
    }
  }

  TX.createRow = createRow;
  TX.updateRow = updateRow;

  function upsert(entry) {
    if (entry == null || entry.id == null) {
      return null;
    }

    // TOOL_OUTPUT: try routing into an existing tool card first.
    if (entry.speaker === 'TOOL_OUTPUT') {
      if (TX.routeToolOutput(entry)) {
        // no standalone row needed; if a previously-standalone row exists, leave it.
        return rows.get(entry.id) || null;
      }
    }

    var rec = rows.get(entry.id);
    if (rec && rec.speaker !== entry.speaker) {
      // speaker changed for same id (rare) — rebuild
      if (rec.el && rec.el.parentNode) {
        rec.el.parentNode.removeChild(rec.el);
      }
      if (rec.toolUseId) {
        toolCards.delete(rec.toolUseId);
      }
      rows.delete(entry.id);
      rec = null;
    }
    if (!rec) {
      rec = createRow(entry);
      rows.set(entry.id, rec);
    }
    updateRow(rec, entry);
    return rec;
  }

  // ---- reposition ---------------------------------------------------------
  // The container a row belongs in: a subagent row (one with a `parent` whose Agent/Task
  // card exists) nests inside that card's .tool-children box; everything else is top-level
  // in #conversation. Falls back to #conversation if the parent card isn't built yet.
  function containerFor(entry) {
    if (entry.parent) {
      var parentCard = toolCards.get(entry.parent);
      if (parentCard) {
        return parentCard.__childrenNode || parentCard.querySelector('.tool-children') || conversationEl();
      }
    }
    return conversationEl();
  }

  // Order is a GLOBAL flat index across the whole transcript, but rows are split across
  // containers once subagents nest. So we order WITHIN a container by comparing each
  // managed sibling's stored __order (set here), not by raw child index — which also makes
  // us robust to any non-managed nodes (#empty, tool heads) sharing a container.
  function reposition(entry) {
    var rec = rows.get(entry.id);
    if (!rec || !rec.el) {
      return;
    }
    var order = entry.order;
    rec.el.__order = typeof order === 'number' && order >= 0 ? order : null;
    var container = containerFor(entry);
    if (!container) {
      return;
    }

    var ref = null;
    if (rec.el.__order != null) {
      var kids = container.children;
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        if (k === rec.el) {
          continue;
        }
        if (k.__order == null) {
          continue;
        } // skip non-managed nodes
        if (k.__order > rec.el.__order) {
          ref = k;
          break;
        }
      }
    }
    // already correctly placed → no move (avoids needless reflow/scroll jumps)
    if (rec.el.parentNode === container && rec.el.nextSibling === ref) {
      return;
    }
    if (ref) {
      container.insertBefore(rec.el, ref);
    } else {
      container.appendChild(rec.el);
    }
  }

  function showEmptyState(show) {
    var empty = emptyEl();
    if (empty) {
      empty.hidden = !show;
    }
  }

  // ---- public: cc.batch ---------------------------------------------------
  cc.batch = function (entries) {
    if (!entries) {
      return;
    }
    if (!Array.isArray(entries)) {
      // tolerate a single entry or {entries:[...]}
      if (entries.entries && Array.isArray(entries.entries)) {
        entries = entries.entries;
      } else {
        entries = [entries];
      }
    }
    var c = conversationEl();
    var stick = followFlag || isNearBottom();

    // First pass: upsert all (so tool cards exist before outputs route).
    for (var i = 0; i < entries.length; i++) {
      upsert(entries[i]);
    }
    // Second pass: reposition rows that have DOM nodes.
    for (var j = 0; j < entries.length; j++) {
      var e = entries[j];
      if (e && e.id != null && e.speaker !== 'TOOL_OUTPUT') {
        reposition(e);
      } else if (e && e.id != null && e.speaker === 'TOOL_OUTPUT' && rows.has(e.id)) {
        // standalone tool-output row (no matching card)
        reposition(e);
      }
    }

    if (rows.size > 0 || (c && c.children.length > 0)) {
      showEmptyState(false);
    }

    // re-apply active search highlight to refreshed bodies
    TX.refreshSearch();

    scheduleScroll(stick);
  };

  // ---- public: cc.clear ---------------------------------------------------

  /**
   * Empties the transcript. Called on every switch between a chat, an agent and a task, and on every session
   * stop — so #conversation's children belong to THIS function, and anything put inside that element is
   * deleted the first time a transcript changes.
   *
   * That is not a caveat, it is the reason the waiting screens are siblings of #conversation and not rows of
   * it: as rows they were wiped by the first clear and every later render found no element and returned
   * silently (see boot.css). #empty survives because it is this function's own idle state, and it is the only
   * exception there should ever be — a second one is a symptom that something is being stored in the wrong
   * element.
   */
  cc.clear = function () {
    rows.clear();
    toolCards.clear();
    var c = conversationEl();
    if (c) {
      var kids = Array.prototype.slice.call(c.children);
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].id === 'empty') {
          continue;
        }
        c.removeChild(kids[i]);
      }
    }
    TX.resetSearch();
    showEmptyState(true);
  };

  // ---- public: cc.trimRows ------------------------------------------------

  /** Drops one row from the registry and from the DOM. Same teardown the speaker-changed rebuild does. */
  function dropRow(id) {
    var rec = rows.get(id);
    if (!rec) {
      return;
    }
    if (rec.el && rec.el.parentNode) {
      rec.el.parentNode.removeChild(rec.el);
    }
    if (rec.toolUseId) {
      toolCards.delete(rec.toolUseId);
    }
    rows.delete(id);
  }

  function trimNoticeText(total) {
    return (
      total +
      (total === 1 ? ' earlier row was' : ' earlier rows were') +
      ' dropped to keep the transcript at ' +
      MAX_ENTRIES +
      ' rows. Nothing was lost: the session file on disk still holds the whole conversation.'
    );
  }

  /**
   * ONE notice row, at the head of the transcript, saying how many rows the cap has dropped in total. It is
   * updated in place, never appended a second time — the cap trims on nearly every added row once the ceiling
   * is reached, so a row per trim would bury the transcript in its own bookkeeping.
   *
   * A total of 0 means no notice at all: the row is absent, not present and empty.
   */
  function renderTrimNotice(total) {
    var c = conversationEl();
    if (!c) {
      return;
    }
    var node = c.querySelector('.trim-notice');
    if (total <= 0) {
      if (node) {
        c.removeChild(node);
      }
      return;
    }
    var text = trimNoticeText(total);
    if (node) {
      node.textContent = text;
      return;
    }
    // First trim of this transcript: announce it once. The count then keeps climbing on the screen without
    // being re-announced — a live region rewritten per trimmed row talks over itself and gets switched off.
    // `__order` stays unset, which is what keeps the notice at the head: `reposition` only ever inserts a row
    // before a MANAGED sibling, so no row can ever be placed above it.
    c.insertBefore(el('div', { class: 'notice trim-notice', text: text }), c.firstChild);
    var C = CCobj();
    if (C.announce) {
      C.announce(text);
    }
  }

  /**
   * The host's transcript model dropped its oldest rows to stay under the cap: remove those nodes and state
   * the cumulative total (`{ids:[…], total:N}` — see ChatTranscriptView.trimNotice).
   *
   * An EMPTY id list is not a no-op and does not mean "remove everything": it is a pure notice refresh, sent
   * when a transcript is (re)attached, because `cc.clear()` took the notice with it and the resend that
   * follows only carries rows that still exist.
   */
  cc.trimRows = function (payload) {
    if (!payload) {
      return;
    }
    var ids = Array.isArray(payload.ids) ? payload.ids : [];
    for (var i = 0; i < ids.length; i++) {
      dropRow(ids[i]);
    }
    var total = typeof payload.total === 'number' ? payload.total : 0;
    renderTrimNotice(total);
  };

  // ---- subscribe to bus events -------------------------------------------
  function subscribe() {
    var C = CCobj();
    if (!C.on) {
      return false;
    }
    C.on('follow', function (b) {
      followFlag = !!b;
      if (followFlag) {
        scheduleScroll(true);
      }
    });
    C.on('search', function (q) {
      TX.runSearch(q, false);
      TX.updateFindCount();
    });
    return true;
  }

  if (!subscribe()) {
    // core may not be ready; retry shortly until CC.on exists.
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (subscribe() || tries > 50) {
        clearInterval(iv);
      }
    }, 20);
  }
})();
