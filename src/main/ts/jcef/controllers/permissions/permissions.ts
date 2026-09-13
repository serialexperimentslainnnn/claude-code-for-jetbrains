(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const PM = (CC.permissions = CC.permissions || ({} as PermissionsNs));

  PM.buildCard = function (card: unknown): HTMLElement | null {
    if (!card || typeof card !== 'object') return null;
    const c = card as PermissionCard;
    if (Array.isArray(c.questions) && c.questions.length) return PM.buildQuestionCard(c);
    if (c.elicitation) return PM.buildElicitCard(c);
    if (c.isPlan) return PM.buildPlanCard(c);
    return PM.buildPermCard(c);
  };

  const pendingCounts = new WeakMap<HTMLElement, number>();
  function announcePending(list: PermissionCard[], region: HTMLElement): void {
    if (typeof CC.announce !== 'function') return;
    const count = list.length;
    const previous = pendingCounts.get(region) || 0;
    pendingCounts.set(region, count);
    if (count === 0 || count <= previous) return;
    if (count === 1) {
      const only = list[0] || {};
      const tool = only.tool ? String(only.tool) : '';
      if (only.questions) CC.announce('Claude is asking you a question.');
      else if (only.isPlan) CC.announce('Claude is proposing a plan for your approval.');
      else if (only.elicitation) CC.announce('An MCP server is requesting input.');
      else
        CC.announce(
          tool ? 'Claude needs your permission to use ' + tool + '.' : 'Claude needs your response.'
        );
      return;
    }
    CC.announce(count + ' requests are waiting for your response.');
  }

  function permissions(list: unknown, into?: HTMLElement | null): void {
    const region = into || PM.mount();
    if (!region) return;
    const cards: PermissionCard[] = Array.isArray(list) ? (list as PermissionCard[]) : [];
    announcePending(cards, region);

    const existing: Record<string, Element> = {};
    const n = region.children.length;
    for (let i = 0; i < n; i++) {
      const node0 = region.children[i];
      const cid = node0.getAttribute ? node0.getAttribute('data-card-id') : null;
      if (cid != null) existing[cid] = node0;
    }

    const wanted: Record<string, boolean> = {};
    const ordered: Element[] = [];
    for (let j = 0; j < cards.length; j++) {
      const card = cards[j];
      if (!card || card.id == null) continue;
      const key = String(card.id);
      wanted[key] = true;
      let node: Element | null = existing[key];
      if (!node) {
        node = PM.buildCard(card);
        if (node) node.setAttribute('data-card-id', key);
      }
      if (node) ordered.push(node);
    }

    for (let k = region.children.length - 1; k >= 0; k--) {
      const child = region.children[k];
      const ck = child.getAttribute ? child.getAttribute('data-card-id') : null;
      if (ck == null || !wanted[ck]) region.removeChild(child);
    }

    for (let m = 0; m < ordered.length; m++) {
      if (region.children[m] !== ordered[m]) region.insertBefore(ordered[m], region.children[m] || null);
    }
  }

  cc.permissions = permissions;
  PM.render = permissions;
})();
