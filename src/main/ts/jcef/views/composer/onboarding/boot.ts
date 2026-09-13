(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  let announcedBoot = false;
  let announcedMissing = false;
  let installMethods: InstallMethod[] = [];
  let installsBuilt = false;
  let installingId: string | null = null;

  CX.renderBoot = function (s: ComposerState): void {
    const boot = document.getElementById('boot');
    if (!boot) return;
    const missing = !!s.binaryMissing;
    const awaitingAuth = CX.authWanted(s);
    const booting = !s.running;
    const showBoot = missing || (booting && !awaitingAuth);
    boot.hidden = !showBoot;
    boot.classList.toggle('missing', missing);
    if (showBoot && !missing && !announcedBoot) {
      announcedBoot = true;
      CC.announce && CC.announce('Loading Claude Code');
    }
    if (!showBoot) announcedBoot = false;
    CC.coverTranscript && CC.coverTranscript('waiting', showBoot || awaitingAuth);
    const card = document.getElementById('boot-missing');
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
    const sub = document.getElementById('boot-sub');
    if (sub) sub.textContent = s.resuming ? 'Resuming your session' : 'Starting the agent';
  };

  function renderInstallMethods(): void {
    const box = document.getElementById('boot-installs');
    if (!box) return;
    if (!installsBuilt) {
      box.textContent = '';
      installMethods.forEach(function (m) {
        const row = document.createElement('div');
        row.className = 'boot-install';

        const btn = document.createElement('button');
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

        const hint = document.createElement('div');
        hint.className = 'boot-install-hint';
        const hintLabel = document.createElement('span');
        hintLabel.className = 'boot-install-hint-label';
        hintLabel.textContent = 'or copy this command to ' + (m.shell || 'a shell') + ':';
        const code = document.createElement('code');
        code.className = 'boot-install-cmd';
        code.textContent = m.display;
        const copy = document.createElement('button');
        copy.type = 'button';
        copy.className = 'btn ghost boot-install-copy';
        copy.textContent = 'Copy';
        copy.setAttribute('aria-label', 'Copy the ' + (m.label || 'install') + ' command');
        copy.addEventListener('click', function (e: MouseEvent) {
          CC.send({ type: 'copy', text: m.display });
          if (CC.flashCopied) CC.flashCopied((e.currentTarget as HTMLElement) || copy);
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

  function wireRecheck(): void {
    const card = document.getElementById('boot-missing');
    const installs = document.getElementById('boot-installs');
    if (!card || document.getElementById('boot-recheck')) return;
    const btn = document.createElement('button');
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

  function syncInstallButtons(): void {
    const box = document.getElementById('boot-installs');
    if (!box) return;
    const btns = box.querySelectorAll<HTMLElement>('.boot-install-btn');
    for (let i = 0; i < btns.length; i++) {
      const b = btns[i];
      let m: InstallMethod | null = null;
      for (let j = 0; j < installMethods.length; j++) {
        if (installMethods[j].id === b.getAttribute('data-method')) m = installMethods[j];
      }
      if (!m) continue;
      const busy = installingId === m.id;
      b.textContent = busy ? 'Installing…' : m.label || '';
      b.classList.toggle('installing', busy);
      b.setAttribute('aria-busy', busy ? 'true' : 'false');
    }
  }

  function wirePathRow(): void {
    const use = document.getElementById('boot-path-use') as WiredEl | null;
    const input = document.getElementById('boot-path') as HTMLInputElement | null;
    if (!use || !input || use.__wired) return;
    use.__wired = true;
    const submit = function () {
      setBootError('');
      CC.send({ type: 'setBinaryPath', path: input.value || '' });
    };
    use.addEventListener('click', submit);
    input.addEventListener('keydown', function (e: KeyboardEvent) {
      if (e.key === 'Enter') submit();
    });
  }

  function setBootError(msg: string): void {
    const el = document.getElementById('boot-path-err');
    if (el) el.textContent = msg || '';
  }

  CX.setInstallMethods = function (methods: InstallMethod[]): void {
    installMethods = methods;
    installsBuilt = false;
    renderInstallMethods();
  };

  cc.bootPathError = function (msg?: unknown): void {
    installingId = null;
    syncInstallButtons();
    setBootError(String(msg == null ? '' : msg));
  };
})();
