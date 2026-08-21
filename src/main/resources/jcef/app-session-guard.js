(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;
  var send = D.send;
  var card = D.card;

  var MAX_ROWS = 100;

  var VERDICT_CLASS = {
    DENIED: 'guard-verdict guard-denied',
    ASKED: 'guard-verdict guard-asked',
    ALLOWED: 'guard-verdict guard-allowed',
  };

  var payload = null;
  var tab = 'blocked';
  var open = false;

  function text(value, fallback) {
    return value == null || value === '' ? fallback : String(value);
  }

  function list(value) {
    return Array.isArray(value) ? value.filter(Boolean) : [];
  }

  function num(value) {
    return typeof value === 'number' && isFinite(value) ? value : 0;
  }

  function tabs() {
    return list(payload && payload.tabs);
  }

  function entriesFor(id) {
    return list(payload && payload.entries).filter(function (e) {
      return text(e.tab, '') === id;
    });
  }

  function knownTab(id) {
    var found = false;
    tabs().forEach(function (t) {
      if (text(t.id, '') === id) found = true;
    });
    return found;
  }

  function currentTab() {
    return knownTab(tab) ? tab : text(tabs().length ? tabs()[0].id : '', 'blocked');
  }

  function when(at) {
    var n = num(at);
    if (!n) return '';
    try {
      return new Date(n).toLocaleString();
    } catch (e) {
      return String(n);
    }
  }

  function requestLog() {
    send({ type: 'guardLog' });
  }

  function buildStateCard() {
    var p = payload || {};
    var w = (p && p.window) || {};
    var rows = [];

    if (p.recording === false) {
      rows.push(
        h('div', {
          class: 'guard-alarm',
          attrs: { role: 'alert' },
          text:
            'Alerts are NOT being written down. The guard is still deciding, but nothing it decides ' +
            'is reaching the log, and nothing said so until now. What you see below is whatever was ' +
            'stored before that started — it is not this session.',
        })
      );
    }

    rows.push(
      h('div', {
        class: 'guard-note',
        text:
          'Showing ' +
          num(w.kept) +
          ' alert(s) from this chat. The log keeps the last ' +
          num(w.max) +
          ' for the whole project, not per chat — a busy chat pushes another chat’s alerts out.',
      })
    );

    if (num(w.dropped) > 0) {
      rows.push(
        h('div', {
          class: 'guard-alarm',
          attrs: { role: 'alert' },
          text:
            num(w.dropped) +
            ' alert(s) from this chat were dropped on the way to the log and cannot be recovered.',
        })
      );
    }

    if (num(w.missing) > 0) {
      rows.push(
        h('div', {
          class: 'guard-note',
          text:
            num(w.missing) +
            ' alert(s) this chat recorded are not in this list — pushed out by newer ones, refused ' +
            'by the password safe, still being written, or filed under no session id.',
        })
      );
    }

    return card('Guard log', h('div', { class: 'guard-state' }, rows), true, 'guard-state');
  }

  function tabButton(spec) {
    var id = text(spec.id, '');
    var active = id === currentTab();
    return h('button', {
      class: 'guard-tab' + (active ? ' active' : ''),
      attrs: {
        type: 'button',
        'data-guard-tab': id,
        'aria-current': active ? 'true' : null,
      },
      text: text(spec.label, id) + ' (' + num(spec.count) + ')',
      on: {
        click: function (ev) {
          ev.preventDefault();
          setTab(id);
        },
      },
    });
  }

  function tabStrip() {
    return h(
      'div',
      { class: 'guard-tabs', attrs: { role: 'group', 'aria-label': 'Guard log' } },
      tabs().map(tabButton)
    );
  }

  function detailRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'guard-detail' },
      h('span', { class: 'guard-detail-label', text: label }),
      h('span', { class: 'guard-detail-value', text: String(value) })
    );
  }

  function commandRow(command) {
    if (command == null || command === '') return null;
    return h('pre', { class: 'guard-cmd' }, h('code', { text: String(command) }));
  }

  function askButton(entry) {
    if (!entry.explainable) return null;
    return h(
      'div',
      { class: 'guard-entry-actions' },
      h('button', {
        class: 'guard-ask',
        attrs: { type: 'button' },
        text: 'Ask Claude why',
        on: {
          click: function (ev) {
            ev.preventDefault();
            send({ type: 'guardExplain', id: text(entry.id, '') });
          },
        },
      })
    );
  }

  function entryNode(entry) {
    var head = h(
      'div',
      { class: 'guard-entry-head' },
      h('span', {
        class: VERDICT_CLASS[text(entry.verdict, '')] || 'guard-verdict',
        text: text(entry.verdictLabel, text(entry.verdict, '—')),
      }),
      h('span', { class: 'guard-rule', text: text(entry.ruleLabel, text(entry.rule, 'Unknown rule')) }),
      h('span', { class: 'guard-when', text: when(entry.at) })
    );
    return h(
      'div',
      { class: 'guard-entry' },
      head,
      detailRow('Category', text(entry.category, null)),
      detailRow('Tool', text(entry.tool, null)),
      detailRow('Matched', text(entry.detail, null)),
      detailRow('Allowed by', text(entry.viaLabel, null)),
      commandRow(entry.command),
      askButton(entry)
    );
  }

  function buildEntriesCard() {
    var id = currentTab();
    var rows = entriesFor(id);
    var shown = rows.slice(0, MAX_ROWS);
    var body = [tabStrip()];

    if (!shown.length) {
      body.push(h('div', { class: 'guard-empty', text: 'Nothing in this chat landed here.' }));
    } else {
      body.push(h('div', { class: 'guard-list' }, shown.map(entryNode)));
    }

    if (rows.length > shown.length) {
      body.push(
        h('div', {
          class: 'guard-note',
          text: 'Showing the newest ' + shown.length + ' of ' + rows.length + '.',
        })
      );
    }

    return card('Decisions', body, true, 'guard-entries');
  }

  function setTab(id) {
    if (tab === id) return;
    tab = id;
    if (typeof D.repaintGuard === 'function') D.repaintGuard();
    var c = D.core();
    if (c && typeof c.announce === 'function') c.announce(text(id, 'guard') + ' guard entries');
  }

  D.buildGuardCards = function () {
    if (!payload) {
      return [
        card(
          'Guard log',
          h('div', { class: 'guard-note', text: 'Reading the guard log…' }),
          true,
          'guard-state'
        ),
      ];
    }
    return [buildStateCard(), buildEntriesCard()];
  };

  D.guardTab = function () {
    return currentTab();
  };

  D.guardVisible = function (visible) {
    var next = !!visible;
    if (next === open) return;
    open = next;
    if (open) requestLog();
  };

  cc.guard = function (data) {
    payload = data && typeof data === 'object' ? data : null;
    if (typeof D.repaintGuard === 'function') D.repaintGuard();
  };
})();
