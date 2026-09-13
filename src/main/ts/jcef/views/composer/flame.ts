(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  const OUTER =
    'M8 1.2c.4 2.3 2.1 3.4 3.3 5 1.2 1.6 1.7 3.1 1.2 4.9-.6 2.1-2.4 3.5-4.5 3.5S4.1 13.3 3.5 11.1' +
    'c-.5-1.9.2-3.6 1.4-4.9.3.9.9 1.5 1.7 1.7-.5-2.5.2-4.6 1.4-6.7z';

  const CORE = 'M8 8.6c.9 1 1.6 1.8 1.5 2.9-.1 1-.7 1.7-1.5 1.7s-1.4-.7-1.5-1.7c-.1-1 .6-1.9 1.5-2.9z';

  const UNLIT =
    '<path d="' +
    OUTER +
    '" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round"/>' +
    '<path class="flame-core" d="' +
    CORE +
    '" stroke="currentColor" stroke-width=".8" opacity=".6"/>';

  const LIT =
    '<path d="' +
    OUTER +
    '" fill="currentColor"/>' +
    '<path class="flame-core lit" d="' +
    CORE +
    '" fill="#ffe27a"/>';

  CX.flameGlyph = function (lit: boolean): string {
    return '<svg viewBox="0 0 16 16" fill="none" aria-hidden="true">' + (lit ? LIT : UNLIT) + '</svg>';
  };
})();
