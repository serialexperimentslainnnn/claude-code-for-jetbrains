(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const PA = (CX.palette = CX.palette || ({} as PaletteNs));

  let dismissed = false;

  function currentQuery(): string {
    const input = PA.composerInput();
    return PA.queryOf(input && input.value) || '';
  }

  function openPalette(): void {
    const p = PA.ensureBuilt();
    if (!p) return;
    dismissed = false;
    p.removeAttribute('hidden');
    PA.state.active = 0;
    PA.state.navigated = false;
    filterPalette(currentQuery());
    PA.linkInput(true);
  }
  CX.openPalette = openPalette;

  function hidePalette(): void {
    const p = PA.paletteEl();
    if (!p) return;
    p.setAttribute('hidden', 'hidden');
    PA.linkInput(false);
    const input = PA.composerInput();
    if (input && input.value === '/') {
      input.value = '';
      CX.autosize(input);
    }
    if (input) input.focus();
  }

  function filterPalette(q: string): void {
    const p = PA.paletteEl();
    if (!p || !p.__list) return;
    PA.state.items = PA.rank((q || '').toLowerCase().replace(/^\//, ''));
    PA.state.active = 0;
    PA.state.navigated = false;
    PA.renderList(pickPalette);
  }

  function movePaletteActive(delta: number): void {
    const n = PA.state.items.length;
    if (!n) return;
    if (!PA.state.navigated) {
      PA.state.navigated = true;
      PA.state.active = delta > 0 ? 0 : n - 1;
    } else {
      PA.state.active = (PA.state.active + delta + n) % n;
    }
    PA.updateActiveClass();
    const p = PA.paletteEl();
    if (p && p.__list) {
      const row = p.__list.querySelectorAll('.palette-item')[PA.state.active];
      if (row && row.scrollIntoView) row.scrollIntoView({ block: 'nearest' });
    }
  }

  function pickPaletteActive(): void {
    if (!PA.state.items.length) {
      hidePalette();
      return;
    }
    pickPalette(PA.state.active);
  }

  function pickPalette(idx: number): void {
    const it = PA.state.items[idx];
    hidePalette();
    if (!it) return;
    if (!CX.ensureBuilt() || !CX.els || !CX.els.input) return;
    const input = CX.els.input;
    input.value = '/' + it.name + ' ';
    CX.autosize(input);
    input.focus();
    try {
      const len = input.value.length;
      input.setSelectionRange(len, len);
    } catch (e) {}
  }

  document.addEventListener(
    'keydown',
    function (e: KeyboardEvent) {
      if (!PA.isOpen() || e.target !== PA.composerInput()) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        dismissed = true;
        hidePalette();
        return;
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        e.stopPropagation();
        movePaletteActive(e.key === 'ArrowDown' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter' && !e.shiftKey) {
        if (!PA.state.navigated) {
          hidePalette();
          return;
        }
        e.preventDefault();
        e.stopPropagation();
        pickPaletteActive();
      }
    },
    true
  );

  document.addEventListener(
    'input',
    function (e: Event) {
      if (e.target !== PA.composerInput()) return;
      const q = PA.queryOf((e.target as HTMLTextAreaElement).value);
      if (q === null) {
        dismissed = false;
        if (PA.isOpen()) hidePalette();
        return;
      }
      if (dismissed) return;
      if (PA.isOpen()) filterPalette(q);
      else openPalette();
    },
    true
  );

  CX.setCommands = function (list: PaletteCommand[]): void {
    PA.setCommands(list);
    if (PA.isOpen()) filterPalette(currentQuery());
  };

  cc.openPalette = function (): void {
    if (!CX.ensureBuilt()) return;
    const input = PA.composerInput();
    if (input) {
      if (PA.queryOf(input.value) === null) {
        input.value = '/';
        CX.autosize(input);
      }
      input.focus();
      try {
        const len = input.value.length;
        input.setSelectionRange(len, len);
      } catch (e) {}
    }
    openPalette();
  };
})();
