(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  let authWired = false;
  let announcedAuth = false;

  function authCard(): AuthCard | null {
    return document.getElementById('auth-card') as AuthCard | null;
  }

  function setAuthStep(step: string, url?: unknown, message?: unknown): void {
    const card = authCard();
    if (!card) return;
    const wire = step;
    if (step === 'url' || step === 'code') step = 'browser';
    const steps = card.querySelectorAll<HTMLElement>('.auth-step');
    for (let i = 0; i < steps.length; i++) {
      steps[i].hidden = steps[i].getAttribute('data-step') !== step;
    }
    if (step === 'idle' || step === 'waiting') card.__url = null;
    if (wire === 'url' && url) card.__url = String(url);
    const hasUrl = !!card.__url;
    const open = document.getElementById('auth-url-open') as HTMLButtonElement | null;
    const copy = document.getElementById('auth-url-copy') as HTMLButtonElement | null;
    if (open) open.disabled = !hasUrl;
    if (copy) copy.disabled = !hasUrl;
    if (wire === 'code') {
      const label = document.getElementById('auth-code-label');
      if (label) label.textContent = 'Paste the authorization code from the browser (or just finish there)';
      const c = document.getElementById('auth-code');
      if (c) c.focus();
    }
    if (step === 'error') {
      const e = document.getElementById('auth-error');
      if (e) e.textContent = (message as string) || 'Sign-in failed. Please try again.';
    }
  }

  cc.authState = function (s?: unknown): void {
    const st = s as { step?: unknown; url?: unknown; message?: unknown } | null;
    if (!st || !st.step) return;
    setAuthStep(String(st.step), st.url, st.message);
  };

  CX.authWanted = function (s: ComposerState): boolean {
    return !!s.needsLogin && !s.binaryMissing && !s.starting;
  };

  function renderAuth(s: ComposerState): void {
    const card = authCard();
    if (!card) return;
    const visible = CX.authWanted(s);
    if (!visible && !card.hidden) {
      announcedAuth = false;
      setAuthStep('idle');
    }
    if (visible && card.hidden) {
      wireAuthCard();
      if (!announcedAuth) {
        announcedAuth = true;
        CC.announce && CC.announce('Sign in to Claude. Options are available.');
      }
    }
    card.hidden = !visible;
  }
  CX.renderAuth = renderAuth;

  function inputValue(id: string): string {
    const input = document.getElementById(id) as HTMLInputElement | null;
    const value = input ? input.value : '';
    if (input) input.value = '';
    return value;
  }

  function submitOnEnter(inputId: string, buttonId: string): void {
    const input = document.getElementById(inputId);
    if (!input) return;
    input.addEventListener('keydown', function (e: KeyboardEvent) {
      if (e.key === 'Enter') {
        const btn = document.getElementById(buttonId);
        if (btn) btn.click();
      }
    });
  }

  function wireAuthCard(): void {
    if (authWired) return;
    authWired = true;
    ['auth-key', 'auth-code'].forEach(function (id) {
      const el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('paste', function (e: ClipboardEvent) {
        const clip = e.clipboardData;
        const text = clip && typeof clip.getData === 'function' ? clip.getData('text') : '';
        if (text) return;
        e.preventDefault();
        CC.send({ type: 'pasteClipboard' });
      });
    });
    const on = function (id: string, fn: (e: MouseEvent) => void): void {
      const el = document.getElementById(id);
      if (el) el.addEventListener('click', fn);
    };
    on('auth-sub', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginSubscription' });
    });
    on('auth-console', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginConsole' });
    });
    on('auth-key-toggle', function (e) {
      const fields = document.getElementById('auth-key-fields');
      if (!fields) return;
      const open = fields.hidden;
      fields.hidden = !open;
      const btn = e.currentTarget as HTMLElement;
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.textContent = open ? 'Hide the API key field' : 'Use an API key instead';
      if (open) {
        const input = document.getElementById('auth-key');
        if (input) input.focus();
      }
    });
    on('auth-key-use', function () {
      CC.send({ type: 'useApiKey', key: inputValue('auth-key') });
    });
    on('auth-code-use', function () {
      const code = inputValue('auth-code');
      setAuthStep('verifying');
      CC.send({ type: 'submitLoginCode', code: code });
    });
    const cancel = function () {
      CC.send({ type: 'cancelLogin' });
      setAuthStep('idle');
    };
    on('auth-cancel', cancel);
    on('auth-cancel-waiting', cancel);
    on('auth-cancel-verify', cancel);
    on('auth-dismiss', function () {
      CC.send({ type: 'dismissAuth' });
    });
    on('auth-retry', function () {
      setAuthStep('idle');
    });
    on('auth-url-copy', function (e) {
      const card = authCard();
      CC.send({ type: 'copy', text: (card && card.__url) || '' });
      if (CC.flashCopied) CC.flashCopied(e.currentTarget as HTMLElement);
    });
    on('auth-url-open', function () {
      const card = authCard();
      if (card && card.__url) CC.send({ type: 'open', url: card.__url });
    });
    submitOnEnter('auth-key', 'auth-key-use');
    submitOnEnter('auth-code', 'auth-code-use');
  }
})();
