(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  function el(tag: string, props?: HProps | null): HTMLElement {
    if (CC.h) {
      return CC.h(tag, props);
    }
    const node = document.createElement(tag);
    const p = props || {};
    if (p.class) {
      node.className = String(p.class);
    }
    if (p.text != null) {
      node.textContent = String(p.text);
    }
    if (p.html != null) {
      node.innerHTML = String(p.html);
    }
    if (p.title != null) {
      node.title = String(p.title);
    }
    const attrs = p.attrs as Record<string, unknown> | undefined;
    if (attrs) {
      for (const a in attrs) {
        if (Object.prototype.hasOwnProperty.call(attrs, a)) {
          node.setAttribute(a, String(attrs[a]));
        }
      }
    }
    const on = p.on as Record<string, EventListener> | undefined;
    if (on) {
      for (const ev in on) {
        if (Object.prototype.hasOwnProperty.call(on, ev)) {
          node.addEventListener(ev, on[ev]);
        }
      }
    }
    return node;
  }
  function esc(text: unknown): string {
    if (CC.escape) {
      return CC.escape(text == null ? '' : text);
    }
    const d = document.createElement('div');
    d.textContent = text == null ? '' : String(text);
    return d.innerHTML;
  }
  function md(text: unknown, hostLinks: boolean): string {
    if (CC.markdown) {
      try {
        return CC.markdown(text == null ? '' : text, { hostLinks: hostLinks });
      } catch (e) {}
    }
    return esc(text);
  }
  function safeSend(obj: unknown): void {
    if (CC.send) {
      try {
        CC.send(obj);
      } catch (e) {}
    }
  }
  function conversationEl(): HTMLElement | null {
    const node = (CC.els && CC.els.conversation) || document.getElementById('conversation');
    return node || null;
  }

  const rows = new Map<unknown, RowRec>();
  const toolCards = new Map<string, RowEl>();

  TX.el = el;
  TX.safeSend = safeSend;
  TX.conversationEl = conversationEl;
  TX.rows = rows;
  TX.toolCards = toolCards;

  TX.setBody = function (rec: RowRec, text: unknown): void {
    const body = rec.bodyNode;
    if (!body) {
      return;
    }
    const kind = rec.kind;
    if (kind === 'md') {
      body.innerHTML = md(text, rec.speaker === 'USER');
      body.__rawText = text == null ? '' : String(text);
    } else if (kind === 'pre') {
      body.textContent = text == null ? '' : String(text);
    } else {
      body.textContent = text == null ? '' : String(text);
      body.__rawText = text == null ? '' : String(text);
    }
  };
})();
