(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));
  const AT = (CX.attach = CX.attach || ({} as AttachNs));

  const h = CX.h;
  const send = CX.send;

  let attachmentsList: Attachment[] = [];

  function renderAttachments(list: Attachment[]): void {
    const els = CX.els;
    if (!els || !els.attachments) return;
    const row = els.attachments;
    row.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
      row.setAttribute('hidden', 'hidden');
      return;
    }
    row.removeAttribute('hidden');
    for (let i = 0; i < list.length; i++) {
      const att = list[i];
      if (!att || att.id == null) continue;
      const kind = att.kind != null ? String(att.kind) : 'file';
      const label = att.label != null ? String(att.label) : '';
      const icon = h('span', { class: 'att-icon', html: AT.attIconGlyph(kind) });
      const name = h('span', { class: 'att-label', text: label });
      const x = h('span', {
        class: 'att-x',
        text: '✕',
        title: 'Remove attachment',
        attrs: { role: 'button', 'aria-label': 'Remove attachment' },
        on: {
          click: function (e: Event) {
            e.preventDefault();
            e.stopPropagation();
            send({ type: 'removeAttachment', id: att.id });
          },
        },
      });
      const chip = h('span', { class: 'att-chip att-' + kind, title: label }, icon, name, x);
      row.appendChild(chip);
    }
  }
  CX.renderAttachments = function (): void {
    renderAttachments(attachmentsList);
  };

  cc.attachments = function (list?: unknown): void {
    attachmentsList = Array.isArray(list) ? (list.slice() as Attachment[]) : [];
    if (!CX.ensureBuilt()) return;
    renderAttachments(attachmentsList);
  };
})();
