(function () {
  'use strict';

  const CC = (window.CC = window.CC || ({} as CcShared));
  const D = (CC.dash = CC.dash || ({} as DashNs));
  const GL = (D.guardLog = D.guardLog || ({} as GuardLogNs));

  GL.payload = null;
  GL.tab = 'blocked';
  GL.queryRaw = '';
  GL.query = '';
  GL.pickedCategories = null;
  GL.pickedRules = null;

  GL.text = function (value: unknown, fallback: string): string {
    return value == null || value === '' ? fallback : String(value);
  };

  GL.textOrNull = function (value: unknown): string | null {
    return value == null || value === '' ? null : String(value);
  };

  GL.list = function <T>(value: unknown): T[] {
    return Array.isArray(value) ? (value.filter(Boolean) as T[]) : [];
  };

  GL.num = function (value: unknown): number {
    return typeof value === 'number' && isFinite(value) ? value : 0;
  };

  GL.tabs = function (): GuardLogTab[] {
    return GL.list<GuardLogTab>(GL.payload && GL.payload.tabs);
  };

  function entriesFor(id: string): GuardLogEntry[] {
    return GL.list<GuardLogEntry>(GL.payload && GL.payload.entries).filter(function (e) {
      return GL.text(e.tab, '') === id;
    });
  }

  GL.catalog = function (): GuardLogCategory[] {
    return GL.list<GuardLogCategory>(GL.payload && GL.payload.catalog);
  };

  GL.rulesOfCategories = function (picked: string[] | null): { id: string; label: string }[] {
    const out: { id: string; label: string }[] = [];
    GL.catalog().forEach(function (c) {
      if (picked && picked.indexOf(GL.text(c.id, '')) < 0) return;
      GL.list<{ id?: unknown; label?: unknown }>(c.rules).forEach(function (r) {
        out.push({ id: GL.text(r.id, ''), label: GL.text(r.label, GL.text(r.id, '')) });
      });
    });
    return out;
  };

  function matchesQuery(entry: GuardLogEntry): boolean {
    if (!GL.query) return true;
    const hay = [entry.ruleLabel, entry.category, entry.command, entry.detail, entry.tool, entry.verdictLabel]
      .map(function (v) {
        return GL.text(v, '').toLowerCase();
      })
      .join(' ');
    return hay.indexOf(GL.query) >= 0;
  }

  function matchesFilters(entry: GuardLogEntry): boolean {
    if (GL.pickedCategories && GL.pickedCategories.indexOf(GL.text(entry.categoryId, '')) < 0) return false;
    if (GL.pickedRules && GL.pickedRules.indexOf(GL.text(entry.rule, '')) < 0) return false;
    return true;
  }

  GL.visibleEntries = function (id: string): GuardLogEntry[] {
    return entriesFor(id).filter(function (e) {
      return matchesQuery(e) && matchesFilters(e);
    });
  };

  function knownTab(id: string): boolean {
    let found = false;
    GL.tabs().forEach(function (t) {
      if (GL.text(t.id, '') === id) found = true;
    });
    return found;
  }

  GL.currentTab = function (): string {
    const first = GL.tabs();
    return knownTab(GL.tab) ? GL.tab : GL.text(first.length ? first[0].id : '', 'blocked');
  };

  GL.when = function (at: unknown): string {
    const n = GL.num(at);
    if (!n) return '';
    try {
      return new Date(n).toLocaleString();
    } catch (e) {
      return String(n);
    }
  };

  GL.filtering = function (): boolean {
    return !!GL.query || !!GL.pickedCategories || !!GL.pickedRules;
  };

  GL.repaint = function (): void {
    if (typeof D.repaintGuard === 'function') D.repaintGuard();
  };
})();
