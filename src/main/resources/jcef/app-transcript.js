(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  function CCobj() {
    return window.CC || {};
  }
  function el(tag, props) {
    var C = CCobj();
    if (C.h) {
      return C.h(tag, props);
    }
    var node = document.createElement(tag);
    props = props || {};
    if (props.class) {
      node.className = props.class;
    }
    if (props.text != null) {
      node.textContent = String(props.text);
    }
    if (props.html != null) {
      node.innerHTML = props.html;
    }
    if (props.title != null) {
      node.title = String(props.title);
    }
    if (props.attrs) {
      for (var a in props.attrs) {
        if (Object.prototype.hasOwnProperty.call(props.attrs, a)) {
          node.setAttribute(a, props.attrs[a]);
        }
      }
    }
    if (props.on) {
      for (var ev in props.on) {
        if (Object.prototype.hasOwnProperty.call(props.on, ev)) {
          node.addEventListener(ev, props.on[ev]);
        }
      }
    }
    return node;
  }
  function md(text) {
    var C = CCobj();
    if (C.markdown) {
      try {
        return C.markdown(text == null ? '' : text);
      } catch (e) {}
    }
    return esc(text);
  }
  function esc(text) {
    var C = CCobj();
    if (C.escape) {
      return C.escape(text == null ? '' : text);
    }
    var d = document.createElement('div');
    d.textContent = text == null ? '' : String(text);
    return d.innerHTML;
  }
  function safeSend(obj) {
    var C = CCobj();
    if (C.send) {
      try {
        C.send(obj);
      } catch (e) {}
    }
  }
  function conversationEl() {
    var C = CCobj();
    var node = (C.els && C.els.conversation) || document.getElementById('conversation');
    return node || null;
  }
  function emptyEl() {
    return document.getElementById('empty');
  }

  var MAX_ENTRIES = 2000;

  var rows = new Map();
  var toolCards = new Map();

  TX.el = el;
  TX.safeSend = safeSend;
  TX.conversationEl = conversationEl;
  TX.rows = rows;
  TX.toolCards = toolCards;

  var followFlag = false;
  var NEAR_BOTTOM = 80;
  var scrollScheduled = false;

  var SMOOTH_SCROLL_MAX_PX = 400;

  function isNearBottom() {
    var c = conversationEl();
    if (!c) {
      return true;
    }
    var distance = c.scrollHeight - c.scrollTop - c.clientHeight;
    return distance <= NEAR_BOTTOM;
  }
  function scheduleScroll(stick) {
    if (!stick) {
      return;
    }
    if (scrollScheduled) {
      return;
    }
    scrollScheduled = true;
    var raf =
      window.requestAnimationFrame ||
      function (fn) {
        return setTimeout(fn, 16);
      };
    raf(function () {
      scrollScheduled = false;
      var c = conversationEl();
      if (!c) {
        return;
      }
      var distance = c.scrollHeight - c.scrollTop - c.clientHeight;
      var smooth = distance > 0 && distance < SMOOTH_SCROLL_MAX_PX && !CC.reducedMotion;
      if (typeof c.scrollTo === 'function') {
        c.scrollTo({ top: c.scrollHeight, behavior: smooth ? 'smooth' : 'instant' });
      } else {
        c.scrollTop = c.scrollHeight;
      }
    });
  }

  function setBody(rec, text) {
    var body = rec.bodyNode;
    if (!body) {
      return;
    }
    var kind = rec.kind;
    if (kind === 'md') {
      body.innerHTML = md(text);
      body.__rawText = text == null ? '' : String(text);
    } else if (kind === 'pre') {
      body.textContent = text == null ? '' : String(text);
    } else {
      body.textContent = text == null ? '' : String(text);
      body.__rawText = text == null ? '' : String(text);
    }
  }

  function createRow(entry, cards) {
    if (!cards) {
      cards = toolCards;
    }
    var rec = TX.builderFor(entry.speaker, entry);
    rec.speaker = entry.speaker;
    rec.toolUseId = entry.toolUseId || null;
    if (entry.speaker === 'TOOL' && entry.toolUseId) {
      rec.outNode = rec.el.__outNode || rec.el.querySelector('.tool-out');
      rec.el.__toolUseId = entry.toolUseId;
      cards.set(entry.toolUseId, rec.el);
      if (entry.meta === 'Task' || entry.meta === 'Agent') {
        rec.el.__isAgentCard = true;
        rec.el.classList.add('agent-link');
      }
      if (entry.open) {
        rec.el.classList.add('open');
      }
    }
    if (entry.speaker === 'TOOL') {
      var icNode = rec.el.querySelector('.ic');
      if (icNode) {
        icNode.innerHTML = TX.toolIconSvg(entry.meta);
      }
      if (entry.command) {
        TX.renderCommandBlock(rec.el.__cmdNode, entry.command);
        rec.el.classList.add('cmd-tool');
      }
      rec.el.__filePath = entry.filePath || null;
    }
    return rec;
  }

  function updateRow(rec, entry, links) {
    if (rec.speaker === 'TOOL' && entry.title) {
      setBody(rec, entry.title);
    } else if (rec.speaker === 'TOOL' && entry.command) {
      setBody(rec, entry.meta || entry.text);
    } else if (rec.speaker === 'TOOL' && entry.filePath) {
      TX.renderToolLabel(rec.bodyNode, entry.text, entry.filePath);
    } else {
      setBody(rec, entry.text);
      if (links !== false && rec.speaker === 'ASSISTANT' && entry.state !== 'RUNNING') {
        TX.requestLinks(rec, entry);
      }
    }
    rec.text = entry.text;
    rec.meta = entry.meta;
    rec.state = entry.state;
    if (rec.speaker === 'TOOL') {
      TX.applyToolState(rec.el, entry.state, entry.meta);
      TX.applyToolElapsed(rec.el, entry.state, entry.elapsed);
      if (rec.el.__diffBtn) {
        rec.el.__diffBtn.hidden = !entry.reviewable;
      }
      if (rec.el.__restoreBtn) {
        rec.el.__restoreBtn.hidden = !entry.reviewable;
      }
    }
    if (rec.speaker === 'MEMORY' && rec.el.__label) {
      var title = entry.meta && String(entry.meta).trim() ? String(entry.meta) : '🧠 Recalled memories';
      rec.el.__label.textContent = title;
    }
  }

  TX.createRow = createRow;
  TX.updateRow = updateRow;

  function upsert(entry) {
    if (entry == null || entry.id == null) {
      return null;
    }

    if (entry.speaker === 'TOOL_OUTPUT') {
      if (TX.routeToolOutput(entry)) {
        return rows.get(entry.id) || null;
      }
    }

    var rec = rows.get(entry.id);
    if (rec && rec.speaker !== entry.speaker) {
      if (rec.el && rec.el.parentNode) {
        rec.el.parentNode.removeChild(rec.el);
      }
      if (rec.toolUseId) {
        toolCards.delete(rec.toolUseId);
      }
      rows.delete(entry.id);
      rec = null;
    }
    if (!rec) {
      rec = createRow(entry);
      rows.set(entry.id, rec);
    }
    updateRow(rec, entry);
    return rec;
  }

  function containerFor(entry) {
    if (entry.parent) {
      var parentCard = toolCards.get(entry.parent);
      if (parentCard) {
        return parentCard.__childrenNode || parentCard.querySelector('.tool-children') || conversationEl();
      }
    }
    return conversationEl();
  }

  function reposition(entry) {
    var rec = rows.get(entry.id);
    if (!rec || !rec.el) {
      return;
    }
    var order = entry.order;
    rec.el.__order = typeof order === 'number' && order >= 0 ? order : null;
    var container = containerFor(entry);
    if (!container) {
      return;
    }

    var ref = null;
    if (rec.el.__order != null) {
      var kids = container.children;
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        if (k === rec.el) {
          continue;
        }
        if (k.__order == null) {
          continue;
        }
        if (k.__order > rec.el.__order) {
          ref = k;
          break;
        }
      }
    }
    if (rec.el.parentNode === container && rec.el.nextSibling === ref) {
      return;
    }
    if (ref) {
      container.insertBefore(rec.el, ref);
    } else {
      container.appendChild(rec.el);
    }
  }

  function showEmptyState(show) {
    var empty = emptyEl();
    if (empty) {
      empty.hidden = !show;
    }
  }

  cc.batch = function (entries) {
    if (!entries) {
      return;
    }
    if (!Array.isArray(entries)) {
      if (entries.entries && Array.isArray(entries.entries)) {
        entries = entries.entries;
      } else {
        entries = [entries];
      }
    }
    var c = conversationEl();
    var stick = followFlag || isNearBottom();

    for (var i = 0; i < entries.length; i++) {
      upsert(entries[i]);
    }
    for (var j = 0; j < entries.length; j++) {
      var e = entries[j];
      if (e && e.id != null && e.speaker !== 'TOOL_OUTPUT') {
        reposition(e);
      } else if (e && e.id != null && e.speaker === 'TOOL_OUTPUT' && rows.has(e.id)) {
        reposition(e);
      }
    }

    if (rows.size > 0 || (c && c.children.length > 0)) {
      showEmptyState(false);
    }

    TX.refreshSearch();

    scheduleScroll(stick);
  };

  cc.clear = function () {
    rows.clear();
    toolCards.clear();
    var c = conversationEl();
    if (c) {
      var kids = Array.prototype.slice.call(c.children);
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].id === 'empty') {
          continue;
        }
        c.removeChild(kids[i]);
      }
    }
    TX.resetSearch();
    showEmptyState(true);
  };

  function dropRow(id) {
    var rec = rows.get(id);
    if (!rec) {
      return;
    }
    if (rec.el && rec.el.parentNode) {
      rec.el.parentNode.removeChild(rec.el);
    }
    if (rec.toolUseId) {
      toolCards.delete(rec.toolUseId);
    }
    rows.delete(id);
  }

  function trimNoticeText(total) {
    return (
      total +
      (total === 1 ? ' earlier row was' : ' earlier rows were') +
      ' dropped to keep the transcript at ' +
      MAX_ENTRIES +
      ' rows. Nothing was lost: the session file on disk still holds the whole conversation.'
    );
  }

  function renderTrimNotice(total) {
    var c = conversationEl();
    if (!c) {
      return;
    }
    var node = c.querySelector('.trim-notice');
    if (total <= 0) {
      if (node) {
        c.removeChild(node);
      }
      return;
    }
    var text = trimNoticeText(total);
    if (node) {
      node.textContent = text;
      return;
    }
    c.insertBefore(el('div', { class: 'notice trim-notice', text: text }), c.firstChild);
    var C = CCobj();
    if (C.announce) {
      C.announce(text);
    }
  }

  cc.trimRows = function (payload) {
    if (!payload) {
      return;
    }
    var ids = Array.isArray(payload.ids) ? payload.ids : [];
    for (var i = 0; i < ids.length; i++) {
      dropRow(ids[i]);
    }
    var total = typeof payload.total === 'number' ? payload.total : 0;
    renderTrimNotice(total);
  };

  function subscribe() {
    var C = CCobj();
    if (!C.on) {
      return false;
    }
    C.on('follow', function (b) {
      followFlag = !!b;
      if (followFlag) {
        scheduleScroll(true);
      }
    });
    C.on('search', function (q) {
      TX.runSearch(q, false);
      TX.updateFindCount();
    });
    return true;
  }

  if (!subscribe()) {
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (subscribe() || tries > 50) {
        clearInterval(iv);
      }
    }, 20);
  }
})();
