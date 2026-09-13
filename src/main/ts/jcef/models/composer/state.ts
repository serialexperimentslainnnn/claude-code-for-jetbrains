(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const CX = (CC.composer = CC.composer || ({} as ComposerNs));

  function h(tag: string, props?: HProps | null, ...children: Child[]): HTMLElement {
    if (CC && typeof CC.h === 'function') {
      return CC.h(tag, props || null, ...children);
    }
    const el = document.createElement(tag);
    const p = (props || {}) as Record<string, unknown>;
    if (p.class) el.className = String(p.class);
    if (p.text != null) el.textContent = String(p.text);
    if (p.html != null) el.innerHTML = String(p.html);
    if (p.title != null) el.title = String(p.title);
    const attrs = p.attrs as Record<string, string> | undefined;
    if (attrs)
      for (const k in attrs) if (Object.prototype.hasOwnProperty.call(attrs, k)) el.setAttribute(k, attrs[k]);
    const on = p.on as Record<string, EventListener> | undefined;
    if (on)
      for (const ev in on) if (Object.prototype.hasOwnProperty.call(on, ev)) el.addEventListener(ev, on[ev]);
    for (const c of children) {
      if (c == null) continue;
      if (Array.isArray(c)) {
        for (const m of c)
          if (m != null) el.appendChild(typeof m === 'string' ? document.createTextNode(m) : (m as Node));
      } else el.appendChild(typeof c === 'string' ? document.createTextNode(c) : (c as Node));
    }
    return el;
  }
  function send(obj: unknown): void {
    if (CC && typeof CC.send === 'function') CC.send(obj);
  }
  CX.h = h;
  CX.send = send;

  CX.els = null;
  CX.lastState = null;
  CX.hostClipboard = false;

  CX.roving = function (rows: () => HTMLElement[]): Roving {
    function set(row: HTMLElement | null | undefined): void {
      const all = rows();
      for (let i = 0; i < all.length; i++) all[i].setAttribute('tabindex', all[i] === row ? '0' : '-1');
    }
    function focus(row: HTMLElement | null | undefined): void {
      set(row);
      if (row) row.focus();
    }
    function step(delta: number): void {
      const all = rows();
      if (!all.length) return;
      const at = all.indexOf(document.activeElement as HTMLElement);
      focus(all[at < 0 ? (delta > 0 ? 0 : all.length - 1) : (at + delta + all.length) % all.length]);
    }
    return { set: set, focus: focus, step: step };
  };
})();
