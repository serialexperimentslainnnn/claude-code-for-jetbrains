/* app-transcript-tools.js — the tool card.
 *
 * One subject: the row a tool call gets — its icon and header, the command it ran, the message it carries,
 * its live elapsed badge, its state colour, and the output (plain, command or diff) routed into it by
 * `tool_use_id`. Extends the shared `CC.transcript` namespace created by app-transcript.js.
 *
 * **Two things a call CARRIES are shown outside the collapse toggle**, because a card is collapsed by default
 * and what a call did is not something you should have to expand to find out: the command it executed
 * (`.tool-cmd`, rendered by `renderCommandBlock` from app-transcript.js's `createRow`) and the message it
 * sent (`.tool-msg`, rendered HERE, in `buildTool`, and nowhere else — see there). Only the RESULT stays
 * behind the toggle.
 */
(function () {
  'use strict';

  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var toolCards = TX.toolCards;

  // Per-tool-type inline SVG icons (themeable: stroke/fill = currentColor, so they follow the
  // tool state colour and ride Vibe Mode). Ported from resources/icons/tool-*.svg.
  var TOOL_ICONS = {
    bash: '<rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75"/><path d="m4.5 6.25 2 1.75-2 1.75"/><path d="M8 10.25h3"/>',
    read: '<path d="M8 4.25C6.75 3.4 5.4 3 3.5 3H2v8.5h1.75c1.6 0 3 .35 4.25 1.25"/><path d="M8 4.25C9.25 3.4 10.6 3 12.5 3H14v8.5h-1.75c-1.6 0-3 .35-4.25 1.25"/><path d="M8 4.25v8.5"/>',
    edit: '<path d="M10.75 2.5 13.5 5.25 6 12.75 2.75 13.5l.75-3.25z"/><path d="M9.25 4 12 6.75"/>',
    search: '<circle cx="7" cy="7" r="4.25"/><path d="m10.25 10.25 3 3"/>',
    web: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12"/><path d="M8 2c1.75 1.6 2.75 3.7 2.75 6S9.75 12.4 8 14C6.25 12.4 5.25 10.3 5.25 8S6.25 3.6 8 2z"/>',
    task: '<circle cx="8" cy="8" r="6"/><path d="M6.5 5.5 11 8l-4.5 2.5z" fill="currentColor"/>',
    generic:
      '<path d="M6.25 2.75h3.5v1.5a1.25 1.25 0 1 0 2.5 0v-1.5h.75v3.5h1.5a1.25 1.25 0 1 1 0 2.5h-1.5v3.5H9.5v-1.5a1.25 1.25 0 1 0-2.5 0v1.5H3.5v-3.5H2a1.25 1.25 0 1 1 0-2.5h1.5v-3.5h2.75z"/>',
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
  TX.toolIconSvg = function (meta) {
    var inner = TOOL_ICONS[toolIconKey(meta)] || TOOL_ICONS.generic;
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      inner +
      '</svg>'
    );
  };

  /**
   * The card for one tool call. [entry] is the row the card is being built from — it is read for the ONE
   * thing that is fixed for the life of the card and has no other hook in this family: the message the call
   * carries (see the `.tool-msg` block below). Everything that can change later is applied by `updateRow`.
   *
   * Optional on purpose: a caller with no entry in hand still gets a well-formed, empty card.
   */
  TX.buildTool = function (entry) {
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
      if (node.__toolUseId) {
        safeSend({ type: 'viewDiffByTool', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(diffBtn);
    var restoreBtn = el('button', {
      class: 'tool-restore',
      text: 'Restore',
      attrs: { type: 'button' },
      title: 'Revert this edit',
    });
    restoreBtn.hidden = true;
    restoreBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (node.__toolUseId) {
        safeSend({ type: 'revertEdit', toolUseId: node.__toolUseId });
      }
    });
    head.appendChild(restoreBtn);
    node.__restoreBtn = restoreBtn;
    head.appendChild(el('span', { class: 'chev', text: '▾' })); // ▾
    // The executed command's own code block (command-executing tools only) — a SIBLING of .tool-out, not
    // nested inside it, so it stays visible whether the card is collapsed or open (see renderCommandBlock).
    // Empty (no command) → collapses to nothing via the `.tool-cmd:empty` CSS rule, no JS bookkeeping needed.
    var cmd = el('div', { class: 'tool-cmd' });
    // The text this call SENDS — the message to another agent, the prompt, the question. Same reasoning as
    // the command block above and the same shape (a sibling of `.tool-out`, so the collapse toggle does not
    // hide it), because it answers the same question: a card that shows only `{"success":true,…}` tells you
    // the call worked and never tells you what was said.
    //
    // The HOST decides which input key holds that text (`message`, `prompt`, `question`…), exactly as it
    // already does for the command — the page never parses a tool input, because the key set is a security
    // decision and it lives next to the rules that read the same keys (see ToolInputScanner). Absent the
    // field this collapses to nothing via `.tool-msg:empty`.
    var msg = el('div', { class: 'tool-msg' });
    var out = el('div', { class: 'tool-out' });
    // Nested subagent activity (rows whose `parent` is this tool's id) lands here,
    // distinct from this tool's own routed output (.tool-out).
    var children = el('div', { class: 'tool-children' });
    head.addEventListener('click', function () {
      // An Agent/Task card is a LINK to that agent's tab, not an expander. Its work no longer lives in this
      // transcript -- it has its own tab with its own transcript -- so expanding would open an empty box,
      // and the card is also the documented way back to a tab the user closed.
      // The host maps this tool_use_id to the agent it spawned (AgentRegistry knows the pairing from the
      // binary's own sidecar), so the card does not have to carry an id it never sees.
      if (node.__isAgentCard && node.__toolUseId) {
        safeSend({ type: 'revealAgent', toolUseId: node.__toolUseId });
        return;
      }
      node.classList.toggle('open');
    });
    node.appendChild(head);
    node.appendChild(msg);
    node.appendChild(cmd);
    node.appendChild(out);
    node.appendChild(children);
    // Rendered at BUILD time, once, and deliberately not in `createRow` alongside the command block: the
    // message is fixed for the life of the row, `builderFor` is where this family sees the entry, and one
    // renderer in one place is what stops the block being appended twice on a later push.
    if (entry && entry.message) {
      renderMessageBlock(msg, entry.message);
      node.classList.add('msg-tool');
    }
    node.__nameNode = name;
    node.__msgNode = msg;
    node.__cmdNode = cmd;
    node.__outNode = out;
    node.__childrenNode = children;
    node.__elapsedNode = elapsed;
    node.__diffBtn = diffBtn;
    return { el: node, bodyNode: name, kind: 'tool', outNode: out };
  };

  // Format an elapsed-seconds value as a dim "· N.Ns" suffix; null-safe.
  function formatElapsed(secs) {
    var n = Number(secs);
    if (!isFinite(n) || n <= 0) {
      return '';
    }
    return '· ' + n.toFixed(1) + 's';
  }

  // Show/hide the .tool-elapsed badge based on RUNNING state.
  TX.applyToolElapsed = function (node, state, elapsedSecs) {
    var badge = node.__elapsedNode || node.querySelector('.tool-elapsed');
    if (!badge) {
      return;
    }
    var running = state === 'RUNNING' || state === 'LOADING';
    var label = running ? formatElapsed(elapsedSecs) : '';
    if (label) {
      badge.textContent = label;
      badge.hidden = false;
    } else {
      badge.textContent = '';
      badge.hidden = true;
    }
  };

  TX.applyToolState = function (node, state, meta) {
    node.classList.remove('loading', 'running', 'done', 'failed');
    if (state === 'ERROR' || meta === 'error') {
      node.classList.add('failed'); // red — wins over done/loading
      // Reveal the failure. A tool card is collapsed by default, and its output — which for a failed call is
      // the ERROR — lives behind that collapse, so a red header was the entire message: you had to know to
      // expand a card to find out what went wrong. Opened ONCE, tracked on the node, so this never fights a
      // user who deliberately collapsed it (applyToolState runs again on every state push).
      // Not for an agent card: it holds nothing but a link, so opening it reveals an empty body — what
      // happened is in its own transcript.
      if (!node.__autoOpenedOnError && !node.__isAgentCard) {
        node.__autoOpenedOnError = true;
        node.classList.add('open');
      }
    } else if (state === 'LOADING') {
      node.classList.add('loading'); // fade sky-blue ↔ amber (active)
    } else if (state === 'RUNNING') {
      node.classList.add('running'); // fade sky-blue ↔ amber
    } else if (state === 'FINISHED') {
      node.classList.add('done'); // green
    }
  };

  // ---- TOOL_OUTPUT routing ------------------------------------------------

  /**
   * Above this, a result is not a document that got long — it is a dump, and nobody reads it inline.
   *
   * The ceiling is a bound on the WORK, and the work is what makes it necessary: `prettyJson` runs on the
   * page's only thread, on every tool result, and a parse plus a re-stringify is two full passes over the
   * string plus a whole object graph allocated in between. Unbounded, one MCP server that answers with a
   * multi-megabyte blob stalls the transcript at exactly the moment it is streaming.
   *
   * 200 000 characters is generous — it is far past any structured answer meant to be read on screen —
   * and the cost of being wrong is small and one-directional: a result over it is shown raw, which is
   * what it was shown as before any of this existed.
   */
  var MAX_JSON_CHARS = 200000;

  /**
   * [text] re-indented, when it IS JSON; null when it is not, or when it is too big to be worth deciding.
   *
   * Detected by CONTENT, never by tool name: a tool called `SendMessage` is no more likely to answer in JSON
   * than an MCP tool nobody has heard of, and the name is the model's to choose. The bracket test before the
   * parse is not decoration either — it is what keeps `JSON.parse` off the ordinary case, since a plain
   * sentence of output would otherwise pay for a throw on every push.
   *
   * The parsed value is used for nothing but re-stringifying, so nothing hostile in it is ever reachable:
   * no key is read, no property is assigned, and the result goes out through `textContent`.
   */
  function prettyJson(text) {
    if (!text || text.length > MAX_JSON_CHARS) {
      return null;
    }
    var s = text.trim();
    var head = s.charAt(0);
    var tail = s.charAt(s.length - 1);
    if ((head !== '{' || tail !== '}') && (head !== '[' || tail !== ']')) {
      return null;
    }
    try {
      return JSON.stringify(JSON.parse(s), null, 2);
    } catch (e) {
      // Not JSON after all (a truncated result, a log line that happens to start with a brace) — show it raw.
      return null;
    }
  }

  /**
   * Appends a tool result into the card that produced it. Returns true when it was routed.
   *
   * [cards] defaults to the main transcript's registry; the Git view's embedded conversation passes its own,
   * because two sessions' `tool_use_id`s are not one namespace and a shared map would drop the Git chat's
   * `git status` output into whichever card of the user's conversation happened to share the id.
   */
  TX.routeToolOutput = function (entry, cards) {
    if (!cards) {
      cards = toolCards;
    }
    // returns true if it was routed into an existing tool card
    var tid = entry.toolUseId;
    if (!tid) {
      return false;
    }
    var card = cards.get(tid);
    if (!card) {
      return false;
    }
    var out = card.__outNode || card.querySelector('.tool-out');
    if (!out) {
      return false;
    }
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
      // `meta` is a space-separated tag set here (see ClaudeSession.kt), not a single value: a command's
      // output can be "command error" at once (a failing build's stderr is exactly what you want to copy).
      var tags = ' ' + (entry.meta || '') + ' ';
      var fileLang =
        window.CC && typeof CC.languageForPath === 'function' ? CC.languageForPath(card.__filePath) : null;
      if (entry.meta === 'diff') {
        renderDiff(codeEl, entry.text == null ? '' : String(entry.text), fileLang);
        block.classList.add('diff');
        block.classList.remove('command');
        block.classList.remove('flow');
      } else if (tags.indexOf(' command ') >= 0) {
        // Command/PowerShell/etc. output: render with the SAME chrome as a markdown code fence (a code-head
        // bar + Copy button, via the shared decorator), so the click/keyboard copy handling in app-core.js
        // just works — it is delegated by class name, not wired per-node.
        block.classList.remove('diff');
        block.classList.remove('flow');
        block.classList.add('command');
        codeEl.textContent = entry.text == null ? '' : String(entry.text);
        if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
          CC.decorateOneCodeBlock(codeEl);
          var langLabel = block.querySelector('.code-lang');
          // Generic label — we deliberately don't guess Bash vs PowerShell vs cmd.exe from the tool name.
          if (langLabel) {
            langLabel.textContent = 'shell';
          }
        }
      } else {
        block.classList.remove('diff');
        block.classList.remove('command');
        var raw = entry.text == null ? '' : String(entry.text);
        var json = prettyJson(raw);
        codeEl.textContent = json == null ? raw : json;
        // A file tool's plain output (Read's dump, Write/Edit's confirmation) — the same code-head+Copy chrome
        // as a markdown fence, with syntax highlighting from the file's extension when known (falls back to
        // hljs's own autodetection when it isn't — see CC.languageForPath).
        //
        // The branch is on `__filePath` ALONE, deliberately: this is the decision about whether the row is
        // file contents, and file contents keep their alignment whether or not the decorator happens to be
        // loaded. Folding the two conditions together made a missing decorator silently reflow a source file.
        if (card.__filePath) {
          block.classList.remove('flow');
          if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
            if (fileLang) {
              codeEl.className = 'language-' + fileLang;
            }
            CC.decorateOneCodeBlock(codeEl);
          }
        } else {
          // EVERYTHING ELSE WRAPS. This is the answer that had no branch: not a diff, not a command's output,
          // not a file's contents — an MCP server's JSON, an agent's acknowledgement, a fetched page, a tool
          // that just says what it did. All of it is prose or structure with no column alignment to protect,
          // and all of it was rendered as one endless line behind a horizontal scrollbar on a card nobody had
          // reason to think was scrollable. The same argument already won for a FAILED card's error text; the
          // only reason it stopped there is that the error was the case somebody reported.
          //
          // The three exempted cases are exempt because their long lines MEAN something: a diff's marker
          // column, a command's tabular output, a file's indentation. Wrapping those corrupts them, which is
          // why the split is by what the row IS and not by how long it happens to be.
          block.classList.add('flow');
          if (json != null && window.CC && typeof CC.decorateOneCodeBlock === 'function') {
            // Re-indented JSON earns the fence chrome the same way a file dump does: a language label, a Copy
            // button, and hljs telling keys from values. `language-json` is set rather than guessed, since we
            // just parsed it and know.
            codeEl.className = 'language-json';
            CC.decorateOneCodeBlock(codeEl);
          }
        }
      }
    }
    return true;
  };

  // Render a unified diff with per-line colour (added/removed/hunk/context), and — when `lang` is a hljs
  // language the vendored bundle knows — per-line syntax highlighting layered UNDER that colour (hljs escapes
  // the code text itself, so this is exactly as safe as the highlightElement() path markdown fences already use).
  function renderDiff(codeEl, text, lang) {
    codeEl.innerHTML = '';
    var lines = String(text).split('\n');
    var hljs = window.hljs;
    var canHighlight = !!(
      lang &&
      hljs &&
      typeof hljs.getLanguage === 'function' &&
      hljs.getLanguage(lang) &&
      typeof hljs.highlight === 'function'
    );
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var c0 = line.charAt(0);
      var isHunk = line.indexOf('@@') === 0;
      var cls = 'dl-ctx';
      if (isHunk) {
        cls = 'dl-hunk';
      } else if (c0 === '+') {
        cls = 'dl-add';
      } else if (c0 === '-') {
        cls = 'dl-del';
      }
      var span = el('span', { class: 'diff-line ' + cls });
      var trailingNl = i < lines.length - 1;
      var highlighted = false;
      // Unified diff lines start with a marker column (+/-/space); hunk headers don't carry code and are
      // left plain. Highlight only the code AFTER that marker, so the marker itself is never mangled.
      if (!isHunk && canHighlight && line.length > 0) {
        try {
          var hi = hljs.highlight(line.slice(1), { language: lang, ignoreIllegals: true }).value;
          span.appendChild(document.createTextNode(c0));
          span.insertAdjacentHTML('beforeend', hi);
          if (trailingNl) {
            span.appendChild(document.createTextNode('\n'));
          }
          highlighted = true;
        } catch (e) {
          // Highlighting is best-effort — fall through to the plain-text line below.
        }
      }
      if (!highlighted) {
        span.textContent = trailingNl ? line + '\n' : line;
      }
      codeEl.appendChild(span);
    }
  }

  /**
   * What a tool call CARRIES, as its own code-head+Copy block — the same chrome as the output blocks (via
   * the shared decorator), so Copy works through the delegated handler in app-core.js with no per-node
   * wiring. [text] goes in via textContent: it is a tool input, i.e. model-authored, and it is never markup.
   *
   * Two callers, differing only in the class the block wears and the word in its head bar — which is exactly
   * the amount of difference that makes this one function and not two near-identical ones.
   */
  function carriedBlock(node, text, blockClass, label) {
    if (!node) {
      return;
    }
    var block = el('pre', { class: blockClass });
    var code = el('code', {});
    code.textContent = String(text);
    block.appendChild(code);
    node.appendChild(block);
    if (window.CC && typeof CC.decorateOneCodeBlock === 'function') {
      CC.decorateOneCodeBlock(code);
      var langLabel = block.querySelector('.code-lang');
      if (langLabel) {
        langLabel.textContent = label;
      }
    }
  }

  // The command a tool call executes. Generic label — we deliberately don't guess Bash vs PowerShell vs
  // cmd.exe from the tool name, which is the model's to choose.
  TX.renderCommandBlock = function (cmdNode, commandText) {
    carriedBlock(cmdNode, commandText, 'command-src', 'shell');
  };

  // The message/prompt a tool call sends. Called from `buildTool` only — see there for why it is rendered at
  // build time rather than beside the command block in `createRow`.
  function renderMessageBlock(msgNode, messageText) {
    carriedBlock(msgNode, messageText, 'message-src', 'message');
  }

  // Build a `jb://open` href for a PROJECT-RELATIVE path (+ optional 1-based line). The host resolves it against
  // the project root and refuses anything outside it (isWithinRoot), so this never needs an absolute path.
  TX.jbHref = function (relPath, line) {
    var u = 'jb://open?file=' + encodeURIComponent(String(relPath));
    if (line) {
      u += '&line=' + encodeURIComponent(String(line));
    }
    return u;
  };

  // A file tool's label — `Read(src/main/kotlin/permission/PermissionBroker.kt)` — with the PATH as a clickable
  // jump-to-code link. The path is inserted via textContent (never innerHTML), so a hostile path can't inject
  // markup; the delegated <a> handler in app-core routes the click to the host.
  TX.renderToolLabel = function (nameEl, text, filePath) {
    if (!nameEl) {
      return;
    }
    var label = text == null ? '' : String(text);
    var p = String(filePath);
    var at = label.indexOf(p);
    if (at < 0) {
      nameEl.textContent = label;
      return;
    } // path not in the label — render plainly
    while (nameEl.firstChild) {
      nameEl.removeChild(nameEl.firstChild);
    }
    nameEl.appendChild(document.createTextNode(label.slice(0, at)));
    var a = el('a', { class: 'jb-link', text: p, attrs: { href: TX.jbHref(p), title: 'Open ' + p } });
    // The card head toggles collapse on click — don't collapse it just because the user followed the link.
    a.addEventListener('click', function (e) {
      e.stopPropagation();
    });
    nameEl.appendChild(a);
    nameEl.appendChild(document.createTextNode(label.slice(at + p.length)));
  };
})();
