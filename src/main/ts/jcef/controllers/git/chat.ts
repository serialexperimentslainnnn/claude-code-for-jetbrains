(function () {
  'use strict';

  const cc = (window.cc = window.cc || {});
  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const h = D.h;

  let pane: HTMLElement | null = null;
  let rowsEl: HTMLElement | null = null;
  let statusEl: HTMLElement | null = null;

  const rows = new Map<unknown, RowRec>();
  const cards = new Map<string, RowEl>();

  let last: { rows?: unknown; starting?: boolean } | null = null;
  let drawn = false;

  function tx(): TranscriptNs | null {
    return CC.transcript || null;
  }

  function gitChatPane(): HTMLElement | null {
    if (pane) return pane;
    if (typeof h !== 'function') return null;

    rowsEl = h('div', {
      class: 'gitchat-rows',
      attrs: { role: 'log', 'aria-label': 'Git conversation', 'aria-live': 'off' },
    });
    statusEl = h('div', { class: 'gitchat-status', attrs: { role: 'status', 'aria-live': 'polite' } });

    pane = h(
      'div',
      { class: 'gitchat', attrs: { hidden: '' } },
      typeof D.gitViewTabs === 'function' ? D.gitViewTabs('chat') : null,
      rowsEl,
      statusEl
    );
    return pane;
  }

  CC.gitChatActive = function (): boolean {
    return (
      typeof D.gitSubView === 'function' &&
      D.gitSubView() === 'chat' &&
      typeof D.dashboardShown === 'function' &&
      D.dashboardShown()
    );
  };

  function draw(): void {
    if (!pane) return;
    drawn = true;
    const payload = last;

    if (!payload) {
      clearRows();
      setStatus('');
      return;
    }

    renderRows(Array.isArray(payload.rows) ? (payload.rows as TranscriptEntry[]) : []);

    setStatus(payload.starting ? 'Starting Claude for this repository…' : '');
  }

  function setStatus(text: string): void {
    if (!statusEl) return;
    statusEl.textContent = text;
    statusEl.hidden = !text;
  }

  function clearRows(): void {
    rows.clear();
    cards.clear();
    if (!rowsEl) return;
    while (rowsEl.firstChild) rowsEl.removeChild(rowsEl.firstChild);
  }

  function renderRows(entries: TranscriptEntry[]): void {
    const T = tx();
    if (!rowsEl || !T || typeof T.createRow !== 'function') return;

    const stick = nearBottom();
    const ordered: HTMLElement[] = [];
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      if (!entry || entry.id == null) continue;

      if (entry.speaker === 'TOOL_OUTPUT' && T.routeToolOutput(entry, cards)) continue;

      let rec = rows.get(entry.id);
      if (rec && rec.speaker !== entry.speaker) {
        if (rec.el && rec.el.parentNode) rec.el.parentNode.removeChild(rec.el);
        rows.delete(entry.id);
        rec = undefined;
      }
      if (!rec) {
        rec = T.createRow(entry, cards);
        rows.set(entry.id, rec);
      }
      T.updateRow(rec, entry, false);
      if (rec.el) ordered.push(rec.el);
    }

    place(ordered);
    if (stick) rowsEl.scrollTop = rowsEl.scrollHeight;
  }

  function place(ordered: HTMLElement[]): void {
    if (!rowsEl) return;
    for (let i = 0; i < ordered.length; i++) {
      if (rowsEl.children[i] !== ordered[i]) {
        rowsEl.insertBefore(ordered[i], rowsEl.children[i] || null);
      }
    }
    while (rowsEl.children.length > ordered.length && rowsEl.lastChild) {
      rowsEl.removeChild(rowsEl.lastChild);
    }
  }

  const NEAR_BOTTOM = 60;
  function nearBottom(): boolean {
    if (!rowsEl) return true;
    return rowsEl.scrollHeight - rowsEl.scrollTop - rowsEl.clientHeight <= NEAR_BOTTOM;
  }

  D.gitChatPane = gitChatPane;

  D.gitChatShown = function (): void {
    if (!drawn) draw();
  };

  cc.gitChat = function (payload?: unknown): void {
    last =
      payload && typeof payload === 'object' ? (payload as { rows?: unknown; starting?: boolean }) : null;
    drawn = false;
    const open = typeof D.gitSubView === 'function' && D.gitSubView() === 'chat';
    const shown = typeof D.dashboardShown === 'function' && D.dashboardShown();
    if (pane && open && shown) draw();
  };
})();
