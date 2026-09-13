(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const TX = (CC.transcript = CC.transcript || ({} as TranscriptNs));

  const conversationEl = TX.conversationEl;
  const rows = TX.rows;
  const toolCards = TX.toolCards;

  function emptyEl(): HTMLElement | null {
    return document.getElementById('empty');
  }

  function upsert(entry: TranscriptEntry | null | undefined): RowRec | null {
    if (entry == null || entry.id == null) {
      return null;
    }

    if (entry.speaker === 'TOOL_OUTPUT') {
      if (TX.routeToolOutput(entry)) {
        return rows.get(entry.id) || null;
      }
    }

    let rec = rows.get(entry.id) || null;
    if (rec && rec.speaker !== entry.speaker) {
      if (rec.el && rec.el.parentNode) {
        rec.el.parentNode.removeChild(rec.el);
      }
      if (rec.toolUseId) {
        toolCards.delete(rec.toolUseId);
      }
      rows.delete(entry.id);
      rec = null;
    }
    if (!rec) {
      rec = TX.createRow(entry);
      rows.set(entry.id, rec);
    }
    TX.updateRow(rec, entry);
    return rec;
  }

  function containerFor(entry: TranscriptEntry): HTMLElement | null {
    if (entry.parent) {
      const parentCard = toolCards.get(entry.parent);
      if (parentCard) {
        return (
          parentCard.__childrenNode ||
          parentCard.querySelector<HTMLElement>('.tool-children') ||
          conversationEl()
        );
      }
    }
    return conversationEl();
  }

  function reposition(entry: TranscriptEntry): void {
    const rec = rows.get(entry.id);
    if (!rec || !rec.el) {
      return;
    }
    const order = entry.order;
    rec.el.__order = typeof order === 'number' && order >= 0 ? order : null;
    const container = containerFor(entry);
    if (!container) {
      return;
    }

    let ref: RowEl | null = null;
    if (rec.el.__order != null) {
      const kids = container.children;
      for (let i = 0; i < kids.length; i++) {
        const k = kids[i] as RowEl;
        if (k === rec.el) {
          continue;
        }
        if (k.__order == null) {
          continue;
        }
        if (k.__order > rec.el.__order) {
          ref = k;
          break;
        }
      }
    }
    if (rec.el.parentNode === container && rec.el.nextSibling === ref) {
      return;
    }
    if (ref) {
      container.insertBefore(rec.el, ref);
    } else {
      container.appendChild(rec.el);
    }
  }

  function hasRows(c: HTMLElement): boolean {
    for (let i = 0; i < c.children.length; i++) {
      if (c.children[i].id !== 'empty') return true;
    }
    return false;
  }

  function showEmptyState(show: boolean): void {
    const empty = emptyEl();
    if (empty) {
      empty.hidden = !show;
    }
  }

  cc.batch = function (input?: unknown): void {
    if (!input) {
      return;
    }
    let entries: TranscriptEntry[];
    if (Array.isArray(input)) {
      entries = input as TranscriptEntry[];
    } else {
      const wrapped = input as { entries?: unknown };
      entries = Array.isArray(wrapped.entries)
        ? (wrapped.entries as TranscriptEntry[])
        : [input as TranscriptEntry];
    }
    const c = conversationEl();
    const stick = TX.stickToBottom();

    for (let i = 0; i < entries.length; i++) {
      upsert(entries[i]);
    }
    for (let j = 0; j < entries.length; j++) {
      const e = entries[j];
      if (e && e.id != null && e.speaker !== 'TOOL_OUTPUT') {
        reposition(e);
      } else if (e && e.id != null && e.speaker === 'TOOL_OUTPUT' && rows.has(e.id)) {
        reposition(e);
      }
    }

    if (rows.size > 0 || (c && hasRows(c))) {
      showEmptyState(false);
    }

    TX.refreshSearch();

    TX.scheduleScroll(stick);
  };

  cc.clear = function (): void {
    rows.clear();
    toolCards.clear();
    const c = conversationEl();
    if (c) {
      const kids = Array.prototype.slice.call(c.children) as Element[];
      for (let i = 0; i < kids.length; i++) {
        if (kids[i].id === 'empty') {
          continue;
        }
        c.removeChild(kids[i]);
      }
    }
    TX.resetSearch();
    showEmptyState(true);
  };
})();
