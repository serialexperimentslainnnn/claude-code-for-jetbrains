(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));

  function core(): CcShared | null {
    return window.CC || null;
  }
  function conversation(): HTMLElement | null {
    const c = core();
    return (c && c.els && c.els.conversation) || document.getElementById('conversation') || null;
  }
  function appRoot(): HTMLElement | null {
    const c = core();
    return (c && c.els && c.els.app) || document.getElementById('app') || document.body || null;
  }
  function h(tag: string, props?: HProps | null, ...children: Child[]): HTMLElement {
    return CC.h(tag, props || null, ...children);
  }
  function send(obj: unknown): void {
    const c = core();
    if (c && typeof c.send === 'function') c.send(obj);
  }

  function num(v: unknown): number | null {
    return typeof v === 'number' && isFinite(v) ? v : null;
  }

  function fmtInt(v: unknown): string | null {
    const n = num(v);
    if (n == null) return null;
    try {
      return Math.round(n).toLocaleString();
    } catch (e) {
      return String(Math.round(n));
    }
  }

  function fmtUsd(v: unknown): string | null {
    const n = num(v);
    if (n == null) return null;
    return '$' + n.toFixed(n < 1 ? 4 : 2);
  }

  function statRow(label: string, value: unknown): HTMLElement | null {
    if (value == null || value === '') return null;
    return h(
      'div',
      { class: 'stat-row' },
      h('span', { class: 'stat-label', text: label }),
      h('span', { class: 'stat-value', text: String(value) })
    );
  }

  function card(title: string, body: unknown, wide?: boolean, anchor?: string): HTMLElement | null {
    const children: Child[] = [];
    if (Array.isArray(body)) {
      for (let i = 0; i < body.length; i++) {
        if (body[i]) children.push(body[i] as Child);
      }
    } else if (body) {
      children.push(body as Child);
    }
    if (!children.length) return null;
    const head = h('div', { class: 'dash-title', text: title });
    const props: HProps = { class: 'dash-card' + (wide ? ' wide' : '') };
    props.attrs = { 'data-card': anchor || slug(title) };
    return h('div', props, head, children);
  }

  function slug(title: string): string {
    return String(title == null ? 'card' : title)
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
  }

  D.core = core;
  D.conversation = conversation;
  D.appRoot = appRoot;
  D.h = h;
  D.send = send;
  D.num = num;
  D.fmtInt = fmtInt;
  D.fmtUsd = fmtUsd;
  D.statRow = statRow;
  D.card = card;

  D.leaveDashboard = function () {};
})();
