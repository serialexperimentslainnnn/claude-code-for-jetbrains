(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const V = (D.vuln = D.vuln || ({} as VulnNs));
  const h = D.h;
  const card = D.card;
  const text = V.text;
  const num = V.num;

  function inventoryCard(): HTMLElement | null {
    const inventory = V.inventory;
    if (!inventory) return null;
    const list = Array.isArray(inventory.components) ? inventory.components : [];
    const rows: HTMLElement[] = [
      h('div', {
        class: 'vuln-note',
        text: 'Read from your project. Until a scan is allowed and run, this list has not left this machine.',
      }),
      h(
        'div',
        { class: 'stat-row' },
        h('span', { class: 'stat-label', text: 'Destination' }),
        h('span', { class: 'stat-value', text: text(inventory.endpoint) })
      ),
    ];
    for (let i = 0; i < list.length; i++) {
      const c = list[i] || {};
      rows.push(
        h(
          'div',
          { class: 'vuln-inv-row' },
          h('span', { class: 'vuln-inv-eco', text: text(c.ecosystem) }),
          h('span', { class: 'vuln-inv-name', text: text(c.name) }),
          h('span', { class: 'vuln-inv-version', text: text(c.version) }),
          h('span', { class: 'vuln-inv-origin', text: text(c.originLabel) })
        )
      );
    }
    if (inventory.truncated) {
      rows.push(
        h('div', {
          class: 'vuln-note',
          text: 'Showing ' + list.length + ' of ' + num(inventory.total) + '.',
        })
      );
    }
    return card('Exactly what would be sent', h('div', { class: 'vuln-inv' }, rows), true);
  }

  D.buildVulnCards = function (v: unknown): (HTMLElement | null)[] {
    const p = v as VulnPayload | null;
    if (!p || typeof p !== 'object' || p.available !== true) return [];
    const state = text(p.state);
    const out: (HTMLElement | null)[] = [];
    if (state === 'unconsented' || state === 'withdrawn') {
      out.push(V.consentCard(p, state));
    } else {
      out.push(V.statusCard(p, state));
      out.push(V.findingsCard(p));
    }
    out.push(inventoryCard());
    return out;
  };

  cc.vulnInventory = function (payload?: unknown): void {
    V.inventory = payload && typeof payload === 'object' ? (payload as VulnInventory) : null;
    V.repaint();
    V.announce('Showing the list that would be sent');
  };
})();
