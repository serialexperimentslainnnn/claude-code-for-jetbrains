/* app-session-mcp.js — the MCP servers card.
 *
 * One subject: the servers this session registered, what state each is in, and the two things you can do about
 * it (reconnect, enable/disable). The payload arrives by its own method (`cc.mcp`) and from more than one
 * shape of reply, which is why finding the list is a function of its own.
 */
(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

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

  D.buildMcpCard = buildMcpCard;
})();
