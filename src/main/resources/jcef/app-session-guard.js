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
  var queryRaw = '';
  var query = '';
  var pickedCategories = null;
  var pickedRules = null;

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

  function catalog() {
    return list(payload && payload.catalog);
  }

  function rulesOfCategories(picked) {
    var out = [];
    catalog().forEach(function (c) {
      if (picked && picked.indexOf(text(c.id, '')) < 0) return;
      list(c.rules).forEach(function (r) {
        out.push({ id: text(r.id, ''), label: text(r.label, text(r.id, '')) });
      });
    });
    return out;
  }

  function matchesQuery(entry) {
    if (!query) return true;
    var hay = [entry.ruleLabel, entry.category, entry.command, entry.detail, entry.tool, entry.verdictLabel]
      .map(function (v) {
        return text(v, '').toLowerCase();
      })
      .join(' ');
    return hay.indexOf(query) >= 0;
  }

  function matchesFilters(entry) {
    if (pickedCategories && pickedCategories.indexOf(text(entry.categoryId, '')) < 0) return false;
    if (pickedRules && pickedRules.indexOf(text(entry.rule, '')) < 0) return false;
    return true;
  }

  function visibleEntries(id) {
    return entriesFor(id).filter(function (e) {
      return matchesQuery(e) && matchesFilters(e);
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

  var ALL = '__all__';

  var DASHBOARD_ID = 'cc-dashboard';

  function toggled(picked, value) {
    if (value === ALL) return null;
    var next = (picked || []).slice();
    var at = next.indexOf(value);
    if (at >= 0) next.splice(at, 1);
    else next.push(value);
    return next.length ? next : null;
  }

  function multiSelect(label, options, pickedOf, onPick) {
    var core = D.core();
    if (!core || typeof core.pickMenu !== 'function') return null;

    var trigger = h('button', {
      class: 'guard-filter-trigger',
      attrs: { type: 'button', 'aria-haspopup': 'menu', 'aria-expanded': 'false' },
      text: label,
    });
    var wrapper = h(
      'div',
      { class: 'guard-filter' },
      h('span', { class: 'guard-filter-label', text: label }),
      trigger
    );

    var items = [{ value: ALL, label: 'All' }].concat(
      options.map(function (opt) {
        return { value: opt.id, label: opt.label };
      })
    );

    var menu = core.pickMenu({
      anchor: trigger,
      home: wrapper,
      label: label,
      checkable: true,
      menuClass: 'guard-disable-menu guard-filter-menu',
      itemClass: 'guard-disable-option',
      items: items,
      checkedOf: function (value) {
        var picked = pickedOf();
        return value === ALL ? !picked : !!picked && picked.indexOf(value) >= 0;
      },
      onPick: function (value) {
        onPick(toggled(pickedOf(), value));
      },
      watch: function () {
        return document.getElementById(DASHBOARD_ID);
      },
    });
    menu.sync();

    trigger.addEventListener('click', function (ev) {
      ev.preventDefault();
      menu.toggle();
    });

    return wrapper;
  }

  function searchBox() {
    var input = h('input', {
      class: 'guard-search',
      attrs: {
        type: 'search',
        placeholder: 'rule, command, tool…',
        'aria-label': 'Search the guard log',
      },
      on: {
        input: function (ev) {
          queryRaw = String(ev.currentTarget.value || '');
          query = queryRaw.trim().toLowerCase();
          if (typeof D.repaintGuard === 'function') D.repaintGuard();
        },
      },
    });
    input.value = queryRaw;
    return h(
      'label',
      { class: 'guard-filter guard-filter-search' },
      h('span', { class: 'guard-filter-label', text: 'Search' }),
      input
    );
  }

  function filterStrip() {
    var categories = catalog().map(function (c) {
      return { id: text(c.id, ''), label: text(c.label, text(c.id, '')) };
    });
    return h(
      'div',
      { class: 'guard-filters', attrs: { role: 'group', 'aria-label': 'Filter the guard log' } },
      searchBox(),
      multiSelect(
        'Category',
        categories,
        function () {
          return pickedCategories;
        },
        function (picked) {
          pickedCategories = picked;
          if (typeof D.repaintGuard === 'function') D.repaintGuard();
        }
      ),
      multiSelect(
        'Rule',
        rulesOfCategories(null),
        function () {
          return pickedRules;
        },
        function (picked) {
          pickedRules = picked;
          if (typeof D.repaintGuard === 'function') D.repaintGuard();
        }
      )
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

  function askAction(entry) {
    if (!entry.explainable) return null;
    return h('button', {
      class: 'guard-ask',
      attrs: { type: 'button' },
      text: 'Ask Claude why',
      on: {
        click: function (ev) {
          ev.preventDefault();
          send({ type: 'guardExplain', id: text(entry.id, '') });
        },
      },
    });
  }

  function whitelistAction(entry) {
    var command = text(entry.command, '');
    var rule = text(entry.rule, '');
    if (!command || !rule || text(entry.tab, '') === 'whitelisted') return null;
    return h('button', {
      class: 'guard-ask guard-whitelist',
      attrs: { type: 'button' },
      text: 'Whitelist',
      on: {
        click: function (ev) {
          ev.preventDefault();
          send({ type: 'guardWhitelist', rule: rule, command: command });
        },
      },
    });
  }

  function entryActions(entry) {
    var actions = [askAction(entry), whitelistAction(entry)].filter(Boolean);
    if (!actions.length) return null;
    return h('div', { class: 'guard-entry-actions' }, actions);
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
      entryActions(entry)
    );
  }

  function filtering() {
    return !!query || !!pickedCategories || !!pickedRules;
  }

  function buildFiltersCard() {
    if (!catalog().length) return null;
    return card('Filter', filterStrip(), true, 'guard-filters');
  }

  function buildEntriesCard() {
    var id = currentTab();
    var rows = visibleEntries(id);
    var shown = rows.slice(0, MAX_ROWS);
    var body = [tabStrip()];

    if (!shown.length) {
      body.push(
        h('div', {
          class: 'guard-empty',
          text: filtering()
            ? 'Nothing here matches the search and filters.'
            : 'Nothing in this chat landed here.',
        })
      );
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
    return [buildStateCard(), buildFiltersCard(), buildEntriesCard()];
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
