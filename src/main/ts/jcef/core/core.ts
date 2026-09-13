(function () {
  'use strict';

  const cc = window.cc || (window.cc = {});
  const CC = window.CC || (window.CC = {} as CcShared);

  CC.send = function (obj: unknown): void {
    try {
      const payload = JSON.stringify(obj);
      if (typeof window.__ccSend === 'function') {
        window.__ccSend(payload);
      }
    } catch (e) {}
  };

  CC.escape = function (s: unknown): string {
    if (s === null || s === undefined) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  };

  function own(obj: object, key: string): boolean {
    return Object.prototype.hasOwnProperty.call(obj, key);
  }

  function entries(val: unknown): Record<string, unknown> {
    return val && typeof val === 'object' ? (val as Record<string, unknown>) : {};
  }

  CC.h = function (tag: string, props?: HProps | null, ...children: Child[]): HTMLElement {
    const el = document.createElement(tag);
    if (props) {
      for (const key in props) {
        if (!own(props, key)) continue;
        const val = props[key];
        if (val === null || val === undefined) continue;
        if (key === 'class' || key === 'className') {
          el.className = val as string;
        } else if (key === 'text') {
          el.textContent = val as string;
        } else if (key === 'html') {
          el.innerHTML = val as string;
        } else if (key === 'title') {
          el.setAttribute('title', val as string);
        } else if (key === 'style') {
          const style = el.style as unknown as Record<string, unknown>;
          const rules = entries(val);
          for (const sp in rules) {
            if (own(rules, sp) && rules[sp] != null) {
              try {
                style[sp] = rules[sp];
              } catch (e) {}
            }
          }
        } else if (key === 'attrs') {
          const attrs = entries(val);
          for (const a in attrs) {
            if (own(attrs, a) && attrs[a] != null) {
              el.setAttribute(a, attrs[a] as string);
            }
          }
        } else if (key === 'on') {
          const handlers = entries(val);
          for (const ev in handlers) {
            if (own(handlers, ev) && typeof handlers[ev] === 'function') {
              el.addEventListener(ev, handlers[ev] as EventListener);
            }
          }
        } else if (key === 'dataset') {
          const data = entries(val);
          for (const d in data) {
            if (own(data, d) && data[d] != null) {
              el.dataset[d] = data[d] as string;
            }
          }
        } else {
          if (val === true) {
            el.setAttribute(key, '');
          } else if (val !== false) {
            el.setAttribute(key, val as string);
          }
        }
      }
    }
    appendChildren(el, children);
    return el;
  };

  function appendChildren(el: HTMLElement, children: Child[]): void {
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) {
        appendChildren(el, child);
      } else if (typeof child === 'string' || typeof child === 'number') {
        el.appendChild(document.createTextNode(String(child)));
      } else if ((child as Node).nodeType) {
        el.appendChild(child as Node);
      }
    }
  }

  function minutesUntil(iso: string | null | undefined): number | null {
    if (!iso) return null;
    const when = Date.parse(iso);
    if (isNaN(when)) return null;
    return Math.round((when - Date.now()) / 60000);
  }
  CC.resetInShort = function (iso: string | null | undefined): string | null {
    const mins = minutesUntil(iso);
    if (mins === null) return null;
    if (mins <= 0) return 'soon';
    const hours = Math.floor(mins / 60);
    return hours > 0 ? hours + 'h ' + (mins % 60) + 'm' : mins + 'm';
  };
  CC.resetIn = function (iso: string | null | undefined): string | null {
    const short = CC.resetInShort(iso);
    if (short === null) return null;
    return short === 'soon' ? 'Resets shortly' : 'Resets in ' + short;
  };

  type Listener = (...args: unknown[]) => void;
  const listeners: Record<string, Listener[]> = {};
  CC.on = function (event: string, fn: Listener): () => void {
    if (typeof fn !== 'function') return function () {};
    (listeners[event] || (listeners[event] = [])).push(fn);
    return function off() {
      const arr = listeners[event];
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    };
  };
  CC.emit = function (event: string, ...args: unknown[]): void {
    const arr = listeners[event];
    if (!arr || !arr.length) return;
    const snapshot = arr.slice();
    for (let i = 0; i < snapshot.length; i++) {
      try {
        snapshot[i].apply(null, args);
      } catch (e) {}
    }
  };

  function byId(id: string): HTMLElement | null {
    return document.getElementById(id);
  }
  CC.els = {
    app: byId('app'),
    conversation: byId('conversation'),
    permissions: byId('permissions'),
    composer: byId('composer'),
    palette: byId('palette'),
    a11yStatus: byId('a11y-status'),
  };

  let lastAnnouncement = '';
  CC.announce = function (message: unknown): void {
    const el = CC.els && CC.els.a11yStatus;
    if (!el) return;
    const text = message == null ? '' : String(message);
    if (text === lastAnnouncement) return;
    lastAnnouncement = text;
    el.textContent = text;
  };

  const covering: Record<string, true> = {};
  CC.coverTranscript = function (owner: string, covered: boolean): void {
    if (covered) covering[owner] = true;
    else delete covering[owner];
    const el = CC.els && CC.els.conversation;
    if (!el) return;
    if (Object.keys(covering).length) el.setAttribute('inert', '');
    else el.removeAttribute('inert');
  };

  if (typeof cc.batch !== 'function') cc.batch = function () {};
  if (typeof cc.clear !== 'function') cc.clear = function () {};
  if (typeof cc.state !== 'function') cc.state = function () {};
  if (typeof cc.meta !== 'function') cc.meta = function () {};
  if (typeof cc.permissions !== 'function') cc.permissions = function () {};
  if (typeof cc.openPalette !== 'function') cc.openPalette = function () {};
  if (typeof cc.focusInput !== 'function') cc.focusInput = function () {};
  if (typeof cc.insertText !== 'function') cc.insertText = function () {};
  if (typeof cc.openDashboard !== 'function') cc.openDashboard = function () {};
  if (typeof cc.attachData !== 'function') cc.attachData = function () {};
  if (typeof cc.attachments !== 'function') cc.attachments = function () {};
  if (typeof cc.session !== 'function') cc.session = function () {};
  if (typeof cc.mcp !== 'function') cc.mcp = function () {};
  if (typeof cc.toggleReasoning !== 'function') cc.toggleReasoning = function () {};

  function announceReady(): void {
    let tries = 0;
    (function attempt() {
      if (typeof window === 'undefined') return;
      if (typeof window.__ccSend === 'function') {
        CC.send({ type: 'ready' });
        try {
          CC.diagnostics();
          CC.selfCheck();
        } catch (e) {}
        return;
      }
      if (tries++ < 200) {
        setTimeout(attempt, 50);
      }
    })();
  }
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(announceReady, 0);
  } else {
    window.addEventListener('DOMContentLoaded', function () {
      setTimeout(announceReady, 0);
    });
  }
})();
