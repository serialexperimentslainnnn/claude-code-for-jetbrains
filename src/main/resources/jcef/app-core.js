(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});

  CC.send = function (obj) {
    try {
      var payload = JSON.stringify(obj);
      if (typeof window.__ccSend === 'function') {
        window.__ccSend(payload);
      }
    } catch (e) {}
  };

  CC.escape = function (s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };

  CC.h = function (tag, props) {
    var el = document.createElement(tag);
    if (props) {
      for (var key in props) {
        if (!Object.prototype.hasOwnProperty.call(props, key)) continue;
        var val = props[key];
        if (val === null || val === undefined) continue;
        if (key === 'class' || key === 'className') {
          el.className = val;
        } else if (key === 'text') {
          el.textContent = val;
        } else if (key === 'html') {
          el.innerHTML = val;
        } else if (key === 'title') {
          el.setAttribute('title', val);
        } else if (key === 'style') {
          if (val && typeof val === 'object') {
            for (var sp in val) {
              if (Object.prototype.hasOwnProperty.call(val, sp) && val[sp] != null) {
                try {
                  el.style[sp] = val[sp];
                } catch (e) {}
              }
            }
          }
        } else if (key === 'attrs') {
          for (var a in val) {
            if (Object.prototype.hasOwnProperty.call(val, a) && val[a] != null) {
              el.setAttribute(a, val[a]);
            }
          }
        } else if (key === 'on') {
          for (var ev in val) {
            if (Object.prototype.hasOwnProperty.call(val, ev) && typeof val[ev] === 'function') {
              el.addEventListener(ev, val[ev]);
            }
          }
        } else if (key === 'dataset') {
          for (var d in val) {
            if (Object.prototype.hasOwnProperty.call(val, d) && val[d] != null) {
              el.dataset[d] = val[d];
            }
          }
        } else {
          if (val === true) {
            el.setAttribute(key, '');
          } else if (val !== false) {
            el.setAttribute(key, val);
          }
        }
      }
    }
    var children = Array.prototype.slice.call(arguments, 2);
    appendChildren(el, children);
    return el;
  };

  function appendChildren(el, children) {
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) {
        appendChildren(el, child);
      } else if (typeof child === 'string' || typeof child === 'number') {
        el.appendChild(document.createTextNode(String(child)));
      } else if (child.nodeType) {
        el.appendChild(child);
      }
    }
  }

  function minutesUntil(iso) {
    if (!iso) return null;
    var when = Date.parse(iso);
    if (isNaN(when)) return null;
    return Math.round((when - Date.now()) / 60000);
  }
  CC.resetInShort = function (iso) {
    var mins = minutesUntil(iso);
    if (mins === null) return null;
    if (mins <= 0) return 'soon';
    var hours = Math.floor(mins / 60);
    return hours > 0 ? hours + 'h ' + (mins % 60) + 'm' : mins + 'm';
  };
  CC.resetIn = function (iso) {
    var short = CC.resetInShort(iso);
    if (short === null) return null;
    return short === 'soon' ? 'Resets shortly' : 'Resets in ' + short;
  };

  var listeners = {};
  CC.on = function (event, fn) {
    if (typeof fn !== 'function') return function () {};
    (listeners[event] || (listeners[event] = [])).push(fn);
    return function off() {
      var arr = listeners[event];
      if (!arr) return;
      var idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    };
  };
  CC.emit = function (event) {
    var arr = listeners[event];
    if (!arr || !arr.length) return;
    var args = Array.prototype.slice.call(arguments, 1);
    var snapshot = arr.slice();
    for (var i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i].apply(null, args);
      } catch (e) {}
    }
  };

  function byId(id) {
    return document.getElementById(id);
  }
  CC.els = {
    app: byId('app'),
    conversation: byId('conversation'),
    permissions: byId('permissions'),
    composer: byId('composer'),
    palette: byId('palette'),
    a11yStatus: byId('a11y-status'),
  };

  var lastAnnouncement = '';
  CC.announce = function (message) {
    var el = CC.els && CC.els.a11yStatus;
    if (!el) return;
    var text = message == null ? '' : String(message);
    if (text === lastAnnouncement) return;
    lastAnnouncement = text;
    el.textContent = text;
  };

  var covering = {};
  CC.coverTranscript = function (owner, covered) {
    if (covered) covering[owner] = true;
    else delete covering[owner];
    var el = CC.els && CC.els.conversation;
    if (!el) return;
    if (Object.keys(covering).length) el.setAttribute('inert', '');
    else el.removeAttribute('inert');
  };

  if (typeof cc.batch !== 'function') cc.batch = function () {};
  if (typeof cc.clear !== 'function') cc.clear = function () {};
  if (typeof cc.state !== 'function') cc.state = function () {};
  if (typeof cc.meta !== 'function') cc.meta = function () {};
  if (typeof cc.permissions !== 'function') cc.permissions = function () {};
  if (typeof cc.openPalette !== 'function') cc.openPalette = function () {};
  if (typeof cc.focusInput !== 'function') cc.focusInput = function () {};
  if (typeof cc.insertText !== 'function') cc.insertText = function () {};
  if (typeof cc.openDashboard !== 'function') cc.openDashboard = function () {};
  if (typeof cc.attachData !== 'function') cc.attachData = function () {};
  if (typeof cc.attachments !== 'function') cc.attachments = function () {};
  if (typeof cc.session !== 'function') cc.session = function () {};
  if (typeof cc.mcp !== 'function') cc.mcp = function () {};

  document.addEventListener(
    'click',
    function (ev) {
      var node = ev.target;
      while (node && node !== document) {
        if (node.tagName === 'A' && node.hasAttribute('href')) {
          var url = node.getAttribute('href');
          if (url && url !== '#') {
            ev.preventDefault();
            ev.stopPropagation();
            CC.send({ type: 'open', url: url });
          }
          return;
        }
        node = node.parentNode;
      }
    },
    true
  );

  function copyTargetText(copyEl) {
    var pre = copyEl.closest ? copyEl.closest('pre') : null;
    if (!pre) {
      var n = copyEl.parentNode;
      while (n && n.tagName !== 'PRE') n = n.parentNode;
      pre = n;
    }
    var code = pre ? pre.querySelector('code') : null;
    return code ? code.textContent : '';
  }
  function flashCopied(copyEl) {
    var prev = copyEl.textContent;
    copyEl.textContent = 'Copied';
    copyEl.classList.add('copied');
    setTimeout(function () {
      copyEl.textContent = prev;
      copyEl.classList.remove('copied');
    }, 1200);
  }
  CC.flashCopied = flashCopied;
  function handleCopyFromCodeHead(ev, copyEl) {
    var text = copyTargetText(copyEl);
    if (!text) return;
    ev.preventDefault();
    ev.stopPropagation();
    CC.send({ type: 'copy', text: text });
    flashCopied(copyEl);
  }
  document.addEventListener(
    'click',
    function (ev) {
      var node = ev.target;
      while (node && node !== document) {
        if (
          node.className &&
          ('' + node.className).indexOf('copy') >= 0 &&
          node.parentNode &&
          ('' + (node.parentNode.className || '')).indexOf('code-head') >= 0
        ) {
          handleCopyFromCodeHead(ev, node);
          return;
        }
        node = node.parentNode;
      }
    },
    true
  );
  document.addEventListener(
    'keydown',
    function (ev) {
      if (ev.key !== 'Enter' && ev.key !== ' ' && ev.key !== 'Spacebar') return;
      var node = ev.target;
      if (
        node &&
        node.className &&
        ('' + node.className).indexOf('copy') >= 0 &&
        node.parentNode &&
        ('' + (node.parentNode.className || '')).indexOf('code-head') >= 0
      ) {
        handleCopyFromCodeHead(ev, node);
      }
    },
    true
  );

  var reported = Object.create(null);
  var reportedCount = 0;
  var MAX_REPORTED = 20;
  function reportUncaught(what, error) {
    var text = error && error.stack ? String(error.stack) : String(error);
    var key = what + '|' + text.split('\n')[0];
    if (reported[key] || reportedCount >= MAX_REPORTED) return;
    reported[key] = true;
    reportedCount++;
    CC.send({ type: 'diagnostics', report: 'uncaught ' + what + ': ' + text });
  }
  window.addEventListener('error', function (ev) {
    reportUncaught('error', ev.error || ev.message);
  });
  window.addEventListener('unhandledrejection', function (ev) {
    reportUncaught('rejection', ev.reason);
  });
  window.addEventListener('securitypolicyviolation', function (ev) {
    reportUncaught(
      'csp',
      ev.violatedDirective +
        ' blocked ' +
        (ev.blockedURI || 'inline') +
        ' (' +
        ev.sourceFile +
        ':' +
        ev.lineNumber +
        ')'
    );
  });

  CC.selfCheck = function () {
    var expected = ['batch', 'clear', 'state', 'permissions', 'session', 'tabs', 'theme', 'settingsMenu'];
    var missing = [];
    for (var i = 0; i < expected.length; i++) {
      if (typeof (window.cc || {})[expected[i]] !== 'function') missing.push('cc.' + expected[i]);
    }
    if (missing.length) CC.send({ type: 'diagnostics', report: 'uncaught missing: ' + missing.join(', ') });
  };

  function announceReady() {
    var tries = 0;
    (function attempt() {
      if (typeof window.__ccSend === 'function') {
        CC.send({ type: 'ready' });
        try {
          CC.diagnostics();
          CC.selfCheck();
        } catch (e) {}
        return;
      }
      if (tries++ < 200) {
        setTimeout(attempt, 50);
      }
    })();
  }
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(announceReady, 0);
  } else {
    window.addEventListener('DOMContentLoaded', function () {
      setTimeout(announceReady, 0);
    });
  }
})();
