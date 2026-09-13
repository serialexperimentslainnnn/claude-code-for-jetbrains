(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));

  function keyOf(node: Element | null | undefined): string | null {
    return node && node.getAttribute ? node.getAttribute('data-card') : null;
  }

  D.reconcile = function (container: HTMLElement, cards: HTMLElement[]): void {
    const existing: Record<string, Element> = Object.create(null);
    let i: number;
    for (i = 0; i < container.children.length; i++) {
      const key = keyOf(container.children[i]);
      if (key != null) existing[key] = container.children[i];
    }

    const ordered: Element[] = [];
    for (i = 0; i < cards.length; i++) {
      const next = cards[i];
      const key = keyOf(next);
      const previous = key != null ? existing[key] : undefined;
      ordered.push(previous && previous.isEqualNode(next) ? previous : next);
    }

    for (i = container.children.length - 1; i >= 0; i--) {
      if (ordered.indexOf(container.children[i]) < 0) container.removeChild(container.children[i]);
    }

    for (i = 0; i < ordered.length; i++) {
      if (container.children[i] !== ordered[i])
        container.insertBefore(ordered[i], container.children[i] || null);
    }
  };
})();
