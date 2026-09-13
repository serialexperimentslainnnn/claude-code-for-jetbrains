(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const L = (D.log = D.log || ({} as LogNs));

  const LOG_POLL_MS = 1000;
  const RANK: Record<string, number> = { warn: 0, info: 1, debug: 2 };

  L.lines = [];
  L.lastSeq = -1;
  L.level = 'all';
  L.debug = false;
  L.ring = { max: 0, dropped: 0 };
  L.listEl = null;
  L.entriesEl = null;
  L.emptyEl = null;

  let open = false;
  let timer: ReturnType<typeof setInterval> | null = null;

  L.text = function (value: unknown, fallback: string): string {
    return value == null || value === '' ? fallback : String(value);
  };

  L.num = function (value: unknown): number {
    return typeof value === 'number' && isFinite(value) ? value : 0;
  };

  L.request = function (): void {
    D.send({ type: 'logLines', since: L.lastSeq });
  };

  L.shows = function (line: LogLine): boolean {
    if (L.level === 'all') return true;
    const rank = RANK[L.text(line.level, '')];
    return rank != null && rank <= RANK[L.level];
  };

  L.absorb = function (payload: LogPayload): void {
    if (payload.reset) {
      L.lines = [];
      L.lastSeq = -1;
      L.reset();
    }
    const added: LogLine[] = [];
    (Array.isArray(payload.lines) ? payload.lines : []).forEach(function (line) {
      if (!line) return;
      const seq = L.num(line.seq);
      if (seq <= L.lastSeq) return;
      L.lines.push(line);
      L.lastSeq = seq;
      added.push(line);
    });
    L.debug = payload.debug === true;
    const ring = payload.ring || {};
    L.ring = { max: L.num(ring.max), dropped: L.num(ring.dropped) };
    L.append(added);
  };

  L.setVisible = function (visible: boolean): void {
    if (visible === open) return;
    open = visible;
    if (open) {
      L.request();
      timer = setInterval(L.request, LOG_POLL_MS);
      return;
    }
    if (timer != null) {
      clearInterval(timer);
      timer = null;
    }
  };

  L.when = function (at: unknown): string {
    const n = L.num(at);
    if (!n) return '';
    try {
      return new Date(n).toLocaleTimeString();
    } catch (e) {
      return String(n);
    }
  };

  L.repaint = function (): void {
    if (typeof D.repaintLog === 'function') D.repaintLog();
  };
})();
