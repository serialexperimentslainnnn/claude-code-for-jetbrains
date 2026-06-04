/*
 * app-core.js — Claude Code JCEF shell core (Agent A1).
 *
 * Creates window.cc (the Kotlin-facing API surface, populated by each module)
 * and window.CC (shared helpers + event bus + DOM mount points). Vanilla ES2019,
 * no frameworks, no external resources. Behaviour is attached via addEventListener
 * only. See JCEF_CONTRACT.md §JS MODULE PATTERN / §CODE BLOCKS / §THEME.
 */
(function () {
  "use strict";

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
      if (typeof window.__ccSend === "function") {
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
    if (s === null || s === undefined) return "";
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
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
        if (key === "class" || key === "className") {
          el.className = val;
        } else if (key === "text") {
          el.textContent = val;
        } else if (key === "html") {
          el.innerHTML = val;
        } else if (key === "title") {
          el.setAttribute("title", val);
        } else if (key === "style") {
          // Apply dynamic styles via the CSSOM (el.style.prop = v), NOT a `style="..."` attribute.
          // CSSOM mutations are not governed by CSP style-src, so we need no 'unsafe-inline'.
          if (val && typeof val === "object") {
            for (var sp in val) {
              if (Object.prototype.hasOwnProperty.call(val, sp) && val[sp] != null) {
                try { el.style[sp] = val[sp]; } catch (e) { /* ignore an invalid property */ }
              }
            }
          }
        } else if (key === "attrs") {
          for (var a in val) {
            if (Object.prototype.hasOwnProperty.call(val, a) && val[a] != null) {
              el.setAttribute(a, val[a]);
            }
          }
        } else if (key === "on") {
          for (var ev in val) {
            if (Object.prototype.hasOwnProperty.call(val, ev) && typeof val[ev] === "function") {
              el.addEventListener(ev, val[ev]);
            }
          }
        } else if (key === "dataset") {
          for (var d in val) {
            if (Object.prototype.hasOwnProperty.call(val, d) && val[d] != null) {
              el.dataset[d] = val[d];
            }
          }
        } else {
          // Generic attribute (e.g. id, type, placeholder, hidden, role…).
          if (val === true) {
            el.setAttribute(key, "");
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
      } else if (typeof child === "string" || typeof child === "number") {
        el.appendChild(document.createTextNode(String(child)));
      } else if (child.nodeType) {
        el.appendChild(child);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // markdown(text): marked.parse → DOMPurify.sanitize → decorate code blocks.
  // Returns a safe HTML string. Code-block decoration (.code-head + Copy +
  // hljs) is applied to a detached fragment, then serialized back out.
  // ---------------------------------------------------------------------------
  CC.markdown = function (text) {
    if (text === null || text === undefined) return "";
    var src = String(text);
    var raw;
    try {
      // breaks:true → single newlines render as <br>, so multi-line prompts/replies keep their
      // line breaks instead of being collapsed into one run by standard Markdown.
      var mdOpts = { breaks: true, gfm: true };
      raw = (typeof window.marked !== "undefined" && window.marked)
        ? (typeof window.marked.parse === "function"
            ? window.marked.parse(src, mdOpts)
            : window.marked(src, mdOpts))
        : CC.escape(src);
    } catch (e) {
      raw = CC.escape(src);
    }

    var clean;
    try {
      clean = (typeof window.DOMPurify !== "undefined" && window.DOMPurify)
        ? window.DOMPurify.sanitize(raw, { ADD_ATTR: ["target"], FORBID_ATTR: ["style"] })
        : raw;
    } catch (e2) {
      clean = raw;
    }

    // Decorate code blocks in a detached container.
    try {
      var holder = document.createElement("div");
      holder.innerHTML = clean;
      decorateCodeBlocks(holder);
      return holder.innerHTML;
    } catch (e3) {
      return clean;
    }
  };

  // Shared code-block decoration so callers can re-run it on live DOM if needed.
  function decorateCodeBlocks(root) {
    if (!root) return;
    var blocks = root.querySelectorAll("pre > code");
    for (var i = 0; i < blocks.length; i++) {
      var code = blocks[i];
      var pre = code.parentNode;
      if (!pre || pre.getAttribute("data-cc-decorated") === "1") continue;
      pre.setAttribute("data-cc-decorated", "1");

      // Derive language from the `language-xxx` class hljs/marked emit.
      var lang = "";
      var cls = (code.className || "").split(/\s+/);
      for (var c = 0; c < cls.length; c++) {
        if (cls[c].indexOf("language-") === 0) {
          lang = cls[c].slice("language-".length);
          break;
        }
      }

      var head = document.createElement("div");
      head.className = "code-head";

      var label = document.createElement("span");
      label.className = "code-lang";
      label.textContent = lang || "text";
      head.appendChild(label);

      var copy = document.createElement("span");
      copy.className = "copy";
      copy.setAttribute("role", "button");
      copy.setAttribute("tabindex", "0");
      copy.textContent = "Copy";
      copy.addEventListener("click", (function (codeEl) {
        return function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          CC.send({ type: "copy", text: codeEl.textContent });
        };
      })(code));
      head.appendChild(copy);

      pre.insertBefore(head, code);

      // Syntax-highlight the code element.
      try {
        if (typeof window.hljs !== "undefined" && window.hljs && typeof window.hljs.highlightElement === "function") {
          window.hljs.highlightElement(code);
        }
      } catch (e) {
        // Highlighting is best-effort.
      }
    }
  }
  CC.decorateCodeBlocks = decorateCodeBlocks;

  // ---------------------------------------------------------------------------
  // Tiny event bus: on(event, fn) / emit(event, ...args).
  // ---------------------------------------------------------------------------
  var listeners = {};
  CC.on = function (event, fn) {
    if (typeof fn !== "function") return function () {};
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
  function byId(id) { return document.getElementById(id); }
  CC.els = {
    app: byId("app"),
    conversation: byId("conversation"),
    dock: byId("dock"),
    permissions: byId("permissions"),
    composer: byId("composer"),
    palette: byId("palette")
  };

  // ---------------------------------------------------------------------------
  // applyTheme(vars): map camelCase keys → kebab CSS custom props on :root.
  // Special mappings per §THEME; everything else → --<lowercased-kebab-key>.
  // ---------------------------------------------------------------------------
  var THEME_MAP = {
    accentSoft: "--accent-soft",
    codeBg: "--code-bg",
    fontFamily: "--font",
    monoFamily: "--mono",
    fontSize: "--fs"
  };

  function camelToKebab(key) {
    return key.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();
  }

  CC.applyTheme = function (vars) {
    if (!vars || typeof vars !== "object") return;
    var root = document.documentElement;
    if (!root || !root.style) return;
    CC.__themeVars = CC.__themeVars || {};
    for (var key in vars) {
      if (!Object.prototype.hasOwnProperty.call(vars, key)) continue;
      if (key === "vibe") continue; // a flag, not a CSS var — handled below
      var val = vars[key];
      if (val === null || val === undefined) continue;
      var prop = THEME_MAP[key] || ("--" + camelToKebab(key));
      CC.__themeVars[prop] = String(val);   // remembered so Vibe Mode can restore on toggle-off
      try {
        root.style.setProperty(prop, String(val));
      } catch (e) {
        // Ignore an invalid property/value; theming is best-effort.
      }
    }
    if (Object.prototype.hasOwnProperty.call(vars, "vibe")) setVibe(!!vars.vibe);
  };

  // ---------------------------------------------------------------------------
  // 🌈 Vibe Mode: while on, a rAF loop cycles the theme vars through the spectrum so
  // EVERYTHING rainbows — transcript text, the prompt-box text, borders, accent, icons
  // (icons inherit currentColor/--accent). Hue animates; saturation/lightness are held in
  // a legible band so text stays readable. On toggle-off we restore the IDE theme verbatim.
  // ---------------------------------------------------------------------------
  var vibeOn = false;
  var vibeTimer = 0;
  var vibeHue = 0;

  function hsl(h, s, l) { return "hsl(" + Math.round(((h % 360) + 360) % 360) + "," + s + "%," + l + "%)"; }
  function hsla(h, s, l, a) { return "hsla(" + Math.round(((h % 360) + 360) % 360) + "," + s + "%," + l + "%," + a + ")"; }

  // One step of the rainbow. Driven by setInterval (NOT requestAnimationFrame): under JCEF's
  // offscreen rendering, rAF stalls when the browser thinks nothing is painting, which froze the
  // colours on a single hue. A timer keeps cycling regardless.
  function vibeStep() {
    if (!vibeOn) return;
    vibeHue = (vibeHue + 3) % 360;
    var s = document.documentElement.style;
    var h = vibeHue;
    s.setProperty("--accent", hsl(h, 90, 60));
    s.setProperty("--accent-soft", hsla(h, 90, 60, 0.20));
    s.setProperty("--text", hsl(h + 40, 85, 72));      // hue cycles; S/L held legible on dark bg
    s.setProperty("--dim", hsl(h + 90, 60, 66));
    s.setProperty("--border", hsla(h + 140, 75, 58, 0.6));
    s.setProperty("--success", hsl(h + 200, 75, 62));
    s.setProperty("--warning", hsl(h + 260, 80, 64));
  }

  function setVibe(on) {
    if (on === vibeOn) return;
    vibeOn = on;
    var body = document.body;
    if (on) {
      if (body) body.classList.add("vibe");
      vibeStep();
      if (!vibeTimer) vibeTimer = window.setInterval(vibeStep, 45);
    } else {
      if (vibeTimer) { window.clearInterval(vibeTimer); vibeTimer = 0; }
      if (body) body.classList.remove("vibe");
      // restore the IDE theme vars we overwrote
      var root = document.documentElement;
      var v = CC.__themeVars || {};
      for (var p in v) { if (Object.prototype.hasOwnProperty.call(v, p)) { try { root.style.setProperty(p, v[p]); } catch (e) {} } }
    }
    CC.vibeOn = on;
  }
  CC.isVibe = function () { return vibeOn; };

  // Nyan Cat (ported from /icons/claude-vibe.svg) — the Vibe Mode glyph for the toggle and avatar.
  CC.nyanSvg = function () {
    return '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
      '<rect x="1" y="9.2" width="8" height="1.45" fill="#FF5B5B"/>' +
      '<rect x="1" y="10.65" width="8" height="1.45" fill="#FFA63D"/>' +
      '<rect x="1" y="12.1" width="8" height="1.45" fill="#FFF03D"/>' +
      '<rect x="1" y="13.55" width="8" height="1.45" fill="#4DE06A"/>' +
      '<rect x="1" y="15.0" width="8" height="1.45" fill="#4DA6FF"/>' +
      '<rect x="1" y="16.45" width="8" height="1.45" fill="#B84DFF"/>' +
      '<rect x="8.3" y="9" width="8" height="8" rx="1.8" fill="#FF9CCB" stroke="#E86FB0" stroke-width="0.8"/>' +
      '<circle cx="10.6" cy="11.4" r="0.6" fill="#19E0E0"/><circle cx="13.2" cy="13" r="0.6" fill="#19E0E0"/>' +
      '<circle cx="11.4" cy="14.4" r="0.6" fill="#19E0E0"/>' +
      '<path d="M14.6 8.7 15.7 6.8 16.6 8.7Z" fill="#9AA0A6"/><path d="M18.6 8.7 19.5 6.8 20.6 8.7Z" fill="#9AA0A6"/>' +
      '<rect x="14.3" y="8.5" width="6.6" height="7" rx="2.2" fill="#B9BCC2" stroke="#8A8E96" stroke-width="0.8"/>' +
      '<circle cx="16.4" cy="11.4" r="0.7" fill="#1A1A1A"/><circle cx="19" cy="11.4" r="0.7" fill="#1A1A1A"/>' +
      '<circle cx="15.7" cy="13" r="0.8" fill="#FF8FB6"/><circle cx="19.7" cy="13" r="0.8" fill="#FF8FB6"/>' +
      '<path d="M17 12.9v0.7M16.2 13.6h1.6" stroke="#5A5E66" stroke-width="0.6" stroke-linecap="round"/>' +
      '<rect x="9.7" y="16.6" width="1.6" height="2" rx="0.7" fill="#B9BCC2"/>' +
      '<rect x="13.4" y="16.6" width="1.6" height="2" rx="0.7" fill="#B9BCC2"/></svg>';
  };

  // cc.theme delegates to CC.applyTheme. Null-safe: callable immediately.
  cc.theme = function (vars) { CC.applyTheme(vars); };

  // Null-safe placeholders so the host may call these before modules load.
  // Each owning module overwrites its own method(s).
  if (typeof cc.batch !== "function") cc.batch = function () {};
  if (typeof cc.clear !== "function") cc.clear = function () {};
  if (typeof cc.state !== "function") cc.state = function () {};
  if (typeof cc.meta !== "function") cc.meta = function () {};
  if (typeof cc.permissions !== "function") cc.permissions = function () {};
  if (typeof cc.openPalette !== "function") cc.openPalette = function () {};
  if (typeof cc.focusInput !== "function") cc.focusInput = function () {};
  if (typeof cc.insertText !== "function") cc.insertText = function () {};
  if (typeof cc.openDashboard !== "function") cc.openDashboard = function () {};
  if (typeof cc.attachments !== "function") cc.attachments = function () {};
  if (typeof cc.session !== "function") cc.session = function () {};
  if (typeof cc.mcp !== "function") cc.mcp = function () {};

  // appendInput(text): drop text (e.g. an @path mention from an editor action) into the composer
  // textarea at its end and focus it. DOM-queried so it works regardless of which module owns the field.
  cc.appendInput = function (text) {
    try {
      if (text == null) return;
      var ta = document.querySelector(".composer-input");
      if (!ta) return;
      var v = ta.value || "";
      if (v && !/\s$/.test(v)) v += " ";
      ta.value = v + String(text);
      ta.focus();
      ta.dispatchEvent(new Event("input", { bubbles: true }));
    } catch (e) {
      // best-effort
    }
  };

  // ---------------------------------------------------------------------------
  // Global link interception: any <a href> click → route to Kotlin, never
  // navigate. Single delegated handler installed once.
  // ---------------------------------------------------------------------------
  document.addEventListener("click", function (ev) {
    var node = ev.target;
    while (node && node !== document) {
      if (node.tagName === "A" && node.hasAttribute("href")) {
        var url = node.getAttribute("href");
        if (url && url !== "#") {
          ev.preventDefault();
          ev.stopPropagation();
          CC.send({ type: "open", url: url });
        }
        return;
      }
      node = node.parentNode;
    }
  }, true);

  // ---------------------------------------------------------------------------
  // Announce readiness once the page has loaded.
  // ---------------------------------------------------------------------------
  function announceReady() {
    CC.send({ type: "ready" });
  }
  if (document.readyState === "complete" || document.readyState === "interactive") {
    // Defer so later modules (transcript/composer/permissions) finish wiring
    // their cc.* methods before the host responds to 'ready'.
    setTimeout(announceReady, 0);
  } else {
    window.addEventListener("DOMContentLoaded", function () {
      setTimeout(announceReady, 0);
    });
  }
})();
