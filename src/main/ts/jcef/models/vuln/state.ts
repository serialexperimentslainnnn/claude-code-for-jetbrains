(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const V = (D.vuln = D.vuln || ({} as VulnNs));
  const h = D.h;
  const send = D.send;

  V.inventory = null;
  V.expanded = {};
  V.pickedTiers = [];

  V.text = function (v: unknown): string {
    return v === null || v === undefined ? '' : String(v);
  };

  V.num = function (v: unknown): number {
    return typeof v === 'number' && isFinite(v) ? v : 0;
  };

  V.repaint = function (): void {
    if (typeof D.repaint === 'function') D.repaint();
  };

  V.announce = function (message: string): void {
    if (CC && typeof CC.announce === 'function') CC.announce(message);
  };

  V.inv = function (v: VulnPayload): { components?: unknown } {
    return (v && v.inventory) || {};
  };

  V.button = function (label: string, variant: string, onPress: () => void): HTMLElement {
    return h('button', {
      class: variant,
      title: label,
      attrs: { type: 'button', 'aria-label': label },
      text: label,
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          ev.stopPropagation();
          onPress();
        },
      },
    });
  };

  V.inventoryButton = function (v: VulnPayload): HTMLElement {
    if (V.inventory) {
      return V.button('Hide the list', 'btn ghost', function () {
        V.inventory = null;
        V.repaint();
      });
    }
    const count = V.num(V.inv(v).components);
    return V.button('Show the exact list that would be sent (' + count + ')', 'btn ghost', function () {
      send({ type: 'vulnInventory' });
    });
  };
})();
