/*
 * app-core.js — Claude Code JCEF shell core.
 *
 * Creates window.cc (the Kotlin-facing API surface, populated by each module)
 * and window.CC (shared helpers + event bus + DOM mount points). Vanilla ES2019,
 * no frameworks, no external resources. Behaviour is attached via addEventListener
 * only.
 *
 * Loaded FIRST: everything below exists before any other module runs. Three companions extend CC
 * immediately afterwards and are part of the same core — `app-core-markdown.js` (markdown + code blocks),
 * `app-core-diagram.js` (the node diagram) and `app-core-theme.js` (theme, motion, Vibe Mode).
 */
(function () {
  'use strict';

  // ---- The two globals --------------------------------------------------------
  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});

  // ---------------------------------------------------------------------------
  // Bridge: send a JSON message to Kotlin via window.__ccSend (installed by host).
  // No-op (but never throws) if the bridge is absent.
  // ---------------------------------------------------------------------------
  CC.send = function (obj) {
    try {
      var payload = JSON.stringify(obj);
      if (typeof window.__ccSend === 'function') {
        window.__ccSend(payload);
      }
    } catch (e) {
      // Swallow: the renderer must never crash on a failed send.
    }
  };

  // ---------------------------------------------------------------------------
  // escape(s): HTML-escape a string for safe text interpolation.
  // ---------------------------------------------------------------------------
  CC.escape = function (s) {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };

  // ---------------------------------------------------------------------------
  // h(tag, props, ...children): tiny hyperscript.
  // props: { class, text, html, title, attrs:{...}, on:{event:fn}, ... }
  // children: nodes or strings (strings become text nodes).
  // ---------------------------------------------------------------------------
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
          // Apply dynamic styles via the CSSOM (el.style.prop = v), NOT a `style="..."` attribute.
          // CSSOM mutations are not governed by CSP style-src, so we need no 'unsafe-inline'.
          if (val && typeof val === 'object') {
            for (var sp in val) {
              if (Object.prototype.hasOwnProperty.call(val, sp) && val[sp] != null) {
                try {
                  el.style[sp] = val[sp];
                } catch (e) {
                  /* ignore an invalid property */
                }
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
          // Generic attribute (e.g. id, type, placeholder, hidden, role…).
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

  // ---------------------------------------------------------------------------
  // How long a quota window has left. Relative, because an absolute timestamp
  // makes the reader do the arithmetic. Lives here rather than in one of the
  // two modules that render it, so the dashboard card and the composer's bar
  // row can never disagree about what "shortly" means.
  // ---------------------------------------------------------------------------
  /** Minutes until `iso`, or null when it is missing or unparseable. */
  function minutesUntil(iso) {
    if (!iso) return null;
    var when = Date.parse(iso);
    if (isNaN(when)) return null;
    return Math.round((when - Date.now()) / 60000);
  }
  /** "4h 50m" / "12m" / "soon" — the compact form, for the composer's bar row. */
  CC.resetInShort = function (iso) {
    var mins = minutesUntil(iso);
    if (mins === null) return null;
    if (mins <= 0) return 'soon';
    var hours = Math.floor(mins / 60);
    return hours > 0 ? hours + 'h ' + (mins % 60) + 'm' : mins + 'm';
  };
  /** "Resets in 4h 50m" — the sentence form, for the dashboard card and tooltips. */
  CC.resetIn = function (iso) {
    var short = CC.resetInShort(iso);
    if (short === null) return null;
    return short === 'soon' ? 'Resets shortly' : 'Resets in ' + short;
  };

  // ---------------------------------------------------------------------------
  // Tiny event bus: on(event, fn) / emit(event, ...args).
  // ---------------------------------------------------------------------------
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
    // Iterate a copy so handlers can unsubscribe during dispatch.
    var snapshot = arr.slice();
    for (var i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i].apply(null, args);
      } catch (e) {
        // A faulty listener must not break the bus.
      }
    }
  };

  // ---------------------------------------------------------------------------
  // els: resolve the DOM mount points by id (§DOM).
  // ---------------------------------------------------------------------------
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

  /**
   * Announce a short status phrase to assistive technology (WCAG 2.2 AA — 4.1.3 Status Messages).
   *
   * The transcript streams without ever moving focus, so without this a screen-reader user has no way to know
   * that Claude began answering, finished, or is now blocked on a permission card. Focus is deliberately NOT
   * moved: 4.1.3 exists precisely for changes that must be perceivable *without* stealing focus.
   *
   * Deliberately terse and low-frequency: this is called on turn transitions, never per streamed token. A live
   * region updated on every delta is unusable — the screen reader would talk over itself continuously and the
   * user would turn it off, which is worse than silence.
   *
   * Re-announcing identical text is a no-op in most screen readers (the node did not change), so repeated
   * states are skipped explicitly rather than relying on that behaviour being uniform.
   */
  var lastAnnouncement = '';
  CC.announce = function (message) {
    var el = CC.els && CC.els.a11yStatus;
    if (!el) return;
    var text = message == null ? '' : String(message);
    if (text === lastAnnouncement) return;
    lastAnnouncement = text;
    el.textContent = text;
  };

  /**
   * Declare that [owner] is (or is no longer) covering the transcript with an opaque layer.
   *
   * A covered transcript stays laid out — it keeps its box and therefore its scroll offset, so leaving the
   * layer returns the reader to the line they were on — which means it is still there underneath, with its
   * links and buttons focusable and its text readable by a screen reader. `inert` is the other half: painted,
   * out of reach. Focusable content under an opaque layer is WCAG 2.2 SC 2.4.11 (Focus Not Obscured).
   *
   * It takes an OWNER because there is more than one such layer — the dashboard and the waiting screens — and
   * a boolean attribute with two independent writers is a bug waiting for the order in which they happen to
   * fire: closing the dashboard would make a transcript reachable that the sign-in card is still covering.
   * The attribute is set while the set is non-empty and cleared when it empties, so no caller has to know
   * about the others.
   */
  var covering = {};
  CC.coverTranscript = function (owner, covered) {
    if (covered) covering[owner] = true;
    else delete covering[owner];
    var el = CC.els && CC.els.conversation;
    if (!el) return;
    if (Object.keys(covering).length) el.setAttribute('inert', '');
    else el.removeAttribute('inert');
  };

  // Null-safe placeholders so the host may call these before modules load.
  // Each owning module overwrites its own method(s).
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

  // ---------------------------------------------------------------------------
  // Global link interception: any <a href> click → route to Kotlin, never
  // navigate. Single delegated handler installed once.
  // ---------------------------------------------------------------------------
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

  // ---------------------------------------------------------------------------
  // Delegated code-block Copy. The per-block listener can't survive CC.markdown's
  // detached-fragment serialization (see decorateCodeBlocks), so resolve the copy
  // intent here on the live DOM. Covers both the markdown code blocks and any
  // other .copy affordance that carries no own handler.
  // ---------------------------------------------------------------------------
  function copyTargetText(copyEl) {
    var pre = copyEl.closest ? copyEl.closest('pre') : null;
    if (!pre) {
      // Walk up manually for very old engines / detached cases.
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
  // Shared so every Copy affordance confirms the same way. The message-level buttons in app-transcript.js
  // carry their OWN click handler (they copy a rendered message, not a `pre > code`), so the delegated
  // code-head path below never reaches them — they copied silently, which reads as a dead button. Exported
  // rather than reimplemented so the two can never drift in wording or duration.
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

  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // Uncaught errors, reported to the host.
  // ---------------------------------------------------------------------------
  /**
   * Sends anything this page throws to `idea.log`, because otherwise nothing does.
   *
   * The plugin's UI is a browser nobody can open the devtools on, and the CEF console goes nowhere — so an
   * exception in one module was, until now, completely invisible from outside. That is not a theoretical
   * gap: `#tabsbar` ships `hidden` and `app-tabs.js` clears that attribute at the END of its render, so a
   * throw anywhere earlier produced a fully-built tab bar that was never shown, with no error on screen, no
   * line in the log and no red test. The only symptom was a user asking where the tabs had gone.
   *
   * Rides the existing `diagnostics` message rather than adding one: the host already logs that, and the
   * prefix is what makes it a WARN instead of the routine environment report (`ChatBridgeRouter`).
   *
   * Bounded, because a throw inside a render can repeat on every push and a log that floods is a log nobody
   * reads. Deduplicated by message, and capped — after that the page stays silent, which is no worse than
   * where this started.
   */
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
  /**
   * A script the policy refused to run — the one failure `error` does NOT fire for.
   *
   * The page is served under a CSP that pins every script by sha256, so a file whose hash does not match the
   * header is BLOCKED: it never executes, it throws nothing, and the only trace is a console message in a
   * browser with no devtools. What that looks like from outside is a feature that simply is not there —
   * `window.cc.tabs` undefined, the host's `cc.tabs && cc.tabs(...)` guard skipping, no tab bar and no error
   * anywhere. Reporting it is the difference between a five-minute answer and an afternoon of guessing.
   */
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

  /**
   * What the page ended up with, reported once — the inventory the host cannot see.
   *
   * Each module is its own `<script>`, so one that fails to load or to run leaves the others working and the
   * page half built. The host then calls `window.cc.<x> && window.cc.<x>(...)`, the guard skips, and nothing
   * says so. This names the missing halves at startup instead.
   */
  CC.selfCheck = function () {
    var expected = ['batch', 'clear', 'state', 'permissions', 'session', 'tabs', 'theme', 'settingsMenu'];
    var missing = [];
    for (var i = 0; i < expected.length; i++) {
      if (typeof (window.cc || {})[expected[i]] !== 'function') missing.push('cc.' + expected[i]);
    }
    if (missing.length) CC.send({ type: 'diagnostics', report: 'uncaught missing: ' + missing.join(', ') });
  };

  // Announce readiness once the page has loaded.
  // ---------------------------------------------------------------------------
  // Announce ready, but ONLY once the host has injected window.__ccSend (it does so on load-end). If the page
  // script runs before that injection (a fast/cached load), a single CC.send is silently dropped and the host
  // never learns the web app is alive — the dead-chat-on-first-open bug. Poll briefly until the bridge exists.
  function announceReady() {
    var tries = 0;
    (function attempt() {
      if (typeof window.__ccSend === 'function') {
        CC.send({ type: 'ready' });
        // One-shot environment report on first load. Cheap (a detached probe element and a few reads) and it
        // is the only window into what this browser actually resolves — see CC.diagnostics.
        try {
          CC.diagnostics();
          // What the page ACTUALLY ended up with. A module that failed to load or to run leaves the others
          // working, so the only symptom is a feature that is not there — see CC.selfCheck.
          CC.selfCheck();
        } catch (e) {
          // Diagnostics must never be the reason the page fails to come up.
        }
        return;
      }
      if (tries++ < 200) {
        setTimeout(attempt, 50);
      } // ~10s ceiling, then give up
    })();
  }
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    // Defer so later modules (transcript/composer/permissions) finish wiring
    // their cc.* methods before the host responds to 'ready'.
    setTimeout(announceReady, 0);
  } else {
    window.addEventListener('DOMContentLoaded', function () {
      setTimeout(announceReady, 0);
    });
  }
})();
