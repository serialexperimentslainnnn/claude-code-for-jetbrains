/* app-transcript-links.js — jump-to-code in model text.
 *
 * One subject: finding the path/symbol candidates in a settled assistant row, asking the host which of them
 * are real, and turning only those into `jb://` links. Extends the shared `CC.transcript` namespace created
 * by app-transcript.js.
 */
(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var rows = TX.rows;

  // ---- jump-to-code in model text ------------------------------------------
  // The transcript can only GUESS what's a path or a symbol, so we never link blindly: candidates are sent to the
  // host, which answers with the ones it could actually resolve (file exists / symbol is an unambiguous
  // declaration in the project). Only those become links — no dead hyperlinks.

  // A path candidate — FILE or DIRECTORY alike; the host is the one that knows which, and whether it exists.
  // Something only looks like a path if at least one of these holds (a bare word like `build` is NOT a path — it
  // would be a guess, and every prose word would end up in the batch):
  //   1. it is ANCHORED     — `~/.claude`, `./a/b.py`, `/tmp/x` (a leading ~/ ./ ../ or /);
  //   2. it has SEGMENTS    — `src/main/kotlin`, `build/`, `a/b.py:42` (a slash inside);
  //   3. it has an EXTENSION— `Foo.kt`, `build.gradle.kts:12`.
  // Each alternative matches the path WHOLE (final segment included, trailing slash optional). Matching only up to
  // a slash is what used to chop `src/main/kotlin/dev/ui` into a `src/main/kotlin/dev/` link with `ui` dangling
  // outside it. A `:42` line suffix may follow any of them.
  var PATH_RE = new RegExp(
    '(?:' +
      '(?:~\\/|\\.{1,2}\\/|\\/)[\\w.-]+(?:\\/[\\w.-]+)*\\/?' + // 1. anchored
      '|' +
      '[\\w.-]+\\/(?:[\\w.-]+\\/?)*' + // 2. has segments (incl. a trailing-slash dir)
      '|' +
      '[\\w.-]+\\.[A-Za-z][\\w]{0,9}' + // 3. bare name with an extension
      ')(?::\\d+)?',
    'g'
  );
  // A plausible symbol: CamelCase (PermissionBroker) or a call (resolvePermission()). Deliberately conservative —
  // the host rejects anything that isn't a real declaration anyway, this just keeps the batch small.
  var SYMBOL_RE = /\b([A-Z][A-Za-z0-9]{2,}|[a-z][A-Za-z0-9]{2,}(?=\(\)))\b/g;

  function collectCandidates(root) {
    var paths = {},
      symbols = {};
    // Only look inside inline code spans and plain text — never inside fenced code blocks (a whole file's source
    // would flood the batch with noise) and never inside an existing link.
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
        // symbols only inside `code spans` — prose would produce mostly noise
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

  // Host answered: turn the confirmed tokens into links, in place, without re-rendering the row.
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
    // Longest token first, so `Foo.kt:42` wins over `Foo.kt`.
    links.sort(function (a, b) {
      return String(b.token).length - String(a.token).length;
    });
    for (var i = 0; i < links.length; i++) {
      linkifyToken(rec.bodyNode, links[i]);
    }
  }

  // A token may only be linked as a WHOLE token — never as a fragment of something bigger. Without this, a
  // resolved `src/main/kotlin/dev/ui` would also linkify inside `src/main/kotlin/dev/ui/Fantasma.kt` (a file that
  // does NOT exist), and a resolved `Session` would light up inside `ClaudeSession`. So: no path/word character
  // may sit right before or right after the match.
  var TOKEN_LEFT = /[\w.\-/~]/;
  var TOKEN_RIGHT = /[\w.\-/]/;
  function atTokenBoundary(txt, at, token) {
    if (at > 0 && TOKEN_LEFT.test(txt.charAt(at - 1))) {
      return false;
    }
    var after = txt.charAt(at + token.length);
    return !(after && TOKEN_RIGHT.test(after));
  }

  // Replace every whole-token occurrence of link.token in root's text nodes with an <a>. The link text is set via
  // textContent (never innerHTML), so a hostile token cannot inject markup.
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
      // Walk EVERY occurrence in this node (the old code linked only the first one and dropped the rest).
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
      } // nothing was a whole token here — leave the node exactly as it was
      frag.appendChild(document.createTextNode(txt.slice(from)));
      node.parentNode.replaceChild(frag, node);
    }
  }

  // Host's answer to `resolveLinks`: upgrade the confirmed tokens in that row to jump-to-code links.
  cc.links = function (payload) {
    try {
      applyLinks(payload);
    } catch (e) {
      /* linkification is best-effort — never break the transcript */
    }
  };
})();
