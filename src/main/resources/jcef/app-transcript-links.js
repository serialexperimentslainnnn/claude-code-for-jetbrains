(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var rows = TX.rows;

  var PATH_RE = new RegExp(
    '(?:' +
      '(?:~\\/|\\.{1,2}\\/|\\/)[\\w.-]+(?:\\/[\\w.-]+)*\\/?' +
      '|' +
      '[\\w.-]+\\/(?:[\\w.-]+\\/?)*' +
      '|' +
      '[\\w.-]+\\.[A-Za-z][\\w]{0,9}' +
      ')(?::\\d+)?',
    'g'
  );
  var SYMBOL_RE = /\b([A-Z][A-Za-z0-9]{2,}|[a-z][A-Za-z0-9]{2,}(?=\(\)))\b/g;

  function collectCandidates(root) {
    var paths = {},
      symbols = {};
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode;
        while (p && p !== root) {
          var t = p.tagName;
          if (t === 'A') {
            return NodeFilter.FILTER_REJECT;
          }
          if (t === 'PRE') {
            return NodeFilter.FILTER_REJECT;
          }
          p = p.parentNode;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    var n, m;
    while ((n = walker.nextNode())) {
      var txt = n.nodeValue || '';
      PATH_RE.lastIndex = 0;
      while ((m = PATH_RE.exec(txt))) {
        paths[m[0]] = true;
      }
      var inCode = n.parentNode && n.parentNode.tagName === 'CODE';
      if (inCode) {
        SYMBOL_RE.lastIndex = 0;
        while ((m = SYMBOL_RE.exec(txt))) {
          symbols[m[1]] = true;
        }
      }
    }
    return { paths: Object.keys(paths), symbols: Object.keys(symbols) };
  }

  TX.requestLinks = function (rec, entry) {
    if (!rec || !rec.bodyNode) {
      return;
    }
    var c = collectCandidates(rec.bodyNode);
    if (!c.paths.length && !c.symbols.length) {
      return;
    }
    safeSend({ type: 'resolveLinks', rowId: entry.id, paths: c.paths, symbols: c.symbols });
  };

  function applyLinks(payload) {
    if (!payload || payload.rowId == null) {
      return;
    }
    var rec = rows.get(payload.rowId);
    if (!rec || !rec.bodyNode) {
      return;
    }
    var links = Array.isArray(payload.links) ? payload.links : [];
    if (!links.length) {
      return;
    }
    links.sort(function (a, b) {
      return String(b.token).length - String(a.token).length;
    });
    for (var i = 0; i < links.length; i++) {
      linkifyToken(rec.bodyNode, links[i]);
    }
  }

  var TOKEN_LEFT = /[\w.\-/~]/;
  var TOKEN_RIGHT = /[\w.\-/]/;
  function atTokenBoundary(txt, at, token) {
    if (at > 0 && TOKEN_LEFT.test(txt.charAt(at - 1))) {
      return false;
    }
    var after = txt.charAt(at + token.length);
    return !(after && TOKEN_RIGHT.test(after));
  }

  function linkifyToken(root, link) {
    var token = String(link.token || '');
    if (!token) {
      return;
    }
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode;
        while (p && p !== root) {
          if (p.tagName === 'A' || p.tagName === 'PRE') {
            return NodeFilter.FILTER_REJECT;
          }
          p = p.parentNode;
        }
        return n.nodeValue && n.nodeValue.indexOf(token) >= 0
          ? NodeFilter.FILTER_ACCEPT
          : NodeFilter.FILTER_REJECT;
      },
    });
    var targets = [],
      n;
    while ((n = walker.nextNode())) {
      targets.push(n);
    }
    for (var i = 0; i < targets.length; i++) {
      var node = targets[i];
      var txt = node.nodeValue;
      var frag = document.createDocumentFragment();
      var from = 0,
        hit = false;
      for (var at = txt.indexOf(token); at >= 0; at = txt.indexOf(token, at + token.length)) {
        if (!atTokenBoundary(txt, at, token)) {
          continue;
        }
        frag.appendChild(document.createTextNode(txt.slice(from, at)));
        frag.appendChild(
          el('a', {
            class: 'jb-link',
            text: token,
            attrs: {
              href: TX.jbHref(link.path, link.line),
              title: 'Open ' + link.path + (link.line ? ':' + link.line : ''),
            },
          })
        );
        from = at + token.length;
        hit = true;
      }
      if (!hit) {
        continue;
      }
      frag.appendChild(document.createTextNode(txt.slice(from)));
      node.parentNode.replaceChild(frag, node);
    }
  }

  cc.links = function (payload) {
    try {
      applyLinks(payload);
    } catch (e) {}
  };
})();
