/* app-transcript.js — A2 (transcript)
 *
 * Implements cc.batch(entries) and cc.clear() plus the search hook per §TRANSCRIPT.
 * Relies ONLY on globals created by app-core.js (window.CC): h, escape, markdown,
 * on/emit, els, send. Vanilla ES2019, addEventListener only, null-safe.
 */
(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});

  // ---- shared helpers (degrade gracefully if core not yet ready) ----------
  function CCobj() { return window.CC || {}; }
  function el(tag, props) {
    var C = CCobj();
    if (C.h) { return C.h(tag, props); }
    // minimal fallback hyperscript
    var node = document.createElement(tag);
    props = props || {};
    if (props.class) { node.className = props.class; }
    if (props.text != null) { node.textContent = String(props.text); }
    if (props.html != null) { node.innerHTML = props.html; }
    if (props.title != null) { node.title = String(props.title); }
    if (props.attrs) { for (var a in props.attrs) { if (props.attrs.hasOwnProperty(a)) { node.setAttribute(a, props.attrs[a]); } } }
    if (props.on) { for (var ev in props.on) { if (props.on.hasOwnProperty(ev)) { node.addEventListener(ev, props.on[ev]); } } }
    return node;
  }
  function md(text) {
    var C = CCobj();
    if (C.markdown) { try { return C.markdown(text == null ? '' : text); } catch (e) { /* fall through */ } }
    return esc(text);
  }
  function esc(text) {
    var C = CCobj();
    if (C.escape) { return C.escape(text == null ? '' : text); }
    var d = document.createElement('div');
    d.textContent = text == null ? '' : String(text);
    return d.innerHTML;
  }
  function safeSend(obj) {
    var C = CCobj();
    if (C.send) { try { C.send(obj); } catch (e) { /* ignore */ } }
  }
  function conversationEl() {
    var C = CCobj();
    var node = (C.els && C.els.conversation) || document.getElementById('conversation');
    return node || null;
  }
  function emptyEl() {
    return document.getElementById('empty');
  }

  // ---- state --------------------------------------------------------------
  // id -> { el, speaker, bodyNode, kind, text, meta, state, toolUseId }
  var rows = new Map();
  // toolUseId -> tool card element (the .tool node)
  var toolCards = new Map();

  // ---- autoscroll / follow -----------------------------------------------
  var followFlag = false;
  var NEAR_BOTTOM = 80;
  var scrollScheduled = false;

  function isNearBottom() {
    var c = conversationEl();
    if (!c) { return true; }
    var distance = c.scrollHeight - c.scrollTop - c.clientHeight;
    return distance <= NEAR_BOTTOM;
  }
  function scheduleScroll(stick) {
    if (!stick) { return; }
    if (scrollScheduled) { return; }
    scrollScheduled = true;
    var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };
    raf(function () {
      scrollScheduled = false;
      var c = conversationEl();
      if (c) { c.scrollTop = c.scrollHeight; }
    });
  }

  // ---- copy affordance ----------------------------------------------------
  function copyButton(getText) {
    return el('span', {
      class: 'act copy',
      text: 'Copy',
      title: 'Copy',
      on: {
        click: function (e) {
          e.preventDefault();
          e.stopPropagation();
          safeSend({ type: 'copy', text: getText() });
        }
      }
    });
  }

  // ---- per-speaker builders ----------------------------------------------
  // Each returns { el, bodyNode, kind } and is "stateless": update() handles refresh.

  function buildUser() {
    var body = el('div', { class: 'body' });
    var head = el('div', { class: 'msg-head' });
    head.appendChild(el('span', { class: 'name', text: 'You' }));
    var copy = copyButton(function () { return body.__rawText || ''; });
    head.appendChild(copy);
    var node = el('div', { class: 'msg user' });
    node.appendChild(head);
    node.appendChild(body);
    // User prompts are shown VERBATIM — never run through marked/markdown. What the
    // user typed is what the model received; rendering it as Markdown would mangle
    // literal `*`, backticks, `#`, indentation, etc. 'text' uses textContent (no
    // parsing, no sanitize) and CSS .msg.user .body keeps white-space: pre-wrap so
    // newlines/indentation survive.
    return { el: node, bodyNode: body, kind: 'text' };
  }

  function buildAssistant() {
    var body = el('div', { class: 'body' });
    var head = el('div', { class: 'msg-head' });
    // avatar: ✶ normally, Nyan Cat while Vibe Mode is on (swap via body.vibe in CSS)
    var avatar = el('span', { class: 'avatar' });
    avatar.appendChild(el('span', { class: 'avatar-star', text: '✶' }));
    avatar.appendChild(el('span', { class: 'avatar-nyan', html: (window.CC && window.CC.nyanSvg) ? window.CC.nyanSvg() : '' }));
    head.appendChild(avatar);
    head.appendChild(el('span', { class: 'name', text: 'Claude' }));
    var copy = copyButton(function () { return body.__rawText || ''; });
    head.appendChild(copy);
    var node = el('div', { class: 'msg assistant' });
    node.appendChild(head);
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: 'md' };
  }

  // Global expand/collapse preference for reasoning ("Thought process") folds.
  // Ctrl/Cmd+O flips it; new THINKING folds are created in this state so the
  // toggle is respected for blocks that stream in later, not just existing ones.
  var reasoningExpanded = false; // Thought-process folds start collapsed; Ctrl/Cmd+O expands them

  function buildFold(summaryText, dim, startOpen, hint) {
    var node = el('details', { class: 'fold' + (dim ? ' dim' : '') });
    node.open = (startOpen !== false);
    var summary = el('summary', {});
    summary.appendChild(el('span', { class: 'tri' }));
    var label = el('span', { class: 'fold-label', text: summaryText });
    summary.appendChild(label);
    if (hint) { summary.appendChild(el('span', { class: 'fold-hint', text: 'Press Ctrl+O to expand' })); }
    var body = el('div', { class: 'body fold-body' });
    node.appendChild(summary);
    node.appendChild(body);
    node.__label = label;
    return { el: node, bodyNode: body, kind: 'md' };
  }

  function buildThinking() {
    var rec = buildFold('Thought process', true, reasoningExpanded, true);
    rec.el.classList.add('reasoning'); // so Ctrl+O toggles thought-process folds only, not memory folds
    return rec;
  }

  function buildMemory(meta) {
    var title = (meta && String(meta).trim()) ? String(meta) : '🧠 Recalled memories';
    return buildFold(title, true);
  }

  // Per-tool-type inline SVG icons (themeable: stroke/fill = currentColor, so they follow the
  // tool state colour and ride Vibe Mode). Ported from resources/icons/tool-*.svg.
  var TOOL_ICONS = {
    bash: '<rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="m4.5 6.25 2 1.75-2 1.75"/><path d="M8 10.25h3"/>',
    read: '<path d="M8 4.25C6.75 3.4 5.4 3 3.5 3H2v8.5h1.75c1.6 0 3 .35 4.25 1.25"/><path d="M8 4.25C9.25 3.4 10.6 3 12.5 3H14v8.5h-1.75c-1.6 0-3 .35-4.25 1.25"/><path d="M8 4.25v8.5"/>',
    edit: '<path d="M10.75 2.5 13.5 5.25 6 12.75 2.75 13.5l.75-3.25z"/><path d="M9.25 4 12 6.75"/>',
    search: '<circle cx="7" cy="7" r="4.25"/><path d="m10.25 10.25 3 3"/>',
    web: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12"/><path d="M8 2c1.75 1.6 2.75 3.7 2.75 6S9.75 12.4 8 14C6.25 12.4 5.25 10.3 5.25 8S6.25 3.6 8 2z"/>',
    task: '<circle cx="8" cy="8" r="6"/><path d="M6.5 5.5 11 8l-4.5 2.5z" fill="currentColor"/>',
    generic: '<path d="M6.25 2.75h3.5v1.5a1.25 1.25 0 1 0 2.5 0v-1.5h.75v3.5h1.5a1.25 1.25 0 1 1 0 2.5h-1.5v3.5H9.5v-1.5a1.25 1.25 0 1 0-2.5 0v1.5H3.5v-3.5H2a1.25 1.25 0 1 1 0-2.5h1.5v-3.5h2.75z"/>'
  };
  function toolIconKey(meta) {
    var m = meta == null ? '' : String(meta);
    if (m === 'Bash') return 'bash';
    if (m === 'Read' || m === 'NotebookRead') return 'read';
    if (m === 'Edit' || m === 'Write' || m === 'MultiEdit' || m === 'NotebookEdit') return 'edit';
    if (m === 'Grep' || m === 'Glob' || m === 'Search') return 'search';
    if (m === 'WebFetch' || m === 'WebSearch') return 'web';
    if (m === 'Task' || m === 'Agent') return 'task';
    return 'generic';
  }
  function toolIconSvg(meta) {
    var inner = TOOL_ICONS[toolIconKey(meta)] || TOOL_ICONS.generic;
    return '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
  }

  function buildTool() {
    var node = el('div', { class: 'tool' });
    var head = el('div', { class: 'tool-head' });
    head.appendChild(el('span', { class: 'ic', text: '▸' })); // ▸ (replaced with a tool icon in createRow)
    var name = el('span', { class: 'name' });
    head.appendChild(name);
    var elapsed = el('span', { class: 'tool-elapsed' });
    elapsed.hidden = true;
    head.appendChild(elapsed);
    // "View diff" + "Restore" for completed Edit/Write/MultiEdit cards (shown when entry.reviewable).
    var diffBtn = el('button', { class: 'tool-diff', text: 'View diff', attrs: { type: 'button' } });
    diffBtn.hidden = true;
    diffBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) { safeSend({ type: 'viewDiffByTool', toolUseId: node.__toolUseId }); }
    });
    head.appendChild(diffBtn);
    var restoreBtn = el('button', { class: 'tool-restore', text: 'Restore', attrs: { type: 'button' }, title: 'Revert this edit' });
    restoreBtn.hidden = true;
    restoreBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) { safeSend({ type: 'revertEdit', toolUseId: node.__toolUseId }); }
    });
    head.appendChild(restoreBtn);
    node.__restoreBtn = restoreBtn;
    head.appendChild(el('span', { class: 'chev', text: '▾' })); // ▾
    var out = el('div', { class: 'tool-out' });
    // Nested subagent activity (rows whose `parent` is this tool's id) lands here,
    // distinct from this tool's own routed output (.tool-out).
    var children = el('div', { class: 'tool-children' });
    head.addEventListener('click', function () {
      node.classList.toggle('open');
    });
    node.appendChild(head);
    node.appendChild(out);
    node.appendChild(children);
    node.__nameNode = name;
    node.__outNode = out;
    node.__childrenNode = children;
    node.__elapsedNode = elapsed;
    node.__diffBtn = diffBtn;
    return { el: node, bodyNode: name, kind: 'tool', outNode: out };
  }

  // Format an elapsed-seconds value as a dim "· N.Ns" suffix; null-safe.
  function formatElapsed(secs) {
    var n = Number(secs);
    if (!isFinite(n) || n <= 0) { return ''; }
    return '· ' + n.toFixed(1) + 's';
  }

  // Show/hide the .tool-elapsed badge based on RUNNING state.
  function applyToolElapsed(node, state, elapsedSecs) {
    var badge = node.__elapsedNode || node.querySelector('.tool-elapsed');
    if (!badge) { return; }
    var running = (state === 'RUNNING' || state === 'LOADING');
    var label = running ? formatElapsed(elapsedSecs) : '';
    if (label) {
      badge.textContent = label;
      badge.hidden = false;
    } else {
      badge.textContent = '';
      badge.hidden = true;
    }
  }

  function buildNotice(isError) {
    var node = el('div', { class: isError ? 'notice error' : 'notice' });
    var body = el('div', { class: 'body' });
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: isError ? 'text' : 'md' };
  }

  function buildToolOutputStandalone() {
    var node = el('div', { class: 'notice tool-output' });
    var pre = el('pre', {});
    var code = el('code', {});
    pre.appendChild(code);
    node.appendChild(pre);
    return { el: node, bodyNode: code, kind: 'pre' };
  }

  function builderFor(speaker, entry) {
    switch (speaker) {
      case 'USER': return buildUser();
      case 'ASSISTANT': return buildAssistant();
      case 'THINKING': return buildThinking();
      case 'MEMORY': return buildMemory(entry.meta);
      case 'TOOL': return buildTool();
      case 'TOOL_OUTPUT': return buildToolOutputStandalone();
      case 'ERROR': return buildNotice(true);
      case 'SYSTEM': return buildNotice(false);
      default: return buildNotice(false);
    }
  }

  // ---- body rendering -----------------------------------------------------
  function setBody(rec, text) {
    var body = rec.bodyNode;
    if (!body) { return; }
    var kind = rec.kind;
    if (kind === 'md') {
      body.innerHTML = md(text);
      body.__rawText = text == null ? '' : String(text);
    } else if (kind === 'pre') {
      body.textContent = text == null ? '' : String(text);
    } else { // text / tool name
      body.textContent = text == null ? '' : String(text);
      body.__rawText = text == null ? '' : String(text);
    }
  }

  function applyToolState(node, state, meta) {
    node.classList.remove('loading', 'running', 'done', 'failed');
    if (state === 'ERROR' || meta === 'error') {
      node.classList.add('failed');          // red — wins over done/loading
    } else if (state === 'LOADING') {
      node.classList.add('loading');         // fade sky-blue ↔ amber (active)
    } else if (state === 'RUNNING') {
      node.classList.add('running');         // fade sky-blue ↔ amber
    } else if (state === 'FINISHED') {
      node.classList.add('done');            // green
    }
  }

  // ---- TOOL_OUTPUT routing ------------------------------------------------
  function routeToolOutput(entry) {
    // returns true if it was routed into an existing tool card
    var tid = entry.toolUseId;
    if (!tid) { return false; }
    var card = toolCards.get(tid);
    if (!card) { return false; }
    var out = card.__outNode || card.querySelector('.tool-out');
    if (!out) { return false; }
    // store/append a pre>code block keyed by entry id so updates replace it
    var pid = 'to-' + entry.id;
    var block = out.querySelector('[data-out-id="' + pid + '"]');
    if (!block) {
      block = el('pre', {});
      block.setAttribute('data-out-id', pid);
      var code = el('code', {});
      block.appendChild(code);
      out.appendChild(block);
    }
    var codeEl = block.querySelector('code');
    if (codeEl) {
      if (entry.meta === 'diff') {
        renderDiff(codeEl, entry.text == null ? '' : String(entry.text));
        block.classList.add('diff');
      } else {
        block.classList.remove('diff');
        codeEl.textContent = entry.text == null ? '' : String(entry.text);
      }
    }
    return true;
  }

  // Render a unified diff with per-line colour (added/removed/hunk/context).
  function renderDiff(codeEl, text) {
    codeEl.innerHTML = '';
    var lines = String(text).split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var c0 = line.charAt(0);
      var cls = 'dl-ctx';
      if (line.indexOf('@@') === 0) { cls = 'dl-hunk'; }
      else if (c0 === '+') { cls = 'dl-add'; }
      else if (c0 === '-') { cls = 'dl-del'; }
      codeEl.appendChild(el('span', { class: 'diff-line ' + cls, text: (i < lines.length - 1 ? line + '\n' : line) }));
    }
  }

  // ---- upsert -------------------------------------------------------------
  function isAgentTool(meta) {
    var m = meta == null ? '' : String(meta);
    return m === 'Task' || m === 'Agent';
  }

  function createRow(entry) {
    var rec = builderFor(entry.speaker, entry);
    rec.speaker = entry.speaker;
    rec.toolUseId = entry.toolUseId || null;
    if (entry.speaker === 'TOOL' && entry.toolUseId) {
      rec.outNode = rec.el.__outNode || rec.el.querySelector('.tool-out');
      rec.el.__toolUseId = entry.toolUseId;
      toolCards.set(entry.toolUseId, rec.el);
      // All tool cards (incl. Agent/Task) start collapsed and toggle on click — predictable, and
      // never auto-expanded (which read as "stuck open"). Expand an Agent to see its nested activity.
    }
    if (entry.speaker === 'TOOL') {
      var icNode = rec.el.querySelector('.ic');
      if (icNode) { icNode.innerHTML = toolIconSvg(entry.meta); }
    }
    return rec;
  }

  // Build a `jb://open` href for a PROJECT-RELATIVE path (+ optional 1-based line). The host resolves it against
  // the project root and refuses anything outside it (isWithinRoot), so this never needs an absolute path.
  function jbHref(relPath, line) {
    var u = 'jb://open?file=' + encodeURIComponent(String(relPath));
    if (line) { u += '&line=' + encodeURIComponent(String(line)); }
    return u;
  }

  // A file tool's label — `Read(src/main/kotlin/permission/PermissionBroker.kt)` — with the PATH as a clickable
  // jump-to-code link. The path is inserted via textContent (never innerHTML), so a hostile path can't inject
  // markup; the delegated <a> handler in app-core routes the click to the host.
  function renderToolLabel(nameEl, text, filePath) {
    if (!nameEl) { return; }
    var label = text == null ? '' : String(text);
    var p = String(filePath);
    var at = label.indexOf(p);
    if (at < 0) { nameEl.textContent = label; return; } // path not in the label — render plainly
    while (nameEl.firstChild) { nameEl.removeChild(nameEl.firstChild); }
    nameEl.appendChild(document.createTextNode(label.slice(0, at)));
    var a = el('a', { class: 'jb-link', text: p, attrs: { href: jbHref(p), title: 'Open ' + p } });
    // The card head toggles collapse on click — don't collapse it just because the user followed the link.
    a.addEventListener('click', function (e) { e.stopPropagation(); });
    nameEl.appendChild(a);
    nameEl.appendChild(document.createTextNode(label.slice(at + p.length)));
  }

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
      '[\\w.-]+\\/(?:[\\w.-]+\\/?)*' +                          // 2. has segments (incl. a trailing-slash dir)
      '|' +
      '[\\w.-]+\\.[A-Za-z][\\w]{0,9}' +                         // 3. bare name with an extension
    ')(?::\\d+)?',
    'g',
  );
  // A plausible symbol: CamelCase (PermissionBroker) or a call (resolvePermission()). Deliberately conservative —
  // the host rejects anything that isn't a real declaration anyway, this just keeps the batch small.
  var SYMBOL_RE = /\b([A-Z][A-Za-z0-9]{2,}|[a-z][A-Za-z0-9]{2,}(?=\(\)))\b/g;

  function collectCandidates(root) {
    var paths = {}, symbols = {};
    // Only look inside inline code spans and plain text — never inside fenced code blocks (a whole file's source
    // would flood the batch with noise) and never inside an existing link.
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode;
        while (p && p !== root) {
          var t = p.tagName;
          if (t === 'A') { return NodeFilter.FILTER_REJECT; }
          if (t === 'PRE') { return NodeFilter.FILTER_REJECT; }
          p = p.parentNode;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n, m;
    while ((n = walker.nextNode())) {
      var txt = n.nodeValue || '';
      PATH_RE.lastIndex = 0;
      while ((m = PATH_RE.exec(txt))) { paths[m[0]] = true; }
      var inCode = n.parentNode && n.parentNode.tagName === 'CODE';
      if (inCode) { // symbols only inside `code spans` — prose would produce mostly noise
        SYMBOL_RE.lastIndex = 0;
        while ((m = SYMBOL_RE.exec(txt))) { symbols[m[1]] = true; }
      }
    }
    return { paths: Object.keys(paths), symbols: Object.keys(symbols) };
  }

  function requestLinks(rec, entry) {
    if (!rec || !rec.bodyNode) { return; }
    var c = collectCandidates(rec.bodyNode);
    if (!c.paths.length && !c.symbols.length) { return; }
    safeSend({ type: 'resolveLinks', rowId: entry.id, paths: c.paths, symbols: c.symbols });
  }

  // Host answered: turn the confirmed tokens into links, in place, without re-rendering the row.
  function applyLinks(payload) {
    if (!payload || payload.rowId == null) { return; }
    var rec = rows.get(payload.rowId);
    if (!rec || !rec.bodyNode) { return; }
    var links = Array.isArray(payload.links) ? payload.links : [];
    if (!links.length) { return; }
    // Longest token first, so `Foo.kt:42` wins over `Foo.kt`.
    links.sort(function (a, b) { return String(b.token).length - String(a.token).length; });
    for (var i = 0; i < links.length; i++) { linkifyToken(rec.bodyNode, links[i]); }
  }

  // A token may only be linked as a WHOLE token — never as a fragment of something bigger. Without this, a
  // resolved `src/main/kotlin/dev/ui` would also linkify inside `src/main/kotlin/dev/ui/Fantasma.kt` (a file that
  // does NOT exist), and a resolved `Session` would light up inside `ClaudeSession`. So: no path/word character
  // may sit right before or right after the match.
  var TOKEN_LEFT = /[\w.\-/~]/;
  var TOKEN_RIGHT = /[\w.\-/]/;
  function atTokenBoundary(txt, at, token) {
    if (at > 0 && TOKEN_LEFT.test(txt.charAt(at - 1))) { return false; }
    var after = txt.charAt(at + token.length);
    return !(after && TOKEN_RIGHT.test(after));
  }

  // Replace every whole-token occurrence of link.token in root's text nodes with an <a>. The link text is set via
  // textContent (never innerHTML), so a hostile token cannot inject markup.
  function linkifyToken(root, link) {
    var token = String(link.token || '');
    if (!token) { return; }
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode;
        while (p && p !== root) {
          if (p.tagName === 'A' || p.tagName === 'PRE') { return NodeFilter.FILTER_REJECT; }
          p = p.parentNode;
        }
        return (n.nodeValue && n.nodeValue.indexOf(token) >= 0) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    var targets = [], n;
    while ((n = walker.nextNode())) { targets.push(n); }
    for (var i = 0; i < targets.length; i++) {
      var node = targets[i];
      var txt = node.nodeValue;
      var frag = document.createDocumentFragment();
      var from = 0, hit = false;
      // Walk EVERY occurrence in this node (the old code linked only the first one and dropped the rest).
      for (var at = txt.indexOf(token); at >= 0; at = txt.indexOf(token, at + token.length)) {
        if (!atTokenBoundary(txt, at, token)) { continue; }
        frag.appendChild(document.createTextNode(txt.slice(from, at)));
        frag.appendChild(el('a', {
          class: 'jb-link', text: token,
          attrs: { href: jbHref(link.path, link.line), title: 'Open ' + link.path + (link.line ? ':' + link.line : '') }
        }));
        from = at + token.length;
        hit = true;
      }
      if (!hit) { continue; } // nothing was a whole token here — leave the node exactly as it was
      frag.appendChild(document.createTextNode(txt.slice(from)));
      node.parentNode.replaceChild(frag, node);
    }
  }

  function updateRow(rec, entry) {
    // refresh body text (tool name for TOOL). A file tool renders its path as a jump-to-code link.
    if (rec.speaker === 'TOOL' && entry.filePath) {
      renderToolLabel(rec.bodyNode, entry.text, entry.filePath);
    } else {
      setBody(rec, entry.text);
      // Model prose/code spans: ask the host to confirm which paths/symbols are real, then link those. Only for
      // settled assistant rows — doing it per streaming delta would spam the host and fight the re-render.
      if (rec.speaker === 'ASSISTANT' && entry.state !== 'RUNNING') { requestLinks(rec, entry); }
    }
    rec.text = entry.text;
    rec.meta = entry.meta;
    rec.state = entry.state;
    if (rec.speaker === 'TOOL') {
      applyToolState(rec.el, entry.state, entry.meta);
      applyToolElapsed(rec.el, entry.state, entry.elapsed);
      if (rec.el.__diffBtn) { rec.el.__diffBtn.hidden = !entry.reviewable; }
      if (rec.el.__restoreBtn) { rec.el.__restoreBtn.hidden = !entry.reviewable; }
    }
    if (rec.speaker === 'MEMORY' && rec.el.__label) {
      var title = (entry.meta && String(entry.meta).trim()) ? String(entry.meta) : '🧠 Recalled memories';
      rec.el.__label.textContent = title;
    }
  }

  function upsert(entry) {
    if (entry == null || entry.id == null) { return null; }

    // TOOL_OUTPUT: try routing into an existing tool card first.
    if (entry.speaker === 'TOOL_OUTPUT') {
      if (routeToolOutput(entry)) {
        // no standalone row needed; if a previously-standalone row exists, leave it.
        return rows.get(entry.id) || null;
      }
    }

    var rec = rows.get(entry.id);
    if (rec && rec.speaker !== entry.speaker) {
      // speaker changed for same id (rare) — rebuild
      if (rec.el && rec.el.parentNode) { rec.el.parentNode.removeChild(rec.el); }
      if (rec.toolUseId) { toolCards.delete(rec.toolUseId); }
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

  // ---- reposition ---------------------------------------------------------
  // The container a row belongs in: a subagent row (one with a `parent` whose Agent/Task
  // card exists) nests inside that card's .tool-children box; everything else is top-level
  // in #conversation. Falls back to #conversation if the parent card isn't built yet.
  function containerFor(entry) {
    if (entry.parent) {
      var parentCard = toolCards.get(entry.parent);
      if (parentCard) {
        return parentCard.__childrenNode || parentCard.querySelector('.tool-children') || conversationEl();
      }
    }
    return conversationEl();
  }

  // Order is a GLOBAL flat index across the whole transcript, but rows are split across
  // containers once subagents nest. So we order WITHIN a container by comparing each
  // managed sibling's stored __order (set here), not by raw child index — which also makes
  // us robust to any non-managed nodes (#empty, tool heads) sharing a container.
  function reposition(entry) {
    var rec = rows.get(entry.id);
    if (!rec || !rec.el) { return; }
    var order = entry.order;
    rec.el.__order = (typeof order === 'number' && order >= 0) ? order : null;
    var container = containerFor(entry);
    if (!container) { return; }

    var ref = null;
    if (rec.el.__order != null) {
      var kids = container.children;
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        if (k === rec.el) { continue; }
        if (k.__order == null) { continue; }       // skip non-managed nodes
        if (k.__order > rec.el.__order) { ref = k; break; }
      }
    }
    // already correctly placed → no move (avoids needless reflow/scroll jumps)
    if (rec.el.parentNode === container && rec.el.nextSibling === ref) { return; }
    if (ref) { container.insertBefore(rec.el, ref); }
    else { container.appendChild(rec.el); }
  }

  function showEmptyState(show) {
    var empty = emptyEl();
    if (empty) { empty.hidden = !show; }
  }

  // ---- public: cc.batch ---------------------------------------------------
  cc.batch = function (entries) {
    if (!entries) { return; }
    if (!Array.isArray(entries)) {
      // tolerate a single entry or {entries:[...]}
      if (entries.entries && Array.isArray(entries.entries)) { entries = entries.entries; }
      else { entries = [entries]; }
    }
    var c = conversationEl();
    var stick = followFlag || isNearBottom();

    // First pass: upsert all (so tool cards exist before outputs route).
    for (var i = 0; i < entries.length; i++) {
      upsert(entries[i]);
    }
    // Second pass: reposition rows that have DOM nodes.
    for (var j = 0; j < entries.length; j++) {
      var e = entries[j];
      if (e && e.id != null && e.speaker !== 'TOOL_OUTPUT') {
        reposition(e);
      } else if (e && e.id != null && e.speaker === 'TOOL_OUTPUT' && rows.has(e.id)) {
        // standalone tool-output row (no matching card)
        reposition(e);
      }
    }

    if (rows.size > 0 || (c && c.children.length > 0)) {
      showEmptyState(false);
    }

    // re-apply active search highlight to refreshed bodies
    if (currentQuery) { runSearch(currentQuery, true); }

    scheduleScroll(stick);
  };

  // ---- public: cc.clear ---------------------------------------------------
  // Host's answer to `resolveLinks`: upgrade the confirmed tokens in that row to jump-to-code links.
  cc.links = function (payload) {
    try { applyLinks(payload); } catch (e) { /* linkification is best-effort — never break the transcript */ }
  };

  cc.clear = function () {
    rows.clear();
    toolCards.clear();
    var c = conversationEl();
    if (c) {
      // remove everything except a persistent #empty if it lives inside
      var kids = Array.prototype.slice.call(c.children);
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].id === 'empty') { continue; }
        c.removeChild(kids[i]);
      }
    }
    currentQuery = '';
    searchHits = [];
    showEmptyState(true);
  };

  // ---- search -------------------------------------------------------------
  var currentQuery = '';
  var searchHits = [];
  var activeIndex = 0;

  // Move the active highlight to hit [i] (wrapping), scroll it into view, and refresh the counter. The find bar
  // previously marked the first hit but never scrolled to it and offered no next/prev — you could see "10 matches"
  // and never reach any of them.
  function setActiveHit(i, scroll) {
    if (!searchHits.length) { activeIndex = 0; updateFindCount(); return; }
    var n = searchHits.length;
    activeIndex = ((i % n) + n) % n; // wrap both directions
    for (var k = 0; k < n; k++) searchHits[k].classList.remove('active');
    var hit = searchHits[activeIndex];
    hit.classList.add('active');
    // Only scroll on an explicit navigation (fresh query / next / prev). The silent re-highlight that runs on
    // every streaming batch must NOT scroll, or it would yank the viewport to the active match on every frame
    // and fight auto-follow.
    if (scroll) { try { hit.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { /* older engines */ } }
    updateFindCount();
  }
  function nextHit() { setActiveHit(activeIndex + 1, true); }
  function prevHit() { setActiveHit(activeIndex - 1, true); }

  function clearHighlights() {
    var c = conversationEl();
    if (!c) { return; }
    var marks = c.querySelectorAll('mark.cc-hit');
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      var parent = m.parentNode;
      if (!parent) { continue; }
      // replace mark with its text
      var txt = document.createTextNode(m.textContent || '');
      parent.replaceChild(txt, m);
      parent.normalize();
    }
    searchHits = [];
  }

  function highlightInNode(node, lower) {
    var count = 0;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null, false);
    var textNodes = [];
    var n;
    while ((n = walker.nextNode())) {
      // skip text already inside code-head copy controls etc. — fine to include
      if (n.nodeValue && n.nodeValue.length) { textNodes.push(n); }
    }
    for (var i = 0; i < textNodes.length; i++) {
      var tn = textNodes[i];
      var val = tn.nodeValue;
      var hay = val.toLowerCase();
      if (hay.indexOf(lower) === -1) { continue; }
      var frag = document.createDocumentFragment();
      var idx = 0;
      var pos;
      while ((pos = hay.indexOf(lower, idx)) !== -1) {
        if (pos > idx) { frag.appendChild(document.createTextNode(val.slice(idx, pos))); }
        var mark = document.createElement('mark');
        mark.className = 'cc-hit';
        mark.textContent = val.slice(pos, pos + lower.length);
        frag.appendChild(mark);
        searchHits.push(mark);
        count++;
        idx = pos + lower.length;
      }
      if (idx < val.length) { frag.appendChild(document.createTextNode(val.slice(idx))); }
      if (tn.parentNode) { tn.parentNode.replaceChild(frag, tn); }
    }
    return count;
  }

  function runSearch(q, silent) {
    clearHighlights();
    currentQuery = q || '';
    if (!currentQuery) {
      if (!silent) { safeSend({ type: 'search', count: 0 }); }
      return;
    }
    var lower = currentQuery.toLowerCase();
    var total = 0;
    rows.forEach(function (rec) {
      if (!rec || !rec.bodyNode) { return; }
      total += highlightInNode(rec.bodyNode, lower);
    });
    if (searchHits.length) {
      // Fresh (non-silent) query → jump+scroll to the first hit. Silent re-highlight (streaming batch) → only
      // restore the active class at the current position, NEVER scroll (that yanked the viewport every frame).
      if (silent) { setActiveHit(Math.min(activeIndex, searchHits.length - 1), false); }
      else { setActiveHit(0, true); }
    }
    if (!silent) { safeSend({ type: 'search', count: total }); }
  }

  // ---- find bar overlay (Ctrl/Cmd+F) -------------------------------------
  // Lightweight, null-safe in-transcript search UI. Typing drives the existing
  // highlight path (CC.emit('search', q) when available, else runSearch); the
  // match count is shown locally. Esc / ✕ closes and clears highlights.
  var findBar = null;
  var findInput = null;
  var findCount = null;

  function emitSearch(q) {
    var C = CCobj();
    if (C.emit) {
      try { C.emit('search', q); return; } catch (e) { /* fall through */ }
    }
    runSearch(q, false);
  }

  function updateFindCount() {
    if (!findCount) { return; }
    var q = (findInput && findInput.value) || '';
    if (!q) { findCount.textContent = ''; return; }
    var n = searchHits.length;
    findCount.textContent = n === 0 ? 'No results' : ((activeIndex + 1) + ' / ' + n);
  }

  function ensureFindBar() {
    if (findBar) { return findBar; }
    findInput = el('input', {
      class: 'find-input',
      attrs: { type: 'text', placeholder: 'Find…', spellcheck: 'false' }
    });
    findCount = el('span', { class: 'find-count' });
    var closeBtn = el('span', {
      class: 'find-x', text: '✕', title: 'Close', attrs: { role: 'button' },
      on: { click: function (e) { e.preventDefault(); e.stopPropagation(); closeFindBar(); } }
    });
    findBar = el('div', { class: 'find-bar' });
    findBar.hidden = true;
    findBar.appendChild(findInput);
    findBar.appendChild(findCount);
    findBar.appendChild(closeBtn);

    findInput.addEventListener('input', function () {
      emitSearch(findInput.value || '');
      updateFindCount();
    });
    findInput.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' || e.keyCode === 27) {
        e.preventDefault();
        e.stopPropagation();
        closeFindBar();
      } else if (e.key === 'Enter' || e.keyCode === 13) {
        // Enter → next match, Shift+Enter → previous (standard find-bar navigation).
        e.preventDefault();
        if (e.shiftKey) prevHit(); else nextHit();
      }
    });

    var host = document.body || conversationEl() || document.documentElement;
    if (host) { host.appendChild(findBar); }
    return findBar;
  }

  function openFindBar() {
    ensureFindBar();
    if (!findBar) { return; }
    findBar.hidden = false;
    if (findInput) {
      try { findInput.focus(); findInput.select(); } catch (e) { /* ignore */ }
      if (findInput.value) {
        emitSearch(findInput.value);
        updateFindCount();
      }
    }
  }

  function closeFindBar() {
    if (findBar) { findBar.hidden = true; }
    emitSearch('');
    if (findCount) { findCount.textContent = ''; }
  }

  // Ctrl/Cmd+O — collapse/expand every "Thought process" (reasoning) fold at once.
  // If any reasoning fold is currently open we collapse them all; otherwise expand them all.
  function toggleReasoningFolds() {
    reasoningExpanded = !reasoningExpanded;       // persists to folds that stream in later
    var c = conversationEl();
    if (!c) { return; }
    var folds = c.querySelectorAll('details.fold.reasoning');
    for (var j = 0; j < folds.length; j++) { folds[j].open = reasoningExpanded; }
  }

  // Expose so the host can drive the toggle if the IDE swallows Ctrl+O before
  // the web view ever sees the keydown.
  cc.toggleReasoning = toggleReasoningFolds;

  document.addEventListener('keydown', function (e) {
    var key = e.key;
    var isF = (key === 'f' || key === 'F' || e.keyCode === 70);
    var isO = (key === 'o' || key === 'O' || e.keyCode === 79);
    if (isF && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
      e.preventDefault();
      openFindBar();
    } else if (isO && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
      e.preventDefault();
      toggleReasoningFolds();
    } else if ((key === 'Escape' || e.keyCode === 27) && findBar && !findBar.hidden) {
      e.preventDefault();
      // Stop the event here so closing the find bar doesn't ALSO reach the composer's Escape handler, which
      // would interrupt the running turn (capture phase runs before the composer's bubble handler).
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
      closeFindBar();
    }
  }, true); // capture phase — beat in-view handlers; IDE-level capture is handled host-side if needed

  // ---- subscribe to bus events -------------------------------------------
  function subscribe() {
    var C = CCobj();
    if (!C.on) { return false; }
    C.on('follow', function (b) { followFlag = !!b; if (followFlag) { scheduleScroll(true); } });
    C.on('search', function (q) { runSearch(q, false); updateFindCount(); });
    return true;
  }

  if (!subscribe()) {
    // core may not be ready; retry shortly until CC.on exists.
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      if (subscribe() || tries > 50) { clearInterval(iv); }
    }, 20);
  }
})();
