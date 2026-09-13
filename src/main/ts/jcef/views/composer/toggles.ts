(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const h = CX.h;
  const send = CX.send;

  let followOn = true;
  let followBtnRef: HTMLElement | null = null;
  let guardOn = true;
  let guardBtnRef: HTMLElement | null = null;
  let rcOn = false;
  let rcError: string | null = null;
  let rcBtnRef: HTMLElement | null = null;
  let godOn = false;
  let flameBtnRef: HTMLElement | null = null;
  let godSwitchRef: HTMLElement | null = null;

  function godSwitchLabel(): string {
    return godOn ? 'Turn God Mode off' : 'Turn God Mode on';
  }

  function applyGodMode(): void {
    if (!flameBtnRef) return;
    flameBtnRef.classList.toggle('active', godOn);
    flameBtnRef.innerHTML = CX.flameGlyph(godOn);
    flameBtnRef.title = godOn
      ? 'Claude God Mode is on — Claude is one with your IDE. Click to switch it off or configure it'
      : 'Claude God Mode is off. Click to make Claude one with your IDE';
    if (godSwitchRef) godSwitchRef.textContent = godSwitchLabel();
  }

  CX.setGodMode = function (on: boolean | undefined): void {
    const next = on === true;
    if (next === godOn) return;
    godOn = next;
    applyGodMode();
  };

  function applyFollow(): void {
    if (followBtnRef) {
      if (followOn) followBtnRef.classList.add('active');
      else followBtnRef.classList.remove('active');
    }
    if (CC && typeof CC.emit === 'function') CC.emit('follow', followOn);
  }
  CX.applyFollow = applyFollow;

  function followGlyph(): string {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M4 4.5 8 8l4-3.5"/><path d="M4 9 8 12.5l4-3.5"/></svg>'
    );
  }
  function guardGlyph(): string {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M8 1.8 13 3.6v4.1c0 3-2.1 5.6-5 6.5-2.9-.9-5-3.5-5-6.5V3.6z"/></svg>'
    );
  }
  function phoneGlyph(): string {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<rect x="4.6" y="1.5" width="6.8" height="13" rx="1.6"/><path d="M7 12.4h2"/></svg>'
    );
  }

  function applyGuard(): void {
    if (!guardBtnRef) return;
    if (guardOn) guardBtnRef.classList.add('active');
    else guardBtnRef.classList.remove('active');
    guardBtnRef.title = guardOn
      ? 'Sensitive Guard is on — click to switch it off'
      : 'Sensitive Guard is OFF — click to switch it back on';
  }

  CX.setGuardOn = function (on: boolean | undefined): void {
    const next = on !== false;
    if (next === guardOn) return;
    guardOn = next;
    applyGuard();
  };

  function applyRemoteControl(): void {
    if (!rcBtnRef) return;
    rcBtnRef.classList.toggle('active', rcOn);
    rcBtnRef.classList.toggle('failed', !rcOn && !!rcError);
    if (!rcOn && rcError) rcBtnRef.title = rcError;
    else if (rcOn) rcBtnRef.title = 'Remote Control is on — click to disconnect this chat from claude.ai';
    else rcBtnRef.title = 'Remote Control is off — click to drive this chat from claude.ai';
  }

  CX.setRemoteControlOn = function (on: boolean | undefined, error: unknown): void {
    const next = on === true;
    const nextError = typeof error === 'string' && error ? error : null;
    if (next === rcOn && nextError === rcError) return;
    rcOn = next;
    rcError = nextError;
    applyRemoteControl();
  };

  function iconButton(cls: string, label: string, glyph: string, onClick: (e: Event) => void): HTMLElement {
    const btn = h('button', {
      class: cls,
      title: label,
      attrs: { type: 'button', 'aria-label': label },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          onClick(e);
        },
      },
    });
    btn.innerHTML = glyph;
    return btn;
  }

  CX.buildToggles = function (barRight: HTMLElement) {
    const vibeIcon = h('span', { class: 'vibe-emoji' });
    vibeIcon.innerHTML = window.CC && CC.nyanSvg ? CC.nyanSvg() : '🌈';
    const vibeBtn = h(
      'button',
      {
        class: 'bar-icon pill-vibe',
        title: 'Vibe Mode',
        attrs: { type: 'button', 'aria-label': 'Vibe Mode' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            const on = !(window.CC && CC.isVibe && CC.isVibe());
            send({ type: 'changeVibe', on: on });
          },
        },
      },
      vibeIcon
    );

    const followBtn = iconButton('bar-icon active', 'Auto-follow scrolling', followGlyph(), function () {
      followOn = !followOn;
      applyFollow();
    });
    followBtn.title = 'Auto-scroll (follow output)';
    followBtnRef = followBtn;

    const guardBtn = h('button', {
      class: 'bar-icon active',
      attrs: {
        type: 'button',
        'aria-label': 'Sensitive Guard',
        'aria-expanded': 'false',
        'aria-haspopup': 'menu',
      },
    });
    guardBtn.innerHTML = guardGlyph();
    guardBtnRef = guardBtn;

    const rcBtn = h('button', {
      class: 'bar-icon',
      attrs: { type: 'button', 'aria-label': 'Remote Control' },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          send({ type: 'settingsToggle', key: 'remoteControl', on: !rcOn });
        },
      },
    });
    rcBtn.innerHTML = phoneGlyph();
    rcBtnRef = rcBtn;
    applyRemoteControl();

    const flameBtn = h('button', {
      class: 'bar-icon flame',
      attrs: {
        type: 'button',
        'aria-label': 'Claude God Mode',
        'aria-expanded': 'false',
        'aria-haspopup': 'menu',
      },
    });
    flameBtnRef = flameBtn;
    const godMenu = CC.pickMenu({
      anchor: flameBtn,
      home: barRight,
      label: 'Claude God Mode menu',
      menuClass: 'god-mode-menu',
      itemClass: 'god-mode-option',
      items: [
        { value: 'switch', label: godSwitchLabel() },
        { value: 'configure', label: 'Configure God Mode' },
      ],
      onPick: function (value) {
        if (value === 'switch') send({ type: 'settingsToggle', key: 'godMode', on: !godOn });
        else CX.settings.openGroup('Claude God Mode');
      },
    });
    godSwitchRef = godMenu.menu.querySelector('[role="menuitem"]');
    flameBtn.addEventListener('click', function (e: Event) {
      e.preventDefault();
      e.stopPropagation();
      godMenu.toggle();
    });
    applyGodMode();

    const guardMenu = CC.durationMenu({
      anchor: guardBtn,
      home: barRight,
      label: 'Switch the Sensitive Guard off for',
      onPick: function (token) {
        send({ type: 'guardMaster', on: false, duration: token });
      },
    });
    guardBtn.addEventListener('click', function (e: Event) {
      e.preventDefault();
      e.stopPropagation();
      if (!guardOn) {
        guardMenu.close();
        send({ type: 'guardMaster', on: true, duration: '' });
        return;
      }
      guardMenu.toggle();
    });

    applyGuard();
    return { follow: followBtn, guard: guardBtn, rc: rcBtn, flame: flameBtn, vibe: vibeBtn };
  };
})();
