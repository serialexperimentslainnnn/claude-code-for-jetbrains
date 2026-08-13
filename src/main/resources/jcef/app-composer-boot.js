/* app-composer-boot.js — the boot screen and the "Claude Code was not found" card.
 *
 * One subject: what the tab shows before there is a session — the loading state, the install routes with
 * their exact commands, and the manual path escape hatch. Driven from cc.state via CX.renderBoot.
 */
(function () {
  'use strict';

  var cc = window.cc || (window.cc = {});
  var CC = window.CC || (window.CC = {});
  var CX = (CC.composer = CC.composer || {});

  var announcedBoot = false; // guards the boot screen's one-per-boot screen-reader announcement
  var announcedMissing = false; // ditto for the "not found" card, which replaces the loading announcement
  var installMethods = []; // from cc.meta: the OS's install routes for the not-found card
  var installsBuilt = false; // the card's method rows are built once per methods payload
  var installingId = null; // method id whose button shows "Installing…" (cleared on error/card teardown)

  /**
   * The boot screen: up until the binary is running, then gone for good.
   *
   * Three states, not two. `running` means go; `starting` means wait; NEITHER means the launch finished without
   * a process — a missing binary, a declined trust prompt, a refused remote-mount project. That last case must
   * clear the screen, or a failed launch leaves the tab covered forever with no way to see the notification
   * explaining why. The host fires a state push on that path precisely so this can happen.
   */
  CX.renderBoot = function (s) {
    var boot = document.getElementById('boot');
    var app = document.getElementById('app');
    if (!boot) return;
    // ONE invariant, and everything else follows from it: **the chat is reachable only while the `claude`
    // process is running.** Install -> sign in -> loading -> plugin, in that order, and any step backwards
    // (the process exits, is restarted, the credential goes away) returns to the matching screen rather
    // than leaving a chat on screen that has nothing behind it.
    //
    // This used to be `starting || binaryMissing`, so every OTHER not-running state — signed out, a dead
    // process, a launch that failed — fell through to the chat UI, which then rendered its first frame with
    // no session behind it and stayed half-empty.
    // Exactly ONE screen at a time, in the order the flow runs: install -> sign in -> loading -> chat.
    // `#boot` hosts the install card and the spinner; the sign-in card is its own layer, so the spinner
    // must stand down while it is up or it would simply cover it (z-index 60 over 55).
    var missing = !s.running && !!s.binaryMissing;
    var awaitingAuth = !s.running && !missing && (!!s.needsLogin || CX.authForced);
    var booting = !s.running;
    var showBoot = missing || (booting && !awaitingAuth);
    boot.hidden = !showBoot;
    boot.classList.toggle('missing', missing);
    // Announce the FIRST booting render, not just a transition into it. The screen is already on-screen when
    // the page loads, so the common case never transitions — and the element's own aria-live never fires
    // either, because static markup present at load is not a mutation. Once per boot: `announcedBoot` resets
    // when the screen comes down, so a later relaunch announces again.
    if (showBoot && !missing && !announcedBoot) {
      announcedBoot = true;
      CC.announce && CC.announce('Loading Claude Code');
    }
    if (!showBoot) announcedBoot = false;
    // `booting`, not `showBoot`: the chat stays inert for the sign-in screen too.
    if (app) app.classList.toggle('booting', booting);
    var card = document.getElementById('boot-missing');
    if (card) card.hidden = !missing;
    if (missing && !announcedMissing) {
      announcedMissing = true;
      CC.announce && CC.announce('Claude Code was not found. Install options are available.');
    }
    if (!missing) {
      announcedMissing = false;
      installingId = null; // a fresh boot resets any "Installing…" button
      setBootError('');
    }
    if (!showBoot) return;
    if (missing) {
      renderInstallMethods();
      return;
    }
    var sub = document.getElementById('boot-sub');
    // Distinguish the two waits: a fresh launch versus resuming an existing session, which reads a transcript
    // back and is the slower of the two. Guessing "Starting" for both made the longer wait look like a hang.
    if (sub) sub.textContent = s.resuming ? 'Resuming your session' : 'Starting the agent';
  };

  /**
   * The not-found card's method rows: per install route, a button ("Install via X") and under it the exact
   * command with a copy affordance ("or copy this command to bash: …"). The command text is the fallback
   * that matters: the button runs it in the IDE terminal, and when a corporate network or a missing
   * Terminal plugin breaks that, the user copies the same command and runs it anywhere.
   */
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

  /**
   * "Check again" — the card's answer for an install the user ran somewhere else.
   *
   * The host watcher behind this card already re-derives the boot state every few seconds, so the button buys
   * no capability; it buys an ANSWER. Someone who copied the command, ran it in their own terminal and came
   * back has no way to say "done, look again", and a card that only ever changes on someone else's timer
   * reads as broken while it waits.
   *
   * Deliberately NO busy state. The two outcomes both speak for themselves — the binary turns up and the card
   * falls away (which is when `renderBoot` announces the wait), or the host writes the reason into
   * `#boot-path-err`, which is `role="alert"` — so a disabled button or an "Checking…" label would only add a
   * state that wedges if a reply never arrives. Clicking twice is two requests, which is exactly right.
   *
   * Built here rather than in `shell.html` for the same reason as the install buttons, and idempotent because
   * a fresh `installMethods` push rebuilds those and must not leave a second copy of this one behind.
   */
  function wireRecheck() {
    var card = document.getElementById('boot-missing');
    var installs = document.getElementById('boot-installs');
    if (!card || document.getElementById('boot-recheck')) return;
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = 'boot-recheck';
    btn.className = 'btn ghost boot-recheck';
    btn.textContent = 'Check again';
    // The visible label is the start of the accessible name (WCAG 2.5.3), which just makes it survive being
    // read out of context — "Check again" alone says nothing in a screen reader's list of buttons.
    btn.setAttribute('aria-label', 'Check again for the Claude Code binary');
    btn.addEventListener('click', function () {
      // Drop any previous message first: the answer to THIS click has to be a fresh mutation of the alert
      // region, or an identical reason twice in a row is announced once.
      setBootError('');
      CC.send({ type: 'recheckBinary' });
    });
    card.insertBefore(btn, installs ? installs.nextSibling : null);
  }

  /** Button labels track the one installing: "Installing…" on it, normal labels (enabled) on the rest, so a
   *  visibly failed attempt in the terminal can be retried by another route without any reset step. */
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

  /** Host → card (via cc.meta): this OS's install routes. Rebuilds the buttons if the card is up. */
  CX.setInstallMethods = function (methods) {
    installMethods = methods;
    installsBuilt = false; // rebuild the card's buttons if it is (or becomes) visible
    renderInstallMethods();
  };

  /** Host → card: a validation or install-launch failure, verbatim. Clears the "Installing…" state so the
   *  buttons are usable again — the error IS the end of that attempt. */
  cc.bootPathError = function (msg) {
    installingId = null;
    syncInstallButtons();
    setBootError(String(msg == null ? '' : msg));
  };
})();
