/*
 * app-core.js — Claude Code JCEF shell core (Agent A1).
 *
 * Creates window.cc (the Kotlin-facing API surface, populated by each module)
 * and window.CC (shared helpers + event bus + DOM mount points). Vanilla ES2019,
 * no frameworks, no external resources. Behaviour is attached via addEventListener
 * only. See JCEF_CONTRACT.md §JS MODULE PATTERN / §CODE BLOCKS / §THEME.
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
  // markdown(text): marked.parse → DOMPurify.sanitize → decorate code blocks.
  // Returns a safe HTML string. Code-block decoration (.code-head + Copy +
  // hljs) is applied to a detached fragment, then serialized back out.
  // ---------------------------------------------------------------------------
  CC.markdown = function (text) {
    if (text === null || text === undefined) return '';
    var src = String(text);
    var raw;
    try {
      // breaks:true → single newlines render as <br>, so multi-line prompts/replies keep their
      // line breaks instead of being collapsed into one run by standard Markdown.
      var mdOpts = { breaks: true, gfm: true };
      raw =
        typeof window.marked !== 'undefined' && window.marked
          ? typeof window.marked.parse === 'function'
            ? window.marked.parse(src, mdOpts)
            : window.marked(src, mdOpts)
          : CC.escape(src);
    } catch (e) {
      raw = CC.escape(src);
    }

    var clean;
    try {
      clean =
        typeof window.DOMPurify !== 'undefined' && window.DOMPurify
          ? window.DOMPurify.sanitize(raw, {
              ADD_ATTR: ['target'],
              FORBID_ATTR: ['style'],
              // Default safe schemes + our internal jb: jump-to-code links + data:image/ (inline images;
              // data:text/html stays blocked). Anything else (file:/javascript:/data:text…) is stripped.
              ALLOWED_URI_REGEXP:
                /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp|jb):|data:image\/|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$))/i,
            })
          : raw;
    } catch (e2) {
      clean = raw;
    }

    // Decorate code blocks in a detached container.
    try {
      var holder = document.createElement('div');
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
    var blocks = root.querySelectorAll('pre > code');
    for (var i = 0; i < blocks.length; i++) {
      decorateOneCodeBlock(blocks[i]);
    }
  }

  // Decorates a SINGLE `pre > code` pair with the code-head bar (language label + Copy button) and
  // syntax highlighting. Extracted from decorateCodeBlocks so a caller that already has one specific
  // <code> in hand (e.g. a tool-output block built by hand, not parsed from markdown) can reuse the exact
  // same chrome — including the Copy button, which needs no per-node listener since the click/keyboard is
  // handled by the single delegated handler below (works on any live `.code-head .copy`, wherever it came from).
  // Idempotent: a `pre` already decorated (data-cc-decorated="1") is left untouched on a later call.
  function decorateOneCodeBlock(code) {
    var pre = code && code.parentNode;
    if (!pre || pre.getAttribute('data-cc-decorated') === '1') return;
    pre.setAttribute('data-cc-decorated', '1');

    // Derive language from the `language-xxx` class hljs/marked emit; absent one, label stays generic.
    var lang = '';
    var cls = (code.className || '').split(/\s+/);
    for (var c = 0; c < cls.length; c++) {
      if (cls[c].indexOf('language-') === 0) {
        lang = cls[c].slice('language-'.length);
        break;
      }
    }

    var head = document.createElement('div');
    head.className = 'code-head';

    var label = document.createElement('span');
    label.className = 'code-lang';
    label.textContent = lang || 'text';
    head.appendChild(label);

    var copy = document.createElement('span');
    copy.className = 'copy';
    copy.setAttribute('role', 'button');
    copy.setAttribute('tabindex', '0');
    copy.textContent = 'Copy';
    head.appendChild(copy);

    pre.insertBefore(head, code);

    // Syntax-highlight the code element.
    try {
      if (
        typeof window.hljs !== 'undefined' &&
        window.hljs &&
        typeof window.hljs.highlightElement === 'function'
      ) {
        window.hljs.highlightElement(code);
      }
    } catch (e) {
      // Highlighting is best-effort.
    }
  }
  CC.decorateCodeBlocks = decorateCodeBlocks;
  CC.decorateOneCodeBlock = decorateOneCodeBlock;

  // Extension → hljs language alias, restricted to languages actually registered in the vendored bundle
  // (highlight.min.js is a curated subset, not the full hljs distribution). An unmapped/unknown extension
  // returns null — decorateOneCodeBlock then leaves the `language-xxx` class unset, and hljs.highlightElement
  // still runs its own autodetection, so the block is never left unhighlighted, just less precisely labelled.
  var EXT_LANG = {
    kt: 'kotlin',
    kts: 'kotlin',
    java: 'java',
    js: 'javascript',
    mjs: 'javascript',
    cjs: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    json: 'json',
    xml: 'xml',
    html: 'xml',
    htm: 'xml',
    svg: 'xml',
    xsd: 'xml',
    xsl: 'xml',
    plist: 'xml',
    yml: 'yaml',
    yaml: 'yaml',
    sh: 'bash',
    bash: 'bash',
    zsh: 'bash',
    py: 'python',
    rb: 'ruby',
    go: 'go',
    rs: 'rust',
    c: 'c',
    h: 'c',
    cpp: 'cpp',
    cc: 'cpp',
    cxx: 'cpp',
    hpp: 'cpp',
    hh: 'cpp',
    cs: 'csharp',
    php: 'php',
    pl: 'perl',
    pm: 'perl',
    lua: 'lua',
    sql: 'sql',
    css: 'css',
    scss: 'scss',
    less: 'less',
    md: 'markdown',
    markdown: 'markdown',
    ini: 'ini',
    cfg: 'ini',
    conf: 'ini',
    properties: 'ini',
    swift: 'swift',
    r: 'r',
    graphql: 'graphql',
    gql: 'graphql',
    vb: 'vbnet',
    wasm: 'wasm',
    wat: 'wasm',
    m: 'objectivec',
    mm: 'objectivec',
    txt: 'plaintext',
  };
  CC.languageForPath = function (path) {
    var p = String(path || '');
    var base = p.split(/[\\/]/).pop() || '';
    if (/^makefile$/i.test(base)) {
      return 'makefile';
    }
    var dot = base.lastIndexOf('.');
    if (dot < 0 || dot === base.length - 1) {
      return null;
    }
    var ext = base.slice(dot + 1).toLowerCase();
    return EXT_LANG[ext] || null;
  };

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
  // Node diagram (Workloads, and the subtab popup)
  // ---------------------------------------------------------------------------
  /**
   * A tree diagram that DESCENDS: one node per row, each child a small step to the right of its parent, with
   * an L-shaped connector down the parent's channel and into the child's left edge.
   *
   * **Why not a column per depth.** The first version put each level in its own column, which is the classic
   * org-chart shape and the wrong one here: a node was 208px wide and every level cost another 264px, so a
   * three-level tree was wider than the tool window and unusable in a popup. Descending costs INDENT (18px)
   * per level instead of a whole column, so depth is nearly free and the thing that grows is the height —
   * which is the axis you can actually scroll and drag.
   *
   * **The geometry is COMPUTED, never measured.** Every coordinate here is arithmetic on the tree, and the
   * connectors are drawn from those same numbers. That is what makes it right at every moment: the tab bar's
   * first design measured `getBoundingClientRect` at render, on a CSS transition and on resize, so it was
   * wrong whenever it was measured at the wrong one. Nothing here is measured at all.
   *
   * The cards size themselves to their text (CSS), which the layout does not need to know: a connector only
   * ever travels down the indent channel to the LEFT of a card, never across one.
   *
   * [roots] is `[{id, kind, label, meta, status, title, onPick, children:[…]}]`. Returns the canvas element;
   * the caller decides where to put it and how big the viewport is.
   */
  var NODE_H = 32;
  var COL_GAP = 26; // horizontal space between one level and the next — where the connector lives
  var ROW_GAP = 10; // vertical space between two cards on the same level
  var NODE_MIN = 170;
  var NODE_MAX = 460;

  /**
   * How wide a card needs to be for its own text — computed from the STRING, never measured from the DOM.
   *
   * A single fixed width was the wrong answer in both directions: `Agent (Inventario de dependencias)` was
   * cut to `Agent (In…` while `Chat 3` wasted two thirds of its slot. 6.6px per character is a close enough
   * average for this size of the UI font, and the clamp keeps a very long title from taking over the canvas
   * — that one still ellipsizes, and its tooltip carries the full text.
   */
  function widthFor(node) {
    var label = (node.label == null ? '' : String(node.label)).length;
    var meta = (node.meta == null ? '' : String(node.meta)).length;
    var action = node.action ? 46 : 0;
    // Sized so the label FITS rather than ellipsizes: a diagram whose every card says `Agent (Inventario…` is
    // a diagram of nothing. Wider cap, and the per-character estimate matches the 12.5px UI font.
    var px = 38 + Math.round(label * 7.1) + Math.round(meta * 5.8) + action;
    return Math.max(NODE_MIN, Math.min(NODE_MAX, px));
  }

  /**
   * How a node names itself: `Agent (…)`, `Subagent (…)`, `Background Task (…)`.
   *
   * WHAT it is comes first and the title second, because the title is the model's own sentence about the
   * job ("Inventario de dependencias") and says nothing about what kind of thing is running it. It also
   * matches the transcript, where the card that spawned the work is already labelled `Agent (…)` — the same
   * work should not have two names depending on which panel you read it in.
   *
   * Agent vs Subagent is DEPTH, not a different kind of object: an agent started by the chat is an agent,
   * one started by another agent is a subagent. [depth] 1 = a chat's own.
   */
  CC.diagramLabel = function (kind, depth, label) {
    var text = label == null ? '' : String(label);
    if (kind === 'task') return 'Background Task (' + text + ')';
    if (kind === 'agent') return (depth > 1 ? 'Subagent (' : 'Agent (') + text + ')';
    return text;
  };

  CC.diagram = function (roots) {
    var list = Array.isArray(roots) ? roots.filter(Boolean) : [];
    var placed = [];
    var cursor = 0; // next free y, in rows

    /**
     * Classic LEFT-TO-RIGHT tree placement, in one pass.
     *
     * A leaf takes the next free row; a parent is centred vertically over the span of its children. Depth is
     * the column, so the diagram grows RIGHT with nesting and DOWN with how much is running.
     *
     * **Why this way round.** Top-down put every sibling on its own column, so a chat with eight agents was
     * eight card-widths across — in a tool window that is a few hundred pixels wide, fitting that on screen
     * meant shrinking it to unreadable. Nesting rarely goes past three or four levels while siblings run to
     * dozens, so the axis that grows without bound has to be the vertical one: it is the axis a panel can
     * give, and the one a drag can follow.
     *
     * Columns are as wide as their widest card, so the whole level lines up instead of stair-stepping.
     */
    function measure(node, depth, widths) {
      widths[depth] = Math.max(widths[depth] || 0, widthFor(node));
      (Array.isArray(node.children) ? node.children.filter(Boolean) : []).forEach(function (kid) {
        measure(kid, depth + 1, widths);
      });
    }
    var colWidth = [];
    list.forEach(function (r) {
      measure(r, 0, colWidth);
    });
    var colX = [];
    colWidth.reduce(function (x, w, i) {
      colX[i] = x;
      return x + w + COL_GAP;
    }, 0);

    function place(node, depth, parent) {
      // `fresh` is decided HERE, once per node per render, so the card and its connector agree about whether
      // this is an arrival — asking twice would mark it seen on the first ask and unseen on the second.
      var me = {
        node: node,
        depth: depth,
        parent: parent,
        kids: [],
        w: colWidth[depth],
        x: colX[depth],
        fresh: isNew(node),
      };
      var kids = Array.isArray(node.children) ? node.children.filter(Boolean) : [];
      if (!kids.length) {
        me.y = cursor;
        cursor += NODE_H + ROW_GAP;
      } else {
        me.kids = kids.map(function (kid) {
          return place(kid, depth + 1, me);
        });
        // Centred on the span of its children's centres, so a group of three and a group of ten both hang
        // off the middle of their parent rather than off its top edge.
        var first = me.kids[0].y + NODE_H / 2;
        var last = me.kids[me.kids.length - 1].y + NODE_H / 2;
        me.y = (first + last) / 2 - NODE_H / 2;
      }
      placed.push(me);
      return me;
    }
    list.forEach(function (r) {
      place(r, 0, null);
    });
    if (!placed.length) return null;

    var width = 0;
    var height = 0;
    placed.forEach(function (p) {
      width = Math.max(width, p.x + p.w);
      height = Math.max(height, p.y + NODE_H);
    });

    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'dg-edges');
    svg.setAttribute('width', String(width));
    svg.setAttribute('height', String(height));
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('aria-hidden', 'true');

    var cards = [];
    placed.forEach(function (p) {
      // One connector PER CHILD, out of the parent's right edge and into the child's left edge, as a flat S
      // with horizontal tangents at both ends. Nothing is shared between siblings, so a connector can carry
      // its own child's state — and there is no bar to get out of step with the cards, which is what made
      // the previous shape look broken whenever a group was uneven.
      if (p.kids.length) {
        var x1 = p.x + p.w;
        var y1 = p.y + NODE_H / 2;
        p.kids.forEach(function (kid) {
          var x2 = kid.x;
          var y2 = kid.y + NODE_H / 2;
          var mid = x1 + (x2 - x1) / 2;
          var path = edge(
            svg,
            'M' + x1 + ' ' + y1 + ' C' + mid + ' ' + y1 + ' ' + mid + ' ' + y2 + ' ' + x2 + ' ' + y2,
            kid.node.running
          );
          // A NEW branch draws itself: the line runs out to where the card is about to be, and the card pops
          // in behind it. `stroke-dasharray` set to the path's own length and animated to zero offset is the
          // standard way to do that, and it needs the length — which only the browser knows.
          if (path && kid.fresh) drawIn(path);
        });
      }
      cards.push(diagramCard(p));
    });

    var canvas = CC.h('div', { class: 'dg-canvas', style: { width: width + 'px', height: height + 'px' } });
    canvas.appendChild(svg);
    cards.forEach(function (card) {
      canvas.appendChild(card);
    });
    return canvas;
  };

  /**
   * Ids already drawn, so a node that appears LATER can announce itself.
   *
   * A rebuild redraws everything; without this there is no way to tell "this agent just started" from "this
   * agent was already there", and either everything animates on every push (flicker) or nothing ever does.
   */
  var everSeen = {};

  // NB the arrival timings (connector draw, then card pop) live in the CSS next to the keyframes — see
  // `.dg-edge.dg-draw` and `.dg-card.dg-pop` in app.css. One place, so they cannot drift apart.

  /** A monotonic clock for animation phases. `performance.now()` where it exists; never `Date.now()` alone. */
  function nowMs() {
    return Math.round(
      typeof performance !== 'undefined' && performance && typeof performance.now === 'function'
        ? performance.now()
        : new Date().getTime()
    );
  }

  /**
   * Whether this node is being drawn for the FIRST time, and records that it has been.
   *
   * Keyed by id, remembered for the life of the page: a node that keeps appearing in every rebuild is not
   * new, it is just still running.
   */
  function isNew(node) {
    var id = node && node.id != null ? String(node.id) : null;
    if (!id || everSeen[id]) return false;
    everSeen[id] = true;
    return true;
  }

  /** Makes [path] draw itself from its start to its end. No-op where `getTotalLength` does not exist. */
  function drawIn(path) {
    if (typeof path.getTotalLength !== 'function') return;
    var len;
    try {
      len = path.getTotalLength();
    } catch (e) {
      return;
    }
    if (!len) return;
    path.style.strokeDasharray = len + ' ' + len;
    path.style.strokeDashoffset = String(len);
    path.classList.add('dg-draw');
  }

  function edge(svg, d, running) {
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'dg-edge' + (running ? ' running' : ''));
    // Same shared phase as the card it points at, so a rebuild does not restart the pulse — see the card's
    // `animationDelay`.
    if (running) path.style.animationDelay = '-' + (nowMs() % 1300) + 'ms';
    svg.appendChild(path);
    return path;
  }

  function diagramCard(p) {
    var n = p.node;
    return CC.h(
      'button',
      {
        // The STATUS is a class, so a node wears the same states — and the same animation — as the tool card
        // that represents it in the transcript. One visual language for one fact.
        class:
          'dg-card' +
          (n.kind ? ' ' + n.kind : '') +
          (n.status ? ' ' + n.status : '') +
          (n.selected ? ' selected' : '') +
          // A node that has just appeared zooms in, after its connector has finished drawing towards it.
          (p.fresh ? ' dg-pop' : ''),
        style: {
          left: p.x + 'px',
          top: p.y + 'px',
          width: p.w + 'px',
          height: NODE_H + 'px',
          // A NEGATIVE delay starts the running animation part-way through, at the phase a shared clock is
          // already at. Without it every rebuild — and the dashboard rebuilds several times a turn — restarts
          // `toolRun` from 0%, so the running nodes visibly jumped back to blue on each refresh. That was the
          // flicker; the animation itself is fine, it was being born again every second.
          animationDelay: n.status === 'running' ? '-' + (nowMs() % 1300) + 'ms' : null,
        },
        attrs: { type: 'button', title: n.title || n.label },
        on: { click: n.onPick || function () {} },
      },
      n.status ? CC.h('span', { class: 'dg-dot ' + n.status, attrs: { 'aria-hidden': 'true' } }) : null,
      CC.h('span', { class: 'dg-label', text: n.label == null ? '' : String(n.label) }),
      // The type rides on the same line, dimmed: it qualifies the node, it is not the node. Two stacked
      // lines doubled every card's height for a word most of them share.
      n.meta ? CC.h('span', { class: 'dg-meta', text: String(n.meta) }) : null,
      // An action ON the node — Stop, for a task that is still running. Inside the card because that is where
      // the thing it acts on is; it stops the click from also navigating, since stopping is not going.
      n.action
        ? CC.h('span', {
            // The page's own button style (`btn`), plus one class for where it sits inside a node.
            class: 'btn dg-action',
            attrs: { role: 'button', tabindex: '0', 'aria-label': n.action.label },
            text: n.action.label,
            on: {
              click: function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                n.action.onClick();
              },
            },
          })
        : null
    );
  }

  /**
   * Wraps a diagram in a viewport you move by dragging, the way a diagram editor works.
   *
   * Not a scroll pane: a canvas wider than the tool window, inside a panel that itself scrolls, gives two
   * scrollbars and a fight over which one the wheel belongs to. A drag has neither problem.
   *
   * A drag is not a click — the pointer must travel more than `SLOP` before the canvas starts following, and
   * the click that ends a real drag is swallowed, so releasing over a card does not also open it.
   */
  /**
   * Where each named viewport was left — zoom and offset — so a repaint does not throw it away.
   *
   * The dashboard rebuilds its cards on every state push, several times a turn, and each rebuild made a new
   * viewport that re-fitted from scratch: pan somewhere, zoom in, and a second later you were back at the
   * start for no reason you could see. Keyed by name, remembered for the life of the page.
   */
  var viewState = {};

  CC.panView = function (canvas, label, key) {
    var SLOP = 4;
    var MIN_ZOOM = 0.4;
    var MAX_ZOOM = 2.5;
    /**
     * The smallest scale [fit] may use. Legibility beats seeing everything at once.
     *
     * At 0.55 a big tree technically "fitted" and could not be read — which is not fitting, it is hiding.
     * Past this the diagram stays readable and overflows instead, and overflowing is fine: you drag it.
     */
    var LEGIBLE = 0.9;
    var view = CC.h('div', { class: 'dg-view', attrs: { tabindex: '0', 'aria-label': label || 'Diagram' } });
    view.appendChild(canvas);
    var at = { x: 0, y: 0 };
    var zoom = 1;
    var from = null;
    var moved = false;

    function apply() {
      // Translate THEN scale, in that order: the origin is the canvas's top-left (`transform-origin: 0 0`),
      // so the pan stays in screen pixels and does not get multiplied by the zoom.
      canvas.style.transform =
        'translate(' + Math.round(at.x) + 'px,' + Math.round(at.y) + 'px) scale(' + zoom.toFixed(3) + ')';
      if (key) viewState[key] = { x: at.x, y: at.y, zoom: zoom };
    }

    /**
     * Scales and centres the diagram so the WHOLE of it is on screen, then stops touching it.
     *
     * Without this the canvas simply sat at 0,0 at scale 1: anything wider or taller than the viewport was
     * cut off, and a tree whose root happened to sit past the right edge showed as an empty panel with a few
     * stray connector ends — a black rectangle with nothing in it, which is exactly what it looked like.
     *
     * This is the one place the DOM is measured, and it is measured once, for the viewport only: the
     * diagram's own geometry is still pure arithmetic. Never scales UP past 1 — a two-node tree blown up to
     * fill a panel looks broken, not helpful.
     */
    function fit() {
      var vw = view.clientWidth;
      var vh = view.clientHeight;
      var cw = canvas.offsetWidth;
      var ch = canvas.offsetHeight;
      if (!vw || !vh || !cw || !ch) return; // not laid out yet; the caller retries on the next frame
      var pad = 16;
      zoom = Math.min(1, (vw - pad * 2) / cw, (vh - pad * 2) / ch);
      // Never below LEGIBLE. A diagram scaled to 0.2 to "fit" is a picture of some grey smudges: it fits and
      // it tells you nothing. Past this point the answer is to drag, which is what the canvas is for.
      if (!(zoom > 0) || zoom < LEGIBLE) zoom = Math.max(LEGIBLE, Math.min(1, zoom || 1));
      // Left-aligned, not centred: the tree reads left to right from its root, so the root is what has to be
      // on screen. Centring a wide diagram puts its middle in view and its beginning off the edge.
      at.x = cw * zoom <= vw - pad * 2 ? (vw - cw * zoom) / 2 : pad;
      at.y = ch * zoom <= vh - pad * 2 ? (vh - ch * zoom) / 2 : pad;
      apply();
    }

    /**
     * Fits ONLY the first time this viewport is shown; afterwards it restores where the user left it.
     *
     * The dashboard rebuilds its cards on every state push, so an unconditional fit meant the diagram jumped
     * back to its default zoom and position every couple of seconds while you were trying to read it.
     */
    function fitOrRestore() {
      var saved = key && viewState[key];
      if (saved) {
        at.x = saved.x;
        at.y = saved.y;
        zoom = saved.zoom;
        apply();
        return;
      }
      fit();
    }
    // The caller inserts the view, then asks for a fit — it can only be computed once the browser has laid
    // the viewport out.
    view.__fit = fitOrRestore;

    view.addEventListener('mousedown', function (ev) {
      if (ev.button !== 0) return;
      from = { x: ev.clientX - at.x, y: ev.clientY - at.y, sx: ev.clientX, sy: ev.clientY };
      moved = false;
    });
    // Bound to the document, not the viewport: a drag that leaves the panel has to keep working, and has to
    // end even when the button comes up outside it.
    document.addEventListener('mousemove', function (ev) {
      if (!from) return;
      if (!moved && Math.abs(ev.clientX - from.sx) + Math.abs(ev.clientY - from.sy) < SLOP) return;
      moved = true;
      view.classList.add('dragging');
      at.x = ev.clientX - from.x;
      at.y = ev.clientY - from.y;
      apply();
    });
    document.addEventListener('mouseup', function () {
      if (!from) return;
      from = null;
      view.classList.remove('dragging');
    });
    /**
     * The wheel zooms, ANCHORED AT THE POINTER.
     *
     * Anchoring is what makes it usable: zooming about the origin walks whatever you were looking at off the
     * screen, so you zoom in and then have to go hunting for it. Keeping the point under the cursor fixed is
     * one line of arithmetic — convert the cursor to canvas coordinates at the old scale, and put it back at
     * the new one — and it is the difference between a zoom and a surprise.
     *
     * `preventDefault` because the panel behind this scrolls: without it the wheel would zoom AND scroll the
     * dashboard out from under the diagram.
     */
    view.addEventListener(
      'wheel',
      function (ev) {
        ev.preventDefault();
        var next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom * (ev.deltaY < 0 ? 1.12 : 1 / 1.12)));
        if (next === zoom) return;
        var r = view.getBoundingClientRect();
        var px = ev.clientX - r.left;
        var py = ev.clientY - r.top;
        at.x = px - ((px - at.x) * next) / zoom;
        at.y = py - ((py - at.y) * next) / zoom;
        zoom = next;
        apply();
      },
      { passive: false }
    );
    // Double-click on the background re-fits. Panning and zooming are easy to overdo, and without a way home
    // the diagram is simply gone — you cannot scroll back to something that does not scroll.
    view.addEventListener('dblclick', function (ev) {
      if (ev.target !== view && ev.target !== canvas) return;
      fit();
    });
    view.addEventListener(
      'click',
      function (ev) {
        if (!moved) return;
        ev.preventDefault();
        ev.stopPropagation();
        moved = false;
      },
      true
    );
    return view;
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
    dock: byId('dock'),
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

  // ---------------------------------------------------------------------------
  // applyTheme(vars): map camelCase keys → kebab CSS custom props on :root.
  // Special mappings per §THEME; everything else → --<lowercased-kebab-key>.
  // ---------------------------------------------------------------------------
  var THEME_MAP = {
    accentSoft: '--accent-soft',
    codeBg: '--code-bg',
    fontFamily: '--font',
    monoFamily: '--mono',
    fontSize: '--fs',
  };

  function camelToKebab(key) {
    return key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
  }

  CC.applyTheme = function (vars) {
    if (!vars || typeof vars !== 'object') return;
    var root = document.documentElement;
    if (!root || !root.style) return;
    CC.__themeVars = CC.__themeVars || {};
    for (var key in vars) {
      if (!Object.prototype.hasOwnProperty.call(vars, key)) continue;
      if (key === 'vibe' || key === 'reducedMotion') continue; // flags, not CSS vars — handled below
      var val = vars[key];
      if (val === null || val === undefined) continue;
      var prop = THEME_MAP[key] || '--' + camelToKebab(key);
      CC.__themeVars[prop] = String(val); // remembered so Vibe Mode can restore on toggle-off
      try {
        root.style.setProperty(prop, String(val));
      } catch (e) {
        // Ignore an invalid property/value; theming is best-effort.
      }
    }
    if (Object.prototype.hasOwnProperty.call(vars, 'vibe')) setVibe(!!vars.vibe);
    if (Object.prototype.hasOwnProperty.call(vars, 'reducedMotion')) {
      setReducedMotion(!!vars.reducedMotion);
    }
  };

  // Motion is reduced ONLY when the host says so. We deliberately do not consult
  // matchMedia('(prefers-reduced-motion: reduce)') here: JCEF renders off-screen, with no GTK window and (on
  // Wayland) no XSETTINGS bridge, so the browser has no desktop preference to report and answering from it
  // disabled every animation for everyone. See the body.reduced-motion block in app.css for the full story.
  function setReducedMotion(on) {
    var body = document.body;
    if (!body) return;
    body.classList.toggle('reduced-motion', !!on);
    // Also readable from JS: the smooth autoscroll is imperative (element.scrollTo), so no stylesheet rule can
    // reach it — the same blind spot that let Vibe Mode's rainbow keep spinning when every CSS animation was
    // flattened. Anything driven by script has to consult this flag itself.
    CC.reducedMotion = !!on;
  }

  // What the embedded browser ACTUALLY resolves — reported once, on demand, to the IDE log.
  //
  // The plugin's UI is a browser we cannot open devtools on, which makes a whole class of bug undiagnosable
  // from the outside: a CSS rule that silently does not apply is indistinguishable from a backend that never
  // sent the state. Everything below is read from the live document, not assumed.
  CC.diagnostics = function () {
    var probe = document.createElement('div');
    probe.className = 'tool loading';
    document.body.appendChild(probe);
    var computed = window.getComputedStyle(probe);
    var report = {
      reducedMotionMedia: !!(
        window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
      ),
      bodyHasReducedClass: document.body.classList.contains('reduced-motion'),
      // Does a `.tool.loading` element actually receive the animation, and for how long?
      toolAnimationName: computed.animationName,
      toolAnimationDuration: computed.animationDuration,
      toolAnimationIterations: computed.animationIterationCount,
      toolBorderColor: computed.borderTopColor,
      // color-mix drives every state border; if it is unsupported the rule is dropped and the card stays grey.
      supportsColorMix: !!(
        window.CSS &&
        CSS.supports &&
        CSS.supports('color', 'color-mix(in srgb, red 50%, blue)')
      ),
      infoVar: getComputedStyle(document.documentElement).getPropertyValue('--info').trim(),
      warningVar: getComputedStyle(document.documentElement).getPropertyValue('--warning').trim(),
      styleSheets: document.styleSheets.length,
      userAgent: navigator.userAgent,
    };
    probe.remove();
    CC.send({ type: 'diag', report: JSON.stringify(report) });
    return report;
  };
  cc.diagnostics = function () {
    return CC.diagnostics();
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

  function hsl(h, s, l) {
    return 'hsl(' + Math.round(((h % 360) + 360) % 360) + ',' + s + '%,' + l + '%)';
  }
  function hsla(h, s, l, a) {
    return 'hsla(' + Math.round(((h % 360) + 360) % 360) + ',' + s + '%,' + l + '%,' + a + ')';
  }

  // One step of the rainbow. Driven by setInterval (NOT requestAnimationFrame): under JCEF's
  // offscreen rendering, rAF stalls when the browser thinks nothing is painting, which froze the
  // colours on a single hue. A timer keeps cycling regardless.
  function vibeStep() {
    if (!vibeOn) return;
    vibeHue = (vibeHue + 6) % 360; // faster rainbow — ~1.8s per full cycle (was ~5.4s); see also the timer below
    var s = document.documentElement.style;
    var h = vibeHue;
    s.setProperty('--accent', hsl(h, 90, 60));
    s.setProperty('--accent-soft', hsla(h, 90, 60, 0.2));
    s.setProperty('--text', hsl(h + 40, 85, 72)); // hue cycles; S/L held legible on dark bg
    s.setProperty('--dim', hsl(h + 90, 60, 66));
    s.setProperty('--border', hsla(h + 140, 75, 58, 0.6));
    s.setProperty('--success', hsl(h + 200, 75, 62));
    s.setProperty('--warning', hsl(h + 260, 80, 64));
  }

  function setVibe(on) {
    if (on === vibeOn) return;
    vibeOn = on;
    var body = document.body;
    if (on) {
      if (body) body.classList.add('vibe');
      vibeStep();
      if (!vibeTimer) vibeTimer = window.setInterval(vibeStep, 30);
    } else {
      if (vibeTimer) {
        window.clearInterval(vibeTimer);
        vibeTimer = 0;
      }
      if (body) body.classList.remove('vibe');
      // restore the IDE theme vars we overwrote
      var root = document.documentElement;
      var v = CC.__themeVars || {};
      for (var p in v) {
        if (Object.prototype.hasOwnProperty.call(v, p)) {
          try {
            root.style.setProperty(p, v[p]);
          } catch (e) {
            // A saved var the browser now rejects: skip it and restore the rest. Losing one colour is
            // strictly better than aborting the loop and leaving the theme half-reverted.
          }
        }
      }
    }
    CC.vibeOn = on;
  }
  CC.isVibe = function () {
    return vibeOn;
  };

  // Nyan Cat (ported from /icons/claude-vibe.svg) — the Vibe Mode glyph for the toggle and avatar.
  CC.nyanSvg = function () {
    return (
      '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">' +
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
      '<rect x="13.4" y="16.6" width="1.6" height="2" rx="0.7" fill="#B9BCC2"/></svg>'
    );
  };

  // cc.theme delegates to CC.applyTheme. Null-safe: callable immediately.
  cc.theme = function (vars) {
    CC.applyTheme(vars);
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

  // appendInput(text): drop text (e.g. an @path mention from an editor action) into the composer
  // textarea at its end and focus it. DOM-queried so it works regardless of which module owns the field.
  cc.appendInput = function (text) {
    try {
      if (text == null) return;
      var ta = document.querySelector('.composer-input');
      if (!ta) return;
      var v = ta.value || '';
      if (v && !/\s$/.test(v)) v += ' ';
      ta.value = v + String(text);
      ta.focus();
      ta.dispatchEvent(new Event('input', { bubbles: true }));
    } catch (e) {
      // best-effort
    }
  };

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
