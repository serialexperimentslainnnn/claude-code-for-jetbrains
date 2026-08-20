(function () {
  'use strict';

  var cc = (window.cc = window.cc || {});
  var CC = (window.CC = window.CC || {});
  var TX = (CC.transcript = CC.transcript || {});

  var el = TX.el;
  var safeSend = TX.safeSend;
  var conversationEl = TX.conversationEl;

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
          if (CC.flashCopied) CC.flashCopied(e.currentTarget || this);
        },
      },
    });
  }

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
    return { el: node, bodyNode: body, kind: 'md' };
  }

  function buildAssistant() {
    var body = el('div', { class: 'body' });
    var head = el('div', { class: 'msg-head' });
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

  var reasoningExpanded = false;

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
    rec.el.classList.add('reasoning');
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

  // A call a rule matched and that ran anyway, because Allow All is on or the command is whitelisted.
  // A warning rather than a block: nothing was stopped, and the point of the row is that the user can see
  // WHICH rule went unenforced and why, instead of the bypass being invisible.
  function buildBypassNotice() {
    var node = el('div', { class: 'notice guard-bypass' });
    var body = el('div', { class: 'body' });
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: 'md' };
  }

  function buildBlockNotice(rule, command) {
    var node = el('div', { class: 'notice guard-block' });
    var body = el('div', { class: 'body' });
    node.appendChild(body);

    var link = el('button', {
      class: 'guard-disable-link',
      text: 'Disable rule',
      attrs: { type: 'button', 'aria-expanded': 'false', 'aria-haspopup': 'menu' },
    });
    var actions = el('div', { class: 'guard-block-actions' });

    var menu = CC.durationMenu({
      anchor: link,
      home: actions,
      label: 'Disable this rule for',
      watch: conversationEl,
      onPick: function (token) {
        safeSend({ type: 'guardSuspend', rule: String(rule), duration: token });
      },
    });

    link.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      menu.toggle();
    });

    actions.appendChild(link);
    actions.appendChild(menu.menu);

    // Only when the call carried a command. Whitelisting is about an exact command string, so a block with
    // nothing to match on — a bare path read, say — must not offer a link that would silently do nothing.
    if (command) {
      actions.appendChild(
        el('button', {
          class: 'guard-whitelist-link',
          text: 'Whitelist Command',
          attrs: { type: 'button' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              safeSend({ type: 'guardWhitelist', rule: String(rule), command: String(command) });
            },
          },
        })
      );
    }

    node.appendChild(actions);
    return { el: node, bodyNode: body, kind: 'md' };
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
      case 'TOOL':
        return TX.buildTool(entry);
      case 'TOOL_OUTPUT':
        return buildToolOutputStandalone();
      case 'ERROR':
        return buildNotice(true);
      case 'SYSTEM':
        if (entry && entry.blockedRule) return buildBlockNotice(entry.blockedRule, entry.command);
        if (entry && entry.bypassedRule) return buildBypassNotice();
        return buildNotice(false);
      default:
        return buildNotice(false);
    }
  };

  function toggleReasoningFolds() {
    reasoningExpanded = !reasoningExpanded;
    var c = conversationEl();
    if (!c) {
      return;
    }
    var folds = c.querySelectorAll('details.fold.reasoning');
    for (var j = 0; j < folds.length; j++) {
      folds[j].open = reasoningExpanded;
    }
  }

  cc.toggleReasoning = toggleReasoningFolds;
})();
