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
  /** The way out, above the views. Only present while the panel is open (see applyVisibility). */
  var chatBtn = null;
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
   * Goes to that agent's tab AND leaves the dashboard.
   *
   * Leaving is half the action: selecting a tab behind an open panel changes something the user cannot see,
   * so the link appeared to do nothing. What you asked for was to go and read it, and reading happens in the
   * chat area the panel is covering.
   *
   * An empty [agentId] means the chat's own transcript — a background task the binary never attributed to
   * an agent still ran somewhere, and that somewhere is the chat.
   */
  function revealAndLeave(agentId, chatId) {
    // The chat it belongs to travels WITH it: this diagram spans every chat, but the message lands on the
    // panel that is on screen. Without the id that panel searched its own session for somebody else's agent,
    // found nothing, and the click did nothing at all.
    send({ type: 'revealAgent', agentId: agentId || '', chatId: chatId == null ? '' : String(chatId) });
    if (shown) toggle();
  }

  /** Same contract as [revealAndLeave], for a background task's own view. */
  function revealTaskAndLeave(taskId, chatId) {
    if (taskId == null) return;
    send({
      type: 'revealBackgroundTask',
      taskId: taskId,
      chatId: chatId == null ? '' : String(chatId),
    });
    if (shown) toggle();
  }

  // NB the hierarchy used to be spelled INSIDE the label — first `|_`, then spaces, then CSS indentation.
  // All three were the same mistake in different clothes: a shape expressed as text wraps, misaligns and
  // cannot be styled. It is a real diagram now (CC.diagram): cards positioned by arithmetic, joined by
  // curves drawn from those same coordinates.

  /**
   * Everything running, as ONE diagram: every chat, its agents, their agents, and the tasks each started.
   *
   * It was three views — Agents, Subagents, Background tasks — and they were three views of the same tree:
   * to find out whether an agent had spawned anything you switched view, lost the parent, and read a
   * breadcrumb to work out where you were. Then it was one already-expanded list, which was right about the
   * data and wrong about the drawing: rows at different x with nothing joining them read as a ragged list.
   *
   * Now it is a diagram, rooted at the CHATS — that is the honest root, since a chat is what starts
   * everything below it — and every node is a destination: click a chat, an agent, a subagent or a task and
   * you go there. It pans by dragging, like a diagram editor, because a tree that outgrows the panel should
   * be moved around rather than scrolled through two scrollbars.
   *
   * [payload] may carry `workloads` (every chat) or just this session's own `agentTree`/`backgroundTasks`;
   * the second is drawn under a single root so the shape is the same either way.
   */
  function buildWorkloadsCard(payload) {
    var chats =
      Array.isArray(payload.workloads) && payload.workloads.length
        ? payload.workloads.filter(Boolean)
        : [
            {
              chatId: null,
              title: 'This chat',
              selected: true,
              tree: Array.isArray(payload.agentTree) ? payload.agentTree.filter(Boolean) : [],
              tasks: Array.isArray(payload.backgroundTasks) ? payload.backgroundTasks.filter(Boolean) : [],
            },
          ];

    var roots = chats.map(chatNode).filter(function (n) {
      // A chat that started nothing is not a workload; drawing it would be a card saying "nothing here".
      return n.children.length > 0;
    });
    if (!roots.length) return null;

    var canvas = CC.diagram(roots);
    if (!canvas) return null;
    // Keyed, so the dashboard's frequent rebuilds restore where you left the diagram instead of re-fitting.
    var view = CC.panView(canvas, 'Workloads diagram — drag to move, wheel to zoom', 'workloads');
    // The card is not in the document yet, so the viewport has no size: fit on the next frame, when it has.
    // Two frames, because the dashboard reveals the panel with its own transition.
    requestAnimationFrame(function () {
      if (view.__fit) view.__fit();
      requestAnimationFrame(function () {
        if (view.__fit) view.__fit();
      });
    });
    return card('Workloads', [view], true, 'workloads');
  }

  /** One chat, with everything it started underneath it. Every node is a destination. */
  function chatNode(chat) {
    var nodes = Array.isArray(chat.tree) ? chat.tree.filter(Boolean) : [];
    var list = Array.isArray(chat.tasks) ? chat.tasks.filter(Boolean) : [];
    var seen = {};

    function tasksOf(agentId) {
      return list.filter(function (t) {
        return (t.agentId == null ? null : t.agentId) === agentId;
      });
    }
    function childrenOf(agentId) {
      return nodes
        .filter(function (n) {
          return (n.parent == null ? null : n.parent) === agentId;
        })
        .filter(function (n) {
          if (seen[n.agentId]) return false; // a malformed parent link must not loop
          seen[n.agentId] = true;
          return true;
        });
    }

    function agentNode(a, depth) {
      return {
        id: a.agentId,
        kind: 'agent',
        // `Agent (…)` / `Subagent (…)` — the same naming the transcript card uses, so the same work does not
        // have two names depending on which panel you read it in.
        label: CC.diagramLabel('agent', depth, a.label != null ? String(a.label) : 'Agent'),
        meta: a.type ? String(a.type) : '',
        status: a.status ? String(a.status) : null,
        running: !!a.running,
        title: a.chain || a.label,
        onPick: function () {
          if (a.agentId) revealAndLeave(a.agentId, chat.chatId);
        },
        children: childrenOf(a.agentId)
          .map(function (child) {
            return agentNode(child, depth + 1);
          })
          .concat(
            tasksOf(a.agentId).map(function (t) {
              return taskNode(t, chat.chatId);
            })
          ),
      };
    }

    var kids = childrenOf(null).map(function (a) {
      return agentNode(a, 1);
    });
    // Tasks the chat itself started hang off the chat, and a task whose owner never became known would
    // otherwise be invisible — an honest gap has to be VISIBLE to be honest.
    var loose = tasksOf(null).concat(
      list.filter(function (t) {
        return (
          t.agentId != null &&
          !nodes.some(function (n) {
            return n.agentId === t.agentId;
          })
        );
      })
    );

    return {
      id: chat.chatId,
      kind: 'chat',
      label: chat.title != null ? String(chat.title) : 'Chat',
      selected: !!chat.selected,
      title: 'Go to this chat',
      onPick: function () {
        if (chat.chatId != null) send({ type: 'selectChat', chatId: chat.chatId });
        revealAndLeave('');
      },
      children: kids.concat(
        loose.map(function (t) {
          return taskNode(t, chat.chatId);
        })
      ),
    };
  }

  /**
   * One background task as a diagram node.
   *
   * Running AND finished ones are drawn: the host sends its own record, not the binary's live set, because
   * that set is a LEVEL signal — a task that ends stops being listed, and the node (with its output) used to
   * disappear at the exact moment there was something to read.
   *
   * The click opens the TASK's own view — what it is, who started it, its command and its output — not its
   * owner's transcript. Sending the user to the owner is what made this node look inert.
   */
  function taskNode(t, chatId) {
    var running = t.running !== false;
    var type = t.type != null ? String(t.type) : '';
    return {
      id: t.id,
      kind: 'task',
      // `Background Task (…)`, holding the model's description of the job or, failing that, the command.
      label: CC.diagramLabel('task', 1, (t.desc != null && String(t.desc)) || type || 'background'),
      meta: type || 'background task',
      // Named by the host, like every other state on the page (see JcefStatus).
      status: t.status || null,
      running: running,
      title: t.chain || t.desc || 'Background task',
      onPick: function () {
        revealTaskAndLeave(t.id, chatId);
      },
      // A finished task keeps its node and loses its Stop: there is nothing left to stop, and a button that
      // does nothing is worse than no button.
      action: running
        ? {
            label: 'Stop',
            onClick: function () {
              if (t.id != null) send({ type: 'stopTask', taskId: t.id });
            },
          }
        : null,
      children: [],
    };
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
    if (built && shown) render();
  }

  function render() {
    if (!panel) return;
    // Clear.
    while (panel.firstChild) panel.removeChild(panel.firstChild);

    // Cards live inside a centred .dash-inner grid (the grid/gap CSS targets `.dashboard > .dash-inner`; without
    // this wrapper the cards stacked with no layout). The wrapper also caps the width to the reading column.
    var inner = h('div', { class: 'dash-inner' });

    var s = lastSession || {};
    // FOUR EXCLUSIVE VIEWS, not one panel with four scroll anchors. The anchor version was wrong in a way
    // that only shows up in use: with no agents there is no Agents card to scroll to, so pressing "Agents"
    // simply left the Session cards on screen — the button looked broken because it did nothing visible.
    //
    // One registry, looked up by view: adding a fifth view is a line here and a line in the button stack,
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
  }

  /**
   * The four views, each declaring its own title, its cards and what it says when empty.
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
          buildUsageCard(s.usage),
          buildContextCard(s.context),
          buildCostCard(s.cost),
          buildAccountCard(s.account),
          buildEnvCard(s),
          buildMcpCard(lastMcp),
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
        return [buildWorkloadsCard(s)];
      },
    },
  };

  // ---------------------------------------------------------------------------
  /**
   * One of the four view buttons.
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
    // Overlay the conversation; the composer (in #dock) stays visible.
    // Insert as a sibling of #conversation so CSS can position it over the
    // conversation area without covering the dock.
    if (conv.parentNode) {
      conv.parentNode.insertBefore(panel, conv.nextSibling);
    } else {
      root.appendChild(panel);
    }

    // The stack: a way OUT, then the four views. "Chat" is its own button rather than a state of another
    // one — leaving the dashboard is a different action from switching view, and making the user find
    // whichever button happens to be highlighted in order to leave is a puzzle, not an affordance. It only
    // exists while the panel is open, because a "Chat" button while you are already in the chat says nothing.
    toggleBtn = viewButton('Session', null);
    chatBtn = h('button', {
      class: 'dash-toggle dash-exit',
      attrs: { type: 'button', 'aria-controls': 'cc-dashboard', 'aria-expanded': 'true' },
      text: 'Chat',
      on: {
        click: function (ev) {
          ev.preventDefault();
          if (shown) toggle();
          announceView();
        },
      },
    });
    var stack = h('div', { class: 'dash-toggles' }, chatBtn, toggleBtn, viewButton('Workloads', 'workloads'));
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
      // Hide the transcript while the dashboard fills the conversation area — the dock (composer) stays visible.
      if (conv) conv.setAttribute('hidden', '');
    } else {
      panel.setAttribute('hidden', '');
      panel.classList.remove('open');
      if (conv) conv.removeAttribute('hidden');
      // Leaving the panel returns to the default view, so the next press of any button opens what it says
      // rather than whatever was last looked at.
      currentView = 'session';
    }
    // Button labels never change (see viewButton); the highlight says where you are. "Chat" is always on
    // screen — it is one of the five places you can be, not a mode of the others — and it is the one lit up
    // when the dashboard is closed, because then the chat IS the view you are looking at.
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
    renderIfShown();
  };

  cc.mcp = function (payload) {
    lastMcp = payload && typeof payload === 'object' ? payload : null;
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
