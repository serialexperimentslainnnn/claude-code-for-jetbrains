(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});
  var h = D.h;

  var pane = null;
  var rowsEl = null;
  var statusEl = null;

  var rows = new Map();
  var cards = new Map();

  var last = null;
  var drawn = false;

  function tx() {
    return CC.transcript || null;
  }

  function gitChatPane() {
    if (pane) return pane;
    if (typeof h !== 'function') return null;

    rowsEl = h('div', {
      class: 'gitchat-rows',
      attrs: { role: 'log', 'aria-label': 'Git conversation', 'aria-live': 'off' },
    });
    statusEl = h('div', { class: 'gitchat-status', attrs: { role: 'status', 'aria-live': 'polite' } });

    pane = h(
      'div',
      { class: 'gitchat', attrs: { hidden: '' } },
      typeof D.gitViewTabs === 'function' ? D.gitViewTabs('chat') : null,
      rowsEl,
      statusEl
    );
    return pane;
  }

  CC.gitChatActive = function () {
    return (
      typeof D.gitSubView === 'function' &&
      D.gitSubView() === 'chat' &&
      typeof D.dashboardShown === 'function' &&
      D.dashboardShown()
    );
  };

  function draw() {
    if (!pane) return;
    drawn = true;
    var payload = last;

    if (!payload) {
      clearRows();
      setStatus('');
      return;
    }

    renderRows(Array.isArray(payload.rows) ? payload.rows : []);

    setStatus(payload.starting ? 'Starting Claude for this repository…' : '');
  }

  function setStatus(text) {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.hidden = !text;
  }

  function clearRows() {
    rows.clear();
    cards.clear();
    if (!rowsEl) return;
    while (rowsEl.firstChild) rowsEl.removeChild(rowsEl.firstChild);
  }

  function renderRows(entries) {
    var T = tx();
    if (!rowsEl || !T || typeof T.createRow !== 'function') return;

    var stick = nearBottom();
    var ordered = [];
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      if (!entry || entry.id == null) continue;

      if (entry.speaker === 'TOOL_OUTPUT' && T.routeToolOutput(entry, cards)) continue;

      var rec = rows.get(entry.id);
      if (rec && rec.speaker !== entry.speaker) {
        if (rec.el && rec.el.parentNode) rec.el.parentNode.removeChild(rec.el);
        rows.delete(entry.id);
        rec = null;
      }
      if (!rec) {
        rec = T.createRow(entry, cards);
        rows.set(entry.id, rec);
      }
      T.updateRow(rec, entry, false);
      if (rec.el) ordered.push(rec.el);
    }

    place(ordered);
    if (stick) rowsEl.scrollTop = rowsEl.scrollHeight;
  }

  function place(ordered) {
    for (var i = 0; i < ordered.length; i++) {
      if (rowsEl.children[i] !== ordered[i]) {
        rowsEl.insertBefore(ordered[i], rowsEl.children[i] || null);
      }
    }
    while (rowsEl.children.length > ordered.length) {
      rowsEl.removeChild(rowsEl.lastChild);
    }
  }

  var NEAR_BOTTOM = 60;
  function nearBottom() {
    if (!rowsEl) return true;
    return rowsEl.scrollHeight - rowsEl.scrollTop - rowsEl.clientHeight <= NEAR_BOTTOM;
  }

  D.gitChatPane = gitChatPane;

  D.gitChatShown = function () {
    if (!drawn) draw();
  };

  window.cc = window.cc || {};
  window.cc.gitChat = function (payload) {
    last = payload && typeof payload === 'object' ? payload : null;
    drawn = false;
    var open = typeof D.gitSubView === 'function' && D.gitSubView() === 'chat';
    var shown = typeof D.dashboardShown === 'function' && D.dashboardShown();
    if (pane && open && shown) draw();
  };
})();
