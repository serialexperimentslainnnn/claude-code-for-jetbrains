(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var announcedBoot = false;
  var announcedMissing = false;
  var installMethods = [];
  var installsBuilt = false;
  var installingId = null;

  CX.renderBoot = function (s) {
    var boot = document.getElementById('boot');
    var app = document.getElementById('app');
    if (!boot) return;
    var missing = !!s.binaryMissing;
    var awaitingAuth = CX.authWanted(s);
    var booting = !s.running;
    var showBoot = missing || (booting && !awaitingAuth);
    boot.hidden = !showBoot;
    boot.classList.toggle('missing', missing);
    if (showBoot && !missing && !announcedBoot) {
      announcedBoot = true;
      CC.announce && CC.announce('Loading Claude Code');
    }
    if (!showBoot) announcedBoot = false;
    if (app) app.classList.toggle('booting', booting);
    CC.coverTranscript && CC.coverTranscript('waiting', showBoot || awaitingAuth);
    var card = document.getElementById('boot-missing');
    if (card) card.hidden = !missing;
    if (missing && !announcedMissing) {
      announcedMissing = true;
      CC.announce && CC.announce('Claude Code was not found. Install options are available.');
    }
    if (!missing) {
      announcedMissing = false;
      installingId = null;
      setBootError('');
    }
    if (!showBoot) return;
    if (missing) {
      renderInstallMethods();
      return;
    }
    var sub = document.getElementById('boot-sub');
    if (sub) sub.textContent = s.resuming ? 'Resuming your session' : 'Starting the agent';
  };

  function renderInstallMethods() {
    var box = document.getElementById('boot-installs');
    if (!box) return;
    if (!installsBuilt) {
      box.textContent = '';
      installMethods.forEach(function (m) {
        var row = document.createElement('div');
        row.className = 'boot-install';

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn primary boot-install-btn';
        btn.setAttribute('data-method', m.id);
        btn.addEventListener('click', function () {
          installingId = m.id;
          setBootError('');
          syncInstallButtons();
          CC.announce && CC.announce('Installing Claude Code. Watch the IDE terminal for progress.');
          CC.send({ type: 'installClaude', method: m.id });
        });
        row.appendChild(btn);

        var hint = document.createElement('div');
        hint.className = 'boot-install-hint';
        var hintLabel = document.createElement('span');
        hintLabel.className = 'boot-install-hint-label';
        hintLabel.textContent = 'or copy this command to ' + (m.shell || 'a shell') + ':';
        var code = document.createElement('code');
        code.className = 'boot-install-cmd';
        code.textContent = m.display;
        var copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn ghost boot-install-copy';
        copy.textContent = 'Copy';
        copy.setAttribute('aria-label', 'Copy the ' + (m.label || 'install') + ' command');
        copy.addEventListener('click', function (e) {
          CC.send({ type: 'copy', text: m.display });
          if (CC.flashCopied) CC.flashCopied(e.currentTarget || copy);
        });
        hint.appendChild(hintLabel);
        hint.appendChild(code);
        hint.appendChild(copy);
        row.appendChild(hint);
        box.appendChild(row);
      });
      installsBuilt = true;
      wireRecheck();
      wirePathRow();
    }
    syncInstallButtons();
  }

  function wireRecheck() {
    var card = document.getElementById('boot-missing');
    var installs = document.getElementById('boot-installs');
    if (!card || document.getElementById('boot-recheck')) return;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = 'boot-recheck';
    btn.className = 'btn ghost boot-recheck';
    btn.textContent = 'Check again';
    btn.setAttribute('aria-label', 'Check again for the Claude Code binary');
    btn.addEventListener('click', function () {
      setBootError('');
      CC.send({ type: 'recheckBinary' });
    });
    card.insertBefore(btn, installs ? installs.nextSibling : null);
  }

  function syncInstallButtons() {
    var box = document.getElementById('boot-installs');
    if (!box) return;
    var btns = box.querySelectorAll('.boot-install-btn');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var m = null;
      for (var j = 0; j < installMethods.length; j++) {
        if (installMethods[j].id === b.getAttribute('data-method')) m = installMethods[j];
      }
      if (!m) continue;
      var busy = installingId === m.id;
      b.textContent = busy ? 'Installing…' : m.label;
      b.classList.toggle('installing', busy);
      b.setAttribute('aria-busy', busy ? 'true' : 'false');
    }
  }

  function wirePathRow() {
    var use = document.getElementById('boot-path-use');
    var input = document.getElementById('boot-path');
    if (!use || !input || use.__wired) return;
    use.__wired = true;
    var submit = function () {
      setBootError('');
      CC.send({ type: 'setBinaryPath', path: input.value || '' });
    };
    use.addEventListener('click', submit);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') submit();
    });
  }

  function setBootError(msg) {
    var el = document.getElementById('boot-path-err');
    if (el) el.textContent = msg || '';
  }

  CX.setInstallMethods = function (methods) {
    installMethods = methods;
    installsBuilt = false;
    renderInstallMethods();
  };

  cc.bootPathError = function (msg) {
    installingId = null;
    syncInstallButtons();
    setBootError(String(msg == null ? '' : msg));
  };
})();
