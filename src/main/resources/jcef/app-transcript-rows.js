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

  var SUSPEND_DURATIONS = [
    { token: '5m', label: '5 minutes' },
    { token: '15m', label: '15 minutes' },
    { token: '30m', label: '30 minutes' },
    { token: '4h', label: '4 hour' },
    { token: '8h', label: '8 hour' },
    { token: 'ide', label: 'Until IDE closes' },
    { token: 'forever', label: 'Forever' },
  ];

  function buildBlockNotice(rule) {
    var node = el('div', { class: 'notice guard-block' });
    var body = el('div', { class: 'body' });
    node.appendChild(body);

    var menu = el('div', {
      class: 'guard-disable-menu',
      attrs: { role: 'menu', hidden: 'hidden', 'aria-label': 'Disable this rule for' },
    });
    var link = el('button', {
      class: 'guard-disable-link',
      text: 'Disable rule',
      attrs: { type: 'button', 'aria-expanded': 'false', 'aria-haspopup': 'menu' },
    });

    function setOpen(open) {
      if (open) {
        menu.removeAttribute('hidden');
      } else {
        menu.setAttribute('hidden', 'hidden');
      }
      link.setAttribute('aria-expanded', open ? 'true' : 'false');
    }

    link.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(link.getAttribute('aria-expanded') !== 'true');
    });
    menu.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        setOpen(false);
        link.focus();
      }
    });

    SUSPEND_DURATIONS.forEach(function (d) {
      menu.appendChild(
        el('button', {
          class: 'guard-disable-option',
          text: d.label,
          attrs: { type: 'button', role: 'menuitem' },
          on: {
            click: function (e) {
              e.preventDefault();
              e.stopPropagation();
              safeSend({ type: 'guardSuspend', rule: String(rule), duration: d.token });
              setOpen(false);
            },
          },
        })
      );
    });

    var actions = el('div', { class: 'guard-block-actions' });
    actions.appendChild(link);
    actions.appendChild(menu);
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
        return entry && entry.blockedRule ? buildBlockNotice(entry.blockedRule) : buildNotice(false);
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
