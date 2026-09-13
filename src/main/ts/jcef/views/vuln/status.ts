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

  function whenText(ms: unknown): string {
    const at = num(ms);
    if (!at) return 'an unknown time';
    try {
      return new Date(at).toLocaleString();
    } catch (e) {
      return String(at);
    }
  }

  function agoText(ms: number): string {
    const mins = Math.floor(num(ms) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    return Math.floor(hours / 24) + 'd ago';
  }

  V.bullets = function (title: string, list: unknown): HTMLElement | null {
    if (!Array.isArray(list) || !list.length) return null;
    const items: HTMLElement[] = [];
    for (let i = 0; i < list.length; i++) {
      items.push(h('li', { class: 'vuln-list-item', text: text(list[i]) }));
    }
    return h(
      'div',
      { class: 'vuln-block' },
      h('div', { class: 'vuln-block-title', text: title }),
      h('ul', { class: 'vuln-list' }, items)
    );
  };

  function statRow(label: string, value: string): HTMLElement {
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: value })
    );
  }

  V.consentCard = function (v: VulnPayload, state: string): HTMLElement | null {
    const d = v.disclosure || {};
    const lede =
      state === 'withdrawn'
        ? 'You withdrew consent for this project. Nothing has been sent since, and the last result was dropped.'
        : 'Checking this project against a vulnerability database means sending its dependency inventory to a third party. That has not happened, and it will not happen until you allow it here.';
    const body = [
      h('div', { class: 'vuln-lede', text: lede }),
      statRow('Would be sent to', text(v.operator)),
      statRow('Endpoint', text(v.endpoint)),
      statRow('Components', String(num(V.inv(v).components))),
      V.bullets('What leaves this machine', d.sent),
      V.bullets('What that means', d.caveats),
      h(
        'div',
        { class: 'vuln-actions' },
        button('Allow and scan now', 'btn primary', function () {
          send({ type: 'vulnConsent', granted: true });
          send({ type: 'vulnScan' });
          V.announce('Scanning dependencies');
        }),
        V.inventoryButton(v)
      ),
    ];
    return card('Dependency vulnerabilities', body, true, 'vuln');
  };

  const LEDE: Record<string, string> = {
    never: 'Nothing has been sent yet. Scanning sends the inventory below, and only that.',
    scanning:
      'Sending the inventory. Cancelling stops it; whatever has already been sent cannot be recalled.',
    results: 'The last scan completed.',
    offline: 'The last scan did not complete, so this is the previous result.',
    failed: 'The last scan produced nothing.',
  };

  function progressRow(v: VulnPayload): HTMLElement {
    const p = v.progress || {};
    const done = num(p.done);
    const total = num(p.total);
    const pct = total > 0 ? Math.min(100, (done / total) * 100) : 0;
    return h(
      'div',
      { class: 'vuln-progress' },
      h(
        'div',
        {
          class: 'vuln-track',
          attrs: {
            role: 'progressbar',
            'aria-label': 'Scan progress',
            'aria-valuemin': '0',
            'aria-valuemax': String(total),
            'aria-valuenow': String(done),
          },
        },
        h('div', { class: 'vuln-fill', style: { width: pct.toFixed(1) + '%' } })
      ),
      h('div', { class: 'vuln-count', text: done + ' of ' + total + ' components' })
    );
  }

  V.statusCard = function (v: VulnPayload, state: string): HTMLElement | null {
    const body: (HTMLElement | null)[] = [
      h(
        'div',
        { class: 'vuln-state', dataset: { status: text(v.status) } },
        h('span', { class: 'vuln-dot' }),
        h('span', { class: 'vuln-lede', text: LEDE[state] || LEDE.never })
      ),
    ];
    if (state === 'scanning') body.push(progressRow(v));
    if (v.report) {
      body.push(
        h('div', {
          class: 'vuln-asof',
          text:
            'As of ' + whenText(v.report.asOfMillis) + ' · ' + agoText(Date.now() - num(v.report.asOfMillis)),
        })
      );
    }
    if (v.note && state !== 'scanning') body.push(h('div', { class: 'vuln-note', text: text(v.note) }));
    body.push(
      h(
        'div',
        { class: 'vuln-actions' },
        primaryAction(state),
        V.inventoryButton(v),
        button('Withdraw consent', 'btn ghost', function () {
          send({ type: 'vulnConsent', granted: false });
          V.announce('Consent withdrawn');
        })
      )
    );
    return card('Dependency vulnerabilities', body, true, 'vuln');
  };

  function primaryAction(state: string): HTMLElement {
    if (state === 'scanning') {
      return button('Cancel', 'btn danger', function () {
        send({ type: 'vulnCancel' });
        V.announce('Cancelling the scan');
      });
    }
    const label = state === 'never' ? 'Scan now' : 'Scan again';
    return button(label, 'btn primary', function () {
      send({ type: 'vulnScan' });
      V.announce('Scanning dependencies');
    });
  }
})();
