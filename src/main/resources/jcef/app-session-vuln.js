(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  var inventory = null;
  var expanded = {};

  function text(v) {
    return v === null || v === undefined ? '' : String(v);
  }

  function num(v) {
    return typeof v === 'number' && isFinite(v) ? v : 0;
  }

  function repaint() {
    if (typeof D.repaint === 'function') D.repaint();
  }

  function announce(message) {
    if (CC && typeof CC.announce === 'function') CC.announce(message);
  }

  function isWebUrl(url) {
    return /^https?:\/\//i.test(String(url || ''));
  }

  function whenText(ms) {
    var at = num(ms);
    if (!at) return 'an unknown time';
    try {
      return new Date(at).toLocaleString();
    } catch (e) {
      return String(at);
    }
  }

  function agoText(ms) {
    var mins = Math.floor(num(ms) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + 'm ago';
    var hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    return Math.floor(hours / 24) + 'd ago';
  }

  function inv(v) {
    return (v && v.inventory) || {};
  }

  function bullets(title, list) {
    if (!Array.isArray(list) || !list.length) return null;
    var items = [];
    for (var i = 0; i < list.length; i++) {
      items.push(h('li', { class: 'vuln-list-item', text: text(list[i]) }));
    }
    return h(
      'div',
      { class: 'vuln-block' },
      h('div', { class: 'vuln-block-title', text: title }),
      h('ul', { class: 'vuln-list' }, items)
    );
  }

  function button(label, variant, onPress) {
    return h('button', {
      class: variant,
      title: label,
      attrs: { type: 'button', 'aria-label': label },
      text: label,
      on: {
        click: function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          onPress();
        },
      },
    });
  }

  function inventoryButton(v) {
    if (inventory) {
      return button('Hide the list', 'btn ghost', function () {
        inventory = null;
        repaint();
      });
    }
    var count = num(inv(v).components);
    return button('Show the exact list that would be sent (' + count + ')', 'btn ghost', function () {
      send({ type: 'vulnInventory' });
    });
  }

  function consentCard(v, state) {
    var d = v.disclosure || {};
    var lede =
      state === 'withdrawn'
        ? 'You withdrew consent for this project. Nothing has been sent since, and the last result was dropped.'
        : 'Checking this project against a vulnerability database means sending its dependency inventory to a third party. That has not happened, and it will not happen until you allow it here.';
    var body = [
      h('div', { class: 'vuln-lede', text: lede }),
      h(
        'div',
        { class: 'stat-row' },
        h('span', { class: 'stat-label', text: 'Would be sent to' }),
        h('span', { class: 'stat-value', text: text(v.operator) })
      ),
      h(
        'div',
        { class: 'stat-row' },
        h('span', { class: 'stat-label', text: 'Endpoint' }),
        h('span', { class: 'stat-value', text: text(v.endpoint) })
      ),
      h(
        'div',
        { class: 'stat-row' },
        h('span', { class: 'stat-label', text: 'Components' }),
        h('span', { class: 'stat-value', text: String(num(inv(v).components)) })
      ),
      bullets('What leaves this machine', d.sent),
      bullets('What that means', d.caveats),
      h(
        'div',
        { class: 'vuln-actions' },
        button('Allow and scan now', 'btn primary', function () {
          send({ type: 'vulnConsent', granted: true });
          send({ type: 'vulnScan' });
          announce('Scanning dependencies');
        }),
        inventoryButton(v)
      ),
    ];
    return card('Dependency vulnerabilities', body, true, 'vuln');
  }

  var LEDE = {
    never: 'Nothing has been sent yet. Scanning sends the inventory below, and only that.',
    scanning:
      'Sending the inventory. Cancelling stops it; whatever has already been sent cannot be recalled.',
    results: 'The last scan completed.',
    offline: 'The last scan did not complete, so this is the previous result.',
    failed: 'The last scan produced nothing.',
  };

  function progressRow(v) {
    var p = v.progress || {};
    var done = num(p.done);
    var total = num(p.total);
    var pct = total > 0 ? Math.min(100, (done / total) * 100) : 0;
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

  function statusCard(v, state) {
    var body = [
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
          text: 'As of ' + whenText(v.report.asOfMillis) + ' · ' + agoText(Date.now() - num(v.report.asOfMillis)),
        })
      );
    }
    if (v.note && state !== 'scanning') body.push(h('div', { class: 'vuln-note', text: text(v.note) }));
    body.push(
      h(
        'div',
        { class: 'vuln-actions' },
        primaryAction(state),
        inventoryButton(v),
        button('Withdraw consent', 'btn ghost', function () {
          send({ type: 'vulnConsent', granted: false });
          announce('Consent withdrawn');
        })
      )
    );
    return card('Dependency vulnerabilities', body, true, 'vuln');
  }

  function primaryAction(state) {
    if (state === 'scanning') {
      return button('Cancel', 'btn danger', function () {
        send({ type: 'vulnCancel' });
        announce('Cancelling the scan');
      });
    }
    var label = state === 'never' ? 'Scan now' : 'Scan again';
    return button(label, 'btn primary', function () {
      send({ type: 'vulnScan' });
      announce('Scanning dependencies');
    });
  }

  function countsRow(counts) {
    if (!Array.isArray(counts) || !counts.length) return null;
    var chips = [];
    for (var i = 0; i < counts.length; i++) {
      var c = counts[i] || {};
      chips.push(
        h('span', {
          class: 'vuln-tier',
          dataset: { tier: text(c.tier) },
          text: text(c.label) + ' · ' + num(c.count),
        })
      );
    }
    return h('div', { class: 'vuln-counts' }, chips);
  }

  function referenceList(f) {
    var refs = Array.isArray(f.references) ? f.references : [];
    var links = [];
    for (var i = 0; i < refs.length; i++) {
      if (!isWebUrl(refs[i])) continue;
      links.push(
        h('li', { class: 'vuln-ref' }, h('a', { attrs: { href: String(refs[i]) }, text: String(refs[i]) }))
      );
    }
    if (!links.length) return null;
    return h('ul', { class: 'vuln-refs' }, links);
  }

  function detailBlock(f) {
    if (!f.details) return null;
    var el = h('div', { class: 'vuln-details' });
    el.innerHTML = CC.markdown(String(f.details));
    return el;
  }

  function findingActions(f) {
    var open = !!expanded[f.id];
    return h(
      'div',
      { class: 'vuln-actions' },
      button('Ask Claude to update this dependency', 'btn primary', function () {
        send({ type: 'vulnFix', findingId: text(f.id) });
        if (typeof D.leaveDashboard === 'function') D.leaveDashboard();
      }),
      button(open ? 'Hide advisory' : 'Read advisory', 'btn ghost', function () {
        expanded[f.id] = !open;
        repaint();
      })
    );
  }

  function fixedLine(f) {
    var fixed = Array.isArray(f.fixed) ? f.fixed : [];
    if (!fixed.length) return h('div', { class: 'vuln-fixed', text: 'No patched version is published.' });
    return h('div', { class: 'vuln-fixed', text: 'Patched in ' + fixed.join(', ') });
  }

  function findingRow(f) {
    var parts = [
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
    if (expanded[f.id]) {
      parts.push(detailBlock(f));
      parts.push(referenceList(f));
    }
    return h('div', { class: 'vuln-finding', dataset: { tier: text(f.tier) } }, parts);
  }

  function findingsCard(v) {
    var r = v.report;
    if (!r || typeof r !== 'object') return null;
    var list = Array.isArray(r.findings) ? r.findings : [];
    if (!list.length) {
      return card(
        'Findings',
        h('div', { class: 'vuln-clean', text: 'No advisory matched ' + num(r.queried) + ' components.' }),
        true
      );
    }
    var body = [countsRow(r.counts)];
    for (var i = 0; i < list.length; i++) body.push(findingRow(list[i]));
    if (num(r.total) > num(r.shown)) {
      body.push(
        h('div', {
          class: 'vuln-note',
          text: 'Showing ' + num(r.shown) + ' of ' + num(r.total) + ' findings.',
        })
      );
    }
    return card('Findings', body, true);
  }

  function inventoryCard() {
    if (!inventory) return null;
    var list = Array.isArray(inventory.components) ? inventory.components : [];
    var rows = [
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
    for (var i = 0; i < list.length; i++) {
      var c = list[i] || {};
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

  D.buildVulnCards = function (v) {
    if (!v || typeof v !== 'object' || v.available !== true) return [];
    var state = text(v.state);
    var out = [];
    if (state === 'unconsented' || state === 'withdrawn') {
      out.push(consentCard(v, state));
    } else {
      out.push(statusCard(v, state));
      out.push(findingsCard(v));
    }
    out.push(inventoryCard());
    return out;
  };

  var cc = window.cc || (window.cc = {});

  cc.vulnInventory = function (payload) {
    inventory = payload && typeof payload === 'object' ? payload : null;
    repaint();
    announce('Showing the list that would be sent');
  };
})();
