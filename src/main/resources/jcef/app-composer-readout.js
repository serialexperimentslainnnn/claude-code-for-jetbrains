(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var h = CX.h;

  CX.renderReadout = function (s) {
    var els = CX.els;
    if (!els || !els.readout) return;
    var ro = els.readout;
    ro.innerHTML = '';
    var running = !!s.turnActive;

    var status = h(
      'span',
      { class: 'ro-item strip-cell' },
      h('span', { class: 'ro-dot' + (running ? ' running' : '') }),
      h('span', { text: running ? (s.thinkingStatus ? s.thinkingStatus : 'Running…') : 'Idle' })
    );
    ro.appendChild(status);

    var ctxPct = s.context && typeof s.context.pct === 'number' ? Math.round(s.context.pct) : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: 'Context ' + ctxPct + '%' }));

    var out = typeof s.tokensOut === 'number' ? s.tokensOut : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(out) + ' out' }));

    var reasoning = typeof s.reasoningTokens === 'number' ? s.reasoningTokens : 0;
    ro.appendChild(h('span', { class: 'ro-item strip-cell', text: formatTokens(reasoning) + ' reasoning' }));

    if (typeof s.costUsd === 'number' && s.costUsd > 0) {
      ro.appendChild(
        h('span', { class: 'ro-item strip-cell', text: '$' + s.costUsd.toFixed(s.costUsd < 1 ? 4 : 2) })
      );
    }

    ro.removeAttribute('hidden');
    if (running && s.thinkingStatus) ro.classList.add('thinking');
    else ro.classList.remove('thinking');

    renderUsageBars(s);
    renderMini();
  };

  function renderUsageBars(s) {
    var els = CX.els;
    if (!els || !els.usageBars) return;
    var host = els.usageBars;
    host.innerHTML = '';
    var usage = Array.isArray(s.usage) ? s.usage : [];
    var shown = 0;
    for (var u = 0; u < usage.length; u++) {
      var win = usage[u] || {};
      if (typeof win.pct !== 'number') continue;
      var label = String(win.label || '');
      var pct = win.pct.toFixed(1) + '%';
      var fill = h('i', { class: usageLevel(win.pct) });
      fill.style.width = Math.max(0, Math.min(100, win.pct)) + '%';
      var reset = CC.resetInShort(win.resetsAt);
      var item = h(
        'div',
        {
          class: 'ub-item strip-cell',
          title: label + ' — ' + pct + ' used' + (reset ? ' · ' + CC.resetIn(win.resetsAt) : ''),
        },
        h(
          'div',
          { class: 'ub-row' },
          h('span', { class: 'ub-label', text: label }),
          h('span', { class: 'ub-track' }, fill),
          h('span', { class: 'ub-pct', text: pct }),
          h('span', { class: 'ub-reset', text: reset })
        )
      );
      host.appendChild(item);
      shown++;
    }
    if (shown > 0) host.removeAttribute('hidden');
    else host.setAttribute('hidden', 'hidden');
  }

  var MINI_ID = 'cc-session-mini';

  var READOUT_ID = 'cc-strip-readout';
  var BARS_ID = 'cc-strip-bars';
  var MORE_ID = 'cc-strip-more';

  var mini = null;

  function sessionPayload() {
    var d = CC.dash;
    return d && typeof d.lastSession === 'function' ? d.lastSession() : null;
  }

  (function watchSessionPayload() {
    var cc = window.cc || (window.cc = {});
    var present = typeof cc.session === 'function' ? cc.session : null;
    var inner = present && present.ccSessionTap ? null : present;
    function wrapper(payload) {
      if (inner) inner.call(cc, payload);
      renderMini();
    }
    wrapper.ccSessionTap = true;
    try {
      Object.defineProperty(cc, 'session', {
        configurable: true,
        enumerable: true,
        get: function () {
          return wrapper;
        },
        set: function (fn) {
          inner = typeof fn === 'function' ? fn : null;
        },
      });
    } catch (e) {}
  })();

  function ensureMini() {
    if (mini) return mini;
    var els = CX.els;
    var readout = els && els.readout;
    var bars = els && els.usageBars;
    if (!readout || !bars || !readout.parentNode) return null;

    var grid = h('div', { class: 'dash-mini-grid', attrs: { id: MINI_ID } });
    var root = h('div', { class: 'dash-mini', attrs: { hidden: 'hidden' } }, grid);

    var parent = readout.parentNode;
    parent.insertBefore(bars, readout);
    parent.insertBefore(root, readout);
    mini = { root: root, grid: grid };
    return mini;
  }

  function renderMini() {
    var m = ensureMini();
    if (!m) return;
    if (!sessionPayload()) {
      m.root.setAttribute('hidden', 'hidden');
      clearMini();
    } else {
      m.root.removeAttribute('hidden');
      drawMini();
    }
    syncFold();
  }

  function clearMini() {
    if (!mini) return;
    while (mini.grid.firstChild) mini.grid.removeChild(mini.grid.firstChild);
  }

  function fact(label, value) {
    if (value == null || value === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell', title: label + ': ' + value },
      h('span', { class: 'mini-key', text: label + ':' }),
      h('span', { class: 'mini-val', text: String(value) })
    );
  }

  function abbreviateHome(path, home) {
    if (!path || !home) return path;
    var root = String(home).replace(/\\/g, '/').replace(/\/+$/, '');
    if (!root || String(path).replace(/\\/g, '/').slice(0, root.length) !== root) return path;
    var rest = String(path).slice(root.length);
    if (rest === '') return '~';
    return /^[/\\]/.test(rest) ? '~' + rest : path;
  }

  function workingDirFact(cwd, home) {
    if (cwd == null || cwd === '') return null;
    return h(
      'span',
      { class: 'mini-fact strip-cell mini-fill', title: 'Working dir: ' + cwd },
      h('span', { class: 'mini-key', text: 'Working dir:' }),
      h('span', { class: 'mini-val', text: abbreviateHome(String(cwd), home) })
    );
  }

  function organizationWorthShowing(org, email) {
    if (!org || !email) return org;
    var value = String(org).trim();
    var owner = String(email).trim();
    if (value.slice(0, owner.length).toLowerCase() !== owner.toLowerCase()) return org;
    var tail = value
      .slice(owner.length)
      .trim()
      .replace(/^[^a-z0-9]+/i, '');
    return /^s?\s*(org|organi[sz]ations?)?$/i.test(tail) ? null : org;
  }

  function factLine(facts) {
    var kept = facts.filter(Boolean);
    return kept.length ? h('div', { class: 'mini-line' }, kept) : null;
  }

  function drawMini() {
    var m = mini;
    if (!m) return;
    var s = sessionPayload() || {};
    clearMini();

    var account = s.account || {};
    var lines = [
      factLine([fact('Model', s.model), workingDirFact(s.cwd, s.home)]),
      factLine([
        fact('Account', account.email),
        fact('Organization', organizationWorthShowing(account.org, account.email)),
        fact('Plan', account.plan),
        fact('Provider', account.provider),
      ]),
    ].filter(Boolean);

    if (!lines.length) {
      m.grid.appendChild(h('div', { class: 'mini-empty', text: 'No session data yet.' }));
    } else {
      lines.forEach(function (line) {
        m.grid.appendChild(line);
      });
    }
  }

  var expanded = false;

  var moreBtn = null;

  function foldableRow(host, selector) {
    return !!host && !host.hasAttribute('hidden') && host.querySelectorAll(selector).length > 2;
  }

  function anythingToFold() {
    var els = CX.els;
    if (!els) return false;
    if (foldableRow(els.readout, '.ro-item')) return true;
    if (foldableRow(els.usageBars, '.ub-item')) return true;
    if (!mini || mini.root.hasAttribute('hidden')) return false;
    var lines = mini.grid.querySelectorAll('.mini-line');
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].querySelectorAll('.mini-fact').length > 2) return true;
    }
    return false;
  }

  function buildMoreBtn() {
    var els = CX.els;
    var after = els && els.readout;
    if (!after || !after.parentNode) return null;
    var btn = h('button', {
      class: 'strip-more',
      attrs: {
        id: MORE_ID,
        type: 'button',
        'aria-expanded': 'false',
        'aria-controls': READOUT_ID + ' ' + MINI_ID + ' ' + BARS_ID,
      },
      on: {
        click: function () {
          expanded = !expanded;
          syncFold();
        },
      },
    });
    after.parentNode.insertBefore(btn, after.nextSibling);
    return btn;
  }

  function syncFold() {
    var els = CX.els;
    if (!els || !els.readout) return;
    els.readout.id = READOUT_ID;
    if (els.usageBars) els.usageBars.id = BARS_ID;

    var host = CC.els && CC.els.composer;
    if (host) host.classList.toggle('strip-open', expanded);

    if (!anythingToFold()) {
      if (moreBtn && moreBtn.parentNode) moreBtn.parentNode.removeChild(moreBtn);
      moreBtn = null;
      return;
    }
    if (!moreBtn) moreBtn = buildMoreBtn();
    if (!moreBtn) return;
    moreBtn.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    moreBtn.textContent = expanded ? 'Show less' : 'Show more';
  }

  function usageLevel(pct) {
    if (pct >= 85) return 'lvl-high';
    if (pct >= 65) return 'lvl-mid';
    return 'lvl-low';
  }

  function formatTokens(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }
})();
