(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const el = TX.el;
  const conversationEl = TX.conversationEl;

  let findBar: HTMLElement | null = null;
  let findInput: HTMLInputElement | null = null;
  let findCount: HTMLElement | null = null;

  function emitSearch(q: string): void {
    if (CC.emit) {
      try {
        CC.emit('search', q);
        return;
      } catch (e) {}
    }
    TX.runSearch(q, false);
  }

  function updateFindCount(): void {
    if (!findCount) {
      return;
    }
    const q = (findInput && findInput.value) || '';
    if (!q) {
      findCount.textContent = '';
      return;
    }
    const n = TX.hitCount();
    findCount.textContent = n === 0 ? 'No results' : TX.activeHit() + 1 + ' / ' + n;
  }
  TX.updateFindCount = updateFindCount;

  function ensureFindBar(): HTMLElement {
    if (findBar) {
      return findBar;
    }
    const input = el('input', {
      class: 'find-input',
      attrs: { type: 'text', placeholder: 'Find…', spellcheck: 'false' },
    }) as HTMLInputElement;
    findInput = input;
    findCount = el('span', { class: 'find-count' });
    const closeBtn = el('span', {
      class: 'find-x',
      text: '✕',
      title: 'Close',
      attrs: { role: 'button' },
      on: {
        click: function (e: Event) {
          e.preventDefault();
          e.stopPropagation();
          closeFindBar();
        },
      },
    });
    const bar = el('div', { class: 'find-bar' });
    findBar = bar;
    bar.hidden = true;
    bar.appendChild(input);
    bar.appendChild(findCount);
    bar.appendChild(closeBtn);

    input.addEventListener('input', function () {
      emitSearch(input.value || '');
      updateFindCount();
    });
    input.addEventListener('keydown', function (e: KeyboardEvent) {
      if (e.key === 'Escape' || e.keyCode === 27) {
        e.preventDefault();
        e.stopPropagation();
        closeFindBar();
      } else if (e.key === 'Enter' || e.keyCode === 13) {
        e.preventDefault();
        if (e.shiftKey) TX.findPrev();
        else TX.findNext();
      }
    });

    const host = document.getElementById('work') || document.body || conversationEl();
    if (host) {
      host.appendChild(bar);
    }
    return bar;
  }

  function openFindBar(): void {
    ensureFindBar();
    if (!findBar) {
      return;
    }
    findBar.hidden = false;
    if (findInput) {
      try {
        findInput.focus();
        findInput.select();
      } catch (e) {}
      if (findInput.value) {
        emitSearch(findInput.value);
        updateFindCount();
      }
    }
  }

  function closeFindBar(): void {
    if (findBar) {
      findBar.hidden = true;
    }
    emitSearch('');
    if (findCount) {
      findCount.textContent = '';
    }
  }

  TX.resetFindBar = function (): void {
    if (findInput) findInput.value = '';
    if (findCount) findCount.textContent = '';
    if (findBar) findBar.hidden = true;
  };

  function isTextField(target: EventTarget | null): boolean {
    const el = target as HTMLElement | null;
    if (!el || !el.tagName) return false;
    return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable === true;
  }

  function ownsEscape(e: KeyboardEvent): boolean {
    if (!findBar || findBar.hidden) return false;
    const target = e.target as Node | null;
    if (findBar.contains(target)) return true;
    const composer = CC.els && CC.els.composer;
    const palette = CC.els && CC.els.palette;
    const paletteOpen = !!(palette && !palette.hasAttribute('hidden'));
    return !!(composer && composer.contains(target)) && !paletteOpen;
  }

  document.addEventListener(
    'keydown',
    function (e: KeyboardEvent) {
      const key = e.key;
      const isF = key === 'f' || key === 'F' || e.keyCode === 70;
      const isO = key === 'o' || key === 'O' || e.keyCode === 79;
      if (isF && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
        e.preventDefault();
        openFindBar();
      } else if (isO && (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey) {
        if (isTextField(e.target)) return;
        e.preventDefault();
        if (cc.toggleReasoning) cc.toggleReasoning();
      } else if ((key === 'Escape' || e.keyCode === 27) && ownsEscape(e)) {
        e.preventDefault();
        e.stopPropagation();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        closeFindBar();
      }
    },
    true
  );
})();
