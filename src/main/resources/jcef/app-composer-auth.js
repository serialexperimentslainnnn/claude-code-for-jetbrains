/* app-composer-auth.js — the sign-in card.
 *
 * One subject: the OAuth/API-key card raised whenever the plugin has no usable credential — which step of the
 * flow is on screen, what each button sends, and clearing a credential out of the DOM the moment it is sent.
 * Driven from renderState via CX.renderAuth (which step of the flow is on screen comes from the host via
 * cc.authState). Whether the card is WANTED is a function of the pushed state and of nothing else — see
 * CX.authWanted. There is deliberately no "raise it anyway" entry point: a second criterion is how the two
 * waiting screens end up disagreeing about which of them is up.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  // ---- sign-in card ---------------------------------------------------------

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

  /**
   * Whether the sign-in card is wanted for a state — the ONE criterion, shared with the boot screen so the
   * two waiting screens cannot disagree about which of them is up.
   *
   * Two suppressions, both from the flow's own order (install -> sign in -> loading -> chat). The install card
   * wins, because signing in is meaningless without a binary. And a session that is STARTING wins, because
   * during startup the answer is still being computed: a launch that resolves the credential in a fraction of
   * a second would otherwise paint the card and remove it, which reads as the plugin flashing.
   *
   * The criterion is `starting`, never `running`. Startup is the only window in which the card is premature;
   * for the rest of a session's life a credential can still expire or be revoked, and this card is the only
   * control that repairs it — suppressing it there leaves a live chat that can no longer take a turn, and no
   * way to sign in from the tab it happened in.
   */
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
    // PASTE INTO THESE TWO FIELDS IS A FALLBACK, NOT A MODE — ask the browser, and ask the host only when
    // the browser came back empty-handed.
    //
    // It used to be gated on `CX.hostClipboard`, a flag the host sets only under the native Wayland toolkit,
    // and that gate is a guess about the environment rather than a fact about this paste. It was wrong in
    // Remote Development, where the flag is false and the browser's clipboard is still not the user's: the
    // page is rendered on the BACKEND and streamed to the thin client, so the clipboard CEF can see belongs
    // to the remote machine and the token the user copied is on their laptop. Pasting into the sign-in card
    // — the one screen you cannot get past without pasting — simply did nothing.
    //
    // Reading the event instead of the environment covers all three cases with no detection at all: X11
    // hands us the text and we let the browser do its job, Wayland and Remote Development hand us nothing
    // and we ask the host. There is deliberately no check for WHICH of those it is: `AppMode.isRemoteDevHost`
    // is internal API (this plugin has had a release blocked over one) and the per-client alternative is
    // experimental, so a gate built on either would be a stability risk taken for information this does not
    // need.
    ['auth-key', 'auth-code'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('paste', function (e) {
        var clip = e.clipboardData;
        var text = clip && typeof clip.getData === 'function' ? clip.getData('text') : '';
        if (text) return; // the browser has it; its own default insert is the right thing
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
