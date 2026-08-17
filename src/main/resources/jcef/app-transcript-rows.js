/* app-transcript-rows.js — the per-speaker row builders.
 *
 * One subject: what a transcript row LOOKS like for each speaker — you, Claude, a thought process, a recalled
 * memory, a notice, a standalone tool output — plus the reasoning fold's global expand/collapse. The tool card
 * is its own subject and lives in app-transcript-tools.js; the spine (upsert/reposition) is in
 * app-transcript.js, which creates the shared `CC.transcript` namespace this file extends.
 */
(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var conversationEl = TX.conversationEl;

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
          // Same "Copied" confirmation the code-block buttons give. Without it this button did its job and
          // said nothing, which is indistinguishable from a broken one — and was reported as exactly that.
          if (CC.flashCopied) CC.flashCopied(e.currentTarget || this);
        },
      },
    });
  }

  // ---- per-speaker builders ----------------------------------------------
  // Each returns { el, bodyNode, kind } and is "stateless": update() handles refresh.

  function buildUser() {
    var body = el('div', { class: 'body' });
    var head = el('div', { class: 'msg-head' });
    head.appendChild(el('span', { class: 'name', text: 'You' }));
    var copy = copyButton(function () {
      return body.__rawText || '';
    });
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
    avatar.appendChild(
      el('span', { class: 'avatar-nyan', html: window.CC && window.CC.nyanSvg ? window.CC.nyanSvg() : '' })
    );
    head.appendChild(avatar);
    head.appendChild(el('span', { class: 'name', text: 'Claude' }));
    var copy = copyButton(function () {
      return body.__rawText || '';
    });
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
    node.open = startOpen !== false;
    var summary = el('summary', {});
    summary.appendChild(el('span', { class: 'tri' }));
    var label = el('span', { class: 'fold-label', text: summaryText });
    summary.appendChild(label);
    if (hint) {
      summary.appendChild(el('span', { class: 'fold-hint', text: 'Press Ctrl+O to expand' }));
    }
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
    var title = meta && String(meta).trim() ? String(meta) : '🧠 Recalled memories';
    return buildFold(title, true);
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

  TX.builderFor = function (speaker, entry) {
    switch (speaker) {
      case 'USER':
        return buildUser();
      case 'ASSISTANT':
        return buildAssistant();
      case 'THINKING':
        return buildThinking();
      case 'MEMORY':
        return buildMemory(entry.meta);
      // The ONE builder that is handed the whole entry rather than a field of it. A tool card carries things
      // that are fixed for the life of the row and have no later hook in this family — the message the call
      // sends — and this is where that family sees the entry at all (`updateRow` re-applies only what can
      // change). Everything else about the card is applied afterwards, by state.
      case 'TOOL':
        return TX.buildTool(entry);
      case 'TOOL_OUTPUT':
        return buildToolOutputStandalone();
      case 'ERROR':
        return buildNotice(true);
      case 'SYSTEM':
        return buildNotice(false);
      default:
        return buildNotice(false);
    }
  };

  // Ctrl/Cmd+O — collapse/expand every "Thought process" (reasoning) fold at once.
  // If any reasoning fold is currently open we collapse them all; otherwise expand them all.
  function toggleReasoningFolds() {
    reasoningExpanded = !reasoningExpanded; // persists to folds that stream in later
    var c = conversationEl();
    if (!c) {
      return;
    }
    var folds = c.querySelectorAll('details.fold.reasoning');
    for (var j = 0; j < folds.length; j++) {
      folds[j].open = reasoningExpanded;
    }
  }

  // Expose so the host can drive the toggle if the IDE swallows Ctrl+O before
  // the web view ever sees the keydown. The find bar's key handler calls it too.
  cc.toggleReasoning = toggleReasoningFolds;
})();
