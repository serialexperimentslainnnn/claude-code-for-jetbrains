/*
 * app-core.js — Claude Code JCEF shell core (Agent A1).
 *
 * Creates window.cc (the Kotlin-facing API surface, populated by each module)
 * and window.CC (shared helpers + event bus + DOM mount points). Vanilla ES2019,
 * no frameworks, no external resources. Behaviour is attached via addEventListener
 * only. See JCEF_CONTRACT.md §JS MODULE PATTERN / §CODE BLOCKS / §THEME.
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
