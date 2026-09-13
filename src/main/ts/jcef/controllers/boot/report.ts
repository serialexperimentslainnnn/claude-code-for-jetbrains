(function () {
  'use strict';

  const CC = window.CC || (window.CC = {} as CcShared);

  function classNameOf(node: Node | null): string {
    return node ? '' + ((node as HTMLElement).className || '') : '';
  }

  document.addEventListener(
    'click',
    function (ev: MouseEvent) {
      let node = ev.target as Node | null;
      while (node && node !== document) {
        const el = node as HTMLElement;
        if (el.tagName === 'A' && el.hasAttribute('href')) {
          const url = el.getAttribute('href');
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

  function copyTargetText(copyEl: HTMLElement): string {
    let pre: Element | null = copyEl.closest ? copyEl.closest('pre') : null;
    if (!pre) {
      let n: Node | null = copyEl.parentNode;
      while (n && (n as Element).tagName !== 'PRE') n = n.parentNode;
      pre = n as Element | null;
    }
    const code = pre ? pre.querySelector('code') : null;
    return code ? code.textContent || '' : '';
  }
  function flashCopied(copyEl: HTMLElement): void {
    const el = copyEl as FlashEl;
    if (el.__ccFlashTimer != null) clearTimeout(el.__ccFlashTimer);
    else el.__ccFlashLabel = el.textContent;
    el.textContent = 'Copied';
    el.classList.add('copied');
    el.__ccFlashTimer = setTimeout(function () {
      el.textContent = el.__ccFlashLabel == null ? '' : el.__ccFlashLabel;
      el.classList.remove('copied');
      el.__ccFlashTimer = null;
    }, 1200);
  }
  CC.flashCopied = flashCopied;
  function handleCopyFromCodeHead(ev: Event, copyEl: HTMLElement): void {
    const text = copyTargetText(copyEl);
    if (!text) return;
    ev.preventDefault();
    ev.stopPropagation();
    CC.send({ type: 'copy', text: text });
    flashCopied(copyEl);
  }
  function isCodeHeadCopy(node: Node | null): node is HTMLElement {
    return (
      !!node &&
      classNameOf(node).indexOf('copy') >= 0 &&
      !!node.parentNode &&
      classNameOf(node.parentNode).indexOf('code-head') >= 0
    );
  }
  document.addEventListener(
    'click',
    function (ev: MouseEvent) {
      let node = ev.target as Node | null;
      while (node && node !== document) {
        if (isCodeHeadCopy(node)) {
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
    function (ev: KeyboardEvent) {
      if (ev.key !== 'Enter' && ev.key !== ' ' && ev.key !== 'Spacebar') return;
      const node = ev.target as Node | null;
      if (isCodeHeadCopy(node)) {
        handleCopyFromCodeHead(ev, node);
      }
    },
    true
  );

  const reported: Record<string, true> = Object.create(null);
  let reportedCount = 0;
  const MAX_REPORTED = 20;
  function reportUncaught(what: string, error: unknown): void {
    const stack = error && typeof error === 'object' ? (error as { stack?: unknown }).stack : undefined;
    const text = stack ? String(stack) : String(error);
    const key = what + '|' + text.split('\n')[0];
    if (reported[key] || reportedCount >= MAX_REPORTED) return;
    reported[key] = true;
    reportedCount++;
    CC.send({ type: 'diag', report: 'uncaught ' + what + ': ' + text });
  }
  window.addEventListener('error', function (ev: ErrorEvent) {
    reportUncaught('error', ev.error || ev.message);
  });
  window.addEventListener('unhandledrejection', function (ev: PromiseRejectionEvent) {
    reportUncaught('rejection', ev.reason);
  });
  window.addEventListener('securitypolicyviolation', function (ev: SecurityPolicyViolationEvent) {
    reportUncaught(
      'csp',
      ev.violatedDirective +
        ' blocked ' +
        (ev.blockedURI || 'inline') +
        ' (' +
        ev.sourceFile +
        ':' +
        ev.lineNumber +
        ')'
    );
  });

  CC.selfCheck = function (): void {
    const expected = ['batch', 'clear', 'state', 'permissions', 'session', 'tabs', 'theme', 'settingsMenu'];
    const missing: string[] = [];
    for (let i = 0; i < expected.length; i++) {
      if (typeof (window.cc || {})[expected[i]] !== 'function') missing.push('cc.' + expected[i]);
    }
    if (missing.length) CC.send({ type: 'diag', report: 'uncaught missing: ' + missing.join(', ') });
  };
})();
