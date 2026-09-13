(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const safeSend = TX.safeSend;
  const conversationEl = TX.conversationEl;

  function copyButton(getText: () => string): HTMLElement {
    return el('span', {
      class: 'act copy',
      text: 'Copy',
      title: 'Copy',
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          safeSend({ type: 'copy', text: getText() });
          if (CC.flashCopied) CC.flashCopied(e.currentTarget as HTMLElement);
        },
      },
    });
  }

  function buildUser(): RowRec {
    const body = el('div', { class: 'body' }) as BodyEl;
    const head = el('div', { class: 'msg-head' });
    head.appendChild(el('span', { class: 'name', text: 'You' }));
    head.appendChild(
      copyButton(function () {
        return body.__rawText || '';
      })
    );
    const node = el('div', { class: 'msg user' });
    node.appendChild(head);
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: 'md' };
  }

  function buildAssistant(): RowRec {
    const body = el('div', { class: 'body' }) as BodyEl;
    const head = el('div', { class: 'msg-head' });
    const avatar = el('span', { class: 'avatar' });
    avatar.appendChild(el('span', { class: 'avatar-star', text: '✶' }));
    avatar.appendChild(el('span', { class: 'avatar-nyan', html: CC.nyanSvg ? CC.nyanSvg() : '' }));
    head.appendChild(avatar);
    head.appendChild(el('span', { class: 'name', text: 'Claude' }));
    head.appendChild(
      copyButton(function () {
        return body.__rawText || '';
      })
    );
    const node = el('div', { class: 'msg assistant' });
    node.appendChild(head);
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: 'md' };
  }

  let reasoningExpanded = false;

  function buildFold(summaryText: string, dim: boolean, startOpen?: boolean, hint?: boolean): RowRec {
    const node = el('details', { class: 'fold' + (dim ? ' dim' : '') }) as HTMLDetailsElement & RowEl;
    node.open = startOpen !== false;
    const summary = el('summary', {});
    summary.appendChild(el('span', { class: 'tri' }));
    const label = el('span', { class: 'fold-label', text: summaryText });
    summary.appendChild(label);
    if (hint) {
      summary.appendChild(el('span', { class: 'fold-hint', text: 'Press Ctrl+O to expand' }));
    }
    const body = el('div', { class: 'body fold-body' }) as BodyEl;
    node.appendChild(summary);
    node.appendChild(body);
    node.__label = label;
    return { el: node, bodyNode: body, kind: 'md' };
  }

  function buildThinking(): RowRec {
    const rec = buildFold('Thought process', true, reasoningExpanded, true);
    rec.el.classList.add('reasoning');
    return rec;
  }

  function buildMemory(meta: unknown): RowRec {
    const title = meta && String(meta).trim() ? String(meta) : '🧠 Recalled memories';
    return buildFold(title, true);
  }

  function buildNotice(isError: boolean): RowRec {
    const node = el('div', { class: isError ? 'notice error' : 'notice' });
    const body = el('div', { class: 'body' }) as BodyEl;
    node.appendChild(body);
    return { el: node, bodyNode: body, kind: isError ? 'text' : 'md' };
  }

  function buildToolOutputStandalone(): RowRec {
    const node = el('div', { class: 'notice tool-output' });
    const pre = el('pre', {});
    const code = el('code', {}) as BodyEl;
    pre.appendChild(code);
    node.appendChild(pre);
    return { el: node, bodyNode: code, kind: 'pre' };
  }

  TX.builderFor = function (speaker: string | undefined, entry: TranscriptEntry): RowRec {
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
        if (entry && entry.blockedRule) return TX.buildBlockNotice(entry.blockedRule, entry.command);
        if (entry && entry.bypassedRule) return TX.buildBypassNotice(entry);
        return buildNotice(false);
      default:
        return buildNotice(false);
    }
  };

  function toggleReasoningFolds(): void {
    reasoningExpanded = !reasoningExpanded;
    const c = conversationEl();
    if (!c) {
      return;
    }
    const folds = c.querySelectorAll<HTMLDetailsElement>('details.fold.reasoning');
    for (let j = 0; j < folds.length; j++) {
      folds[j].open = reasoningExpanded;
    }
  }

  cc.toggleReasoning = toggleReasoningFolds;
})();
