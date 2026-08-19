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
    { token: '4h', label: '4 hours' },
    { token: '8h', label: '8 hours' },
    { token: 'ide', label: 'Until IDE closes' },
    { token: 'forever', label: 'Forever' },
  ];

  function placeByAnchor(floating, anchor) {
    var margin = 8;
    var r = anchor.getBoundingClientRect();
    var left = Math.min(Math.round(r.left), window.innerWidth - floating.offsetWidth - margin);
    var top = r.bottom + 4;
    if (top + floating.offsetHeight > window.innerHeight - margin) top = r.top - floating.offsetHeight - 4;
    floating.style.left = Math.max(margin, left) + 'px';
    floating.style.top = Math.round(Math.max(margin, top)) + 'px';
  }

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
    var options = [];
    var isOpen = false;

    function focusOption(at) {
      var target = options[(at + options.length) % options.length];
      if (target) target.focus({ preventScroll: true });
    }

    function onOutside(e) {
      if (menu.contains(e.target) || link.contains(e.target)) return;
      setOpen(false);
    }

    function onEscape(e) {
      if (e.key !== 'Escape') return;
      setOpen(false);
      link.focus();
    }

    function onViewChange() {
      setOpen(false);
    }

    function setOpen(open) {
      if (open === isOpen) return;
      isOpen = open;
      link.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (!open) {
        menu.setAttribute('hidden', 'hidden');
        document.removeEventListener('mousedown', onOutside, true);
        document.removeEventListener('keydown', onEscape, true);
        document.removeEventListener('scroll', onViewChange, true);
        window.removeEventListener('resize', onViewChange);
        return;
      }
      menu.removeAttribute('hidden');
      placeByAnchor(menu, link);
      document.addEventListener('mousedown', onOutside, true);
      document.addEventListener('keydown', onEscape, true);
      document.addEventListener('scroll', onViewChange, true);
      window.addEventListener('resize', onViewChange);
      focusOption(0);
    }

    link.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(!isOpen);
    });
    menu.addEventListener('keydown', function (e) {
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
      e.preventDefault();
      var step = e.key === 'ArrowDown' ? 1 : -1;
      var at = options.indexOf(document.activeElement);
      focusOption(at < 0 ? (step > 0 ? 0 : options.length - 1) : at + step);
    });

    SUSPEND_DURATIONS.forEach(function (d) {
      var option = el('button', {
        class: 'guard-disable-option',
        text: d.label,
        attrs: { type: 'button', role: 'menuitem' },
        on: {
          click: function (e) {
            e.preventDefault();
            e.stopPropagation();
            safeSend({ type: 'guardSuspend', rule: String(rule), duration: d.token });
            setOpen(false);
            link.focus();
          },
        },
      });
      options.push(option);
      menu.appendChild(option);
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
