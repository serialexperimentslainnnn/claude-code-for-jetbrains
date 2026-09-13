(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  AT.data = { recent: [], hasSelection: false, hasFile: false };
  AT.view = 'root';
  AT.tree = null;

  CX.attachGlyph = function (): string {
    return (
      '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M12.75 7.5 7.5 12.75a3 3 0 0 1-4.25-4.25l5.75-5.75a2 2 0 0 1 2.83 2.83l-5.75 5.75a1 1 0 0 1-1.42-1.42l5.09-5.09"/></svg>'
    );
  };

  AT.attIconGlyph = function (kind: string): string {
    if (kind === 'image') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<rect x="3" y="4" width="18" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/>' +
        '<circle cx="8.5" cy="9" r="1.6" fill="currentColor"/>' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="m4 18 5-5 4 4 3-3 4 4"/></svg>'
      );
    }
    if (kind === 'selection') {
      return (
        '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
        '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
        'd="M4 7V5a1 1 0 0 1 1-1h2M4 17v2a1 1 0 0 0 1 1h2M20 7V5a1 1 0 0 0-1-1h-2M20 17v2a1 1 0 0 1-1 1h-2M8 12h8"/></svg>'
      );
    }
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" ' +
      'd="M6 2h7l5 5v15a0 0 0 0 1 0 0H6a0 0 0 0 1 0 0V2Z"/>' +
      '<path fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" d="M13 2v5h5"/></svg>'
    );
  };

  AT.folderGlyph = function (): string {
    return (
      '<svg viewBox="0 0 24 24" width="13" height="13" fill="currentColor" aria-hidden="true">' +
      '<path d="M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>'
    );
  };

  AT.fileIconGlyph = function (ext: string | undefined): string {
    const e = (ext || '').toLowerCase();
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].indexOf(e) !== -1)
      return AT.attIconGlyph('image');
    return AT.attIconGlyph('file');
  };

  AT.extOf = function (name: unknown): string {
    const s = String(name || '');
    const dot = s.lastIndexOf('.');
    return dot > 0 ? s.slice(dot + 1) : '';
  };

  AT.isText = function (v: unknown): v is string {
    return typeof v === 'string' && v !== '';
  };
})();
