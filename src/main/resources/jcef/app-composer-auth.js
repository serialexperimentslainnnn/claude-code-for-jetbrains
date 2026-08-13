/* app-composer-auth.js — the sign-in card.
 *
 * One subject: the OAuth/API-key card raised whenever the plugin has no usable credential — which step of the
 * flow is on screen, what each button sends, and clearing a credential out of the DOM the moment it is sent.
 * Driven from renderState via CX.renderAuth, and from the host via cc.authState / cc.showAuth.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  // ---- sign-in card ---------------------------------------------------------

  /** Raised by cc.showAuth() (fresh install) until dismissed or resolved. Read by the boot screen too. */
  CX.authForced = false;
  var authWired = false;
  var announcedAuth = false;

  /**
   * The whole card is a function of one step name; every step's markup exists statically in the shell and
   * exactly one is visible. Credential inputs are cleared the moment their value is sent — the DOM is a
   * debug surface (DevTools, DOM dumps) and a secret must not linger in it.
   */
  function setAuthStep(step, url, message) {
    var card = document.getElementById('auth-card');
    if (!card) return;
    // The host still speaks in flow events ('url' appeared / 'code' requested); both land on the ONE
    // combined browser step — the callback path and the paste path are the same screen, with the code
    // field merely emphasised once the binary explicitly asks for it.
    var wire = step;
    if (step === 'url' || step === 'code') step = 'browser';
    var steps = card.querySelectorAll('.auth-step');
    for (var i = 0; i < steps.length; i++) {
      steps[i].hidden = steps[i].getAttribute('data-step') !== step;
    }
    // A restarted flow gets a fresh authorize URL; keeping the previous one would send the user to a
    // consent page for an attempt that is already over.
    if (step === 'idle' || step === 'waiting') card.__url = null;
    if (wire === 'url' && url) card.__url = url;
    // Both buttons act on a URL the host has to have handed us; until it does they are inert rather than
    // silently no-op, so nobody clicks "Open your browser" and concludes the card is broken.
    var hasUrl = !!card.__url;
    var open = document.getElementById('auth-url-open');
    var copy = document.getElementById('auth-url-copy');
    if (open) open.disabled = !hasUrl;
    if (copy) copy.disabled = !hasUrl;
    if (wire === 'code') {
      // The binary is now waiting for input — make the optional field the obvious next thing without
      // hiding the "the browser can still finish this" framing.
      var label = document.getElementById('auth-code-label');
      if (label) label.textContent = 'Paste the authorization code from the browser (or just finish there)';
      var c = document.getElementById('auth-code');
      if (c) c.focus();
    }
    if (step === 'error') {
      var e = document.getElementById('auth-error');
      if (e) e.textContent = message || 'Sign-in failed. Please try again.';
    }
  }

  cc.authState = function (s) {
    if (!s || !s.step) return;
    setAuthStep(String(s.step), s.url, s.message);
  };

  /** Host → card, proactively (a fresh install has no credentials): raise it without waiting for state. */
  cc.showAuth = function () {
    CX.authForced = true;
    setAuthStep('idle');
    renderAuth(lastAuthState || {});
  };

  var lastAuthState = null;

  function renderAuth(s) {
    lastAuthState = s;
    var card = document.getElementById('auth-card');
    if (!card) return;
    // Two gates, both from the same rule (install -> sign in -> loading -> chat): the install card wins,
    // because signing in is meaningless without a binary; and a RUNNING session wins over both, so the
    // card cannot linger over a live chat once the sign-in it was asking for has happened.
    var visible = (!!s.needsLogin || CX.authForced) && !s.binaryMissing && !s.running;
    if (!visible && !card.hidden) {
      CX.authForced = false;
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
    if (!s.needsLogin && !CX.authForced) CX.authForced = false;
  }
  CX.renderAuth = renderAuth;

  function wireAuthCard() {
    if (authWired) return;
    authWired = true;
    // Under the native-Wayland toolkit CEF's clipboard is isolated from the system one, so a plain
    // paste into these fields yields nothing. Route it through the host, which reads the real
    // clipboard — cc.insertText then lands it in whichever field has focus.
    ['auth-key', 'auth-code'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('paste', function (e) {
        if (!CX.hostClipboard) return;
        e.preventDefault();
        CC.send({ type: 'pasteClipboard' });
      });
    });
    var on = function (id, fn) {
      var el = document.getElementById(id);
      if (el) el.addEventListener('click', fn);
    };
    on('auth-sub', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginSubscription' });
    });
    // The organisation route: same OAuth dance, same card, but the consent grants org:create_api_key and the
    // binary mints the key itself — nothing to paste, nothing to hand around.
    on('auth-console', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginConsole' });
    });
    // Typing a key by hand is the exception now, so it starts collapsed. Kept rather than removed: someone
    // holding only a bare API key must not be sent to Settings to get started.
    on('auth-key-toggle', function (e) {
      var fields = document.getElementById('auth-key-fields');
      if (!fields) return;
      var open = fields.hidden;
      fields.hidden = !open;
      var btn = e.currentTarget;
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.textContent = open ? 'Hide the API key field' : 'Use an API key instead';
      if (open) {
        var input = document.getElementById('auth-key');
        if (input) input.focus();
      }
    });
    on('auth-key-use', function () {
      var input = document.getElementById('auth-key');
      var key = input ? input.value : '';
      if (input) input.value = ''; // never leave a credential sitting in the DOM
      CC.send({ type: 'useApiKey', key: key });
    });
    on('auth-code-use', function () {
      var input = document.getElementById('auth-code');
      var code = input ? input.value : '';
      if (input) input.value = '';
      setAuthStep('verifying');
      CC.send({ type: 'submitLoginCode', code: code });
    });
    var cancel = function () {
      CC.send({ type: 'cancelLogin' });
      setAuthStep('idle');
    };
    on('auth-cancel', cancel);
    on('auth-cancel-waiting', cancel);
    // Verifying has its own Cancel: a submit that never resolves must not strand the user on a spinner
    // with no exit — observed live before the raw-TTY Enter fix, and worth an escape hatch regardless.
    on('auth-cancel-verify', cancel);
    on('auth-dismiss', function () {
      CX.authForced = false;
      CC.send({ type: 'dismissAuth' });
    });
    on('auth-retry', function () {
      setAuthStep('idle');
    });
    on('auth-url-copy', function (e) {
      var card = document.getElementById('auth-card');
      CC.send({ type: 'copy', text: (card && card.__url) || '' });
      if (CC.flashCopied) CC.flashCopied(e.currentTarget);
    });
    on('auth-url-open', function () {
      var card = document.getElementById('auth-card');
      if (card && card.__url) CC.send({ type: 'open', url: card.__url });
    });
    var keyInput = document.getElementById('auth-key');
    if (keyInput) {
      keyInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          var btn = document.getElementById('auth-key-use');
          if (btn) btn.click();
        }
      });
    }
    var codeInput = document.getElementById('auth-code');
    if (codeInput) {
      codeInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          var btn = document.getElementById('auth-code-use');
          if (btn) btn.click();
        }
      });
    }
  }
})();
