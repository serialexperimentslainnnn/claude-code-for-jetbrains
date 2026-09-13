(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;

  const MCP_STATUS_CLASS: Record<string, string> = {
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

  interface McpServer {
    name: string;
    status: string;
  }

  interface McpShape {
    mcpServers?: unknown;
    servers?: unknown;
    mcp_servers?: unknown;
    mcp_status?: unknown;
    status?: unknown;
    mcp?: unknown;
  }

  function serverList(shape: McpShape): unknown[] | null {
    if (Array.isArray(shape.mcpServers)) return shape.mcpServers;
    if (Array.isArray(shape.servers)) return shape.servers;
    if (Array.isArray(shape.mcp_servers)) return shape.mcp_servers;
    return null;
  }

  function mcpServersFrom(payload: unknown): McpServer[] {
    if (!payload || typeof payload !== 'object') return [];
    const shape = payload as McpShape;
    let list = serverList(shape);
    if (!list) {
      const inner = shape.mcp_status || shape.status || shape.mcp;
      if (inner && typeof inner === 'object') list = serverList(inner as McpShape);
    }
    if (!list) return [];
    const out: McpServer[] = [];
    for (let i = 0; i < list.length; i++) {
      const srv = list[i] as { name?: unknown; status?: unknown } | null;
      if (!srv || typeof srv !== 'object') continue;
      const name = srv.name != null ? String(srv.name) : '';
      const status = srv.status != null ? String(srv.status) : '';
      if (!name) continue;
      out.push({ name: name, status: status });
    }
    return out;
  }

  function refreshTools(): HTMLElement {
    return h(
      'div',
      { class: 'mcp-tools' },
      h('span', {
        class: 'btn',
        attrs: { role: 'button', tabindex: '0', title: 'Ask the binary for the servers it sees now' },
        text: 'Refresh',
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            ev.stopPropagation();
            send({ type: 'mcpRefresh' });
          },
        },
      })
    );
  }

  function buildMcpCard(payload: unknown): HTMLElement | null {
    if (!payload || typeof payload !== 'object') return null;
    const servers = mcpServersFrom(payload);
    const rows: HTMLElement[] = [refreshTools()];
    if (!servers.length) {
      rows.push(h('div', { class: 'mcp-empty', text: 'The binary reports no MCP server for this session.' }));
    }
    for (let i = 0; i < servers.length; i++) {
      const srv = servers[i];
      const statusLower = (srv.status || '').toLowerCase();
      let dotClass = 'mcp-dot';
      const extra = MCP_STATUS_CLASS[statusLower];
      if (extra) dotClass += ' ' + extra;

      const disabled = statusLower === 'disabled';
      const enabledNext = disabled;

      const reconnectBtn = h('span', {
        class: 'btn',
        attrs: { role: 'button', tabindex: '0' },
        text: 'Reconnect',
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            ev.stopPropagation();
            send({ type: 'mcpReconnect', name: srv.name });
          },
        },
      });

      const toggleEl = h('span', {
        class: disabled ? 'toggle' : 'toggle on',
        attrs: {
          role: 'switch',
          tabindex: '0',
          'aria-checked': disabled ? 'false' : 'true',
          'aria-label': disabled ? 'Enable server' : 'Disable server',
        },
        title: disabled ? 'Enable' : 'Disable',
        on: {
          click: function (ev: Event) {
            ev.preventDefault();
            ev.stopPropagation();
            send({ type: 'mcpToggle', name: srv.name, enabled: enabledNext });
          },
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
