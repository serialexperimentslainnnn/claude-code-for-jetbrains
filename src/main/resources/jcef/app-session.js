/* app-session.js — A5 (session dashboard)
 * Implements cc.session(payload) and cc.mcp(payload) per JCEF_CONTRACT2.md §cc.session/cc.mcp.
 * A fixed top-right ".dash-toggle" button shows/hides a ".dashboard" panel that overlays
 * CC.els.conversation (the composer stays visible). Hidden by default.
 * Consumes app-core.js globals (window.CC: h, escape, send). Vanilla ES2019,
 * addEventListener only, no external resources, themeable via CSS classes only.
 */
(function () {
  'use strict';

  // ---- Safe accessors --------------------------------------------------------
  function core() {
    return window.CC || null;
  }
  function conversation() {
    var c = core();
    return (c && c.els && c.els.conversation) || document.getElementById('conversation') || null;
  }
  function appRoot() {
    var c = core();
    return (c && c.els && c.els.app) || document.getElementById('app') || document.body || null;
  }
  function h() {
    var c = core();
    if (c && typeof c.h === 'function') return c.h.apply(c, arguments);
    return null;
  }
  function send(obj) {
    var c = core();
    if (c && typeof c.send === 'function') c.send(obj);
  }

  // ---- Last payloads (stashed so cc.session/cc.mcp may fire before build) -----
  var lastSession = null;
  var lastMcp = null;

  // ---- DOM handles (created on build) ----------------------------------------
  var toggleBtn = null;
  var panel = null;
  var shown = false;
  var built = false;

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------
  function num(v) {
    return typeof v === 'number' && isFinite(v) ? v : null;
  }

  function fmtInt(v) {
    var n = num(v);
    if (n == null) return null;
    try {
      return Math.round(n).toLocaleString();
    } catch (e) {
      return String(Math.round(n));
    }
  }

  function fmtUsd(v) {
    var n = num(v);
    if (n == null) return null;
    return '$' + n.toFixed(n < 1 ? 4 : 2);
  }

  // A simple, deterministic palette index for segment legend swatches. We do not
  // hardcode colors here — the CSS owns them via .seg:nth-of-type / data attrs.
  function statRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: String(value) })
    );
  }

  // `wide` cards span the whole grid row (.dash-card.wide { grid-column: 1 / -1 }). Use it for anything with rows
  // that need horizontal room — the context legend, and the server/task lists whose name column would otherwise
  // collapse to an ellipsis inside a 260px column.
  /**
   * A dashboard card. [anchor], when given, tags the card so a view button can scroll straight to it —
   * the four buttons are views of one panel, not four panels, so navigation is a scroll, not a rebuild.
   */
  function card(title, body, wide, anchor) {
    // body may be a node, an array of nodes, or empty. Hide when nothing renders.
    var children = [];
    if (Array.isArray(body)) {
      for (var i = 0; i < body.length; i++) {
        if (body[i]) children.push(body[i]);
      }
    } else if (body) {
      children.push(body);
    }
    if (!children.length) return null;
    var head = h('div', { class: 'dash-title', text: title });
    var props = { class: 'dash-card' + (wide ? ' wide' : '') };
    if (anchor) props.attrs = { 'data-card': anchor };
    return h('div', props, head, children);
  }

  // ---------------------------------------------------------------------------
  // Card builders
  // ---------------------------------------------------------------------------
  /**
   * Plan limits: one labelled bar per window (current session, all models, per model), plus the extra-credit
   * balance. Sourced from the binary's `get_usage`, which returns every window at once.
   *
   * A window whose percentage is unknown renders its bar EMPTY and its value as "—", never as 0%. The binary
   * reports `utilization` only when the API returned it, and "we do not know" is a different statement from
   * "you have used none" — a bar cannot say both, so it says neither and the text carries the distinction.
   */
  function buildUsageCard(usage) {
    if (!usage || typeof usage !== 'object') return null;
    var windows = Array.isArray(usage.windows) ? usage.windows : [];
    if (!windows.length && !usage.extra) return null;

    var rows = [];
    for (var i = 0; i < windows.length; i++) {
      var w = windows[i] || {};
      rows.push(usageBar(w.label, num(w.pct), w.resetsAt, w.exhausted));
    }
    if (usage.extra && usage.extra.enabled) {
      rows.push(extraCreditRow(usage.extra));
    }
    var title = usage.plan ? 'Plan limits · ' + String(usage.plan) : 'Plan limits';
    return card(title, rows, true);
  }

  /** One window: label, a proportional bar, the percentage, and when it resets. */
  function usageBar(label, pct, resetsAt, exhausted) {
    var known = pct != null;
    // Same blue/amber/red scale as the composer's dot — see usageLevel there. `exhausted` (the binary told us
    // the window is spent) always wins over the percentage, which may be stale or absent.
    var level = exhausted ? 'lvl-high' : known ? usageLevel(pct) : 'lvl-low';
    var fill = h('div', {
      class: 'usage-fill ' + level,
      style: { width: (known ? pct.toFixed(1) : 0) + '%' },
    });
    var reset = resetIn(resetsAt);
    return h(
      'div',
      { class: 'usage-row' },
      h(
        'div',
        { class: 'usage-head' },
        h('span', { class: 'usage-label', text: label == null ? '' : String(label) }),
        // toFixed(1): one decimal, and it also kills the IEEE-754 tail (0.28*100 = 28.000000000000004).
        h('span', { class: 'usage-pct', text: known ? pct.toFixed(1) + '% used' : '—' })
      ),
      h('div', { class: 'usage-track' }, fill),
      reset ? h('div', { class: 'usage-reset', text: reset }) : null
    );
  }

  /** Pay-as-you-go balance, shown only once the user has actually enabled extra credits. */
  function extraCreditRow(extra) {
    var spent = num(extra.spent);
    var text =
      spent == null
        ? 'enabled'
        : spent.toFixed(2) + (extra.currency ? ' ' + String(extra.currency) : '') + ' used';
    return h(
      'div',
      { class: 'usage-row' },
      h(
        'div',
        { class: 'usage-head' },
        h('span', { class: 'usage-label', text: 'Extra credits' }),
        h('span', {
          class: 'usage-pct' + (extra.limitReached ? ' exhausted' : ''),
          text: extra.limitReached ? 'limit reached' : text,
        })
      )
    );
  }

  /** Quota severity. Kept identical to app-composer.js's usageLevel; the class names are the shared contract. */
  function usageLevel(pct) {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  /** "Resets in 4h 50m" — one implementation, in app-core, shared with the composer's bar row. */
  function resetIn(iso) {
    return CC.resetIn(iso);
  }

  function buildContextCard(ctx) {
    if (!ctx || typeof ctx !== 'object') return null;
    var cats = Array.isArray(ctx.categories) ? ctx.categories : [];
    var used = num(ctx.used);
    var max = num(ctx.max);
    var pct = num(ctx.pct);

    if (!cats.length && used == null && max == null) return null;

    // Total tokens across categories, to compute proportional widths.
    var total = 0;
    var i;
    for (i = 0; i < cats.length; i++) {
      var t = num(cats[i] && cats[i].tokens);
      if (t != null && t > 0) total += t;
    }

    var children = [];

    // Headline: used/max · pct%
    var headlineBits = [];
    if (used != null || max != null) {
      var u = fmtInt(used);
      var m = fmtInt(max);
      headlineBits.push((u != null ? u : '?') + ' / ' + (m != null ? m : '?'));
    }
    if (pct != null) headlineBits.push(Math.round(pct) + '%');
    if (headlineBits.length) {
      children.push(
        h(
          'div',
          { class: 'stat-row' },
          h('span', { class: 'stat-label', text: 'Context' }),
          h('span', { class: 'stat-value', text: headlineBits.join(' · ') })
        )
      );
    }

    // Segmented bar.
    if (cats.length && total > 0) {
      var segs = [];
      var legendItems = [];
      for (i = 0; i < cats.length; i++) {
        var cat = cats[i] || {};
        var name = cat.name != null ? String(cat.name) : '';
        var tok = num(cat.tokens);
        if (tok == null || tok <= 0) continue;
        var widthPct = (tok / total) * 100;
        var idx = String((i % 8) + 1); // CSS may key swatch color off data-seg
        segs.push(
          h('div', {
            class: 'seg',
            dataset: { seg: idx },
            style: { width: widthPct.toFixed(3) + '%' },
            title: name + ' · ' + (fmtInt(tok) || tok),
          })
        );
        legendItems.push(
          h(
            'span',
            { class: 'legend-item' },
            h('span', { class: 'legend-swatch', dataset: { seg: idx } }),
            h('span', { class: 'legend-name', text: name }),
            h('span', { class: 'legend-tokens', text: fmtInt(tok) || String(tok) })
          )
        );
      }
      if (segs.length) {
        children.push(h('div', { class: 'seg-bar' }, segs));
        children.push(h('div', { class: 'legend' }, legendItems));
      }
    }

    return card('Context', children, true);
  }

  function buildCostCard(cost) {
    if (!cost || typeof cost !== 'object') return null;
    var rows = [
      statRow('Input', fmtInt(cost.input)),
      statRow('Output', fmtInt(cost.output)),
      statRow('Cache write', fmtInt(cost.cacheWrite)),
      statRow('Cache read', fmtInt(cost.cacheRead)),
      statRow('Cost', fmtUsd(cost.usd)),
    ];
    return card('Usage & cost', rows);
  }

  function buildAccountCard(acct) {
    if (!acct || typeof acct !== 'object') return null;
    var rows = [
      statRow('Email', acct.email),
      statRow('Organization', acct.org),
      statRow('Plan', acct.plan),
      statRow('Provider', acct.provider),
    ];
    // Sign in / Log out, from the VERIFIED auth state only (`loggedIn` comes from the host's
    // `auth status` probe). When it is unknown the row is omitted — a button must not claim a state
    // nobody checked. Signing in is a button here, not a slash command to know about.
    if (acct.loggedIn === true || acct.loggedIn === false) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'btn account-auth-btn';
      btn.textContent = acct.loggedIn ? 'Log out' : 'Sign in';
      btn.addEventListener('click', function () {
        CC.send({ type: acct.loggedIn ? 'logout' : 'loginSubscription' });
      });
      var row = document.createElement('div');
      row.className = 'account-auth-row';
      row.appendChild(btn);
      rows.push(row);
    }
    return card('Account', rows);
  }

  function buildEnvCard(payload) {
    var rows = [
      statRow('Model', payload.model),
      statRow('Working dir', payload.cwd),
      statRow('Version', payload.version),
    ];
    return card('Session', rows);
  }

  /**
   * One row of the Agents / Subagents windows.
   *
   * The whole row is the link: clicking it goes to that agent's tab, reopening it when the user had closed
   * it. The ownership chain (`Chat |_ Agent A |_ Agent B`) is built by the host, because parentage is a
   * property of the data rather than of how it is drawn — and it is the same string the Background tasks
   * window shows, so "where does this hang from" reads identically everywhere.
   */
  function agentRow(a) {
    var label = a.label != null ? String(a.label) : 'Agent';
    var metaBits = [];
    if (a.type) metaBits.push(String(a.type));
    if (a.status) metaBits.push(String(a.status));
    var depth = typeof a.depth === 'number' ? a.depth : 1;
    return h(
      'div',
      {
        class: 'subagent-row agent-row' + (a.running ? ' running' : ''),
        attrs: { role: 'button', tabindex: '0', title: a.chain || label },
        on: {
          click: (function (agentId) {
            return function () {
              if (agentId) send({ type: 'revealAgent', agentId: agentId });
            };
          })(a.agentId),
        },
      },
      h(
        'div',
        { class: 'subagent-main' },
        h('span', { class: 'subagent-desc', text: treePrefix(depth) + label }),
        h('span', { class: 'subagent-meta', text: metaBits.join(' · ') }),
        a.chain ? h('span', { class: 'agent-chain', text: a.chain }) : null
      )
    );
  }

  /** `|_ ` per level, capped — the same tree idiom the tab strips use, so one visual language for hanging off. */
  function treePrefix(depth) {
    var n = Math.max(0, Math.min(4, depth - 1));
    var out = '';
    for (var i = 0; i < n; i++) out += '|_ ';
    return out + '|_ ';
  }

  /** Agents spawned directly by this chat's turns. */
  function buildAgentsCard(tree) {
    if (!Array.isArray(tree)) return null;
    var roots = tree.filter(function (a) {
      return a && !a.parent;
    });
    if (!roots.length) return null;
    return card('Agents', roots.map(agentRow), true, 'agents');
  }

  /** Agents spawned BY another agent, at any depth — the window that answers "who launched this?". */
  function buildSubagentsCard(tree) {
    if (!Array.isArray(tree)) return null;
    var nested = tree.filter(function (a) {
      return a && a.parent;
    });
    if (!nested.length) return null;
    return card('Subagents', nested.map(agentRow), true, 'subagents');
  }

  // Live background tasks, from the `background_tasks_changed` LEVEL signal: the host always sends the CURRENT
  // set, so this list can never wedge on a missed start/stop bookend the way the edge-derived Subagents list can.
  // Deliberately a separate card — the two streams must not be correlated.
  function buildBackgroundTasksCard(tasks) {
    if (!Array.isArray(tasks) || !tasks.length) return null;
    var rows = [];
    for (var i = 0; i < tasks.length; i++) {
      var t = tasks[i] || {};
      var id = t.id;
      var desc = t.desc != null ? String(t.desc) : '';
      var type = t.type != null ? String(t.type) : '';

      var stopBtn = h('span', {
        class: 'btn',
        attrs: { role: 'button', tabindex: '0' },
        text: 'Stop',
        on: {
          click: (function (taskId) {
            return function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              if (taskId != null) send({ type: 'stopTask', taskId: taskId });
            };
          })(id),
        },
      });

      // Where it runs. The chat is always known -- it is the session that reported the task -- but the
      // OWNING AGENT often is not: `background_tasks_changed` carries only id, type and description, with
      // no parent and no tool_use_id. When the host could not resolve one it says so, because a made-up
      // chain would be worse than an honest gap.
      var chain = t.chain != null ? String(t.chain) : '';
      var row = h(
        'div',
        {
          class: 'subagent-row' + (t.agentId ? ' agent-row' : ''),
          attrs: t.agentId ? { role: 'button', tabindex: '0', title: chain } : { title: chain },
          on: t.agentId
            ? {
                click: (function (agentId) {
                  return function () {
                    send({ type: 'revealAgent', agentId: agentId });
                  };
                })(t.agentId),
              }
            : null,
        },
        h(
          'div',
          { class: 'subagent-main' },
          h('span', { class: 'subagent-desc', text: desc || type || 'Background task' }),
          type ? h('span', { class: 'subagent-meta', text: type }) : null,
          chain ? h('span', { class: 'agent-chain', text: chain }) : null
        ),
        stopBtn
      );
      rows.push(row);
    }
    return card('Background tasks', rows, true, 'background');
  }

  // status → mcp-dot class. Defensive: unknown maps to nothing extra.
  var MCP_STATUS_CLASS = {
    connected: 'connected',
    pending: 'pending',
    connecting: 'pending',
    failed: 'failed',
    error: 'failed',
    'needs-auth': 'needs-auth',
    needs_auth: 'needs-auth',
    authentication: 'needs-auth',
    disabled: 'disabled',
  };

  function mcpServersFrom(payload) {
    if (!payload || typeof payload !== 'object') return [];
    // The control response uses camelCase `mcpServers`; system/init uses snake `mcp_servers`. Accept both.
    var list = payload.mcpServers;
    if (!Array.isArray(list)) list = payload.servers;
    if (!Array.isArray(list)) list = payload.mcp_servers;
    if (!Array.isArray(list)) {
      // Some shapes nest one level (e.g. { mcp_status: { servers: [...] } }).
      var inner = payload.mcp_status || payload.status || payload.mcp;
      if (inner && typeof inner === 'object') {
        if (Array.isArray(inner.mcpServers)) list = inner.mcpServers;
        else if (Array.isArray(inner.servers)) list = inner.servers;
        else if (Array.isArray(inner.mcp_servers)) list = inner.mcp_servers;
      }
    }
    if (!Array.isArray(list)) return [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var srv = list[i];
      if (!srv || typeof srv !== 'object') continue;
      var name = srv.name != null ? String(srv.name) : '';
      var status = srv.status != null ? String(srv.status) : '';
      if (!name) continue;
      out.push({ name: name, status: status });
    }
    return out;
  }

  function buildMcpCard(payload) {
    var servers = mcpServersFrom(payload);
    if (!servers.length) return null;
    var rows = [];
    for (var i = 0; i < servers.length; i++) {
      var srv = servers[i];
      var statusLower = (srv.status || '').toLowerCase();
      var dotClass = 'mcp-dot';
      var extra = MCP_STATUS_CLASS[statusLower];
      if (extra) dotClass += ' ' + extra;

      var disabled = statusLower === 'disabled';
      var enabledNext = disabled; // toggling sets the opposite of current

      var reconnectBtn = h('span', {
        class: 'btn',
        attrs: { role: 'button', tabindex: '0' },
        text: 'Reconnect',
        on: {
          click: (function (name) {
            return function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              send({ type: 'mcpReconnect', name: name });
            };
          })(srv.name),
        },
      });

      // `.toggle` is a 32x18 switch whose knob is an absolutely-positioned ::after — it must NOT carry text, or the
      // label overflows the pill and the knob paints on top of it ("Dis●ble"). The state is conveyed by the switch
      // itself; the accessible name lives in title/aria-label.
      var toggleEl = h('span', {
        class: disabled ? 'toggle' : 'toggle on',
        attrs: {
          role: 'switch',
          tabindex: '0',
          'aria-checked': disabled ? 'false' : 'true',
          'aria-label': disabled ? 'Enable server' : 'Disable server',
        },
        title: disabled ? 'Enable' : 'Disable',
        on: {
          click: (function (name, enabled) {
            return function (ev) {
              ev.preventDefault();
              ev.stopPropagation();
              send({ type: 'mcpToggle', name: name, enabled: enabled });
            };
          })(srv.name, enabledNext),
        },
      });

      rows.push(
        h(
          'div',
          { class: 'mcp-row' },
          h('span', { class: dotClass }),
          h('span', { class: 'mcp-name', text: srv.name }),
          h('span', { class: 'mcp-status', text: srv.status || 'unknown' }),
          h('span', { class: 'mcp-actions' }, reconnectBtn, toggleEl)
        )
      );
    }
    return card('MCP servers', rows, true);
  }

  // ---------------------------------------------------------------------------
  // Render the whole dashboard body from the stashed payloads.
  // ---------------------------------------------------------------------------
  function render() {
    if (!panel) return;
    // Clear.
    while (panel.firstChild) panel.removeChild(panel.firstChild);

    // Cards live inside a centred .dash-inner grid (the grid/gap CSS targets `.dashboard > .dash-inner`; without
    // this wrapper the cards stacked with no layout). The wrapper also caps the width to the reading column.
    var inner = h('div', { class: 'dash-inner' });

    var s = lastSession || {};
    var cards = [
      buildUsageCard(s.usage),
      buildContextCard(s.context),
      buildCostCard(s.cost),
      buildAccountCard(s.account),
      buildEnvCard(s),
      buildMcpCard(lastMcp),
      // Session first, then the three windows the agent work moved into, in the order the user asked for:
      // Session · Agents · Subagents · Background tasks.
      buildAgentsCard(s.agentTree),
      buildSubagentsCard(s.agentTree),
      buildBackgroundTasksCard(s.backgroundTasks),
    ];

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
          h('div', { class: 'dash-title', text: 'Session' }),
          h('div', { class: 'stat-row' }, h('span', { class: 'stat-label', text: 'No session data yet.' }))
        )
      );
    }
    panel.appendChild(inner);
  }

  // ---------------------------------------------------------------------------
  /**
   * One of the four view buttons. [anchor] is the card to scroll to once the panel is open (null = the top,
   * i.e. the Session view). Opening an already-open panel on the same button closes it, so a button toggles
   * its own view rather than trapping the user in the dashboard.
   */
  function viewButton(label, anchor) {
    return h('button', {
      class: 'dash-toggle',
      attrs: { type: 'button', 'data-anchor': anchor || 'session' },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown && currentAnchor === (anchor || 'session')) {
            toggle();
            return;
          }
          currentAnchor = anchor || 'session';
          if (!shown) toggle();
          scrollToAnchor();
          markActiveButton();
        },
      },
    });
  }

  /** Which view the last button press asked for; drives the scroll and the active-button highlight. */
  var currentAnchor = 'session';

  function scrollToAnchor() {
    if (!panel) return;
    if (currentAnchor === 'session') {
      panel.scrollTop = 0;
      return;
    }
    var card = panel.querySelector('[data-card="' + currentAnchor + '"]');
    if (card && card.scrollIntoView) card.scrollIntoView({ block: 'start' });
  }

  function markActiveButton() {
    var all = document.querySelectorAll('.dash-toggle');
    for (var i = 0; i < all.length; i++) {
      var isActive = shown && all[i].getAttribute('data-anchor') === currentAnchor;
      all[i].classList.toggle('active', isActive);
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

    panel = h('div', { class: 'dashboard', attrs: { hidden: '' } });
    // Overlay the conversation; the composer (in #dock) stays visible.
    // Insert as a sibling of #conversation so CSS can position it over the
    // conversation area without covering the dock.
    if (conv.parentNode) {
      conv.parentNode.insertBefore(panel, conv.nextSibling);
    } else {
      root.appendChild(panel);
    }

    // Four buttons, stacked: Session, then the three windows the agent work moved into. Each opens the
    // dashboard scrolled to its own card, so they are views of one panel rather than four panels -- the
    // data is the same payload and splitting it would mean four things to keep in sync.
    toggleBtn = viewButton('Session', null);
    var stack = h(
      'div',
      { class: 'dash-toggles' },
      toggleBtn,
      viewButton('Agents', 'agents'),
      viewButton('Subagents', 'subagents'),
      viewButton('Background tasks', 'background')
    );
    root.appendChild(stack);

    applyVisibility();
    render();
  }

  function applyVisibility() {
    if (!panel || !toggleBtn) return;
    var conv = conversation();
    if (shown) {
      panel.removeAttribute('hidden');
      panel.classList.add('open');
      // Hide the transcript while the dashboard fills the conversation area — the dock (composer) stays visible.
      if (conv) conv.setAttribute('hidden', '');
      // The first button doubles as the way OUT: with the panel open it reads "Chat". The other three keep
      // their names and only light up, so the stack always says both where you are and how to leave.
      toggleBtn.textContent = 'Chat';
      toggleBtn.classList.add('active');
    } else {
      panel.setAttribute('hidden', '');
      panel.classList.remove('open');
      if (conv) conv.removeAttribute('hidden');
      toggleBtn.textContent = 'Session';
      toggleBtn.classList.remove('active');
    }
    markActiveButton();
  }

  function toggle() {
    shown = !shown;
    if (shown) render(); // refresh on show
    applyVisibility();
  }

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
    if (built) render(); // keep DOM fresh even while hidden
  };

  cc.mcp = function (payload) {
    lastMcp = payload && typeof payload === 'object' ? payload : null;
    ensureBuilt();
    if (built) render();
  };

  // Host can force the dashboard open (e.g. the ⚙ menu reusing this instead of plain-text dialogs).
  cc.openDashboard = function () {
    ensureBuilt();
    if (!built) return;
    shown = true;
    render();
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
