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
  function core() { return window.CC || null; }
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
  function esc(s) {
    var c = core();
    if (c && typeof c.escape === 'function') return c.escape(s == null ? '' : String(s));
    return s == null ? '' : String(s);
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
    return (typeof v === 'number' && isFinite(v)) ? v : null;
  }

  function fmtInt(v) {
    var n = num(v);
    if (n == null) return null;
    try { return Math.round(n).toLocaleString(); } catch (e) { return String(Math.round(n)); }
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
    return h('div', { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: String(value) })
    );
  }

  function card(title, body) {
    // body may be a node, an array of nodes, or empty. Hide when nothing renders.
    var children = [];
    if (Array.isArray(body)) {
      for (var i = 0; i < body.length; i++) { if (body[i]) children.push(body[i]); }
    } else if (body) {
      children.push(body);
    }
    if (!children.length) return null;
    var head = h('div', { class: 'dash-title', text: title });
    return h('div', { class: 'dash-card' }, head, children);
  }

  // ---------------------------------------------------------------------------
  // Card builders
  // ---------------------------------------------------------------------------
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
      children.push(h('div', { class: 'stat-row' },
        h('span', { class: 'stat-label', text: 'Context' }),
        h('span', { class: 'stat-value', text: headlineBits.join(' · ') })
      ));
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
        segs.push(h('div', {
          class: 'seg',
          dataset: { seg: idx },
          style: { width: widthPct.toFixed(3) + '%' },
          title: name + ' · ' + (fmtInt(tok) || tok)
        }));
        legendItems.push(h('span', { class: 'legend-item' },
          h('span', { class: 'legend-swatch', dataset: { seg: idx } }),
          h('span', { class: 'legend-name', text: name }),
          h('span', { class: 'legend-tokens', text: fmtInt(tok) || String(tok) })
        ));
      }
      if (segs.length) {
        children.push(h('div', { class: 'seg-bar' }, segs));
        children.push(h('div', { class: 'legend' }, legendItems));
      }
    }

    return card('Context', children);
  }

  function buildCostCard(cost) {
    if (!cost || typeof cost !== 'object') return null;
    var rows = [
      statRow('Input', fmtInt(cost.input)),
      statRow('Output', fmtInt(cost.output)),
      statRow('Cache write', fmtInt(cost.cacheWrite)),
      statRow('Cache read', fmtInt(cost.cacheRead)),
      statRow('Cost', fmtUsd(cost.usd))
    ];
    return card('Usage & cost', rows);
  }

  function buildAccountCard(acct) {
    if (!acct || typeof acct !== 'object') return null;
    var rows = [
      statRow('Email', acct.email),
      statRow('Organization', acct.org),
      statRow('Plan', acct.plan),
      statRow('Provider', acct.provider)
    ];
    return card('Account', rows);
  }

  function buildEnvCard(payload) {
    var rows = [
      statRow('Model', payload.model),
      statRow('Working dir', payload.cwd),
      statRow('Version', payload.version)
    ];
    return card('Session', rows);
  }

  function buildSubagentsCard(subs) {
    if (!Array.isArray(subs) || !subs.length) return null;
    var rows = [];
    for (var i = 0; i < subs.length; i++) {
      var s = subs[i] || {};
      var id = s.id;
      var desc = s.desc != null ? String(s.desc) : '';
      var type = s.type != null ? String(s.type) : '';
      var status = s.status != null ? String(s.status) : '';
      var tokens = fmtInt(s.tokens);

      var metaBits = [];
      if (type) metaBits.push(type);
      if (status) metaBits.push(status);
      if (tokens != null) metaBits.push(tokens + ' tok');

      var stopBtn = h('span', {
        class: 'btn',
        attrs: { role: 'button', tabindex: '0' },
        text: 'Stop',
        on: {
          click: (function (taskId) {
            return function (ev) {
              ev.preventDefault(); ev.stopPropagation();
              if (taskId != null) send({ type: 'stopTask', taskId: taskId });
            };
          })(id)
        }
      });

      rows.push(h('div', { class: 'subagent-row' },
        h('div', { class: 'subagent-main' },
          h('span', { class: 'subagent-desc', text: desc || (type || 'Subagent') }),
          metaBits.length ? h('span', { class: 'subagent-meta', text: metaBits.join(' · ') }) : null
        ),
        stopBtn
      ));
    }
    return card('Subagents', rows);
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
              ev.preventDefault(); ev.stopPropagation();
              if (taskId != null) send({ type: 'stopTask', taskId: taskId });
            };
          })(id)
        }
      });

      rows.push(h('div', { class: 'subagent-row' },
        h('div', { class: 'subagent-main' },
          h('span', { class: 'subagent-desc', text: desc || (type || 'Background task') }),
          type ? h('span', { class: 'subagent-meta', text: type }) : null
        ),
        stopBtn
      ));
    }
    return card('Background tasks', rows);
  }

  // status → mcp-dot class. Defensive: unknown maps to nothing extra.
  var MCP_STATUS_CLASS = {
    'connected': 'connected',
    'pending': 'pending',
    'connecting': 'pending',
    'failed': 'failed',
    'error': 'failed',
    'needs-auth': 'needs-auth',
    'needs_auth': 'needs-auth',
    'authentication': 'needs-auth',
    'disabled': 'disabled'
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
              ev.preventDefault(); ev.stopPropagation();
              send({ type: 'mcpReconnect', name: name });
            };
          })(srv.name)
        }
      });

      var toggleEl = h('span', {
        class: disabled ? 'toggle' : 'toggle on',
        attrs: { role: 'button', tabindex: '0' },
        title: disabled ? 'Enable' : 'Disable',
        text: disabled ? 'Enable' : 'Disable',
        on: {
          click: (function (name, enabled) {
            return function (ev) {
              ev.preventDefault(); ev.stopPropagation();
              send({ type: 'mcpToggle', name: name, enabled: enabled });
            };
          })(srv.name, enabledNext)
        }
      });

      rows.push(h('div', { class: 'mcp-row' },
        h('span', { class: dotClass }),
        h('span', { class: 'mcp-name', text: srv.name }),
        h('span', { class: 'mcp-status', text: srv.status || 'unknown' }),
        h('span', { class: 'mcp-actions' }, reconnectBtn, toggleEl)
      ));
    }
    return card('MCP servers', rows);
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
      buildContextCard(s.context),
      buildCostCard(s.cost),
      buildAccountCard(s.account),
      buildEnvCard(s),
      buildSubagentsCard(s.subagents),
      buildBackgroundTasksCard(s.backgroundTasks),
      buildMcpCard(lastMcp)
    ];

    var any = false;
    for (var i = 0; i < cards.length; i++) {
      if (cards[i]) { inner.appendChild(cards[i]); any = true; }
    }

    if (!any) {
      inner.appendChild(h('div', { class: 'dash-card dash-empty' },
        h('div', { class: 'dash-title', text: 'Session' }),
        h('div', { class: 'stat-row' },
          h('span', { class: 'stat-label', text: 'No session data yet.' }))
      ));
    }
    panel.appendChild(inner);
  }

  // ---------------------------------------------------------------------------
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

    toggleBtn = h('button', {
      class: 'dash-toggle',
      attrs: { type: 'button' },
      text: 'Session',
      on: { click: function (ev) { ev.preventDefault(); toggle(); } }
    });
    root.appendChild(toggleBtn);

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
      toggleBtn.textContent = 'Chat';
      toggleBtn.classList.add('active');
    } else {
      panel.setAttribute('hidden', '');
      panel.classList.remove('open');
      if (conv) conv.removeAttribute('hidden');
      toggleBtn.textContent = 'Session';
      toggleBtn.classList.remove('active');
    }
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
    lastSession = (payload && typeof payload === 'object') ? payload : null;
    ensureBuilt();
    if (built) render(); // keep DOM fresh even while hidden
  };

  cc.mcp = function (payload) {
    lastMcp = (payload && typeof payload === 'object') ? payload : null;
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
