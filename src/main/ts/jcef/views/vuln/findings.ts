(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const V = (D.vuln = D.vuln || ({} as VulnNs));
  const h = D.h;
  const send = D.send;
  const card = D.card;
  const text = V.text;
  const num = V.num;
  const button = V.button;

  function isWebUrl(url: unknown): boolean {
    return /^https?:\/\//i.test(String(url || ''));
  }

  function toggleTier(tier: string): void {
    const at = V.pickedTiers.indexOf(tier);
    if (at >= 0) V.pickedTiers.splice(at, 1);
    else V.pickedTiers.push(tier);
    V.repaint();
  }

  function showsTier(tier: string): boolean {
    return !V.pickedTiers.length || V.pickedTiers.indexOf(tier) >= 0;
  }

  function countsRow(counts: unknown): HTMLElement | null {
    if (!Array.isArray(counts) || !counts.length) return null;
    const chips: HTMLElement[] = [];
    for (let i = 0; i < counts.length; i++) {
      const c = (counts[i] || {}) as { tier?: unknown; label?: unknown; count?: unknown };
      chips.push(tierButton(text(c.tier), text(c.label), num(c.count)));
    }
    return h(
      'div',
      { class: 'vuln-counts', attrs: { role: 'group', 'aria-label': 'Filter findings by severity' } },
      chips
    );
  }

  function tierButton(tier: string, label: string, count: number): HTMLElement {
    const on = V.pickedTiers.indexOf(tier) >= 0;
    return h('button', {
      class: 'vuln-tier vuln-tier-filter' + (on ? ' picked' : ''),
      dataset: { tier: tier },
      attrs: { type: 'button', 'aria-pressed': on ? 'true' : 'false' },
      text: label + ' · ' + count,
      on: {
        click: function (ev: Event) {
          ev.preventDefault();
          toggleTier(tier);
        },
      },
    });
  }

  function planButton(shown: VulnFinding[]): HTMLElement | null {
    if (!shown.length) return null;
    return button('Plan with Claude to solve everything', 'btn primary', function () {
      send({ type: 'vulnPlan', tiers: V.pickedTiers.slice() });
      if (typeof D.leaveDashboard === 'function') D.leaveDashboard();
    });
  }

  function referenceList(f: VulnFinding): HTMLElement | null {
    const refs = Array.isArray(f.references) ? f.references : [];
    const links: HTMLElement[] = [];
    for (let i = 0; i < refs.length; i++) {
      if (!isWebUrl(refs[i])) continue;
      links.push(
        h('li', { class: 'vuln-ref' }, h('a', { attrs: { href: String(refs[i]) }, text: String(refs[i]) }))
      );
    }
    if (!links.length) return null;
    return h('ul', { class: 'vuln-refs' }, links);
  }

  function detailBlock(f: VulnFinding): HTMLElement | null {
    if (!f.details) return null;
    const el = h('div', { class: 'vuln-details' });
    el.innerHTML = CC.markdown(String(f.details));
    return el;
  }

  function findingActions(f: VulnFinding): HTMLElement {
    const key = text(f.id);
    const open = !!V.expanded[key];
    return h(
      'div',
      { class: 'vuln-actions' },
      button('Ask Claude to update this dependency', 'btn primary', function () {
        send({ type: 'vulnFix', findingId: key });
        if (typeof D.leaveDashboard === 'function') D.leaveDashboard();
      }),
      button(open ? 'Hide advisory' : 'Read advisory', 'btn ghost', function () {
        V.expanded[key] = !open;
        V.repaint();
      })
    );
  }

  function fixedLine(f: VulnFinding): HTMLElement {
    const fixed = Array.isArray(f.fixed) ? f.fixed : [];
    if (!fixed.length) return h('div', { class: 'vuln-fixed', text: 'No patched version is published.' });
    return h('div', { class: 'vuln-fixed', text: 'Patched in ' + fixed.join(', ') });
  }

  function findingRow(f: VulnFinding): HTMLElement {
    const parts: (HTMLElement | null)[] = [
      h(
        'div',
        { class: 'vuln-finding-head' },
        h('span', { class: 'vuln-tier', dataset: { tier: text(f.tier) }, text: text(f.tierLabel) }),
        h('span', { class: 'vuln-pkg', text: text(f.name) + '@' + text(f.version) }),
        h('span', { class: 'vuln-id', text: text(f.id) })
      ),
      h('div', {
        class: 'vuln-where',
        text: text(f.ecosystem) + ' · ' + text(f.originLabel) + ' · ' + text(f.manifest),
      }),
    ];
    if (f.summary) parts.push(h('div', { class: 'vuln-summary', text: String(f.summary) }));
    if (f.cvss) {
      parts.push(h('div', { class: 'vuln-cvss', text: text(f.cvssType) + ' ' + text(f.cvss) }));
    }
    parts.push(fixedLine(f));
    parts.push(findingActions(f));
    if (V.expanded[text(f.id)]) {
      parts.push(detailBlock(f));
      parts.push(referenceList(f));
    }
    return h('div', { class: 'vuln-finding', dataset: { tier: text(f.tier) } }, parts);
  }

  V.findingsCard = function (v: VulnPayload): HTMLElement | null {
    const r = v.report;
    if (!r || typeof r !== 'object') return null;
    const list = Array.isArray(r.findings) ? r.findings : [];
    if (!list.length) {
      return card(
        'Findings',
        h('div', { class: 'vuln-clean', text: 'No advisory matched ' + num(r.queried) + ' components.' }),
        true
      );
    }
    const shown = list.filter(function (f) {
      return showsTier(text(f.tier));
    });
    const body: (HTMLElement | null)[] = [countsRow(r.counts), planButton(shown)];
    for (let i = 0; i < shown.length; i++) body.push(findingRow(shown[i]));
    if (!shown.length) {
      body.push(h('div', { class: 'vuln-note', text: 'No finding matches the severities you picked.' }));
    } else if (num(r.total) > num(r.shown)) {
      body.push(
        h('div', {
          class: 'vuln-note',
          text: 'Showing ' + num(r.shown) + ' of ' + num(r.total) + ' findings.',
        })
      );
    }
    return card('Findings', body, true);
  };
})();
