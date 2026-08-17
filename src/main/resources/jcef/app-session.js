/* app-session.js — the dashboard panel, and which of its views you are looking at.
 * Implements cc.session(payload) and cc.mcp(payload).
 * A ".dash-toggle" button in the tab bar shows/hides a ".dashboard" panel that overlays
 * CC.els.conversation (the composer stays visible). Hidden by default.
 * Consumes app-core.js globals (window.CC: h, escape, send). Vanilla ES2019,
 * addEventListener only, no external resources, themeable via CSS classes only.
 *
 * This file owns the SHELL: the panel, the view switcher, what is rendered when, and the Kotlin-facing
 * methods — `cc.session` and `cc.mcp` outright, plus a wrapper over the composer's `cc.meta` for the one
 * field that decides which view a tab opens on. The cards themselves are four companions hanging off the
 * shared `CC.dash` namespace, which `app-session-base.js` creates and must therefore load first —
 * `app-session-cards.js` (plan limits, context, cost, account, session facts), `app-session-mcp.js` (the
 * servers), `app-session-workloads.js` (the diagram of everything that is running) and `app-session-git.js`
 * (the repository). This file loads LAST of the family: it builds the panel eagerly at the bottom, so every
 * builder it can reach for has to exist by then.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var core = D.core;
  var conversation = D.conversation;
  var appRoot = D.appRoot;

  // ---- Last payloads (stashed so cc.session/cc.mcp may fire before build) -----
  var lastSession = null;
  var lastMcp = null;

  // ---- DOM handles (created on build) ----------------------------------------
  var toggleBtn = null;
  // Held because they are shown and hidden as the session gains and loses a plan or a Git surface — see
  // `syncOptionalButtons`.
  var planBtn = null;
  var gitBtn = null;
  var panel = null;
  var shown = false;
  var built = false;

  /** The host said this tab exists to show Git (`cc.meta({gitIntegration:true})`). */
  var gitTab = false;
  /** …and the Git view has already been opened once, so a later push does not reopen a panel the user shut. */
  var gitOpened = false;

  // ---------------------------------------------------------------------------
  // Render the whole dashboard body from the stashed payloads.
  // ---------------------------------------------------------------------------
  /**
   * Renders only what is on screen.
   *
   * The host pushes the session payload on every state change, several times a turn, and this used to
   * rebuild the whole panel each time "to keep the DOM fresh while hidden" — rebuilding a diagram nobody
   * is looking at, laying out its cards and measuring its SVG. Opening the panel renders anyway (see
   * [toggle] and [cc.openDashboard]), so the work was pure waste; while hidden the payload is simply
   * stashed and drawn when it is next shown.
   */
  function renderIfShown() {
    // The Plan and Git buttons live in the TAB BAR, not in the panel, so they have to be kept current even
    // while the panel is closed — otherwise a plan written during a turn would go unannounced until you
    // happened to open the dashboard, which is precisely the discoverability problem they exist to fix.
    syncOptionalButtons();
    openGitTabOnce();
    if (built && shown) render();
  }

  /**
   * Shows the Plan and Git buttons when the session has one, and hides them when it does not.
   *
   * Hiding one while it is the OPEN view would leave the panel showing a view with no way back to it and no
   * lit button, so that case falls back to the default view — the plan vanishing is a real transition (the
   * session left plan mode), not an error, and it should read as one.
   */
  function syncOptionalButtons() {
    var s = lastSession;
    optionalButton(planBtn, 'plan', !!(s && s.plan && s.plan.body));
    optionalButton(gitBtn, 'git', !!(s && s.git && s.git.available));
  }

  function optionalButton(btn, view, has) {
    if (!btn) return;
    btn.hidden = !has;
    if (!has && currentView === view) {
      currentView = defaultView();
      markActiveButton();
    }
  }

  /**
   * Which view the panel falls back to: Git in a tab that exists to show Git, Session everywhere else.
   *
   * The same answer serves the first render and every return to the panel, so a Git tab cannot open on Git
   * and then land on Session the second time you look at it.
   */
  function defaultView() {
    return gitTab && lastSession && lastSession.git && lastSession.git.available ? 'git' : 'session';
  }

  /**
   * A Git tab opens ON the Git view, once.
   *
   * The host makes this tab to show the visualiser, so opening it on an empty chat would make the user press
   * a button to reach the only reason the tab exists. Once only: after that the panel is the user's, and a
   * routine payload push must not reopen what they closed.
   */
  function openGitTabOnce() {
    if (gitOpened || !gitTab || defaultView() !== 'git') return;
    ensureBuilt();
    if (!built) return; // no mount points yet; the next push tries again
    gitOpened = true;
    currentView = 'git';
    shown = true;
    // Only the visibility here: the caller renders immediately afterwards, and rendering twice for one push
    // is the exact waste `renderIfShown` exists to avoid.
    applyVisibility();
  }

  function render() {
    if (!panel) return;
    // The panel is its own scroll container and this rebuilds it from nothing, which drops the offset to
    // zero. The host pushes a payload several times a turn, so without carrying it across, reading anything
    // below the fold — a plan, the history list — meant being sent back to the top mid-sentence.
    var offset = panel.scrollTop;
    // Clear.
    while (panel.firstChild) panel.removeChild(panel.firstChild);

    // Cards live inside a centred .dash-inner grid (the grid/gap CSS targets `.dashboard > .dash-inner`; without
    // this wrapper the cards stacked with no layout). The wrapper also caps the width to the reading column.
    var inner = h('div', { class: 'dash-inner' });

    var s = lastSession || {};
    // EXCLUSIVE VIEWS, not one panel with as many scroll anchors. The anchor version was wrong in a way
    // that only shows up in use: with no agents there is no Agents card to scroll to, so pressing "Agents"
    // simply left the Session cards on screen — the button looked broken because it did nothing visible.
    //
    // One registry, looked up by view: adding a view is a line here and a line in the button stack,
    // and no branch anywhere else. The nested ternary this replaces was four levels deep and had the same
    // failure mode as any conditional chain — the next view would have been appended to the tail of it.
    var view = VIEWS[currentView] || VIEWS.session;
    var cards = view.cards(s);

    var any = false;
    for (var i = 0; i < cards.length; i++) {
      if (cards[i]) {
        inner.appendChild(cards[i]);
        any = true;
      }
    }

    if (!any) {
      inner.appendChild(
        h(
          'div',
          { class: 'dash-card dash-empty' },
          h('div', { class: 'dash-title', text: view.title }),
          h('div', { class: 'stat-row' }, h('span', { class: 'stat-label', text: view.empty }))
        )
      );
    }
    panel.appendChild(inner);
    // Restored after the content exists, or there is nothing to scroll and the browser clamps it to zero.
    panel.scrollTop = offset;
  }

  /**
   * The views, each declaring its own title, its cards and what it says when empty.
   *
   * A view that renders nothing must still say WHICH view is empty: "No session data yet" under the Agents
   * button is the same failure as showing the Session cards — the panel answering a question nobody asked.
   */
  var VIEWS = {
    session: {
      title: 'Session',
      empty: 'No session data yet.',
      cards: function (s) {
        return [
          D.buildUsageCard(s.usage),
          D.buildContextCard(s.context),
          D.buildCostCard(s.cost),
          D.buildAccountCard(s.account),
          D.buildEnvCard(s),
          D.buildMcpCard(lastMcp),
        ];
      },
    },
    // ONE view for everything that is running. It was three — Agents, Subagents, Background tasks — and they
    // were three views of one tree: to see whether an agent had spawned anything you switched view, lost the
    // parent, and had to read a breadcrumb to work out where you were. Here it is a single diagram, rooted at
    // the chats, and every node in it is somewhere you can go.
    workloads: {
      title: 'Workloads',
      empty: 'Nothing is running: no agents, no background tasks.',
      cards: function (s) {
        return [D.buildWorkloadsCard(s)];
      },
    },
    // Its own view rather than a card among the Session stats, and its button only exists while there IS a
    // plan (see `syncOptionalButtons`). A plan is prose you go back and re-read while working, not a number
    // you glance at — and buried in a grid of stat cards it was unfindable, which is exactly how it was
    // reported. The button appearing is also the notice that one has been written.
    plan: {
      title: 'Plan',
      empty: 'No plan for this session.',
      cards: function (s) {
        return [D.buildPlanCard(s.plan)];
      },
    },
    // The repository, what can be done to it, and what has happened in it. Like Plan, its button only exists
    // while the session HAS the surface (`git.available`) — a project with no Git support would otherwise get
    // a permanent button onto a view that can only say so.
    git: {
      title: 'Git',
      empty: 'No Git repository for this project.',
      cards: function (s) {
        return [D.buildGitHeadCard(s.git), D.buildGitActionsCard(s.git), D.buildGitHistoryCard(s.git)];
      },
    },
  };

  // ---------------------------------------------------------------------------
  /**
   * One of the view buttons.
   *
   * Each button owns a VIEW, and the rule is the one people expect from a switcher: pressing another view
   * switches to it, pressing the one you are already in closes the panel and gives you the chat back. The
   * earlier version renamed the first button to "Chat" while the active view was a different one, so the
   * button that said "Chat" was not the button that would take you there — it took two presses and looked
   * broken. Names are fixed now, and the highlight says where you are.
   */
  function viewButton(label, view) {
    var id = view || 'session';
    return h('button', {
      class: 'dash-toggle',
      // A real <button>, so keyboard operation, focus and the button role come from the platform rather
      // than from attributes we would have to keep correct by hand. `aria-controls`/`aria-expanded` say
      // what it opens and whether it is open (4.1.2); `aria-current` says which view you are in, which is
      // the part colour alone must not carry (1.4.1).
      attrs: {
        type: 'button',
        'data-view': id,
        'aria-controls': 'cc-dashboard',
        'aria-expanded': 'false',
      },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown && currentView === id) {
            toggle(); // back to the chat
            return;
          }
          currentView = id;
          if (shown) {
            render();
            markActiveButton();
          } else {
            toggle(); // opens, renders and marks
          }
          announceView();
        },
      },
    });
  }

  /**
   * Says out loud which view is now on screen (4.1.3 Status Messages).
   *
   * The panel swaps its whole content without the focus moving, so a screen-reader user gets no signal at
   * all otherwise — the transcript simply becomes a different panel in silence. `CC.announce` writes to the
   * live region the shell declares statically, which is why it is announced rather than created here.
   */
  function announceView() {
    var c = core();
    if (!c || typeof c.announce !== 'function') return;
    if (!shown) {
      c.announce('Dashboard closed');
      return;
    }
    var v = VIEWS[currentView] || VIEWS.session;
    c.announce(v.title + ' view');
  }

  /** Which of the four views the panel is showing. Drives `render` and the active-button highlight. */
  var currentView = 'session';

  function markActiveButton() {
    var all = document.querySelectorAll('.dash-toggle');
    for (var i = 0; i < all.length; i++) {
      var isChat = all[i].classList.contains('dash-exit');
      // Exactly one button is lit at any moment: the open view, or Chat when nothing is open.
      var isActive = shown ? !isChat && all[i].getAttribute('data-view') === currentView : isChat;
      all[i].classList.toggle('active', isActive);
      // Both states are programmatic, not just painted: `aria-expanded` for "this opens the panel and the
      // panel is open", `aria-current` for "and this is the view you are in".
      all[i].setAttribute('aria-expanded', shown ? 'true' : 'false');
      if (isActive) {
        all[i].setAttribute('aria-current', 'true');
      } else {
        all[i].removeAttribute('aria-current');
      }
    }
  }

  // Build the toggle + panel once. Idempotent.
  // ---------------------------------------------------------------------------
  function build() {
    if (built) return;
    var conv = conversation();
    var root = appRoot();
    if (!conv || !root) return; // try again later
    built = true;

    // The id is what the view buttons point `aria-controls` at, so the relation between the stack and the
    // panel it opens is programmatic rather than only visual.
    panel = h('div', { class: 'dashboard', attrs: { hidden: '', id: 'cc-dashboard' } });
    // A sibling of #conversation, and so a child of #work: the two share the first cell of its grid, which
    // is what makes this a layer over a transcript that stays laid out rather than a swap for it. The dock
    // is the row below and the tab bar is outside #work, so neither can be covered (dashboard.css).
    if (conv.parentNode) {
      conv.parentNode.insertBefore(panel, conv.nextSibling);
    } else {
      root.appendChild(panel);
    }

    // The stack: a way OUT, then the views. "Chat" is its own button rather than a state of another
    // one — leaving the dashboard is a different action from switching view, and making the user find
    // whichever button happens to be highlighted in order to leave is a puzzle, not an affordance.
    toggleBtn = viewButton('Session', null);
    var chatBtn = h('button', {
      class: 'dash-toggle dash-exit',
      attrs: { type: 'button', 'aria-controls': 'cc-dashboard', 'aria-expanded': 'true' },
      text: 'Chat',
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown) toggle();
          // "Chat" means the CHAT, not merely "not the dashboard". Closing the panel was all this did, so
          // pressed while an agent's or a task's transcript was on screen — which is the state you most
          // want out of — it did nothing at all, and with no pill for a task there was no other way back.
          CC.send({ type: 'showChatTranscript' });
          announceView();
        },
      },
    });
    planBtn = viewButton('Plan', 'plan');
    // Hidden until the session actually has a plan — see `syncOptionalButtons`. Most sessions never write one,
    // and a permanent button whose view says "No plan for this session" is the panel answering a question
    // nobody asked, which is the same reason the Session cards are not shown by default.
    planBtn.hidden = true;
    gitBtn = viewButton('Git', 'git');
    // Same rule, same reason: hidden until the host reports a Git surface for this project.
    gitBtn.hidden = true;
    var stack = h(
      'div',
      { class: 'dash-toggles' },
      chatBtn,
      toggleBtn,
      viewButton('Workloads', 'workloads'),
      gitBtn,
      planBtn
    );
    // Into the TAB BAR, not floating over the transcript. As a fixed stack in the corner it sat on top of
    // the conversation and, with a few chats open, on top of the tabs themselves — the row it now lives in
    // has always reserved the space for it (`.tab-row` padding-right).
    var bar = document.getElementById('tabsbar');
    if (bar) bar.appendChild(stack);
    else root.appendChild(stack);

    applyVisibility();
    render();
  }

  function applyVisibility() {
    if (!panel || !toggleBtn) return;
    var conv = conversation();
    if (shown) {
      panel.removeAttribute('hidden');
      panel.classList.add('open');
      // The transcript is COVERED, not hidden: it keeps its box and therefore its scroll offset, and the
      // reader comes back to the line they were on instead of to the top of the conversation. `inert` is the
      // other half of that — a transcript still painted underneath would keep its links and buttons
      // focusable while invisible, which is WCAG 2.2 SC 2.4.11 (Focus Not Obscured), and it would also be
      // read out by a screen reader over the panel that replaced it.
      if (conv) conv.setAttribute('inert', '');
    } else {
      panel.setAttribute('hidden', '');
      panel.classList.remove('open');
      if (conv) conv.removeAttribute('inert');
      // Leaving the panel returns to the default view, so the next press of any button opens what it says
      // rather than whatever was last looked at.
      currentView = defaultView();
    }
    // Button labels never change (see viewButton); the highlight says where you are. "Chat" is always on
    // screen — it is one of the places you can be, not a mode of the others — and it is the one lit up
    // when the dashboard is closed, because then the chat IS the view you are looking at.
    markActiveButton();
  }

  function toggle() {
    shown = !shown;
    if (shown) render(); // refresh on show
    applyVisibility();
  }

  // The panel's visibility is this file's; a Workloads node that sends you somewhere has to be able to get
  // out of the way (see app-session-workloads.js), and this is the only door it needs.
  D.leaveDashboard = function () {
    if (shown) toggle();
  };

  // The Git view's header carries an explicit Show/Hide chat button (app-session-git.js). It drives THIS
  // toggle rather than one of its own: the panel's visibility has a single owner, and a second one would be a
  // second answer to "is the chat on screen". `dashboardShown` is what lets that button label itself.
  D.toggleDashboard = toggle;
  D.dashboardShown = function () {
    return shown;
  };

  function ensureBuilt() {
    if (!built) build();
  }

  // ---------------------------------------------------------------------------
  // Public API — assigned onto window.cc (null-safe, stash-then-render).
  // ---------------------------------------------------------------------------
  var cc = window.cc || (window.cc = {});

  cc.session = function (payload) {
    lastSession = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    renderIfShown();
  };

  cc.mcp = function (payload) {
    lastMcp = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    renderIfShown();
  };

  /**
   * `cc.meta` belongs to the composer, which loads earlier; this WRAPS it rather than replacing it.
   *
   * The one field read here is `gitIntegration`: the host marking a tab as the Git tab, so it opens on the
   * visualiser instead of on an empty chat. Everything else in the meta stays the composer's, and no other
   * tab changes behaviour — a tab without the flag is exactly what it was.
   */
  var metaBefore = typeof cc.meta === 'function' ? cc.meta : null;
  cc.meta = function (m) {
    if (metaBefore) metaBefore(m);
    if (m && m.gitIntegration === true) gitTab = true;
    ensureBuilt();
    renderIfShown();
  };

  // Host can force the dashboard open (e.g. the ⚙ menu reusing this instead of plain-text dialogs).
  cc.openDashboard = function () {
    ensureBuilt();
    if (!built) return;
    shown = true;
    render();
    applyVisibility();
  };

  /**
   * Host can force the dashboard SHUT — used when a tab is selected in one of the agent strips.
   *
   * Selecting a tab repaints the transcript, which is behind the panel: without this the click looked
   * like it did nothing, because what changed was hidden by the very view you were in.
   */
  cc.closeDashboard = function () {
    if (!built || !shown) return;
    shown = false;
    applyVisibility();
  };

  // ---------------------------------------------------------------------------
  // Build when DOM is ready (mount points exist).
  // ---------------------------------------------------------------------------
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    build();
  } else {
    window.addEventListener('DOMContentLoaded', build);
  }
})();
