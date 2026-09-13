(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const send = CX.send;

  AT.attachImageFile = function (file: File | null | undefined): void {
    if (!file) return;
    const name = file.name != null ? String(file.name) : 'image';
    const reader = new FileReader();
    function failed(): void {
      if (CC.announce) CC.announce('The image ' + name + ' could not be read and was not attached.');
      send({ type: 'diag', report: 'attach: the browser could not read ' + name });
    }
    reader.onerror = failed;
    reader.onload = function () {
      const result = reader.result;
      if (typeof result !== 'string') return;
      const comma = result.indexOf(',');
      const base64 = comma >= 0 ? result.slice(comma + 1) : result;
      send({
        type: 'attach',
        name: name,
        mediaType: file.type != null ? String(file.type) : 'application/octet-stream',
        base64: base64,
      });
    };
    try {
      reader.readAsDataURL(file);
    } catch (e) {
      failed();
    }
  };

  AT.isImageFile = function (f: File | null | undefined): boolean {
    return !!(f && typeof f.type === 'string' && f.type.indexOf('image/') === 0);
  };

  CX.wireImageDrop = function (card: HTMLElement | null): void {
    if (!card) return;
    card.addEventListener('dragover', function (e: DragEvent) {
      if (e.preventDefault) e.preventDefault();
      if (e.dataTransfer) {
        try {
          e.dataTransfer.dropEffect = 'copy';
        } catch (x) {}
      }
      card.classList.add('drag-over');
    });
    card.addEventListener('dragleave', function (e: DragEvent) {
      if (e && e.target === card) card.classList.remove('drag-over');
    });
    card.addEventListener('drop', function (e: DragEvent) {
      if (e.preventDefault) e.preventDefault();
      card.classList.remove('drag-over');
      const dt = e.dataTransfer;
      if (!dt || !dt.files) return;
      for (let i = 0; i < dt.files.length; i++) {
        if (AT.isImageFile(dt.files[i])) AT.attachImageFile(dt.files[i]);
      }
    });
  };

  CX.insertAtCursor = function (input: HTMLInputElement | HTMLTextAreaElement, text: string): void {
    const start = input.selectionStart != null ? input.selectionStart : input.value.length;
    const end = input.selectionEnd != null ? input.selectionEnd : input.value.length;
    const v = input.value;
    input.value = v.slice(0, start) + text + v.slice(end);
    const pos = start + text.length;
    try {
      input.setSelectionRange(pos, pos);
    } catch (e) {}
    CX.autosize(input);
  };

  CX.wireImagePaste = function (input: HTMLTextAreaElement | null): void {
    if (!input) return;
    input.addEventListener('paste', function (e: ClipboardEvent) {
      if (CX.hostClipboard) {
        e.preventDefault();
        send({ type: 'pasteClipboard' });
        return;
      }

      const cd = e.clipboardData || (window as unknown as { clipboardData?: DataTransfer }).clipboardData;
      if (!cd) return;

      const images: File[] = [];
      const items = cd.items;
      if (items) {
        for (let i = 0; i < items.length; i++) {
          const it = items[i];
          if (it && it.kind === 'file' && typeof it.type === 'string' && it.type.indexOf('image/') === 0) {
            const f = it.getAsFile();
            if (f) images.push(f);
          }
        }
      }
      if (images.length === 0 && cd.files && cd.files.length) {
        for (let j = 0; j < cd.files.length; j++) {
          if (AT.isImageFile(cd.files[j])) images.push(cd.files[j]);
        }
      }
      if (images.length > 0) {
        e.preventDefault();
        for (let k = 0; k < images.length; k++) AT.attachImageFile(images[k]);
        return;
      }

      let text: string;
      try {
        text = (cd.getData && (cd.getData('text/plain') || cd.getData('text'))) || '';
      } catch (x) {
        text = '';
      }
      if (text) {
        e.preventDefault();
        CX.insertAtCursor(input, text);
        return;
      }

      e.preventDefault();
      send({ type: 'pasteClipboard' });
    });
  };
})();
