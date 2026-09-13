(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const PM = (CC.permissions = CC.permissions || ({} as PermissionsNs));

  const h = CC.h;

  interface FieldMeta {
    input: HTMLInputElement;
    kind: string;
    required: boolean;
  }

  function fieldKind(f: ElicitField): string {
    const t = f && f.type != null ? String(f.type).toLowerCase() : 'string';
    if (t === 'number') return 'number';
    if (t === 'integer' || t === 'int') return 'integer';
    if (t === 'boolean' || t === 'bool') return 'boolean';
    return 'string';
  }

  PM.buildElicitCard = function (card: PermissionCard): HTMLElement {
    const id = card.id;
    const e: Elicitation = card.elicitation || {};

    const fields = Array.isArray(e.fields)
      ? e.fields.filter(function (f) {
          return f && f.name != null && String(f.name) !== '';
        })
      : [];
    const isForm = e.mode === 'form' || fields.length > 0;
    const isUrl = e.mode === 'url' && PM.isHttpUrl(e.url);

    const bodyChildren: HTMLElement[] = [];
    if (e.description) bodyChildren.push(h('div', { class: 'elicit-desc', text: String(e.description) }));

    if (isUrl) {
      const url = String(e.url).trim();
      bodyChildren.push(
        h('a', {
          text: url,
          attrs: { href: '#' },
          on: {
            click: function (ev: Event) {
              if (ev && ev.preventDefault) ev.preventDefault();
              PM.send({ type: 'open', url: url });
            },
          },
        })
      );
    }

    const fieldMeta: Record<string, FieldMeta> = {};
    let acceptBtn: HTMLButtonElement | null = null;

    if (isForm && fields.length) {
      const fieldEls = fields.map(function (f) {
        const name = String(f.name);
        const kind = fieldKind(f);
        const required = !!(f && f.required);
        let titleText = f && f.title != null && f.title !== '' ? String(f.title) : name;
        if (required) titleText += ' *';

        let inputType = 'text';
        if (kind === 'number' || kind === 'integer') inputType = 'number';
        else if (kind === 'boolean') inputType = 'checkbox';

        const input = h('input', { attrs: { type: inputType, name: name } }) as HTMLInputElement;
        input.addEventListener('input', refreshAcceptState);
        input.addEventListener('change', refreshAcceptState);
        fieldMeta[name] = { input: input, kind: kind, required: required };

        return h('label', null, h('span', { class: 'elicit-field-label', text: titleText }), input);
      });
      bodyChildren.push(h('div', { class: 'elicit-fields' }, fieldEls));
    }

    function requiredSatisfied(): boolean {
      const names = Object.keys(fieldMeta);
      for (let i = 0; i < names.length; i++) {
        const meta = fieldMeta[names[i]];
        if (!meta || !meta.required) continue;
        const input = meta.input;
        if (meta.kind === 'boolean') {
          if (!input.checked) return false;
        } else if (String(input.value == null ? '' : input.value).trim() === '') {
          return false;
        }
      }
      return true;
    }

    function refreshAcceptState(): void {
      if (!acceptBtn) return;
      const ok = !isForm || requiredSatisfied();
      acceptBtn.disabled = !ok;
      if (ok) acceptBtn.removeAttribute('disabled');
      else acceptBtn.setAttribute('disabled', '');
    }

    function collectContent(): Record<string, unknown> {
      const content: Record<string, unknown> = {};
      Object.keys(fieldMeta).forEach(function (name) {
        const meta = fieldMeta[name];
        if (!meta) return;
        const input = meta.input;
        if (meta.kind === 'boolean') {
          content[name] = !!input.checked;
        } else if (meta.kind === 'number' || meta.kind === 'integer') {
          const raw = input.value;
          content[name] = raw == null || String(raw).trim() === '' ? null : Number(raw);
        } else {
          content[name] = input.value != null ? String(input.value) : '';
        }
      });
      return content;
    }

    function resolve(action: string): void {
      const msg: Record<string, unknown> = { type: 'resolveElicitation', id: id, action: action };
      if (action === 'accept') msg.content = collectContent();
      PM.sendFor(card, msg);
    }

    const serverName = e.serverName != null ? String(e.serverName) : card.title || 'Server';
    const message = e.message != null ? String(e.message) : card.summary || '';

    acceptBtn = PM.button({ class: 'btn primary', text: 'Accept' }, function () {
      if (acceptBtn && !acceptBtn.disabled) resolve('accept');
    }) as HTMLButtonElement;

    const root = h(
      'div',
      { class: 'perm-card elicit-card' },
      h('div', { class: 'perm-head', text: 'MCP request' }),
      serverName ? h('div', { class: 'elicit-server', text: serverName }) : null,
      h(
        'div',
        { class: 'perm-body' },
        message ? h('div', { class: 'elicit-msg', text: String(message) }) : null,
        bodyChildren.length ? h('div', { class: 'elicit-extra' }, bodyChildren) : null
      ),
      h(
        'div',
        { class: 'perm-actions' },
        acceptBtn,
        PM.button({ class: 'btn ghost', text: 'Decline' }, function () {
          resolve('decline');
        }),
        PM.button({ class: 'btn ghost', text: 'Cancel' }, function () {
          resolve('cancel');
        })
      )
    );

    refreshAcceptState();
    return root;
  };
})();
