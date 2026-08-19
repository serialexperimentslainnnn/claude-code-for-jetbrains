(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var authWired = false;
  var announcedAuth = false;

  function setAuthStep(step, url, message) {
    var card = document.getElementById('auth-card');
    if (!card) return;
    var wire = step;
    if (step === 'url' || step === 'code') step = 'browser';
    var steps = card.querySelectorAll('.auth-step');
    for (var i = 0; i < steps.length; i++) {
      steps[i].hidden = steps[i].getAttribute('data-step') !== step;
    }
    if (step === 'idle' || step === 'waiting') card.__url = null;
    if (wire === 'url' && url) card.__url = url;
    var hasUrl = !!card.__url;
    var open = document.getElementById('auth-url-open');
    var copy = document.getElementById('auth-url-copy');
    if (open) open.disabled = !hasUrl;
    if (copy) copy.disabled = !hasUrl;
    if (wire === 'code') {
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

  CX.authWanted = function (s) {
    return !!s.needsLogin && !s.binaryMissing && !s.starting;
  };

  function renderAuth(s) {
    var card = document.getElementById('auth-card');
    if (!card) return;
    var visible = CX.authWanted(s);
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

  function wireAuthCard() {
    if (authWired) return;
    authWired = true;
    ['auth-key', 'auth-code'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('paste', function (e) {
        var clip = e.clipboardData;
        var text = clip && typeof clip.getData === 'function' ? clip.getData('text') : '';
        if (text) return;
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
    on('auth-console', function () {
      setAuthStep('waiting');
      CC.send({ type: 'loginConsole' });
    });
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
      if (input) input.value = '';
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
    on('auth-cancel-verify', cancel);
    on('auth-dismiss', function () {
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
