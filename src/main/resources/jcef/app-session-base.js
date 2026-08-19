(function () {
  'use strict';

  var CC = window.CC || (window.CC = {});
  var D = (CC.dash = CC.dash || {});

  function core() {
    return window.CC || null;
  }
  function conversation() {
    var c = core();
    return (c && c.els && c.els.conversation) || document.getElementById('conversation') || null;
  }
  function appRoot() {
    var c = core();
    return (c && c.els && c.els.app) || document.getElementById('app') || document.body || null;
  }
  function h() {
    var c = core();
    if (c && typeof c.h === 'function') return c.h.apply(c, arguments);
    return null;
  }
  function send(obj) {
    var c = core();
    if (c && typeof c.send === 'function') c.send(obj);
  }

  function num(v) {
    return typeof v === 'number' && isFinite(v) ? v : null;
  }

  function fmtInt(v) {
    var n = num(v);
    if (n == null) return null;
    try {
      return Math.round(n).toLocaleString();
    } catch (e) {
      return String(Math.round(n));
    }
  }

  function fmtUsd(v) {
    var n = num(v);
    if (n == null) return null;
    return '$' + n.toFixed(n < 1 ? 4 : 2);
  }

  function statRow(label, value) {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: String(value) })
    );
  }

  function card(title, body, wide, anchor) {
    var children = [];
    if (Array.isArray(body)) {
      for (var i = 0; i < body.length; i++) {
        if (body[i]) children.push(body[i]);
      }
    } else if (body) {
      children.push(body);
    }
    if (!children.length) return null;
    var head = h('div', { class: 'dash-title', text: title });
    var props = { class: 'dash-card' + (wide ? ' wide' : '') };
    if (anchor) props.attrs = { 'data-card': anchor };
    return h('div', props, head, children);
  }

  D.core = core;
  D.conversation = conversation;
  D.appRoot = appRoot;
  D.h = h;
  D.send = send;
  D.num = num;
  D.fmtInt = fmtInt;
  D.fmtUsd = fmtUsd;
  D.statRow = statRow;
  D.card = card;

  D.leaveDashboard = function () {};
})();
